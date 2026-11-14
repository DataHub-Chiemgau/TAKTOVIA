package de.taktovia.bookingdispatch.internal

import de.taktovia.bookingdispatch.api.DueSignal
import de.taktovia.bookingdispatch.api.FlagCause
import de.taktovia.bookingdispatch.api.FlagEvaluation
import de.taktovia.bookingdispatch.api.OperatorFlagItem
import de.taktovia.bookingdispatch.api.OperatorFlagView
import de.taktovia.bookingdispatch.api.OperatorFlags
import de.taktovia.bookingdispatch.api.SignalSource
import de.taktovia.bookingdispatch.api.ThresholdSource
import de.taktovia.platform.alert.BusinessAlertPort
import de.taktovia.platform.alert.BusinessAlertRequest
import de.taktovia.platform.authz.ServiceScopeAuthorizer
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.platform.tenant.TenantId
import org.springframework.jdbc.core.JdbcOperations
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Auswertung und Lesen der Hinweise (WP-M3-42) aus `operator_flag` (eigene Tabelle), `route_stop`, `vehicle_run`, `booking`, `journey_leg` und `vehicle_pool_assignment` (nur lesend). Die Lease des
 * Auswertungslaufs ist die transaktionsbezogene Advisory-Sperre `pg_try_advisory_xact_lock` je Mandant: der Lauf besteht aus genau einer Transaktion, die Sperre fällt mit ihr; ein zweiter gleichzeitiger
 * Lauf bekommt sie nicht und überspringt. Unabhängig davon verhindert der Teilindex `operator_flag_one_open` einen zweiten offenen Hinweis desselben Sachverhalts.
 */
internal class JdbcOperatorFlags(
    private val ctxTx: ContextTransactions, private val clock: Clock, private val thresholds: ThresholdSource, private val signals: SignalSource, private val alerts: BusinessAlertPort?,
) : OperatorFlags {

    /** Eine Bedingung dieses Laufs: Ursache, Dienst, Fahrzeug, Umlauf, betroffene Buchungen (sortiert), Auslöseschwelle. */
    private class Cond(val cause: FlagCause, val serviceId: UUID?, val vehicleId: UUID?, val runId: UUID?, val key: String, val affected: List<UUID>, val thresholdMin: Int?)
    private class Open(val id: UUID, val key: String, val threshold: Int?, val affected: List<UUID>)

    override fun evaluate(tenant: TenantId): FlagEvaluation {
        val now = clock.instant()
        // Meldungen anderer Module vor der eigenen Transaktion holen (eigene Transaktionen dort, keine geschachtelten)
        val due = signals.due(tenant, now)
        return ctxTx.runSystem(tenant) { j ->
            if (j.queryForObject("SELECT pg_try_advisory_xact_lock(hashtextextended(? || CAST(taktovia.current_tenant() AS text), 0))", Boolean::class.java, LOCK_NAME) != true)
                return@runSystem FlagEvaluation(0, 0, 0, skipped = true)
            val open = j.query(
                "SELECT id, dedupe_key, threshold_min, affected_bookings FROM taktovia.operator_flag WHERE state = 'OPEN' ORDER BY opened_at, id FOR UPDATE",
                { rs, _ -> Open(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getObject(3) as Int?, uuids(rs.getArray(4))) },
            ).associateBy { it.key }
            val conds = pickupOverdue(j, tenant, now, open) + signalConds(j, tenant, now, due)
            var opened = 0; var updated = 0; var resolved = 0
            for (c in conds.distinctBy { it.key }) {
                val existing = open[c.key]
                if (existing == null) { if (insert(j, c, now)) opened++ }
                else if (existing.affected != c.affected) {
                    j.update("UPDATE taktovia.operator_flag SET affected_bookings = CAST(? AS uuid[]), affected_count = ?, last_evaluated_at = ?, revision = revision + 1 WHERE id = ? AND state = 'OPEN'",
                        arr(c.affected), c.affected.size, Timestamp.from(now), existing.id)
                    updated++
                }
            }
            val live = conds.map { it.key }.toSet()
            for (o in open.values.filter { it.key !in live }) {
                j.update("UPDATE taktovia.operator_flag SET state = 'RESOLVED', resolved_at = ?, last_evaluated_at = ?, revision = revision + 1 WHERE id = ? AND state = 'OPEN'", Timestamp.from(now), Timestamp.from(now), o.id)
                alerts?.resolve(j, alertKey(o.key))
                resolved++
            }
            FlagEvaluation(opened, updated, resolved)
        }
    }

    /**
     * Überfällige Abholungen: bestätigte Buchungen, deren geplanter Einstieg im aktuellen Plan des Umlaufs länger als die Schwelle des Dienstes zurückliegt, ohne dass der Fahrgast eingestiegen ist.
     * Gruppiert je Umlauf und Dienst (die Betroffenenzahl zählt nur Buchungen des Dienstes). Für einen bereits offenen Hinweis gilt dessen eigene, beim Auslösen gespeicherte Schwelle (nicht rückwirkend).
     */
    private fun pickupOverdue(j: JdbcOperations, tenant: TenantId, now: Instant, open: Map<String, Open>): List<Cond> {
        class Row(val booking: UUID, val service: UUID, val run: UUID, val vehicle: UUID, val plannedAt: Instant)
        val rows = j.query(
            "SELECT bk.id, bk.service_id, r.id, r.vehicle_id, rs.planned_at FROM taktovia.route_stop rs " +
                "JOIN taktovia.vehicle_run r ON r.tenant_id = rs.tenant_id AND r.id = rs.run_id AND r.revision = rs.run_revision AND r.state IN ('PLANNED', 'ACTIVE', 'LOCKED', 'IN_PROGRESS') " +
                "CROSS JOIN LATERAL jsonb_array_elements(rs.boardings) AS b(item) " +
                "JOIN taktovia.booking bk ON bk.tenant_id = rs.tenant_id AND bk.id = CAST(b.item ->> 'booking_id' AS uuid) " +
                "JOIN taktovia.journey_leg jl ON jl.tenant_id = bk.tenant_id AND jl.journey_id = bk.journey_id AND jl.execution_state = 'PLANNED' " +
                "WHERE bk.state = 'CONFIRMED' AND rs.planned_at < ? ORDER BY r.id, bk.service_id, bk.id",
            { rs, _ -> Row(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getObject(3, UUID::class.java), rs.getObject(4, UUID::class.java), rs.getTimestamp(5).toInstant()) }, Timestamp.from(now),
        )
        return rows.groupBy { it.run to it.service }.mapNotNull { (k, group) ->
            val key = "PICKUP_OVERDUE|run|${k.first}|${k.second}"
            val threshold = open[key]?.threshold ?: thresholds.minutes(tenant, k.second, FlagCause.PICKUP_OVERDUE)
            val late = group.filter { it.plannedAt.plus(Duration.ofMinutes(threshold.toLong())).isBefore(now) }
            if (late.isEmpty()) null else Cond(FlagCause.PICKUP_OVERDUE, k.second, group.first().vehicle, k.first, key, late.map { it.booking }.sorted(), threshold)
        }
    }

    /** Meldungen anderer Module je Dienst: betroffen sind die aktiven Buchungen des Umlaufs des Fahrzeugs im jeweiligen Dienst; ohne Buchungen je Dienst des Fahrzeugpools (sonst mandantenweit). */
    private fun signalConds(j: JdbcOperations, tenant: TenantId, now: Instant, due: List<DueSignal>): List<Cond> = due.flatMap { s ->
        val run = s.runId ?: j.query(
            "SELECT r.id FROM taktovia.vehicle_run r JOIN taktovia.staff_shift sh ON sh.tenant_id = r.tenant_id AND sh.id = r.shift_id WHERE r.vehicle_id = ? AND r.state IN ('PLANNED', 'ACTIVE', 'LOCKED', 'IN_PROGRESS') " +
                "ORDER BY (r.state = 'IN_PROGRESS') DESC, sh.starts_at DESC, r.id LIMIT 1", { rs, _ -> rs.getObject(1, UUID::class.java) }, s.vehicleId).firstOrNull()
        class Bk(val id: UUID, val service: UUID)
        val bookings = if (run == null) emptyList() else j.query(
            "SELECT DISTINCT bk.id, bk.service_id FROM taktovia.route_stop rs JOIN taktovia.vehicle_run r ON r.tenant_id = rs.tenant_id AND r.id = rs.run_id AND r.revision = rs.run_revision " +
                "CROSS JOIN LATERAL (SELECT item FROM jsonb_array_elements(rs.boardings || rs.alightings) AS x(item)) b " +
                "JOIN taktovia.booking bk ON bk.tenant_id = rs.tenant_id AND bk.id = CAST(b.item ->> 'booking_id' AS uuid) WHERE rs.run_id = ? AND bk.state IN ('CONFIRMING', 'CONFIRMED') ORDER BY bk.id",
            { rs, _ -> Bk(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java)) }, run)
        val byService: Map<UUID?, List<UUID>> = if (bookings.isNotEmpty()) bookings.groupBy({ it.service }, { it.id })
        else j.query("SELECT DISTINCT service_id FROM taktovia.vehicle_pool_assignment WHERE vehicle_id = ? AND valid_from <= ? AND (valid_to IS NULL OR valid_to > ?) ORDER BY service_id",
            { rs, _ -> rs.getObject(1, UUID::class.java) }, s.vehicleId, Timestamp.from(now), Timestamp.from(now)).ifEmpty { listOf(null) }.associateWith { emptyList<UUID>() }
        byService.map { (service, affected) -> Cond(s.cause, service, s.vehicleId, run, "${s.cause.name}|vehicle|${s.vehicleId}|${service ?: "-"}", affected.sorted(), s.thresholdMin) }
    }

    /** Legt den Hinweis an und löst den Alarm aus (Alarm zuerst: ein Alarm gleichen Schlüssels besteht höchstens einmal offen). Liefert falsch, wenn ein anderer Lauf den offenen Hinweis schon angelegt hat. */
    private fun insert(j: JdbcOperations, c: Cond, now: Instant): Boolean {
        val id = UUID.randomUUID()
        val alertId = alerts?.raise(j, BusinessAlertRequest(
            kind = if (c.cause.kind == "OVERDUE") "OVERDUE" else "INFO_PENDING", dedupeKey = alertKey(c.key), vehicleId = c.vehicleId, serviceId = c.serviceId, refType = "operator_flag", refId = id, reasonCode = c.cause.name,
        ))?.alertId
        return j.update(
            "INSERT INTO taktovia.operator_flag(tenant_id, id, service_id, vehicle_id, run_id, kind, cause, dedupe_key, affected_bookings, affected_count, threshold_min, alert_id, opened_at, last_evaluated_at) " +
                "VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?, ?, CAST(? AS uuid[]), ?, ?, ?, ?, ?) ON CONFLICT (tenant_id, dedupe_key) WHERE state = 'OPEN' DO NOTHING",
            id, c.serviceId, c.vehicleId, c.runId, c.cause.kind, c.cause.name, c.key, arr(c.affected), c.affected.size, c.thresholdMin, alertId, Timestamp.from(now), Timestamp.from(now),
        ) == 1
    }

    // ---- Lesen --------------------------------------------------------------------------------------------------------------------------

    private fun requireHuman(ctx: RequestContext) { if (ctx.actor.type != ActorType.HUMAN) throw ProblemException(ErrorCode.FORBIDDEN) }

    override fun list(ctx: RequestContext, state: String?, kind: String?, serviceId: UUID?, limit: Int, after: Pair<Instant, UUID>?): List<OperatorFlagItem> {
        requireHuman(ctx)
        if (state != null && state !in setOf("OPEN", "RESOLVED")) throw ProblemException.invalid(Violation("/\$query/state", ViolationCode.NOT_IN_ENUM))
        if (kind != null && kind !in setOf("OVERDUE", "INFO_PENDING")) throw ProblemException.invalid(Violation("/\$query/kind", ViolationCode.NOT_IN_ENUM))
        require(limit in 1..101) { "limit" }
        return ctxTx.run(ctx, readOnly = true) { j ->
            val scope = ServiceScopeAuthorizer.currentServices(j, ctx).joinToString(",", "{", "}")   // nur UUIDs aus dem Scope der Mitgliedschaften
            j.query(
                "SELECT id, kind, cause, state, service_id, vehicle_id, affected_count, opened_at, resolved_at, threshold_min, revision FROM taktovia.operator_flag " +
                    "WHERE state = ? AND (service_id IS NULL OR service_id = ANY(CAST(? AS uuid[]))) AND (CAST(? AS text) IS NULL OR kind = CAST(? AS text)) " +
                    "AND (CAST(? AS uuid) IS NULL OR service_id = CAST(? AS uuid)) AND (CAST(? AS timestamptz) IS NULL OR (opened_at, id) > (CAST(? AS timestamptz), CAST(? AS uuid))) ORDER BY opened_at, id LIMIT ?",
                { rs, _ -> OperatorFlagItem(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3), rs.getString(4), rs.getObject(5, UUID::class.java), rs.getObject(6, UUID::class.java), rs.getInt(7),
                    rs.getTimestamp(8).toInstant(), rs.getTimestamp(9)?.toInstant(), rs.getObject(10) as Int?, rs.getLong(11)) },
                state ?: "OPEN", scope, kind, kind, serviceId?.toString(), serviceId?.toString(), after?.let { Timestamp.from(it.first) }, after?.let { Timestamp.from(it.first) }, after?.second?.toString(), limit + 1,
            )
        }
    }

    override fun forVehicles(ctx: RequestContext, vehicleIds: Collection<UUID>): Map<UUID, List<OperatorFlagView>> {
        requireHuman(ctx)
        if (vehicleIds.isEmpty()) return emptyMap()
        return ctxTx.run(ctx, readOnly = true) { j ->
            val scope = ServiceScopeAuthorizer.currentServices(j, ctx).joinToString(",", "{", "}")
            class Row(val vehicle: UUID, val view: OperatorFlagView)
            j.query(
                "SELECT vehicle_id, id, kind, cause, affected_count, opened_at, threshold_min FROM taktovia.operator_flag WHERE state = 'OPEN' AND vehicle_id = ANY(CAST(? AS uuid[])) " +
                    "AND (service_id IS NULL OR service_id = ANY(CAST(? AS uuid[]))) ORDER BY opened_at, id",
                { rs, _ -> Row(rs.getObject(1, UUID::class.java), OperatorFlagView(rs.getObject(2, UUID::class.java), rs.getString(3), rs.getString(4), rs.getInt(5), rs.getTimestamp(6).toInstant(), rs.getObject(7) as Int?)) },
                vehicleIds.joinToString(",", "{", "}"), scope,
            ).groupBy({ it.vehicle }, { it.view })
        }
    }

    private fun uuids(a: java.sql.Array?): List<UUID> = a?.let { (it.array as Array<*>).map { v -> v as UUID }.sorted() } ?: emptyList()
    private fun arr(ids: List<UUID>) = ids.joinToString(",", "{", "}")
    private fun alertKey(flagKey: String) = "flag|$flagKey"

    private companion object { const val LOCK_NAME = "operator_flags:" }
}

package de.taktovia.analytics.internal

import de.taktovia.analytics.api.CauseCategory
import de.taktovia.analytics.api.CauseCorrection
import de.taktovia.analytics.api.CauseCount
import de.taktovia.analytics.api.CauseState
import de.taktovia.analytics.api.CorrectCause
import de.taktovia.analytics.api.MetricFilter
import de.taktovia.analytics.api.MetricReason
import de.taktovia.analytics.api.MetricState
import de.taktovia.analytics.api.NotPerformedCause
import de.taktovia.analytics.api.NotPerformedReport
import de.taktovia.analytics.api.NotPerformedTrip
import de.taktovia.analytics.api.NotPerformedTrips
import de.taktovia.analytics.api.Rate
import de.taktovia.analytics.api.ReportingPeriod
import de.taktovia.platform.api.Etag
import de.taktovia.platform.audit.ActorRef
import de.taktovia.platform.audit.AuditActions
import de.taktovia.platform.audit.AuditEntry
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.audit.ObjectRef
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.money.MoneyRounding
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import org.springframework.jdbc.core.JdbcOperations
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Nicht durchgeführte Fahrten auf PostgreSQL (WP-M4-55). Der Fakt `not_performed_trip` wird von der Funktion `not_performed_refresh` aus dem Buchungsfakt abgeleitet (Aufruf durch die Faktenprojektion); die Korrektur
 * läuft unter der Zeilensperre der Fahrt: je Fahrt fortlaufende Vermerke (`correction_no`), der alte Wert ist der zum Zeitpunkt der Serialisierung wirksame, der letzte Vermerk bestimmt den Stand.
 */
internal class JdbcNotPerformedTrips(private val tx: TenantTransactions, private val ctxTx: ContextTransactions, private val audit: AuditPort, private val clock: Clock) : NotPerformedTrips {

    override fun causes(tenant: TenantId): List<NotPerformedCause> = tx.run(tenant) { j ->
        j.queryForObject("SELECT taktovia.seed_not_performed_causes()", Int::class.java)   // Arbeitsvorschlag des Katalogs beim ersten Zugriff des Mandanten
        j.query("SELECT code, category, label, derived_from, valid_from, valid_to FROM taktovia.not_performed_cause ORDER BY code",
            { rs, _ -> NotPerformedCause(rs.getString(1), CauseCategory.valueOf(rs.getString(2)), rs.getString(3), rs.getString(4), rs.getTimestamp(5).toInstant().takeIf { it.isAfter(Instant.MIN.plusSeconds(1L shl 40)) }, rs.getTimestamp(6)?.toInstant()) })
    }

    override fun report(tenant: TenantId, period: ReportingPeriod, filter: MetricFilter): NotPerformedReport {
        require(filter.channel == null && filter.vehicleId == null && filter.energy == null && filter.currency == null) { "Filterdimension für nicht durchgeführte Fahrten nicht zulässig" }
        return tx.run(tenant, readOnly = true) { j ->
            val from = Timestamp.from(period.from); val to = Timestamp.from(period.to); val svc = filter.serviceId?.toString()
            val rows = j.query(
                "SELECT t.cause, t.category, c.label, t.cause_state, count(*) FROM taktovia.not_performed_trip t LEFT JOIN taktovia.not_performed_cause c ON c.code = t.cause " +
                    "WHERE coalesce(t.pickup_earliest, t.not_performed_at) >= ? AND coalesce(t.pickup_earliest, t.not_performed_at) < ? AND (CAST(? AS uuid) IS NULL OR t.service_id = CAST(? AS uuid)) " +
                    "GROUP BY t.cause, t.category, c.label, t.cause_state ORDER BY t.cause NULLS LAST, t.cause_state",
                { rs, _ -> Row(rs.getString(1), rs.getString(2)?.let(CauseCategory::valueOf), rs.getString(3), CauseState.valueOf(rs.getString(4)), rs.getLong(5)) }, from, to, svc, svc)
            val byCause = rows.filter { it.cause != null }.groupBy { it.cause!! }.map { (cause, rs) -> CauseCount(cause, rs.first().category!!, rs.first().label ?: cause, rs.sumOf { it.n }) }
            val byCategory = CauseCategory.entries.associateWith { cat -> byCause.filter { it.category == cat }.sumOf { it.count } }
            val denominators = j.query(
                "SELECT count(*) FILTER (WHERE b.state <> 'CANCELED'), count(*) FROM taktovia.fact_booking b WHERE b.confirmed_at IS NOT NULL AND coalesce(b.pickup_earliest, b.confirmed_at) >= ? AND coalesce(b.pickup_earliest, b.confirmed_at) < ? " +
                    "AND (CAST(? AS uuid) IS NULL OR b.service_id = CAST(? AS uuid))", { rs, _ -> rs.getLong(1) to rs.getLong(2) }, from, to, svc, svc).single()
            val wm = j.query("SELECT max(processed_until), coalesce(bool_or(completeness = 'INCOMPLETE'), false), coalesce(sum(gap_count), 0), coalesce(sum(event_count), 0), count(*) FROM taktovia.dataset_watermark WHERE source IN ('BOOKING', 'DRIVER_EVENT')",
                { rs, _ -> Watermark(rs.getTimestamp(1)?.toInstant(), rs.getBoolean(2), rs.getLong(3), rs.getLong(4), rs.getLong(5) > 0) }).single()
            fun count(cause: String) = byCause.firstOrNull { it.cause == cause }?.count ?: 0L
            val operatorCancelled = count("OPERATOR_CANCELLATION") + count("SAFETY_CANCELLATION")
            val state = when { !wm.hasData -> MetricState.NOT_COMPUTABLE; wm.incomplete -> MetricState.PROVISIONAL; else -> MetricState.OK }
            NotPerformedReport(period, filter, rows.sumOf { it.n }, byCause, byCategory, rows.filter { it.state == CauseState.OPEN }.sumOf { it.n }, rows.filter { it.state == CauseState.CONFLICT }.sumOf { it.n },
                denominators.first, denominators.second, rate(count("PASSENGER_NO_SHOW"), denominators.first), rate(operatorCancelled, denominators.second), rate(count("VEHICLE_OUTAGE"), denominators.second),
                state, if (!wm.hasData) null else if (wm.incomplete) BigDecimal.ZERO.setScale(4) else BigDecimal.ONE.setScale(4), wm.asOf, wm.events)
        }
    }

    private class Row(val cause: String?, val category: CauseCategory?, val label: String?, val state: CauseState, val n: Long)
    private class Watermark(val asOf: Instant?, val incomplete: Boolean, val gaps: Long, val events: Long, val hasData: Boolean)

    private fun rate(num: Long, den: Long) = if (den == 0L) Rate(num, den, null, MetricReason.ZERO_DENOMINATOR) else Rate(num, den, MoneyRounding.ratioHalfUp(BigDecimal(num), BigDecimal(den), 6), null)

    override fun trips(tenant: TenantId, period: ReportingPeriod, filter: MetricFilter, onlyOpen: Boolean, limit: Int): List<NotPerformedTrip> = tx.run(tenant, readOnly = true) { j ->
        val svc = filter.serviceId?.toString()
        val sql = "SELECT booking_id, service_id, confirmed_at, not_performed_at, cause_state, cause, category, derived_cause, revision FROM taktovia.not_performed_trip " +
            "WHERE coalesce(pickup_earliest, not_performed_at) >= ? AND coalesce(pickup_earliest, not_performed_at) < ? AND (CAST(? AS uuid) IS NULL OR service_id = CAST(? AS uuid)) " +
            "AND (NOT ? OR cause_state IN ('OPEN', 'CONFLICT')) ORDER BY not_performed_at, booking_id LIMIT ?"
        val trips = j.query(sql, TRIP, Timestamp.from(period.from), Timestamp.from(period.to), svc, svc, onlyOpen, limit.coerceIn(1, 1000))   // sql-lint: ok Konstante ohne Eingabewerte, Werte gebunden
        trips.map { it.copy(corrections = corrections(j, it.bookingId)) }
    }

    override fun get(tenant: TenantId, bookingId: UUID): NotPerformedTrip = tx.run(tenant, readOnly = true) { j -> load(j, bookingId, false) }

    private fun load(j: JdbcOperations, bookingId: UUID, lock: Boolean): NotPerformedTrip {
        val base = "SELECT booking_id, service_id, confirmed_at, not_performed_at, cause_state, cause, category, derived_cause, revision FROM taktovia.not_performed_trip WHERE booking_id = ?"
        val t = (if (lock) j.query("$base FOR UPDATE", TRIP, bookingId) else j.query(base, TRIP, bookingId)).firstOrNull() ?: throw ProblemException.notFound()   // sql-lint: ok Zusatz aus Konstante, Wert gebunden
        return t.copy(corrections = corrections(j, bookingId))
    }

    private fun corrections(j: JdbcOperations, bookingId: UUID): List<CauseCorrection> = j.query(
        "SELECT correction_no, old_cause, old_category, new_cause, new_category, reason, actor, created_at FROM taktovia.not_performed_correction WHERE booking_id = ? ORDER BY correction_no",
        { rs, _ -> CauseCorrection(rs.getInt(1), rs.getString(2), rs.getString(3)?.let(CauseCategory::valueOf), rs.getString(4), CauseCategory.valueOf(rs.getString(5)), rs.getString(6), rs.getString(7), rs.getTimestamp(8).toInstant()) }, bookingId)

    override fun correct(ctx: RequestContext, bookingId: UUID, cmd: CorrectCause): NotPerformedTrip {
        if (ctx.actor.type != ActorType.HUMAN || ctx.actor.sessionId == null || !ctx.hasScope("analytics:write") || ctx.roles.none { it in CORRECTORS }) throw ProblemException(ErrorCode.FORBIDDEN)
        val reason = cmd.reason.trim()
        if (reason.isEmpty()) throw ProblemException.invalid(Violation("/reason", ViolationCode.REQUIRED))
        if (reason.length < 3) throw ProblemException.invalid(Violation("/reason", ViolationCode.TOO_SHORT))
        if (reason.length > 500) throw ProblemException.invalid(Violation("/reason", ViolationCode.TOO_LONG))
        return ctxTx.run(ctx) { j ->
            val now = clock.instant()
            j.queryForObject("SELECT taktovia.seed_not_performed_causes()", Int::class.java)
            val cur = load(j, bookingId, true)   // Zeilensperre: gleichzeitige Korrekturen laufen nacheinander; fremde oder unbekannte Fahrt: 404
            val cat = j.query("SELECT category FROM taktovia.not_performed_cause WHERE code = ? AND valid_from <= ? AND (valid_to IS NULL OR valid_to > ?)", { rs, _ -> CauseCategory.valueOf(rs.getString(1)) },
                cmd.newCause, Timestamp.from(now), Timestamp.from(now)).firstOrNull() ?: throw ProblemException.invalid(Violation("/new_cause", ViolationCode.NOT_IN_ENUM))
            if (cmd.ifMatch != null && cmd.ifMatch != cur.revision) throw ProblemException(ErrorCode.REVISION_MISMATCH, "Die Fahrt wurde zwischenzeitlich geändert; aktuelle Revision ${cur.revision} lesen.", headers = mapOf("ETag" to Etag.format(cur.revision)))
            if (cur.cause == cmd.newCause) throw ProblemException.invalid(Violation("/new_cause", ViolationCode.INVALID_VALUE, "Die Ursache ist bereits gesetzt."))
            val no = (cur.corrections.maxOfOrNull { it.no } ?: 0) + 1
            val actor = "user:" + ctx.actor.subject
            j.update("INSERT INTO taktovia.not_performed_correction(tenant_id, id, booking_id, correction_no, old_cause, old_category, new_cause, new_category, reason, actor) VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), bookingId, no, cur.cause, cur.category?.name, cmd.newCause, cat.name, reason, actor)
            check(j.update("UPDATE taktovia.not_performed_trip SET cause_state = 'CORRECTED', cause = ?, category = ?, revision = revision + 1, updated_at = ? WHERE booking_id = ? AND revision = ?",
                cmd.newCause, cat.name, Timestamp.from(now), bookingId, cur.revision) == 1) { "Fahrt nicht aktualisierbar" }
            audit.record(j, AuditEntry(ActorRef(actor), AuditActions.ANALYTICS_NOT_PERFORMED_CORRECTED, ObjectRef("booking", bookingId), ctx.correlationId,
                mapOf("old_cause" to cur.cause, "new_cause" to cmd.newCause, "correction_no" to no)))
            load(j, bookingId, false)
        }
    }

    private companion object {
        val CORRECTORS = setOf("dispatcher", "tenant_admin")
        val TRIP = org.springframework.jdbc.core.RowMapper { rs, _ ->
            NotPerformedTrip(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getTimestamp(3).toInstant(), rs.getTimestamp(4).toInstant(), CauseState.valueOf(rs.getString(5)), rs.getString(6),
                rs.getString(7)?.let(CauseCategory::valueOf), rs.getString(8), rs.getLong(9))
        }
    }
}

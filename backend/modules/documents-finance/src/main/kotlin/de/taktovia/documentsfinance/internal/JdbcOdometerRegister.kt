package de.taktovia.documentsfinance.internal

import de.taktovia.documentsfinance.api.ConflictDecision
import de.taktovia.documentsfinance.api.ConflictResolutionView
import de.taktovia.documentsfinance.api.OdometerConflictKind
import de.taktovia.documentsfinance.api.OdometerConflictStatus
import de.taktovia.documentsfinance.api.OdometerConflictView
import de.taktovia.documentsfinance.api.OdometerEvents
import de.taktovia.documentsfinance.api.OdometerPolicyView
import de.taktovia.documentsfinance.api.OdometerReadingView
import de.taktovia.documentsfinance.api.OdometerRecordOutcome
import de.taktovia.documentsfinance.api.OdometerRegister
import de.taktovia.documentsfinance.api.OdometerSource
import de.taktovia.documentsfinance.api.RecordOdometer
import de.taktovia.documentsfinance.api.ResolveConflict
import de.taktovia.documentsfinance.internal.DocumentRows.isFinance
import de.taktovia.platform.audit.ActorRef
import de.taktovia.platform.audit.AuditActions
import de.taktovia.platform.audit.AuditEntry
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.audit.AuditRequired
import de.taktovia.platform.audit.ObjectRef
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.outbox.OutboxEvent
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Kilometerstandsregister (WP-M4-41). Alle Schreibwege (API und Ereignisse) laufen über [recordIn]: Sperre je Fahrzeug, einmaliges Einfügen je Quellobjekt, Konflikterkennung gegen die schon
 * vorhandenen Stände. Nichts wird überschrieben; die Auflösung ist ein eigener Eintrag.
 */
internal class JdbcOdometerRegister(
    private val ctxTx: ContextTransactions, private val audit: AuditPort, private val outbox: Outbox, private val clock: Clock, private val telematicsProductive: Boolean = false,
) : OdometerRegister {

    override fun record(ctx: RequestContext, vehicleId: UUID, cmd: RecordOdometer): OdometerRecordOutcome {
        require(ctx, SCOPE_RECORD)
        validate(cmd, clock.instant(), telematicsProductive)
        return ctxTx.run(ctx) { j ->
            if (j.query("SELECT 1 FROM taktovia.vehicle WHERE id = ?", { _, _ -> 1 }, vehicleId).isEmpty()) throw ProblemException.notFound()
            recordIn(j, DocumentRows.actor(ctx), ctx.correlationId, vehicleId, cmd)
        }
    }

    /** Formale Prüfung mit der Uhr des Registers (Ereigniskonsum). */
    fun check(cmd: RecordOdometer) = validate(cmd, clock.instant(), telematicsProductive)

    /** Aufnahme im Mandanten der laufenden Transaktion; vom Ereigniskonsum ohne Personenkontext genutzt (Akteur: Dienst). Das Fahrzeug muss existieren (sonst Fremdschlüsselfehler). */
    @AuditRequired
    fun recordIn(j: JdbcOperations, actor: ActorRef, correlationId: UUID, vehicleId: UUID, cmd: RecordOdometer): OdometerRecordOutcome {
        // je Fahrzeug serialisiert: gleichzeitige Meldungen sehen einander (READ COMMITTED: die folgenden Abfragen sehen alles, was vor der Sperre committet wurde)
        j.query("SELECT pg_advisory_xact_lock(hashtextextended('odometer:' || taktovia.current_tenant()::text || ':' || ?, 0))", { _, _ -> 0 }, vehicleId.toString())
        val id = UUID.randomUUID()
        val inserted = j.update(
            "INSERT INTO taktovia.odometer_reading (tenant_id, id, vehicle_id, reading_km, source, source_ref, measured_at, recorded_by) VALUES (taktovia.current_tenant(),?,?,?,?,?,?,?) " +
                "ON CONFLICT (tenant_id, source, source_ref) DO NOTHING", id, vehicleId, cmd.readingKm, cmd.source.name, cmd.sourceRef, Timestamp.from(cmd.measuredAt), actor.value)
        if (inserted == 0) {   // dasselbe Quellereignis noch einmal zugestellt: der vorhandene Eintrag bleibt der einzige
            val existing = j.query("$READING_SQL WHERE r.source = ? AND r.source_ref = ?", READING_MAPPER, cmd.source.name, cmd.sourceRef).single()
            return OdometerRecordOutcome(existing, false, emptyList())
        }
        val reading = j.query("$READING_SQL WHERE r.id = ?", READING_MAPPER, id).single()
        audit.record(j, AuditEntry(actor, AuditActions.FINANCE_ODOMETER_RECORDED, ObjectRef("odometer_reading", id), correlationId,
            mapOf("vehicle_id" to vehicleId.toString(), "source" to cmd.source.name, "reading_km" to cmd.readingKm)))
        val policy = loadPolicy(j)
        val conflicts = detect(j, reading, policy).map { (kind, other) ->
            val (earlier, later) = if (order(other, reading) <= 0) other to reading else reading to other
            val cid = UUID.randomUUID()
            val ins = j.update("INSERT INTO taktovia.odometer_conflict (tenant_id, id, vehicle_id, kind, earlier_reading_id, later_reading_id, tolerance_km) VALUES (taktovia.current_tenant(),?,?,?,?,?,?) " +
                "ON CONFLICT (tenant_id, earlier_reading_id, later_reading_id, kind) DO NOTHING", cid, vehicleId, kind.name, earlier.id, later.id, policy.toleranceKm)
            if (ins == 0) null else {
                audit.record(j, AuditEntry(actor, AuditActions.FINANCE_ODOMETER_CONFLICT_DETECTED, ObjectRef("odometer_conflict", cid), correlationId,
                    mapOf("vehicle_id" to vehicleId.toString(), "kind" to kind.name, "earlier_reading_id" to earlier.id.toString(), "later_reading_id" to later.id.toString())))
                val now = clock.instant()
                outbox.append(j, OutboxEvent(cid, 1, OdometerEvents.CONFLICT_DETECTED, linkedMapOf("conflict_id" to cid.toString(), "vehicle_id" to vehicleId.toString(), "kind" to kind.name,
                    "earlier_reading_id" to earlier.id.toString(), "later_reading_id" to later.id.toString(), "tolerance_km" to policy.toleranceKm, "detected_at" to now.toString()), now, correlationId = correlationId))
                j.query("$CONFLICT_SQL WHERE c.id = ?", CONFLICT_MAPPER, cid).single()
            }
        }.filterNotNull()
        return OdometerRecordOutcome(reading, true, conflicts)
    }

    /**
     * Konflikte des neuen Stands gegen die Gegenstände (ohne in Auflösungen verworfene Stände): erst widersprüchliche Quellen im selben Zeitfenster (SOURCE_MISMATCH), dann der rückläufige Stand
     * gegenüber dem unmittelbaren Vorgänger und Nachfolger nach Messzeit (DECREASING), soweit das Paar nicht schon als SOURCE_MISMATCH erfasst ist. Abweichungen bis einschließlich der Toleranz sind keine Konflikte.
     */
    private fun detect(j: JdbcOperations, r: OdometerReadingView, policy: OdometerPolicyView): List<Pair<OdometerConflictKind, OdometerReadingView>> {
        val tol = policy.toleranceKm
        val window = Duration.ofMinutes(policy.windowMinutes.toLong())
        val out = ArrayList<Pair<OdometerConflictKind, OdometerReadingView>>()
        val flagged = HashSet<UUID>()
        j.query("$READING_SQL WHERE r.vehicle_id = ? AND r.id <> ? AND r.source <> ? AND r.measured_at BETWEEN ? AND ? AND $NOT_DISCARDED ORDER BY r.measured_at, r.id", READING_MAPPER,
            r.vehicleId, r.id, r.source.name, Timestamp.from(r.measuredAt.minus(window)), Timestamp.from(r.measuredAt.plus(window)))
            .filter { Math.abs(it.readingKm - r.readingKm) > tol }.forEach { out += OdometerConflictKind.SOURCE_MISMATCH to it; flagged += it.id }
        j.query("$READING_SQL WHERE r.vehicle_id = ? AND r.id <> ? AND $NOT_DISCARDED AND (r.measured_at, r.id) <= (?, CAST(? AS uuid)) ORDER BY r.measured_at DESC, r.id DESC LIMIT 1", READING_MAPPER,
            r.vehicleId, r.id, Timestamp.from(r.measuredAt), r.id).firstOrNull()
            ?.takeIf { it.id !in flagged && it.readingKm - r.readingKm > tol }?.let { out += OdometerConflictKind.DECREASING to it }
        j.query("$READING_SQL WHERE r.vehicle_id = ? AND r.id <> ? AND $NOT_DISCARDED AND (r.measured_at, r.id) > (?, CAST(? AS uuid)) ORDER BY r.measured_at, r.id LIMIT 1", READING_MAPPER,
            r.vehicleId, r.id, Timestamp.from(r.measuredAt), r.id).firstOrNull()
            ?.takeIf { it.id !in flagged && r.readingKm - it.readingKm > tol }?.let { out += OdometerConflictKind.DECREASING to it }
        return out
    }

    private fun order(a: OdometerReadingView, b: OdometerReadingView): Int = compareValuesBy(a, b, { it.measuredAt }, { it.id })

    override fun readings(ctx: RequestContext, vehicleId: UUID, limit: Int, after: Pair<Instant, UUID>?): List<OdometerReadingView> {
        require(ctx, SCOPE_READ); checkLimit(limit)
        return ctxTx.run(ctx, readOnly = true) { j ->
            vehicleOrNotFound(j, vehicleId)
            j.query("$READING_SQL WHERE r.vehicle_id = ? AND (CAST(? AS timestamptz) IS NULL OR (r.measured_at, r.id) > (?, CAST(? AS uuid))) ORDER BY r.measured_at, r.id LIMIT ?", READING_MAPPER,
                vehicleId, after?.first?.let { Timestamp.from(it) }, after?.first?.let { Timestamp.from(it) }, after?.second, limit)
        }
    }

    override fun conflicts(ctx: RequestContext, vehicleId: UUID, status: OdometerConflictStatus?, limit: Int, after: Pair<Instant, UUID>?): List<OdometerConflictView> {
        require(ctx, SCOPE_READ); checkLimit(limit)
        return ctxTx.run(ctx, readOnly = true) { j ->
            vehicleOrNotFound(j, vehicleId)
            val resolved = status?.let { it == OdometerConflictStatus.RESOLVED }
            j.query("$CONFLICT_SQL WHERE c.vehicle_id = ? AND (CAST(? AS boolean) IS NULL OR (res.id IS NOT NULL) = CAST(? AS boolean)) AND (CAST(? AS timestamptz) IS NULL OR (c.detected_at, c.id) > (?, CAST(? AS uuid))) " +
                "ORDER BY c.detected_at, c.id LIMIT ?", CONFLICT_MAPPER, vehicleId, resolved, resolved, after?.first?.let { Timestamp.from(it) }, after?.first?.let { Timestamp.from(it) }, after?.second, limit)
        }
    }

    override fun resolve(ctx: RequestContext, vehicleId: UUID, conflictId: UUID, cmd: ResolveConflict): OdometerConflictView {
        require(ctx, SCOPE_RESOLVE)
        if (!isFinance(ctx) || ctx.actor.type != ActorType.HUMAN) throw ProblemException(ErrorCode.FORBIDDEN)
        val reason = cmd.reason.trim()
        if (reason.length < 3) throw ProblemException.invalid(Violation("/reason", if (reason.isEmpty()) ViolationCode.REQUIRED else ViolationCode.TOO_SHORT))
        if (reason.length > 1000) throw ProblemException.invalid(Violation("/reason", ViolationCode.TOO_LONG))
        if ((cmd.decision == ConflictDecision.CHOSEN) != (cmd.chosenReadingId != null)) throw ProblemException.invalid(Violation("/chosen_reading_id", if (cmd.decision == ConflictDecision.CHOSEN) ViolationCode.REQUIRED else ViolationCode.INVALID_VALUE))
        return ctxTx.run(ctx) { j -> doResolve(j, ctx, vehicleId, conflictId, cmd, reason) }
    }

    @AuditRequired
    private fun doResolve(j: JdbcOperations, ctx: RequestContext, vehicleId: UUID, conflictId: UUID, cmd: ResolveConflict, reason: String): OdometerConflictView {
        val c = j.query("$CONFLICT_SQL WHERE c.id = ? AND c.vehicle_id = ?", CONFLICT_MAPPER, conflictId, vehicleId).firstOrNull() ?: throw ProblemException.notFound()
        if (cmd.chosenReadingId != null && cmd.chosenReadingId !in setOf(c.earlier.id, c.later.id)) throw ProblemException.invalid(Violation("/chosen_reading_id", ViolationCode.INVALID_VALUE, "Der Stand gehört nicht zum Konflikt"))
        val actor = DocumentRows.actor(ctx)
        // Die Eindeutigkeit je Konflikt entscheidet bei paralleler Auflösung: genau ein Eintrag, der andere Aufruf wartet auf den Commit und erhält 409
        val inserted = j.update("INSERT INTO taktovia.odometer_conflict_resolution (tenant_id, id, conflict_id, decision, chosen_reading_id, reason, resolved_by) VALUES (taktovia.current_tenant(),?,?,?,?,?,?) " +
            "ON CONFLICT (tenant_id, conflict_id) DO NOTHING", UUID.randomUUID(), conflictId, cmd.decision.name, cmd.chosenReadingId, reason, actor.value)
        if (inserted == 0) throw ProblemException(ErrorCode.CONFLICT, "Der Konflikt ist bereits aufgelöst.")
        audit.record(j, AuditEntry(actor, AuditActions.FINANCE_ODOMETER_CONFLICT_RESOLVED, ObjectRef("odometer_conflict", conflictId), ctx.correlationId,
            mapOf("vehicle_id" to vehicleId.toString(), "decision" to cmd.decision.name, "chosen_reading_id" to cmd.chosenReadingId?.toString(), "reason" to reason)))
        return j.query("$CONFLICT_SQL WHERE c.id = ?", CONFLICT_MAPPER, conflictId).single()
    }

    override fun policy(ctx: RequestContext): OdometerPolicyView {
        require(ctx, SCOPE_READ)
        return ctxTx.run(ctx, readOnly = true) { j -> loadPolicy(j) }
    }

    override fun updatePolicy(ctx: RequestContext, toleranceKm: Int, windowMinutes: Int): OdometerPolicyView {
        require(ctx, SCOPE_RESOLVE)
        if (!isFinance(ctx) || ctx.actor.type != ActorType.HUMAN) throw ProblemException(ErrorCode.FORBIDDEN)
        if (toleranceKm !in 0..1000) throw ProblemException.invalid(Violation("/tolerance_km", if (toleranceKm < 0) ViolationCode.BELOW_MINIMUM else ViolationCode.ABOVE_MAXIMUM))
        if (windowMinutes !in 1..10080) throw ProblemException.invalid(Violation("/window_minutes", if (windowMinutes < 1) ViolationCode.BELOW_MINIMUM else ViolationCode.ABOVE_MAXIMUM))
        return ctxTx.run(ctx) { j -> doUpdatePolicy(j, ctx, toleranceKm, windowMinutes) }
    }

    @AuditRequired
    private fun doUpdatePolicy(j: JdbcOperations, ctx: RequestContext, toleranceKm: Int, windowMinutes: Int): OdometerPolicyView {
        run {
            j.update("INSERT INTO taktovia.odometer_policy AS p (tenant_id, tolerance_km, window_minutes, updated_by) VALUES (taktovia.current_tenant(),?,?,?) " +
                "ON CONFLICT (tenant_id) DO UPDATE SET tolerance_km = EXCLUDED.tolerance_km, window_minutes = EXCLUDED.window_minutes, updated_by = EXCLUDED.updated_by, updated_at = clock_timestamp(), revision = p.revision + 1",
                toleranceKm, windowMinutes, ctx.actor.subject.take(200))
            val p = loadPolicy(j)
            audit.record(j, AuditEntry(DocumentRows.actor(ctx), AuditActions.FINANCE_ODOMETER_POLICY_CHANGED, ObjectRef("odometer_policy", ctx.tenant.value.toString()), ctx.correlationId,
                mapOf("tolerance_km" to p.toleranceKm, "window_minutes" to p.windowMinutes, "revision" to p.revision)))
            return p
        }
    }

    private fun vehicleOrNotFound(j: JdbcOperations, vehicleId: UUID) {
        if (j.query("SELECT 1 FROM taktovia.vehicle WHERE id = ?", { _, _ -> 1 }, vehicleId).isEmpty()) throw ProblemException.notFound()
    }

    private fun checkLimit(limit: Int) { if (limit !in 1..200) throw ProblemException.invalid(Violation("/limit", ViolationCode.INVALID_VALUE)) }
    private fun require(ctx: RequestContext, scope: String) { if (!ctx.hasScope(scope)) throw ProblemException(ErrorCode.FORBIDDEN) }

    companion object {
        const val SCOPE_READ = "odometer:read"
        const val SCOPE_RECORD = "odometer:record"
        const val SCOPE_RESOLVE = "odometer:resolve"
        private const val READING_SQL = "SELECT r.id, r.vehicle_id, r.reading_km, r.source, r.source_ref, r.measured_at, r.recorded_at, r.recorded_by FROM taktovia.odometer_reading r"
        /** Ein Stand, der in einer Auflösung verworfen wurde (beide bei DISCARDED, sonst der nicht gewählte), zählt für die Konflikterkennung nicht mehr. */
        private const val NOT_DISCARDED = "NOT EXISTS (SELECT 1 FROM taktovia.odometer_conflict_resolution x JOIN taktovia.odometer_conflict xc ON xc.tenant_id = x.tenant_id AND xc.id = x.conflict_id " +
            "WHERE r.id IN (xc.earlier_reading_id, xc.later_reading_id) AND (x.chosen_reading_id IS DISTINCT FROM r.id))"
        private const val CONFLICT_SQL = "SELECT c.id, c.vehicle_id, c.kind, c.tolerance_km, c.detected_at, " +
            "e.id AS e_id, e.vehicle_id AS e_vehicle_id, e.reading_km AS e_km, e.source AS e_source, e.source_ref AS e_ref, e.measured_at AS e_measured, e.recorded_at AS e_recorded, e.recorded_by AS e_by, " +
            "l.id AS l_id, l.vehicle_id AS l_vehicle_id, l.reading_km AS l_km, l.source AS l_source, l.source_ref AS l_ref, l.measured_at AS l_measured, l.recorded_at AS l_recorded, l.recorded_by AS l_by, " +
            "res.decision, res.chosen_reading_id, res.resolved_by, res.reason, res.resolved_at " +
            "FROM taktovia.odometer_conflict c JOIN taktovia.odometer_reading e ON e.tenant_id = c.tenant_id AND e.id = c.earlier_reading_id JOIN taktovia.odometer_reading l ON l.tenant_id = c.tenant_id AND l.id = c.later_reading_id " +
            "LEFT JOIN taktovia.odometer_conflict_resolution res ON res.tenant_id = c.tenant_id AND res.conflict_id = c.id"

        val READING_MAPPER = RowMapper { rs, _ ->
            OdometerReadingView(rs.getObject("id", UUID::class.java), rs.getObject("vehicle_id", UUID::class.java), rs.getLong("reading_km"), OdometerSource.valueOf(rs.getString("source")), rs.getString("source_ref"),
                rs.getTimestamp("measured_at").toInstant(), rs.getTimestamp("recorded_at").toInstant(), rs.getString("recorded_by"))
        }
        private val CONFLICT_MAPPER = RowMapper { rs, _ ->
            fun reading(p: String) = OdometerReadingView(rs.getObject("${p}_id", UUID::class.java), rs.getObject("${p}_vehicle_id", UUID::class.java), rs.getLong("${p}_km"), OdometerSource.valueOf(rs.getString("${p}_source")),
                rs.getString("${p}_ref"), rs.getTimestamp("${p}_measured").toInstant(), rs.getTimestamp("${p}_recorded").toInstant(), rs.getString("${p}_by"))
            val resolution = rs.getString("decision")?.let { ConflictResolutionView(ConflictDecision.valueOf(it), rs.getObject("chosen_reading_id", UUID::class.java), rs.getString("resolved_by"), rs.getString("reason"), rs.getTimestamp("resolved_at").toInstant()) }
            OdometerConflictView(rs.getObject("id", UUID::class.java), rs.getObject("vehicle_id", UUID::class.java), OdometerConflictKind.valueOf(rs.getString("kind")), rs.getInt("tolerance_km"), reading("e"), reading("l"),
                rs.getTimestamp("detected_at").toInstant(), resolution)
        }

        fun loadPolicy(j: JdbcOperations): OdometerPolicyView =
            j.query("SELECT tolerance_km, window_minutes, revision FROM taktovia.odometer_policy", { rs, _ -> OdometerPolicyView(rs.getInt(1), rs.getInt(2), rs.getLong(3)) }).firstOrNull()
                ?: OdometerPolicyView(OdometerPolicyView.DEFAULT_TOLERANCE_KM, OdometerPolicyView.DEFAULT_WINDOW_MINUTES, 0)

        /** Formale Prüfung eines Stands; Telematik nur aus dem Simulator (Bezug `sim:`), solange D-09 nicht beschlossen ist. */
        fun validate(cmd: RecordOdometer, now: Instant, telematicsProductive: Boolean) {
            if (cmd.readingKm !in 0..10_000_000L) throw ProblemException.invalid(Violation("/reading_km", if (cmd.readingKm < 0) ViolationCode.BELOW_MINIMUM else ViolationCode.ABOVE_MAXIMUM))
            if (cmd.sourceRef.isBlank() || cmd.sourceRef.length > 200) throw ProblemException.invalid(Violation("/source_ref", if (cmd.sourceRef.isBlank()) ViolationCode.REQUIRED else ViolationCode.TOO_LONG))
            if (cmd.measuredAt.isAfter(now.plus(Duration.ofDays(1)))) throw ProblemException.invalid(Violation("/measured_at", ViolationCode.INVALID_VALUE, "Messzeit liegt in der Zukunft"))
            if (cmd.source == OdometerSource.TELEMATICS && !telematicsProductive && !cmd.sourceRef.startsWith("sim:"))
                throw ProblemException.invalid(Violation("/source_ref", ViolationCode.INVALID_VALUE, "Telematikstände stammen bis zum Beschluss D-09 nur aus dem Simulator (Bezug beginnt mit sim:)"))
        }
    }
}

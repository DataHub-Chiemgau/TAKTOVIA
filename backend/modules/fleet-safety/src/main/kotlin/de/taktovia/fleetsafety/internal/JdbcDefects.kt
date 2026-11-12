package de.taktovia.fleetsafety.internal

import de.taktovia.fleetsafety.api.DefectCatalog
import de.taktovia.fleetsafety.api.DefectFilter
import de.taktovia.fleetsafety.api.DefectView
import de.taktovia.fleetsafety.api.Defects
import de.taktovia.fleetsafety.api.DriverVehicleRelation
import de.taktovia.fleetsafety.api.EvidenceView
import de.taktovia.fleetsafety.api.ReportDefect
import de.taktovia.fleetsafety.api.ReportedDefect
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
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Rollen, die Schäden melden dürfen (Rechtematrix `createDefect`: Fahrer, Disposition, Werkstatt); die Rechtematrix im Anfragefilter ist die erste, diese Prüfung die zweite Schicht. */
internal object DefectRoles {
    val REPORTERS = setOf("driver", "dispatcher", "workshop")
    /** Ein Konto, das nur als Fahrer auftritt, sieht und meldet im Rahmen seines Bezugs und seiner eigenen Meldungen (DMG-011). */
    fun driverOnly(ctx: RequestContext) = "driver" in ctx.roles && ctx.roles.none { it == "dispatcher" || it == "workshop" || it == "tenant_admin" }
    fun reporterRole(ctx: RequestContext): String? = listOf("dispatcher", "workshop", "driver").firstOrNull { it in ctx.roles }
}

/** Standardumsetzung des Fahrerbezugs: Schicht des Fahrers für das Fahrzeug (siehe [DriverVehicleRelation]); nur lesend. */
internal class ShiftDriverRelation : DriverVehicleRelation {
    override fun hasRelation(jdbc: JdbcOperations, driverSubject: String, vehicleId: UUID, now: Instant): Boolean =
        jdbc.queryForObject(
            "SELECT count(*) FROM taktovia.staff_shift WHERE vehicle_id = ? AND driver_ref::text = lower(?) AND upper(state) NOT IN ('CANCELLED', 'CANCELED') " +
                "AND starts_at <= ? AND ends_at >= ?", Int::class.java, vehicleId, driverSubject,
            Timestamp.from(now.plus(Duration.ofHours(DefectCatalog.DRIVER_PRE_SHIFT_HOURS))), Timestamp.from(now.minus(Duration.ofHours(DefectCatalog.DRIVER_POST_SHIFT_HOURS))),
        )!! > 0
}

/** Schadenakte (WP-M3-43). Siehe [Defects]. */
internal class JdbcDefects(
    private val ctxTx: ContextTransactions, private val audit: AuditPort, private val outbox: Outbox, private val clock: Clock, private val relation: DriverVehicleRelation,
    private val evidence: JdbcDefectEvidence,
) : Defects {

    override fun report(ctx: RequestContext, cmd: ReportDefect): ReportedDefect {
        val actor = actor(ctx)
        val role = DefectRoles.reporterRole(ctx) ?: throw ProblemException(ErrorCode.FORBIDDEN, "Schäden melden Fahrer, Disposition und Werkstatt.")
        val subject = reporter(ctx)
        try {
            return ctxTx.run(ctx) { j -> doReport(j, ctx, actor, subject, role, cmd) }
        } catch (e: DataIntegrityViolationException) {
            // Gleichzeitige Wiederholung derselben Geräteereignis-ID: die zweite Transaktion scheitert an UNIQUE und liefert die erste Akte
            if (sqlState(e) == "23505" && cmd.deviceEventId != null) {
                val existing = ctxTx.run(ctx, readOnly = true) { j -> findByDeviceEvent(j, cmd.deviceEventId) } ?: throw e
                return ReportedDefect(existing, created = false)
            }
            if (sqlState(e) == "23514") throw ProblemException.invalid(Violation("/occurred_at", ViolationCode.INVALID_VALUE))   // Ereigniszeit in der Zukunft (Trigger)
            throw e
        }
    }

    @AuditRequired
    private fun doReport(j: JdbcOperations, ctx: RequestContext, actor: ActorRef, subject: UUID, role: String, cmd: ReportDefect): ReportedDefect {
        val now = clock.instant()
        if (cmd.occurredAt.isAfter(now.plusSeconds(DefectCatalog.FUTURE_TOLERANCE_SECONDS))) throw ProblemException.invalid(Violation("/occurred_at", ViolationCode.INVALID_VALUE))
        // Wiederholung (Gerät, Offline-Synchronisation): dieselbe Akte, nur für denselben Meldenden
        if (cmd.deviceEventId != null) findByDeviceEvent(j, cmd.deviceEventId)?.let {
            if (it.reportedBy != subject || it.vehicleId != cmd.vehicleId) throw ProblemException(ErrorCode.IDEMPOTENCY_CONFLICT, "Die Geräteereignis-ID gehört zu einer anderen Meldung.")
            return ReportedDefect(it, created = false)
        }
        // Fahrzeug im eigenen Mandanten (RLS): fremd oder unbekannt = 404; ein Fahrer braucht zusätzlich den Bezug, ohne Hinweis auf die Existenz
        if (j.queryForObject("SELECT count(*) FROM taktovia.vehicle WHERE id = ?", Int::class.java, cmd.vehicleId) != 1) throw ProblemException.notFound()
        if (DefectRoles.driverOnly(ctx) && !relation.hasRelation(j, ctx.actor.subject, cmd.vehicleId, now)) throw ProblemException.notFound()
        cmd.sourceInspectionId?.let {
            if (j.queryForObject("SELECT count(*) FROM taktovia.inspection_run WHERE id = ? AND vehicle_id = ?", Int::class.java, it, cmd.vehicleId) != 1)
                throw ProblemException.invalid(Violation("/source_inspection_id", ViolationCode.INVALID_VALUE))
        }
        val id = UUID.randomUUID()
        val view = insertCase(j, id, actor, subject, cmd)
        afterInsert(j, id, view, actor, cmd)
        audit.record(j, AuditEntry(actor, AuditActions.DEFECT_REPORTED, ObjectRef("defect", id), ctx.correlationId, buildMap {
            put("case_number", view.caseNumber); put("vehicle_id", cmd.vehicleId.toString()); put("category_code", cmd.categoryCode); put("reporter_role", role)
            put("capture_mode", cmd.captureMode.name); put("evidence_count", cmd.evidenceIds.size); cmd.severityHint?.let { put("severity_hint", it) }
        }))
        outbox.append(j, OutboxEvent(id, view.revision, "defect.reported",
            mapOf("defect_id" to id.toString(), "case_number" to view.caseNumber, "vehicle_id" to cmd.vehicleId.toString(), "category_code" to cmd.categoryCode, "reporter_role" to role,
                "evidence_ids" to cmd.evidenceIds.map { it.toString() }),
            clock.instant(), correlationId = ctx.correlationId))
        return ReportedDefect(loadOrNull(j, id)!!, created = true)
    }

    private fun insertCase(j: JdbcOperations, id: UUID, actor: ActorRef, subject: UUID, cmd: ReportDefect): DefectView =
        j.query(
            "INSERT INTO taktovia.defect_case AS d (tenant_id, id, case_number, vehicle_id, reported_by, occurred_at, location_kind, location_value, category_code, kind, vehicle_area, observed_effect, " +
                "description, source_inspection_id, source_step_code, device_event_id, created_by) " +
                "VALUES (taktovia.current_tenant(), ?, taktovia.next_defect_case_number(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING $COLUMNS",
            MAPPER, id, cmd.vehicleId, subject, Timestamp.from(cmd.occurredAt), cmd.locationKind, cmd.locationValue, cmd.categoryCode, cmd.kind, cmd.vehicleArea, cmd.observedEffect,
            cmd.description, cmd.sourceInspectionId, cmd.sourceStepCode, cmd.deviceEventId, actor.value,
        ).single()

    /** Erweiterungspunkt der folgenden Arbeitspakete (Statushistorie, Revision 1, Evidenzanbindung) innerhalb derselben Transaktion. */
    private fun afterInsert(j: JdbcOperations, id: UUID, view: DefectView, actor: ActorRef, cmd: ReportDefect) {
        evidence.attach(j, id, cmd.evidenceIds, actor, 1)
    }

    override fun get(ctx: RequestContext, id: UUID): DefectView = ctxTx.run(ctx, readOnly = true) { j ->
        val v = loadOrNull(j, id) ?: throw ProblemException.notFound()
        if (DefectRoles.driverOnly(ctx) && v.reportedBy.toString() != ctx.actor.subject.lowercase()) throw ProblemException.notFound()
        v
    }

    override fun list(ctx: RequestContext, filter: DefectFilter, limit: Int, after: Pair<Instant, UUID>?): List<DefectView> = ctxTx.run(ctx, readOnly = true) { j ->
        val own = if (DefectRoles.driverOnly(ctx)) reporter(ctx) else null
        val afterTs = after?.first?.let(Timestamp::from)
        enrich(j, j.query(
            "SELECT $COLUMNS FROM taktovia.defect_case d WHERE (CAST(? AS uuid) IS NULL OR d.vehicle_id = CAST(? AS uuid)) AND (CAST(? AS text) IS NULL OR d.status = CAST(? AS text)) " +
                "AND (CAST(? AS text) IS NULL OR d.severity = CAST(? AS text)) AND (CAST(? AS timestamptz) IS NULL OR d.reported_at >= CAST(? AS timestamptz)) " +
                "AND (CAST(? AS timestamptz) IS NULL OR d.reported_at < CAST(? AS timestamptz)) AND (CAST(? AS uuid) IS NULL OR d.reported_by = CAST(? AS uuid)) " +
                "AND (CAST(? AS timestamptz) IS NULL OR (d.created_at, d.id) > (CAST(? AS timestamptz), CAST(? AS uuid))) ORDER BY d.created_at, d.id LIMIT ?",
            MAPPER, filter.vehicleId, filter.vehicleId, filter.status, filter.status, filter.severity, filter.severity,
            filter.reportedFrom?.let(Timestamp::from), filter.reportedFrom?.let(Timestamp::from), filter.reportedTo?.let(Timestamp::from), filter.reportedTo?.let(Timestamp::from),
            own, own, afterTs, afterTs, after?.second, limit + 1,
        ))
    }

    // ---------------- Hilfen ----------------

    private fun findByDeviceEvent(j: JdbcOperations, deviceEventId: UUID): DefectView? =
        enrich(j, j.query("SELECT $COLUMNS FROM taktovia.defect_case d WHERE d.device_event_id = ?", MAPPER, deviceEventId)).firstOrNull()

    private fun loadOrNull(j: JdbcOperations, id: UUID): DefectView? = enrich(j, j.query("SELECT $COLUMNS FROM taktovia.defect_case d WHERE d.id = ?", MAPPER, id)).firstOrNull()

    /** Ergänzt freigegebene Fotos und die Zahl der Fotos in Prüfung (nicht sichtbar, nicht abrufbar). Eine Abfrage je Seite. */
    private fun enrich(j: JdbcOperations, views: List<DefectView>): List<DefectView> {
        if (views.isEmpty()) return views
        val ids = views.joinToString(",") { it.id.toString() }
        val released = j.query("SELECT $EVIDENCE_COLUMNS FROM taktovia.defect_evidence e WHERE e.defect_id = ANY (string_to_array(?, ',')::uuid[]) ORDER BY e.created_at, e.id",
            JdbcDefectEvidence.EV_MAPPER, ids).groupBy { it.defectId }
        val pending = j.query("SELECT defect_id, count(*) FROM taktovia.defect_evidence_upload WHERE state = 'IN_REVIEW' AND defect_id = ANY (string_to_array(?, ',')::uuid[]) GROUP BY defect_id",
            { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getInt(2) }, ids).toMap()
        return views.map { v ->
            v.copy(evidence = released[v.id].orEmpty().map { EvidenceView(it.id, it.mime ?: "", it.sizeBytes ?: 0, it.sha256Hex, it.capturedAt, it.receivedAt, it.addedInVersion, it.replacesId) }, evidencePending = pending[v.id] ?: 0)
        }
    }

    private fun reporter(ctx: RequestContext): UUID =
        runCatching { UUID.fromString(ctx.actor.subject) }.getOrNull() ?: throw ProblemException(ErrorCode.FORBIDDEN, "Schäden melden nur Personenkonten.")

    private fun actor(ctx: RequestContext) = ActorRef((if (ctx.actor.type == ActorType.SERVICE) "service:" else "user:") + ctx.actor.subject)

    private fun sqlState(e: Throwable): String? = generateSequence(e) { it.cause }.filterIsInstance<SQLException>().firstOrNull()?.sqlState

    internal companion object {
        /** Wie [JdbcDefectEvidence.EV_COLUMNS] (hier als eigene Konstante, damit die Abfrage-Prüfung den Text sieht). */
        const val EVIDENCE_COLUMNS = "e.id, e.defect_id, e.mime, e.size_bytes, e.sha256, e.captured_at, e.received_at, e.created_by"
        const val COLUMNS = "d.id, d.case_number, d.vehicle_id, d.reported_by, d.reported_at, d.occurred_at, d.location_kind, d.location_value, d.category_code, d.kind, d.vehicle_area, " +
            "d.observed_effect, d.description, d.severity, d.status, d.assignee_ref, d.source_inspection_id, d.source_step_code, d.content_version, d.revision, d.updated_at, d.created_at"
        val MAPPER = RowMapper { rs, _ ->
            DefectView(
                rs.getObject("id", UUID::class.java), rs.getString("case_number"), rs.getObject("vehicle_id", UUID::class.java), rs.getObject("reported_by", UUID::class.java),
                rs.getTimestamp("reported_at").toInstant(), rs.getTimestamp("occurred_at").toInstant(), rs.getString("location_kind"), rs.getString("location_value"),
                rs.getString("category_code"), rs.getString("kind"), rs.getString("vehicle_area"), rs.getString("observed_effect"), rs.getString("description"), rs.getString("severity"),
                rs.getString("status"), rs.getObject("assignee_ref", UUID::class.java), rs.getObject("source_inspection_id", UUID::class.java), rs.getString("source_step_code"),
                rs.getInt("content_version"), rs.getLong("revision"), rs.getTimestamp("updated_at").toInstant(), rs.getTimestamp("created_at").toInstant(),
            )
        }
    }
}

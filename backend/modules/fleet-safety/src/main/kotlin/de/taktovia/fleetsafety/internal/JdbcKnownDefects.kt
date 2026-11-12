package de.taktovia.fleetsafety.internal

import de.taktovia.fleetsafety.api.AcknowledgeKnownDefect
import de.taktovia.fleetsafety.api.AcknowledgedKnownDefect
import de.taktovia.fleetsafety.api.DefectCatalog
import de.taktovia.fleetsafety.api.DriverVehicleRelation
import de.taktovia.fleetsafety.api.KnownDefectCatalog
import de.taktovia.fleetsafety.api.KnownDefectList
import de.taktovia.fleetsafety.api.KnownDefectPhoto
import de.taktovia.fleetsafety.api.KnownDefectView
import de.taktovia.fleetsafety.api.KnownDefects
import de.taktovia.platform.audit.ActorRef
import de.taktovia.platform.audit.AuditActions
import de.taktovia.platform.audit.AuditEntry
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.audit.AuditRequired
import de.taktovia.platform.audit.ObjectRef
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcOperations
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Altschäden und Quittierung (WP-M3-21, siehe [KnownDefects]). Eine Transaktion je Aufruf. Die Regel „Altschaden“ liegt in der SQL-Funktion `inspection_known_defects`; Revision und Status der
 * Akte zum Quittierungszeitpunkt trägt der Datenbanktrigger unter `FOR SHARE` ein (eine gleichzeitige Statusänderung der Akte wartet, die Quittierung sieht einen konsistenten Stand).
 * Doppelte Quittierung entscheidet `ON CONFLICT DO NOTHING` auf dem Unique-Index (Mandant, Prüflauf, Akte): genau ein Eintrag, der zweite Aufruf liefert ihn unverändert.
 */
internal class JdbcKnownDefects(private val ctxTx: ContextTransactions, private val audit: AuditPort, private val relation: DriverVehicleRelation, private val clock: Clock) : KnownDefects {

    private class Run(val id: UUID, val vehicleId: UUID, val kind: String, val status: String)

    override fun forInspection(ctx: RequestContext, inspectionId: UUID): KnownDefectList {
        requireRole(ctx, READERS)
        return ctxTx.run(ctx, readOnly = true) { j ->
            val run = loadRun(j, ctx, inspectionId)
            list(j, run)
        }
    }

    override fun acknowledge(ctx: RequestContext, inspectionId: UUID, defectId: UUID, cmd: AcknowledgeKnownDefect): AcknowledgedKnownDefect {
        requireRole(ctx, ACKNOWLEDGERS)
        try {
            return ctxTx.run(ctx) { j -> doAcknowledge(j, ctx, inspectionId, defectId) }
        } catch (e: DataIntegrityViolationException) {
            // Der Wächter (V30210) weist einen nicht mehr offenen Lauf auch bei Wettlauf mit dem Abschluss ab
            if (sqlState(e) == "23514") throw ProblemException(ErrorCode.INVALID_STATE_TRANSITION, "Der Prüflauf ist nicht mehr offen oder die Akte gehört nicht zu seinem Fahrzeug.")
            throw e
        }
    }

    @AuditRequired
    private fun doAcknowledge(j: JdbcOperations, ctx: RequestContext, inspectionId: UUID, defectId: UUID): AcknowledgedKnownDefect {
        val run = loadRun(j, ctx, inspectionId, lock = true)
        existing(j, inspectionId, defectId)?.let { return AcknowledgedKnownDefect(inspectionId, it, created = false) }   // Wiederholung oder zweites Gerät
        if (run.status != "IN_PROGRESS") throw ProblemException(ErrorCode.INVALID_STATE_TRANSITION, "Der Prüflauf ist nicht mehr offen.")
        // Akte des Fahrzeugs des Laufs (RLS: fremder Mandant = nicht vorhanden); unter Lesesperre, damit Status und Revision zum Quittierungszeitpunkt feststehen
        val defect = j.query("SELECT status FROM taktovia.defect_case WHERE id = ? AND vehicle_id = ? FOR SHARE", { rs, _ -> rs.getString(1) }, defectId, run.vehicleId).firstOrNull() ?: throw ProblemException.notFound()
        val known = j.queryForObject("SELECT count(*) FROM taktovia.inspection_known_defects(taktovia.current_tenant(), ?) k WHERE k = ?", Int::class.java, inspectionId, defectId) == 1
        if (defect in CLOSED) throw ProblemException(ErrorCode.CONFLICT, "${KnownDefectCatalog.NOT_OPEN}: Der Fall ist inzwischen abgeschlossen; die Liste der Altschäden neu laden.")
        if (!known) throw ProblemException.notFound()   // nach dem Start des Laufs gemeldet oder aus diesem Lauf: kein Altschaden dieses Laufs
        val inserted = j.update(
            "INSERT INTO taktovia.known_defect_acknowledgement (tenant_id, id, inspection_id, defect_id, acknowledged_by) VALUES (taktovia.current_tenant(), ?, ?, ?, ?) " +
                "ON CONFLICT (tenant_id, inspection_id, defect_id) DO NOTHING",
            UUID.randomUUID(), inspectionId, defectId, actor(ctx).value)
        val view = existing(j, inspectionId, defectId) ?: throw ProblemException(ErrorCode.CONFLICT, "Die Quittierung konnte nicht gespeichert werden; wiederholen.")
        if (inserted == 1) audit.record(j, AuditEntry(actor(ctx), AuditActions.KNOWN_DEFECT_ACKNOWLEDGED, ObjectRef("inspection", inspectionId), ctx.correlationId,
            mapOf("defect_id" to defectId.toString(), "case_number" to view.caseNumber, "defect_revision" to view.acknowledgedDefectRevision!!, "vehicle_id" to run.vehicleId.toString())))
        return AcknowledgedKnownDefect(inspectionId, view, created = inserted == 1)
    }

    override fun forVehicle(ctx: RequestContext, vehicleId: UUID): KnownDefectList {
        requireRole(ctx, READERS)
        return ctxTx.run(ctx, readOnly = true) { j ->
            if (j.queryForObject("SELECT count(*) FROM taktovia.vehicle WHERE id = ?", Int::class.java, vehicleId) != 1) throw ProblemException.notFound()
            if (DefectRoles.driverOnly(ctx) && !hasVehicleRelation(j, ctx, vehicleId)) throw ProblemException.notFound()
            val defects = query(j, "d.vehicle_id = ? AND d.status NOT IN ('CLOSED', 'DISMISSED')", null, vehicleId)
            KnownDefectList(null, vehicleId, defects, acknowledgementRequired = false, unacknowledgedCount = 0)
        }
    }

    /** Bezug des Fahrers zum Fahrzeug: der Bezug der Schadenmeldung ([DriverVehicleRelation]) oder eine eigene Schicht (Fahrerstamm oder IdP-Kennung wie beim Prüflauf) im selben Zeitfenster. */
    private fun hasVehicleRelation(j: JdbcOperations, ctx: RequestContext, vehicleId: UUID): Boolean {
        val now = clock.instant()
        if (relation.hasRelation(j, ctx.actor.subject, vehicleId, now)) return true
        return j.queryForObject(   // sql-lint: ok ShiftScope.OWN ist eine Compile-Zeit-Konstante, alle Werte sind Platzhalter
            "SELECT count(*) FROM taktovia.staff_shift s WHERE s.vehicle_id = ? AND upper(s.state) NOT IN ('CANCELLED', 'CANCELED') AND s.starts_at <= ? AND s.ends_at >= ? AND ${ShiftScope.OWN}",
            Int::class.java, vehicleId, java.sql.Timestamp.from(now.plus(java.time.Duration.ofHours(DefectCatalog.DRIVER_PRE_SHIFT_HOURS))),
            java.sql.Timestamp.from(now.minus(java.time.Duration.ofHours(DefectCatalog.DRIVER_POST_SHIFT_HOURS))), *ShiftScope.args(ctx.actor.issuer, ctx.actor.subject))!! > 0
    }

    override fun requireAllAcknowledged(jdbc: JdbcOperations, inspectionId: UUID) {
        val kind = jdbc.query("SELECT kind FROM taktovia.inspection_run WHERE id = ?", { rs, _ -> rs.getString(1) }, inspectionId).firstOrNull() ?: throw ProblemException.notFound()
        if (kind !in KnownDefectCatalog.ACK_REQUIRED_KINDS) return
        val missing = jdbc.queryForObject(
            "SELECT count(*) FROM taktovia.inspection_known_defects(taktovia.current_tenant(), ?) k WHERE NOT EXISTS " +
                "(SELECT 1 FROM taktovia.known_defect_acknowledgement a WHERE a.inspection_id = ? AND a.defect_id = k)", Int::class.java, inspectionId, inspectionId)!!
        if (missing > 0) throw ProblemException(ErrorCode.CONFLICT, "${KnownDefectCatalog.UNACKNOWLEDGED}: $missing offene Altschäden des Fahrzeugs sind nicht quittiert.")
    }

    // --- Lesen -------------------------------------------------------------------------------------------------------------------------------------------------------------------

    private fun loadRun(j: JdbcOperations, ctx: RequestContext, id: UUID, lock: Boolean = false): Run {
        val mapper = { rs: java.sql.ResultSet, _: Int -> Run(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getString(3), rs.getString(4)) }
        // Fahrer sehen nur Läufe eigener Schichten (sonst 404); Disposition und Werkstatt alle Läufe des Mandanten
        val own = InspectionRoles.driverOnly(ctx)
        val rows = when {
            own && lock -> j.query("SELECT i.id, i.vehicle_id, i.kind, i.status FROM taktovia.inspection_run i WHERE i.id = ? AND EXISTS (SELECT 1 FROM taktovia.staff_shift s WHERE s.id = i.shift_id AND ${ShiftScope.OWN}) FOR SHARE OF i", mapper, id, *ShiftScope.args(ctx.actor.issuer, ctx.actor.subject))   // sql-lint: ok ShiftScope.OWN ist eine Compile-Zeit-Konstante, alle Werte sind Platzhalter
            own -> j.query("SELECT i.id, i.vehicle_id, i.kind, i.status FROM taktovia.inspection_run i WHERE i.id = ? AND EXISTS (SELECT 1 FROM taktovia.staff_shift s WHERE s.id = i.shift_id AND ${ShiftScope.OWN})", mapper, id, *ShiftScope.args(ctx.actor.issuer, ctx.actor.subject))   // sql-lint: ok ShiftScope.OWN ist eine Compile-Zeit-Konstante, alle Werte sind Platzhalter
            lock -> j.query("SELECT i.id, i.vehicle_id, i.kind, i.status FROM taktovia.inspection_run i WHERE i.id = ? FOR SHARE OF i", mapper, id)
            else -> j.query("SELECT i.id, i.vehicle_id, i.kind, i.status FROM taktovia.inspection_run i WHERE i.id = ?", mapper, id)
        }
        return rows.firstOrNull() ?: throw ProblemException.notFound()
    }

    private fun list(j: JdbcOperations, run: Run): KnownDefectList {
        val open = run.status == "IN_PROGRESS"
        val defects = query(j, "d.vehicle_id = ? AND (a.id IS NOT NULL OR (? AND d.id IN (SELECT k FROM taktovia.inspection_known_defects(taktovia.current_tenant(), ?) k)))", run.id, run.vehicleId, open, run.id)
        val missing = if (open) defects.count { it.stillOpen && !it.acknowledged } else 0
        return KnownDefectList(run.id, run.vehicleId, defects, acknowledgementRequired = missing > 0 && run.kind in KnownDefectCatalog.ACK_REQUIRED_KINDS, unacknowledgedCount = missing)
    }

    private fun existing(j: JdbcOperations, inspectionId: UUID, defectId: UUID): KnownDefectView? =
        query(j, "d.id = ? AND a.id IS NOT NULL", inspectionId, defectId).firstOrNull()

    /**
     * Projektion der Akten; [inspectionId] verbindet die Quittierung (`LEFT JOIN`), die Platzhalter von [where] folgen auf die Prüflauf-ID. Die Fotos kommen in einer zweiten Abfrage:
     * nur freigegebene, nicht ersetzte und nicht vertrauliche (DMG-011).
     */
    private fun query(j: JdbcOperations, where: String, inspectionId: UUID?, vararg args: Any): List<KnownDefectView> {
        val sql = "SELECT d.id, d.case_number, d.category_code, d.kind, d.vehicle_area, d.status, a.id IS NOT NULL, a.created_at, a.defect_revision " +
            "FROM taktovia.defect_case d LEFT JOIN taktovia.known_defect_acknowledgement a ON a.tenant_id = d.tenant_id AND a.defect_id = d.id AND a.inspection_id IS NOT DISTINCT FROM ? " +
            "WHERE $where ORDER BY d.case_number, d.id"
        class Row(val id: UUID, val number: String, val category: String, val kind: String?, val area: String?, val status: String, val acked: Boolean, val ackedAt: Instant?, val ackedRevision: Long?)
        val rows = j.query(sql, { rs, _ ->   // sql-lint: ok where besteht nur aus festen Literalen der drei Aufrufer in dieser Klasse, alle Werte sind Platzhalter
            Row(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getBoolean(7),
                rs.getTimestamp(8)?.toInstant(), rs.getObject(9)?.let { (it as Number).toLong() })
        }, inspectionId, *args)
        if (rows.isEmpty()) return emptyList()
        val photos = HashMap<UUID, MutableList<KnownDefectPhoto>>()
        j.query(
            "SELECT e.defect_id, e.id, e.captured_at FROM taktovia.defect_evidence e WHERE e.defect_id = ANY(CAST(? AS uuid[])) AND e.confidentiality = 'STANDARD' " +
                "AND NOT EXISTS (SELECT 1 FROM taktovia.defect_evidence r WHERE r.tenant_id = e.tenant_id AND r.replaces_evidence_id = e.id) ORDER BY e.created_at, e.id",
            { rs, _ -> photos.getOrPut(rs.getObject(1, UUID::class.java)) { mutableListOf() }.add(KnownDefectPhoto(rs.getObject(2, UUID::class.java), rs.getTimestamp(3).toInstant())) },
            rows.joinToString(",", "{", "}") { it.id.toString() })   // nur UUIDs, nie Fremdtext
        return rows.map { KnownDefectView(it.id, it.number, it.category, it.kind, it.area, photos[it.id] ?: emptyList(), it.status !in CLOSED, it.acked, it.ackedAt, it.ackedRevision) }
    }

    private fun requireRole(ctx: RequestContext, allowed: Set<String>) {
        if (allowed.none { it in ctx.roles }) throw ProblemException(ErrorCode.FORBIDDEN, "Altschäden sehen Fahrer, Disposition und Werkstatt; quittieren Fahrer und Werkstatt.")
    }

    private fun actor(ctx: RequestContext) = ActorRef((if (ctx.actor.type == ActorType.SERVICE) "service:" else "user:") + ctx.actor.subject)

    private fun sqlState(e: Throwable): String? = generateSequence(e) { it.cause }.filterIsInstance<SQLException>().firstOrNull()?.sqlState

    private companion object {
        val READERS = setOf("driver", "dispatcher", "workshop")
        val ACKNOWLEDGERS = setOf("driver", "workshop")
        val CLOSED = setOf("CLOSED", "DISMISSED")
    }
}

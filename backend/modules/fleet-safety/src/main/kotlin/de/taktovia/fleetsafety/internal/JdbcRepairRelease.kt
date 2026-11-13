package de.taktovia.fleetsafety.internal

import de.taktovia.fleetsafety.api.CreateRepairOrder
import de.taktovia.fleetsafety.api.DefectState
import de.taktovia.fleetsafety.api.ReleaseDecisionView
import de.taktovia.fleetsafety.api.ReleaseVehicle
import de.taktovia.fleetsafety.api.RepairOrderView
import de.taktovia.fleetsafety.api.RepairOrders
import de.taktovia.fleetsafety.api.RepairStatus
import de.taktovia.fleetsafety.api.RepairTransition
import de.taktovia.fleetsafety.api.VehicleReleases
import de.taktovia.platform.approval.FourEyes
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
import java.time.Instant
import java.util.UUID

/**
 * Protokoll abgewiesener Freigabeversuche (WP-M3-51, AT-51): Audit `fleet.release.attempt_rejected` und Ereignis `fleet.release_attempt_rejected` in einer **eigenen** Transaktion, damit der Fehlversuch
 * auch dann festgehalten wird, wenn die abgewiesene Anfrage selbst nichts schreiben durfte. Nur Kennungen und Codes, nie Fallinhalt; ein Ausfall des Protokolls verdeckt den ursprünglichen Fehler nie.
 */
internal class ReleaseAttempts(private val ctxTx: ContextTransactions, private val audit: AuditPort, private val outbox: Outbox, private val clock: Clock) {
    fun record(ctx: RequestContext, vehicleId: UUID, repairOrderId: UUID?, reasonCode: String, status: Int) {
        try {
            ctxTx.run(ctx) { j -> write(j, ctx, vehicleId, repairOrderId, reasonCode, status) }
        } catch (_: RuntimeException) { /* das Protokoll darf den eigentlichen Fehler nicht ersetzen */ }
    }

    @AuditRequired
    private fun write(j: JdbcOperations, ctx: RequestContext, vehicleId: UUID, repairOrderId: UUID?, reasonCode: String, status: Int) {
        val actor = FourEyes.refOf(ctx)
        audit.record(j, AuditEntry(actor, AuditActions.RELEASE_ATTEMPT_REJECTED, ObjectRef("vehicle", vehicleId), ctx.correlationId, linkedMapOf(
            "reason_code" to reasonCode, "status" to status, "repair_order_id" to repairOrderId?.toString(), "roles" to ctx.roles.sorted())))
        outbox.append(j, OutboxEvent(UUID.randomUUID(), 1, "fleet.release_attempt_rejected", linkedMapOf(
            "vehicle_id" to vehicleId.toString(), "repair_order_id" to repairOrderId?.toString(), "reason_code" to reasonCode, "status" to status), clock.instant(), correlationId = ctx.correlationId))
    }
}

/** Akte unter Zeilensperre lesen (Sperrreihenfolge: Akte, Auftrag, Fahrzeug). */
private const val DEFECT_ROW_LOCK = "SELECT ${JdbcDefects.COLUMNS} FROM taktovia.defect_case d WHERE d.id = ? FOR UPDATE"

/** Reparaturaufträge (WP-M3-51), siehe [RepairOrders]. */
internal class JdbcRepairOrders(
    private val ctxTx: ContextTransactions, private val audit: AuditPort, private val outbox: Outbox, private val clock: Clock, private val defects: JdbcDefects,
) : RepairOrders {

    override fun create(ctx: RequestContext, cmd: CreateRepairOrder): RepairOrderView {
        requireWorkshop(ctx)
        try {
            return ctxTx.run(ctx) { j -> doCreate(j, ctx, FourEyes.refOf(ctx), cmd) }
        } catch (e: DataIntegrityViolationException) {
            if (sqlState(e) == "23505") throw ProblemException(ErrorCode.CONFLICT, "Zu dieser Akte besteht bereits ein nicht stornierter Reparaturauftrag.", cause = e)
            throw e
        }
    }

    @AuditRequired
    private fun doCreate(j: JdbcOperations, ctx: RequestContext, actor: ActorRef, cmd: CreateRepairOrder): RepairOrderView {
        // Sperrreihenfolge wie die Triage: zuerst die Akte (Aktenstatus und Auftrag ändern sich gemeinsam), danach der Auftrag
        val defect = j.query(DEFECT_ROW_LOCK, JdbcDefects.MAPPER, cmd.defectId).firstOrNull() ?: throw ProblemException.notFound()
        if (defect.status != DefectState.IN_PROGRESS.name) throw ProblemException(ErrorCode.INVALID_STATE_TRANSITION, "Ein Reparaturauftrag ist nur zu einer Akte im Status IN_PROGRESS zulässig (aktuell ${defect.status}).")
        val id = UUID.randomUUID()
        val view = j.query(
            "INSERT INTO taktovia.repair_order AS r (tenant_id, id, defect_id, vehicle_id, status, workshop_ref, planned_at, cost_reference, created_by) VALUES (taktovia.current_tenant(), ?, ?, ?, 'OPEN', ?, ?, ?, ?) RETURNING $COLUMNS",
            MAPPER, id, defect.id, defect.vehicleId, cmd.workshopRef, cmd.plannedAt?.let(Timestamp::from), cmd.costReference, actor.value,
        ).single()
        defects.applyStatus(j, actor, ctx.correlationId, defect, DefectState.REPAIR, null, reopen = false)
        audit.record(j, AuditEntry(actor, AuditActions.REPAIR_ORDER_CREATED, ObjectRef("repair_order", id), ctx.correlationId, mapOf("case_number" to defect.caseNumber, "vehicle_id" to defect.vehicleId.toString())))
        outbox.append(j, OutboxEvent(id, 1, "repair.order_created", mapOf("repair_order_id" to id.toString(), "defect_id" to defect.id.toString(), "vehicle_id" to defect.vehicleId.toString()), clock.instant(), correlationId = ctx.correlationId))
        return view
    }

    override fun get(ctx: RequestContext, id: UUID): RepairOrderView = ctxTx.run(ctx, readOnly = true) { j ->
        requireReader(ctx); j.query("SELECT $COLUMNS FROM taktovia.repair_order r WHERE r.id = ?", MAPPER, id).firstOrNull() ?: throw ProblemException.notFound()
    }

    override fun list(ctx: RequestContext, defectId: UUID?, vehicleId: UUID?, limit: Int, after: Pair<Instant, UUID>?): List<RepairOrderView> {
        requireReader(ctx)
        return ctxTx.run(ctx, readOnly = true) { j ->
            val ts = after?.first?.let(Timestamp::from)
            j.query("SELECT $COLUMNS FROM taktovia.repair_order r WHERE (CAST(? AS uuid) IS NULL OR r.defect_id = CAST(? AS uuid)) AND (CAST(? AS uuid) IS NULL OR r.vehicle_id = CAST(? AS uuid)) " +
                "AND (CAST(? AS timestamptz) IS NULL OR (r.created_at, r.id) > (CAST(? AS timestamptz), CAST(? AS uuid))) ORDER BY r.created_at, r.id LIMIT ?",
                MAPPER, defectId, defectId, vehicleId, vehicleId, ts, ts, after?.second, limit + 1)
        }
    }

    override fun transition(ctx: RequestContext, id: UUID, expectedRevision: Long, cmd: RepairTransition): RepairOrderView {
        requireWorkshop(ctx)
        try {
            return ctxTx.run(ctx) { j -> doTransition(j, ctx, FourEyes.refOf(ctx), id, expectedRevision, cmd) }
        } catch (e: DataIntegrityViolationException) {
            if (sqlState(e) == "23514") throw ProblemException(ErrorCode.INVALID_STATE_TRANSITION, "Der Übergang ist für diesen Reparaturauftrag nicht zulässig.", cause = e)
            throw e
        }
    }

    @AuditRequired
    private fun doTransition(j: JdbcOperations, ctx: RequestContext, actor: ActorRef, id: UUID, expected: Long, cmd: RepairTransition): RepairOrderView {
        val defectId = j.query("SELECT defect_id FROM taktovia.repair_order WHERE id = ?", { rs, _ -> rs.getObject(1, UUID::class.java) }, id).firstOrNull() ?: throw ProblemException.notFound()
        val defect = j.query(DEFECT_ROW_LOCK, JdbcDefects.MAPPER, defectId).single()   // Akte zuerst, dann der Auftrag
        val before = j.query("SELECT $COLUMNS FROM taktovia.repair_order r WHERE r.id = ? FOR UPDATE", MAPPER, id).single()
        if (before.revision != expected) throw ProblemException(ErrorCode.REVISION_MISMATCH, "Der Reparaturauftrag wurde zwischenzeitlich geändert; aktuelle Revision lesen.")
        val to = cmd.to
        if (before.status.isFinal) throw ProblemException(ErrorCode.IMMUTABLE_RECORD, "Der Reparaturauftrag ist ${before.status.name} und unveränderlich.")
        val allowed = when (before.status) {
            RepairStatus.OPEN -> setOf(RepairStatus.IN_PROGRESS, RepairStatus.CANCELED)
            RepairStatus.IN_PROGRESS -> setOf(RepairStatus.PROOF_RECORDED, RepairStatus.CANCELED)
            else -> emptySet()
        }
        if (to !in allowed) throw ProblemException(ErrorCode.INVALID_STATE_TRANSITION, "Der Übergang von ${before.status.name} nach ${to.name} ist nicht zulässig.")
        val reason = cmd.reason?.trim()?.takeIf { it.isNotEmpty() }
        var cost = before.costReference
        if (to == RepairStatus.PROOF_RECORDED) {
            val proof = cmd.proofEvidenceId ?: throw ProblemException.invalid(Violation("/proof_evidence_id", ViolationCode.REQUIRED))
            // Nachweis: Evidenz **dieser** Akte (RLS: eigener Mandant); ein fremder oder erfundener Nachweis ist ein Feldfehler, keine Existenzauskunft
            if (j.queryForObject("SELECT count(*) FROM taktovia.defect_evidence WHERE id = ? AND defect_id = ?", Int::class.java, proof, defectId) != 1) throw ProblemException.invalid(Violation("/proof_evidence_id", ViolationCode.INVALID_VALUE))
            cost = cmd.costReference ?: before.costReference ?: throw ProblemException.invalid(Violation("/cost_reference", ViolationCode.REQUIRED))
        }
        if (to == RepairStatus.CANCELED && reason == null) throw ProblemException.invalid(Violation("/reason", ViolationCode.REQUIRED))
        val after = j.query(
            "UPDATE taktovia.repair_order AS r SET status = ?, revision = r.revision + 1, updated_at = clock_timestamp(), cost_reference = ?, proof_evidence_id = ?, proof_recorded_at = ?, proof_recorded_by = ?, cancel_reason = ? " +
                "WHERE r.id = ? AND r.revision = ? RETURNING $COLUMNS",
            MAPPER, to.name, cost, if (to == RepairStatus.PROOF_RECORDED) cmd.proofEvidenceId else null, if (to == RepairStatus.PROOF_RECORDED) Timestamp.from(clock.instant()) else null,
            if (to == RepairStatus.PROOF_RECORDED) actor.value else null, if (to == RepairStatus.CANCELED) reason else null, id, expected,
        ).singleOrNull() ?: throw ProblemException(ErrorCode.REVISION_MISMATCH, "Der Reparaturauftrag wurde zwischenzeitlich geändert; aktuelle Revision lesen.")
        // Stornierung: die Akte fällt aus der Reparatur zurück in die Bearbeitung (Zustandsautomat `defect_case`)
        if (to == RepairStatus.CANCELED && defect.status == DefectState.REPAIR.name) defects.applyStatus(j, actor, ctx.correlationId, defect, DefectState.IN_PROGRESS, null, reopen = false)
        audit.record(j, AuditEntry(actor, AuditActions.REPAIR_ORDER_CHANGED, ObjectRef("repair_order", id), ctx.correlationId, mapOf(
            "case_number" to defect.caseNumber, "from" to before.status.name, "to" to to.name, "revision" to after.revision)))
        if (to == RepairStatus.PROOF_RECORDED) outbox.append(j, OutboxEvent(id, after.revision, "repair.proof_recorded", mapOf(
            "repair_order_id" to id.toString(), "defect_id" to defectId.toString(), "vehicle_id" to after.vehicleId.toString(), "proof_evidence_id" to cmd.proofEvidenceId.toString()), clock.instant(), correlationId = ctx.correlationId))
        return after
    }

    private fun requireWorkshop(ctx: RequestContext) { if ("workshop" !in ctx.roles) throw ProblemException(ErrorCode.FORBIDDEN, "Reparaturaufträge pflegt nur die Werkstatt.") }
    private fun requireReader(ctx: RequestContext) { if (ctx.roles.none { it in READERS }) throw ProblemException(ErrorCode.FORBIDDEN, "Reparaturaufträge sehen Disposition, Werkstatt und Betriebsleitung.") }
    private fun sqlState(e: Throwable): String? = generateSequence(e) { it.cause }.filterIsInstance<SQLException>().firstOrNull()?.sqlState

    private companion object {
        val READERS = setOf("dispatcher", "workshop", "tenant_admin")
        const val COLUMNS = "r.id, r.defect_id, r.vehicle_id, r.status, r.workshop_ref, r.planned_at, r.cost_reference, r.proof_evidence_id, r.proof_recorded_at, r.created_at, r.revision"
        val MAPPER = RowMapper { rs, _ ->
            RepairOrderView(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getObject(3, UUID::class.java), RepairStatus.valueOf(rs.getString(4)), rs.getString(5),
                rs.getTimestamp(6)?.toInstant(), rs.getString(7), rs.getObject(8, UUID::class.java), rs.getTimestamp(9)?.toInstant(), rs.getTimestamp(10).toInstant(), rs.getLong(11))
        }
    }
}

/** Wiederfreigabe (WP-M3-51), siehe [VehicleReleases]. */
internal class JdbcVehicleReleases(
    private val ctxTx: ContextTransactions, private val audit: AuditPort, private val outbox: Outbox, private val clock: Clock, private val readiness: JdbcReadiness, private val attempts: ReleaseAttempts,
) : VehicleReleases {

    override fun release(ctx: RequestContext, vehicleId: UUID, expectedSafetyRevision: Long, cmd: ReleaseVehicle): ReleaseDecisionView {
        try {
            if (ctx.roles.none { it in RELEASERS } || ctx.actor.type != ActorType.HUMAN) throw ProblemException(ErrorCode.FORBIDDEN, "Die Wiederfreigabe erteilen nur Werkstatt (technische Freigabe) und Betriebsleitung (Zweitfreigabe).")
            return ctxTx.run(ctx) { j -> doRelease(j, ctx, FourEyes.refOf(ctx), vehicleId, expectedSafetyRevision, cmd) }
        } catch (e: ProblemException) {
            // jeder abgewiesene Versuch (403, 409, 412, 422) ist ein Fehlversuch im Audit; unbekannt/fremd (404) verrät nichts und bleibt ohne Eintrag zu fremden Objekten
            if (e.code.status != 404) attempts.record(ctx, vehicleId, cmd.repairOrderId, e.code.name, e.code.status)
            throw e
        } catch (e: DataIntegrityViolationException) {
            if (sqlState(e) == "23505") {   // zwei gleichzeitige gleichartige Entscheidungen: die zweite verliert gegen den Unique-Index
                val conflict = ProblemException(ErrorCode.CONFLICT, "Zu diesem Reparaturauftrag besteht bereits eine gleichartige Entscheidung.", cause = e)
                attempts.record(ctx, vehicleId, cmd.repairOrderId, conflict.code.name, conflict.code.status); throw conflict
            }
            throw e
        }
    }

    private class Order(val defectId: UUID, val vehicleId: UUID, val status: String, val proofEvidenceId: UUID?, val proofAt: Instant?)
    private class Existing(val id: UUID, val decision: String, val decidedBy: String, val decidedByRole: String?, val secondApprover: String?)

    @AuditRequired
    private fun doRelease(j: JdbcOperations, ctx: RequestContext, actor: ActorRef, vehicleId: UUID, expected: Long, cmd: ReleaseVehicle): ReleaseDecisionView {
        val role = if ("workshop" in ctx.roles) "workshop" else "tenant_admin"
        // Sperrreihenfolge: Akte, Reparaturauftrag, Fahrzeug (wie Triage und Anlage des Auftrags)
        val order = j.query("SELECT defect_id, vehicle_id, status, proof_evidence_id, proof_recorded_at FROM taktovia.repair_order WHERE id = ?",
            { rs, _ -> Order(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getString(3), rs.getObject(4, UUID::class.java), rs.getTimestamp(5)?.toInstant()) }, cmd.repairOrderId).firstOrNull()
            ?.takeIf { it.vehicleId == vehicleId } ?: throw ProblemException.notFound()
        val caseNumber = j.query("SELECT case_number FROM taktovia.defect_case WHERE id = ? FOR UPDATE", { rs, _ -> rs.getString(1) }, order.defectId).single()
        val status = j.query("SELECT status FROM taktovia.repair_order WHERE id = ? FOR UPDATE", { rs, _ -> rs.getString(1) }, cmd.repairOrderId).single()
        val currentRevision = readiness.lockedSafetyRevision(j, vehicleId) ?: throw ProblemException.notFound()
        if (currentRevision != expected) throw ProblemException(ErrorCode.REVISION_MISMATCH, "Der Sicherheitsstand des Fahrzeugs hat sich geändert (neue Sperre oder Freigabe); aktuellen Stand lesen und neu entscheiden.")

        val decisions = j.query("SELECT id, decision, decided_by, decided_by_role, second_approver FROM taktovia.vehicle_release_decision WHERE repair_order_id = ? ORDER BY created_at, id",
            { rs, _ -> Existing(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)) }, cmd.repairOrderId)
        decisions.firstOrNull { it.decision == "RELEASED" }?.let { return view(j, it.id, replayed = true) }   // bereits freigegeben: dieselbe Entscheidung, keine zweite

        if (status != "PROOF_RECORDED") throw ProblemException(ErrorCode.INVALID_STATE_TRANSITION, "Der Reparaturnachweis fehlt: Der Reparaturauftrag ist im Status $status; das Fahrzeug bleibt gesperrt.", violations = listOf(Violation("/repair_order_id", ViolationCode.INVALID_VALUE)))
        val inspectionOk = j.query("SELECT 1 FROM taktovia.inspection_run WHERE id = ? AND vehicle_id = ? AND status = 'COMPLETED' AND outcome IN ('PASSED', 'PASSED_RESTRICTED') AND completed_at >= ?",
            { _, _ -> 1 }, cmd.followUpInspectionId, vehicleId, Timestamp.from(order.proofAt ?: Instant.EPOCH)).isNotEmpty()
        if (!inspectionOk) throw ProblemException(ErrorCode.INVALID_STATE_TRANSITION, "Es fehlt eine bestandene Folgeprüfung dieses Fahrzeugs nach dem Reparaturnachweis; das Fahrzeug bleibt gesperrt.", violations = listOf(Violation("/follow_up_inspection_id", ViolationCode.INVALID_VALUE)))

        val pending = decisions.firstOrNull { it.decision == "PENDING_SECOND_APPROVAL" }
        val now = clock.instant()
        if (pending == null) {
            // erste Entscheidung: nur die Werkstatt (technische Freigabe); eine benannte zweite Person ist nie die erste selbst
            if (role != "workshop") throw ProblemException(ErrorCode.FORBIDDEN, "Freigaberolle fehlt: die erste (technische) Freigabe erteilt die Werkstatt.")
            if (cmd.secondApprover != null && "user:${cmd.secondApprover}" == actor.value) throw ProblemException.invalid(Violation("/second_approver_ref", ViolationCode.INVALID_VALUE))
            val id = UUID.randomUUID()
            j.update("INSERT INTO taktovia.vehicle_release_decision (tenant_id, id, vehicle_id, repair_order_id, follow_up_inspection_id, decision, decided_by, second_approver, safety_revision, defect_id, decided_by_role, reason, proof_evidence_id) " +
                "VALUES (taktovia.current_tenant(), ?, ?, ?, ?, 'PENDING_SECOND_APPROVAL', ?, ?, ?, ?, ?, ?, ?)", id, vehicleId, cmd.repairOrderId, cmd.followUpInspectionId, actor.value, cmd.secondApprover?.let { "user:$it" },
                currentRevision, order.defectId, role, cmd.reason.trim(), order.proofEvidenceId)
            record(j, ctx, actor, id, vehicleId, cmd, "PENDING_SECOND_APPROVAL", role, caseNumber, currentRevision, now)
            return view(j, id, replayed = false)
        }
        // zweite Entscheidung: eine andere Identität, die benannte zweite Person (falls benannt), Rolle Werkstatt oder Betriebsleitung
        FourEyes.requireDistinct(pending.decidedBy, actor.value)
        if (pending.secondApprover != null && pending.secondApprover != actor.value) throw ProblemException(ErrorCode.FORBIDDEN, "Für diese Freigabe ist eine andere Person als zweite Freigebende benannt.")
        val liftCount = readiness.activeSafetyBlockCount(j, vehicleId, caseNumber)
        val finalRevision = currentRevision + maxOf(liftCount, 1)
        val id = UUID.randomUUID()
        j.update("INSERT INTO taktovia.vehicle_release_decision (tenant_id, id, vehicle_id, repair_order_id, follow_up_inspection_id, decision, decided_by, second_approver, safety_revision, defect_id, decided_by_role, second_approver_role, reason, proof_evidence_id, supersedes_decision_id) " +
            "VALUES (taktovia.current_tenant(), ?, ?, ?, ?, 'RELEASED', ?, ?, ?, ?, ?, ?, ?, ?, ?)", id, vehicleId, cmd.repairOrderId, cmd.followUpInspectionId, pending.decidedBy, actor.value, finalRevision, order.defectId, pending.decidedByRole, role,
            cmd.reason.trim(), order.proofEvidenceId, pending.id)
        val (lifted, revision) = readiness.liftSafetyBlocksForCase(j, actor, ctx.correlationId, vehicleId, id, caseNumber)
        check(revision == finalRevision) { "safety_revision nach der Freigabe: $revision statt $finalRevision" }
        record(j, ctx, actor, id, vehicleId, cmd, "RELEASED", role, caseNumber, revision, now, lifted)
        return view(j, id, replayed = false, lifted = lifted)
    }

    private fun record(j: JdbcOperations, ctx: RequestContext, actor: ActorRef, id: UUID, vehicleId: UUID, cmd: ReleaseVehicle, decision: String, role: String, caseNumber: String, revision: Long, now: Instant, lifted: List<UUID> = emptyList()) {
        audit.record(j, AuditEntry(actor, AuditActions.RELEASE_DECISION_RECORDED, ObjectRef("vehicle", vehicleId), ctx.correlationId, linkedMapOf(
            "decision_id" to id.toString(), "decision" to decision, "role" to role, "repair_order_id" to cmd.repairOrderId.toString(), "case_number" to caseNumber, "safety_revision" to revision, "lifted_blocks" to lifted.size)))
        outbox.append(j, OutboxEvent(id, 1, "fleet.release_decided", linkedMapOf(
            "decision_id" to id.toString(), "vehicle_id" to vehicleId.toString(), "repair_order_id" to cmd.repairOrderId.toString(), "decision" to decision, "safety_revision" to revision), now, correlationId = ctx.correlationId))
    }

    private fun view(j: JdbcOperations, id: UUID, replayed: Boolean, lifted: List<UUID>? = null): ReleaseDecisionView {
        val liftedIds = lifted ?: j.query("SELECT id FROM taktovia.vehicle_block WHERE release_decision_id = ? ORDER BY id", { rs, _ -> rs.getObject(1, UUID::class.java) }, id)
        return j.query("SELECT id, vehicle_id, repair_order_id, defect_id, decision, decided_by, decided_by_role, second_approver, second_approver_role, decided_at, safety_revision, proof_evidence_id, revision FROM taktovia.vehicle_release_decision WHERE id = ?", { rs, _ ->
            ReleaseDecisionView(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getObject(3, UUID::class.java), rs.getObject(4, UUID::class.java), rs.getString(5), rs.getString(6), rs.getString(7),
                rs.getString(8), rs.getString(9), rs.getTimestamp(10).toInstant(), rs.getLong(11), rs.getObject(12, UUID::class.java), liftedIds, rs.getLong(13), replayed)
        }, id).single()
    }

    private fun sqlState(e: Throwable): String? = generateSequence(e) { it.cause }.filterIsInstance<SQLException>().firstOrNull()?.sqlState

    private companion object { val RELEASERS = setOf("workshop", "tenant_admin") }
}

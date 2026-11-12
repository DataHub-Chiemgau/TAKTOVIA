package de.taktovia.fleetsafety.internal

import de.taktovia.fleetsafety.api.BlockOutcome
import de.taktovia.fleetsafety.api.BlockReason
import de.taktovia.fleetsafety.api.BlockSource
import de.taktovia.fleetsafety.api.BlockStatus
import de.taktovia.fleetsafety.api.DispatchRefusal
import de.taktovia.fleetsafety.api.DispatchVerdict
import de.taktovia.fleetsafety.api.IssueRelease
import de.taktovia.fleetsafety.api.LiftOutcome
import de.taktovia.fleetsafety.api.Readiness
import de.taktovia.fleetsafety.api.ReleaseBlocker
import de.taktovia.fleetsafety.api.ReleaseOutcome
import de.taktovia.fleetsafety.api.ReleasePolicy
import de.taktovia.fleetsafety.api.ReleaseRevokeReason
import de.taktovia.fleetsafety.api.ReleaseState
import de.taktovia.fleetsafety.api.ResourceReleaseView
import de.taktovia.fleetsafety.api.SetBlock
import de.taktovia.fleetsafety.api.VehicleBlockView
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
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Sperr- und Freigabekern (WP-M3-08, siehe [Readiness]). Jede schreibende Operation läuft in genau einer Mandantentransaktion und sperrt als Erstes die Fahrzeugzeile
 * (`FOR UPDATE`); damit sind Sperren, Freigaben und der Hold-Commit der Disposition (`FOR SHARE` auf dieselbe Zeile, nach dem Umlauf) in jeder Verschränkung nacheinander
 * entschieden. Die Datenbank hält die Invarianten zusätzlich (Wächter `vehicle_block_guard`, `resource_release_guard`, `vehicle_safety_revision_guard`, Unique-Indizes).
 * Nur dieses Modul schreibt `vehicle_block`, `resource_release` sowie `vehicle.active_block` und `vehicle.safety_revision`.
 */
internal class JdbcReadiness(
    private val ctxTx: ContextTransactions, private val audit: AuditPort, private val outbox: Outbox, private val clock: Clock, private val policy: ReleasePolicy = ReleasePolicy(),
) : Readiness {

    // ------------------------------------------------------------------------------------------------ Sperren

    override fun setBlock(ctx: RequestContext, cmd: SetBlock): BlockOutcome {
        when (cmd.source) {
            BlockSource.OPERATIONS -> requireRole(ctx, OPERATORS)
            BlockSource.SAFETY -> if (ctx.actor.type != ActorType.SERVICE) throw ProblemException(ErrorCode.FORBIDDEN, "Eine Sicherheitssperre setzt ausschließlich der Server.")
        }
        return ctxTx.run(ctx) { j -> setBlockIn(j, actor(ctx), ctx.correlationId, cmd) }
    }

    /** Sperre in der laufenden Transaktion des Aufrufers (Prüfabschluss, Schadenakte): die Aufrufer im Modul prüfen die Berechtigung selbst. */
    @AuditRequired
    fun setBlockIn(j: JdbcOperations, actor: ActorRef, correlationId: UUID, cmd: SetBlock): BlockOutcome {
        val vehicle = lockVehicle(j, cmd.vehicleId, exclusive = true) ?: throw ProblemException.notFound()
        existingBlock(j, cmd)?.let { return BlockOutcome(it, created = false, safetyRevision = vehicle.safetyRevision, revokedReleaseIds = emptyList()) }
        val id = UUID.randomUUID()
        j.update(
            "INSERT INTO taktovia.vehicle_block (tenant_id, id, vehicle_id, source, reason_code, reason_ref, set_by) VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?)",
            id, cmd.vehicleId, cmd.source.name, cmd.reason.name, cmd.reasonRef, actor.value,
        )
        val revision = bump(j, cmd.vehicleId, blocked = true)
        val now = Timestamp.from(clock.instant())
        val revoked = j.query(
            "UPDATE taktovia.resource_release SET revoked_at = ?, revoked_by = ?, revoke_reason = 'BLOCK_SET' WHERE vehicle_id = ? AND revoked_at IS NULL AND valid_until > ? RETURNING id, shift_id",
            { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getObject(2, UUID::class.java) }, now, actor.value, cmd.vehicleId, now,
        )
        val view = block(j, id)!!
        audit.record(j, AuditEntry(actor, AuditActions.VEHICLE_BLOCKED, ObjectRef("vehicle", cmd.vehicleId), correlationId, linkedMapOf(
            "block_id" to id.toString(), "source" to cmd.source.name, "reason_code" to cmd.reason.name, "reason_ref" to cmd.reasonRef, "safety_revision" to revision, "revoked_releases" to revoked.size)))
        outbox.append(j, OutboxEvent(id, 1, "fleet.vehicle_blocked", linkedMapOf(
            "vehicle_id" to cmd.vehicleId.toString(), "block_id" to id.toString(), "source" to cmd.source.name, "reason_code" to cmd.reason.name, "safety_revision" to revision),
            clock.instant(), correlationId = correlationId))
        for ((releaseId, shiftId) in revoked) {
            audit.record(j, AuditEntry(actor, AuditActions.RELEASE_REVOKED, ObjectRef("resource_release", releaseId), correlationId, linkedMapOf(
                "vehicle_id" to cmd.vehicleId.toString(), "shift_id" to shiftId.toString(), "reason_code" to ReleaseRevokeReason.BLOCK_SET.name, "safety_revision" to revision)))
            outbox.append(j, OutboxEvent(releaseId, 2, "fleet.release_revoked", linkedMapOf(
                "release_id" to releaseId.toString(), "vehicle_id" to cmd.vehicleId.toString(), "shift_id" to shiftId.toString(), "reason_code" to ReleaseRevokeReason.BLOCK_SET.name),
                clock.instant(), correlationId = correlationId))
        }
        return BlockOutcome(view, created = true, safetyRevision = revision, revokedReleaseIds = revoked.map { it.first })
    }

    override fun liftOperationalBlock(ctx: RequestContext, vehicleId: UUID, blockId: UUID): LiftOutcome {
        requireRole(ctx, OPERATORS)
        return ctxTx.run(ctx) { j -> liftIn(j, actor(ctx), ctx.correlationId, vehicleId, blockId, null) }
    }

    override fun liftSafetyBlock(ctx: RequestContext, vehicleId: UUID, blockId: UUID, releaseDecisionId: UUID): LiftOutcome {
        requireRole(ctx, SAFETY_LIFTERS)
        return ctxTx.run(ctx) { j -> liftIn(j, actor(ctx), ctx.correlationId, vehicleId, blockId, releaseDecisionId) }
    }

    @AuditRequired
    private fun liftIn(j: JdbcOperations, actor: ActorRef, correlationId: UUID, vehicleId: UUID, blockId: UUID, decisionId: UUID?): LiftOutcome {
        lockVehicle(j, vehicleId, exclusive = true) ?: throw ProblemException.notFound()
        val current = block(j, blockId)?.takeIf { it.vehicleId == vehicleId } ?: throw ProblemException.notFound()
        if (current.status != BlockStatus.ACTIVE) throw ProblemException(ErrorCode.INVALID_STATE_TRANSITION, "Die Sperre ist bereits aufgehoben; eine neue Sperre ist eine neue Zeile.")
        if (decisionId == null && current.source == BlockSource.SAFETY)
            throw ProblemException(ErrorCode.INVALID_STATE_TRANSITION, "Eine Sicherheitssperre hebt nur die technische Wiederfreigabe auf, nicht die Disposition.")
        if (decisionId != null) {
            if (current.source != BlockSource.SAFETY) throw ProblemException(ErrorCode.INVALID_STATE_TRANSITION, "Eine Wiederfreigabeentscheidung hebt nur Sicherheitssperren auf.")
            val ok = j.query("SELECT 1 FROM taktovia.vehicle_release_decision WHERE id = ? AND vehicle_id = ? AND decision = 'RELEASED'", { _, _ -> 1 }, decisionId, vehicleId).isNotEmpty()
            if (!ok) throw ProblemException(ErrorCode.CONFLICT, "Es liegt keine abgeschlossene Wiederfreigabeentscheidung (RELEASED) für dieses Fahrzeug vor.")
        }
        j.update("UPDATE taktovia.vehicle_block SET status = 'LIFTED', lifted_by = ?, lifted_at = ?, release_decision_id = ? WHERE id = ? AND status = 'ACTIVE'",
            actor.value, Timestamp.from(clock.instant()), decisionId, blockId)
        val stillBlocked = j.queryForObject("SELECT count(*) FROM taktovia.vehicle_block WHERE vehicle_id = ? AND status = 'ACTIVE'", Int::class.java, vehicleId)!! > 0
        val revision = bump(j, vehicleId, blocked = stillBlocked)
        val view = block(j, blockId)!!
        audit.record(j, AuditEntry(actor, AuditActions.VEHICLE_BLOCK_LIFTED, ObjectRef("vehicle", vehicleId), correlationId, linkedMapOf(
            "block_id" to blockId.toString(), "source" to current.source.name, "reason_code" to current.reason.name, "safety_revision" to revision, "still_blocked" to stillBlocked,
            "release_decision_id" to decisionId?.toString())))
        outbox.append(j, OutboxEvent(blockId, 2, "fleet.vehicle_block_lifted", linkedMapOf(
            "vehicle_id" to vehicleId.toString(), "block_id" to blockId.toString(), "source" to current.source.name, "safety_revision" to revision, "release_decision_id" to decisionId?.toString()),
            clock.instant(), correlationId = correlationId))
        return LiftOutcome(view, revision, stillBlocked)
    }

    override fun activeBlocks(ctx: RequestContext, vehicleId: UUID): List<VehicleBlockView> = ctxTx.run(ctx, readOnly = true) { j ->
        if (j.query("SELECT 1 FROM taktovia.vehicle WHERE id = ?", { _, _ -> 1 }, vehicleId).isEmpty()) throw ProblemException.notFound()
        j.query("$BLOCK_SELECT WHERE b.vehicle_id = ? AND b.status = 'ACTIVE' ORDER BY b.set_at, b.id", BLOCK, vehicleId)
    }

    // ------------------------------------------------------------------------------------------------ Freigabe

    override fun issueRelease(ctx: RequestContext, cmd: IssueRelease): ReleaseOutcome {
        if (ctx.actor.type != ActorType.SERVICE) requireRole(ctx, RELEASERS)
        return ctxTx.run(ctx) { j -> issueReleaseIn(j, actor(ctx), ctx.correlationId, cmd) }
    }

    /**
     * Erteilt die Freigabe in der laufenden Transaktion des Aufrufers (Prüfabschluss, WP-M3-14). Bedingungen alle unter der Sperre der Fahrzeugzeile geprüft: Schicht (angetreten
     * oder veröffentlicht, nicht beendet, gehört zum Fahrzeug), Prüfung (abgeschlossen, bestanden, gleiche Kombination Fahrer, Fahrzeug, Schicht), Qualifikation des Fahrers (Fahrerzeile
     * gesperrt gelesen), keine aktive Sperre, Fahrzeug aktiv. Wiederholung für denselben Prüflauf liefert die bestehende Freigabe.
     */
    @AuditRequired
    fun issueReleaseIn(j: JdbcOperations, actor: ActorRef, correlationId: UUID, cmd: IssueRelease): ReleaseOutcome {
        val vehicle = lockVehicle(j, cmd.vehicleId, exclusive = true) ?: throw ProblemException.notFound()
        val shift = shift(j, cmd.shiftId) ?: throw ProblemException.notFound()
        val inspection = inspection(j, cmd.inspectionId)
        existingFor(j, cmd.inspectionId)?.let { r ->
            if (r.vehicleId != cmd.vehicleId || r.shiftId != cmd.shiftId) throw ProblemException(ErrorCode.CONFLICT, "Zu diesem Prüflauf besteht eine Freigabe für eine andere Kombination.")
            if (r.state == ReleaseState.REVOKED) throw ProblemException(ErrorCode.IMMUTABLE_RECORD, "Die widerrufene Freigabe wird nicht wieder gültig; eine neue Prüfung erzeugt eine neue Freigabe.")
            return ReleaseOutcome.Issued(r, replayed = true)
        }
        val now = clock.instant()
        val missing = blockers(j, vehicle, shift, inspection, cmd, now, lock = true)
        if (missing.isNotEmpty()) return ReleaseOutcome.Refused(missing.toList())

        val qualification = qualification(j, shift.driverRef, now)!!
        val validUntil = minOf(shift.endsAt, now.plus(policy.maxValidity))
        val id = UUID.randomUUID()
        j.update(
            "INSERT INTO taktovia.resource_release (tenant_id, id, vehicle_id, shift_id, safety_revision, inspection_ref, qualification_revision, valid_until, created_at, issued_by) VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            id, cmd.vehicleId, cmd.shiftId, vehicle.safetyRevision, cmd.inspectionId, qualification, Timestamp.from(validUntil), Timestamp.from(now), actor.value,
        )
        // die Freigabe löscht den abgeleiteten Sperrzustand des Fahrzeugs (V1-Vorgabe true = „nicht freigegeben“); es besteht keine aktive Sperre (oben geprüft)
        j.update("UPDATE taktovia.vehicle SET active_block = false WHERE id = ? AND active_block", cmd.vehicleId)
        val view = release(j, id)!!
        audit.record(j, AuditEntry(actor, AuditActions.RELEASE_ISSUED, ObjectRef("resource_release", id), correlationId, linkedMapOf(
            "vehicle_id" to cmd.vehicleId.toString(), "shift_id" to cmd.shiftId.toString(), "inspection_id" to cmd.inspectionId.toString(), "safety_revision" to vehicle.safetyRevision,
            "qualification_revision" to qualification, "valid_until" to validUntil.toString())))
        outbox.append(j, OutboxEvent(id, 1, "fleet.release_issued", linkedMapOf(
            "release_id" to id.toString(), "vehicle_id" to cmd.vehicleId.toString(), "shift_id" to cmd.shiftId.toString(), "inspection_id" to cmd.inspectionId.toString(),
            "safety_revision" to vehicle.safetyRevision, "valid_until" to validUntil.toString()), clock.instant(), correlationId = correlationId))
        return ReleaseOutcome.Issued(view, replayed = false)
    }

    /**
     * Fehlende Voraussetzungen der Einsatzfreigabe für (Fahrzeug, Schicht, Prüflauf), **ohne** zu sperren und ohne etwas zu schreiben (Anzeige „fehlende Voraussetzungen“, WP-M3-09).
     * Dieselbe Regel wie beim Erteilen; ein bereits widerrufener Freigabestand steht als [ReleaseBlocker.RELEASE_REVOKED] da.
     */
    fun missing(j: JdbcOperations, vehicleId: UUID, shiftId: UUID, inspectionId: UUID): List<ReleaseBlocker> {
        val vehicle = lockVehicle(j, vehicleId, exclusive = false, lock = false) ?: return listOf(ReleaseBlocker.NO_INSPECTION)
        val shift = shift(j, shiftId) ?: return listOf(ReleaseBlocker.VEHICLE_CHANGED)
        val inspection = inspection(j, inspectionId)
        val cmd = IssueRelease(vehicleId, shiftId, inspectionId)
        val list = blockers(j, vehicle, shift, inspection, cmd, clock.instant(), lock = false).toMutableList()
        if (list.isEmpty() && existingFor(j, inspectionId)?.state == ReleaseState.REVOKED) list += ReleaseBlocker.RELEASE_REVOKED
        return list
    }

    private fun inspection(j: JdbcOperations, id: UUID): Insp? = j.query("SELECT vehicle_id, shift_id, driver_ref, status, outcome FROM taktovia.inspection_run WHERE id = ?",
        { rs, _ -> Insp(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getObject(3, UUID::class.java), rs.getString(4), rs.getString(5)) }, id).firstOrNull()

    private fun blockers(j: JdbcOperations, vehicle: Vehicle, shift: Shift, inspection: Insp?, cmd: IssueRelease, now: Instant, lock: Boolean): Set<ReleaseBlocker> {
        val missing = linkedSetOf<ReleaseBlocker>()
        if (shift.vehicleId != cmd.vehicleId || shift.state !in USABLE_SHIFT || !now.isBefore(shift.endsAt)) missing += ReleaseBlocker.VEHICLE_CHANGED
        when {
            inspection == null || inspection.vehicleId != cmd.vehicleId || inspection.shiftId != cmd.shiftId || inspection.driverRef != shift.driverRef -> missing += ReleaseBlocker.NO_INSPECTION
            inspection.status == "IN_PROGRESS" -> missing += ReleaseBlocker.INSPECTION_OPEN
            inspection.status != "COMPLETED" -> missing += ReleaseBlocker.INSPECTION_EXPIRED
            inspection.outcome != "PASSED" && inspection.outcome != "PASSED_RESTRICTED" -> missing += ReleaseBlocker.NO_INSPECTION
        }
        if (qualification(j, shift.driverRef, now, lock = lock) == null) missing += ReleaseBlocker.QUALIFICATION_MISSING
        val blocks = j.query("SELECT source FROM taktovia.vehicle_block WHERE vehicle_id = ? AND status = 'ACTIVE'", { rs, _ -> rs.getString(1) }, cmd.vehicleId)
        if (blocks.any { it == "SAFETY" }) missing += ReleaseBlocker.DEFECT_BLOCK
        if (blocks.any { it == "OPERATIONS" } || !vehicle.active) missing += ReleaseBlocker.OPERATIONAL_BLOCK
        return missing
    }

    override fun revokeRelease(ctx: RequestContext, releaseId: UUID, reason: ReleaseRevokeReason): ResourceReleaseView {
        if (ctx.actor.type != ActorType.SERVICE) requireRole(ctx, REVOKERS)
        return ctxTx.run(ctx) { j -> revokeIn(j, actor(ctx), ctx.correlationId, releaseId, reason) }
    }

    @AuditRequired
    private fun revokeIn(j: JdbcOperations, actor: ActorRef, correlationId: UUID, releaseId: UUID, reason: ReleaseRevokeReason): ResourceReleaseView {
        val vehicleId = j.query("SELECT vehicle_id FROM taktovia.resource_release WHERE id = ?", { rs, _ -> rs.getObject(1, UUID::class.java) }, releaseId).firstOrNull() ?: throw ProblemException.notFound()
        lockVehicle(j, vehicleId, exclusive = true) ?: throw ProblemException.notFound()
        val before = release(j, releaseId) ?: throw ProblemException.notFound()
        if (before.state == ReleaseState.REVOKED) throw ProblemException(ErrorCode.INVALID_STATE_TRANSITION, "Die Freigabe ist bereits widerrufen.")
        if (before.state == ReleaseState.EXPIRED) throw ProblemException(ErrorCode.INVALID_STATE_TRANSITION, "Die Freigabe ist abgelaufen.")
        j.update("UPDATE taktovia.resource_release SET revoked_at = ?, revoked_by = ?, revoke_reason = ? WHERE id = ? AND revoked_at IS NULL", Timestamp.from(clock.instant()), actor.value, reason.name, releaseId)
        val stillBlocked = j.queryForObject("SELECT count(*) FROM taktovia.vehicle_block WHERE vehicle_id = ? AND status = 'ACTIVE'", Int::class.java, vehicleId)!! > 0
        val revision = bump(j, vehicleId, blocked = stillBlocked)
        val view = release(j, releaseId)!!
        audit.record(j, AuditEntry(actor, AuditActions.RELEASE_REVOKED, ObjectRef("resource_release", releaseId), correlationId, linkedMapOf(
            "vehicle_id" to vehicleId.toString(), "shift_id" to before.shiftId.toString(), "reason_code" to reason.name, "safety_revision" to revision)))
        outbox.append(j, OutboxEvent(releaseId, 2, "fleet.release_revoked", linkedMapOf(
            "release_id" to releaseId.toString(), "vehicle_id" to vehicleId.toString(), "shift_id" to before.shiftId.toString(), "reason_code" to reason.name), clock.instant(), correlationId = correlationId))
        return view
    }

    override fun release(ctx: RequestContext, releaseId: UUID): ResourceReleaseView = ctxTx.run(ctx, readOnly = true) { j -> release(j, releaseId) ?: throw ProblemException.notFound() }

    // ------------------------------------------------------------------------------------------------ Dispositionsprüfung

    override fun assertDispatchable(ctx: RequestContext, vehicleId: UUID, shiftId: UUID, expectedSafetyRevision: Long?, at: Instant): DispatchVerdict =
        ctxTx.run(ctx, readOnly = true) { j -> evaluate(j, vehicleId, shiftId, expectedSafetyRevision, at, lock = false) }

    override fun assertDispatchable(jdbc: JdbcOperations, vehicleId: UUID, shiftId: UUID, expectedSafetyRevision: Long?, at: Instant): DispatchVerdict =
        evaluate(jdbc, vehicleId, shiftId, expectedSafetyRevision, at, lock = true)

    private fun evaluate(j: JdbcOperations, vehicleId: UUID, shiftId: UUID, expected: Long?, at: Instant, lock: Boolean): DispatchVerdict {
        val vehicle = lockVehicle(j, vehicleId, exclusive = false, lock = lock) ?: return DispatchVerdict.Refused(listOf(DispatchRefusal.UNKNOWN_VEHICLE))
        val reasons = linkedSetOf<DispatchRefusal>()
        if (!vehicle.active) reasons += DispatchRefusal.VEHICLE_INACTIVE
        val activeBlock = vehicle.activeBlock || j.queryForObject("SELECT count(*) FROM taktovia.vehicle_block WHERE vehicle_id = ? AND status = 'ACTIVE'", Int::class.java, vehicleId)!! > 0
        if (activeBlock) reasons += DispatchRefusal.VEHICLE_BLOCKED
        if (expected != null && expected != vehicle.safetyRevision) reasons += DispatchRefusal.STALE_SAFETY_REVISION
        val shift = shift(j, shiftId)
        if (shift == null || shift.vehicleId != vehicleId || shift.state !in USABLE_SHIFT || !at.isBefore(shift.endsAt)) reasons += DispatchRefusal.NO_VALID_SHIFT
        var releaseId: UUID? = null; var validUntil: Instant? = null
        val rel = j.query("$RELEASE_SELECT WHERE r.vehicle_id = ? AND r.shift_id = ? ORDER BY (r.revoked_at IS NULL) DESC, r.valid_until DESC, r.id LIMIT 1", RELEASE_ROW(clock.instant()), vehicleId, shiftId).firstOrNull()
        when {
            rel == null -> reasons += DispatchRefusal.NO_RELEASE
            rel.revokedAt != null -> reasons += DispatchRefusal.RELEASE_REVOKED
            !rel.validUntil.isAfter(at) -> reasons += DispatchRefusal.RELEASE_EXPIRED
            rel.safetyRevision != vehicle.safetyRevision -> reasons += DispatchRefusal.RELEASE_STALE
            else -> { releaseId = rel.id; validUntil = rel.validUntil }
        }
        if (shift != null && qualification(j, shift.driverRef, at, lock = lock) == null) reasons += DispatchRefusal.QUALIFICATION_MISSING
        return if (reasons.isEmpty()) DispatchVerdict.Dispatchable(releaseId!!, vehicle.safetyRevision, validUntil!!) else DispatchVerdict.Refused(reasons.toList())
    }

    // ------------------------------------------------------------------------------------------------ Hilfen

    private class Vehicle(val activeBlock: Boolean, val safetyRevision: Long, val active: Boolean)
    private class Shift(val vehicleId: UUID, val driverRef: UUID, val state: String, val endsAt: Instant)
    private class Insp(val vehicleId: UUID, val shiftId: UUID, val driverRef: UUID, val status: String, val outcome: String?)

    /** Sperrreihenfolge: immer zuerst die Fahrzeugzeile. [exclusive] für Änderungen, sonst gemeinsam (Hold-Commit, Prüfung). */
    private fun lockVehicle(j: JdbcOperations, vehicleId: UUID, exclusive: Boolean, lock: Boolean = true): Vehicle? {
        val row = RowMapper { rs, _ -> Vehicle(rs.getBoolean(1), rs.getLong(2), rs.getBoolean(3)) }
        return when {
            !lock -> j.query("SELECT active_block, safety_revision, active FROM taktovia.vehicle WHERE id = ?", row, vehicleId)
            exclusive -> j.query("SELECT active_block, safety_revision, active FROM taktovia.vehicle WHERE id = ? FOR UPDATE", row, vehicleId)
            else -> j.query("SELECT active_block, safety_revision, active FROM taktovia.vehicle WHERE id = ? FOR SHARE", row, vehicleId)
        }.firstOrNull()
    }

    /** Erhöht die `safety_revision` und leitet `active_block` ab (wahr, solange ein Grund aktiv ist); liefert den neuen Stand. */
    private fun bump(j: JdbcOperations, vehicleId: UUID, blocked: Boolean): Long =
        j.queryForObject("UPDATE taktovia.vehicle SET safety_revision = safety_revision + 1, active_block = ? WHERE id = ? RETURNING safety_revision", Long::class.java, blocked, vehicleId)!!

    private fun shift(j: JdbcOperations, shiftId: UUID): Shift? =
        j.query("SELECT vehicle_id, driver_ref, state, ends_at FROM taktovia.staff_shift WHERE id = ?", { rs, _ -> Shift(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getString(3), rs.getTimestamp(4).toInstant()) }, shiftId).firstOrNull()

    /**
     * Qualifikationsnummer des Fahrers, wenn er aktiv ist und mindestens eine zum Zeitpunkt gültige, freigegebene und wirksame Qualifikation hält; sonst `null` (fail closed, auch für
     * Schichten mit einer Fahrerkennung ohne Fahrerstamm). Die Fahrerzeile wird gemeinsam gesperrt: ein gleichzeitiger Entzug (Fahrerzeile `FOR UPDATE` im Fahrerstamm) wird davor
     * oder danach entschieden, nie dazwischen.
     */
    private fun qualification(j: JdbcOperations, driverRef: UUID, at: Instant, lock: Boolean = true): Long? {
        // Erst sperren, dann in einer **neuen Anweisung** auswerten: bei einem Sperrwarten (READ COMMITTED) sähe eine einzige Anweisung die Zeile des Fahrers neu, die Qualifikationsrevisionen
        // aber noch im alten Stand und würde einen gleichzeitigen Entzug übersehen.
        if (lock) j.query("SELECT 1 FROM taktovia.driver WHERE id = ? FOR SHARE", { _, _ -> 1 }, driverRef)
        val ts = Timestamp.from(at)
        return j.query(
            "SELECT d.qualification_seq FROM taktovia.driver d WHERE d.id = ? AND d.status = 'ACTIVE' AND EXISTS (SELECT 1 FROM taktovia.qualification_revision q WHERE q.driver_id = d.id " +
                "AND q.approval_status = 'APPROVED' AND q.valid_from <= ? AND (q.valid_to IS NULL OR q.valid_to > ?) AND q.revision = (SELECT max(x.revision) FROM taktovia.qualification_revision x " +
                "WHERE x.tenant_id = q.tenant_id AND x.driver_id = q.driver_id AND x.qualification_code = q.qualification_code AND x.approval_status <> 'PENDING'))",
            { rs, _ -> rs.getLong(1) }, driverRef, ts, ts,
        ).firstOrNull()
    }

    private fun existingBlock(j: JdbcOperations, cmd: SetBlock): VehicleBlockView? =
        j.query("$BLOCK_SELECT WHERE b.vehicle_id = ? AND b.source = ? AND b.reason_code = ? AND COALESCE(b.reason_ref, '') = COALESCE(?, '') AND b.status = 'ACTIVE'", BLOCK,
            cmd.vehicleId, cmd.source.name, cmd.reason.name, cmd.reasonRef).firstOrNull()

    private fun block(j: JdbcOperations, id: UUID): VehicleBlockView? = j.query("$BLOCK_SELECT WHERE b.id = ?", BLOCK, id).firstOrNull()

    private fun existingFor(j: JdbcOperations, inspectionId: UUID): ResourceReleaseView? =
        j.query("$RELEASE_SELECT WHERE r.inspection_ref = ?", RELEASE_ROW(clock.instant()), inspectionId).firstOrNull()

    private fun release(j: JdbcOperations, id: UUID): ResourceReleaseView? = j.query("$RELEASE_SELECT WHERE r.id = ?", RELEASE_ROW(clock.instant()), id).firstOrNull()

    private fun actor(ctx: RequestContext) = de.taktovia.platform.approval.FourEyes.refOf(ctx)

    private fun requireRole(ctx: RequestContext, roles: Set<String>) {
        if (roles.none { it in ctx.roles }) throw ProblemException(ErrorCode.FORBIDDEN, "Für diese Aktion fehlt die Rolle.")
    }

    private companion object {
        val OPERATORS = setOf("dispatcher", "tenant_admin")
        val SAFETY_LIFTERS = setOf("workshop", "tenant_admin")
        val RELEASERS = setOf("workshop", "tenant_admin")
        val REVOKERS = setOf("dispatcher", "workshop", "tenant_admin")
        /** Zustände einer Schicht, in der eine Freigabe gilt: angetreten (M3) bzw. veröffentlicht (Fixtures und Plan aus M2/M4). */
        val USABLE_SHIFT = setOf("STARTED", "PUBLISHED")

        const val BLOCK_SELECT = "SELECT b.id, b.vehicle_id, b.source, b.reason_code, b.reason_ref, b.status, b.set_by, b.set_at, b.lifted_by, b.lifted_at, b.release_decision_id, b.revision FROM taktovia.vehicle_block b"
        val BLOCK = RowMapper { rs, _ ->
            VehicleBlockView(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), BlockSource.valueOf(rs.getString(3)), BlockReason.valueOf(rs.getString(4)), rs.getString(5),
                BlockStatus.valueOf(rs.getString(6)), rs.getString(7), rs.getTimestamp(8).toInstant(), rs.getString(9), rs.getTimestamp(10)?.toInstant(), rs.getObject(11, UUID::class.java), rs.getLong(12))
        }

        const val RELEASE_SELECT = "SELECT r.id, r.vehicle_id, r.shift_id, r.inspection_ref, r.safety_revision, r.qualification_revision, r.valid_until, r.created_at, r.issued_by, r.revoked_at, r.revoked_by, r.revoke_reason FROM taktovia.resource_release r"
        @Suppress("FunctionName")
        fun RELEASE_ROW(now: Instant) = RowMapper { rs, _ ->
            val validUntil = rs.getTimestamp(7).toInstant(); val revoked = rs.getTimestamp(10)?.toInstant()
            ResourceReleaseView(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getObject(3, UUID::class.java), rs.getObject(4, UUID::class.java), rs.getLong(5), rs.getLong(6),
                validUntil, rs.getTimestamp(8).toInstant(), rs.getString(9), revoked, rs.getString(11), rs.getString(12)?.let { ReleaseRevokeReason.valueOf(it) },
                if (revoked != null) ReleaseState.REVOKED else if (!validUntil.isAfter(now)) ReleaseState.EXPIRED else ReleaseState.ISSUED)
        }
    }
}

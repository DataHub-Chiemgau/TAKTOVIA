package de.taktovia.stafftime.internal

import de.taktovia.platform.api.Etag
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
import de.taktovia.platform.tenant.TenantId
import de.taktovia.stafftime.api.AckRunCommand
import de.taktovia.stafftime.api.AckRunResult
import de.taktovia.stafftime.api.DriverPlanAcks
import de.taktovia.stafftime.api.DriverRunSource
import de.taktovia.stafftime.api.DriverShifts
import de.taktovia.stafftime.api.PlanAckOverdue
import de.taktovia.stafftime.api.PlanAckOverdueSink
import de.taktovia.stafftime.api.PlanRevisionRules
import de.taktovia.stafftime.api.StafftimeSettings
import org.springframework.jdbc.core.JdbcOperations
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * Planbestätigung des Fahrers und Überwachung unbestätigter Revisionen (WP-M3-27, LH-6.6-DRV-004). Die Bestätigung hält den Umlauf mit `FOR SHARE`: ein gleichzeitiger Plan-Commit
 * (UPDATE der Revision) wartet darauf bzw. die Bestätigung sieht die neue Revision und wird mit 412 abgewiesen; es entsteht nie eine Bestätigung einer inzwischen überholten Revision.
 * Idempotenz über die Eindeutigkeit `(tenant, shift, run, plan_revision)`: zwei Geräte desselben Fahrers erzeugen einen Eintrag.
 */
internal class JdbcDriverPlanAcks(
    private val ctxTx: ContextTransactions, private val audit: AuditPort, private val outbox: Outbox, private val clock: Clock, private val settings: StafftimeSettings,
    private val source: DriverRunSource?, private val sink: PlanAckOverdueSink, private val shifts: DriverShifts,
) : DriverPlanAcks {

    override fun acknowledge(ctx: RequestContext, shiftId: UUID, ifMatch: Long, cmd: AckRunCommand): AckRunResult {
        guard(ctx)
        if (cmd.planRevision != ifMatch) throw ProblemException.invalid(Violation("/plan_revision", ViolationCode.INVALID_VALUE))
        if (cmd.acknowledgedAt.isAfter(clock.instant().plus(FUTURE_TOLERANCE))) throw ProblemException.invalid(Violation("/acknowledged_at", ViolationCode.INVALID_VALUE))
        val created = ctxTx.run(ctx) { j -> ack(j, ctx, shiftId, cmd) }
        return AckRunResult(shifts.run(ctx, shiftId), created)
    }

    @AuditRequired
    private fun ack(j: JdbcOperations, ctx: RequestContext, shiftId: UUID, cmd: AckRunCommand): Boolean {
        val shift = j.query("SELECT s.vehicle_id, s.state, s.driver_ref FROM taktovia.staff_shift s WHERE s.id = ? AND $OWN_SHIFT", { rs, _ -> Triple(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getObject(3, UUID::class.java)) },
            shiftId, *ShiftOwnership.args(ctx.actor.issuer, ctx.actor.subject)).firstOrNull() ?: throw ProblemException.notFound()
        if (shift.second !in OPEN_STATES) throw ProblemException(ErrorCode.INVALID_STATE_TRANSITION, "Die Schicht ist beendet oder entzogen; es gibt nichts zu bestätigen.")
        val device = cmd.deviceId?.let { DeviceGuard.requireBound(j, ctx, it) }
        val run = j.query("SELECT id, revision FROM taktovia.vehicle_run WHERE shift_id = ? AND vehicle_id = ? FOR SHARE", { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getLong(2) }, shiftId, shift.first)
            .firstOrNull() ?: throw ProblemException.notFound()
        if (run.second != cmd.planRevision) throw ProblemException(ErrorCode.REVISION_MISMATCH, "Der Plan wurde zwischenzeitlich geändert; aktuelle Revision ${run.second} laden.", headers = mapOf("ETag" to Etag.format(run.second)))
        val ackId = UUID.randomUUID()
        val n = j.update(
            "INSERT INTO taktovia.driver_plan_ack(tenant_id, id, shift_id, run_id, plan_revision, device_id, acknowledged_at, created_by) VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT (tenant_id, shift_id, run_id, plan_revision) DO NOTHING", ackId, shiftId, run.first, cmd.planRevision, device, Timestamp.from(cmd.acknowledgedAt), "user:" + ctx.actor.subject)
        // Ereignis `driver.run_acknowledged` (Ereigniskatalog M3): nur Kennungen und Revision, in derselben Transaktion wie die Bestätigung
        if (n == 1) outbox.append(j, OutboxEvent(ackId, 1, "driver.run_acknowledged", linkedMapOf("shift_id" to shiftId.toString(), "plan_revision" to cmd.planRevision, "driver_ref" to shift.third?.toString()),
            clock.instant(), correlationId = ctx.correlationId))
        if (n == 1) audit.record(j, AuditEntry(ActorRef("user:" + ctx.actor.subject), AuditActions.DRIVER_PLAN_ACKNOWLEDGED, ObjectRef("staff_shift", shiftId), ctx.correlationId,
            linkedMapOf("plan_revision" to cmd.planRevision, "device_id" to device?.toString()), clock.instant()))
        return n == 1
    }

    override fun reportOverdue(tenant: TenantId): List<PlanAckOverdue> {
        val now = clock.instant()
        val cutoff = now.minus(settings.planAckOverdue)
        val candidates = ctxTx.runSystem(tenant, readOnly = true) { j ->
            j.query(
                "SELECT s.id, s.vehicle_id, r.id, r.revision, pc.recorded_at, coalesce((SELECT max(a.plan_revision) FROM taktovia.driver_plan_ack a WHERE a.shift_id = s.id AND a.run_id = r.id), " + AT_START + ") " +
                    "FROM taktovia.staff_shift s JOIN taktovia.vehicle_run r ON r.tenant_id = s.tenant_id AND r.shift_id = s.id AND r.vehicle_id = s.vehicle_id " +
                    "JOIN taktovia.plan_change pc ON pc.tenant_id = r.tenant_id AND pc.run_id = r.id AND pc.run_revision = r.revision " +
                    "WHERE s.state = 'STARTED' AND r.state IN ('PLANNED', 'ACTIVE', 'IN_PROGRESS') AND pc.recorded_at <= ? " +
                    "AND r.revision > coalesce((SELECT max(a.plan_revision) FROM taktovia.driver_plan_ack a WHERE a.shift_id = s.id AND a.run_id = r.id), " + AT_START + ") ORDER BY pc.recorded_at LIMIT 500",
                { rs, _ -> Cand(PlanAckOverdue(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getObject(3, UUID::class.java), rs.getLong(4), rs.getTimestamp(5).toInstant()), rs.getLong(6)) },
                Timestamp.from(cutoff))
        }
        // nur bestätigungspflichtige Änderungen melden; ist die Basis nicht lesbar, gilt die Revision im Zweifel als bestätigungspflichtig (nie still übernommen)
        val due = candidates.filter { c ->
            val s = source ?: return@filter true
            val now2 = s.revision(tenant, c.hint.shiftId, c.hint.vehicleId, c.hint.planRevision)
            val before = s.revision(tenant, c.hint.shiftId, c.hint.vehicleId, c.base)
            if (now2 == null || before == null) true else PlanRevisionRules.diff(before.stops, now2.stops, settings.planAckTimeShift).acknowledgementRequired
        }.map { it.hint }
        due.forEach { sink.overdue(tenant, it) }
        return due
    }

    private class Cand(val hint: PlanAckOverdue, val base: Long)

    private fun guard(ctx: RequestContext) {
        if (ctx.actor.type != ActorType.HUMAN || ctx.actor.sessionId == null || "driver" !in ctx.roles || !ctx.hasScope("driver:run")) throw ProblemException(ErrorCode.FORBIDDEN)
    }

    private companion object {
        /** Eigene Schicht (siehe [ShiftOwnership]); als `const val` in dieser Datei, damit die SQL-Prüfung (tools/lint_sql.py) sie als Konstante erkennt. */
        const val OWN_SHIFT = ShiftOwnership.PREDICATE
        /** Basisrevision ohne Bestätigung: die letzte Revision bis zum Dienstantritt (sonst die erste); Konstante, damit die SQL-Prüfung (tools/lint_sql.py) sie als solche erkennt. */
        const val AT_START = "coalesce((SELECT max(p2.run_revision) FROM taktovia.plan_change p2 WHERE p2.tenant_id = r.tenant_id AND p2.run_id = r.id AND p2.recorded_at <= s.started_at), 1)"
        val OPEN_STATES = setOf("PLANNED", "PUBLISHED", "STARTED")
        val FUTURE_TOLERANCE: Duration = Duration.ofMinutes(5)
    }
}

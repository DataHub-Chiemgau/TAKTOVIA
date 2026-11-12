package de.taktovia.stafftime.internal

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
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.stafftime.api.DriverRunProjection
import de.taktovia.stafftime.api.DriverRunSource
import de.taktovia.stafftime.api.DriverRunView
import de.taktovia.stafftime.api.DriverShiftView
import de.taktovia.stafftime.api.DriverShifts
import de.taktovia.stafftime.api.EndShiftCommand
import de.taktovia.stafftime.api.RunReleaseState
import de.taktovia.stafftime.api.ShiftStartResult
import de.taktovia.stafftime.api.StafftimeSettings
import de.taktovia.stafftime.api.StartShiftCommand
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Dienstanmeldung des Fahrers (WP-M3-25). Eine Transaktion je Aufruf; die Schichtzeile wird für Antritt und Ende mit `FOR UPDATE` gesperrt, damit gleichzeitige Geräte desselben
 * Fahrers genau einen Zustand erzeugen. Die Uhr ist die Serveruhr ([Clock]); eine Gerätezeit gibt es hier nicht.
 */
internal class JdbcDriverShifts(
    private val ctxTx: ContextTransactions, private val audit: AuditPort, private val clock: Clock, private val settings: StafftimeSettings, private val source: DriverRunSource?,
) : DriverShifts {

    override fun mine(ctx: RequestContext): List<DriverShiftView> {
        guard(ctx)
        val now = clock.instant()
        return ctxTx.run(ctx, readOnly = true) { j ->
            j.query("SELECT $COLUMNS FROM taktovia.staff_shift s JOIN taktovia.vehicle v ON v.tenant_id = s.tenant_id AND v.id = s.vehicle_id " +
                "WHERE $OWN_SHIFT AND s.state IN ('PLANNED', 'PUBLISHED', 'STARTED') AND s.ends_at > ? ORDER BY s.starts_at, s.id LIMIT 10",
                mapper(now), *ShiftOwnership.args(ctx.actor.issuer, ctx.actor.subject), Timestamp.from(now))
        }
    }

    override fun start(ctx: RequestContext, cmd: StartShiftCommand): ShiftStartResult {
        guard(ctx)
        return ctxTx.run(ctx) { j -> doStart(j, ctx, cmd) }
    }

    override fun end(ctx: RequestContext, cmd: EndShiftCommand): DriverShiftView {
        guard(ctx)
        return ctxTx.run(ctx) { j -> doEnd(j, ctx, cmd) }
    }

    override fun run(ctx: RequestContext, shiftId: UUID): DriverRunView {
        guard(ctx)
        val now = clock.instant()
        val row = ctxTx.run(ctx, readOnly = true) { j ->
            val s = ownShift(j, ctx, shiftId, lock = false) ?: throw ProblemException.notFound()
            val valid = s.state == "STARTED" && !now.isBefore(s.startsAt) && now.isBefore(s.endsAt)
            val gate = releaseGate(j, s, now)
            val planRevision = j.query("SELECT max(revision) FROM taktovia.vehicle_run WHERE shift_id = ? AND vehicle_id = ?", { rs, _ -> rs.getObject(1) as Long? }, s.id, s.vehicleId).firstOrNull()
            Triple(s, valid to gate, planRevision)
        }
        val (s, vg, planRevision) = row
        val (valid, gate) = vg
        // Aufträge nur bei gültiger, angetretener Schicht UND bestehender Einsatzfreigabe; die Quelle wird sonst nicht einmal gefragt.
        val projection: DriverRunProjection? = if (valid && gate.state == RunReleaseState.RELEASED) source?.projection(ctx, s.id, s.vehicleId) else null
        val missing = if (!valid) emptyList() else gate.missing
        return DriverRunView(s.id, s.vehicleId, s.state, valid, if (valid) gate.state else RunReleaseState.NOT_RELEASED, missing, projection?.planRevision ?: planRevision, now, projection?.stops.orEmpty(), null)
    }

    @AuditRequired
    private fun doStart(j: JdbcOperations, ctx: RequestContext, cmd: StartShiftCommand): ShiftStartResult {
        val now = clock.instant()
        val s = ownShift(j, ctx, cmd.shiftId, lock = true) ?: throw ProblemException.notFound()
        val device = DeviceGuard.requireBound(j, ctx, cmd.deviceId)
        if (s.state == "STARTED") {
            // Wiederholung oder zweites Gerät: bestehender Zustand; ein abweichendes Fahrzeug ist ein Konflikt mit dem bereits bestätigten Antritt
            if (cmd.vehicleId != s.vehicleId) throw ProblemException(ErrorCode.CONFLICT, "Die Schicht ist bereits mit einem anderen Fahrzeug angetreten.", listOf(Violation("/vehicle_id", ViolationCode.INVALID_VALUE)))
            return ShiftStartResult(view(j, ctx, s, now), true)
        }
        if (s.state != "PLANNED" && s.state != "PUBLISHED") throw ProblemException(ErrorCode.INVALID_STATE_TRANSITION, "Die Schicht ist beendet oder entzogen; ein Dienstantritt ist nicht möglich.")
        if (cmd.vehicleId != s.vehicleId) throw ProblemException.invalid(Violation("/vehicle_id", ViolationCode.INVALID_VALUE))
        if (now.isBefore(s.startsAt.minus(settings.shiftStartTolerance)) || !now.isBefore(s.endsAt))
            throw ProblemException(ErrorCode.INVALID_STATE_TRANSITION, "Der Dienstantritt ist nur ab ${settings.shiftStartTolerance.toMinutes()} Minuten vor Schichtbeginn bis Schichtende möglich.")
        j.update("UPDATE taktovia.staff_shift SET state = 'STARTED', started_at = ?, started_device_id = ? WHERE id = ? AND state IN ('PLANNED', 'PUBLISHED')", Timestamp.from(now), device, s.id)
        val fresh = ownShift(j, ctx, cmd.shiftId, lock = false)!!
        audit.record(j, AuditEntry(actor(ctx), AuditActions.SHIFT_STARTED, ObjectRef("staff_shift", s.id), ctx.correlationId,
            mapOf("device_id" to device.toString(), "vehicle_id" to s.vehicleId.toString(), "occurred_at" to now.toString(), "previous_state" to s.state)))
        return ShiftStartResult(view(j, ctx, fresh, now), false)
    }

    @AuditRequired
    private fun doEnd(j: JdbcOperations, ctx: RequestContext, cmd: EndShiftCommand): DriverShiftView {
        val now = clock.instant()
        val s = ownShift(j, ctx, cmd.shiftId, lock = true) ?: throw ProblemException.notFound()
        val device = DeviceGuard.requireBound(j, ctx, cmd.deviceId)
        if (s.state == "ENDED") return view(j, ctx, s, now)
        if (s.state != "STARTED") throw ProblemException(ErrorCode.INVALID_STATE_TRANSITION, "Nur eine angetretene Schicht kann beendet werden.")
        j.update("UPDATE taktovia.staff_shift SET state = 'ENDED', ended_at = ? WHERE id = ? AND state = 'STARTED'", Timestamp.from(now), s.id)
        audit.record(j, AuditEntry(actor(ctx), AuditActions.SHIFT_ENDED, ObjectRef("staff_shift", s.id), ctx.correlationId,
            mapOf("device_id" to device.toString(), "vehicle_id" to s.vehicleId.toString(), "occurred_at" to now.toString())))
        return view(j, ctx, ownShift(j, ctx, cmd.shiftId, lock = false)!!, now)
    }

    private class Own(
        val id: UUID, val vehicleId: UUID, val vehicleLabel: String?, val startsAt: Instant, val endsAt: Instant, val timezone: String, val state: String, val startedAt: Instant?, val endedAt: Instant?,
    )

    private fun ownShift(j: JdbcOperations, ctx: RequestContext, shiftId: UUID, lock: Boolean): Own? =
        j.query(
            "SELECT s.id, s.vehicle_id, v.vehicle_label, s.starts_at, s.ends_at, s.timezone, s.state, s.started_at, s.ended_at FROM taktovia.staff_shift s " +
                "JOIN taktovia.vehicle v ON v.tenant_id = s.tenant_id AND v.id = s.vehicle_id WHERE s.id = ? AND $OWN_SHIFT ${if (lock) "FOR UPDATE OF s" else ""}",
            { rs, _ -> Own(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getString(3), rs.getTimestamp(4).toInstant(), rs.getTimestamp(5).toInstant(), rs.getString(6),
                rs.getString(7), rs.getTimestamp(8)?.toInstant(), rs.getTimestamp(9)?.toInstant()) },
            shiftId, *ShiftOwnership.args(ctx.actor.issuer, ctx.actor.subject),
        ).firstOrNull()

    private fun view(@Suppress("UNUSED_PARAMETER") j: JdbcOperations, @Suppress("UNUSED_PARAMETER") ctx: RequestContext, s: Own, now: Instant) = DriverShiftView(
        s.id, s.vehicleId, s.vehicleLabel, s.startsAt, s.endsAt, s.timezone, s.state, s.startsAt.minus(settings.shiftStartTolerance), s.startedAt, s.endedAt,
        s.state == "STARTED" && !now.isBefore(s.startsAt) && now.isBefore(s.endsAt),
    )

    private fun mapper(now: Instant) = RowMapper { rs, _ ->
        val state = rs.getString("state"); val from = rs.getTimestamp("starts_at").toInstant(); val to = rs.getTimestamp("ends_at").toInstant()
        DriverShiftView(rs.getObject("id", UUID::class.java), rs.getObject("vehicle_id", UUID::class.java), rs.getString("vehicle_label"), from, to, rs.getString("timezone"), state,
            from.minus(settings.shiftStartTolerance), rs.getTimestamp("started_at")?.toInstant(), rs.getTimestamp("ended_at")?.toInstant(), state == "STARTED" && !now.isBefore(from) && now.isBefore(to))
    }

    private class Gate(val state: RunReleaseState, val missing: List<String>)

    /** Einsatzfreigabe des Fahrzeugs für diese Schicht (Lesesicht): gültige, nicht widerrufene `resource_release` mit aktueller `safety_revision` und kein operativer Block. */
    private fun releaseGate(j: JdbcOperations, s: Own, now: Instant): Gate {
        val v = j.query("SELECT active_block, safety_revision FROM taktovia.vehicle WHERE id = ?", { rs, _ -> rs.getBoolean(1) to rs.getLong(2) }, s.vehicleId).firstOrNull()
            ?: return Gate(RunReleaseState.NOT_RELEASED, listOf("NO_INSPECTION"))
        val rel = j.query("SELECT safety_revision, valid_until, revoked_at FROM taktovia.resource_release WHERE shift_id = ? AND vehicle_id = ? ORDER BY valid_until DESC, id LIMIT 1",
            { rs, _ -> Triple(rs.getLong(1), rs.getTimestamp(2).toInstant(), rs.getTimestamp(3)?.toInstant()) }, s.id, s.vehicleId).firstOrNull()
        return when {
            rel == null -> Gate(RunReleaseState.NOT_RELEASED, listOf("NO_INSPECTION"))
            rel.third != null -> Gate(RunReleaseState.NOT_RELEASED, listOf("RELEASE_REVOKED"))
            !rel.second.isAfter(now) -> Gate(RunReleaseState.NOT_RELEASED, listOf("NO_INSPECTION"))
            rel.first != v.second -> Gate(RunReleaseState.NOT_RELEASED, listOf("SAFETY_REVISION_CHANGED"))
            v.first -> Gate(RunReleaseState.BLOCKED, listOf("OPERATIONAL_BLOCK"))
            else -> Gate(RunReleaseState.RELEASED, emptyList())
        }
    }

    private fun guard(ctx: RequestContext) {
        if (ctx.actor.type != ActorType.HUMAN || ctx.actor.sessionId == null || "driver" !in ctx.roles || !ctx.hasScope(SCOPE)) throw ProblemException(ErrorCode.FORBIDDEN)
    }

    private fun actor(ctx: RequestContext) = ActorRef("user:" + ctx.actor.subject)

    private companion object {
        /** Eigene Schicht (siehe [ShiftOwnership]); als `const val` in dieser Datei, damit die SQL-Prüfung (tools/lint_sql.py) sie als Konstante erkennt. */
        const val OWN_SHIFT = ShiftOwnership.PREDICATE
        const val SCOPE = "driver:run"
        const val COLUMNS = "s.id, s.vehicle_id, v.vehicle_label, s.starts_at, s.ends_at, s.timezone, s.state, s.started_at, s.ended_at"
    }
}

package de.taktovia.stafftime.internal

import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.outbox.OutboxEvent
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.stafftime.api.DriverEventType
import de.taktovia.stafftime.api.ServiceStatus
import de.taktovia.stafftime.api.ServiceStatusAutomaton
import de.taktovia.stafftime.api.ServiceStatusChange
import de.taktovia.stafftime.api.ServiceStatusReport
import de.taktovia.stafftime.api.ServiceStatusResult
import de.taktovia.stafftime.api.ServiceStatusView
import de.taktovia.stafftime.api.StatusOutcome
import de.taktovia.stafftime.api.StatusTrigger
import de.taktovia.stafftime.api.VehicleServiceStatuses
import org.springframework.jdbc.core.JdbcOperations
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Dienststatus auf PostgreSQL (WP-M3-31). Eine Transaktion je Wechsel: Fahrzeugzeile `FOR UPDATE` (Sperrreihenfolge wie Sperre/Freigabe, damit die Disposition unter `FOR SHARE` entweder vor oder nach dem
 * Wechsel liest), Statuszeile `FOR UPDATE`, Kantenprüfung gegen [ServiceStatusAutomaton], Statuszeile, Historie und Ereignis `vehicle.service_status_changed` atomar. Die Dedupe über die Ereignis-ID gilt
 * dauerhaft (Unique-Index) und wird nach dem Sperren erneut geprüft, damit parallele Wiederholungen genau einen Wechsel ergeben.
 */
internal class JdbcVehicleServiceStatuses(private val ctxTx: ContextTransactions, private val outbox: Outbox, private val clock: Clock) : VehicleServiceStatuses {

    private class ShiftRow(val id: UUID, val vehicleId: UUID, val state: String)

    private class Current(val status: ServiceStatus, val revision: Long, val changedAt: Instant)

    override fun report(ctx: RequestContext, cmd: ServiceStatusReport): ServiceStatusResult {
        if (ctx.actor.type != ActorType.HUMAN) throw ProblemException(ErrorCode.FORBIDDEN)
        val session = ctx.actor.sessionId ?: throw ProblemException(ErrorCode.FORBIDDEN)
        return ctxTx.run(ctx) { j ->
            DeviceGuard.requireBound(j, ctx, cmd.deviceId ?: boundDevice(j, ctx, session))
            val shift = ownShift(j, ctx, cmd.shiftId) ?: throw ProblemException.notFound()
            if (cmd.vehicleId != null && cmd.vehicleId != shift.vehicleId) throw ProblemException.notFound()
            existing(j, cmd.eventId)?.let { return@run duplicate(j, it, shift, cmd.status) }
            if (shift.state != "STARTED") throw ProblemException(ErrorCode.INVALID_STATE_TRANSITION, "Die Schicht ist nicht angetreten oder bereits beendet; ein Status kann nicht gemeldet werden.")
            apply(j, ctx, shift, StatusTrigger.DRIVER, userRef(ctx), cmd.eventId, cmd.occurredAt, cmd.offlineRecorded, strict = true) { cmd.status }
        }
    }

    override fun current(ctx: RequestContext, shiftId: UUID): ServiceStatusView = ctxTx.run(ctx, readOnly = true) { j ->
        val shift = ownShift(j, ctx, shiftId) ?: throw ProblemException.notFound()
        view(j, shift)
    }

    override fun byDispatch(ctx: RequestContext, shiftId: UUID, target: ServiceStatus): ServiceStatusResult {
        if ("dispatcher" !in ctx.roles) throw ProblemException(ErrorCode.FORBIDDEN)
        return ctxTx.run(ctx) { j ->
            val shift = j.query("SELECT id, vehicle_id, state FROM taktovia.staff_shift WHERE id = ?", { rs, _ -> ShiftRow(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getString(3)) }, shiftId)
                .firstOrNull() ?: throw ProblemException.notFound()
            apply(j, ctx, shift, StatusTrigger.DISPATCH, userRef(ctx), null, null, false, strict = true) { target }
        }
    }

    override fun latestForVehicles(ctx: RequestContext, vehicleIds: Collection<UUID>): Map<UUID, ServiceStatus> {
        if (vehicleIds.isEmpty()) return emptyMap()
        return ctxTx.run(ctx, readOnly = true) { j ->
            val out = LinkedHashMap<UUID, ServiceStatus>()
            j.query("SELECT DISTINCT ON (vehicle_id) vehicle_id, status FROM taktovia.vehicle_service_status WHERE vehicle_id = ANY(CAST(? AS uuid[])) ORDER BY vehicle_id, changed_at DESC, shift_id",
                { rs, _ -> out[rs.getObject(1, UUID::class.java)] = ServiceStatus.valueOf(rs.getString(2)) }, vehicleIds.joinToString(",", "{", "}"))   // nur UUIDs
            out
        }
    }

    /**
     * Ableitung aus einem verarbeiteten Fahrerereignis (DRV-005), in der Transaktion des Ereigniseingangs. Pause, Störung und Ende sind Handlungen des Fahrers (Auslöser `DRIVER`), Wartezeit und das
     * Ende des Wartens sind abgeleitet (`SYSTEM`). Ist der Wechsel nicht zulässig (z. B. verspätetes BREAK_END nach einer Störung) oder die Schicht nicht angetreten, bleibt der Status unverändert: das
     * Ereignis selbst ist erfasst, es überschreibt nie den Serverstand (Kap. 05 §5.5).
     */
    internal fun deriveFromDriverEvent(
        j: JdbcOperations, ctx: RequestContext, type: DriverEventType, eventId: UUID, shiftId: UUID, routeStopId: UUID?, occurredAt: Instant, offlineRecorded: Boolean,
    ) {
        val shift = j.query("SELECT id, vehicle_id, state FROM taktovia.staff_shift WHERE id = ?", { rs, _ -> ShiftRow(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getString(3)) }, shiftId)
            .firstOrNull() ?: return
        if (shift.state != "STARTED") return
        val trigger = when (type) {
            DriverEventType.BREAK_START, DriverEventType.BREAK_END, DriverEventType.DISRUPTION, DriverEventType.SHIFT_END -> StatusTrigger.DRIVER
            else -> StatusTrigger.SYSTEM
        }
        val by = if (trigger == StatusTrigger.DRIVER) userRef(ctx) else SYSTEM_REF
        apply(j, ctx, shift, trigger, by, eventId, occurredAt, offlineRecorded, strict = false) { current ->
            when (type) {
                DriverEventType.ARRIVAL -> ServiceStatus.WAITING
                DriverEventType.CHECK_IN, DriverEventType.CHECK_OUT, DriverEventType.NO_SHOW ->
                    if (current != ServiceStatus.WAITING) null else if (isLastStop(j, routeStopId)) ServiceStatus.AVAILABLE else ServiceStatus.EN_ROUTE
                DriverEventType.BREAK_START -> ServiceStatus.BREAK
                DriverEventType.BREAK_END -> ServiceStatus.AVAILABLE
                DriverEventType.DISRUPTION -> ServiceStatus.DISRUPTED
                DriverEventType.SHIFT_END -> ServiceStatus.ENDED
            }
        }
    }

    /** Letzter Stopp der aktuellen Planrevision des Umlaufs (kein späterer Stopp): nach ihm ist der Umlauf abgearbeitet. Unbekannter Stopp: nicht der letzte. */
    private fun isLastStop(j: JdbcOperations, routeStopId: UUID?): Boolean {
        if (routeStopId == null) return false
        return j.queryForObject(
            "SELECT COALESCE((SELECT NOT EXISTS (SELECT 1 FROM taktovia.route_stop n JOIN taktovia.vehicle_run r ON r.tenant_id = n.tenant_id AND r.id = n.run_id AND r.revision = n.run_revision " +
                "WHERE n.run_id = rs.run_id AND n.seq > rs.seq) FROM taktovia.route_stop rs WHERE rs.id = ?), false)", Boolean::class.java, routeStopId,
        ) == true
    }

    /**
     * Gemeinsamer Wechsel unter Sperre. [targetOf] liefert das Ziel aus dem unter Sperre gelesenen aktuellen Status (`null` = nichts zu tun). [strict]: unzulässiger Übergang wirft 409 (Fahrermeldung online, Leitstelle);
     * sonst oder bei offline erfassten Meldungen bleibt er ohne Änderung ([StatusOutcome.REVIEW_REQUIRED]).
     */
    private fun apply(
        j: JdbcOperations, ctx: RequestContext, shift: ShiftRow, trigger: StatusTrigger, createdBy: String, eventId: UUID?, occurredAt: Instant?, offline: Boolean, strict: Boolean,
        targetOf: (ServiceStatus) -> ServiceStatus?,
    ): ServiceStatusResult {
        val now = clock.instant()
        // 1. Sperrreihenfolge: Fahrzeugzeile, dann Statuszeile
        j.query("SELECT 1 FROM taktovia.vehicle WHERE id = ? FOR UPDATE", { _, _ -> 1 }, shift.vehicleId)
        if (j.update("INSERT INTO taktovia.vehicle_service_status (tenant_id, shift_id, vehicle_id, status, changed_at) VALUES (taktovia.current_tenant(), ?, ?, 'NOT_READY', ?) ON CONFLICT (tenant_id, shift_id) DO NOTHING",
                shift.id, shift.vehicleId, Timestamp.from(now)) == 1) {
            history(j, shift, null, ServiceStatus.NOT_READY, StatusTrigger.SYSTEM, SYSTEM_REF, null, null, false, now, 1)
        }
        val cur = j.query("SELECT status, revision, changed_at FROM taktovia.vehicle_service_status WHERE shift_id = ? FOR UPDATE",
            { rs, _ -> Current(ServiceStatus.valueOf(rs.getString(1)), rs.getLong(2), rs.getTimestamp(3).toInstant()) }, shift.id).single()
        // 2. dieselbe Meldung nach dem Sperren erneut: ein paralleler Gewinner macht sie zum Duplikat
        if (eventId != null) existing(j, eventId)?.let { return duplicate(j, it, shift, null) }
        val target = targetOf(cur.status) ?: return ServiceStatusResult(StatusOutcome.REVIEW_REQUIRED, view(j, shift), now, "NO_EFFECT")
        fun reject(reason: String, detail: String): ServiceStatusResult {
            if (strict && !offline) throw ProblemException(ErrorCode.INVALID_STATE_TRANSITION, detail)
            return ServiceStatusResult(StatusOutcome.REVIEW_REQUIRED, view(j, shift), now, reason)
        }
        if (!ServiceStatusAutomaton.allowed(cur.status, target, trigger))
            return reject("INVALID_TRANSITION", "Der Statuswechsel ${cur.status} -> $target ist nicht zulässig.")
        // eine offline erfasste Meldung, die älter ist als der aktuelle Serverstand, überschreibt ihn nie
        if (offline && occurredAt != null && occurredAt.isBefore(cur.changedAt)) return reject("STALE_EVENT", "Die Meldung ist älter als der aktuelle Status.")
        if (cur.status == ServiceStatus.NOT_READY && target == ServiceStatus.AVAILABLE) missingForReady(j, shift, now)?.let { return reject("RELEASE_REQUIRED", "Die Bereitmeldung braucht $it.") }
        // 3. Wechsel: Wächter der Datenbank zählt die Revision
        j.update("UPDATE taktovia.vehicle_service_status SET status = ?, changed_at = ? WHERE shift_id = ?", target.name, Timestamp.from(now), shift.id)
        val revision = cur.revision + 1
        history(j, shift, cur.status, target, trigger, createdBy, eventId, occurredAt, offline, now, revision)
        outbox.append(j, OutboxEvent(shift.id, revision, "vehicle.service_status_changed", linkedMapOf(
            "vehicle_id" to shift.vehicleId.toString(), "shift_id" to shift.id.toString(), "previous" to cur.status.name, "current" to target.name, "trigger" to trigger.name,
        ), now, correlationId = ctx.correlationId))
        return ServiceStatusResult(StatusOutcome.APPLIED, view(j, shift), now, null)
    }

    /** Voraussetzung der Bereitmeldung (CHK-001): Fahrzeug aktiv und nicht gesperrt, gültige Einsatzfreigabe der Schicht gegen den aktuellen Sicherheitsstand. `null` = erfüllt, sonst der fehlende Nachweis. */
    private fun missingForReady(j: JdbcOperations, shift: ShiftRow, now: Instant): String? {
        val usable = j.queryForObject("SELECT active AND NOT active_block FROM taktovia.vehicle WHERE id = ?", Boolean::class.java, shift.vehicleId)
        if (usable != true) return "ein aktives, nicht gesperrtes Fahrzeug"
        val released = j.queryForObject(
            "SELECT count(*) FROM taktovia.resource_release r JOIN taktovia.vehicle v ON v.tenant_id = r.tenant_id AND v.id = r.vehicle_id " +
                "WHERE r.shift_id = ? AND r.revoked_at IS NULL AND r.valid_until > ? AND r.safety_revision = v.safety_revision", Int::class.java, shift.id, Timestamp.from(now))!!
        return if (released > 0) null else "eine gültige Einsatzfreigabe"
    }

    private fun history(j: JdbcOperations, shift: ShiftRow, from: ServiceStatus?, to: ServiceStatus, trigger: StatusTrigger, createdBy: String, eventId: UUID?, occurredAt: Instant?, offline: Boolean, now: Instant, revision: Long) {
        j.update(
            "INSERT INTO taktovia.vehicle_status_history (tenant_id, id, vehicle_id, shift_id, from_status, to_status, trigger, revision, created_by, created_at, event_id, occurred_at, offline_recorded) " +
                "VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            UUID.randomUUID(), shift.vehicleId, shift.id, from?.name, to.name, trigger.name, revision, createdBy, Timestamp.from(now), eventId, occurredAt?.let { Timestamp.from(it) }, offline,
        )
    }

    private fun existing(j: JdbcOperations, eventId: UUID): Pair<UUID, ServiceStatus>? =
        j.query("SELECT shift_id, to_status FROM taktovia.vehicle_status_history WHERE event_id = ?", { rs, _ -> rs.getObject(1, UUID::class.java) to ServiceStatus.valueOf(rs.getString(2)) }, eventId).firstOrNull()

    /** Dieselbe Ereignis-ID wirkt nie zweimal; gehört sie zu einer anderen Schicht oder einem anderen Zielstatus, ist es ein Idempotenzkonflikt. [target] = `null`: Ziel nicht geprüft (Ableitung, Wiederholung unter Sperre). */
    private fun duplicate(j: JdbcOperations, previous: Pair<UUID, ServiceStatus>, shift: ShiftRow, target: ServiceStatus?): ServiceStatusResult {
        if (previous.first != shift.id || (target != null && previous.second != target)) throw ProblemException(ErrorCode.IDEMPOTENCY_CONFLICT)
        return ServiceStatusResult(StatusOutcome.DUPLICATE, view(j, shift), clock.instant(), null)
    }

    private fun view(j: JdbcOperations, shift: ShiftRow): ServiceStatusView {
        val cur = j.query("SELECT status, revision, changed_at FROM taktovia.vehicle_service_status WHERE shift_id = ?",
            { rs, _ -> Current(ServiceStatus.valueOf(rs.getString(1)), rs.getLong(2), rs.getTimestamp(3).toInstant()) }, shift.id).firstOrNull()
        val history = j.query(
            "SELECT id, vehicle_id, from_status, to_status, trigger, occurred_at, created_at, offline_recorded, event_id, revision FROM taktovia.vehicle_status_history WHERE shift_id = ? ORDER BY created_at, revision, id",
            { rs, _ -> ServiceStatusChange(rs.getObject(1, UUID::class.java), shift.id, rs.getObject(2, UUID::class.java), rs.getString(3)?.let(ServiceStatus::valueOf), ServiceStatus.valueOf(rs.getString(4)),
                StatusTrigger.valueOf(rs.getString(5)), rs.getTimestamp(6)?.toInstant(), rs.getTimestamp(7).toInstant(), rs.getBoolean(8), rs.getObject(9, UUID::class.java), rs.getLong(10)) }, shift.id)
        return ServiceStatusView(shift.id, shift.vehicleId, cur?.status ?: ServiceStatus.NOT_READY, cur?.revision ?: 0L, cur?.changedAt, history)
    }

    private fun ownShift(j: JdbcOperations, ctx: RequestContext, shiftId: UUID): ShiftRow? =
        j.query("SELECT s.id, s.vehicle_id, s.state FROM taktovia.staff_shift s WHERE s.id = ? AND $OWN_SHIFT",
            { rs, _ -> ShiftRow(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getString(3)) }, shiftId, *ShiftOwnership.args(ctx.actor.issuer, ctx.actor.subject)).firstOrNull()

    /** Ohne `device_id` gilt das eine, an die aktuelle Sitzung gebundene und nicht beendete Gerät des Kontos; sonst ist kein Gerät registriert (404). */
    private fun boundDevice(j: JdbcOperations, ctx: RequestContext, session: String): UUID =
        j.query("SELECT id FROM taktovia.driver_device WHERE issuer = ? AND subject = ? AND idp_session_id = ? AND revoked_at IS NULL", { rs, _ -> rs.getObject(1, UUID::class.java) },
            ctx.actor.issuer, ctx.actor.subject, session).singleOrNull() ?: throw ProblemException.notFound()

    /** Urheber als Pseudonym `user:<Subject>` (nie Name oder Mailadresse); nur zulässige Zeichen, höchstens 120. */
    private fun userRef(ctx: RequestContext): String = "user:" + ctx.actor.subject.replace(Regex("[^A-Za-z0-9._:-]"), "_").take(120)

    private companion object {
        const val SYSTEM_REF = "system:service-status"

        /** Eigene Schicht: Fahrerstamm über die Mitgliedschaft oder (Altbestand) die IdP-Subject-UUID (siehe [ShiftOwnership]). */
        const val OWN_SHIFT = ShiftOwnership.PREDICATE
    }
}

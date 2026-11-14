package de.taktovia.fleetsafety.internal

import de.taktovia.fleetsafety.api.ReleaseRevokeReason
import de.taktovia.fleetsafety.api.ShiftVehiclePort
import de.taktovia.fleetsafety.api.SwitchActorRole
import de.taktovia.fleetsafety.api.SwitchReason
import de.taktovia.fleetsafety.api.SwitchVehicle
import de.taktovia.fleetsafety.api.VehicleSwitchView
import de.taktovia.fleetsafety.api.VehicleSwitches
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
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import java.time.Clock
import java.util.UUID

/**
 * Fahrzeugwechsel in laufender Schicht (WP-M3-22, siehe [VehicleSwitches]). Eine Mandantentransaktion; Sperrreihenfolge: Fahrzeugzeilen (nach Kennung, wie die Sperr- und Freigabeoperationen die Fahrzeugzeile
 * zuerst sperren), dann die Schicht (über den Port `staff-time`), dann der Fahrer (gemeinsam, Qualifikation). Das Ersatzfahrzeug erhält hier nie eine Freigabe.
 */
internal class JdbcVehicleSwitches(
    private val ctxTx: ContextTransactions, private val audit: AuditPort, private val outbox: Outbox, private val clock: Clock, private val readiness: JdbcReadiness, private val shifts: ShiftVehiclePort?,
) : VehicleSwitches {

    override fun switchVehicle(ctx: RequestContext, cmd: SwitchVehicle): VehicleSwitchView {
        val role = roleOf(ctx)
        val port = shifts ?: throw ProblemException(ErrorCode.SERVICE_UNAVAILABLE, "Der Fahrzeugwechsel ist in dieser Anwendung nicht verdrahtet.")
        return ctxTx.run(ctx) { j -> doSwitch(j, ctx, role, cmd, port) }
    }

    override fun history(ctx: RequestContext, shiftId: UUID): List<VehicleSwitchView> {
        val role = roleOf(ctx)
        return ctxTx.run(ctx, readOnly = true) { j ->
            visibleShift(j, ctx, role, shiftId) ?: throw ProblemException.notFound()
            j.query("$SELECT WHERE w.shift_id = ? ORDER BY w.created_at DESC, w.id", ROW, shiftId)
        }
    }

    @AuditRequired
    private fun doSwitch(j: JdbcOperations, ctx: RequestContext, role: SwitchActorRole, cmd: SwitchVehicle, port: ShiftVehiclePort): VehicleSwitchView {
        // Sichtbarkeit zuerst: fremde Schicht, fremder Mandant und (für den Fahrer) nicht eigene Schicht sind gleichermaßen 404
        val seen = visibleShift(j, ctx, role, cmd.shiftId) ?: throw ProblemException.notFound()
        if (seen.vehicleId != cmd.fromVehicleId) throw stale()
        // Fahrzeuge in fester Reihenfolge sperren: zwei gleichzeitige Wechsel (auch in entgegengesetzter Richtung) laufen nacheinander und verklemmen sich nie
        val vehicles = LinkedHashMap<UUID, Vehicle>()
        for (id in listOf(seen.vehicleId, cmd.newVehicleId).distinct().sortedBy { it.toString() }) vehicles[id] = lockVehicle(j, id) ?: throw ProblemException.notFound()
        // Schicht unter der Sperre erneut lesen: hat ein paralleler Wechsel gewonnen, ist der Ausgangsstand veraltet (409, genau ein Ersatzfahrzeug je Wechselzeitpunkt)
        val shift = lockShift(j, cmd.shiftId) ?: throw ProblemException.notFound()
        if (shift.vehicleId != cmd.fromVehicleId) throw stale()
        if (cmd.newVehicleId == shift.vehicleId) throw ProblemException(ErrorCode.CONFLICT, "Die Schicht fährt bereits dieses Fahrzeug.")
        if (shift.state !in CHANGEABLE || !clock.instant().isBefore(shift.endsAt)) throw ProblemException(ErrorCode.INVALID_STATE_TRANSITION, "Die Schicht ist beendet, entzogen oder abgelaufen; das Fahrzeug ist nicht mehr wechselbar.")
        val target = vehicles.getValue(cmd.newVehicleId)
        if (!target.active) throw ProblemException.invalid(Violation("/new_vehicle_id", ViolationCode.INVALID_VALUE))
        if (activeBlocks(j, cmd.newVehicleId) > 0) throw ProblemException(ErrorCode.CONFLICT, "VEHICLE_BLOCKED: Das Ersatzfahrzeug ist gesperrt.")
        requireQualification(j, shift.driverRef, target.type)

        val actor = FourEyes.refOf(ctx)
        val revoked = readiness.revokeShiftReleasesIn(j, actor, ctx.correlationId, cmd.shiftId, ReleaseRevokeReason.VEHICLE_CHANGED)
        port.change(j, ctx, cmd.shiftId, cmd.newVehicleId)
        val id = UUID.randomUUID()
        j.update(
            "INSERT INTO taktovia.shift_vehicle_switch (tenant_id, id, shift_id, driver_ref, from_vehicle_id, to_vehicle_id, reason_code, reason_note, actor, actor_role, revoked_releases) " +
                "VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            id, cmd.shiftId, shift.driverRef, shift.vehicleId, cmd.newVehicleId, cmd.reason.name, cmd.trimmedNote, actor.value, role.name, revoked.size,
        )
        audit.record(j, AuditEntry(actor, AuditActions.VEHICLE_SWITCHED, ObjectRef("staff_shift", cmd.shiftId), ctx.correlationId, linkedMapOf(
            "switch_id" to id.toString(), "from_vehicle_id" to shift.vehicleId.toString(), "to_vehicle_id" to cmd.newVehicleId.toString(), "reason_code" to cmd.reason.name, "reason_note" to cmd.trimmedNote,
            "actor_role" to role.name, "revoked_releases" to revoked.size)))
        outbox.append(j, OutboxEvent(id, 1, "fleet.vehicle_switched", linkedMapOf(
            "switch_id" to id.toString(), "shift_id" to cmd.shiftId.toString(), "from_vehicle_id" to shift.vehicleId.toString(), "to_vehicle_id" to cmd.newVehicleId.toString(),
            "reason_code" to cmd.reason.name, "revoked_release_count" to revoked.size), clock.instant(), correlationId = ctx.correlationId))
        return j.query("$SELECT WHERE w.id = ?", ROW, id).single()
    }

    /** Qualifikation für das Ersatzfahrzeug: allgemein gültig, und wo für den Fahrzeugtyp Qualifikationsarten hinterlegt sind, mindestens eine davon (neutrale Meldung, keine Personaldetails). */
    private fun requireQualification(j: JdbcOperations, driverRef: UUID, vehicleType: String?) {
        val now = clock.instant()
        val missing = ProblemException(ErrorCode.VALIDATION_FAILED, "Für dieses Fahrzeug fehlt die erforderliche Qualifikation.", listOf(Violation("/new_vehicle_id", ViolationCode.INVALID_VALUE)))
        if (readiness.qualification(j, driverRef, now) == null) throw missing
        val codes = if (vehicleType == null) emptyList() else j.query("SELECT qualification_code FROM taktovia.vehicle_type_qualification WHERE vehicle_type = ? ORDER BY qualification_code", { rs, _ -> rs.getString(1) }, vehicleType)
        if (codes.isNotEmpty() && codes.none { readiness.qualification(j, driverRef, now, lock = false, code = it) != null }) throw missing
    }

    private fun activeBlocks(j: JdbcOperations, vehicleId: UUID): Int = j.queryForObject("SELECT count(*) FROM taktovia.vehicle_block WHERE vehicle_id = ? AND status = 'ACTIVE'", Int::class.java, vehicleId)!!

    private class Vehicle(val active: Boolean, val type: String?)
    private class Seen(val vehicleId: UUID, val driverRef: UUID, val state: String = "", val endsAt: java.time.Instant = java.time.Instant.MAX)

    private fun stale() = ProblemException(ErrorCode.CONFLICT, "Das Fahrzeug der Schicht ist ein anderes als angenommen (zwischenzeitlich gewechselt); aktuellen Stand lesen.")

    private fun lockVehicle(j: JdbcOperations, id: UUID): Vehicle? =
        j.query("SELECT active, vehicle_type FROM taktovia.vehicle WHERE id = ? FOR UPDATE", { rs, _ -> Vehicle(rs.getBoolean(1), rs.getString(2)) }, id).firstOrNull()

    private fun lockShift(j: JdbcOperations, id: UUID): Seen? =
        j.query("SELECT vehicle_id, driver_ref, state, ends_at FROM taktovia.staff_shift WHERE id = ? FOR UPDATE", { rs, _ -> Seen(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getString(3), rs.getTimestamp(4).toInstant()) }, id).firstOrNull()

    /** Die Schicht, soweit der Akteur sie sehen darf: Leitstelle jede des Mandanten (RLS), der Fahrer nur seine eigene. */
    private fun visibleShift(j: JdbcOperations, ctx: RequestContext, role: SwitchActorRole, shiftId: UUID): Seen? =
        if (role == SwitchActorRole.DISPATCH) j.query("SELECT s.vehicle_id, s.driver_ref FROM taktovia.staff_shift s WHERE s.id = ?", { rs, _ -> Seen(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java)) }, shiftId).firstOrNull()
        else j.query("SELECT s.vehicle_id, s.driver_ref FROM taktovia.staff_shift s WHERE s.id = ? AND ${ShiftScope.OWN}", { rs, _ -> Seen(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java)) },   // sql-lint: ok ShiftScope.OWN ist eine Compile-Zeit-Konstante, alle Werte sind Platzhalter
            shiftId, *ShiftScope.args(ctx.actor.issuer, ctx.actor.subject)).firstOrNull()

    /** Leitstelle (Disposition, Betriebsleitung) vor Fahrer; nur menschliche Akteure (kein Dienstkonto), jede andere Rolle: 403. */
    private fun roleOf(ctx: RequestContext): SwitchActorRole {
        if (ctx.actor.type != ActorType.HUMAN) throw ProblemException(ErrorCode.FORBIDDEN, "Den Fahrzeugwechsel löst nur ein Mensch aus.")
        return when {
            ctx.roles.any { it in DISPATCH_ROLES } -> SwitchActorRole.DISPATCH
            "driver" in ctx.roles -> SwitchActorRole.DRIVER
            else -> throw ProblemException(ErrorCode.FORBIDDEN, "Für diese Aktion fehlt die Rolle.")
        }
    }

    private companion object {
        val DISPATCH_ROLES = setOf("dispatcher", "tenant_admin")
        /** Zustände, in denen das Fahrzeug der Schicht wechselbar ist (angetreten, geplant oder veröffentlicht, nicht beendet oder entzogen). */
        val CHANGEABLE = setOf("PLANNED", "PUBLISHED", "STARTED")
        const val SELECT = "SELECT w.id, w.shift_id, w.from_vehicle_id, w.to_vehicle_id, w.reason_code, w.reason_note, w.actor_role, w.revoked_releases, w.created_at FROM taktovia.shift_vehicle_switch w"
        val ROW = RowMapper { rs, _ ->
            VehicleSwitchView(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getObject(3, UUID::class.java), rs.getObject(4, UUID::class.java), SwitchReason.valueOf(rs.getString(5)),
                rs.getString(6), SwitchActorRole.valueOf(rs.getString(7)), rs.getInt(8), rs.getTimestamp(9).toInstant())
        }
    }
}

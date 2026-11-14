package de.taktovia.fleetsafety

import de.taktovia.fleetsafety.api.FleetsafetyApi
import de.taktovia.fleetsafety.api.InspectionView
import de.taktovia.fleetsafety.api.ShiftVehiclePort
import de.taktovia.fleetsafety.api.SubmitInspection
import de.taktovia.fleetsafety.api.SwitchReason
import de.taktovia.fleetsafety.api.SwitchVehicle
import de.taktovia.fleetsafety.api.VehicleSwitchView
import de.taktovia.platform.audit.JdbcAuditPort
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.outbox.Outbox
import java.time.Instant
import java.util.UUID

/**
 * Testhilfen des Fahrzeugwechsels (WP-M3-22), eigene Klasse je Cluster. Der Port zur Schichtzuweisung ist hier ein **Stellvertreter**: er setzt `staff_shift.vehicle_id` wie `staff-time` es tut
 * (der Datenbank-Trigger der Überlappung läuft trotzdem); die echte Umsetzung `Shifts.changeVehicle` weist der Anwendungstest `VehicleSwitchApiTest` im Modul `app` nach.
 */
class VehicleSwitchWorld(val fx: FleetFixture) {
    val rw = ReadinessWorld(fx)
    val iw = rw.iw
    val tenant = rw.tenant

    val standIn = ShiftVehiclePort { j, _, shiftId, newVehicle -> j.update("UPDATE taktovia.staff_shift SET vehicle_id = ? WHERE id = ?", newVehicle, shiftId) }
    val api: FleetsafetyApi = FleetsafetyApi.create(fx.ctxTx, JdbcAuditPort(), Outbox(), shiftVehicles = standIn)
    val switches get() = api.vehicleSwitches

    /** Fahrer mit gestarteter Schicht, bestandener Prüfung und gültiger Einsatzfreigabe. */
    class Released(val run: ReadinessWorld.Run, val inspection: InspectionView) {
        val driver get() = run.driver
        val vehicle get() = run.vehicle
        val shift get() = run.shift
        val ctx get() = run.ctx
    }

    fun released(): Released {
        val run = rw.startedRun()
        return Released(run, rw.submit(run, rw.allOk()))
    }

    fun replacement(): UUID = iw.vehicle("PKW")

    /** [from] = das Fahrzeug, das der Auslöser als aktuell sieht (Standard: das jetzt in der Schicht eingetragene). */
    fun switch(who: Released, to: UUID, reason: SwitchReason = SwitchReason.BREAKDOWN, note: String? = null, ctx: RequestContext = who.ctx, from: UUID = shiftVehicle(who.shift)): VehicleSwitchView =
        switches.switchVehicle(ctx, SwitchVehicle(who.shift, from, to, reason, note))

    fun dispatcher(): RequestContext = rw.ctx("dispatcher")

    /** Neuer Prüflauf der Art `VEHICLE_CHANGE` für das Ersatzfahrzeug (nach dem Wechsel) und dessen Abschluss mit lauter OK-Antworten. */
    fun inspect(who: Released, vehicle: UUID): InspectionView {
        val started = iw.start(who.ctx, who.shift, vehicle, "VEHICLE_CHANGE").inspection
        return fx.fleet.inspections.submit(who.ctx, started.id, started.revision, SubmitInspection(rw.allOk(), Instant.now().minusSeconds(5), null))
    }

    fun shiftVehicle(shift: UUID): UUID = UUID.fromString(fx.strings("SELECT vehicle_id::text FROM taktovia.staff_shift WHERE id = ?", shift).single())
}

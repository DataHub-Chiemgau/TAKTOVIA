package de.taktovia.app.fleet

import de.taktovia.app.identity.RequestContexts
import de.taktovia.fleetsafety.api.FleetsafetyApi
import de.taktovia.fleetsafety.api.SwitchVehicle
import de.taktovia.fleetsafety.api.VehicleSwitchView
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Fahrzeugwechsel in laufender Schicht (WP-M3-22, UI-07/UI-09): `POST /v1/shifts/{shift_id}/vehicle-switches` (idempotent über `Idempotency-Key` im Filter) und die Wechselhistorie. Das Recht
 * `fleet:switch` (Fahrer, Disposition, Betriebsleitung) kommt aus der Rechtematrix im Filter; Mandant und Akteur aus dem Kontext, nie aus dem Body; die Schicht steht im Pfad.
 */
@RestController
class VehicleSwitchController(private val fleet: FleetsafetyApi) {

    @PostMapping("/v1/shifts/{shift_id}/vehicle-switches")
    fun create(@PathVariable("shift_id") shiftId: UUID, @RequestBody body: Map<String, Any?>, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        val view = fleet.vehicleSwitches.switchVehicle(RequestContexts.require(request), SwitchVehicle.parse(shiftId, body))
        return ResponseEntity.status(HttpStatus.CREATED).body(json(view))
    }

    @GetMapping("/v1/shifts/{shift_id}/vehicle-switches")
    fun list(@PathVariable("shift_id") shiftId: UUID, request: HttpServletRequest): Map<String, Any?> =
        linkedMapOf("items" to fleet.vehicleSwitches.history(RequestContexts.require(request), shiftId).map { json(it) })

    private fun json(v: VehicleSwitchView): Map<String, Any?> = linkedMapOf(
        "id" to v.id.toString(), "shift_id" to v.shiftId.toString(), "from_vehicle_id" to v.fromVehicleId.toString(), "to_vehicle_id" to v.toVehicleId.toString(), "reason_code" to v.reason.name,
        "note" to v.note, "actor_role" to v.actorRole.name, "revoked_releases" to v.revokedReleases, "inspection_required" to true, "created_at" to v.createdAt.toString(),
    )
}

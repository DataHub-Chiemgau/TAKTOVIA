package de.taktovia.app.fleet

import com.fasterxml.jackson.annotation.JsonValue
import de.taktovia.app.identity.RequestContexts
import de.taktovia.fleetsafety.api.FleetsafetyApi
import de.taktovia.fleetsafety.api.OperationalBlockInput
import de.taktovia.fleetsafety.api.OperationalLiftInput
import de.taktovia.fleetsafety.api.VehicleBlockView
import de.taktovia.platform.api.Etag
import de.taktovia.platform.api.Revisioned
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.util.UUID

class VehicleBlockResource(@get:JsonValue val data: Map<String, Any?>, override val revision: Long) : Revisioned

/**
 * Operative Fahrzeugsperre durch die Leitstelle (WP-M3-41, LH-6.7-OPS-005, UI-07): `createVehicleBlock` und `liftVehicleBlock`. Rolle und Scope (`operator:write`) prüft die Rechtematrix im Filter, den
 * `Idempotency-Key` der Idempotenzfilter (Wiederholung liefert die erste Antwort, ohne das Modul zu erreichen). Mandant nur aus dem Kontext, nie aus dem Body. Das Modul prüft Sichtbarkeit (404), `If-Match`
 * (Sperren: Revision der Fahrzeugsicht, Aufheben: Revision der Sperre; 428 ohne, 412 veraltet) und die Aufhebungsregeln (eine `SAFETY`-Sperre ist hier nie aufhebbar, 409).
 * 201 bei neuer Sperre, 200 bei bereits aktiver Sperre desselben Grundes (keine zweite).
 */
@RestController
class OperatorBlockController(private val fleet: FleetsafetyApi) {

    @PostMapping("/v1/operator/vehicles/{vehicle_id}/blocks")
    fun create(@PathVariable("vehicle_id") vehicleId: UUID, @RequestHeader("If-Match", required = false) ifMatch: String?, @RequestBody body: Map<String, Any?>, request: HttpServletRequest): ResponseEntity<VehicleBlockResource> {
        val expected = Etag.parseIfMatch(ifMatch)
        val out = fleet.operatorBlocks.create(RequestContexts.require(request), vehicleId, expected, OperationalBlockInput.parse(body))
        val res = VehicleBlockResource(json(out.block), out.block.revision)
        return if (out.created) ResponseEntity.status(HttpStatus.CREATED).location(URI.create("/v1/operator/vehicles/$vehicleId/blocks/${out.block.id}")).body(res) else ResponseEntity.ok(res)
    }

    @PostMapping("/v1/operator/vehicles/{vehicle_id}/blocks/{block_id}/lift")
    fun lift(@PathVariable("vehicle_id") vehicleId: UUID, @PathVariable("block_id") blockId: UUID, @RequestHeader("If-Match", required = false) ifMatch: String?, @RequestBody body: Map<String, Any?>, request: HttpServletRequest): VehicleBlockResource {
        val expected = Etag.parseIfMatch(ifMatch)
        val out = fleet.operatorBlocks.lift(RequestContexts.require(request), vehicleId, blockId, expected, OperationalLiftInput.parse(body))
        return VehicleBlockResource(json(out.block), out.block.revision)
    }

    /** `VehicleBlock` (api/fleet-safety.yaml); Sperren der Leitstelle sind immer Fahrzeugsperren ohne Vorgangsnummer. */
    private fun json(b: VehicleBlockView): Map<String, Any?> = linkedMapOf(
        "id" to b.id.toString(), "vehicle_id" to b.vehicleId.toString(), "source" to b.source.name, "reason_code" to b.reason.name, "set_at" to b.setAt.toString(), "set_by" to b.setBy,
        "lifted_at" to b.liftedAt?.toString(), "lifted_by" to b.liftedBy, "revision" to b.revision, "block_kind" to "VEHICLE", "case_number" to null, "shift_id" to null,
    )
}

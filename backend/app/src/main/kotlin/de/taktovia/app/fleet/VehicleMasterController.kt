package de.taktovia.app.fleet

import com.fasterxml.jackson.annotation.JsonValue
import de.taktovia.app.identity.RequestContexts
import de.taktovia.fleetsafety.api.CapacityPatch
import de.taktovia.fleetsafety.api.CapacityView
import de.taktovia.fleetsafety.api.ConfiguredVector
import de.taktovia.fleetsafety.api.FleetsafetyApi
import de.taktovia.fleetsafety.api.VehicleMasterInput
import de.taktovia.fleetsafety.api.VehicleMasterView
import de.taktovia.platform.api.CursorCodec
import de.taktovia.platform.api.CursorPosition
import de.taktovia.platform.api.CursorScope
import de.taktovia.platform.api.Etag
import de.taktovia.platform.api.Paging
import de.taktovia.platform.api.Revisioned
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.time.Instant
import java.util.UUID

class VehicleMasterResource(@get:JsonValue val data: Map<String, Any?>, override val revision: Long) : Revisioned

/**
 * Fahrzeugstamm und Kapazitätskonfiguration (WP-M3-03, WP-M3-04, UI-11). Rechte (`fleet:read`, `fleet:write`) aus der Rechtematrix im Filter, Mandant aus dem Kontext (nie aus dem Body).
 * Die Eingaben werden im Modul vollständig geprüft (`VehicleMasterInput.parse`, `CapacityPatch.parse`): negative, fehlende oder nicht ganzzahlige Werte ergeben 422 mit Feldverweis.
 */
@RestController
class VehicleMasterController(private val fleet: FleetsafetyApi, private val codec: CursorCodec) {

    @GetMapping("/v1/operator/vehicle-master")
    fun list(@RequestParam(required = false) limit: String?, @RequestParam(required = false) cursor: String?, @RequestParam(required = false) active: String?, request: HttpServletRequest): Map<String, Any?> {
        val ctx = RequestContexts.require(request)
        val filter = when (active) {
            null -> null
            "true" -> true
            "false" -> false
            else -> throw ProblemException.invalid(Violation("/\$query/active", ViolationCode.INVALID_FORMAT))
        }
        val scope = CursorScope(ctx.tenant.toString(), ctx.actor.subject, "vehicle-master|${filter ?: "-"}")
        val lim = Paging.limit(limit)
        val rows = fleet.vehicles.list(ctx, filter, lim, Paging.position(cursor, codec, scope)?.let { it.createdAt to it.id })
        return Paging.page(rows, lim, { CursorPosition(it.createdAt, it.id) }, codec, scope, Instant.now()).toMap { json(it) }
    }

    @PostMapping("/v1/operator/vehicle-master")
    fun create(@RequestBody body: Map<String, Any?>, request: HttpServletRequest): ResponseEntity<VehicleMasterResource> {
        val v = fleet.vehicles.create(RequestContexts.require(request), VehicleMasterInput.parse(body))
        return ResponseEntity.status(HttpStatus.CREATED).location(URI.create("/v1/operator/vehicle-master/${v.id}")).body(VehicleMasterResource(json(v), v.revision))
    }

    @GetMapping("/v1/operator/vehicle-master/{vehicle_id}")
    fun get(@PathVariable("vehicle_id") id: UUID, request: HttpServletRequest): VehicleMasterResource {
        val v = fleet.vehicles.get(RequestContexts.require(request), id)
        return VehicleMasterResource(json(v), v.revision)
    }

    @PutMapping("/v1/operator/vehicle-master/{vehicle_id}")
    fun update(@PathVariable("vehicle_id") id: UUID, @RequestHeader("If-Match", required = false) ifMatch: String?, @RequestBody body: Map<String, Any?>, request: HttpServletRequest): VehicleMasterResource {
        val expected = Etag.parseIfMatch(ifMatch)
        val v = fleet.vehicles.update(RequestContexts.require(request), id, expected, VehicleMasterInput.parse(body))
        return VehicleMasterResource(json(v), v.revision)
    }

    @GetMapping("/v1/operator/vehicle-master/{vehicle_id}/capacity")
    fun getCapacity(@PathVariable("vehicle_id") id: UUID, request: HttpServletRequest): VehicleMasterResource {
        val c = fleet.vehicleCapacities.getCapacity(RequestContexts.require(request), id)
        return VehicleMasterResource(json(c), c.revision)
    }

    @PatchMapping("/v1/operator/vehicle-master/{vehicle_id}/capacity")
    fun updateCapacity(@PathVariable("vehicle_id") id: UUID, @RequestHeader("If-Match", required = false) ifMatch: String?, @RequestBody body: Map<String, Any?>, request: HttpServletRequest): VehicleMasterResource {
        val expected = Etag.parseIfMatch(ifMatch)
        val c = fleet.vehicleCapacities.updateCapacity(RequestContexts.require(request), id, expected, CapacityPatch.parse(body))
        return VehicleMasterResource(json(c), c.revision)
    }

    private fun json(v: VehicleMasterView): Map<String, Any?> = linkedMapOf(
        "id" to v.id.toString(), "vehicle_label" to v.label, "vehicle_type" to v.type, "operator_organization_id" to v.operatorOrganizationId?.toString(),
        "capacity" to linkedMapOf("seats" to v.seats, "wheelchair" to v.wheelchair), "equipment" to v.equipment, "active" to v.active,
        "dispatch_blocked" to v.dispatchBlocked, "dispatchable" to v.dispatchable, "safety_revision" to v.safetyRevision, "has_capacity_layout" to v.hasCapacityLayout,
        "revision" to v.revision, "created_at" to v.createdAt.toString(), "updated_at" to v.updatedAt.toString(), "depot_id" to v.depotId?.toString(),
    )

    private fun json(v: ConfiguredVector): Map<String, Any?> = linkedMapOf("code" to v.code, "seats" to v.seats, "wheelchair" to v.wheelchair, "equipment" to v.equipment)

    private fun json(c: CapacityView): Map<String, Any?> = linkedMapOf(
        "vehicle_id" to c.vehicleId.toString(), "layout" to c.layout?.toMap(), "effective" to json(c.effective), "vectors" to c.vectors.map { json(it) }, "revision" to c.revision,
    )
}

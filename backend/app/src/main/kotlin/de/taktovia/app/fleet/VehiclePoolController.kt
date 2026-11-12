package de.taktovia.app.fleet

import com.fasterxml.jackson.annotation.JsonValue
import de.taktovia.app.identity.RequestContexts
import de.taktovia.fleetsafety.api.AssignPoolService
import de.taktovia.fleetsafety.api.FleetsafetyApi
import de.taktovia.fleetsafety.api.PoolAssignmentView
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
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

class VehiclePoolResource(@get:JsonValue val data: Map<String, Any?>, override val revision: Long) : Revisioned

/**
 * Dienstzuordnungen des Fahrzeugpools als Teilressource von `/v1/operator/vehicles/{vehicle_id}` (WP-M3-06, UI-11). Rechte (`fleet:read`, `fleet:write`) aus der Rechtematrix im Filter,
 * Mandant aus dem Kontext (nie aus dem Body). Die Eingabe ist durch das OpenAPI-Schema geprüft; fachliche Prüfungen (Intervall, Überschneidung, Mandant) liegen im Modul.
 */
@RestController
class VehiclePoolController(private val fleet: FleetsafetyApi, private val codec: CursorCodec) {

    @GetMapping("/v1/operator/vehicles/{vehicle_id}/service-assignments")
    fun list(
        @PathVariable("vehicle_id") vehicleId: UUID, @RequestParam(required = false) limit: String?, @RequestParam(required = false) cursor: String?,
        @RequestParam(name = "as_of", required = false) asOf: String?, request: HttpServletRequest,
    ): Map<String, Any?> {
        val ctx = RequestContexts.require(request)
        val at = asOf?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() ?: throw ProblemException.invalid(Violation("/\$query/as_of", ViolationCode.INVALID_FORMAT)) }
        // Der Cursor gilt für genau diese Filter (Fahrzeug, Stichtag): wer sie ändert, kann ihn nicht weiterverwenden.
        val scope = CursorScope(ctx.tenant.toString(), ctx.actor.subject, "vehicle-service-assignments|$vehicleId|${at ?: "-"}")
        val lim = Paging.limit(limit)
        val rows = fleet.vehiclePool.list(ctx, vehicleId, at, lim, Paging.position(cursor, codec, scope)?.let { it.createdAt to it.id })
        return Paging.page(rows, lim, { CursorPosition(it.createdAt, it.id) }, codec, scope, at ?: Instant.now()).toMap { json(it) }
    }

    @PostMapping("/v1/operator/vehicles/{vehicle_id}/service-assignments")
    fun create(@PathVariable("vehicle_id") vehicleId: UUID, @RequestBody body: Map<String, Any?>, request: HttpServletRequest): ResponseEntity<VehiclePoolResource> {
        val cmd = AssignPoolService(
            vehicleId, UUID.fromString(body["service_id"] as String), (body["service_area_id"] as String?)?.let(UUID::fromString),
            OffsetDateTime.parse(body["valid_from"] as String).toInstant(), (body["valid_to"] as String?)?.let { OffsetDateTime.parse(it).toInstant() },
        )
        val v = fleet.vehiclePool.assign(RequestContexts.require(request), cmd)
        return ResponseEntity.status(HttpStatus.CREATED).location(URI.create("/v1/operator/vehicles/$vehicleId/service-assignments/${v.id}")).body(VehiclePoolResource(json(v), v.revision))
    }

    @PostMapping("/v1/operator/vehicles/{vehicle_id}/service-assignments/{assignment_id}/end")
    fun end(
        @PathVariable("vehicle_id") vehicleId: UUID, @PathVariable("assignment_id") assignmentId: UUID, @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestBody body: Map<String, Any?>, request: HttpServletRequest,
    ): VehiclePoolResource {
        val expected = Etag.parseIfMatch(ifMatch)
        val v = fleet.vehiclePool.end(RequestContexts.require(request), vehicleId, assignmentId, expected, OffsetDateTime.parse(body["valid_to"] as String).toInstant())
        return VehiclePoolResource(json(v), v.revision)
    }

    private fun json(v: PoolAssignmentView): Map<String, Any?> = linkedMapOf(
        "id" to v.id.toString(), "vehicle_id" to v.vehicleId.toString(), "service_id" to v.serviceId.toString(), "service_area_id" to v.serviceAreaId?.toString(),
        "valid_from" to v.validFrom.toString(), "valid_to" to v.validTo?.toString(), "revision" to v.revision, "created_at" to v.createdAt.toString(),
        "committed_rides_after_end" to v.committedRidesAfterEnd.map {
            linkedMapOf("booking_id" to it.bookingId.toString(), "state" to it.state, "pickup_earliest" to it.pickupEarliest.toString(), "pickup_latest" to it.pickupLatest.toString())
        },
    )
}

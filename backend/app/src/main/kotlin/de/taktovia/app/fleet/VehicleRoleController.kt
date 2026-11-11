package de.taktovia.app.fleet

import com.fasterxml.jackson.annotation.JsonValue
import de.taktovia.app.identity.RequestContexts
import de.taktovia.fleetsafety.api.AssignVehicleRole
import de.taktovia.fleetsafety.api.FleetsafetyApi
import de.taktovia.fleetsafety.api.VehicleRole
import de.taktovia.fleetsafety.api.VehicleRoleAssignmentView
import de.taktovia.platform.api.CursorCodec
import de.taktovia.platform.api.CursorPosition
import de.taktovia.platform.api.CursorScope
import de.taktovia.platform.api.Paging
import de.taktovia.platform.api.Revisioned
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

class VehicleRoleResource(@get:JsonValue val data: Map<String, Any?>, override val revision: Long) : Revisioned

/** Anfrage-DTO „Fahrzeugrolle zuordnen“; der Mandant steht nicht darin, er folgt aus dem Kontext. */
class VehicleRoleAssignRequest(val vehicleId: UUID, val roleCode: String, val organizationId: UUID, val validFrom: Instant, val validTo: Instant?) {
    fun toCommand() = AssignVehicleRole(vehicleId, requireNotNull(VehicleRole.parse(roleCode)) { "role_code ist durch das Schema geprüft" }, organizationId, validFrom, validTo)
    companion object {
        fun from(b: Map<String, Any?>) = VehicleRoleAssignRequest(UUID.fromString(b["vehicle_id"] as String), b["role_code"] as String, UUID.fromString(b["organization_id"] as String),
            OffsetDateTime.parse(b["valid_from"] as String).toInstant(), (b["valid_to"] as String?)?.let { OffsetDateTime.parse(it).toInstant() })
    }
}

/** Fahrzeugrollen mit Gültigkeitsintervallen (WP-M1-34). Rechte (`fleet:read`, `fleet:write`) aus der Rechtematrix im Filter, Mandant aus dem Kontext. */
@RestController
class VehicleRoleController(private val fleet: FleetsafetyApi, private val codec: CursorCodec) {

    @GetMapping("/v1/vehicle-roles")
    fun list(
        @RequestParam(required = false) limit: String?, @RequestParam(required = false) cursor: String?,
        @RequestParam(name = "vehicle_id", required = false) vehicleId: String?, @RequestParam(name = "as_of", required = false) asOf: String?,
        request: HttpServletRequest,
    ): Map<String, Any?> {
        val ctx = RequestContexts.require(request)
        val vehicle = vehicleId?.let { runCatching { UUID.fromString(it) }.getOrNull() ?: throw ProblemException.invalid(Violation("/\$query/vehicle_id", ViolationCode.INVALID_FORMAT)) }
        val at = asOf?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() ?: throw ProblemException.invalid(Violation("/\$query/as_of", ViolationCode.INVALID_FORMAT)) }
        // Der Cursor gilt für genau diese Filter (Fahrzeug, Stichtag): wer sie ändert, kann ihn nicht weiterverwenden.
        val scope = CursorScope(ctx.tenant.toString(), ctx.actor.subject, "vehicle-roles|${vehicle ?: "-"}|${at ?: "-"}")
        val lim = Paging.limit(limit)
        val rows = fleet.vehicleRoles.list(ctx, vehicle, at, lim, Paging.position(cursor, codec, scope)?.let { it.createdAt to it.id })
        return Paging.page(rows, lim, { CursorPosition(it.createdAt, it.id) }, codec, scope, at ?: Instant.now()).toMap { json(it) }
    }

    @PostMapping("/v1/vehicle-roles")
    fun create(@RequestBody body: Map<String, Any?>, request: HttpServletRequest): ResponseEntity<VehicleRoleResource> {
        val v = fleet.vehicleRoles.assign(RequestContexts.require(request), VehicleRoleAssignRequest.from(body).toCommand())
        return ResponseEntity.status(HttpStatus.CREATED).location(URI.create("/v1/vehicle-roles/${v.id}")).body(VehicleRoleResource(json(v), v.revision))
    }

    private fun json(v: VehicleRoleAssignmentView): Map<String, Any?> = linkedMapOf(
        "id" to v.id.toString(), "vehicle_id" to v.vehicleId.toString(), "role_code" to v.role.name, "organization_id" to v.organizationId.toString(),
        "valid_from" to v.validFrom.toString(), "valid_to" to v.validTo?.toString(), "revision" to v.revision,
    )
}

package de.taktovia.app.driver

import com.fasterxml.jackson.annotation.JsonValue
import de.taktovia.app.identity.RequestContexts
import de.taktovia.platform.api.CursorCodec
import de.taktovia.platform.api.CursorPosition
import de.taktovia.platform.api.CursorScope
import de.taktovia.platform.api.Etag
import de.taktovia.platform.api.Paging
import de.taktovia.platform.api.Revisioned
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.platform.time.Rfc3339
import de.taktovia.stafftime.api.ShiftInput
import de.taktovia.stafftime.api.ShiftView
import de.taktovia.stafftime.api.StafftimeApi
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
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

class ShiftResource(@get:JsonValue val data: Map<String, Any?>, override val revision: Long) : Revisioned

/**
 * Schichtzuweisung der Leitstelle (WP-M3-07, LH-6.8-FLT-003; `/v1/rosters` gehört zu M4). Rechte (`shifts:read`, `shifts:write`) aus der Rechtematrix im Filter, Mandant aus dem Kontext,
 * nie aus dem Body. Überlappung ohne dokumentierte Ausnahme: 409 `CONFLICT` mit Ursache; `If-Match` trägt die Revision der Schicht.
 */
@RestController
class ShiftController(private val staff: StafftimeApi, private val codec: CursorCodec) {

    @GetMapping("/v1/shifts")
    fun list(
        @RequestParam(required = false) limit: String?, @RequestParam(required = false) cursor: String?, @RequestParam(name = "driver_id", required = false) driverId: String?,
        @RequestParam(name = "vehicle_id", required = false) vehicleId: String?, @RequestParam(required = false) from: String?, @RequestParam(required = false) to: String?, request: HttpServletRequest,
    ): Map<String, Any?> {
        val ctx = RequestContexts.require(request)
        val driver = uuidParam("driver_id", driverId); val vehicle = uuidParam("vehicle_id", vehicleId)
        val f = instantParam("from", from); val t = instantParam("to", to)
        val scope = CursorScope(ctx.tenant.toString(), ctx.actor.subject, "shifts|${driver ?: "-"}|${vehicle ?: "-"}|${f ?: "-"}|${t ?: "-"}")
        val lim = Paging.limit(limit)
        val rows = staff.shifts.list(ctx, driver, vehicle, f, t, lim, Paging.position(cursor, codec, scope)?.let { it.createdAt to it.id })
        return Paging.page(rows, lim, { CursorPosition(it.startsAt, it.id) }, codec, scope, Instant.now()).toMap { json(it) }
    }

    @PostMapping("/v1/shifts")
    fun create(@RequestBody body: Map<String, Any?>, request: HttpServletRequest): ResponseEntity<ShiftResource> {
        val s = staff.shifts.create(RequestContexts.require(request), ShiftInput.parse(body, update = false))
        return ResponseEntity.status(HttpStatus.CREATED).location(URI.create("/v1/shifts/${s.id}")).body(ShiftResource(json(s), s.revision))
    }

    @GetMapping("/v1/shifts/{shift_id}")
    fun get(@PathVariable("shift_id") id: UUID, request: HttpServletRequest): ShiftResource {
        val s = staff.shifts.get(RequestContexts.require(request), id)
        return ShiftResource(json(s), s.revision)
    }

    @PutMapping("/v1/shifts/{shift_id}")
    fun update(@PathVariable("shift_id") id: UUID, @RequestHeader("If-Match", required = false) ifMatch: String?, @RequestBody body: Map<String, Any?>, request: HttpServletRequest): ShiftResource {
        val expected = Etag.parseIfMatch(ifMatch)
        val s = staff.shifts.update(RequestContexts.require(request), id, expected, ShiftInput.parse(body, update = true))
        return ShiftResource(json(s), s.revision)
    }

    private fun uuidParam(name: String, v: String?): UUID? = v?.let { runCatching { UUID.fromString(it) }.getOrNull() ?: throw ProblemException.invalid(Violation("/\$query/$name", ViolationCode.INVALID_FORMAT)) }
    private fun instantParam(name: String, v: String?): Instant? = v?.let {
        val c = Rfc3339.parse(it, "/\$query/$name"); if (!c.ok) throw ProblemException.invalid(*c.violations.toTypedArray()); c.value!!.toInstant()
    }

    private fun json(s: ShiftView): Map<String, Any?> = linkedMapOf(
        "id" to s.id.toString(), "driver_id" to s.driverId.toString(), "vehicle_id" to s.vehicleId.toString(), "starts_at" to s.startsAt.toString(), "ends_at" to s.endsAt.toString(),
        "duration_minutes" to s.durationMinutes, "timezone" to s.timezone, "state" to s.state, "roster_revision" to s.rosterRevision, "revision" to s.revision,
        "overlap_exception" to s.overlapException?.let { linkedMapOf("approved_by" to it.approvedBy, "reason" to it.reason, "approved_at" to it.approvedAt.toString()) },
        "started_at" to s.startedAt?.toString(), "ended_at" to s.endedAt?.toString(),
    )
}

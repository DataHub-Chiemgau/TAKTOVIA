package de.taktovia.app.staff

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
import de.taktovia.stafftime.api.RosterShiftInput
import de.taktovia.stafftime.api.RosterShiftView
import de.taktovia.stafftime.api.RosterViewKind
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
 * Schichten eines Dienstplans (WP-M4-03, Modul `staff-time`): `/v1/rosters/shifts`. Rechte (`rosters:write|read`) aus der Rechtematrix im Filter und im Dienst; Mandant und Akteur stammen aus dem Kontext,
 * nie aus dem Body. Der Dienstplan (`POST /v1/rosters`) wird als `roster_id` im Body bzw. in der Abfrage geführt.
 */
@RestController
class RosterShiftController(private val staff: StafftimeApi, private val codec: CursorCodec) {

    @GetMapping("/v1/rosters/shifts")
    fun list(
        @RequestParam("roster_id") roster: UUID, @RequestParam(required = false) view: String?, @RequestParam(required = false) open: Boolean?,
        @RequestParam(required = false) limit: String?, @RequestParam(required = false) cursor: String?, request: HttpServletRequest,
    ): Map<String, Any?> {
        val ctx = RequestContexts.require(request)
        val kind = when (view) { null, "planner" -> RosterViewKind.PLANNER; "published" -> RosterViewKind.PUBLISHED; else -> throw ProblemException.invalid(Violation("/\$query/view", ViolationCode.NOT_IN_ENUM)) }
        val scope = CursorScope(ctx.tenant.toString(), ctx.actor.subject, "shifts|$roster|$kind|${open == true}")
        val lim = Paging.limit(limit)
        val rows = staff.rosterShifts.list(ctx, roster, lim, Paging.position(cursor, codec, scope)?.let { it.createdAt to it.id }, kind, open == true)
        return Paging.page(rows, lim, { CursorPosition(it.startsAt, it.id) }, codec, scope, Instant.now()).toMap { json(it) }
    }

    @PostMapping("/v1/rosters/shifts")
    fun create(@RequestBody body: Map<String, Any?>, request: HttpServletRequest): ResponseEntity<ShiftResource> {
        val v = staff.rosterShifts.create(RequestContexts.require(request), UUID.fromString(body["roster_id"] as String), input(body))
        return ResponseEntity.status(HttpStatus.CREATED).location(URI.create("/v1/rosters/shifts/${v.id}?roster_id=${v.rosterId}")).body(ShiftResource(json(v), v.revision))
    }

    @GetMapping("/v1/rosters/shifts/{shift_id}")
    fun get(@PathVariable("shift_id") id: UUID, @RequestParam("roster_id") roster: UUID, request: HttpServletRequest): ShiftResource {
        val v = staff.rosterShifts.get(RequestContexts.require(request), roster, id)
        return ShiftResource(json(v), v.revision)
    }

    @PutMapping("/v1/rosters/shifts/{shift_id}")
    fun update(@PathVariable("shift_id") id: UUID, @RequestBody body: Map<String, Any?>, @RequestHeader("If-Match", required = false) ifMatch: String?, request: HttpServletRequest): ShiftResource {
        val v = staff.rosterShifts.update(RequestContexts.require(request), UUID.fromString(body["roster_id"] as String), id, Etag.parseIfMatch(ifMatch), input(body))
        return ShiftResource(json(v), v.revision)
    }

    private fun input(b: Map<String, Any?>) = RosterShiftInput(
        b["starts_local"] as String?, b["starts_offset"] as String?, b["ends_local"] as String?, b["ends_offset"] as String?, b["timezone"] as String?, b["break_plan"],
        uuid(b, "operator_id")!!, uuid(b, "depot_id")!!, b["function"] as String?, b["required_qualification"] as String?, (b["staffing_need"] as Number).toInt(), uuid(b, "driver_ref"), uuid(b, "vehicle_id"),
    )

    private fun uuid(b: Map<String, Any?>, key: String): UUID? = (b[key] as String?)?.let { runCatching { UUID.fromString(it) }.getOrNull() ?: throw ProblemException.invalid(Violation("/$key", ViolationCode.INVALID_FORMAT)) }

    private fun json(v: RosterShiftView): Map<String, Any?> = linkedMapOf(
        "id" to v.id.toString(), "roster_id" to v.rosterId.toString(), "date" to v.operatingDay.toString(), "starts_local" to v.startsLocal.toString(), "ends_local" to v.endsLocal.toString(),
        "timezone" to v.timezone.id, "starts_at" to v.startsAt.toString(), "ends_at" to v.endsAt.toString(), "duration_min" to v.durationMinutes, "break_plan" to v.breakPlan.toMap(),
        "operator_id" to v.operatorId.toString(), "depot_id" to v.depotId.toString(), "function" to v.function, "required_qualification" to v.requiredQualification, "staffing_need" to v.staffingNeed,
        "driver_ref" to v.driverRef?.toString(), "vehicle_id" to v.vehicleId?.toString(), "state" to v.state, "created_by" to v.createdBy, "created_at" to v.createdAt.toString(), "revision" to v.revision,
    ).also { m -> v.conflicts?.let { c -> m["conflicts"] = c.map(RosterJson::conflict) } }
}

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
import de.taktovia.stafftime.api.CreateRoster
import de.taktovia.stafftime.api.RosterConflict
import de.taktovia.stafftime.api.RosterSide
import de.taktovia.stafftime.api.RosterView
import de.taktovia.stafftime.api.RosterViewKind
import de.taktovia.stafftime.api.PublishRoster
import de.taktovia.stafftime.api.PublishedRoster
import de.taktovia.stafftime.api.RosterRevisionHead
import de.taktovia.stafftime.api.StafftimeApi
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
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class RosterResource(@get:JsonValue val data: Map<String, Any?>, override val revision: Long) : Revisioned

/**
 * Dienstpläne (WP-M4-04, Modul `staff-time`): `/v1/rosters` mit getrenntem Entwurf und veröffentlichtem Plan. Rechte (`rosters:write|read`) aus der Rechtematrix im Filter und im Dienst;
 * Mandant und Akteur stammen aus dem Kontext, nie aus dem Body. Die verbindliche Sicht (`view=published`) zeigt nie den Entwurf.
 */
@RestController
class RosterController(private val staff: StafftimeApi, private val codec: CursorCodec) {

    @GetMapping("/v1/rosters")
    fun list(
        @RequestParam(name = "depot_id", required = false) depot: String?, @RequestParam(required = false) from: String?, @RequestParam(required = false) to: String?,
        @RequestParam(required = false) limit: String?, @RequestParam(required = false) cursor: String?, request: HttpServletRequest,
    ): Map<String, Any?> {
        val ctx = RequestContexts.require(request)
        val depotId = depot?.let { uuid(it, "depot_id") }; val f = date(from, "from"); val t = date(to, "to")
        val scope = CursorScope(ctx.tenant.toString(), ctx.actor.subject, "rosters|${depotId ?: "-"}|${f ?: "-"}|${t ?: "-"}")
        val lim = Paging.limit(limit)
        val rows = staff.rosters.list(ctx, depotId, f, t, lim, Paging.position(cursor, codec, scope)?.let { it.createdAt.atOffset(ZoneOffset.UTC).toLocalDate() to it.id })
        return Paging.page(rows, lim, { CursorPosition(it.weekStart.atStartOfDay().toInstant(ZoneOffset.UTC), it.rosterId) }, codec, scope, Instant.now()).toMap { json(it) }
    }

    @PostMapping("/v1/rosters")
    fun create(@RequestBody body: Map<String, Any?>, request: HttpServletRequest): ResponseEntity<RosterResource> {
        val week = date(body["week_start"] as String?, "week_start") ?: throw ProblemException.invalid(Violation("/week_start", ViolationCode.REQUIRED))
        val v = staff.rosters.create(RequestContexts.require(request), CreateRoster(uuid(body["depot_id"] as String?, "depot_id"), week))
        return ResponseEntity.status(HttpStatus.CREATED).location(URI.create("/v1/rosters/${v.rosterId}")).body(RosterResource(json(v), v.etag))
    }

    @GetMapping("/v1/rosters/{roster_id}")
    fun get(@PathVariable("roster_id") id: UUID, @RequestParam(required = false) view: String?, request: HttpServletRequest): RosterResource {
        val kind = when (view) { null, "planner" -> RosterViewKind.PLANNER; "published" -> RosterViewKind.PUBLISHED; else -> throw ProblemException.invalid(Violation("/\$query/view", ViolationCode.NOT_IN_ENUM)) }
        val v = staff.rosters.get(RequestContexts.require(request), id, kind)
        return RosterResource(json(v), v.etag)
    }

    @PostMapping("/v1/rosters/{roster_id}/draft")
    fun openDraft(@PathVariable("roster_id") id: UUID, @RequestHeader("If-Match", required = false) ifMatch: String?, request: HttpServletRequest): ResponseEntity<RosterResource> {
        val v = staff.rosters.openDraft(RequestContexts.require(request), id, Etag.parseIfMatch(ifMatch))
        return ResponseEntity.status(HttpStatus.CREATED).location(URI.create("/v1/rosters/${v.rosterId}")).body(RosterResource(json(v), v.etag))
    }

    /**
     * Veröffentlicht den geprüften Entwurf (WP-M4-14): `rosters:publish`, `If-Match` (428 ohne, 412 veraltet), `Idempotency-Key` (Wiederholung liefert dieselbe Revision, anderer Inhalt 409
     * `IDEMPOTENCY_CONFLICT`), 409 mit Konfliktliste bei blockierendem Konflikt (keine Revision, kein Ereignis). Die Fahrermeldung geht über die Outbox.
     */
    @PostMapping("/v1/rosters/{roster_id}/publish")
    fun publish(
        @PathVariable("roster_id") id: UUID, @RequestBody body: Map<String, Any?>, @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestHeader("Idempotency-Key", required = false) key: String?, request: HttpServletRequest,
    ): RosterResource {
        val etag = Etag.parseIfMatch(ifMatch)
        val effective = runCatching { java.time.OffsetDateTime.parse(body["effective_from"] as String?).toInstant() }.getOrNull() ?: throw ProblemException.invalid(Violation("/effective_from", ViolationCode.INVALID_FORMAT))
        val impact = runCatching { UUID.fromString(body["impact_report_id"] as String?) }.getOrNull() ?: throw ProblemException.invalid(Violation("/impact_report_id", ViolationCode.INVALID_FORMAT))
        val r = staff.publishedRosters.publish(RequestContexts.require(request), id, PublishRoster(effective, body["reason"] as String? ?: "", impact, etag, key))
        return RosterResource(published(r), r.etag)
    }

    /** Eigensicht (WP-M4-14, UI-09/UI-13): eigene Schichten der aktuellen veröffentlichten Revisionen; nie Entwürfe, nie fremde Schichten. */
    @GetMapping("/v1/rosters/own-shifts")
    fun ownShifts(@RequestParam from: String, @RequestParam to: String, request: HttpServletRequest): Map<String, Any?> {
        val f = date(from, "from")!!; val t = date(to, "to")!!
        return mapOf("items" to staff.publishedRosters.ownShifts(RequestContexts.require(request), f, t).map {
            linkedMapOf(
                "shift_id" to it.shiftId.toString(), "roster_id" to it.rosterId.toString(), "roster_revision" to it.rosterRevision, "operating_day" to it.operatingDay.toString(), "starts_at" to it.startsAt.toString(),
                "ends_at" to it.endsAt.toString(), "timezone" to it.timezone.id, "function" to it.function, "state" to it.state, "break_minutes" to it.breakMinutes,
            )
        })
    }

    /** Revisionen des Dienstplans (WP-M4-15): Auditrecht und Depotrecht. */
    @GetMapping("/v1/rosters/{roster_id}/revisions")
    fun revisions(@PathVariable("roster_id") id: UUID, request: HttpServletRequest): Map<String, Any?> =
        mapOf("items" to staff.rosterHistory.revisions(RequestContexts.require(request), id).map(::head))

    @GetMapping("/v1/rosters/{roster_id}/revisions/{revision}")
    fun revision(@PathVariable("roster_id") id: UUID, @PathVariable("revision") revision: Long, request: HttpServletRequest): Map<String, Any?> {
        val v = staff.rosterHistory.revision(RequestContexts.require(request), id, revision)
        return linkedMapOf("head" to head(v.head), "shifts" to v.shifts.map { s ->
            linkedMapOf(
                "shift_id" to s.shiftId.toString(), "lineage_id" to s.lineageId.toString(), "operating_day" to s.operatingDay.toString(), "starts_at" to s.startsAt.toString(), "ends_at" to s.endsAt.toString(),
                "timezone" to s.timezone.id, "function" to s.function, "required_qualification" to s.requiredQualification, "staffing_need" to s.staffingNeed, "driver_ref" to s.driverRef?.toString(),
                "vehicle_id" to s.vehicleId?.toString(), "depot_id" to s.depotId?.toString(), "break_plan" to s.breakPlan.toMap(), "state" to s.state,
            )
        })
    }

    @GetMapping("/v1/rosters/{roster_id}/shifts/{shift_id}/history")
    fun shiftHistory(@PathVariable("roster_id") id: UUID, @PathVariable("shift_id") shift: UUID, request: HttpServletRequest): Map<String, Any?> =
        mapOf("items" to staff.rosterHistory.shiftHistory(RequestContexts.require(request), id, shift).map {
            linkedMapOf(
                "id" to it.id.toString(), "kind" to it.kind.name, "roster_revision" to it.rosterRevision, "shift_id" to it.shiftId.toString(), "shift_revision" to it.shiftRevision, "actor" to it.actor,
                "occurred_at" to it.occurredAt.toString(), "snapshot" to it.snapshot,
            )
        })

    private fun head(h: RosterRevisionHead): Map<String, Any?> = linkedMapOf(
        "revision" to h.revision, "state" to h.state.name, "author" to h.author, "created_at" to h.createdAt.toString(), "published_at" to h.publishedAt?.toString(), "published_by" to h.publishedBy,
        "effective_from" to h.effectiveFrom?.toString(), "etag" to h.etag,
    )

    private fun published(r: PublishedRoster): Map<String, Any?> = linkedMapOf(
        "resource_id" to r.rosterId.toString(), "revision" to r.etag, "state" to "PUBLISHED", "effective_from" to r.effectiveFrom.toString(), "audit_event_id" to r.auditEventId.toString(),
    )

    private fun uuid(s: String?, name: String): UUID = runCatching { UUID.fromString(s) }.getOrNull() ?: throw ProblemException.invalid(Violation("/$name", ViolationCode.INVALID_FORMAT))
    private fun date(s: String?, name: String): LocalDate? = s?.let { runCatching { LocalDate.parse(it) }.getOrNull() ?: throw ProblemException.invalid(Violation("/\$query/$name", ViolationCode.INVALID_FORMAT)) }

    private fun json(v: RosterView): Map<String, Any?> = linkedMapOf(
        "id" to v.rosterId.toString(), "depot_id" to v.depotId.toString(), "week_start" to v.weekStart.toString(), "draft" to v.draft?.let(::side), "published" to v.published?.let(::side),
        "conflicts" to v.conflicts?.map(::conflict), "hints" to v.hints?.map(RosterJson::hint), "revision" to v.etag,
    )

    private fun side(s: RosterSide): Map<String, Any?> = linkedMapOf(
        "revision" to s.revision, "state" to s.state.name, "author" to s.author, "created_at" to s.createdAt.toString(), "published_at" to s.publishedAt?.toString(), "etag" to s.etag,
        "shift_count" to s.shiftCount, "open_shifts" to s.openShifts, "unstaffed_need" to s.unstaffedNeed,
        "need_lines" to s.needLines.map { linkedMapOf("date" to it.day.toString(), "function" to it.function, "need" to it.need, "assigned" to it.assigned, "unstaffed" to it.unstaffed) },
    )

    private fun conflict(c: RosterConflict) = RosterJson.conflict(c)
}

/** JSON-Form der Konfliktmeldung (`RosterConflict` im Vertrag `api/rosters.yaml`). */
internal object RosterJson {
    /** JSON-Form des Regelhinweises (`RuleHint` in `api/common.yaml`, WP-M4-13). */
    fun hint(h: de.taktovia.stafftime.api.RuleHint): Map<String, Any?> = linkedMapOf(
        "rule" to h.rule.name, "basis" to h.basis.name, "person_ref" to h.personRef, "expected" to h.expected, "actual" to h.actual, "unit" to h.unit, "day" to h.day.toString(),
        "from" to h.from.toString(), "to" to h.to.toString(), "shift_ids" to h.shiftIds.map { it.toString() }, "event_ids" to h.eventIds.map { it.toString() }, "message" to h.message,
    )

    fun conflict(c: RosterConflict): Map<String, Any?> = linkedMapOf(
        "code" to "ROSTER_" + c.kind.name, "person_ref" to c.personRef.toString(), "shift_ids" to c.shiftIds.map { it.toString() }, "cause" to c.cause, "missing_qualification" to c.missingQualification,
    )
}

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
import de.taktovia.stafftime.api.AbsenceKind
import de.taktovia.stafftime.api.AbsenceView
import de.taktovia.stafftime.api.AvailabilityRestriction
import de.taktovia.stafftime.api.CreateAbsence
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
import java.time.OffsetDateTime
import java.util.UUID

class AbsenceResource(@get:JsonValue val data: Map<String, Any?>, override val revision: Long) : Revisioned

/**
 * Abwesenheit als Verfügbarkeitsrestriktion (WP-M4-06, Modul `staff-time`). Rechte (`absences:write|read|detail`) aus der Rechtematrix im Filter; die Listensicht liefert
 * ausschließlich die neutrale Restriktion („nicht verfügbar“), die Art gibt es nur im Detail der Personalrolle. Mandant und Akteur stammen aus dem Kontext, nie aus dem Body.
 */
@RestController
class AbsenceController(private val staff: StafftimeApi, private val codec: CursorCodec) {

    @GetMapping("/v1/absences")
    fun list(
        @RequestParam(required = false) limit: String?, @RequestParam(required = false) cursor: String?,
        @RequestParam(name = "person_ref", required = false) person: String?, @RequestParam(required = false) from: String?, @RequestParam(required = false) to: String?,
        request: HttpServletRequest,
    ): Map<String, Any?> {
        val ctx = RequestContexts.require(request)
        if (person != null && (person.isEmpty() || person.length > 200)) throw ProblemException.invalid(Violation("/\$query/person_ref", ViolationCode.INVALID_FORMAT))
        val f = instant(from, "from"); val t = instant(to, "to")
        val scope = CursorScope(ctx.tenant.toString(), ctx.actor.subject, "absences|${person ?: "-"}|${f ?: "-"}|${t ?: "-"}")
        val lim = Paging.limit(limit)
        val rows = staff.absences.restrictions(ctx, person, f, t, lim, Paging.position(cursor, codec, scope)?.let { it.createdAt to it.id })
        return Paging.page(rows, lim, { CursorPosition(it.createdAt, it.id) }, codec, scope, Instant.now()).toMap { json(it) }
    }

    /** Eigensicht (WP-M4-20, UI-13): die eigenen genehmigten Abwesenheitszeiträume, neutral; die Person ist immer der Akteur. */
    @GetMapping("/v1/absences/own")
    fun own(
        @RequestParam(required = false) limit: String?, @RequestParam(required = false) cursor: String?, @RequestParam(required = false) from: String?, @RequestParam(required = false) to: String?,
        request: HttpServletRequest,
    ): Map<String, Any?> {
        val ctx = RequestContexts.require(request)
        val f = instant(from, "from"); val t = instant(to, "to")
        val scope = CursorScope(ctx.tenant.toString(), ctx.actor.subject, "absences-own|${f ?: "-"}|${t ?: "-"}")
        val lim = Paging.limit(limit)
        val rows = staff.absences.own(ctx, f, t, lim, Paging.position(cursor, codec, scope)?.let { it.createdAt to it.id })
        return Paging.page(rows, lim, { CursorPosition(it.createdAt, it.id) }, codec, scope, Instant.now()).toMap { json(it) }
    }

    @PostMapping("/v1/absences")
    fun create(@RequestBody body: Map<String, Any?>, request: HttpServletRequest): ResponseEntity<AbsenceResource> {
        val cmd = CreateAbsence(
            body["person_ref"] as String, OffsetDateTime.parse(body["from"] as String).toInstant(), OffsetDateTime.parse(body["to"] as String).toInstant(),
            requireNotNull(AbsenceKind.entries.firstOrNull { it.name == body["kind"] }) { "kind ist durch das Schema geprüft" },
        )
        val v = staff.absences.create(RequestContexts.require(request), cmd)
        return ResponseEntity.status(HttpStatus.CREATED).location(URI.create("/v1/absences/${v.id}")).body(AbsenceResource(json(v), v.revision))
    }

    @GetMapping("/v1/absences/{absence_id}")
    fun get(@PathVariable("absence_id") id: UUID, request: HttpServletRequest): AbsenceResource {
        val v = staff.absences.get(RequestContexts.require(request), id)
        return AbsenceResource(json(v), v.revision)
    }

    @PostMapping("/v1/absences/{absence_id}/withdraw")
    fun withdraw(@PathVariable("absence_id") id: UUID, @RequestHeader("If-Match", required = false) ifMatch: String?, request: HttpServletRequest): AbsenceResource {
        val v = staff.absences.withdraw(RequestContexts.require(request), id, Etag.parseIfMatch(ifMatch))
        return AbsenceResource(json(v), v.revision)
    }

    /** Ein unkodiertes `+` im Offset kommt als Leerzeichen an und wird zurückgewandelt. */
    private fun instant(raw: String?, name: String): Instant? = raw?.let { runCatching { OffsetDateTime.parse(it.replace(' ', '+')).toInstant() }.getOrNull() ?: throw ProblemException.invalid(Violation("/\$query/$name", ViolationCode.INVALID_FORMAT)) }

    private fun json(v: AbsenceView): Map<String, Any?> = linkedMapOf(
        "id" to v.id.toString(), "person_ref" to v.personRef, "from" to v.from.toString(), "to" to v.to.toString(), "kind" to v.kind.name, "state" to v.state.name,
        "created_by" to v.createdBy, "created_at" to v.createdAt.toString(), "revision" to v.revision,
    )

    private fun json(r: AvailabilityRestriction): Map<String, Any?> = linkedMapOf(
        "id" to r.id.toString(), "person_ref" to r.personRef, "from" to r.from.toString(), "to" to r.to.toString(), "availability" to "UNAVAILABLE",
    )
}

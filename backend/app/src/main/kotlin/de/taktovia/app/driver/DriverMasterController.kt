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
import de.taktovia.stafftime.api.DriverCreate
import de.taktovia.stafftime.api.DriverStatus
import de.taktovia.stafftime.api.DriverUpdate
import de.taktovia.stafftime.api.DriverView
import de.taktovia.stafftime.api.QualificationInput
import de.taktovia.stafftime.api.QualificationView
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
import java.time.OffsetDateTime
import java.util.UUID

class DriverMasterResource(@get:JsonValue val data: Map<String, Any?>, override val revision: Long) : Revisioned

/**
 * Fahrerstamm und Qualifikationsrevisionen (WP-M3-05, UI-11): Verwaltungsendpunkte für Personalplanung und Disposition. Rechte (`drivers:read`, `drivers:write`) aus der Rechtematrix im Filter,
 * Mandant aus dem Kontext. HR-nahe Felder gibt das Modul nur der Personalplanung heraus; die Eingaben prüft das Modul vollständig (422 mit Feldverweis).
 */
@RestController
class DriverMasterController(private val staff: StafftimeApi, private val codec: CursorCodec) {

    @GetMapping("/v1/drivers")
    fun list(@RequestParam(required = false) limit: String?, @RequestParam(required = false) cursor: String?, @RequestParam(required = false) status: String?, request: HttpServletRequest): Map<String, Any?> {
        val ctx = RequestContexts.require(request)
        val filter = status?.let { s -> DriverStatus.entries.firstOrNull { it.name == s } ?: throw ProblemException.invalid(Violation("/\$query/status", ViolationCode.NOT_IN_ENUM)) }
        val scope = CursorScope(ctx.tenant.toString(), ctx.actor.subject, "drivers|${filter ?: "-"}")
        val lim = Paging.limit(limit)
        val rows = staff.drivers.list(ctx, filter, lim, Paging.position(cursor, codec, scope)?.let { it.createdAt to it.id })
        return Paging.page(rows, lim, { CursorPosition(it.createdAt, it.id) }, codec, scope, Instant.now()).toMap { json(it) }
    }

    @PostMapping("/v1/drivers")
    fun create(@RequestBody body: Map<String, Any?>, request: HttpServletRequest): ResponseEntity<DriverMasterResource> {
        val d = staff.drivers.create(RequestContexts.require(request), DriverCreate.parse(body))
        return ResponseEntity.status(HttpStatus.CREATED).location(URI.create("/v1/drivers/${d.id}")).body(DriverMasterResource(json(d), d.revision))
    }

    @GetMapping("/v1/drivers/{driver_id}")
    fun get(@PathVariable("driver_id") id: UUID, request: HttpServletRequest): DriverMasterResource {
        val d = staff.drivers.get(RequestContexts.require(request), id)
        return DriverMasterResource(json(d), d.revision)
    }

    @PutMapping("/v1/drivers/{driver_id}")
    fun update(@PathVariable("driver_id") id: UUID, @RequestHeader("If-Match", required = false) ifMatch: String?, @RequestBody body: Map<String, Any?>, request: HttpServletRequest): DriverMasterResource {
        val expected = Etag.parseIfMatch(ifMatch)
        val d = staff.drivers.update(RequestContexts.require(request), id, expected, DriverUpdate.parse(body))
        return DriverMasterResource(json(d), d.revision)
    }

    @GetMapping("/v1/drivers/{driver_id}/qualifications")
    fun qualifications(
        @PathVariable("driver_id") id: UUID, @RequestParam(required = false) at: String?, @RequestParam(name = "only_valid", required = false) onlyValid: String?, request: HttpServletRequest,
    ): Map<String, Any?> {
        val ctx = RequestContexts.require(request)
        val instant = at?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() ?: throw ProblemException.invalid(Violation("/\$query/at", ViolationCode.INVALID_FORMAT)) } ?: Instant.now()
        val only = when (onlyValid) { null, "false" -> false; "true" -> true; else -> throw ProblemException.invalid(Violation("/\$query/only_valid", ViolationCode.INVALID_FORMAT)) }
        val items = staff.drivers.qualifications(ctx, id, instant, only)
        return linkedMapOf("items" to items.map { json(it, null) }, "next_cursor" to null, "as_of" to Instant.now().toString(), "at" to instant.toString())
    }

    @PostMapping("/v1/drivers/{driver_id}/qualifications")
    fun addQualification(
        @PathVariable("driver_id") id: UUID, @RequestHeader("If-Match", required = false) ifMatch: String?, @RequestBody body: Map<String, Any?>, request: HttpServletRequest,
    ): ResponseEntity<DriverMasterResource> {
        val expected = Etag.parseIfMatch(ifMatch)
        val (q, d) = staff.drivers.addQualification(RequestContexts.require(request), id, expected, QualificationInput.parse(body))
        // ETag der Antwort ist die neue Revision des Fahrers (nächster If-Match)
        return ResponseEntity.status(HttpStatus.CREATED).location(URI.create("/v1/drivers/$id/qualifications")).body(DriverMasterResource(json(q, d.revision), d.revision))
    }

    private fun json(d: DriverView): Map<String, Any?> = linkedMapOf(
        "id" to d.id.toString(), "membership_id" to d.membershipId?.toString(), "status" to d.status.name, "qualification_seq" to d.qualificationSeq, "revision" to d.revision,
        "hr_fields_visible" to (d.hr != null), "display_name" to d.hr?.displayName, "contact_channel" to d.hr?.contactChannel?.name, "contact_value" to d.hr?.contactValue,
        "created_at" to d.createdAt.toString(), "updated_at" to d.updatedAt.toString(),
    )

    private fun json(q: QualificationView, driverRevision: Long?): Map<String, Any?> = linkedMapOf<String, Any?>(
        "id" to q.id.toString(), "qualification_code" to q.code, "revision" to q.revision, "status" to q.status.name, "valid_from" to q.validFrom.toString(), "valid_to" to q.validTo?.toString(),
        "approved_by" to q.approvedBy, "approved_at" to q.approvedAt?.toString(), "driver_seq" to q.driverSeq, "evidence_ref" to q.evidenceRef, "effective" to q.effective, "valid_at" to q.validAt,
        "created_at" to q.createdAt.toString(),
    ).also { if (driverRevision != null) it["driver_revision"] = driverRevision }
}

package de.taktovia.app.fleet

import com.fasterxml.jackson.annotation.JsonValue
import de.taktovia.app.identity.RequestContexts
import de.taktovia.fleetsafety.api.DefectCatalog
import de.taktovia.fleetsafety.api.DefectAmendment
import de.taktovia.fleetsafety.api.DefectFilter
import de.taktovia.fleetsafety.api.EvidenceView
import de.taktovia.fleetsafety.api.DefectTransition
import de.taktovia.fleetsafety.api.DefectView
import de.taktovia.fleetsafety.api.FleetsafetyApi
import de.taktovia.fleetsafety.api.ReportDefect
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
import java.time.format.DateTimeParseException
import java.util.UUID

class DefectResource(@get:JsonValue val data: Map<String, Any?>, override val revision: Long) : Revisioned

/** Wire-Darstellung der Schadenakte (`Defect` in `api/fleet-safety.yaml`); gemeinsam für alle Endpunkte der Akte. */
internal object DefectJson {
    fun of(d: DefectView): Map<String, Any?> = linkedMapOf(
        "id" to d.id.toString(), "case_number" to d.caseNumber, "vehicle_id" to d.vehicleId.toString(), "reported_by" to d.reportedBy.toString(),
        "reported_at" to d.reportedAt.toString(), "occurred_at" to d.occurredAt.toString(), "category_code" to d.categoryCode,
        "location_kind" to d.locationKind, "location_value" to d.locationValue, "kind" to d.kind, "vehicle_area" to d.vehicleArea, "observed_effect" to d.observedEffect,
        "source_inspection_id" to d.sourceInspectionId?.toString(), "source_step_code" to d.sourceStepCode,
        "severity" to d.severity, "status" to d.status, "assignee_ref" to d.assigneeRef?.toString(), "blocks_vehicle" to d.blocksVehicle,
        "evidence_ids" to d.evidence.map { it.id.toString() }, "evidence_pending" to d.evidencePending, "content_version" to d.contentVersion,
        "description" to d.description, "revision" to d.revision,
    )

    fun resource(d: DefectView) = DefectResource(of(d), d.revision)

    /** Wire-Darstellung eines Fotos (`DefectEvidence`); der Hash geht als base64url (siehe `DefectEvidenceController`). */
    fun evidence(e: EvidenceView, defectId: UUID): Map<String, Any?> = linkedMapOf(
        "id" to e.id.toString(), "defect_id" to defectId.toString(), "mime" to e.mime, "size_bytes" to e.sizeBytes,
        "sha256" to java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { e.sha256.substring(it * 2, it * 2 + 2).toInt(16).toByte() }),
        "captured_at" to e.capturedAt.toString(), "received_at" to e.receivedAt.toString(), "added_in_version" to e.addedInVersion, "replaces_id" to e.replacesId?.toString(),
    )
}

/**
 * Schadenakte: Meldung, Pflichtdaten, Fallnummer (WP-M3-43, UI-11/UI-10). Rechte (`defects:read`, `defects:write`) aus der Rechtematrix im Filter, Mandant aus dem Kontext (nie aus dem Body),
 * Idempotenz über `Idempotency-Key` im Filter und über `device_event_id` im Modul. Die Eingabe prüft das Modul vollständig (422 mit Feldverweis).
 */
@RestController
class DefectController(private val fleet: FleetsafetyApi, private val codec: CursorCodec) {

    @PostMapping("/v1/defects")
    fun create(@RequestBody body: Map<String, Any?>, request: HttpServletRequest): ResponseEntity<DefectResource> {
        val r = fleet.defects.report(RequestContexts.require(request), ReportDefect.parse(body))
        val res = DefectJson.resource(r.defect)
        return if (r.created) ResponseEntity.status(HttpStatus.CREATED).location(URI.create("/v1/defects/${r.defect.id}")).body(res) else ResponseEntity.ok(res)
    }

    @PostMapping("/v1/defects/{defect_id}/transitions")
    fun transition(@PathVariable("defect_id") id: UUID, @RequestHeader("If-Match", required = false) ifMatch: String?, @RequestBody body: Map<String, Any?>, request: HttpServletRequest): DefectResource =
        DefectJson.resource(fleet.defects.transition(RequestContexts.require(request), id, Etag.parseIfMatch(ifMatch), DefectTransition.parse(body)))

    @GetMapping("/v1/defects/{defect_id}/status-history")
    fun statusHistory(@PathVariable("defect_id") id: UUID, request: HttpServletRequest): Map<String, Any?> {
        val items = fleet.defects.statusHistory(RequestContexts.require(request), id)
        return linkedMapOf("items" to items.map { h ->
            linkedMapOf("id" to h.id.toString(), "from_status" to h.fromStatus, "to_status" to h.toStatus, "actor" to h.actor, "reason" to h.reason, "revision" to h.revision,
                "reopening" to h.reopening, "occurred_at" to h.occurredAt.toString())
        }, "next_cursor" to null, "as_of" to Instant.now().toString())
    }

    @PostMapping("/v1/defects/{defect_id}/amendments")
    fun amend(@PathVariable("defect_id") id: UUID, @RequestBody body: Map<String, Any?>, request: HttpServletRequest): DefectResource =
        DefectJson.resource(fleet.defects.amend(RequestContexts.require(request), id, DefectAmendment.parse(body)))

    @GetMapping("/v1/defects/{defect_id}/history")
    fun history(@PathVariable("defect_id") id: UUID, request: HttpServletRequest): Map<String, Any?> {
        val items = fleet.defects.history(RequestContexts.require(request), id)
        return linkedMapOf("items" to items.map { r ->
            linkedMapOf("version" to r.version, "author" to r.author, "change_reason" to r.changeReason, "created_at" to r.createdAt.toString(), "content" to r.content, "evidence" to r.evidence.map { DefectJson.evidence(it, id) })
        }, "next_cursor" to null, "as_of" to Instant.now().toString())
    }

    @GetMapping("/v1/defects/{defect_id}/as-of")
    fun asOf(@PathVariable("defect_id") id: UUID, @RequestParam(required = false) at: String?, request: HttpServletRequest): Map<String, Any?> {
        val ts = at?.let { instant("at", it) } ?: throw bad("at", ViolationCode.REQUIRED)
        val r = fleet.defects.stateAt(RequestContexts.require(request), id, ts)
        return linkedMapOf("as_of" to r.asOf.toString(), "version" to r.version, "version_created_at" to r.versionCreatedAt.toString(), "status" to r.status, "content" to r.content,
            "evidence" to r.evidence.map { DefectJson.evidence(it, id) })
    }

    @GetMapping("/v1/defects/{defect_id}")
    fun get(@PathVariable("defect_id") id: UUID, request: HttpServletRequest): DefectResource = DefectJson.resource(fleet.defects.get(RequestContexts.require(request), id))

    @GetMapping("/v1/defects")
    fun list(
        @RequestParam(required = false) limit: String?, @RequestParam(required = false) cursor: String?, @RequestParam(required = false) vehicle_id: String?,
        @RequestParam(required = false) status: String?, @RequestParam(required = false) severity: String?, @RequestParam(required = false) reported_from: String?,
        @RequestParam(required = false) reported_to: String?, request: HttpServletRequest,
    ): Map<String, Any?> {
        val ctx = RequestContexts.require(request)
        val filter = DefectFilter(
            vehicleId = vehicle_id?.let { uuid("vehicle_id", it) },
            status = status?.also { if (it !in DefectCatalog.STATUSES) throw bad("status", ViolationCode.NOT_IN_ENUM) },
            severity = severity?.also { if (it !in DefectCatalog.SEVERITIES) throw bad("severity", ViolationCode.NOT_IN_ENUM) },
            reportedFrom = reported_from?.let { instant("reported_from", it) }, reportedTo = reported_to?.let { instant("reported_to", it) },
        )
        val scope = CursorScope(ctx.tenant.toString(), ctx.actor.subject, "defects|${filter.vehicleId ?: "-"}|${filter.status ?: "-"}|${filter.severity ?: "-"}|${filter.reportedFrom ?: "-"}|${filter.reportedTo ?: "-"}")
        val lim = Paging.limit(limit)
        val rows = fleet.defects.list(ctx, filter, lim, Paging.position(cursor, codec, scope)?.let { it.createdAt to it.id })
        return Paging.page(rows, lim, { CursorPosition(it.createdAt, it.id) }, codec, scope, Instant.now()).toMap { DefectJson.of(it) }
    }

    private fun bad(name: String, code: ViolationCode) = ProblemException.invalid(Violation("/\$query/$name", code))
    private fun uuid(name: String, v: String): UUID = runCatching { UUID.fromString(v) }.getOrNull() ?: throw bad(name, ViolationCode.INVALID_FORMAT)
    private fun instant(name: String, v: String): Instant = try { OffsetDateTime.parse(v).toInstant() } catch (e: DateTimeParseException) { throw bad(name, ViolationCode.INVALID_FORMAT) }
}

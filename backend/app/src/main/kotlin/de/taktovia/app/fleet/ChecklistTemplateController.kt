package de.taktovia.app.fleet

import com.fasterxml.jackson.annotation.JsonValue
import de.taktovia.app.identity.RequestContexts
import de.taktovia.fleetsafety.api.ChecklistStepView
import de.taktovia.fleetsafety.api.ChecklistTemplateInput
import de.taktovia.fleetsafety.api.ChecklistTemplateState
import de.taktovia.fleetsafety.api.ChecklistTemplateView
import de.taktovia.fleetsafety.api.FleetsafetyApi
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
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.time.Instant
import java.util.Base64
import java.util.UUID

class ChecklistTemplateResource(@get:JsonValue val data: Map<String, Any?>, override val revision: Long) : Revisioned

/**
 * Checklistenvorlagen und Prüfelemente (WP-M3-10, UI-11): Verwaltung unter `/v1/operator/checklist-templates` (Rechte `checklists:read|write` aus der Rechtematrix im Filter),
 * Lesen der freigegebenen Revisionen für die Fahrer-App unter `/v1/checklist-templates` (`inspections:read`). Der Mandant kommt aus dem Kontext, nie aus dem Body; alle
 * Eingaben prüft das Modul vollständig (`ChecklistTemplateInput.parse`, 422 mit Feldverweis).
 */
@RestController
class ChecklistTemplateController(private val fleet: FleetsafetyApi, private val codec: CursorCodec) {

    @GetMapping("/v1/checklist-templates")
    fun listPublished(@RequestParam(required = false) limit: String?, @RequestParam(required = false) cursor: String?,
                      @RequestParam("vehicle_class", required = false) vehicleClass: String?, @RequestParam("service_mode", required = false) serviceMode: String?, request: HttpServletRequest): Map<String, Any?> {
        val ctx = RequestContexts.require(request)
        val scope = CursorScope(ctx.tenant.toString(), ctx.actor.subject, "checklist-published|${vehicleClass ?: "-"}|${serviceMode ?: "-"}")
        val lim = Paging.limit(limit)
        val rows = fleet.checklistTemplates.list(ctx, ChecklistTemplateState.PUBLISHED, vehicleClass, serviceMode, lim, Paging.position(cursor, codec, scope)?.let { it.createdAt to it.id })
        return Paging.page(rows, lim, { CursorPosition(it.createdAt, it.id) }, codec, scope, Instant.now()).toMap { published(it) }
    }

    @GetMapping("/v1/operator/checklist-templates")
    fun list(@RequestParam(required = false) limit: String?, @RequestParam(required = false) cursor: String?, @RequestParam(required = false) state: String?,
             @RequestParam("vehicle_class", required = false) vehicleClass: String?, @RequestParam("service_mode", required = false) serviceMode: String?, request: HttpServletRequest): Map<String, Any?> {
        val ctx = RequestContexts.require(request)
        val st = state?.let { s -> ChecklistTemplateState.entries.firstOrNull { it.name == s } ?: throw ProblemException.invalid(Violation("/\$query/state", ViolationCode.NOT_IN_ENUM)) }
        val scope = CursorScope(ctx.tenant.toString(), ctx.actor.subject, "checklist-revisions|${st ?: "-"}|${vehicleClass ?: "-"}|${serviceMode ?: "-"}")
        val lim = Paging.limit(limit)
        val rows = fleet.checklistTemplates.list(ctx, st, vehicleClass, serviceMode, lim, Paging.position(cursor, codec, scope)?.let { it.createdAt to it.id })
        return Paging.page(rows, lim, { CursorPosition(it.createdAt, it.id) }, codec, scope, Instant.now()).toMap { json(it) }
    }

    @PostMapping("/v1/operator/checklist-templates")
    fun create(@RequestBody body: Map<String, Any?>, request: HttpServletRequest): ResponseEntity<ChecklistTemplateResource> {
        val v = fleet.checklistTemplates.create(RequestContexts.require(request), ChecklistTemplateInput.parse(body))
        return ResponseEntity.status(HttpStatus.CREATED).location(URI.create("/v1/operator/checklist-templates/${v.id}")).body(resource(v))
    }

    @GetMapping("/v1/operator/checklist-templates/{checklist_revision_id}")
    fun get(@PathVariable("checklist_revision_id") id: UUID, request: HttpServletRequest): ChecklistTemplateResource = resource(fleet.checklistTemplates.get(RequestContexts.require(request), id))

    @PutMapping("/v1/operator/checklist-templates/{checklist_revision_id}")
    fun update(@PathVariable("checklist_revision_id") id: UUID, @RequestHeader("If-Match", required = false) ifMatch: String?, @RequestBody body: Map<String, Any?>, request: HttpServletRequest): ChecklistTemplateResource {
        val expected = Etag.parseIfMatch(ifMatch)
        return resource(fleet.checklistTemplates.updateDraft(RequestContexts.require(request), id, expected, ChecklistTemplateInput.parse(body)))
    }

    @PostMapping("/v1/operator/checklist-templates/{checklist_revision_id}/revisions")
    fun newRevision(@PathVariable("checklist_revision_id") id: UUID, @RequestHeader("If-Match", required = false) ifMatch: String?, request: HttpServletRequest): ResponseEntity<ChecklistTemplateResource> {
        val v = fleet.checklistTemplates.newRevision(RequestContexts.require(request), id, Etag.parseIfMatch(ifMatch))
        return ResponseEntity.status(HttpStatus.CREATED).location(URI.create("/v1/operator/checklist-templates/${v.id}")).body(resource(v))
    }

    @PostMapping("/v1/operator/checklist-templates/{checklist_revision_id}/publish")
    fun publish(@PathVariable("checklist_revision_id") id: UUID, @RequestHeader("If-Match", required = false) ifMatch: String?, request: HttpServletRequest): ChecklistTemplateResource {
        val expected = Etag.parseIfMatch(ifMatch)
        return resource(fleet.checklistTemplates.publish(RequestContexts.require(request), id, expected))
    }

    @PutMapping("/v1/operator/checklist-templates/{checklist_revision_id}/steps/{step_code}/example-image")
    fun putImage(@PathVariable("checklist_revision_id") id: UUID, @PathVariable("step_code") stepCode: String, @RequestHeader("If-Match", required = false) ifMatch: String?, @RequestBody body: Map<String, Any?>, request: HttpServletRequest): ChecklistTemplateResource {
        val expected = Etag.parseIfMatch(ifMatch)
        val type = body["content_type"] as? String ?: throw ProblemException.invalid(Violation("/content_type", ViolationCode.REQUIRED))
        val data = body["data_base64"] as? String ?: throw ProblemException.invalid(Violation("/data_base64", ViolationCode.REQUIRED))
        val bytes = try { Base64.getDecoder().decode(data) } catch (e: IllegalArgumentException) { throw ProblemException.invalid(Violation("/data_base64", ViolationCode.INVALID_FORMAT)) }
        return resource(fleet.checklistTemplates.putExampleImage(RequestContexts.require(request), id, stepCode, expected, bytes, type))
    }

    @GetMapping("/v1/operator/checklist-templates/{checklist_revision_id}/steps/{step_code}/example-image")
    fun getImage(@PathVariable("checklist_revision_id") id: UUID, @PathVariable("step_code") stepCode: String, request: HttpServletRequest): Map<String, Any?> {
        val img = fleet.checklistTemplates.exampleImage(RequestContexts.require(request), id, stepCode)
        return linkedMapOf("content_type" to img.contentType, "data_base64" to Base64.getEncoder().encodeToString(img.bytes))
    }

    private fun resource(v: ChecklistTemplateView) = ChecklistTemplateResource(json(v), v.revision)

    private fun json(v: ChecklistTemplateView): Map<String, Any?> = linkedMapOf(
        "id" to v.id.toString(), "template_id" to v.templateId.toString(), "version" to v.version, "name" to v.name, "vehicle_class" to v.vehicleClass, "service_mode" to v.serviceMode,
        "template_type" to v.templateType.name, "state" to v.state.name, "steps" to v.steps.map { step(it) }, "created_by" to v.createdBy, "approved_by" to v.approvedBy, "published_at" to v.publishedAt?.toString(),
        "revision" to v.revision, "created_at" to v.createdAt.toString(),
    )

    private fun step(s: ChecklistStepView): Map<String, Any?> = linkedMapOf(
        "step_code" to s.code, "position" to s.position, "title" to s.title, "help_text" to s.helpText, "input_kind" to s.kind.code, "mandatory" to s.mandatory, "safety_critical" to s.safetyCritical,
        "na_requires_reason" to s.naRequiresReason, "photo_required_on_defect" to s.photoRequiredOnDefect, "number_min" to s.numberMin, "number_max" to s.numberMax, "number_unit" to s.numberUnit,
        "has_example_image" to (s.exampleImageName != null), "equipment_condition" to s.equipmentCondition, "restricts_dimension" to s.restrictsDimension, "block_kind" to s.blockKind,
    )

    /** Sicht der Fahrer-App auf eine freigegebene Revision (`ChecklistTemplate`). */
    private fun published(v: ChecklistTemplateView): Map<String, Any?> = linkedMapOf(
        "id" to v.id.toString(), "template_id" to v.templateId.toString(), "version" to v.version, "vehicle_class" to v.vehicleClass, "service_mode" to v.serviceMode,
        "published_at" to v.publishedAt!!.toString(),
        "steps" to v.steps.map {
            linkedMapOf("step_code" to it.code, "title" to it.title, "mandatory" to it.mandatory, "safety_critical" to it.safetyCritical, "answer_kind" to it.kind.code,
                "photo_required_on_defect" to it.photoRequiredOnDefect, "help_text" to it.helpText, "na_requires_reason" to it.naRequiresReason, "number_min" to it.numberMin,
                "number_max" to it.numberMax, "number_unit" to it.numberUnit, "has_example_image" to (it.exampleImageName != null),
                "equipment_condition" to it.equipmentCondition, "restricts_dimension" to it.restrictsDimension, "block_kind" to it.blockKind)
        },
    )
}

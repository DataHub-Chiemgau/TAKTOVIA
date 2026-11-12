package de.taktovia.app.fleet

import com.fasterxml.jackson.annotation.JsonValue
import de.taktovia.app.identity.RequestContexts
import de.taktovia.fleetsafety.api.FleetsafetyApi
import de.taktovia.fleetsafety.api.InspectionView
import de.taktovia.fleetsafety.api.StartInspection
import de.taktovia.fleetsafety.api.CreateInspectionAmendment
import de.taktovia.fleetsafety.api.InspectionAmendmentView
import de.taktovia.fleetsafety.api.SaveInspectionDraft
import de.taktovia.fleetsafety.api.SubmitInspection
import de.taktovia.platform.api.Etag
import de.taktovia.platform.api.Revisioned
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.util.UUID

class InspectionResource(@get:JsonValue val data: Map<String, Any?>, override val revision: Long) : Revisioned

/**
 * Prüflauf-Start (WP-M3-13, UI-09): `POST /v1/inspections` (idempotent über `Idempotency-Key` im Filter; die Vorlage bestimmt allein der Server, ein mitgesendetes
 * `template_revision_id` ist wirkungslos) und `GET /v1/inspections/{id}` mit fester Vorlagenrevision und Antworten. Rechte (`inspections:read|write`) aus der Rechtematrix im Filter,
 * Mandant und Fahrer aus dem Kontext, nie aus dem Body.
 */
@RestController
class InspectionController(private val fleet: FleetsafetyApi) {

    @PostMapping("/v1/inspections")
    fun create(@RequestBody body: Map<String, Any?>, request: HttpServletRequest): ResponseEntity<InspectionResource> {
        val r = fleet.inspections.start(RequestContexts.require(request), StartInspection.parse(body))
        val res = resource(r.inspection)
        return if (r.created) ResponseEntity.status(HttpStatus.CREATED).location(URI.create("/v1/inspections/${r.inspection.id}")).body(res) else ResponseEntity.ok(res)
    }

    /** Abschluss (WP-M3-14, `submitInspection`): `If-Match` ist die Revision des Laufs (428 fehlt, 412 veraltet); Prüfer und Ergebnis setzt allein der Server. */
    @PostMapping("/v1/inspections/{inspection_id}/submit")
    fun submit(@PathVariable("inspection_id") id: UUID, @RequestHeader("If-Match", required = false) ifMatch: String?, @RequestBody body: Map<String, Any?>, request: HttpServletRequest): InspectionResource {
        val expected = Etag.parseIfMatch(ifMatch)
        return resource(fleet.inspections.submit(RequestContexts.require(request), id, expected, SubmitInspection.parse(body)))
    }

    /** Entwurf speichern (WP-M3-15, `saveInspectionDraft`): nur im offenen Lauf; `If-Match` ist die Revision des Laufs, jede Speicherung erhöht sie. */
    @PutMapping("/v1/inspections/{inspection_id}")
    fun saveDraft(@PathVariable("inspection_id") id: UUID, @RequestHeader("If-Match", required = false) ifMatch: String?, @RequestBody body: Map<String, Any?>, request: HttpServletRequest): InspectionResource {
        val expected = Etag.parseIfMatch(ifMatch)
        return resource(fleet.inspections.saveDraft(RequestContexts.require(request), id, expected, SaveInspectionDraft.parse(body)))
    }

    /** Nachtrag zu einer Originalantwort des abgeschlossenen Laufs (WP-M3-15, `createInspectionAmendment`): Pflichtgrund, versioniert, überschreibt nie. */
    @PostMapping("/v1/inspections/{inspection_id}/amendments")
    fun amend(@PathVariable("inspection_id") id: UUID, @RequestBody body: Map<String, Any?>, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        val a = fleet.inspections.amend(RequestContexts.require(request), id, CreateInspectionAmendment.parse(body))
        return ResponseEntity.status(HttpStatus.CREATED).body(amendment(a))
    }

    @GetMapping("/v1/inspections/{inspection_id}")
    fun get(@PathVariable("inspection_id") id: UUID, request: HttpServletRequest): InspectionResource = resource(fleet.inspections.get(RequestContexts.require(request), id))

    private fun amendment(a: InspectionAmendmentView): Map<String, Any?> = linkedMapOf(
        "id" to a.id.toString(), "inspection_id" to a.inspectionId.toString(), "step_code" to a.stepCode, "answer_id" to a.answerId.toString(), "version" to a.version, "kind" to a.kind.name,
        "new_answer" to a.newAnswer, "new_number_value" to a.newNumberValue, "new_note" to a.newNote, "reason" to a.reason, "amended_by" to a.amendedBy, "created_at" to a.createdAt.toString())

    private fun resource(v: InspectionView) = InspectionResource(json(v), v.revision)

    private fun json(v: InspectionView): Map<String, Any?> = linkedMapOf(
        "id" to v.id.toString(), "shift_id" to v.shiftId.toString(), "vehicle_id" to v.vehicleId.toString(), "driver_ref" to v.driverRef.toString(),
        "template_revision_id" to v.templateRevisionId.toString(), "kind" to v.kind, "status" to v.status, "outcome" to v.outcome,
        // Fehlende Voraussetzungen der Einsatzfreigabe (Codes, keine Diagnosetexte); solange der Lauf offen ist, fehlt sie wegen der offenen Pflichtschritte
        "missing_requirements" to v.missing.map { m ->
            linkedMapOf("code" to m.name, "inspection_id" to v.id.toString(), "step_codes" to if (m == de.taktovia.fleetsafety.api.ReleaseBlocker.INSPECTION_OPEN) v.template.steps.filter { it.mandatory }.map { it.code } else emptyList<String>())
        },
        "completed_by" to v.completedBy,
        "amendments" to v.amendments.map { amendment(it) },
        "answers" to v.answers.map { linkedMapOf("step_code" to it.stepCode, "answer" to it.answer, "number_value" to it.numberValue, "note" to it.note, "answered_at" to it.answeredAt.toString()) },
        "release" to v.release?.let { linkedMapOf("release_id" to it.releaseId.toString(), "valid_until" to it.validUntil.toString(), "safety_revision" to it.safetyRevision) },
        "started_at" to v.startedAt.toString(), "completed_at" to v.completedAt?.toString(), "received_at" to v.receivedAt?.toString(), "revision" to v.revision,
        "template" to linkedMapOf(
            "template_revision_id" to v.template.revisionId.toString(), "template_id" to v.template.templateId.toString(), "version" to v.template.version,
            "vehicle_class" to v.template.vehicleClass, "service_mode" to v.template.serviceMode, "step_count" to v.template.steps.size,
            "steps" to v.template.steps.mapIndexed { i, s ->
                linkedMapOf("step_code" to s.code, "position" to i + 1, "title" to s.title, "mandatory" to s.mandatory, "safety_critical" to s.safetyCritical, "answer_kind" to s.kind.code,
                    "photo_required_on_defect" to s.photoRequiredOnDefect, "help_text" to s.helpText, "na_requires_reason" to s.naRequiresReason, "number_min" to s.numberMin,
                    "number_max" to s.numberMax, "number_unit" to s.numberUnit, "has_example_image" to (s.exampleImageName != null))
            },
        ),
    )
}

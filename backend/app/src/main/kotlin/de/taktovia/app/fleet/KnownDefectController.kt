package de.taktovia.app.fleet

import de.taktovia.app.identity.RequestContexts
import de.taktovia.fleetsafety.api.AcknowledgeKnownDefect
import de.taktovia.fleetsafety.api.FleetsafetyApi
import de.taktovia.fleetsafety.api.KnownDefectList
import de.taktovia.fleetsafety.api.KnownDefectView
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Bekannte Altschäden anzeigen und quittieren (WP-M3-21, UI-09/UI-10): minimierte Fahrerprojektion (`KnownDefect`, `api/fleet-safety.yaml`), Quittierung ohne Verursacherangabe,
 * Hinweis auf offene Fälle je Fahrzeug vor der Neuanlage. Rechte (`inspections:read|write`, `defects:read`) aus der Rechtematrix im Filter; Mandant und Fahrer aus dem Kontext.
 */
@RestController
class KnownDefectController(private val fleet: FleetsafetyApi) {

    @GetMapping("/v1/inspections/{inspection_id}/known-defects")
    fun list(@PathVariable("inspection_id") inspectionId: UUID, request: HttpServletRequest): Map<String, Any?> =
        json(fleet.knownDefects.forInspection(RequestContexts.require(request), inspectionId))

    @PostMapping("/v1/inspections/{inspection_id}/known-defects/{defect_id}/acknowledge")
    fun acknowledge(@PathVariable("inspection_id") inspectionId: UUID, @PathVariable("defect_id") defectId: UUID, @RequestBody body: Map<String, Any?>, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        val r = fleet.knownDefects.acknowledge(RequestContexts.require(request), inspectionId, defectId, AcknowledgeKnownDefect.parse(body))
        return ResponseEntity.status(if (r.created) HttpStatus.CREATED else HttpStatus.OK).body(json(r.defect))
    }

    @GetMapping("/v1/vehicles/{vehicle_id}/known-defects")
    fun listForVehicle(@PathVariable("vehicle_id") vehicleId: UUID, request: HttpServletRequest): Map<String, Any?> =
        json(fleet.knownDefects.forVehicle(RequestContexts.require(request), vehicleId))

    private fun json(l: KnownDefectList): Map<String, Any?> = linkedMapOf(
        "inspection_id" to l.inspectionId?.toString(), "vehicle_id" to l.vehicleId.toString(), "defects" to l.defects.map { json(it) },
        "acknowledgement_required" to l.acknowledgementRequired, "unacknowledged_count" to l.unacknowledgedCount,
    )

    private fun json(d: KnownDefectView): Map<String, Any?> = linkedMapOf(
        "defect_id" to d.defectId.toString(), "case_number" to d.caseNumber, "category_code" to d.categoryCode, "kind" to d.kind, "vehicle_area" to d.vehicleArea,
        "photos" to d.photos.map { linkedMapOf("evidence_id" to it.evidenceId.toString(), "captured_at" to it.capturedAt.toString()) },
        "still_open" to d.stillOpen, "acknowledged" to d.acknowledged, "acknowledged_at" to d.acknowledgedAt?.toString(), "acknowledged_defect_revision" to d.acknowledgedDefectRevision,
    )
}

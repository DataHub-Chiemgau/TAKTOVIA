package de.taktovia.app.fleet

import com.fasterxml.jackson.annotation.JsonValue
import de.taktovia.app.identity.RequestContexts
import de.taktovia.fleetsafety.api.EvidenceDetail
import de.taktovia.fleetsafety.api.EvidenceTicketView
import de.taktovia.fleetsafety.api.FleetsafetyApi
import de.taktovia.fleetsafety.api.RequestEvidenceTicket
import de.taktovia.platform.api.Etag
import de.taktovia.platform.api.Revisioned
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.time.Instant
import java.util.UUID

class EvidenceResource(@get:JsonValue val data: Map<String, Any?>, override val revision: Long) : Revisioned

/**
 * Fotoevidenz der Schadenakte (WP-M3-44, UI-10/UI-11): Uploadticket, Abschluss mit Medienprüfung, freigegebene Fotos und signierter Abruf. Rechte (`defects:read|write`) aus der Rechtematrix im
 * Filter, Mandant und Akteur aus dem Kontext. Die Datei selbst geht nicht durch die API (JSON, 1 MiB), sondern direkt in die Quarantäne des Objektspeichers (vorsignierter Upload: Anbindung S3/MinIO nicht
 * ausgeführt, WP-M0-08).
 */
@RestController
class DefectEvidenceController(private val fleet: FleetsafetyApi) {

    @PostMapping("/v1/defect-evidence-uploads")
    fun request(@RequestBody body: Map<String, Any?>, request: HttpServletRequest): ResponseEntity<EvidenceResource> {
        val t = fleet.defectEvidence.requestTicket(RequestContexts.require(request), RequestEvidenceTicket.parse(body))
        return ResponseEntity.status(HttpStatus.CREATED).location(URI.create("/v1/defect-evidence-uploads/${t.id}")).body(EvidenceResource(json(t), t.revision))
    }

    @GetMapping("/v1/defect-evidence-uploads/{upload_id}")
    fun get(@PathVariable("upload_id") id: UUID, request: HttpServletRequest): EvidenceResource {
        val t = fleet.defectEvidence.get(RequestContexts.require(request), id)
        return EvidenceResource(json(t), t.revision)
    }

    @PostMapping("/v1/defect-evidence-uploads/{upload_id}/complete")
    fun complete(@PathVariable("upload_id") id: UUID, @RequestHeader("If-Match", required = false) ifMatch: String?, request: HttpServletRequest): EvidenceResource {
        val t = fleet.defectEvidence.complete(RequestContexts.require(request), id, Etag.parseIfMatch(ifMatch))
        return EvidenceResource(json(t), t.revision)
    }

    @GetMapping("/v1/defects/{defect_id}/evidence")
    fun list(@PathVariable("defect_id") defectId: UUID, request: HttpServletRequest): Map<String, Any?> {
        val items = fleet.defectEvidence.listForDefect(RequestContexts.require(request), defectId)
        return linkedMapOf("items" to items.map { json(it) }, "next_cursor" to null, "as_of" to Instant.now().toString())
    }

    @GetMapping("/v1/defects/{defect_id}/evidence/{evidence_id}/download")
    fun download(@PathVariable("defect_id") defectId: UUID, @PathVariable("evidence_id") evidenceId: UUID, request: HttpServletRequest): Map<String, Any?> {
        val link = fleet.defectEvidence.downloadLink(RequestContexts.require(request), defectId, evidenceId)
        return linkedMapOf("url" to link.url, "expires_at" to link.expiresAt.toString())
    }

    /** Der Mandant steht im signierten Pfad, wird aber nie daraus übernommen: `openLinked` prüft Signatur, Ablauf und Gleichheit mit dem Mandanten des Kontexts (fremd = 404). */
    @GetMapping("/v1/objects/{tenant_id}/{object_name}")
    fun readObject(request: HttpServletRequest): ResponseEntity<ByteArray> {
        val url = request.requestURI + (request.queryString?.let { "?$it" } ?: "")
        val o = fleet.defectEvidence.openLinked(RequestContexts.require(request), url)
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(o.mime)).header(HttpHeaders.CACHE_CONTROL, "private, no-store").header("X-Content-Type-Options", "nosniff").body(o.bytes)
    }

    private fun json(t: EvidenceTicketView): Map<String, Any?> = linkedMapOf(
        "id" to t.id.toString(), "state" to t.state.name, "mime" to t.mime, "size_bytes" to t.sizeBytes, "sha256" to t.sha256Wire, "captured_at" to t.capturedAt.toString(),
        "max_bytes" to t.maxBytes, "expires_at" to t.expiresAt.toString(), "upload_key" to t.uploadKey, "reject_reason" to t.rejectReason, "defect_id" to t.defectId?.toString(), "revision" to t.revision,
    )

    private fun json(e: EvidenceDetail): Map<String, Any?> = linkedMapOf(
        "id" to e.id.toString(), "defect_id" to e.defectId.toString(), "mime" to e.mime, "size_bytes" to e.sizeBytes,
        "sha256" to java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { e.sha256Hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }),
        "captured_at" to e.capturedAt.toString(), "received_at" to e.receivedAt.toString(), "added_in_version" to e.addedInVersion, "replaces_id" to e.replacesId?.toString(),
    )
}

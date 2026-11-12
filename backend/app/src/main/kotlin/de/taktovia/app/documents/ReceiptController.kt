package de.taktovia.app.documents

import com.fasterxml.jackson.annotation.JsonValue
import de.taktovia.app.identity.RequestContexts
import de.taktovia.documentsfinance.api.RequestUploadTicket
import de.taktovia.documentsfinance.api.StoredDocumentView
import de.taktovia.documentsfinance.api.UploadPurpose
import de.taktovia.documentsfinance.api.UploadTicketView
import de.taktovia.documentsfinance.api.Uploads
import de.taktovia.platform.api.Etag
import de.taktovia.platform.api.Revisioned
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.util.Base64
import java.util.UUID

class ReceiptResource(@get:JsonValue val data: Map<String, Any?>, override val revision: Long) : Revisioned

/**
 * Uploadticket, Quarantäne und Original des Belegs (WP-M4-21, Modul `documents-finance`). Rechte (`receipts:write|read`) aus der Rechtematrix im Filter; Mandant und Akteur aus dem Kontext.
 * Der Hash wird als base64url übertragen (die Kartendatenerkennung SEC-008 würde lange Hexfolgen gelegentlich fehldeuten); das Modul rechnet intern mit Hex.
 */
@RestController
class ReceiptController(private val uploads: Uploads) {

    @PostMapping("/v1/expenses/receipts")
    fun request(@RequestBody body: Map<String, Any?>, request: HttpServletRequest): ResponseEntity<ReceiptResource> {
        val cmd = RequestUploadTicket(
            UUID.fromString(body["legal_entity_id"] as String), UploadPurpose.valueOf(body["purpose"] as String), body["mime"] as String, (body["size_bytes"] as Number).toLong(), toHex(body["sha256"] as String),
        )
        val t = uploads.requestTicket(RequestContexts.require(request), cmd)
        return ResponseEntity.status(HttpStatus.CREATED).body(ReceiptResource(json(t), t.revision))
    }

    @PostMapping("/v1/expenses/receipts/{ticket_id}/complete")
    fun complete(@PathVariable("ticket_id") id: UUID, @RequestHeader("If-Match", required = false) ifMatch: String?, request: HttpServletRequest): ResponseEntity<ReceiptResource> {
        val d = uploads.complete(RequestContexts.require(request), id, Etag.parseIfMatch(ifMatch))
        return ResponseEntity.status(HttpStatus.CREATED).body(ReceiptResource(json(d), d.revision))
    }

    @GetMapping("/v1/expenses/receipts/{document_id}")
    fun get(@PathVariable("document_id") id: UUID, request: HttpServletRequest): ReceiptResource {
        val d = uploads.document(RequestContexts.require(request), id)
        return ReceiptResource(json(d), d.revision)
    }

    private fun toHex(b64: String): String = Base64.getUrlDecoder().decode(b64).joinToString("") { "%02x".format(it) }
    private fun toB64(hex: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() })

    private fun json(t: UploadTicketView): Map<String, Any?> = linkedMapOf(
        "ticket_id" to t.ticketId.toString(), "purpose" to t.purpose.name, "mime" to t.mime, "size_bytes" to t.sizeBytes, "sha256" to toB64(t.sha256), "max_bytes" to t.maxBytes,
        "expires_at" to t.expiresAt.toString(), "state" to t.state.name, "revision" to t.revision, "upload_key" to t.uploadKey,
        "upload" to t.target?.let { linkedMapOf("method" to it.method, "url" to it.url, "headers" to it.headers) },
    )

    private fun json(d: StoredDocumentView): Map<String, Any?> = linkedMapOf(
        "document_id" to d.documentId.toString(), "ticket_id" to d.ticketId.toString(), "purpose" to d.purpose.name, "state" to d.state.name, "mime" to d.mime, "size_bytes" to d.sizeBytes,
        "sha256" to toB64(d.sha256), "received_at" to d.receivedAt.toString(), "scanned_at" to d.scannedAt?.toString(), "revision" to d.revision,
    )
}

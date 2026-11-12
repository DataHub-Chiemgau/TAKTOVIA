package de.taktovia.app.documents

import com.fasterxml.jackson.annotation.JsonValue
import de.taktovia.app.identity.RequestContexts
import de.taktovia.documentsfinance.api.CorrectExpense
import de.taktovia.documentsfinance.api.ExpenseView
import de.taktovia.documentsfinance.api.Expenses
import de.taktovia.documentsfinance.api.RevisionView
import de.taktovia.platform.api.Etag
import de.taktovia.platform.api.Revisioned
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.util.Base64
import java.util.UUID

class ExpenseResource(@get:JsonValue val data: Map<String, Any?>, override val revision: Long) : Revisioned

/**
 * Beleg lesen, Revisionen, Korrektur und Original (WP-M4-22, WP-M4-23, WP-M4-24; Modul `documents-finance`). Rechte (`receipts:read|write`) aus der Rechtematrix im Filter; Mandant und Akteur aus
 * dem Kontext. Änderungen nur mit `If-Match` (428 ohne, 412 bei veralteter Revision). Das Original wird unverändert ausgeliefert, nach einer Hashprüfung.
 */
@RestController
class ExpenseController(private val expenses: Expenses) {

    @GetMapping("/v1/expenses/{expense_id}")
    fun get(@PathVariable("expense_id") id: UUID, request: HttpServletRequest): ExpenseResource = resource(expenses.get(RequestContexts.require(request), id))

    @PatchMapping("/v1/expenses/{expense_id}")
    @Suppress("UNCHECKED_CAST")
    fun correct(@PathVariable("expense_id") id: UUID, @RequestHeader("If-Match", required = false) ifMatch: String?, @RequestBody body: Map<String, Any?>, request: HttpServletRequest): ExpenseResource {
        val revision = Etag.parseIfMatch(ifMatch)
        val cmd = CorrectExpense(body["reason"] as String, (body["fields"] as Map<String, Any?>?) ?: emptyMap(), ((body["confirm"] as List<String>?) ?: emptyList()).toSet())
        return resource(expenses.correct(RequestContexts.require(request), id, revision, cmd))
    }

    @GetMapping("/v1/expenses/{expense_id}/revisions")
    fun revisions(@PathVariable("expense_id") id: UUID, request: HttpServletRequest): Map<String, Any?> =
        linkedMapOf("items" to expenses.revisions(RequestContexts.require(request), id).map(::json))

    @GetMapping("/v1/expenses/{expense_id}/original")
    fun original(@PathVariable("expense_id") id: UUID, request: HttpServletRequest): ResponseEntity<ByteArray> {
        val ctx = RequestContexts.require(request)
        val mime = expenses.get(ctx, id).document.mime
        val bytes = expenses.original(ctx, id)
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(mime)).cacheControl(CacheControl.noStore()).header("Content-Disposition", "inline; filename=\"beleg-$id\"").body(bytes)
    }

    private fun resource(e: ExpenseView): ExpenseResource {
        val d = e.document
        return ExpenseResource(linkedMapOf(
            "document_id" to d.documentId.toString(), "legal_entity_id" to d.legalEntityId?.toString(), "state" to d.workflowState, "source_channel" to d.channel.name, "document_kind" to d.kind.name,
            "case_ref" to d.caseRef?.let { linkedMapOf("kind" to it.kind.name, "id" to it.id.toString()) }, "mime" to d.mime, "size_bytes" to d.sizeBytes, "sha256" to toB64(d.sha256),
            "received_at" to d.receivedAt.toString(), "scanned_at" to d.scannedAt?.toString(), "sender_verified" to d.senderVerified, "integrity" to d.integrity.name, "approvable" to e.blockers.isEmpty(),
            "blockers" to e.blockers.map { it.name }, "unconfirmed_fields" to e.review.unconfirmedFields, "sum_mismatch" to e.review.sumMismatch, "fields" to e.fields, "revision" to d.revision,
        ), d.revision)
    }

    private fun json(r: RevisionView): Map<String, Any?> = linkedMapOf(
        "revision" to r.revision, "fields" to r.fields.mapValues { (_, c) -> linkedMapOf<String, Any?>("old" to c.old, "new" to c.new, "confirmed" to c.confirmed).also { if (c.ocrProposal != null) it["ocr_proposal"] = c.ocrProposal } },
        "edited_by" to r.editedBy, "edited_at" to r.editedAt.toString(), "reason" to r.reason, "edited_view_key" to r.editedViewKey,
    )

    private fun toB64(hex: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() })
}

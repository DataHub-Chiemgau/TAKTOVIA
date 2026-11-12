package de.taktovia.app.documents

import de.taktovia.app.identity.RequestContexts
import de.taktovia.app.web.IdempotencyFilter
import de.taktovia.documentsfinance.api.ConfirmLedgerImport
import de.taktovia.documentsfinance.api.ExportRequestOutcome
import de.taktovia.documentsfinance.api.ImportConfirmation
import de.taktovia.documentsfinance.api.LedgerExportLineView
import de.taktovia.documentsfinance.api.LedgerExportView
import de.taktovia.documentsfinance.api.LedgerExports
import de.taktovia.documentsfinance.api.RequestLedgerExport
import de.taktovia.platform.api.CursorCodec
import de.taktovia.platform.api.CursorPosition
import de.taktovia.platform.api.CursorScope
import de.taktovia.platform.api.Paging
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/**
 * ERP-Export des Hauptbuchs (WP-M4-37; Modul `documents-finance`): Auftrag anfordern (202, ausgeführt vom Operation-Runner mit Lease und Fencing), lesen, Journale, kurzlebige Downloadadresse, Inhalt und
 * Bestätigungsprotokoll. Rechte (`ledger:read|export`) aus der Rechtematrix im Filter; Menschen zusätzlich mit Finanzrolle im Dienst. Der Antwortfilter für Idempotenz meldet die `operation` des Auftrags.
 */
@RestController
class LedgerExportController(private val exports: LedgerExports, private val codec: CursorCodec) {

    @PostMapping("/v1/ledger/exports")
    fun create(@RequestBody body: Map<String, Any?>, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        val target = (body["target_system"] as String?) ?: LedgerExports.NEUTRAL
        val cmd = RequestLedgerExport(FinanceJson.uuid(body["legal_entity_id"], "/legal_entity_id"), FinanceJson.date(body["period_from"], "/period_from"), FinanceJson.date(body["period_to"], "/period_to"), target)
        return when (val out = exports.request(RequestContexts.require(request), cmd)) {
            is ExportRequestOutcome.Existing -> ResponseEntity.ok(json(out.export))
            is ExportRequestOutcome.Queued -> {
                request.setAttribute(IdempotencyFilter.OPERATION_ID_ATTRIBUTE, out.export.operationId)
                ResponseEntity.status(HttpStatus.ACCEPTED).header("Location", "/v1/operations/${out.export.operationId}").header("Retry-After", "3").body(json(out.export))
            }
        }
    }

    @GetMapping("/v1/ledger/exports")
    fun list(@RequestParam(name = "legal_entity_id", required = false) legalEntityId: String?, @RequestParam(required = false) limit: String?, @RequestParam(required = false) cursor: String?, request: HttpServletRequest): Map<String, Any?> {
        val ctx = RequestContexts.require(request)
        val entity = legalEntityId?.let { v -> try { UUID.fromString(v) } catch (e: IllegalArgumentException) { throw ProblemException.invalid(Violation("/\$query/legal_entity_id", ViolationCode.INVALID_FORMAT)) } }
        val scope = CursorScope(ctx.tenant.toString(), ctx.actor.subject, "ledger-exports:${entity ?: "all"}")
        val lim = Paging.limit(limit)
        val rows = exports.list(ctx, entity, lim + 1, Paging.position(cursor, codec, scope)?.let { it.createdAt to it.id })
        return Paging.page(rows, lim, { CursorPosition(it.createdAt, it.id) }, codec, scope, Instant.now()).toMap(::json)
    }

    @GetMapping("/v1/ledger/exports/{export_id}")
    fun get(@PathVariable("export_id") id: UUID, request: HttpServletRequest): Map<String, Any?> = json(exports.get(RequestContexts.require(request), id))

    @GetMapping("/v1/ledger/exports/{export_id}/lines")
    fun lines(@PathVariable("export_id") id: UUID, request: HttpServletRequest): Map<String, Any?> =
        linkedMapOf("items" to exports.lines(RequestContexts.require(request), id).map(::lineJson), "next_cursor" to null, "as_of" to Instant.now().toString())

    @GetMapping("/v1/ledger/exports/{export_id}/download")
    fun download(@PathVariable("export_id") id: UUID, request: HttpServletRequest): Map<String, Any?> {
        val l = exports.downloadLink(RequestContexts.require(request), id)
        return linkedMapOf("url" to l.url, "expires_at" to l.expiresAt.toString())
    }

    /** Der Mandant steckt in der Signatur und wird nie aus der Adresse übernommen: eine Adresse eines anderen Mandanten ist 404. */
    @GetMapping("/v1/ledger/exports/{export_id}/content")
    fun content(@PathVariable("export_id") id: UUID, @RequestParam("exp") exp: String, @RequestParam("sig") sig: String, request: HttpServletRequest): ResponseEntity<ByteArray> {
        val expires = exp.toLongOrNull() ?: throw ProblemException.invalid(Violation("/\$query/exp", ViolationCode.INVALID_FORMAT))
        val d = exports.content(RequestContexts.require(request), id, expires, sig)
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(d.mediaType)).cacheControl(CacheControl.noStore()).header("X-Content-SHA256", d.sha256).header("X-Content-Type-Options", "nosniff")
            .header("Content-Disposition", "attachment; filename=\"${d.fileName}\"").body(d.bytes)
    }

    @PostMapping("/v1/ledger/exports/{export_id}/confirmation")
    @Suppress("UNCHECKED_CAST")
    fun confirm(@PathVariable("export_id") id: UUID, @RequestBody body: Map<String, Any?>, request: HttpServletRequest): Map<String, Any?> {
        val raw = body["entries"] as? List<*> ?: throw ProblemException.invalid(Violation("/entries", if (body["entries"] == null) ViolationCode.REQUIRED else ViolationCode.TYPE_MISMATCH))
        val entries = raw.mapIndexed { i, e ->
            val m = e as? Map<String, Any?> ?: throw ProblemException.invalid(Violation("/entries/$i", ViolationCode.TYPE_MISMATCH))
            if (m.keys.any { it != "journal_id" && it != "import_ref" }) throw ProblemException.invalid(Violation("/entries/$i", ViolationCode.UNKNOWN_FIELD))
            ImportConfirmation(FinanceJson.uuid(m["journal_id"], "/entries/$i/journal_id"), m["import_ref"] as? String ?: throw ProblemException.invalid(Violation("/entries/$i/import_ref", ViolationCode.REQUIRED)))
        }
        return json(exports.confirm(RequestContexts.require(request), id, ConfirmLedgerImport(entries)))
    }

    private fun json(e: LedgerExportView): Map<String, Any?> = linkedMapOf(
        "id" to e.id.toString(), "legal_entity_id" to e.legalEntityId.toString(), "target_system" to e.targetSystem, "period_from" to e.periodFrom.toString(), "period_to" to e.periodTo.toString(), "state" to e.state.name,
        "operation_id" to e.operationId.toString(), "batch_ref" to e.batchRef, "content_sha256" to e.contentSha256, "content_size" to e.contentSize, "journal_count" to e.journalCount, "format" to e.formatName,
        "format_version" to e.formatVersion, "media_type" to e.mediaType, "result_code" to e.resultCode, "requested_by" to e.requestedBy, "created_at" to e.createdAt.toString(), "provided_at" to e.providedAt?.toString(),
        "confirmed_at" to e.confirmedAt?.toString(),
    )

    private fun lineJson(l: LedgerExportLineView): Map<String, Any?> = linkedMapOf("journal_id" to l.journalId.toString(), "document_id" to l.documentId.toString(), "import_ref" to l.importRef, "confirmed_at" to l.confirmedAt?.toString())
}

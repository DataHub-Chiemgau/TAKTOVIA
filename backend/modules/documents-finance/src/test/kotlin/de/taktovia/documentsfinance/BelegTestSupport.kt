package de.taktovia.documentsfinance

import de.taktovia.documentsfinance.api.DocumentKind
import de.taktovia.documentsfinance.api.DocumentServices
import de.taktovia.documentsfinance.api.DocumentUploads
import de.taktovia.documentsfinance.api.EicarSignatureScanner
import de.taktovia.documentsfinance.api.ExtractionPolicy
import de.taktovia.documentsfinance.api.MalwareScanner
import de.taktovia.documentsfinance.api.ReceiptRecognizer
import de.taktovia.documentsfinance.api.RecognitionResult
import de.taktovia.documentsfinance.api.RecognizedLine
import de.taktovia.documentsfinance.api.RequestUploadTicket
import de.taktovia.documentsfinance.api.SourceChannel
import de.taktovia.documentsfinance.api.StoredDocumentView
import de.taktovia.documentsfinance.api.UpdateDocumentSettings
import de.taktovia.documentsfinance.api.UploadPolicy
import de.taktovia.documentsfinance.api.UploadPurpose
import de.taktovia.platform.audit.JdbcAuditPort
import de.taktovia.platform.context.Actor
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.storage.ObjectKey
import de.taktovia.platform.storage.TenantObjectStore
import de.taktovia.platform.tenant.TenantId
import java.util.UUID

/**
 * Testhilfe der Belegpakete WP-M4-22 bis -27 (eigene Klasse, keine Duplikate der Upload-Fixtures): echte Datenbank unter der Runtime-Rolle, alle Belegbausteine über
 * [DocumentUploads.services], ein Objektspeicher und eine skriptbare OCR-Engine.
 */
class BelegFixture(val store: TenantObjectStore, recognizer: ReceiptRecognizer? = null, scanner: MalwareScanner = EicarSignatureScanner(), extraction: ExtractionPolicy = ExtractionPolicy()) : AutoCloseable {
    val fx = UploadFixture(store)
    val svc: DocumentServices = DocumentUploads.services(fx.ctxTx, JdbcAuditPort(), fx.security, Outbox(), store, scanner, UploadPolicy(maxBytes = 64 * 1024), recognizer = recognizer, extraction = extraction)

    fun financeCtx(w: UploadFixture.World, subject: UUID = UUID.randomUUID()) = fx.ctx(w.tenant, subject, roles = setOf("finance"))
    fun driverCtx(w: UploadFixture.World, subject: UUID = UUID.randomUUID()) = fx.ctx(w.tenant, subject, roles = setOf("driver"))

    /** Dienstkonto eines serverseitigen Kanals (E-Mail-Adapter, Importjob) für den Mandanten. */
    fun serviceCtx(tenant: TenantId) = RequestContext(Actor(ActorType.SERVICE, "https://idp.test/realms/m4", "svc-mail-intake", null, true), tenant, emptySet(), setOf("receipts:write", "receipts:read"), emptySet(), UUID.randomUUID())

    /** Ticket, Upload in die Quarantäne und Abschluss: ein Beleg im Zustand STORED (oder QUARANTINE_REJECTED bei Schadsoftware). */
    fun upload(w: UploadFixture.World, ctx: RequestContext, bytes: ByteArray = Samples.jpeg(), channel: SourceChannel = SourceChannel.DISPATCH_WEB, kind: DocumentKind = DocumentKind.RECEIPT): StoredDocumentView {
        val mime = when { bytes[0] == 0xFF.toByte() -> "image/jpeg"; bytes[0] == 0x89.toByte() -> "image/png"; else -> "application/pdf" }
        val t = svc.uploads.requestTicket(ctx, RequestUploadTicket(w.legalEntity, UploadPurpose.RECEIPT, mime, bytes.size.toLong(), Samples.sha256(bytes), channel, kind))
        store.put(ObjectKey(w.tenant, t.uploadKey), bytes, mime)
        return svc.uploads.complete(ctx, t.ticketId, t.revision)
    }

    fun configureInbound(w: UploadFixture.World, address: String = "rechnung@firma.test", domains: Set<String> = setOf("lieferant.test"), ocr: Boolean = true) {
        svc.settings.update(financeCtx(w), UpdateDocumentSettings(ocr, address, w.legalEntity, domains))
    }

    fun problem(block: () -> Unit): ProblemException = try { block(); throw AssertionError("ProblemException erwartet") } catch (e: ProblemException) { e }

    override fun close() = fx.close()
}

/** OCR-Engine, die zu einem Inhalt (SHA-256) vorgegebene Zeilen liefert; ohne Eintrag erkennt sie nichts (leeres Ergebnis). */
class ScriptedRecognizer(val script: MutableMap<String, List<RecognizedLine>> = mutableMapOf(), override val engine: String = "scripted", override val version: String = "1") : ReceiptRecognizer {
    @Volatile var behavior: (ByteArray) -> RecognitionResult = { b -> RecognitionResult(script[Samples.sha256(b)] ?: emptyList()) }
    val calls = java.util.concurrent.atomic.AtomicInteger()
    override fun recognize(content: ByteArray, mime: String): RecognitionResult { calls.incrementAndGet(); return behavior(content) }
    fun lines(bytes: ByteArray, vararg text: String, confidence: Double = 0.98) { script[Samples.sha256(bytes)] = text.mapIndexed { i, t -> RecognizedLine(t, confidence, 1, 40.0, 780.0 - 14 * i, 300.0, 12.0) } }
}

package de.taktovia.app.documents

import de.taktovia.documentsfinance.api.ClamdScanner
import de.taktovia.adapters.ocr.api.OcrAdapter
import de.taktovia.adapters.ocr.api.OcrTimeoutException
import de.taktovia.adapters.ocr.api.OcrUnsupportedException
import de.taktovia.adapters.ocr.api.PdfTextLayerOcr
import de.taktovia.documentsfinance.api.AccountingMappings
import de.taktovia.documentsfinance.api.CostAssignments
import de.taktovia.documentsfinance.api.CostEvents
import de.taktovia.documentsfinance.api.DocumentServices
import de.taktovia.documentsfinance.api.DocumentSettings
import de.taktovia.documentsfinance.api.DocumentUploads
import de.taktovia.documentsfinance.api.Expenses
import de.taktovia.documentsfinance.api.ExtractionPolicy
import de.taktovia.documentsfinance.api.IntegrityChecks
import de.taktovia.documentsfinance.api.ReceiptExtraction
import de.taktovia.documentsfinance.api.ReceiptIntake
import de.taktovia.documentsfinance.api.ReceiptRecognizer
import de.taktovia.documentsfinance.api.RecognitionResult
import de.taktovia.documentsfinance.api.RecognitionTimeoutException
import de.taktovia.documentsfinance.api.RecognizedLine
import de.taktovia.documentsfinance.api.UnsupportedContentException
import de.taktovia.platform.context.RequestContext
import de.taktovia.documentsfinance.api.EicarSignatureScanner
import de.taktovia.documentsfinance.api.MalwareScanner
import de.taktovia.documentsfinance.api.UnavailableScanner
import de.taktovia.documentsfinance.api.UploadPolicy
import de.taktovia.documentsfinance.api.Uploads
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.audit.SecurityEventPort
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.storage.FileObjectStore
import de.taktovia.platform.storage.InMemoryObjectStore
import de.taktovia.platform.storage.TenantObjectStore
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.nio.file.Path
import java.time.Duration

/**
 * Verdrahtung von Uploadticket, Quarantäne und Malware-Prüfung (WP-M4-21). Einstellungen (`taktovia.documents.…`, Planungsdefaults, Offener Punkt 13, nicht freigegeben):
 * `store-dir` (Wurzel des Dateisystem-Objektspeichers; **Pflicht** außerhalb von `local`/`development`, dort sonst ein flüchtiger Speicher), `scanner` (`clamd` mit `clamd-host`/`clamd-port`;
 * `signature` = nur EICAR, nur in `local`/`development`; ohne Angabe: in `local`/`development` EICAR, sonst **kein Prüfer** = alles bleibt in Quarantäne), `max-bytes` (10 MiB),
 * `ticket-ttl-minutes` (15). Ein S3/MinIO-Objektspeicher-Adapter ist nicht ausgeführt (WP-M0-08).
 */
@Configuration
class DocumentsConfiguration {
    private fun localLike(env: Environment) = (env.getProperty("taktovia.environment")?.trim()?.ifEmpty { null } ?: "production") in setOf("local", "development")

    @Bean
    fun tenantObjectStore(env: Environment): TenantObjectStore {
        val dir = env.getProperty("taktovia.documents.store-dir")?.trim()?.ifEmpty { null }
        check(dir != null || localLike(env)) { "taktovia.documents.store-dir fehlt: außerhalb der lokalen Umgebung ist ein dauerhafter Objektspeicher Pflicht (Belegoriginale)" }
        return if (dir != null) FileObjectStore(Path.of(dir)) else InMemoryObjectStore()
    }

    @Bean
    fun malwareScanner(env: Environment): MalwareScanner {
        val kind = env.getProperty("taktovia.documents.scanner")?.trim()?.ifEmpty { null } ?: if (localLike(env)) "signature" else "none"
        return when (kind) {
            "clamd" -> ClamdScanner(env.getProperty("taktovia.documents.clamd-host", "127.0.0.1"), env.getProperty("taktovia.documents.clamd-port", Int::class.java, 3310))
            "signature" -> { check(localLike(env)) { "taktovia.documents.scanner=signature (nur EICAR) ist außerhalb der lokalen Umgebung nicht zulässig" }; EicarSignatureScanner() }
            "none" -> UnavailableScanner()
            else -> throw IllegalStateException("taktovia.documents.scanner: unbekannter Wert '$kind' (clamd|signature|none)")
        }
    }

    @Bean
    fun uploadPolicy(env: Environment) = UploadPolicy(
        maxBytes = env.getProperty("taktovia.documents.max-bytes", Long::class.java, 10L * 1024 * 1024),
        ticketTtl = Duration.ofMinutes(env.getProperty("taktovia.documents.ticket-ttl-minutes", Long::class.java, 15L)),
    )

    /**
     * OCR-Engine (WP-M4-27): `taktovia.documents.ocr` = `pdf-text` (Vorgabe: lokale Textebene digitaler PDF-Belege, kein externer Dienst) oder `none` (keine Engine: die Extraktion lehnt mit 503 ab,
     * die manuelle Erfassung bleibt). Eine Bild-OCR-Engine und die Datenschutzprüfung eines externen Dienstes sind offen (D-06, Offener Punkt 11).
     */
    @Bean
    fun ocrAdapter(env: Environment): OcrAdapter? = when (val kind = env.getProperty("taktovia.documents.ocr")?.trim()?.ifEmpty { null } ?: "pdf-text") {
        "pdf-text" -> PdfTextLayerOcr()
        "none" -> null
        else -> throw IllegalStateException("taktovia.documents.ocr: unbekannter Wert '$kind' (pdf-text|none)")
    }

    @Bean
    fun extractionPolicy(env: Environment) = ExtractionPolicy(
        lease = Duration.ofSeconds(env.getProperty("taktovia.documents.extraction-lease-seconds", Long::class.java, 60L)),
        recognitionTimeout = Duration.ofSeconds(env.getProperty("taktovia.documents.ocr-timeout-seconds", Long::class.java, 30L)),
        lowConfidenceThreshold = env.getProperty("taktovia.documents.low-confidence-threshold", Double::class.java, 0.80),
    )

    @Bean
    fun documentServices(
        ctxTx: ContextTransactions, audit: AuditPort, security: SecurityEventPort, outbox: Outbox, store: TenantObjectStore, scanner: MalwareScanner, policy: UploadPolicy,
        ocr: java.util.Optional<OcrAdapter>, extraction: ExtractionPolicy,
    ): DocumentServices = DocumentUploads.services(ctxTx, audit, security, outbox, store, scanner, policy, recognizer = ocr.map { OcrBridge(it) as ReceiptRecognizer }.orElse(null), extraction = extraction)

    @Bean fun uploads(s: DocumentServices): Uploads = s.uploads
    @Bean fun expenses(s: DocumentServices): Expenses = s.expenses
    @Bean fun receiptIntake(s: DocumentServices): ReceiptIntake = s.intake
    @Bean fun documentSettings(s: DocumentServices): DocumentSettings = s.settings
    @Bean fun receiptReview(s: DocumentServices): de.taktovia.documentsfinance.api.ReceiptReview = s.review
    @Bean fun integrityChecks(s: DocumentServices): IntegrityChecks = s.integrity
    @Bean fun receiptExtraction(s: DocumentServices): ReceiptExtraction = s.extraction
    @Bean fun costAssignments(s: DocumentServices): CostAssignments = s.costs
    @Bean fun costEvents(s: DocumentServices): CostEvents = s.costEvents
    @Bean fun accountingMappings(s: DocumentServices): AccountingMappings = s.accounting
    @Bean fun costAllocations(s: DocumentServices): de.taktovia.documentsfinance.api.CostAllocations = s.allocations
    @Bean fun expenseSplits(s: DocumentServices): de.taktovia.documentsfinance.api.ExpenseSplits = s.splits
    @Bean fun fiscalPeriods(s: DocumentServices): de.taktovia.documentsfinance.api.FiscalPeriods = s.periods
    @Bean fun costEventCorrections(s: DocumentServices): de.taktovia.documentsfinance.api.CostEventCorrections = s.corrections
    @Bean fun fuelReceipts(s: DocumentServices): de.taktovia.documentsfinance.api.FuelReceipts = s.fuel
    @Bean fun fuelPlausibility(s: DocumentServices): de.taktovia.documentsfinance.api.FuelPlausibility = s.plausibility
    @Bean fun duplicateReview(s: DocumentServices): de.taktovia.documentsfinance.api.DuplicateReview = s.duplicates
    @Bean fun expenseApprovals(s: DocumentServices): de.taktovia.documentsfinance.api.ExpenseApprovals = s.approvals
    @Bean fun financePolicies(s: DocumentServices): de.taktovia.documentsfinance.api.FinancePolicies = s.policies

    /** Führt angestoßene Extraktionsläufe asynchron aus (Antwort 202); der Lauf selbst ist idempotent und lease-gesichert, ein Neustart verliert höchstens den Anstoß, nicht den Lauf. */
    @Bean(destroyMethod = "shutdown")
    fun extractionDispatcher(extraction: ReceiptExtraction) = ExtractionDispatcher(extraction)
}

/** Brücke vom Adapter `ocr` zum Port des Belegmoduls (Module kennen keine Adapter, Leitlinie 1). */
class OcrBridge(private val adapter: OcrAdapter) : ReceiptRecognizer {
    override val engine get() = adapter.engine
    override val version get() = adapter.version
    override fun recognize(content: ByteArray, mime: String): RecognitionResult = try {
        RecognitionResult(adapter.recognize(content, mime).lines.map { RecognizedLine(it.text, it.confidence, it.page, it.x, it.y, it.width, it.height) })
    } catch (e: OcrUnsupportedException) { throw UnsupportedContentException(e.message ?: "nicht lesbar") }
    catch (e: OcrTimeoutException) { throw RecognitionTimeoutException(e.message ?: "Zeitüberschreitung") }
}

class ExtractionDispatcher(private val extraction: ReceiptExtraction) {
    private val log = org.slf4j.LoggerFactory.getLogger("taktovia.documents.extraction")
    private val pool = java.util.concurrent.Executors.newFixedThreadPool(2) { r -> Thread(r, "extraction-dispatch").also { it.isDaemon = true } }

    fun dispatch(ctx: RequestContext, runId: java.util.UUID) {
        pool.submit { try { extraction.process(ctx, runId) } catch (e: Exception) { log.warn("Extraktionslauf {} nicht ausgeführt: {}", runId, e.javaClass.simpleName) } }
    }

    fun shutdown() { pool.shutdown() }
}

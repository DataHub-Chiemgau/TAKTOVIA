package de.taktovia.documentsfinance.api

import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.operation.OperationHandler
import de.taktovia.platform.tenant.TenantTransactions
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Zustände eines Exportbatchs: `REQUESTED` (Auftrag wartet oder läuft), `PROVIDED` (bereitgestellt), `CONFIRMED` (Import bestätigt), `EMPTY` (nichts exportierbar) und `FAILED` (ohne Ergebnis beendet). */
enum class LedgerExportState { REQUESTED, PROVIDED, CONFIRMED, EMPTY, FAILED }

/** Exportbatch ohne Inhalt: `batchRef`, `contentSha256`, `journalCount` und Formatangaben erst ab `PROVIDED`. */
data class LedgerExportView(
    val id: UUID, val legalEntityId: UUID, val targetSystem: String, val periodFrom: LocalDate, val periodTo: LocalDate, val state: LedgerExportState, val operationId: UUID,
    val batchRef: String?, val contentSha256: String?, val contentSize: Int?, val journalCount: Int?, val formatName: String?, val formatVersion: String?, val mediaType: String?, val resultCode: String?,
    val requestedBy: String, val createdAt: Instant, val providedAt: Instant?, val confirmedAt: Instant?,
)

/** Exportauftrag: `periodTo` ist exklusiv; `targetSystem` ist bis zum Beschluss D-06 nur `NEUTRAL` (neutrales Testformat). */
data class RequestLedgerExport(val legalEntityId: UUID, val periodFrom: LocalDate, val periodTo: LocalDate, val targetSystem: String = LedgerExports.NEUTRAL)

/** `Queued`: ein neuer Auftrag wurde eingestellt (die Ausführung übernimmt der Operation-Runner); `Existing`: der Umfang hat bereits einen offenen Auftrag oder einen Batch, nichts Neues (Wiederholung). */
sealed interface ExportRequestOutcome {
    val export: LedgerExportView
    data class Queued(override val export: LedgerExportView) : ExportRequestOutcome
    data class Existing(override val export: LedgerExportView) : ExportRequestOutcome
}

data class ImportConfirmation(val journalId: UUID, val importRef: String)
data class ConfirmLedgerImport(val entries: List<ImportConfirmation>)
data class LedgerExportLineView(val journalId: UUID, val documentId: UUID, val importRef: String?, val confirmedAt: Instant?)
data class LedgerDownloadLink(val url: String, val expiresAt: Instant)
data class LedgerDownload(val mediaType: String, val fileName: String, val bytes: ByteArray, val sha256: String)

// ---- Port zum Zielformat (Adapter `erp-export`, die Anwendung verbindet beides) ----

data class LedgerBatchLine(val account: String, val side: String, val amountMinor: Long)
data class LedgerBatchJournal(val journalId: UUID, val eventId: UUID, val documentId: UUID?, val reversesEventId: UUID?, val accountingDate: LocalDate, val currency: String, val lines: List<LedgerBatchLine>)
data class LedgerBatch(val batchRef: String, val exportId: UUID, val legalEntityId: UUID, val periodFrom: LocalDate, val periodTo: LocalDate, val journals: List<LedgerBatchJournal>)
class RenderedLedgerBatch(val mediaType: String, val fileExtension: String, val bytes: ByteArray)

/** Darstellung eines Batchs im Zielformat: rein und deterministisch (gleiche Eingabe, gleiche Bytes). Ziel-ERP, Format und Kontenrahmen sind offen (D-06). */
interface LedgerExportFormat {
    val name: String
    val version: String
    fun render(batch: LedgerBatch): RenderedLedgerBatch
}

/** `format = null`: kein Zielformat angebunden, der Auftrag endet als `FAILED` (`NO_EXPORT_FORMAT`). `downloadSecret` signiert die kurzlebigen Downloadadressen (mindestens 32 Byte). */
class LedgerExportConfig(val format: LedgerExportFormat? = null, val downloadSecret: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }, val linkTtl: Duration = Duration.ofSeconds(60), val maxJournals: Int = 5000) {
    init { require(downloadSecret.size >= 32) { "Signaturschlüssel zu kurz" } }
}

/**
 * ERP-Export als wiederholsicherer Job (WP-M4-37). Ein Exportauftrag ist eine `operation` (`LEDGER_EXPORT`, Lease und Fencing): der Runner führt ihn aus, ein abgestürzter Worker wird nach Ablauf der Lease von
 * einem anderen ersetzt, der alte schreibt nichts mehr; **ein** Auftrag erzeugt höchstens **einen** Batch (Batch, Zeilen, Beleg-Exportstatus, Audit und Outbox entstehen in der Transaktion, die auch die Operation
 * abschließt). Exportiert werden nur `POSTED`-Journale des Rechtsträgers im Zeitraum, deren Beleg gebucht und vollständig freigegeben ist (Budgetfreigabe, bei Überschreitung der Schwelle auch die zweite) und die
 * noch in keinem Batch sind: ein Journal erscheint in genau einem Batch (Primärschlüssel), auch bei parallelen Aufträgen (Sperre je Rechtsträger). Der Exportstatus am Beleg (nicht exportiert, bereitgestellt,
 * bestätigt) wird ausschließlich im Exportjob und in der Bestätigung gesetzt.
 *
 * **Wiederholung:** dieselbe Anforderung (Rechtsträger, Zielsystem, Zeitraum) liefert denselben offenen Auftrag bzw. den vorhandenen Batch mit denselben Kennungen und demselben Inhaltshash; neu entstehen nur
 * Batches für Journale, die noch in keinem Batch sind. Ohne exportierbare Journale und ohne vorhandenen Batch ist die Anforderung 409.
 *
 * Rechte: Scope `ledger:export` (anfordern, bestätigen, herunterladen; eigene Berechtigung, getrennt von der Freigabe, Kap. 03 §3 Nr. 4) bzw. `ledger:read` (lesen); Menschen brauchen zusätzlich die Finanzrolle,
 * Dienstkonten genügt der Scope. Export und Download stehen im Audit (`finance.ledger_export.*`). Der Download läuft über eine kurzlebige, mandantengebundene signierte Adresse.
 */
interface LedgerExports {
    fun request(ctx: RequestContext, cmd: RequestLedgerExport): ExportRequestOutcome
    fun get(ctx: RequestContext, exportId: UUID): LedgerExportView
    fun list(ctx: RequestContext, legalEntityId: UUID?, limit: Int, after: Pair<Instant, UUID>?): List<LedgerExportView>
    fun lines(ctx: RequestContext, exportId: UUID): List<LedgerExportLineView>

    /** Kurzlebige Downloadadresse (höchstens 60 s) für einen bereitgestellten Batch; sonst 409. */
    fun downloadLink(ctx: RequestContext, exportId: UUID): LedgerDownloadLink

    /** Liefert den Inhalt zu einer signierten Adresse (`exp`, `sig`); ungültige, abgelaufene oder mandantenfremde Adressen sind 404. Audit `finance.ledger_export.downloaded`. */
    fun content(ctx: RequestContext, exportId: UUID, expires: Long, signature: String): LedgerDownload

    /**
     * Bestätigungsprotokoll: die Import-ID je Journal des Batchs (alle Journale genau einmal, sonst 422). Setzt den Batch auf `CONFIRMED` und den Exportstatus der Belege auf bestätigt. Dieselbe Bestätigung
     * wiederholt liefert den unveränderten Stand, eine abweichende 409; ein nicht bereitgestellter Batch 409.
     */
    fun confirm(ctx: RequestContext, exportId: UUID, cmd: ConfirmLedgerImport): LedgerExportView

    /** Ausführer der Operationsart `LEDGER_EXPORT` für den Operation-Runner der Anwendung. */
    fun operationHandler(tx: TenantTransactions): OperationHandler

    companion object {
        const val NEUTRAL = "NEUTRAL"
        const val OPERATION_KIND = "LEDGER_EXPORT"
        const val JOB_ACTOR = "ledger-export-job"
    }
}

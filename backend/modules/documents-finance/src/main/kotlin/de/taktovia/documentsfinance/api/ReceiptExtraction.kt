package de.taktovia.documentsfinance.api

import de.taktovia.platform.context.RequestContext
import java.time.Duration
import java.time.Instant
import java.util.UUID

// ---- Providerunabhängige OCR-Schnittstelle (WP-M4-27) ----

/** Erkannte Zeile mit Konfidenz (0 bis 1) und Fundstelle (Seite ab 1, Koordinaten in Seiteneinheiten). */
data class RecognizedLine(val text: String, val confidence: Double, val page: Int = 1, val x: Double = 0.0, val y: Double = 0.0, val width: Double = 0.0, val height: Double = 0.0) {
    init { require(confidence in 0.0..1.0) { "Konfidenz außerhalb 0..1" } }
}

class RecognitionResult(val lines: List<RecognizedLine>)

/** Die Engine konnte nicht in der vorgegebenen Zeit antworten; der Lauf ist wiederholbar. */
class RecognitionTimeoutException(message: String = "OCR-Zeitüberschreitung") : RuntimeException(message)

/** Die Engine kann diesen Inhalt nicht lesen (z. B. Rasterbild ohne Bild-OCR); der Lauf schlägt endgültig fehl, es werden keine Felder geraten. */
class UnsupportedContentException(message: String = "Inhalt nicht lesbar") : RuntimeException(message)

/**
 * Port zur OCR-Engine hinter einer providerunabhängigen Schnittstelle (Kap. 03 §3 Nr. 2: erkannter Text ist nie Anweisung). Die Anwendung bindet den Adapter `ocr` an. Der Aufruf läuft nie
 * innerhalb einer Datenbanktransaktion. Die Auswahl der Engine und die Datenschutzprüfung bei einem externen Dienst sind offen (D-06, Offener Punkt 11).
 */
interface ReceiptRecognizer {
    val engine: String
    val version: String
    fun recognize(content: ByteArray, mime: String): RecognitionResult
}

// ---- Fachmodell ----

enum class ExtractedFieldKind(val expenseField: String) {
    DOCUMENT_DATE(ExpenseFields.DOCUMENT_DATE), AMOUNT(ExpenseFields.AMOUNT_MINOR), CURRENCY(ExpenseFields.CURRENCY), MERCHANT(ExpenseFields.MERCHANT),
    DOCUMENT_NUMBER(ExpenseFields.DOCUMENT_NUMBER), VAT_AMOUNT(ExpenseFields.VAT_AMOUNTS),
}

enum class FieldOrigin { OCR, HUMAN }
enum class FieldStatus { PROPOSED, CONFIRMED, CORRECTED }
enum class RunStatus { QUEUED, RUNNING, COMPLETED, FAILED }
enum class RunFailure { TIMEOUT, ENGINE_ERROR, UNSUPPORTED_CONTENT, ORIGINAL_UNAVAILABLE }

data class SourceLocation(val page: Int, val x: Double, val y: Double, val width: Double, val height: Double)

/**
 * Ein Feld. `normalizedValue` (Betrag als `amount_minor`-Ganzzahl, Datum ISO-8601, Währung ISO-4217) ist `null`, wenn nichts erkannt wurde. `displayValue` ist die neutralisierte Darstellung
 * (kein Markup, keine Formel); `rawValue` bleibt als Datum erhalten und wird nie ausgeführt.
 */
data class ExtractedFieldView(
    val id: UUID, val kind: ExtractedFieldKind, val occurrence: Int, val origin: FieldOrigin, val status: FieldStatus, val rawValue: String?, val normalizedValue: String?, val displayValue: String?,
    val qualifier: String?, val confidence: Double?, val source: SourceLocation?, val suspicious: Boolean, val correctsFieldId: UUID?, val decidedBy: String?, val decidedAt: Instant?,
)

data class ExtractionRunView(
    val id: UUID, val documentId: UUID, val status: RunStatus, val engine: String, val ocrVersion: String, val attempt: Int, val failure: RunFailure?, val fieldCount: Int?,
    val requestedAt: Instant, val finishedAt: Instant?,
)

/** `proposals` = die unveränderten OCR-Vorschläge, `current` = je Feld (und Vorkommen) der jüngste Stand (Vorschlag, Bestätigung oder Korrektur). */
data class ExtractionDetail(val run: ExtractionRunView, val proposals: List<ExtractedFieldView>, val current: List<ExtractedFieldView>)

/** Lease und Zeitgrenze der Extraktion (Planungsdefaults, nicht freigegeben). */
data class ExtractionPolicy(val lease: Duration = Duration.ofSeconds(60), val recognitionTimeout: Duration = Duration.ofSeconds(30)) {
    init { require(!lease.isNegative && !lease.isZero && !recognitionTimeout.isNegative && !recognitionTimeout.isZero) }
}

/**
 * Feldextraktion zu einem Beleg (WP-M4-27, DOC-002). Ablauf: [request] legt den Lauf an (höchstens einer je Beleg und OCR-Version), [process] führt ihn mit Lease und Fencing aus: der Lauf wird atomar
 * beansprucht (`attempt` als Fencing-Token), die Engine läuft außerhalb jeder Transaktion, und nur der aktuelle Versuch darf das Ergebnis schreiben. Der Rohbeleg bleibt unabhängig vom OCR-Ergebnis
 * archiviert; die Extraktion läuft im Mandantenkontext des Belegs. Korrektur und Bestätigung einzelner Felder laufen über [Expenses.correct].
 */
interface ReceiptExtraction {
    /**
     * Legt den Lauf an oder liefert den vorhandenen (idempotent). Nur für Belege im Zustand `STORED` (sonst 409: Quarantäne wird nie extrahiert); mit `receipt_ocr_enabled = false` 409 (manuelle
     * Erfassung bleibt möglich); ohne Engine 503. Fremde oder nicht sichtbare Belege: 404. `expectedRevision` (`If-Match`): weicht die Belegrevision ab, 412.
     */
    fun request(ctx: RequestContext, documentId: UUID, expectedRevision: Long? = null): ExtractionRunView

    /** Führt den Lauf aus; wiederholbar nach `FAILED` und nach abgelaufener Lease, nie doppelt wirksam. Liefert den Stand nach dem Versuch. */
    fun process(ctx: RequestContext, runId: UUID): ExtractionRunView

    /** Jüngster Lauf mit Feldern; `null`, wenn noch keiner angestoßen wurde. */
    fun latest(ctx: RequestContext, documentId: UUID): ExtractionDetail?
}

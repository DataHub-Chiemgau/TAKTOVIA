package de.taktovia.documentsfinance.api

import de.taktovia.platform.context.RequestContext
import java.time.Instant
import java.util.UUID

/** Herkunftskanal eines Belegs (WP-M4-22, DOC-006). Alle vier Kanäle erzeugen dieselbe fachliche Struktur und durchlaufen Uploadticket und Quarantäne. */
enum class SourceChannel { DRIVER_APP, DISPATCH_WEB, EMAIL_INBOUND, INVOICE_IMPORT }

/** Belegart (Tankbelege erhalten mit WP-M4-30 einen eigenen Feldsatz). */
enum class DocumentKind { RECEIPT, FUEL_RECEIPT, INVOICE, OTHER }

/** Vorgang, an dem ein Beleg hängt: Fahrzeug oder Schicht. */
enum class CaseKind { VEHICLE, SHIFT }
data class CaseRef(val kind: CaseKind, val id: UUID)

/** Ergebnis der Integritätsprüfung am Beleg; `VIOLATED` ist endgültig und sperrt Freigabe und Posting (WP-M4-24). */
enum class IntegrityState { UNCHECKED, VERIFIED, VIOLATED }

/** Gründe, aus denen ein Beleg (noch) nicht freigabefähig ist. Spätere Pakete ergänzen weitere (Pflichtfelder, Kostenzuordnung, Dublette). */
enum class ReleaseBlocker { NOT_STORED, UNVERIFIED_SENDER, INTEGRITY_VIOLATION, UNCONFIRMED_FIELDS, SUM_MISMATCH, COST_ASSIGNMENT_MISSING, COST_OBJECT_INACTIVE, FUEL_FIELDS_MISSING, HINT_UNACKNOWLEDGED, DUPLICATE_REVIEW_OPEN, DUPLICATE_EVIDENCE, SPLIT_INCOMPLETE }

/** Belegfelder, die eine neue Revision erzeugen können (WP-M4-23). Typ und Normalisierung: [ExpenseFields]. */
object ExpenseFields {
    const val AMOUNT_MINOR = "amount_minor"
    const val CURRENCY = "currency"
    const val DOCUMENT_DATE = "document_date"
    const val MERCHANT = "merchant"
    const val DOCUMENT_NUMBER = "document_number"
    const val VAT_AMOUNTS = "vat_amounts"
    /** Nettobetrag (WP-M4-28): Grundlage der Summenprüfung Netto + Steuer = Brutto. */
    const val NET_AMOUNT_MINOR = "net_amount_minor"
    val ALL = setOf(AMOUNT_MINOR, CURRENCY, DOCUMENT_DATE, MERCHANT, DOCUMENT_NUMBER, VAT_AMOUNTS, NET_AMOUNT_MINOR)
    /** Steuerlich notwendige Pflichtfelder: nie aus `receipt_mandatory_fields` entfernbar (WP-M4-28). */
    val PROTECTED_MANDATORY = setOf(AMOUNT_MINOR, CURRENCY, DOCUMENT_DATE, MERCHANT)
}

/** Ein Feld einer Revision: Alt- und Neuwert; `confirmed` = ausdrückliche Bestätigung eines OCR-Vorschlags ohne Wertänderung; `ocrProposal` = der OCR-Vorschlag, den die Entscheidung betrifft. */
data class FieldChange(val old: Any?, val new: Any?, val confirmed: Boolean = false, val ocrProposal: Any? = null)

data class RevisionView(
    val revision: Long, val fields: Map<String, FieldChange>, val editedBy: String, val editedAt: Instant, val reason: String, val editedViewKey: String?,
)

/** Beleg mit aktuellem Feldstand (WP-M4-22/-23): das Dokument (Original, Zustand, Kanal), die bestätigten oder erfassten Felder und die Freigabehindernisse. */
data class ExpenseView(val document: StoredDocumentView, val fields: Map<String, Any?>, val blockers: List<ReleaseBlocker>, val review: ReviewState = ReviewState.NONE, val costObjects: List<CostObjectView> = emptyList(),
    /** Tankwerte (nur Belege der Art `FUEL_RECEIPT`, WP-M4-30). */
    val fuel: FuelView? = null,
    /** Plausibilitätshinweise der aktuellen Revision und Vermerke zu nicht prüfbaren Regeln (WP-M4-31). */
    val plausibility: PlausibilityReport = PlausibilityReport.NONE,
    /** Dublettentreffer, an denen der Beleg beteiligt ist (WP-M4-32). */
    val duplicates: List<DuplicateCandidateView> = emptyList(),
    /** Freigabeschritte (WP-M4-34) und Exportstatus. */
    val approvalSteps: List<ApprovalStepView> = emptyList(), val exportState: ExportState = ExportState.NOT_EXPORTED,
    /** Aufteilungsplan vor dem Posting (WP-M4-35) und der Grund, falls er unvollständig ist (nur bei mehreren zugeordneten Kostenobjekten). */
    val split: SplitView? = null, val splitProblem: String? = null,
    /** Das Kostenereignis des gebuchten Belegs (WP-M4-36: Grundlage des Gegenbelegs), sonst `null`. */
    val costEventId: UUID? = null) {
    /** Freigabefähig, wenn kein Hindernis besteht (Dokument, Bestätigungspflicht und Kostenzuordnung). */
    val approvable: Boolean get() = blockers.isEmpty()
}

/**
 * Stand der Bestätigungspflicht (WP-M4-28, DOC-003): `unconfirmedFields` = Pflichtfelder ohne menschliche Bestätigung oder Korrektur sowie markierte OCR-Vorschläge ohne Entscheidung (Feldnamen aus
 * [ExpenseFields]); `sumMismatch` = bestätigte Werte mit Netto + Steuer ≠ Brutto. Nur ein Beleg ohne beides ist freigabefähig.
 */
data class ReviewState(val unconfirmedFields: List<String>, val sumMismatch: Boolean) {
    val ready: Boolean get() = unconfirmedFields.isEmpty() && !sumMismatch
    companion object { val NONE = ReviewState(emptyList(), false) }
}

/** Bearbeitete Ansicht (z. B. zugeschnittenes Foto) als eigenes Objekt; sie ersetzt nie das Original. */
class EditedView(val mime: String, val bytes: ByteArray)

/**
 * Korrektur eines Belegs (WP-M4-23): `fields` setzt Werte (muss vom aktuellen Wert abweichen), `confirm` bestätigt einen OCR-Vorschlag unverändert. Mindestens eines von beiden oder eine bearbeitete Ansicht (WP-M4-25), der Grund ist Pflicht.
 * Eine Korrektur erzeugt genau eine neue Revision.
 */
data class CorrectExpense(val reason: String, val fields: Map<String, Any?> = emptyMap(), val confirm: Set<String> = emptySet(), val editedView: EditedView? = null)

/** Die bearbeitete Ansicht eines Belegs (jüngste Revision mit eigener Ansicht), unabhängig vom Original; `sha256` = Hash der Ansicht. */
class EditedViewContent(val revision: Long, val mime: String, val bytes: ByteArray, val sha256: String)

/** Zeile der Arbeitsliste (WP-M4-38): Kopfdaten und Feldwerte des Belegs, ohne Kostenobjekte und Schritte. `state` ist der Rohzustand (`STORED`, `IN_REVIEW`, ..., `POSTED`, in Quarantäne `QUARANTINED`). */
data class ExpenseSummary(
    val documentId: UUID, val legalEntityId: UUID?, val state: String, val kind: DocumentKind, val channel: SourceChannel, val receivedAt: Instant, val revision: Long, val merchant: String?,
    val amountMinor: Long?, val currency: String?, val documentDate: String?, val exportState: ExportState, val costEventId: UUID?,
)

/** Beleg lesen, Revisionen abrufen, korrigieren und das Original abrufen (WP-M4-22, WP-M4-23). */
interface Expenses {
    /**
     * Arbeitsliste (WP-M4-38): Belege des Mandanten, optional nach Rohzustand gefiltert, aufsteigend nach `(Eingangszeit, Kennung)` mit Cursor. Die Finanzrolle sieht alle Belege, andere Akteure nur die von ihnen
     * erfassten (wie [get]). Scope `receipts:read`.
     */
    fun list(ctx: RequestContext, state: String?, limit: Int, after: Pair<Instant, UUID>?): List<ExpenseSummary>

    /** Fremder Mandant und fremde Belege (nicht Erfasser, keine Finanzrolle): 404. */
    fun get(ctx: RequestContext, id: UUID): ExpenseView

    /** Alle Revisionen ab 1, aufsteigend. */
    fun revisions(ctx: RequestContext, id: UUID): List<RevisionView>

    /**
     * Korrigiert Felder (`expectedRevision` = `If-Match`: veraltet 412, bei parallelen Korrekturen derselben Revision gewinnt genau eine). Nur im Entwurfszustand `STORED`: ein
     * gebuchter oder freigegebener Beleg ist unveränderlich (409 `IMMUTABLE_RECORD`), ebenso ein Beleg in Quarantäne. Das Original wird nie berührt.
     */
    fun correct(ctx: RequestContext, id: UUID, expectedRevision: Long, cmd: CorrectExpense): ExpenseView

    /**
     * Die jüngste bearbeitete Ansicht (Zuschnitt, Drehung) als eigenes Objekt; fehlt sie, 404. Das Original ist davon unabhängig ([original]) und bleibt byteweise unverändert (WP-M4-25).
     * Anlegen: [correct] mit [CorrectExpense.editedView] (eigene Revision, ohne Feldänderung möglich).
     */
    fun editedView(ctx: RequestContext, id: UUID): EditedViewContent

    /** Das Original, byteweise unverändert; geprüft gegen den gespeicherten Hash (Verletzung: Vorfall und 409 `DOCUMENT_INTEGRITY_VIOLATION`). */
    fun original(ctx: RequestContext, id: UUID): ByteArray
}

// ---- Kanäle (WP-M4-22) ----

/** Datei eines serverseitigen Kanals (E-Mail-Anhang, Importdatei). */
class InboundAttachment(val filename: String, val mime: String?, val bytes: ByteArray)

/** Eingehende E-Mail an die Rechnungseingangsadresse des Mandanten (`invoice_email_inbound`). */
class InboundEmail(val messageId: String, val from: String, val to: String, val attachments: List<InboundAttachment>)

/** Datei aus dem Rechnungsimport. `sourceRef` (z. B. Importlauf und Dateiname) dedupliziert Wiederholungen; ohne Angabe gibt es keine Deduplikation. */
class ImportFile(val legalEntityId: UUID, val filename: String, val mime: String?, val bytes: ByteArray, val kind: DocumentKind = DocumentKind.INVOICE, val sourceRef: String? = null)

enum class IntakeRejection { NO_ATTACHMENT, NOT_CONFIGURED, WRONG_RECIPIENT, INVALID_FILE, UNSUPPORTED_TYPE, TOO_LARGE }

sealed interface IntakeOutcome {
    val filename: String
    /** Beleg angelegt (Zustand je nach Prüfung `STORED` oder `QUARANTINED`). */
    data class Created(override val filename: String, val document: StoredDocumentView) : IntakeOutcome
    /** Dieselbe Quelle ist schon bekannt (zweite Zustellung): kein weiterer Beleg; `document` fehlt, solange die erste Verarbeitung noch läuft. */
    data class Duplicate(override val filename: String, val document: StoredDocumentView?) : IntakeOutcome
    data class Rejected(override val filename: String, val reason: IntakeRejection) : IntakeOutcome
}

data class EmailIntakeResult(val messageId: String, val rejection: IntakeRejection?, val senderVerified: Boolean, val outcomes: List<IntakeOutcome>)

/**
 * Serverseitige Eingangskanäle (E-Mail-Eingang, Rechnungsimport). Beide rufen denselben Anlagebefehl wie die Web- und App-Kanäle ([Uploads]) und durchlaufen Ticket und Quarantäne; kein Kanal
 * umgeht die Prüfung. Der Aufrufer (Adapter) liefert einen Kontext mit Scope `receipts:write` des Mandanten, dem die Nachricht gehört.
 */
interface ReceiptIntake {
    /**
     * Legt je Anhang einen Beleg an. Ohne Anhang, ohne konfigurierte Eingangsadresse oder an eine andere Adresse: abgewiesen, nichts angelegt. Ist die Absenderdomäne nicht verifiziert, entsteht ein Beleg, der
     * nicht freigabefähig ist ([ReleaseBlocker.UNVERIFIED_SENDER]). Dieselbe Nachricht ein zweites Mal ergibt [IntakeOutcome.Duplicate].
     */
    fun receiveEmail(ctx: RequestContext, mail: InboundEmail): EmailIntakeResult

    /** Rechnungsimport einer Datei; defekte oder nicht unterstützte Dateien werden abgewiesen. */
    fun importFile(ctx: RequestContext, file: ImportFile): IntakeOutcome
}

// ---- Einstellungen ----

data class DocumentSettingsView(
    val receiptOcrEnabled: Boolean, val invoiceEmailInbound: String?, val inboundLegalEntityId: UUID?, val verifiedSenderDomains: Set<String>, val revision: Long,
    /** `receipt_mandatory_fields` (WP-M4-28): Felder, die vor der Freigabe bestätigt oder korrigiert sein müssen; mindestens [ExpenseFields.PROTECTED_MANDATORY]. */
    val receiptMandatoryFields: Set<String> = ExpenseFields.PROTECTED_MANDATORY,
)

data class UpdateDocumentSettings(
    val receiptOcrEnabled: Boolean, val invoiceEmailInbound: String?, val inboundLegalEntityId: UUID?, val verifiedSenderDomains: Set<String>,
    /** `null` = unverändert lassen. Muss die geschützten Felder enthalten (sonst 422). */
    val receiptMandatoryFields: Set<String>? = null,
)

/** Mandanteneinstellungen der Belege (`receipt_ocr_enabled`, `invoice_email_inbound`). Ohne gespeicherte Einstellung: OCR an, kein E-Mail-Eingang. Ändern darf die Finanzrolle. */
interface DocumentSettings {
    fun get(ctx: RequestContext): DocumentSettingsView
    fun update(ctx: RequestContext, cmd: UpdateDocumentSettings): DocumentSettingsView
}

// ---- Integrität (WP-M4-24) ----

enum class IntegrityTrigger { DELIVERY, APPROVAL, SCHEDULED, MANUAL }
enum class IntegrityFailure { HASH_MISMATCH, OBJECT_MISSING }

data class IntegrityResult(val documentId: UUID, val state: IntegrityState, val failure: IntegrityFailure?, val incidentId: UUID?)
data class SweepResult(val checked: Int, val violations: Int, val nextCursor: UUID?)

/** Bescheinigung, dass das Original bei der Freigabe unverändert war. Sie ersetzt nie die Prüfung im Posting ([IntegrityChecks.requirePostable]). */
data class ApprovalClearance(val documentId: UUID, val revision: Long, val checkedAt: Instant)

/**
 * Integritätsprüfung der Originale (WP-M4-24): SHA-256 des Objekts gegen den gespeicherten Hash. Eine Abweichung oder ein fehlendes Objekt setzt den Beleg endgültig auf
 * [IntegrityState.VIOLATED], schreibt einen unveränderlichen Vorfall, ein Sicherheitsereignis und ein Outbox-Ereignis. Die Prüfung liest das Objekt außerhalb einer Datenbanktransaktion.
 */
interface IntegrityChecks {
    /** Prüft ein Dokument im Mandanten des Kontexts (fremde Dokumente: 404). Nur `STORED`-Dokumente haben ein Original; Dokumente in Quarantäne bleiben unberührt (`UNCHECKED`). */
    fun check(ctx: RequestContext, documentId: UUID, trigger: IntegrityTrigger = IntegrityTrigger.MANUAL): IntegrityResult

    /**
     * Periodischer Prüfjob, fortsetzbar: prüft bis zu `limit` Belege im Mandanten des Kontexts in der Reihenfolge `(integrity_checked_at, id)` und liefert den Cursor für die Fortsetzung
     * (`null`, wenn alle geprüft sind). Bereits verletzte Belege werden übersprungen. Läuft je Mandant im Tenantkontext (kein Zugriff auf fremde Objekte).
     */
    fun sweep(ctx: RequestContext, limit: Int, after: UUID? = null): SweepResult

    /**
     * Vor der Freigabe (Kap. 05 §5.5): prüft den Originalhash **jetzt** und bescheinigt ihn. Ein verletzter Beleg wird mit 409 `DOCUMENT_INTEGRITY_VIOLATION` abgewiesen; `expectedRevision`
     * (veraltet: 412) bindet die Bescheinigung an den Stand des Belegs. Die Freigabeschritte selbst folgen mit WP-M4-34.
     */
    fun clearForApproval(ctx: RequestContext, documentId: UUID, expectedRevision: Long): ApprovalClearance

    /**
     * Sperre im Posting: in der **laufenden Transaktion des Postings** (`jdbc` im Mandantenkontext) wird die Beleg-Zeile gesperrt und der Integritätszustand gelesen. Ein verletzter Beleg wirft
     * 409 `DOCUMENT_INTEGRITY_VIOLATION`; ein parallel festgestellter Vorfall wird entweder vor dem Posting sichtbar oder wartet auf dessen Ende (kein Posting auf verletztem Original).
     */
    fun requirePostable(jdbc: org.springframework.jdbc.core.JdbcOperations, documentId: UUID)
}

// ---- Bestätigungspflicht (WP-M4-28) ----

/**
 * Voraussetzung der Freigabe: OCR-Ergebnisse sind unbestätigte Vorschläge (DOC-003). Freigabefähig ist ein Beleg erst, wenn alle Pflichtfelder (`receipt_mandatory_fields`) menschlich bestätigt oder
 * korrigiert sind, kein markierter Vorschlag (niedrige Konfidenz, Summenabweichung) unentschieden bleibt und die bestätigten Werte der Summenregel Netto + Steuer = Brutto genügen. Die Freigabeschritte
 * selbst (Rollentrennung, Vier-Augen, Buchung) folgen mit WP-M4-34 und rufen diese Prüfung in ihrer Transaktion.
 */
interface ReceiptReview {
    fun state(ctx: RequestContext, id: UUID): ReviewState

    /**
     * Prüft in eigener Transaktion unter Zeilensperre: Revision (`expectedRevision`, veraltet 412), Zustand `STORED`, Integrität (409) und Bestätigungspflicht (422 `UNCONFIRMED_FIELDS` mit den
     * offenen Feldern als Verletzungen); davor wird der Originalhash geprüft ([IntegrityChecks.clearForApproval]). Es wird nichts gebucht und nichts verändert.
     */
    fun requireApprovable(ctx: RequestContext, id: UUID, expectedRevision: Long): ApprovalClearance

    /**
     * Wie oben, aber in der **laufenden Transaktion** der Freigabe (`jdbc` im Mandantenkontext): die Belegzeile wird `FOR UPDATE` gesperrt, daher kann zwischen Prüfung und Zustandswechsel keine
     * Feldkorrektur dazwischentreten; eine Korrektur auf der alten Revision scheitert mit 412 bzw. nach dem Zustandswechsel mit 409 `IMMUTABLE_RECORD`.
     */
    fun requireApprovable(jdbc: org.springframework.jdbc.core.JdbcOperations, ctx: RequestContext, id: UUID, expectedRevision: Long)
}

/**
 * Bündel der Belegbausteine (WP-M4-21 bis -28); die Ports (Objektspeicher, Prüfer, OCR) liefert die Anwendung.
 */
class DocumentServices(
    val uploads: Uploads, val expenses: Expenses, val intake: ReceiptIntake, val settings: DocumentSettings, val integrity: IntegrityChecks, val extraction: ReceiptExtraction,
    val review: ReceiptReview,
    val costs: CostAssignments, val costEvents: CostEvents, val accounting: AccountingMappings,
    val allocations: CostAllocations, val splits: ExpenseSplits, val periods: FiscalPeriods, val corrections: CostEventCorrections, val exports: LedgerExports,
    val fuel: FuelReceipts, val policies: FinancePolicies, val plausibility: FuelPlausibility,
    val duplicates: DuplicateReview, val approvals: ExpenseApprovals,
    val classifications: CostClassifications, val manualCosts: ManualCostEvents, val vehicleCosts: VehicleCosts,
)

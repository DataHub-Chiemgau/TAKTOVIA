package de.taktovia.documentsfinance.api

import de.taktovia.documentsfinance.internal.UploadServicesFactory
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.audit.SecurityEventPort
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.storage.TenantObjectStore
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Zweck eines Uploads (Zusage an M3: [DAMAGE_PHOTO] für Fotoevidenz der Schadenmeldung, AT-33). */
enum class UploadPurpose(val prefix: String) { RECEIPT("receipts"), DAMAGE_PHOTO("evidence") }

/** Zustand des Dokuments: in Quarantäne (nicht ausgeliefert, nicht an OCR), geprüft und als Original gespeichert, oder aus der Quarantäne endgültig abgelehnt. */
enum class DocumentState { QUARANTINED, STORED, QUARANTINE_REJECTED }

enum class TicketState { ISSUED, COMPLETING, CONSUMED, REJECTED }

/**
 * Grenzen des Uploads. Die Maximalgröße und die erlaubten Typen sind im Pflichtenheft nicht beziffert (Offener Punkt 13): **Planungsdefaults, nicht freigegeben**
 * (10 MiB; JPEG, PNG, PDF; Ticket 15 Minuten gültig).
 */
data class UploadPolicy(
    val maxBytes: Long = 10L * 1024 * 1024,
    val allowedMime: Set<String> = setOf("image/jpeg", "image/png", "application/pdf"),
    val ticketTtl: Duration = Duration.ofMinutes(15),
) {
    init { require(maxBytes > 0 && ticketTtl > Duration.ZERO && allowedMime.isNotEmpty()) }
}

/** Anforderung eines Uploadtickets: Größe, MIME-Typ und erwarteter SHA-256 (hexadezimal, klein) der Datei. */
data class RequestUploadTicket(
    val legalEntityId: UUID, val purpose: UploadPurpose, val mime: String, val sizeBytes: Long, val sha256: String,
    /** Herkunftskanal (WP-M4-22): über die HTTP-Schnittstelle nur [SourceChannel.DRIVER_APP] und [SourceChannel.DISPATCH_WEB]; E-Mail und Import laufen über [ReceiptIntake]. */
    val channel: SourceChannel = SourceChannel.DISPATCH_WEB, val kind: DocumentKind = DocumentKind.RECEIPT, val caseRef: CaseRef? = null,
    /**
     * Stabile Kennung des Entwurfs, vom Gerät **vor** dem Upload erzeugt (WP-M4-26, DOC-013): macht die Anlage über [Uploads.registerReceipt] idempotent (Unique `(tenant_id, client_uuid)`).
     * Ohne Angabe gilt keine Idempotenz über Wiederholungen (Web-Upload, serverseitige Kanäle). `capturedAt` = Erfassungszeit am Gerät, getrennt von der Eingangszeit am Server.
     */
    val clientUuid: UUID? = null, val capturedAt: Instant? = null,
)

/** Ziel des direkten Uploads in den Objektspeicher (z. B. vorsignierte MinIO-URL); `null`, solange kein S3/MinIO-Adapter angebunden ist (nicht ausgeführt, WP-M0-08). */
data class UploadTarget(val method: String, val url: String, val headers: Map<String, String> = emptyMap())

fun interface UploadTargetSigner { fun target(tenantPath: String, expiresAt: Instant, mime: String, sizeBytes: Long): UploadTarget? }

data class UploadTicketView(
    val ticketId: UUID, val purpose: UploadPurpose, val mime: String, val sizeBytes: Long, val sha256: String, val maxBytes: Long, val expiresAt: Instant,
    val state: TicketState, val revision: Long, val uploadKey: String, val target: UploadTarget?,
    val clientUuid: UUID? = null, val capturedAt: Instant? = null,
    /** Gesetzt, sobald aus dem Ticket ein Beleg entstanden ist (Zustand [TicketState.CONSUMED]): der Entwurf ist dann bereits synchronisiert. */
    val documentId: UUID? = null,
)

data class StoredDocumentView(
    val documentId: UUID, val ticketId: UUID, val purpose: UploadPurpose, val state: DocumentState, val mime: String, val sizeBytes: Long, val sha256: String,
    val receivedAt: Instant, val scannedAt: Instant?, val revision: Long,
    val channel: SourceChannel = SourceChannel.DISPATCH_WEB, val kind: DocumentKind = DocumentKind.RECEIPT, val caseRef: CaseRef? = null,
    /** Nur E-Mail-Eingang: Absenderdomäne verifiziert? Sonst `null`. */
    val senderVerified: Boolean? = null, val integrity: IntegrityState = IntegrityState.UNCHECKED, val legalEntityId: UUID? = null,
    /** Rohzustand des Belegs; spätere Pakete (Freigabe, Buchung) führen weitere Zustände nach `STORED`, [state] zeigt dafür `STORED`. */
    val workflowState: String = state.name,
    /** Stabile Kennung des Entwurfs (Geräte-UUID bzw. bei Kanälen ohne Gerät die Beleg-ID) und Erfassungszeit am Gerät (nur Fahrer-App), getrennt von [receivedAt]. */
    val clientUuid: UUID? = null, val capturedAt: Instant? = null,
) {
    /** Freigabehindernisse, die aus dem Dokument selbst folgen (weitere Pakete ergänzen Pflichtfelder, Kostenzuordnung, Dublette). */
    val blockers: List<ReleaseBlocker> get() = buildList {
        if (state != DocumentState.STORED) add(ReleaseBlocker.NOT_STORED)
        if (senderVerified == false) add(ReleaseBlocker.UNVERIFIED_SENDER)
        if (integrity == IntegrityState.VIOLATED) add(ReleaseBlocker.INTEGRITY_VIOLATION)
    }
    val approvable: Boolean get() = blockers.isEmpty()
}

/** Ergebnis der Prüfung; [Unavailable] lässt das Dokument in Quarantäne (fail-closed). */
sealed interface ScanResult {
    data object Clean : ScanResult
    data class Infected(val signature: String) : ScanResult
    data class Unavailable(val reason: String) : ScanResult
}

/** Malware-Prüfung (ClamAV im Betrieb, [ClamdScanner]); die Umsetzung entscheidet nie „sauber“ bei Fehlern. */
interface MalwareScanner {
    val engine: String
    fun scan(bytes: ByteArray): ScanResult
}

/**
 * Uploadticket, Quarantäne und Malware-Prüfung (WP-M4-21). Eine Datei gelangt nur über ein autorisiertes, einmal verbrauchbares Ticket (Größe, MIME, erwarteter Hash,
 * Ablauf) in die Quarantäne; [complete] prüft Größe, Hash und Typ **am gespeicherten Objekt** (serverseitig berechneter Hash), legt das Dokument in Quarantäne an
 * und startet die Prüfung. Erst nach bestandener Prüfung entsteht das Original im schreibgeschützten Originalbestand ([DocumentState.STORED]); eine infizierte Datei bleibt in
 * Quarantäne, wird weder ausgeliefert noch weitergegeben und löst das Sicherheitsereignis `security.document.malware_detected` aus. Objektschlüssel tragen das Mandantenpräfix,
 * fremde Mandanten erreichen weder Ticket noch Dokument (404). Andere Module nutzen dasselbe Ticket mit [UploadPurpose] (Zusage an M3).
 */
interface Uploads {
    /** Stellt ein Ticket aus; Rechtsträger muss zum Mandanten gehören (sonst 404), Größe/Typ außerhalb der [UploadPolicy] ergeben 422. */
    fun requestTicket(ctx: RequestContext, cmd: RequestUploadTicket): UploadTicketView

    /**
     * Schließt den Upload ab (`expectedRevision` = `If-Match` des Tickets, veraltet: 412). Ein abgelaufenes oder bereits verbrauchtes Ticket ist nicht wiederverwendbar (409);
     * bei parallelem Abschluss gewinnt genau einer. Abweichende Größe, Hash oder Typ: 422, das Ticket ist danach verbraucht (neues Ticket anfordern).
     */
    fun complete(ctx: RequestContext, ticketId: UUID, expectedRevision: Long): StoredDocumentView

    /**
     * Idempotente Anlage eines Belegs über die Client-UUID (WP-M4-26, DOC-013; auch der Online-Weg der Fahrer-App, WP-M4-25). `cmd.clientUuid` ist Pflicht. Ergebnis:
     * kein Beleg und kein offenes Ticket zur UUID: neues Ticket ([ReceiptRegistration.created] = true, 201); ein noch gültiges Ticket derselben UUID und desselben Hashs: dasselbe Ticket
     * (Wiederaufnahme eines abgebrochenen Uploads, 200); Beleg schon angelegt: dessen Ticket mit `documentId` (200). Dieselbe UUID mit abweichendem Hash: 409 ohne Überschreiben
     * (auch wenn der Beleg erst entsteht). Die Ausstellung ist je `(Mandant, UUID)` serialisiert; ein Zuordnungsziel (Fahrzeug/Schicht) muss dem Fahrer der Fahrer-App zugewiesen sein (403, sonst 404).
     */
    fun registerReceipt(ctx: RequestContext, cmd: RequestUploadTicket): ReceiptRegistration

    /**
     * Wie [complete], aber idempotent für Tickets mit Client-UUID: ein bereits verbrauchtes Ticket liefert den vorhandenen Beleg ([CompletedReceipt.created] = false), ein gerade abgeschlossenes
     * Ticket (paralleler Lauf) 409 `CONFLICT` zum Wiederholen. Übertragungsfehler (Hash/Größe weichen ab): 422, das Ticket ist verbraucht, mit [registerReceipt] folgt ein neues Ticket.
     */
    fun completeReceipt(ctx: RequestContext, ticketId: UUID, expectedRevision: Long): CompletedReceipt

    fun document(ctx: RequestContext, documentId: UUID): StoredDocumentView

    /** Wiederholt die Prüfung eines in Quarantäne verbliebenen Dokuments (z. B. nach Ausfall des Scanners). Andere Zustände bleiben unverändert. */
    fun rescan(ctx: RequestContext, documentId: UUID): StoredDocumentView

    /** Liefert das Original, **nur** wenn die Prüfung bestanden ist; Dokumente in Quarantäne oder abgelehnte sind nicht auffindbar (404). Nur für den Besitzer und die Finanzrolle. Weicht der Hash ab, entsteht ein Integritätsvorfall (WP-M4-24). */
    fun openOriginal(ctx: RequestContext, documentId: UUID): ByteArray

    /**
     * Gemeinsamer Anlagebefehl für serverseitige Kanäle (E-Mail-Eingang, Rechnungsimport; WP-M4-22): legt das Ticket an, schreibt die Bytes in die Quarantäne und schließt ab, mit
     * serverseitig berechnetem Hash und demselben Ablauf wie der Upload per Ticket. Der Kontext darf ein Dienstkonto sein (Scope `receipts:write`). Dieselbe `sourceRef` im selben Kanal
     * erzeugt nie einen zweiten Beleg ([IngestResult.Duplicate]).
     */
    fun ingest(ctx: RequestContext, cmd: IngestFile): IngestResult
}

class ReceiptRegistration(val ticket: UploadTicketView, val created: Boolean)
class CompletedReceipt(val document: StoredDocumentView, val created: Boolean)

/** Datei eines serverseitigen Kanals. `mime` (falls angegeben) muss zum tatsächlichen Typ passen; der Typ wird an den Bytes erkannt. */
class IngestFile(
    val legalEntityId: UUID, val channel: SourceChannel, val kind: DocumentKind, val bytes: ByteArray, val mime: String? = null, val sourceRef: String? = null,
    val senderVerified: Boolean? = null, val caseRef: CaseRef? = null, val purpose: UploadPurpose = UploadPurpose.RECEIPT,
)

sealed interface IngestResult {
    data class Created(val document: StoredDocumentView) : IngestResult
    /** Die Quelle ist schon verarbeitet oder wird gerade verarbeitet; `document` fehlt, solange die erste Verarbeitung noch nicht abgeschlossen ist. */
    data class Duplicate(val document: StoredDocumentView?) : IngestResult
}

/** Fabrik des Upload-Bausteins; die Ports (Objektspeicher, Scanner) liefert die Anwendung. */
object DocumentUploads {
    fun create(
        ctxTx: ContextTransactions, audit: AuditPort, security: SecurityEventPort, outbox: Outbox, store: TenantObjectStore, scanner: MalwareScanner,
        policy: UploadPolicy = UploadPolicy(), signer: UploadTargetSigner? = null, clock: Clock = Clock.systemUTC(),
    ): Uploads = UploadServicesFactory.create(ctxTx, audit, security, outbox, store, scanner, policy, signer, clock)

    /** Alle Belegbausteine (WP-M4-21 bis -27). `recognizer` = OCR-Engine hinter der providerunabhängigen Schnittstelle; ohne Engine lehnt die Extraktion mit 503 ab. */
    fun services(
        ctxTx: ContextTransactions, audit: AuditPort, security: SecurityEventPort, outbox: Outbox, store: TenantObjectStore, scanner: MalwareScanner,
        policy: UploadPolicy = UploadPolicy(), signer: UploadTargetSigner? = null, clock: Clock = Clock.systemUTC(),
        recognizer: ReceiptRecognizer? = null, extraction: ExtractionPolicy = ExtractionPolicy(), ledger: LedgerExportConfig = LedgerExportConfig(),
    ): DocumentServices = UploadServicesFactory.services(ctxTx, audit, security, outbox, store, scanner, policy, signer, clock, recognizer, extraction, ledger)
}

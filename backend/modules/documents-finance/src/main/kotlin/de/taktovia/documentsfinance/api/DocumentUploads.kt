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
data class RequestUploadTicket(val legalEntityId: UUID, val purpose: UploadPurpose, val mime: String, val sizeBytes: Long, val sha256: String)

/** Ziel des direkten Uploads in den Objektspeicher (z. B. vorsignierte MinIO-URL); `null`, solange kein S3/MinIO-Adapter angebunden ist (nicht ausgeführt, WP-M0-08). */
data class UploadTarget(val method: String, val url: String, val headers: Map<String, String> = emptyMap())

fun interface UploadTargetSigner { fun target(tenantPath: String, expiresAt: Instant, mime: String, sizeBytes: Long): UploadTarget? }

data class UploadTicketView(
    val ticketId: UUID, val purpose: UploadPurpose, val mime: String, val sizeBytes: Long, val sha256: String, val maxBytes: Long, val expiresAt: Instant,
    val state: TicketState, val revision: Long, val uploadKey: String, val target: UploadTarget?,
)

data class StoredDocumentView(
    val documentId: UUID, val ticketId: UUID, val purpose: UploadPurpose, val state: DocumentState, val mime: String, val sizeBytes: Long, val sha256: String,
    val receivedAt: Instant, val scannedAt: Instant?, val revision: Long,
)

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

    fun document(ctx: RequestContext, documentId: UUID): StoredDocumentView

    /** Wiederholt die Prüfung eines in Quarantäne verbliebenen Dokuments (z. B. nach Ausfall des Scanners). Andere Zustände bleiben unverändert. */
    fun rescan(ctx: RequestContext, documentId: UUID): StoredDocumentView

    /** Liefert das Original, **nur** wenn die Prüfung bestanden ist; Dokumente in Quarantäne oder abgelehnte sind nicht auffindbar (404). Nur für den Besitzer und die Finanzrolle. */
    fun openOriginal(ctx: RequestContext, documentId: UUID): ByteArray
}

/** Fabrik des Upload-Bausteins; die Ports (Objektspeicher, Scanner) liefert die Anwendung. */
object DocumentUploads {
    fun create(
        ctxTx: ContextTransactions, audit: AuditPort, security: SecurityEventPort, outbox: Outbox, store: TenantObjectStore, scanner: MalwareScanner,
        policy: UploadPolicy = UploadPolicy(), signer: UploadTargetSigner? = null, clock: Clock = Clock.systemUTC(),
    ): Uploads = UploadServicesFactory.create(ctxTx, audit, security, outbox, store, scanner, policy, signer, clock)
}

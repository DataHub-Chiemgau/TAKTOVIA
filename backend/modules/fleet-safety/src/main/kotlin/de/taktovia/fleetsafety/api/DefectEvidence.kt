package de.taktovia.fleetsafety.api

import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.platform.storage.InMemoryObjectStore
import de.taktovia.platform.storage.SignedUrl
import de.taktovia.platform.storage.SignedUrls
import de.taktovia.platform.storage.TenantObjectStore
import de.taktovia.platform.tenant.TenantId
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * Grenzen der Fotoevidenz. Das Pflichtenheft beziffert sie nicht (OP-13): **Planungsdefaults, nicht freigegeben** (10 MiB, JPEG und PNG, Ticket 15 Minuten gültig, Download-Link
 * höchstens 60 s nach Kap. 02 §4.3). Die Datenbank erzwingt dieselben Obergrenzen (CHECK) als zweite Schicht.
 */
data class EvidencePolicy(
    val maxBytes: Long = 10L * 1024 * 1024,
    val allowedMime: Set<String> = setOf("image/jpeg", "image/png"),
    val ticketTtl: Duration = Duration.ofMinutes(15),
) {
    init { require(maxBytes in 1..10L * 1024 * 1024 && allowedMime.isNotEmpty() && ticketTtl > Duration.ZERO) }
}

/**
 * Vertraulichkeitsstufe eines Dokuments der Schadenakte (WP-M3-50, DMG-011). [STANDARD]: jede Rolle mit Zugriff auf die Akte. [CONFIDENTIAL]: nur Werkstatt und Betriebsleitung (Prüfer) und
 * der Urheber; für alle anderen ist das Dokument nicht vorhanden (404, kein Hinweis, auch kein Zähler). Planungsannahme der Rollenmatrix, nicht freigegeben.
 */
object EvidenceConfidentiality {
    const val STANDARD = "STANDARD"
    const val CONFIDENTIAL = "CONFIDENTIAL"
    val ALL: Set<String> = linkedSetOf(STANDARD, CONFIDENTIAL)
}

/** Zustand des Fotos auf dem Weg zur Akte: hochgeladen nur nach Ticket, in Prüfung (Quarantäne), freigegeben (sichtbar, abrufbar) oder abgewiesen (endgültig). */
enum class EvidenceState { ISSUED, IN_REVIEW, RELEASED, REJECTED }

/**
 * Anforderung eines Uploadtickets: Größe, MIME-Typ, SHA-256 der Datei (Wire: base64url ohne Auffüllung, [sha256Hex] intern in Hex; die Kartendatenerkennung SEC-008 würde lange
 * Hexfolgen gelegentlich fehldeuten) und Aufnahmezeit laut Gerät. EXIF-Angaben entfernt der Server (OP-31).
 */
data class RequestEvidenceTicket(val mime: String, val sizeBytes: Long, val sha256Hex: String, val capturedAt: Instant, val confidentiality: String = EvidenceConfidentiality.STANDARD) {
    companion object {
        private val FIELDS = setOf("mime", "size_bytes", "sha256", "captured_at", "confidentiality")

        fun parse(raw: Map<String, Any?>, policy: EvidencePolicy = EvidencePolicy()): RequestEvidenceTicket {
            val v = Violations()
            v.unknown(raw, FIELDS, "")
            val mime = v.code(raw, "mime", policy.allowedMime, true)
            val size = v.int(raw, "size_bytes", "/size_bytes", 1, Int.MAX_VALUE)
            if (size != null && size > policy.maxBytes) v.add("/size_bytes", ViolationCode.ABOVE_MAXIMUM)
            val sha = (raw["sha256"] as? String)?.let { b -> runCatching { Base64.getUrlDecoder().decode(b) }.getOrNull()?.takeIf { it.size == 32 }?.joinToString("") { "%02x".format(it) } }
            if (sha == null) v.add("/sha256", if (raw["sha256"] == null) ViolationCode.REQUIRED else ViolationCode.INVALID_FORMAT)
            val captured = v.instant(raw, "captured_at", true)
            val confidentiality = v.code(raw, "confidentiality", EvidenceConfidentiality.ALL, false) ?: EvidenceConfidentiality.STANDARD
            v.throwIfAny()
            return RequestEvidenceTicket(mime!!, size!!.toLong(), sha!!, captured!!, confidentiality)
        }
    }
}

data class EvidenceTicketView(
    val id: UUID, val state: EvidenceState, val mime: String, val sizeBytes: Long, val sha256Hex: String, val capturedAt: Instant, val maxBytes: Long, val expiresAt: Instant,
    val uploadKey: String, val rejectReason: String?, val defectId: UUID?, val revision: Long, val confidentiality: String = EvidenceConfidentiality.STANDARD,
) {
    /** Wire-Form des Hashs. */
    val sha256Wire: String get() = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { sha256Hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() })
}

/** Ergebnis der Medienprüfung; [Unavailable] lässt das Foto in Quarantäne (fail-closed, nie „sauber“ bei Fehlern). */
sealed interface MediaVerdict {
    /** Sauber; [bytes] ist die zu speichernde (neu kodierte, von Metadaten befreite) Fassung. */
    class Clean(val bytes: ByteArray) : MediaVerdict
    data class Infected(val signature: String) : MediaVerdict
    data class Invalid(val reason: String) : MediaVerdict
    data class Unavailable(val reason: String) : MediaVerdict
}

/** Malware- und Medienprüfung (ClamAV im Betrieb über den Baustein aus WP-M4-21; Verdrahtung in der Anwendung). Die Umsetzung entscheidet nie „sauber“ bei Fehlern. */
interface MediaInspector {
    val engine: String
    fun inspect(bytes: ByteArray, mime: String): MediaVerdict
}

/** Foto an der Akte, wie es ein Prüfer sieht. */
data class EvidenceDetail(
    val id: UUID, val defectId: UUID, val mime: String?, val sizeBytes: Long?, val sha256Hex: String, val capturedAt: Instant, val receivedAt: Instant, val addedInVersion: Int,
    val replacesId: UUID?, val createdBy: String, val confidentiality: String = EvidenceConfidentiality.STANDARD,
)

/** Ergebnis der Integritätsprüfung Objekt ↔ gespeicherter Hash. */
data class IntegrityReport(val checked: Int, val mismatched: List<UUID>, val missing: List<UUID>) { val ok get() = mismatched.isEmpty() && missing.isEmpty() }

/**
 * Uploadticket, Quarantäne, Medienprüfung und signierter Abruf der Schadenfotos (WP-M3-44, LH-6.22-DMG-004). Eine Datei gelangt nur über ein Ticket (Größe, MIME, erwarteter Hash,
 * Ablauf) in die Quarantäne; [complete] prüft Größe, Hash und tatsächlichen Typ **am gespeicherten Objekt** und führt die Medienprüfung aus (außerhalb jeder Datenbanktransaktion).
 * Erst nach bestandener Prüfung entsteht die unveränderliche Evidenz an der Akte; bis dahin zeigt die Akte nur „in Prüfung“, das Foto ist weder sichtbar noch abrufbar.
 * Objekte liegen unter dem Mandantenpräfix; fremde Mandanten erreichen weder Ticket noch Foto (404). Ein Link ist höchstens 60 s gültig und gilt nur für den Mandanten, der ihn erhielt.
 */
interface DefectEvidenceUploads {
    /** Stellt ein Ticket aus (Rollen `driver`, `dispatcher`, `workshop`); dieselbe Datei desselben Urhebers liefert das bestehende Ticket (Wiederholung nach Zeitüberschreitung). */
    fun requestTicket(ctx: RequestContext, cmd: RequestEvidenceTicket): EvidenceTicketView

    fun get(ctx: RequestContext, ticketId: UUID): EvidenceTicketView

    /**
     * Schließt den Upload ab (`expectedRevision` = `If-Match`, veraltet: 412). Abweichende Größe, Hash oder Typ, fehlendes Objekt oder abgelaufenes Ticket: 422, das Ticket ist abgewiesen
     * und wird nie an eine Akte gehängt. Medienprüfung gefunden: Foto freigegeben; Prüfer nicht verfügbar: bleibt in Prüfung ([reviewPending] wiederholt); Schadsoftware oder
     * ungültiges Bild: 422, abgewiesen (Audit `fleet.defect_evidence.rejected`).
     */
    fun complete(ctx: RequestContext, ticketId: UUID, expectedRevision: Long): EvidenceTicketView

    /** Wiederholt die Prüfung der in Quarantäne verbliebenen Tickets des Mandanten (z. B. nach Ausfall des Prüfers); liefert die Anzahl neu entschiedener Tickets. Aufruf durch den Prüfauftrag je Mandant. */
    fun reviewPending(tenant: TenantId, limit: Int = 100): Int

    /** Prüft, ob jedes freigegebene Objekt des Mandanten noch zum gespeicherten SHA-256 passt; Abweichung und fehlendes Objekt lösen `security.defect_evidence.integrity_violation` aus. */
    fun verifyIntegrity(tenant: TenantId, limit: Int = 1000): IntegrityReport

    /** Freigegebene Fotos einer Akte (Metadaten); Fotos in Prüfung fehlen hier. Fahrer: nur eigene Akten. */
    fun listForDefect(ctx: RequestContext, defectId: UUID): List<EvidenceDetail>

    /** Signierter Abruf: erst nach Objekt- und Zweckprüfung (freigegebene Evidenz einer sichtbaren Akte), Gültigkeit höchstens 60 s. */
    fun downloadLink(ctx: RequestContext, defectId: UUID, evidenceId: UUID): SignedUrl

    /** Liefert den Inhalt zu einem signierten Link; ungültige, abgelaufene oder mandantenfremde Links sind 404 (nicht unterscheidbar von „nicht vorhanden“). */
    fun openLinked(ctx: RequestContext, url: String): LinkedObject
}

class LinkedObject(val bytes: ByteArray, val mime: String)

/** Verdrahtung der Fotoevidenz: mandantengetrennter Objektspeicher (WP-M1-11), Medienprüfung, Schlüssel der signierten Links (ohne: kein Abruf) und Grenzen. */
class DefectEvidenceWiring(
    val store: TenantObjectStore = InMemoryObjectStore(), val inspector: MediaInspector = MediaInspectors.basic(), val urls: SignedUrls? = null, val policy: EvidencePolicy = EvidencePolicy(),
)

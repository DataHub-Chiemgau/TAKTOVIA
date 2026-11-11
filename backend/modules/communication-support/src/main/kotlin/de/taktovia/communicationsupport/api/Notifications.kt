package de.taktovia.communicationsupport.api

import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.outbox.EventEnvelope
import de.taktovia.platform.outbox.InboxOutcome
import de.taktovia.platform.tenant.TenantId
import org.springframework.jdbc.core.JdbcOperations
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Asynchroner Nachrichtenversand mit Zustellversuchen (WP-M2-32, LH-6.12-COM-003). Vertrag: `docs/umsetzungsplan/vertraege/LH-6.12-COM-003.md`,
 * Zustandsautomat `vertraege/zustaende/nachricht.md`. Der Auftrag entsteht in der Transaktion des Auslösers (oder über die Inbox aus einem Ereignis), versendet
 * wird ausschließlich vom [NotificationDispatcher] außerhalb jeder Fachtransaktion. Retry und Zustellstatus ändern nie eine Buchungszusage (Kap. 01 §1.3).
 */
enum class NotificationChannel { PUSH, SMS, EMAIL }

/** Zustände des Auftrags (Automat `nachricht.md`); Endzustände sind [DELIVERED], [FAILED], [SUPPRESSED]. */
enum class NotificationState { QUEUED, SENDING, RETRY_WAIT, UNKNOWN, DELIVERED, FAILED, SUPPRESSED;
    val terminal get() = this == DELIVERED || this == FAILED || this == SUPPRESSED
}

/** Ausgang eines Zustellversuchs; `TIMED_OUT` heißt „Ergebnis unbestimmt“ (nie als Fehler oder Erfolg gewertet). */
enum class AttemptOutcome { ACCEPTED, REJECTED, TIMED_OUT }

/** Dokumentierter Kanalfehler am Auftrag (kein stiller Verlust). */
enum class FailureReason { INVALID_CONTACT, PROVIDER_ERROR, RETRIES_EXHAUSTED, TEMPLATE_UNAVAILABLE, NO_VERIFIED_CONTACT }

/**
 * Auftrag zum Versand einer Nachricht. [eventId] ist das auslösende Ereignis (oder der Schlüssel des aufrufenden Moduls) und bildet mit Empfänger und Kanal
 * den fachlichen Schlüssel: höchstens eine Nachricht je Ereignis, Empfänger und Kanal. [recipientRef] ist `passenger_private.id`, nie ein Klartextkontakt.
 * [params] sind typisierte Platzhalterwerte (Katalog je Ereignistyp, siehe [NotificationEventTypes]); fehlende Werte sind erst beim Rendern ein Kanalfehler.
 */
class NotificationRequest(
    val eventId: UUID, val recipientRef: UUID, val channel: NotificationChannel, val eventType: String, val serviceId: UUID? = null,
    val params: Map<String, String> = emptyMap(),
) {
    override fun toString() = "NotificationRequest(eventType=$eventType, channel=$channel)"   // nie Werte
}

data class EnqueueResult(val notificationId: UUID, val created: Boolean)

/** Sicht auf den Auftrag und seinen Zustellstatus: weder Inhalt noch Kontaktdaten, nur der Name der Vorlage. */
data class NotificationView(
    val id: UUID, val eventId: UUID, val channel: NotificationChannel, val template: String, val state: NotificationState, val attempts: Int,
    val lastFailure: FailureReason?, val createdAt: Instant, val revision: Long,
)

interface NotificationService {
    /**
     * Interne Fachoperation „Betriebsnachricht senden“ für andere Module (u. a. SMS-Zustellkanal der Kontaktverifikation): legt den Auftrag **in der laufenden
     * Mandantentransaktion** des Aufrufers an (`jdbc`), versendet wird später und asynchron. Ein zweiter Aufruf mit demselben (Ereignis, Empfänger, Kanal) legt keinen
     * zweiten Auftrag an (`created = false`, derselbe `notificationId`). Unbekannter Ereignistyp oder Platzhalter: 422.
     */
    fun send(jdbc: JdbcOperations, request: NotificationRequest): EnqueueResult

    /** Aufträge des Mandanten nach (`created_at`, `id`); je Seite höchstens `limit + 1` Zeilen. */
    fun list(ctx: RequestContext, state: NotificationState?, limit: Int, after: Pair<Instant, UUID>?): List<NotificationView>

    /** 404 für unbekannte ID und für den Auftrag eines fremden Mandanten. */
    fun get(ctx: RequestContext, id: UUID): NotificationView
}

/** Ein ausgehender Versand an den Provider. [messageId] ist die Nachrichten-ID des Auftrags und gilt als Idempotenzreferenz des Providers. */
class OutboundMessage(val messageId: UUID, val channel: NotificationChannel, val to: String, val subject: String?, val body: String) {
    override fun toString() = "OutboundMessage(messageId=$messageId, channel=$channel)"   // nie Kontakt oder Inhalt
}

sealed interface ProviderResult {
    /** Der Provider hat die Nachricht eindeutig angenommen. */
    data class Accepted(val providerRef: String) : ProviderResult
    /** Der Provider lehnt ab. [permanent]: nie wiederholen (z. B. ungültiger Kontakt); sonst nachweislich nicht zugestellt, später erneut versuchen. */
    data class Rejected(val permanent: Boolean, val reason: FailureReason) : ProviderResult {
        init { require(reason == FailureReason.INVALID_CONTACT || reason == FailureReason.PROVIDER_ERROR) { "Ablehnung nur mit Kanalfehlergrund des Providers" } }
    }
}

/** Keine Antwort in der Frist: der Ausgang ist unbestimmt (die Nachricht kann trotzdem zugestellt worden sein). */
class ProviderTimeoutException(message: String = "Provider-Zeitüberschreitung") : RuntimeException(message)

/** Abgleich einer Nachrichten-ID beim Provider (nur Zustand, nie Inhalt). */
sealed interface ProviderStatus {
    data class Accepted(val providerRef: String) : ProviderStatus
    /** Der Provider kennt die Nachrichten-ID nicht: nachweislich nicht zugestellt. */
    data object NotKnown : ProviderStatus
    /** Der Provider kann (noch) keine Auskunft geben. */
    data object Unavailable : ProviderStatus
}

/**
 * Kanalschnittstelle Push, SMS, E-Mail (Adapter `notification`). Der Provider behandelt [OutboundMessage.messageId] als Idempotenzreferenz: dieselbe ID ergibt
 * höchstens eine fachliche Nachricht. Aufrufe laufen nie innerhalb einer offenen SQL-Transaktion (Leitlinie 3).
 */
interface DeliveryGateway {
    /** Wirft [ProviderTimeoutException] bei Zeitüberschreitung; jede andere Ausnahme gilt als vorübergehender Providerfehler. */
    fun send(message: OutboundMessage): ProviderResult

    fun status(channel: NotificationChannel, messageId: UUID): ProviderStatus
}

/** Ergebnis der Kontaktauflösung. Der Kontakt wird nie gespeichert oder protokolliert. */
sealed interface ContactLookup {
    class Found(val address: String) : ContactLookup { override fun toString() = "ContactLookup.Found" }
    /** Kein verifizierter, nutzbarer Kontakt für den Kanal. */
    data object None : ContactLookup
}

/** Verzeichnis des Empfängers (Implementierung im Modul `identity` über den Feldschutz; hier nur der Port). */
interface RecipientDirectory {
    fun contact(tenant: TenantId, recipientRef: UUID, channel: NotificationChannel): ContactLookup

    /** Sprache des Fahrgasts als BCP-47-Tag (z. B. `de-DE`), `null` wenn unbekannt. */
    fun language(tenant: TenantId, recipientRef: UUID): String?
}

/**
 * Planungswerte (OP 28: im Pflichtenheft nicht festgelegt, Produktivwerte brauchen eine Fachentscheidung): Staffel 1 s, 5 s, 30 s, 2 min, 10 min, höchstens 6 Versuche
 * (in Anlehnung an Kap. 05 §5.6, Vorschlag `nachricht.md`).
 */
data class DispatchConfig(
    val maxAttempts: Int = 6,
    val backoff: List<Duration> = listOf(1L, 5L, 30L, 120L, 600L).map { Duration.ofSeconds(it) },
    val lease: Duration = Duration.ofSeconds(60),
    val batchSize: Int = 20,
) {
    init { require(maxAttempts in 1..100 && backoff.isNotEmpty() && batchSize in 1..500 && !lease.isNegative && !lease.isZero) }
    fun delay(attempt: Int): Duration = backoff[(attempt - 1).coerceIn(0, backoff.size - 1)]
}

data class DispatchStats(val delivered: Int = 0, val retried: Int = 0, val unknown: Int = 0, val failed: Int = 0, val suppressed: Int = 0) {
    operator fun plus(o: DispatchStats) = DispatchStats(delivered + o.delivered, retried + o.retried, unknown + o.unknown, failed + o.failed, suppressed + o.suppressed)
}

/**
 * Versand-Worker (ein Lauf je Mandant, der Betrieb liefert die Mandanten, OP-06/OP-08): beansprucht fällige Aufträge (`FOR UPDATE SKIP LOCKED`, Lease), löst Kontakt und
 * Vorlage auf, sendet **außerhalb** jeder Datenbanktransaktion mit der Nachrichten-ID als Idempotenzreferenz und schreibt das Ergebnis. Ein Timeout ist `UNKNOWN`
 * (Abgleich beim Provider vor jeder Wiederholung); erschöpfte Versuche enden als `FAILED` mit Dead-Letter-Markierung, Ereignis `communication.notification_failed`
 * und Betreiberwarnung, nie still.
 */
interface NotificationDispatcher {
    fun runTenant(tenant: TenantId): DispatchStats
}

/** Zuordnung Ereignis -> Aufträge (z. B. WP-M2-34: Buchung bestätigt, Storno, …). Läuft in der Inbox-Transaktion des Empfängermandanten. */
fun interface NotificationTrigger {
    fun requests(event: EventEnvelope): List<NotificationRequest>
}

/** Ereignisverbraucher: nimmt die Ereignishülle (JSON) entgegen und legt je Ereignis und Empfänger genau einen Auftrag an (Inbox plus Unique). */
interface NotificationEventConsumer {
    fun consume(json: String): InboxOutcome

    companion object { const val CONSUMER = "communication-support" }
}

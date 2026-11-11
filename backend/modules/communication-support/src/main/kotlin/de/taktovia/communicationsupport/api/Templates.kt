package de.taktovia.communicationsupport.api

import de.taktovia.platform.context.RequestContext
import org.springframework.jdbc.core.JdbcOperations
import java.time.Instant
import java.util.UUID

/**
 * Nachrichtenvorlagen je Sprache, Verkehrsprodukt (Dienst, OP 6) und Ereignistyp (WP-M2-33, LH-6.12-COM-002). Vertrag: `docs/umsetzungsplan/vertraege/LH-6.12-COM-002.md`.
 * Vorlagen sind unveränderliche Revisionen: eine Änderung ist eine neue Revision, die wirksame ist die höchste zum Zeitpunkt gültige.
 */
enum class PlaceholderType(val description: String) {
    /** Buchungsreferenz und Buchungsnummer: kurze Kennung aus Buchstaben, Ziffern, `-`, `_`. */
    REFERENCE("Kennung (A-Z, a-z, 0-9, -, _; höchstens 40 Zeichen)") {
        private val pattern = Regex("[A-Za-z0-9_-]{1,40}")
        override fun accepts(value: String) = pattern.matches(value)
    },
    /** Freitext (Treffpunkt, Zeitfenster, ETA); Steuerzeichen werden beim Rendern kanalgerecht entfernt. */
    TEXT("Text (höchstens 200 Zeichen)") {
        override fun accepts(value: String) = value.isNotBlank() && value.length <= 200
    };

    abstract fun accepts(value: String): Boolean
}

/** Platzhalter des Katalogs (Schlüssel in der Vorlage: `{{schluessel}}`). */
enum class Placeholder(val key: String, val type: PlaceholderType) {
    BOOKING_REFERENCE("booking_reference", PlaceholderType.REFERENCE),
    BOOKING_NUMBER("booking_number", PlaceholderType.REFERENCE),
    MEETING_POINT("meeting_point", PlaceholderType.TEXT),
    TIME_WINDOW("time_window", PlaceholderType.TEXT),
    ETA("eta", PlaceholderType.TEXT);

    companion object { fun byKey(key: String): Placeholder? = entries.firstOrNull { it.key == key } }
}

/**
 * Ereignistypen der Nachrichten (Arbeitsnamen, Zuordnungstabelle in `LH-6.12-COM-001.md`) und Platzhalterkatalog je Ereignistyp. Alle fünf Typen sind
 * buchungsbezogen: ihre Vorlagen brauchen den Pflichtplatzhalter „Buchungsreferenz“. Die Liste der buchungsbezogenen Typen steht auch im CHECK
 * `channel_template_booking_reference` (V2330); `TemplateResolutionTest.katalog_und_datenbank_pruefung_stimmen_ueberein` hält beide synchron.
 */
object NotificationEventTypes {
    const val BOOKING_CONFIRMED = "booking.confirmed"
    const val ETA_CHANGED = "eta.changed"
    const val VEHICLE_ARRIVED = "vehicle.arrived"
    const val BOOKING_CANCELLED_BY_PASSENGER = "booking.cancelled_by_passenger"
    const val BOOKING_CANCELLED_BY_OPERATOR = "booking.cancelled_by_operator"

    private val all = Placeholder.entries.toSet()
    private val catalog: Map<String, Set<Placeholder>> = mapOf(
        BOOKING_CONFIRMED to all,
        ETA_CHANGED to all,
        VEHICLE_ARRIVED to setOf(Placeholder.BOOKING_REFERENCE, Placeholder.BOOKING_NUMBER, Placeholder.MEETING_POINT),
        BOOKING_CANCELLED_BY_PASSENGER to setOf(Placeholder.BOOKING_REFERENCE, Placeholder.BOOKING_NUMBER, Placeholder.TIME_WINDOW),
        BOOKING_CANCELLED_BY_OPERATOR to setOf(Placeholder.BOOKING_REFERENCE, Placeholder.BOOKING_NUMBER, Placeholder.TIME_WINDOW),
    )
    private val bookingRelated = catalog.keys

    val ALL: Set<String> get() = catalog.keys
    fun isKnown(eventType: String) = eventType in catalog
    fun isBookingRelated(eventType: String) = eventType in bookingRelated
    /** Zulässige Platzhalter des Ereignistyps; leer für unbekannte Typen. */
    fun placeholders(eventType: String): Set<Placeholder> = catalog[eventType].orEmpty()
}

/** Wirksame Vorlagenrevision. */
data class TemplateRevisionView(
    val id: UUID, val eventType: String, val language: String, val serviceId: UUID?, val channel: NotificationChannel, val revision: Long,
    val subjectTemplate: String?, val bodyTemplate: String, val validFrom: Instant, val validTo: Instant?, val createdAt: Instant,
)

/** Neue Vorlagenrevision. [validFrom] fehlt = jetzt. Die Revisionsnummer vergibt der Dienst (höchste je Schlüssel plus 1). */
class CreateTemplateRevision(
    val eventType: String, val language: String, val serviceId: UUID?, val channel: NotificationChannel,
    val bodyTemplate: String, val subjectTemplate: String? = null, val validFrom: Instant? = null, val validTo: Instant? = null,
)

/** Auflösung nach (Ereignistyp, Dienst, Sprache, Kanal) zum Zeitpunkt [at]; [language] ist der BCP-47-Tag des Fahrgasts. */
class TemplateQuery(val eventType: String, val serviceId: UUID?, val language: String, val channel: NotificationChannel, val at: Instant)

sealed interface TemplateResolution {
    data class Found(val template: TemplateRevisionView) : TemplateResolution
    /** Auch die Rückfallkette hat keine Vorlage: Kanalfehler statt leerer Nachricht. */
    data object Missing : TemplateResolution
}

/**
 * Vorlagenpflege und -auflösung. Pflege in P1: **Seed und Dienstoperation**, keine HTTP-Schnittstelle (OP 29, Entscheidung dieses Baus). Rückfallkette:
 * 1. Sprache und Dienst exakt; 2. gleiche Sprache ohne Dienst; 3. Standardsprache Deutsch mit Dienst; 4. Deutsch ohne Dienst; sonst [TemplateResolution.Missing].
 * „Gleiche Sprache“: zuerst der volle Tag (`de-DE`), dann der Hauptteil (`de`), dann eine andere Region derselben Sprache.
 */
interface TemplateService {
    /**
     * Legt eine neue Revision an (Aktivierung = Anlage, `validFrom`). 422: unbekannter Ereignistyp oder Platzhalter, fehlender Pflichtplatzhalter „Buchungsreferenz“ bei
     * buchungsbezogenen Typen, ungültiger Sprachtag, Gültigkeitsende nicht nach Beginn; 404: Dienst eines fremden Mandanten. Ein Audit je Anlage.
     */
    fun create(ctx: RequestContext, cmd: CreateTemplateRevision): TemplateRevisionView

    /** 404 für unbekannte ID und Revision eines fremden Mandanten. */
    fun get(ctx: RequestContext, id: UUID): TemplateRevisionView

    /** Revisionen des Mandanten, neueste zuerst, optional nur eines Ereignistyps (höchstens 200). */
    fun list(ctx: RequestContext, eventType: String?): List<TemplateRevisionView>

    /** Löst die wirksame Revision in der laufenden Mandantentransaktion auf (ein Statement: konsistent auch während paralleler Anlage neuer Revisionen). */
    fun resolve(jdbc: JdbcOperations, query: TemplateQuery): TemplateResolution

    /** Legt die Standardvorlagen Deutsch und Englisch für alle Ereignistypen und Kanäle an, soweit der Mandant noch keine Vorlage dafür hat (Seed, idempotent). Liefert die Zahl neuer Revisionen. */
    fun seedDefaults(ctx: RequestContext): Int
}

class TemplateRenderException(val code: String) : RuntimeException(code)

data class RenderedMessage(val subject: String?, val body: String) {
    override fun toString() = "RenderedMessage"   // nie Inhalt
}

/**
 * Rendern einer Vorlage: `{{schluessel}}` wird **einstufig** ersetzt (ein Wert wird nie erneut ausgewertet), Werte werden kanalgerecht maskiert
 * (E-Mail: HTML-Escaping, SMS/Push: Klartext ohne Steuerzeichen). Ein unbekannter Platzhalter, ein fehlender oder ungültiger Wert ist ein [TemplateRenderException]
 * (Kanalfehler), nie eine leere oder halbe Nachricht.
 */
object TemplateRenderer {
    private val TOKEN = Regex("\\{\\{\\s*([a-z_]+)\\s*}}")

    fun placeholdersIn(template: String): Set<String> = TOKEN.findAll(template).map { it.groupValues[1] }.toSet()

    fun render(eventType: String, channel: NotificationChannel, subjectTemplate: String?, bodyTemplate: String, params: Map<String, String>): RenderedMessage {
        val allowed = NotificationEventTypes.placeholders(eventType).associateBy { it.key }
        fun one(template: String) = TOKEN.replace(template) { m ->
            val p = allowed[m.groupValues[1]] ?: throw TemplateRenderException("placeholder_unknown")
            val value = params[p.key]?.takeIf { it.isNotBlank() } ?: throw TemplateRenderException("placeholder_missing")
            if (!p.type.accepts(value)) throw TemplateRenderException("placeholder_invalid")
            escape(channel, value)
        }
        return RenderedMessage(subjectTemplate?.let(::one), one(bodyTemplate))
    }

    fun escape(channel: NotificationChannel, value: String): String = when (channel) {
        NotificationChannel.EMAIL -> buildString { for (c in value) when (c) { '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;"); '"' -> append("&quot;"); '\'' -> append("&#39;"); else -> if (!c.isISOControl()) append(c) } }
        NotificationChannel.SMS, NotificationChannel.PUSH -> value.map { if (it.isISOControl()) ' ' else it }.joinToString("")
    }
}

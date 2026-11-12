package de.taktovia.communicationsupport.api

import de.taktovia.platform.outbox.EventEnvelope
import de.taktovia.platform.tenant.TenantId
import org.springframework.jdbc.core.JdbcOperations
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Auslöser transaktionaler Benachrichtigungen (WP-M2-34, LH-6.12-COM-001). Vertrag: `docs/umsetzungsplan/vertraege/LH-6.12-COM-001.md`. Fünf Szenarien, je ein Ereignistyp der Nachricht
 * (Zuordnung dort): Bestätigung (`booking.confirmed`), relevante ETA-Änderung (`eta.changed`), Ankunft (`vehicle.arrived`), Storno durch den Fahrgast
 * (`booking.cancelled_by_passenger`) und Ausfall/Betreiberstorno (`booking.cancelled_by_operator`). Die Auslöser laufen in der Inbox-Transaktion des Empfängermandanten, lesen die
 * Buchungsfakten über den Port [BookingFactsSource] und legen je Ereignis und Fahrgast genau einen Auftrag an; versendet wird nichts (das tut der Worker, WP-M2-32).
 */
enum class NotificationPurpose {
    /** Betriebsmitteilung zur gebuchten Fahrt: braucht keine Einwilligung (Kap. 05 §5.7). */
    OPERATIONAL,
    /** Werbung: über diesen Weg nie versendet. */
    MARKETING,
}

/** Fakten einer Buchung für die Nachricht; [state] ist der Buchungszustand zum Zeitpunkt der Verarbeitung (unter Lesesperre, damit ein paralleles Storno linearisiert ist). */
data class BookingFacts(
    val bookingId: UUID, val bookingNumber: String, val passengerRef: UUID, val serviceId: UUID, val state: String,
    val meetingPoint: String?, val pickupEarliest: Instant, val pickupLatest: Instant, val timezone: String,
) {
    /** Nur bestätigte oder in Bestätigung befindliche Buchungen bekommen Bestätigungs- und ETA-Nachrichten. */
    val active: Boolean get() = state == "CONFIRMING" || state == "CONFIRMED"
}

/** Lesezugriff auf Buchungsfakten in der laufenden Mandantentransaktion (Implementierung: `booking-dispatch` über die Anwendung). `null` = unbekannt oder fremder Mandant. */
fun interface BookingFactsSource {
    fun booking(jdbc: JdbcOperations, bookingId: UUID): BookingFacts?
}

/** Darf zum Zweck an den Empfänger zugestellt werden? Betriebsmitteilungen immer, Werbung nur mit Einwilligung (Port der Identität, WP-M1-30). */
fun interface DeliveryPurposePolicy {
    fun allowed(tenant: TenantId, recipientRef: UUID, purpose: NotificationPurpose): Boolean

    companion object {
        /** Betriebsmitteilungen ja, Werbung nie (der Auslöserweg versendet keine Werbung). */
        val OPERATIONAL_ONLY = DeliveryPurposePolicy { _, _, purpose -> purpose == NotificationPurpose.OPERATIONAL }
    }
}

/** Ein ausgelöster Auftrag mit seinem Zweck. */
data class TriggeredNotification(val request: NotificationRequest, val purpose: NotificationPurpose = NotificationPurpose.OPERATIONAL)

/** Auslöser mit Zugriff auf die Mandantentransaktion des Verbrauchers (Fakten lesen, Kanal wählen). */
fun interface TransactionalNotificationTrigger {
    fun requests(jdbc: JdbcOperations, event: EventEnvelope): List<TriggeredNotification>
}

/**
 * Planungswerte der Auslöser (OP 28/30: im Pflichtenheft nicht festgelegt, Produktivwerte brauchen eine Fachentscheidung). [etaRelevanceThreshold]: eine Verschiebung der geplanten
 * Abholzeit um mindestens diese Dauer ist eine „relevante ETA-Änderung“ (Vorläuferwert 5 Minuten, kein Katalogparameter). [channelPreference]: Reihenfolge der Kanalwahl; gewählt wird
 * der erste Kanal mit verifiziertem Kontakt, ohne nutzbaren Kanal der erste der Liste (der Auftrag endet dann dokumentiert als Kanalfehler `NO_VERIFIED_CONTACT`).
 */
data class TriggerSettings(
    val etaRelevanceThreshold: Duration = Duration.ofMinutes(5),
    val channelPreference: List<NotificationChannel> = listOf(NotificationChannel.PUSH, NotificationChannel.SMS, NotificationChannel.EMAIL),
) {
    init {
        require(!etaRelevanceThreshold.isNegative && !etaRelevanceThreshold.isZero && etaRelevanceThreshold <= Duration.ofHours(6)) { "Relevanzschwelle außerhalb 1 s .. 6 h" }
        require(channelPreference.isNotEmpty() && channelPreference.toSet().size == channelPreference.size) { "Kanalreihenfolge leer oder mit Doppelten" }
    }
}

/** Ereignisnamen, die die Standardauslöser verarbeiten (Quellereignisse der Zuordnungstabelle). */
object TriggerSourceEvents {
    const val BOOKING_CONFIRMED = "booking.confirmed"
    const val BOOKING_CANCELLED = "booking.cancelled"
    const val RUN_REVISED = "dispatch.run_revised"
    const val PROMISE_CHANGED = "promise.changed"
    /** Ankunft am Stopp (Driver-App, WP-M3-29); in M2 nur als Testereignis, Schema `api/events/vehicle.arrived.v1.schema.json`. */
    const val VEHICLE_ARRIVED = "vehicle.arrived"
    val ALL = setOf(BOOKING_CONFIRMED, BOOKING_CANCELLED, RUN_REVISED, PROMISE_CHANGED, VEHICLE_ARRIVED)
}

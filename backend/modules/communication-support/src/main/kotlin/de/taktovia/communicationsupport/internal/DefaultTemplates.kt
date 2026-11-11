package de.taktovia.communicationsupport.internal

import de.taktovia.communicationsupport.api.NotificationChannel
import de.taktovia.communicationsupport.api.NotificationEventTypes

/**
 * Standardvorlagen für den Seed (OP 29: Deutsch und Englisch als Annahme des Baus, die Auslieferungssprachen sind fachlich zu bestätigen). Jeder Text verwendet nur
 * Platzhalter des Ereignistyps und enthält die Buchungsreferenz. Die E-Mail ist HTML (Werte werden maskiert), SMS und Push sind Klartext.
 */
internal object DefaultTemplates {
    class Text(val subject: String?, val body: String)

    private val de = mapOf(
        NotificationEventTypes.BOOKING_CONFIRMED to ("Buchung bestätigt" to "Ihre Buchung {{booking_reference}} (Nr. {{booking_number}}) ist bestätigt. Treffpunkt: {{meeting_point}}, Zeitfenster: {{time_window}}."),
        NotificationEventTypes.ETA_CHANGED to ("Neue Abholzeit" to "Zu Ihrer Buchung {{booking_reference}} gibt es eine neue voraussichtliche Ankunft: {{eta}} am Treffpunkt {{meeting_point}}."),
        NotificationEventTypes.VEHICLE_ARRIVED to ("Ihr Fahrzeug ist da" to "Ihr Fahrzeug zur Buchung {{booking_reference}} wartet am Treffpunkt {{meeting_point}}."),
        NotificationEventTypes.BOOKING_CANCELLED_BY_PASSENGER to ("Stornierung bestätigt" to "Ihre Buchung {{booking_reference}} wurde auf Ihren Wunsch storniert."),
        NotificationEventTypes.BOOKING_CANCELLED_BY_OPERATOR to ("Fahrt entfällt" to "Ihre Fahrt zur Buchung {{booking_reference}} entfällt leider (Zeitfenster {{time_window}}). Wir bitten um Entschuldigung."),
    )
    private val en = mapOf(
        NotificationEventTypes.BOOKING_CONFIRMED to ("Booking confirmed" to "Your booking {{booking_reference}} (no. {{booking_number}}) is confirmed. Meeting point: {{meeting_point}}, time window: {{time_window}}."),
        NotificationEventTypes.ETA_CHANGED to ("New pickup time" to "There is a new expected arrival for your booking {{booking_reference}}: {{eta}} at the meeting point {{meeting_point}}."),
        NotificationEventTypes.VEHICLE_ARRIVED to ("Your vehicle has arrived" to "Your vehicle for booking {{booking_reference}} is waiting at the meeting point {{meeting_point}}."),
        NotificationEventTypes.BOOKING_CANCELLED_BY_PASSENGER to ("Cancellation confirmed" to "Your booking {{booking_reference}} was cancelled at your request."),
        NotificationEventTypes.BOOKING_CANCELLED_BY_OPERATOR to ("Trip cancelled" to "Your trip for booking {{booking_reference}} has been cancelled (time window {{time_window}}). We apologise."),
    )

    val LANGUAGES: Map<String, Map<String, Pair<String, String>>> = mapOf("de" to de, "en" to en)

    /** Text je Ereignistyp, Sprache und Kanal: die E-Mail hat einen Betreff und HTML-Absatz, SMS und Push nur Klartext. */
    fun text(eventType: String, language: String, channel: NotificationChannel): Text {
        val (subject, body) = LANGUAGES.getValue(language).getValue(eventType)
        return when (channel) {
            NotificationChannel.EMAIL -> Text(subject, "<p>$body</p>")
            NotificationChannel.PUSH -> Text(subject, body)
            NotificationChannel.SMS -> Text(null, body)
        }
    }
}

package de.taktovia.bookingdispatch.api

import org.springframework.jdbc.core.JdbcOperations
import java.time.Instant
import java.util.UUID

/**
 * Buchungsfakten für Benachrichtigungen (WP-M2-34, LH-6.12-COM-001): was `communication-support` für Bestätigung, ETA-Änderung, Ankunft, Storno und Ausfall braucht, ohne die
 * Tabellen dieses Moduls zu kennen. Der Fahrgast ist als Verweis auf sein Konto ([passengerRef] = `passenger_private.id`) angegeben, nie mit Kontaktdaten.
 */
data class BookingNotificationFacts(
    val bookingId: UUID, val bookingNumber: String, val passengerRef: UUID, val serviceId: UUID, val state: String,
    val meetingPoint: String?, val pickupEarliest: Instant, val pickupLatest: Instant, val timezone: String,
)

/**
 * Liest die Fakten in der laufenden Mandantentransaktion des Aufrufers. Bei [lock] unter Lesesperre der Buchungszeile (`FOR SHARE`): ein paralleles Storno (`FOR UPDATE`) wartet dann
 * auf die Transaktion des Aufrufers oder wird vor ihr festgeschrieben und ist sichtbar; so entsteht nach einem Storno nie eine Bestätigungs- oder ETA-Nachricht. `null`, wenn die Buchung
 * unbekannt ist oder einem anderen Mandanten gehört (RLS), oder noch keine bestätigte Buchung ist.
 */
interface BookingNotificationFactsPort {
    fun facts(jdbc: JdbcOperations, bookingId: UUID, lock: Boolean = true): BookingNotificationFacts?

    companion object {
        fun create(): BookingNotificationFactsPort = de.taktovia.bookingdispatch.internal.JdbcBookingNotificationFacts()
    }
}

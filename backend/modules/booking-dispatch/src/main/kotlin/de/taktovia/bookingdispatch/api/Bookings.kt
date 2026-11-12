package de.taktovia.bookingdispatch.api

import de.taktovia.bookingdispatch.internal.BookingServicesFactory
import de.taktovia.config.api.ImpactProvider
import de.taktovia.farepayment.api.FareQuotes
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.outbox.Outbox
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Lesesicht auf `booking.promise_snapshot` (Schema `promise-snapshot.v1`): nach der Bestätigung unveränderlich. */
data class BookingPromise(
    val pickupEarliest: Instant, val pickupLatest: Instant, val arrivalEarliest: Instant, val arrivalLatest: Instant,
    val grossMinor: Long, val currency: String, val exponent: Int, val cancellationPolicyRef: String, val vehicleClass: String?, val resourceVector: ResourceVector?,
)

/**
 * Buchung (`RideBooking`). [assistanceCodes] ist nur für Fahrgast und zuständige Rollen (Disposition, Callcenter) gefüllt, sonst `null` (Minimierung, Kap. 05 §5.1); Kontaktdaten und die
 * Kennung des Fahrgastkontos sind nie Teil der Sicht. [bookingNumber] ist je Mandant eindeutig und erscheint unverändert in Bestätigung, UI-04 und Nachrichten.
 */
data class BookingDetail(
    val id: UUID, val bookingNumber: String, val serviceId: UUID, val holdId: UUID, val state: String, val revision: Long, val createdAt: Instant,
    val passengers: Int, val wheelchairSpaces: Int, val assistanceCodes: List<String>?, val promise: BookingPromise,
)

/** Befehl „Hold bestätigen“: [ifMatch] = Revision des Angebots, die der Hold-Antwort als ETag mitgegeben wurde; [maxGrossMinor] der akzeptierte Betrag. */
data class ConfirmBooking(val holdId: UUID, val ifMatch: Long, val maxGrossMinor: Long, val currency: String, val paymentReference: String? = null)

/** Ergebnis der Bestätigung; [replayed] = der Hold war bereits bestätigt, geliefert wird dieselbe Buchung. */
data class ConfirmedBooking(val booking: BookingDetail, val replayed: Boolean)

/**
 * Buchungsbestätigung und Lesesichten (WP-M2-23, LH-6.4-BKG-003).
 *
 * **Confirm** läuft in einer lokalen Transaktion unter dem Zeilenlock des Umlaufs (Reihenfolge Umlauf, Angebot, Hold): Wiederholung findet dieselbe Buchung; abgelaufener oder verworfener Hold
 * `409 HOLD_EXPIRED` bzw. `INVALID_STATE_TRANSITION`; veraltete Angebotsrevision `412`; abweichender Betrag `409 REQUOTE_REQUIRED`; seit dem Hold geänderter Fahrtenplan oder nicht mehr
 * verfügbare Kapazität `409 CAPACITY_UNAVAILABLE`; fremder Hold `404`; ohne Fahrgastkonto `403`. Hold verbrauchen, Buchung mit unveränderlicher Zusage, Journey-Hülle, Buchungsnummer und
 * Outbox-Ereignis entstehen gemeinsam oder gar nicht. Tariflich kostenfreie Buchungen (Betrag 0) sind sofort `CONFIRMED`, alle anderen bleiben `CONFIRMING`, bis die Zahlungsvoraussetzung
 * erfüllt ist (OP-5, Zahlungsautomat WP-M2-30; der Übergang selbst ist BookingStates).
 *
 * **Lesen:** Fahrgast nur eigene Buchungen (fremde ID `404`); Betriebspersonal nur Buchungen der Dienste seines Scopes (anderer Dienst oder Mandant `404`).
 */
interface Bookings {
    fun confirm(ctx: RequestContext, cmd: ConfirmBooking): ConfirmedBooking
    fun get(ctx: RequestContext, bookingId: UUID): BookingDetail

    /** Eigene Buchungen (Fahrgast) bzw. Buchungen der Dienste des Scopes (Betrieb), aufsteigend nach `(created_at, id)`; [limit] + 1 Zeilen ab [after] (Seitenbildung beim Aufrufer). */
    fun list(ctx: RequestContext, limit: Int, after: Pair<Instant, UUID>?): List<BookingDetail>

    companion object {
        fun create(ctxTx: ContextTransactions, outbox: Outbox, quotes: FareQuotes, clock: Clock = Clock.systemUTC()): Bookings = BookingServicesFactory.bookings(ctxTx, outbox, quotes, clock)

        /** ImpactProvider „Buchungen“ (von WP-M1-23 erwartet): bestätigte Buchungen eines Dienstes ab dem Wirksamkeitsbeginn einer Dienstkonfigurationsrevision. Nur Verweise, nie Personendaten. */
        fun impactProvider(): ImpactProvider = BookingServicesFactory.bookingImpactProvider()
    }
}

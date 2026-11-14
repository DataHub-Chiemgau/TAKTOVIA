package de.taktovia.bookingdispatch.api

import de.taktovia.bookingdispatch.internal.BookingServicesFactory
import de.taktovia.config.api.EffectiveConfig
import de.taktovia.farepayment.api.FareQuotes
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.outbox.Outbox
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Grundkatalog der Leitstelle für Anlage und Änderung (WP-M3-39, Arbeitskatalog bis zur Fachentscheidung D-12/OP-30): Pflicht bei jeder Leitstellenbuchung und -änderung. Der Stornogrund ist der
 * Katalog [BookingReason] (Art `CANCELLATION`). [OTHER] verlangt einen Freitext.
 */
enum class DispatchReason { PHONE_REQUEST, COUNTER_REQUEST, PASSENGER_REQUEST, OPERATIONAL_NEED, CORRECTION, OTHER;
    companion object { fun parse(code: String?): DispatchReason? = entries.firstOrNull { it.name == code } }
}

/** Nachweis der Zustimmung des Fahrgasts zu einer Preis- oder Zeitänderung: [channel] (`PHONE`, `COUNTER`, `WRITTEN`) und der Zeitpunkt, zu dem der Disponent sie eingeholt hat. */
data class PassengerConsent(val channel: String, val obtainedAt: Instant) {
    init { require(channel in CHANNELS) { "Zustimmungskanal" } }
    companion object { val CHANNELS = setOf("PHONE", "COUNTER", "WRITTEN") }
}

/**
 * Änderung einer Buchung durch die Disposition (Mindestumfang, OP-30): [ifMatch] = Revision der bestehenden Buchung, [newHoldId] der bereits gehaltene Hold des geänderten Wunsches (Angebot und Hold
 * liefen über `POST /v1/ride-offers` und `.../hold` mit [DispatchSubject] auf denselben Fahrgast), [newOfferRevision] die Angebotsrevision (ETag der Hold-Antwort), [maxGrossMinor] der akzeptierte
 * Betrag. [consent] ist Pflicht, wenn der neue Preis oder der Beginn des Abholfensters vom bestehenden abweicht.
 */
data class ChangeBooking(
    val ifMatch: Long, val newHoldId: UUID, val newOfferRevision: Long, val maxGrossMinor: Long, val currency: String, val reason: DispatchReason, val reasonText: String? = null,
    val consent: PassengerConsent? = null,
)

/** Ergebnis der Änderung: die Folgebuchung, die stornierte bisherige Buchung und der Änderungseintrag; [replayed] = dieselbe Änderung war bereits durchgeführt. */
data class ChangedBooking(val booking: BookingDetail, val replaced: BookingDetail, val changeId: UUID, val replayed: Boolean, val priceChanged: Boolean, val timeChanged: Boolean)

/**
 * Buchung durch die Disposition (WP-M3-39, LH-6.7-OPS-004) auf demselben Tarif-, Kapazitäts- und Sicherheitskern wie die Fahrgastbuchung (Angebot, Hold, Bestätigung, Storno); nichts wird
 * umgangen. Mindestumfang, weil M2 weder `DELEGATED` noch die Bearbeitung bestätigter Buchungen liefert (OP-30).
 *
 * - **Berechtigung:** Rolle Disposition oder Callcenter und Schreibrecht am Dienst (frischer Scope); ohne Rolle 403, ohne Recht am Dienst oder bei fremdem Objekt 404.
 * - **Fahrgastbindung:** [issueOffer] bindet jedes Angebot an den Fahrgast ([operator_offer_subject]); der Fahrgast muss im Mandanten ein aktives Konto haben. Hold und Bestätigung prüfen
 *   Rolle, Scope und Bindung erneut ([assertHoldAllowed], [create]); ein Subjektwechsel ist ein neues Angebot. Die Buchung gehört dem Fahrgast (`passenger_id`), der Disponent steht als Urheber
 *   (`created_by`), im `booking_change`-Eintrag und im Audit als eigener Akteur.
 * - **Anlage und Änderung** verlangen einen Grund ([DispatchReason], 422 ohne), der Storno den Stornogrund (Katalog) und wahlweise einen Freitext.
 * - **Änderung als atomarer Tausch:** neues Angebot und neuer Hold liegen vor; unter den Sperren beider Umläufe (stabile Reihenfolge) entsteht die Folgebuchung, die bisherige Buchung wird storniert
 *   (Hold frei), ein `booking_change`-Eintrag (`REPLACE_OFFER`, alte und neue Zusage) und der Audit-Eintrag geschrieben. Scheitert irgendein Schritt, bleibt die bisherige Buchung samt Zusage
 *   unverändert und der neue Hold wird freigegeben (keine verwaiste Kapazität); es gibt keinen Zwischenzustand ohne Buchung.
 * - **Zustimmung:** weicht Preis oder Beginn des Abholfensters ab, wird die Änderung ohne [PassengerConsent] nicht wirksam (422, nichts geschieht, der neue Hold bleibt bis zum Ablauf gültig).
 */
interface OperatorBookings {
    /** Stellt Angebot(e) für den Fahrgast [passengerId] aus und bindet sie an ihn. */
    fun issueOffer(ctx: RequestContext, passengerId: UUID, cmd: RideOfferCommand): RideOfferResult

    /** Prüft vor dem Hold eines Angebots, das für einen Fahrgast gebunden ist, Rolle, Dienstscope und Konto erneut; für ein nicht gebundenes Angebot ohne Wirkung. */
    fun assertHoldAllowed(ctx: RequestContext, offerId: UUID)

    /** Bestätigt den Hold eines gebundenen Angebots als Buchung des Fahrgasts; protokolliert Akteur und Grund. */
    fun create(ctx: RequestContext, cmd: ConfirmBooking, reason: DispatchReason, reasonText: String?): ConfirmedBooking

    /** Ändert die Buchung [bookingId] (siehe Klassenbeschreibung). */
    fun change(ctx: RequestContext, bookingId: UUID, cmd: ChangeBooking): ChangedBooking

    companion object {
        /** Zeitverschiebung des Abholfensters, ab der eine Änderung die Zustimmung des Fahrgasts braucht (Arbeitsannahme OP-30, nicht im Parameterkatalog). */
        const val CONSENT_TIME_SHIFT_MIN = 5L

        fun create(
            ctxTx: ContextTransactions, outbox: Outbox, quotes: FareQuotes, requests: RideOfferRequests, offers: RideOffers, clock: Clock = Clock.systemUTC(), config: EffectiveConfig? = null,
            cancellation: CancellationListener? = null, audit: AuditPort? = null, staffing: de.taktovia.platform.readports.ShiftStaffingReadPort? = null,
        ): OperatorBookings = BookingServicesFactory.operatorBookings(ctxTx, outbox, quotes, requests, offers, clock, config, cancellation, audit, staffing)
    }
}

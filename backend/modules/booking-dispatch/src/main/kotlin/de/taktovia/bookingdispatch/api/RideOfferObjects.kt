package de.taktovia.bookingdispatch.api

import de.taktovia.bookingdispatch.internal.RideOfferServicesFactory
import de.taktovia.config.api.EffectiveConfig
import de.taktovia.farepayment.api.FareQuotes
import de.taktovia.farepayment.api.QuoteView
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.outbox.Outbox
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Zustand des Angebots (`ride_offer.state`, Automat `ride_offer`): `OFFERED` -> `HELD` | `EXPIRED` | `DECLINED`, `HELD` -> `EXPIRED` | `DECLINED`. */
enum class OfferState { OFFERED, HELD, EXPIRED, DECLINED }

/** Haltepunkt und die Revision, die zum Angebotszeitpunkt galt (Abholpunkt und Ziel sind Teil des Angebots). */
data class OfferStop(val stopId: UUID, val revisionId: UUID)

/**
 * Bindung des Angebots an die Planrevision (WP-M2-21): gehalten wird gegen Umlauf [runId], Revision [runRevision] und das halboffene Abschnittsintervall `[intervalFrom, intervalTo)`.
 * Die Bindung ist ein Planungsstand, **kein** Kapazitätsversprechen: ob der Platz noch frei ist, entscheidet erst der Hold unter dem Lock des Umlaufs (WP-M2-22).
 */
data class OfferAssignment(val runId: UUID, val runRevision: Long, val intervalFrom: Int, val intervalTo: Int) {
    init {
        require(runRevision >= 1) { "Planrevision muss >= 1 sein" }
        require(intervalFrom >= 0 && intervalTo > intervalFrom) { "Abschnittsintervall leer" }
    }
}

/** Zusammenfassung des Holds eines Angebots (`RideHold` der API): [state] `HELD`, `CONSUMED`, `RELEASED` oder `EXPIRED` (Ablauf wird bei jedem Zugriff mit Serverzeit bestimmt). */
data class HoldSummary(val id: UUID, val state: String, val expiresAt: Instant, val vector: ResourceVector)

/**
 * Das Angebot, genau wie ausgestellt (`GET /v1/ride-offers/{id}`): Inhalt, Preisstand und Haltepunkte ändern sich nie; ändern können sich nur [state], [hold] und [revision]
 * (ETag). Ein Angebot, dessen Frist erreicht ist (`now >= expiresAt`) und das keinen Hold hat, wird als `EXPIRED` geliefert.
 */
data class RideOfferView(
    val id: UUID, val attemptId: UUID, val serviceId: UUID, val state: OfferState, val bookingKind: RideBookingKind, val timeKind: RideTimeKind,
    val pickupWindow: RideTimeWindow, val arrivalWindow: RideTimeWindow, val arrivalGraceMin: Int, val directSeconds: Int, val expiresAt: Instant, val runRevision: Long?,
    val quote: QuoteView, val cancellationPolicyRef: String, val demand: ResourceVector, val pickupStop: OfferStop?, val dropoffStop: OfferStop?, val hold: HoldSummary?, val revision: Long,
)

/** Befehl „Kapazität halten“ auf einem Angebot: [ifMatch] = zuletzt gelesene Angebotsrevision (ETag), [maxGrossMinor] der höchste akzeptierte Betrag (Abweichung vom Preisstand: `REQUOTE_REQUIRED`). */
data class HoldRideOffer(val ifMatch: Long, val idempotencyKey: String, val maxGrossMinor: Long, val currency: String)

/** Ergebnis des Holds: [replayed] = Wiederholung desselben Befehls (gleicher Schlüssel), die den ursprünglichen Hold liefert; [offerRevision] ist die neue Angebotsrevision (ETag). */
data class RideOfferHold(val hold: HoldSummary, val offerRevision: Long, val replayed: Boolean)

/**
 * Angebotsobjekt, Hold und Freigabe (WP-M2-21 und WP-M2-22; LH-6.4-BKG-002, LH-6.5-OPT-009). Ein Angebot gehört dem Akteur, der es angefragt hat; fremde (anderer Fahrgast, anderer Mandant)
 * und unbekannte Angebote sind `404`.
 *
 * **Hold** (eine lokale Transaktion; Reihenfolge der Sperren Umlauf, dann Angebot): Reihenfolge der Prüfungen: Wiederholung desselben `Idempotency-Key` liefert den ursprünglichen Hold
 * (auch nach `expires_at`); abgelaufen (`now >= expires_at`, Serverzeit) `409 OFFER_EXPIRED`; veraltete Angebotsrevision `412 REVISION_MISMATCH`; abweichender Betrag `409 REQUOTE_REQUIRED`;
 * Angebot nicht mehr `OFFERED` `409 INVALID_STATE_TRANSITION`; kein Umlauf gebunden oder Kapazität erschöpft, Fahrzeug nicht einsetzbar `409 CAPACITY_UNAVAILABLE`; seit dem Angebot
 * veränderte Planrevision `409 REQUOTE_REQUIRED` (das Angebot ist veraltet, neu suchen). Hold, Angebotsrevision und Outbox entstehen gemeinsam oder gar nicht.
 * **Freigabe:** nur der Urheber; idempotent; ein `CONSUMED` Hold bleibt unverändert (`409 INVALID_STATE_TRANSITION`).
 */
interface RideOffers {
    fun get(ctx: RequestContext, offerId: UUID): RideOfferView
    fun hold(ctx: RequestContext, offerId: UUID, request: HoldRideOffer): RideOfferHold
    /** Gibt den Hold des Angebots frei; [ifMatch] wird nur geprüft, solange der Hold noch gehalten wird (eine Wiederholung nach erfolgter Freigabe ist unabhängig davon idempotent). */
    fun release(ctx: RequestContext, offerId: UUID, ifMatch: Long): RideOfferHold

    companion object {
        fun create(ctxTx: ContextTransactions, outbox: Outbox, quotes: FareQuotes, config: EffectiveConfig? = null, clock: Clock = Clock.systemUTC(), holdTtl: Duration = DEFAULT_HOLD_TTL,
            staffing: de.taktovia.platform.readports.ShiftStaffingReadPort? = null): RideOffers =
            RideOfferServicesFactory.offers(ctxTx, outbox, quotes, config, clock, holdTtl, staffing)

        /** Hold-Dauer 180 s (Planungsdefault, Kap. 02 §7.2; kein Katalogparameter, M2 OP-20). */
        val DEFAULT_HOLD_TTL: Duration = Duration.ofSeconds(180)
    }
}

/**
 * Ablauf von Holds (Expiry-Job, WP-M2-22): setzt abgelaufene `HELD`-Holds auf `EXPIRED` und das zugehörige Angebot ebenfalls. Fortsetzbar (jeder Hold in einer eigenen kurzen Transaktion
 * unter dem Lock seines Umlaufs, Wiederholung ist wirkungslos) und je Mandant mit Mandantenkontext. Die Prüfung bei jedem Zugriff (Serverzeit) bleibt maßgeblich; der Job ist nur die
 * Aufräumhilfe und benutzt denselben Lock wie Hold, Freigabe und Bestätigung.
 */
interface HoldExpiry {
    /** Verfällt höchstens [limit] abgelaufene Holds des Mandanten; liefert die Zahl der in diesem Lauf verfallenen. */
    fun runOnce(tenant: de.taktovia.platform.tenant.TenantId, limit: Int = 200): Int

    companion object {
        fun create(tx: de.taktovia.platform.tenant.TenantTransactions, outbox: Outbox, clock: Clock = Clock.systemUTC()): HoldExpiry = RideOfferServicesFactory.expiry(tx, outbox, clock)
    }
}

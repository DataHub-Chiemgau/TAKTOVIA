package de.taktovia.bookingdispatch.api

import de.taktovia.platform.statemachine.BookingState
import java.time.Instant
import java.util.UUID

/**
 * Katalog der Änderungs- und Stornogründe (WP-M2-25 gemeinsam mit WP-M2-26/-27; Datenbank: `booking_status_reason`, ein Test hält beide synchron). [targets] sind die Zielzustände, in
 * denen der Grund zulässig ist: ein Grund, der nicht zum Übergang passt, wird abgewiesen (422).
 */
enum class BookingReason(val kind: Kind, val targets: Set<BookingState>, val cause: CauseClass? = null, val settableBy: Set<ReasonActor> = emptySet()) {
    HOLD_CONFIRMED(Kind.CONFIRMATION, setOf(BookingState.CONFIRMING)),
    PAYMENT_AUTHORIZED(Kind.PAYMENT, setOf(BookingState.CONFIRMED)),
    FREE_OF_CHARGE(Kind.PAYMENT, setOf(BookingState.CONFIRMED)),
    PAYMENT_UNDETERMINED(Kind.PAYMENT, setOf(BookingState.RECONCILING)),
    PAYMENT_DECLINED(Kind.PAYMENT, setOf(BookingState.COMPENSATING)),
    HOLD_EXPIRED(Kind.CONFIRMATION, setOf(BookingState.COMPENSATING, BookingState.FAILED)),
    HOLD_UNUSABLE(Kind.CONFIRMATION, setOf(BookingState.COMPENSATING, BookingState.FAILED)),
    CONFIRMATION_FAILED(Kind.CONFIRMATION, setOf(BookingState.FAILED)),
    RECONCILED_AUTHORIZED(Kind.RECONCILIATION, setOf(BookingState.CONFIRMED)),
    RECONCILED_FAILED(Kind.RECONCILIATION, setOf(BookingState.COMPENSATING)),
    RECONCILED_NOTHING_TO_COMPENSATE(Kind.RECONCILIATION, setOf(BookingState.FAILED)),
    COMPENSATION_DONE(Kind.COMPENSATION, setOf(BookingState.CANCELED)),
    COMPENSATION_FAILED(Kind.COMPENSATION, setOf(BookingState.FAILED)),
    PASSENGER_REQUEST(Kind.CANCELLATION, setOf(BookingState.CANCEL_PENDING, BookingState.COMPENSATING), CauseClass.FAHRGAST, setOf(ReasonActor.PASSENGER, ReasonActor.OPERATOR)),
    OPERATOR_CANCELLATION(Kind.CANCELLATION, setOf(BookingState.CANCEL_PENDING, BookingState.COMPENSATING), CauseClass.BETREIBER, setOf(ReasonActor.OPERATOR, ReasonActor.SYSTEM)),
    NO_SHOW(Kind.CANCELLATION, setOf(BookingState.CANCEL_PENDING), CauseClass.FAHRGAST, setOf(ReasonActor.OPERATOR, ReasonActor.SYSTEM)),
    PAYMENT_FAILED(Kind.CANCELLATION, setOf(BookingState.CANCEL_PENDING, BookingState.COMPENSATING), CauseClass.FAHRGAST, setOf(ReasonActor.SYSTEM)),
    SAFETY(Kind.CANCELLATION, setOf(BookingState.CANCEL_PENDING, BookingState.COMPENSATING), CauseClass.BETREIBER, setOf(ReasonActor.OPERATOR, ReasonActor.SYSTEM)),
    VEHICLE_OUTAGE(Kind.CANCELLATION, setOf(BookingState.CANCEL_PENDING, BookingState.COMPENSATING), CauseClass.FAHRZEUGAUSFALL, setOf(ReasonActor.OPERATOR, ReasonActor.SYSTEM)),
    CANCEL_COMPLETED(Kind.CANCELLATION, setOf(BookingState.CANCELED)),
    CANCEL_UNDETERMINED(Kind.CANCELLATION, setOf(BookingState.RECONCILING)),
    TRIP_COMPLETED(Kind.EXECUTION, setOf(BookingState.COMPLETED));

    enum class Kind { CONFIRMATION, PAYMENT, RECONCILIATION, COMPENSATION, CANCELLATION, EXECUTION }

    /** Wer den Grund setzen darf (Fahrgast nur Kundenstorno, Betrieb im Dienstscope jeden Betriebsgrund, gescheiterte Zahlung nur das System). */
    enum class ReasonActor { PASSENGER, OPERATOR, SYSTEM }

    companion object { fun parse(code: String): BookingReason? = entries.firstOrNull { it.name == code } }
}

/**
 * Verursacherklasse eines Stornos (WP-M2-27, Vorschlag D-05): Datenbasis der Kennzahlen `Buchungsstornoquote Fahrgast` (FAHRGAST) und `Buchungsstornoquote Betreiber` (BETREIBER, Fahrzeugausfall
 * getrennt als FAHRZEUGAUSFALL). Die Erstattungslogik (WP-M2-31) wählt bei FAHRZEUGAUSFALL die betreiberverursachte Regel.
 */
enum class CauseClass { FAHRGAST, BETREIBER, FAHRZEUGAUSFALL }

/** Sichtweise auf die Historie: [PASSENGER] reduziert (Zeitpunkt, Zustand, Grund in Fahrgastsprache), [AUDIT] vollständig mit Akteur, Korrelation und Verknüpfungen (Prüfer, Betrieb im Dienstscope). */
enum class HistoryView { PASSENGER, AUDIT }

/** Verknüpfungen eines Eintrags zur Rekonstruktion der Kette Anfrage, Angebot, Hold, Bestätigung (nur Prüfersicht). */
data class HistoryLinks(val attemptId: UUID?, val offerId: UUID?, val holdId: UUID?, val quoteId: UUID?, val paymentIntentId: UUID?)

/**
 * Ein Eintrag der Statushistorie. [revision] ist die Buchungsrevision nach dem Übergang (laufende Nummer, lückenlos ab 1); [fromState] fehlt beim ersten Eintrag. In der Fahrgastsicht sind
 * [actorKind] gesetzt, aber [actorRef], [correlationId] und [links] immer `null` (keine Akteur-IDs Dritter), [reasonText] ist die Formulierung in Fahrgastsprache.
 */
data class BookingHistoryEntry(
    val revision: Long, val fromState: String?, val toState: String, val reason: BookingReason, val reasonText: String, val occurredAt: Instant, val actorKind: String,
    val actorRef: String?, val correlationId: UUID?, val links: HistoryLinks?,
)

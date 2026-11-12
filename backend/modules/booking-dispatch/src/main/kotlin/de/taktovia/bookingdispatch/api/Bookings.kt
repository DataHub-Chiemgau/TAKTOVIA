package de.taktovia.bookingdispatch.api

import de.taktovia.bookingdispatch.internal.BookingServicesFactory
import de.taktovia.config.api.EffectiveConfig
import de.taktovia.config.api.ImpactProvider
import de.taktovia.farepayment.api.FareQuotes
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.statemachine.BookingState
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
    /** Stornogrund und Verursacherklasse (WP-M2-27): einmal gesetzt, danach unveränderlich; `null`, solange nicht storniert. */
    val cancelReason: BookingReason? = null, val cancelCause: CauseClass? = null,
    /** Bereich der Buchungsübersicht (WP-M2-45), serverseitig aus Zustand, Ausführungszustand und Zeit berechnet. */
    val area: BookingArea = BookingArea.UPCOMING,
)

/**
 * Bezüge einer Buchung für die Zahlung (WP-M2-30): Dienst, Journey-Hülle und Quote (Preisstand des Angebots), dazu der Buchungszustand und der Bestätigungszeitpunkt. Nur Lesen, mit derselben
 * Objektberechtigung wie [Bookings.get] (fremde Buchung `404`); die Zahlung selbst führt das Modul `fare-payment`.
 */
data class BookingPaymentContext(val bookingId: UUID, val serviceId: UUID, val journeyId: UUID, val quoteId: UUID, val state: String, val createdAt: Instant)

/** Ergebnis einer Kopplungsoperation der Zahlung (ohne Personenkontext): [APPLIED] ausgeführt, [ALREADY_APPLIED] war bereits so (Wiederaufnahme), [NOT_APPLICABLE] die Buchung ist in einem Zustand, in dem die Zahlung nichts ändern darf. */
enum class CouplingOutcome { APPLIED, ALREADY_APPLIED, NOT_APPLICABLE }

/** Befehl „Hold bestätigen“: [ifMatch] = Revision des Angebots, die der Hold-Antwort als ETag mitgegeben wurde; [maxGrossMinor] der akzeptierte Betrag. */
data class ConfirmBooking(val holdId: UUID, val ifMatch: Long, val maxGrossMinor: Long, val currency: String, val paymentReference: String? = null)

/** Ergebnis der Bestätigung; [replayed] = der Hold war bereits bestätigt, geliefert wird dieselbe Buchung. */
data class ConfirmedBooking(val booking: BookingDetail, val replayed: Boolean)

/** Storno-Befehl: [ifMatch] = Revision der Buchung (ETag), [reason] einer der Stornogründe des Katalogs (Art `CANCELLATION`). */
data class CancelBooking(val ifMatch: Long, val reason: BookingReason)

/**
 * Ergebnis: die stornierte Buchung, die freigegebene Reservierung und die neue Planrevision des Umlaufs (`null`, wenn der Plan die Buchung nicht enthielt oder nicht änderbar war). [refundHint] ist der
 * Erstattungshinweis der Zahlung (WP-M2-31, `null` ohne Zahlung): [RefundHint.status] nennt, was beauftragt ist (nie „erstattet“, solange der PSP nicht bestätigt hat).
 */
data class CancelResult(val booking: BookingDetail, val releasedHoldId: UUID, val planRevision: Long?, val refundHint: RefundHint? = null)

/** Erstattungshinweis zum Storno: [status] (`NONE`, `VOID_REQUESTED`, `PENDING_CAPTURE`, `REQUESTED`, `CONFIRMED`, `FAILED`, `NOT_DUE`) und Betrag in kleinster Einheit. */
data class RefundHint(val status: String, val amountMinor: Long, val currency: String)

/** Anlass der Zahlungsreaktion beim Storno (WP-M2-31): Grund, Verursacherklasse (Erstattungsregel, BKG-014) und Initiator. */
data class CancellationEvent(val bookingId: UUID, val reason: BookingReason, val cause: CauseClass, val initiator: String)

/**
 * Port: Reaktion eines anderen Moduls auf einen Storno **in derselben Transaktion** (Zahlung: Void bzw. Erstattung beauftragen, WP-M2-31). Der Aufruf geschieht nach dem Übergang der Buchung und der
 * Freigabe der Reservierung; wirft er, rollt der ganze Storno zurück. Er ruft nie einen externen Dienst (Leitlinie 3), der PSP-Aufruf folgt als Operation.
 */
fun interface CancellationListener { fun onCancelled(j: org.springframework.jdbc.core.JdbcOperations, event: CancellationEvent): RefundHint? }

/** Wirksame Stornoregeln eines Dienstes (Planungsannahmen, wenn die Konfiguration keinen Wert nennt; [defaulted] nennt die Parameter mit Planungsdefault). */
data class CancellationPolicy(val deadlineMin: Int, val reasons: Set<BookingReason>, val defaulted: Set<String>) {
    companion object {
        const val DEFAULT_DEADLINE_MIN = 60
        val DEFAULT_REASONS: Set<BookingReason> = BookingReason.entries.filter { it.kind == BookingReason.Kind.CANCELLATION && it != BookingReason.CANCEL_COMPLETED && it != BookingReason.CANCEL_UNDETERMINED }.toSet()
    }
}

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

    /** Bezüge der Buchung für die Zahlung (Dienst, Journey, Quote, Zustand); Objektberechtigung wie [get]. */
    fun paymentContext(ctx: RequestContext, bookingId: UUID): BookingPaymentContext

    /** Eigene Buchungen (Fahrgast) bzw. Buchungen der Dienste des Scopes (Betrieb), aufsteigend nach `(created_at, id)`; [limit] + 1 Zeilen ab [after] (Seitenbildung beim Aufrufer). */
    fun list(ctx: RequestContext, limit: Int, after: Pair<Instant, UUID>?, area: BookingArea? = null): List<BookingDetail>

    /**
     * Lesesicht für UI-04 mit Haltepunkten, Plan-ETA aus der letzten Planänderung der Buchung, Fahrzeugidentifikation und Stornoregel (WP-M2-43). Dieselbe Objektberechtigung wie [get]:
     * fremde Buchung, fremder Dienst oder Mandant `404`; nie Personal- oder Fahrzeugakten.
     */
    fun tracking(ctx: RequestContext, bookingId: UUID): BookingTracking

    /**
     * Zustandsübergang einer Buchung durch Betrieb oder System (Zahlungsautomat, Abgleich, Fahrtabschluss), einziger Weg neben Confirm und Storno (WP-M2-25): Automat `booking`, Revision (CAS,
     * [expectedRevision] `null` = nur Zustand prüfen, nie überschreiben), **Grund aus dem Katalog** (passt er nicht zum Zielzustand: 422), Historieneintrag und Ereignis in derselben Transaktion;
     * unzulässiger Übergang `409 INVALID_STATE_TRANSITION` ohne Wirkung. Fahrgäste dürfen nur über Storno ändern (403); Objektberechtigung wie Lesen, schreibend (fremder Dienst: 404).
     */
    fun transition(ctx: RequestContext, bookingId: UUID, to: BookingState, reason: BookingReason, expectedRevision: Long? = null): BookingDetail

    /**
     * Storno (WP-M2-26, einstufig: OP-24-Annahme) in **einer** Transaktion unter dem Kapazitätslock des Umlaufs (Reihenfolge Umlauf, Hold, Buchung): Regelprüfung (Berechtigung je Grund, zulässiger
     * Grund und Frist des Dienstes, Zustand `CONFIRMING`/`CONFIRMED`, `If-Match`), Übergang der Buchung (`CANCEL_PENDING` bzw. `COMPENSATING` und danach `CANCELED`) mit Historieneintrag, Freigabe der
     * Reservierung (`CONSUMED` zu `RELEASED`, die Kapazität zählt auf allen Abschnitten und Dimensionen wieder), neue Planrevision ohne die Stopps der Buchung und Ereignis `booking.cancelled`.
     * Fahrgäste dürfen nur `PASSENGER_REQUEST` setzen (sonst 403) und nur innerhalb der Frist (`409 CANCEL_DEADLINE_EXPIRED`); Betrieb im Dienstscope ist an die Frist nicht gebunden.
     * Nicht zulässiger Grund des Dienstes 422, falscher Zustand `409 INVALID_STATE_TRANSITION`, veraltete Revision 412, fremde Buchung 404. Bei jedem Fehler bleibt alles unverändert.
     */
    fun cancel(ctx: RequestContext, bookingId: UUID, cmd: CancelBooking): CancelResult

    /**
     * Zahlungskopplung (WP-M2-30, OP-5) als Systemauftrag ohne Personenkontext (der Mandant kommt aus dem Auftrag, nie aus einer Eingabe): Zahlung `AUTHORIZED` bestätigt die Buchung
     * (`CONFIRMING` -> `CONFIRMED`, Grund `PAYMENT_AUTHORIZED`, Akteur `service:payment-coupling`). In jedem anderen Zustand ändert sich nichts ([CouplingOutcome.NOT_APPLICABLE]).
     */
    fun confirmAfterPayment(tenant: de.taktovia.platform.tenant.TenantId, bookingId: UUID): CouplingOutcome

    /**
     * Zahlungskopplung (WP-M2-30): Autorisierung abgelehnt, abgelaufen oder aufgehoben: eine Buchung im Zustand `CONFIRMING` wird mit Grund `PAYMENT_FAILED` storniert (Hold und Kapazität werden
     * frei, Ereignis `booking.cancelled`). Eine bestätigte oder bereits anders beendete Buchung bleibt unberührt ([CouplingOutcome.NOT_APPLICABLE]).
     */
    fun cancelAfterPaymentFailure(tenant: de.taktovia.platform.tenant.TenantId, bookingId: UUID): CouplingOutcome

    /** Statushistorie in der [view]; die Prüfersicht ist Fahrgästen verwehrt (403), fremde Buchungen und Dienste ohne Scope sind 404. */
    fun history(ctx: RequestContext, bookingId: UUID, view: HistoryView): List<BookingHistoryEntry>

    companion object {
        fun create(ctxTx: ContextTransactions, outbox: Outbox, quotes: FareQuotes, clock: Clock = Clock.systemUTC(), config: EffectiveConfig? = null, cancellation: CancellationListener? = null,
            executionStates: ExecutionStateSource = ExecutionStateSource.NONE, staffing: de.taktovia.platform.readports.ShiftStaffingReadPort? = null): Bookings =
            BookingServicesFactory.bookings(ctxTx, outbox, quotes, clock, config, cancellation, executionStates, staffing)

        /** ImpactProvider „Buchungen“ (von WP-M1-23 erwartet): bestätigte Buchungen eines Dienstes ab dem Wirksamkeitsbeginn einer Dienstkonfigurationsrevision. Nur Verweise, nie Personendaten. */
        fun impactProvider(): ImpactProvider = BookingServicesFactory.bookingImpactProvider()
    }
}

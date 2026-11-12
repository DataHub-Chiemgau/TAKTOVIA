package de.taktovia.farepayment.api

import de.taktovia.farepayment.internal.FarepaymentServicesFactory
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.operation.OperationHandler
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.statemachine.PaymentState
import de.taktovia.platform.tenant.TenantTransactions
import org.springframework.jdbc.core.JdbcOperations
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Minimalregel P1 für die Erstattungshöhe je Stornogrund (WP-M2-31, **Testregel bis zur Freigabe D-05**): Anteil des erfassten Betrags in Prozent. Kundenstorno (Verursacher `FAHRGAST`, in der Frist
 * der Dienstregel, die der Storno selbst prüft) [customerPercent]; betreiberverursacht (`BETREIBER`, `FAHRZEUGAUSFALL`: **die betreiberverursachte Regel, nie die Kundenstornoregel**, BKG-014) [operatorPercent];
 * No-Show [noShowPercent]. Die vollständigen Regeln nach Zeitpunkt, Grund und Zahlungslage sind PAY-006 (P2).
 */
data class RefundRule(val customerPercent: Int = 100, val operatorPercent: Int = 100, val noShowPercent: Int = 0) {
    init { require(listOf(customerPercent, operatorPercent, noShowPercent).all { it in 0..100 }) }
}

/** Planungsdefaults des Zahlungsautomaten (D-03/D-05: Zahlenwerte sind Annahmen, kein Katalogparameter). */
data class PaymentConfig(
    val provider: String,
    /** Frist, in der eine Zahlung für eine Buchung im Zustand `CONFIRMING` autorisiert sein muss (Hold-Dauer 180 s, `m2-detailvertraege.md` §11). Danach wird eine später eintreffende Autorisierung aufgehoben. */
    val authorizationWindow: Duration = Duration.ofSeconds(180),
    /** Frist der Zahlungsoperationen (`operation.deadline_at`). */
    val operationDeadline: Duration = Duration.ofMinutes(15),
    /** Verzögerung des nächsten Abgleichs eines beim PSP noch schwebenden Einzugs. */
    val settlementPoll: Duration = Duration.ofSeconds(30),
    /** Erstattungsregel (WP-M2-31). */
    val refund: RefundRule = RefundRule(),
)

/**
 * Sicht auf die Zahlung: **getrennt vom Buchungsstatus** gespeichert und gelesen (PAY-004). [state] ist der interne Zahlungszustand, [status] die UI-Projektion (Kap. 05 §5.9): interne
 * `CAPTURE_UNKNOWN`/`RECONCILING` sowie eine unbestimmte Autorisierung (Zustand `CREATED` mit Marker, Abschnitt Autorisierungs-Timeout in `zahlung-ablauf.md`) erscheinen als [PaymentUiStatus.UNKNOWN];
 * `REFUND_PENDING` erscheint als bisheriger Stand (`CAPTURED` bzw. `PARTIALLY_REFUNDED`) mit [refundOpenMinor] > 0.
 */
data class PaymentView(
    val id: UUID, val bookingId: UUID?, val journeyId: UUID, val state: PaymentState, val status: PaymentUiStatus, val currency: String,
    val requestedMinor: Long?, val authorizedMinor: Long, val capturedMinor: Long, val refundedMinor: Long,
    /** Betrag der beauftragten, noch nicht vom PSP bestätigten Erstattung (WP-M2-31); 0 = keine offene Erstattung. */
    val refundOpenMinor: Long, val failureCode: String?, val revision: Long, val authorizeBy: Instant?,
    /** Die laufende bzw. letzte Zahlungsoperation (`GET /v1/operations/{id}`), soweit vorhanden. */
    val operationId: UUID?,
    /** Der letzte Erstattungsfall (WP-M2-31); `null`, wenn keiner besteht. */
    val refund: RefundView? = null,
)

/** Zustand einer Erstattung: `REQUESTED` beauftragt (noch nicht vom PSP bestätigt, dem Fahrgast „Erstattung beauftragt“), `CONFIRMED` bestätigt, `FAILED` abgelehnt (Betrag bleibt offen), `NOT_DUE` kein Anspruch. */
data class RefundView(val id: UUID, val paymentId: UUID, val state: String, val reasonCode: String, val percent: Int, val requestedMinor: Long?, val refundedMinor: Long, val failureCode: String?)

/** Erstattungshinweis zum Storno (WP-M2-31): [status] [RefundStatus], [amountMinor] der beauftragte (bzw. bei `VOID_REQUESTED` der aufgehobene) Betrag. */
data class RefundHint(val status: RefundStatus, val amountMinor: Long, val currency: String)

enum class RefundStatus { NONE, VOID_REQUESTED, PENDING_CAPTURE, REQUESTED, CONFIRMED, FAILED, NOT_DUE }

/** Der Storno einer Buchung (Anlass der Rückführung): Grund und Verursacherklasse nach `BookingReason`/`CauseClass`. */
data class CancelledBooking(val bookingId: UUID, val reasonCode: String, val causeClass: String)

/** Beauftragung einer Erstattung zu einer Zahlung. [caseKey] ist der fachliche Schlüssel des Anlasses (eine Erstattung je Zahlung und Schlüssel, Wiederholung liefert den vorhandenen Fall); [amountMinor] `null` = nach Regel. */
data class RefundRequest(val paymentId: UUID, val caseKey: UUID, val reasonCode: String, val causeClass: String, val amountMinor: Long? = null)

enum class PaymentUiStatus { CREATED, REQUIRES_ACTION, AUTHORIZED, AUTHORIZATION_FAILED, CAPTURE_PENDING, CAPTURED, VOID_PENDING, VOIDED, PARTIALLY_REFUNDED, REFUNDED, UNKNOWN }

/**
 * Beginn der Zahlung für eine Buchung im Zustand `CONFIRMING` (Zahlungsauswahl im Checkout). [amountMinor] muss **exakt** `fare_quote.pay_at_platform_minor` sein (sonst `409 REQUOTE_REQUIRED`);
 * [paymentMethodRef] ist ein vom PSP gelieferter Verweis, nie Kartendaten (SEC-008); [authorizeBy] die Frist (Hold-Ablauf), danach `409 HOLD_EXPIRED`.
 */
data class BeginPayment(
    val bookingId: UUID, val serviceId: UUID, val journeyId: UUID, val quoteId: UUID, val amountMinor: Long, val currency: String, val paymentMethodRef: String, val authorizeBy: Instant,
)

/** Ergebnis von [Payments.begin]: [replayed] = für die Buchung läuft bereits eine wirksame Zahlung (kein zweiter Einzug), geliefert wird diese. */
data class BegunPayment(val payment: PaymentView, val operationId: UUID, val replayed: Boolean)

/** Anstehende Kopplung einer Zahlung an ihre Buchung (explizite Fachoperation statt gemeinsamer Zustandsfelder). */
data class CouplingTask(val paymentId: UUID, val bookingId: UUID, val serviceId: UUID, val kind: CouplingKind, val state: PaymentState)

enum class CouplingKind {
    /** Zahlung `AUTHORIZED`: die Buchung wird bestätigt, danach wird der Einzug angestoßen (OP-5). */
    CONFIRM_BOOKING,
    /** Autorisierung abgelehnt, abgelaufen oder aufgehoben: die Buchung wird mit Grund `PAYMENT_FAILED` storniert (Hold-Kapazität wird frei); die Zahlung ist nie eingezogen. */
    RELEASE_BOOKING,
}

/**
 * Zahlungsautomat unabhängig vom Buchungsstatus (WP-M2-30, LH-6.10-PAY-004). Der Zahlstatus steht ausschließlich in `payment_intent.state` (Automat `payment`), der Buchungsstatus in `booking.state`;
 * dieses Modul schreibt nie eine Buchung und liest ihren Zustand nicht. Die Kopplung läuft über [CouplingTask]s (Fachoperationen der Anwendung) und Outbox-Ereignisse
 * (`payment.authorized`, `payment.authorization_failed`, `payment.captured`, `payment.outcome_unknown`).
 *
 * **PSP-Aufrufe** laufen nur in den [OperationHandler]n ([handlers]) über den [PaymentGateway], nie in einer SQL-Transaktion; jeder Aufruf trägt eine stabile Kommandoreferenz
 * (`payment_operation.command_ref`), sodass jeder Wiederholungsversuch denselben Idempotenzschlüssel benutzt (kein zweiter Einzug). Unbestimmte Ergebnisse (Timeout) bleiben unbestimmt
 * (`UNKNOWN`/`RECONCILING`, Leitlinie 6) und werden per Statusabfrage abgeglichen; ein PSP-Ereignis löst Übergänge erst nach dieser Statusabfrage aus.
 */
interface Payments {
    /** Zahlungsauswahl: legt die Zahlung (`CREATED`) und die Operation `AUTHORIZE_PAYMENT` in **einer** Transaktion an; kein PSP-Aufruf. */
    fun beginIn(j: JdbcOperations, cmd: BeginPayment): BegunPayment

    /** Rückkehr aus SCA/3-D-Secure (idempotent, auch doppelt): stellt einen Abgleich ein; der Zustand folgt erst dem verifizierten PSP-Status, nie der Rückkehr. Liefert die Operation. */
    fun scaReturnIn(j: JdbcOperations, bookingId: UUID): UUID?

    /** Eine PSP-Benachrichtigung zu [providerRef] ist gespeichert: stellt (höchstens einen offenen) Abgleich der Zahlung ein; unbekannte Referenz ohne Wirkung. */
    fun reactToProviderEventIn(j: JdbcOperations, provider: String, providerRef: String)

    /** Die aktuelle (wirksame, sonst letzte) Zahlung der Buchung; `null`, wenn keine begonnen wurde. */
    fun forBookingIn(j: JdbcOperations, bookingId: UUID): PaymentView?
    fun forBooking(ctx: RequestContext, bookingId: UUID): PaymentView?
    fun getIn(j: JdbcOperations, paymentId: UUID): PaymentView?

    /** Offene Kopplungen im Mandanten der Transaktion (Arbeitsliste der Anwendung); höchstens [limit]. */
    fun couplingTasksIn(j: JdbcOperations, limit: Int = 50): List<CouplingTask>

    /** Kopplung vollzogen: bei [CouplingKind.CONFIRM_BOOKING] wird der Einzug angestoßen (`AUTHORIZED` → `CAPTURE_PENDING`, Operation `CAPTURE_PAYMENT`), sonst nur markiert. Idempotent. */
    fun completeCouplingIn(j: JdbcOperations, paymentId: UUID, kind: CouplingKind)

    /** Die Buchung wurde nach Ablauf der Zahlungsfrist ohne wirksame Autorisierung freigegeben: eine später eintreffende Autorisierung wird aufgehoben (Marker `BOOKING_RELEASED`). */
    fun abandonIn(j: JdbcOperations, paymentId: UUID)

    /**
     * Die Buchung will die Zahlung nicht mehr (sie ist nicht mehr `CONFIRMING`, z. B. storniert): eine bestehende Autorisierung (`AUTHORIZED`) wird aufgehoben (`VOID_PENDING`, Operation
     * `VOID_PAYMENT`, Fehlercode `BOOKING_RELEASED`), eine noch ausstehende wird später aufgehoben; die Kopplung gilt als vollzogen. Idempotent.
     */
    fun releaseAuthorizationIn(j: JdbcOperations, paymentId: UUID)

    /**
     * Reaktion auf „Buchung storniert“ (WP-M2-31) in **derselben Transaktion** wie der Storno: bei `AUTHORIZED` ein Void (kein Einzug), bei `CAPTURED` eine Erstattung in der durch Stornoregel und Grund
     * bestimmten Höhe ([RefundRule]); beides wird nur **beauftragt** (Operation `VOID_PAYMENT` bzw. `REFUND_PAYMENT`), der PSP-Aufruf folgt später. „Erstattet“ gilt erst nach PSP-Bestätigung.
     * Läuft ein Einzug, wartet der Fall (`PENDING_CAPTURE`). Idempotent: ein wiederholtes Storno erzeugt keinen zweiten Fall. `null`, wenn die Buchung keine Zahlung hat.
     */
    fun onBookingCancelledIn(j: JdbcOperations, cancelled: CancelledBooking): RefundHint?

    /** Beauftragt eine Erstattung (Betrieb, Korrektur); ein Betrag über dem noch erstattbaren Rest ist `409 CONFLICT` (nie über dem erfassten Betrag, auch nicht unter parallelen Aufträgen). */
    fun requestRefundIn(j: JdbcOperations, request: RefundRequest): RefundView

    /** Die Operationshandler (`AUTHORIZE_PAYMENT`, `CAPTURE_PAYMENT`, `VOID_PAYMENT`, `REFUND_PAYMENT`, `RECONCILE_PAYMENT`); gegen den [gateway] des Zahlungsdienstleisters. */
    fun handlers(gateway: PaymentGateway): List<OperationHandler>

    companion object {
        fun create(tx: TenantTransactions, ctxTx: ContextTransactions, quotes: FareQuotes, outbox: Outbox, config: PaymentConfig, clock: Clock = Clock.systemUTC()): Payments =
            FarepaymentServicesFactory.payments(tx, ctxTx, quotes, outbox, config, clock)
    }
}

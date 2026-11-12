package de.taktovia.farepayment.api

import java.security.MessageDigest
import java.time.Duration

/**
 * Port des Zahlungsmoduls zum Zahlungsdienstleister (WP-M2-30). Das Modul kennt den Adapter `psp` nicht (Architekturregel: Module kennen keine Adapter); die Anwendung setzt diesen Port
 * mit dem PSP-Adapter um (`PspPaymentGateway`). **Nie innerhalb einer offenen SQL-Transaktion aufrufen** (Leitlinie 3): die Handler der Zahlungsoperationen rufen ihn ausschließlich außerhalb
 * von Transaktionen. Beträge sind [Long] in kleinster Einheit (Leitlinie 5); Kartendaten kennt der Port nicht (SEC-008).
 */
interface PaymentGateway {
    val provider: String
    fun authorize(cmd: GatewayAuthorize): GatewayOutcome
    fun capture(cmd: GatewayCapture): GatewayOutcome
    fun void(cmd: GatewayVoid): GatewayOutcome
    fun refund(cmd: GatewayRefund): GatewayOutcome
    /** Statusabfrage nach PSP-Zahlungs-ID oder PSP-Referenz. */
    fun status(paymentIdOrProviderRef: String): GatewayOutcome
    /** Statusabfrage nach der eigenen Händlerreferenz (Abgleich, wenn die Antwort auf Authorize verloren ging). */
    fun statusByMerchantRef(merchantRef: String): GatewayOutcome
    /** Abgleich einer unbestimmten Authorize-Operation (Händlerreferenz und Kommandoreferenz). */
    fun reconcileAuthorize(cmd: GatewayAuthorize): GatewayReconciliation
    /** Abgleich einer unbestimmten Capture-/Void-/Refund-Operation: Zahlung abfragen und die Kommandoreferenz in ihren Operationen suchen. */
    fun reconcile(action: PaymentAction, paymentId: String, commandRef: String): GatewayReconciliation
}

/** Art eines PSP-Aufrufs (Spiegel von `payment_operation.action`). */
enum class PaymentAction { AUTHORIZE, CAPTURE, VOID, REFUND, RECONCILE }

/** Zustand der Zahlung beim PSP (Kap. 03 §4.2, ohne die Aufruferzustände `CAPTURE_UNKNOWN`/`RECONCILING`). */
enum class GatewayState { CREATED, REQUIRES_ACTION, AUTHORIZED, CAPTURE_PENDING, CAPTURED, AUTHORIZATION_FAILED, VOID_PENDING, VOIDED, REFUND_PENDING, PARTIALLY_REFUNDED, REFUNDED }

data class GatewayPayment(
    val paymentId: String, val providerRef: String, val merchantRef: String, val state: GatewayState, val currency: String,
    val authorizedMinor: Long, val capturedMinor: Long, val refundedMinor: Long,
)

data class GatewayAuthorize(val commandRef: String, val merchantRef: String, val amountMinor: Long, val currency: String, val paymentMethodRef: String)
data class GatewayCapture(val commandRef: String, val paymentId: String, val amountMinor: Long)
data class GatewayVoid(val commandRef: String, val paymentId: String)
data class GatewayRefund(val commandRef: String, val paymentId: String, val amountMinor: Long)

/**
 * Ergebnis eines PSP-Aufrufs. **[Unknown] ist kein Misserfolg** (Leitlinie 6): Timeout oder abgebrochene Verbindung lassen offen, ob der PSP die Operation ausgeführt hat; aufgelöst wird nur
 * über die Statusabfrage mit der Kommandoreferenz oder die Wiederholung mit **derselben** Referenz.
 */
sealed interface GatewayOutcome {
    data class Ok(val payment: GatewayPayment) : GatewayOutcome
    /** Definitive Ablehnung durch den Zahlungsdienstleister. Kein Effekt. */
    data class Declined(val code: String) : GatewayOutcome
    /** Definitiv unzulässige Anfrage (Betrag über Autorisierung, unbekannte Referenz). Kein Effekt. */
    data class Rejected(val code: String) : GatewayOutcome
    /** Zustand der Zahlung beim PSP erlaubt die Operation nicht (oder sie läuft noch): abgleichen. */
    data class Conflict(val code: String) : GatewayOutcome
    /** Vor der Ausführung nicht erreichbar (Verbindung verweigert, 429, 503): kein Effekt, Wiederholung mit derselben Referenz ist sicher. */
    data class Unavailable(val retryAfter: Duration?) : GatewayOutcome
    data class Unknown(val reason: String) : GatewayOutcome
    /** Die Zahlung ist dem PSP unbekannt (Statusabfrage, HTTP 404). */
    data object NotFound : GatewayOutcome
}

sealed interface GatewayReconciliation {
    /** Der PSP hat die Operation mit dieser Kommandoreferenz ausgeführt; [payment] ist der aktuelle Stand. */
    data class Performed(val payment: GatewayPayment) : GatewayReconciliation
    /** Der PSP kennt die Operation nicht: Wiederholung mit **derselben** Kommandoreferenz ist sicher. */
    data class NotPerformed(val payment: GatewayPayment?) : GatewayReconciliation
    /** Auch die Statusabfrage ist gescheitert: weiter unbestimmt, nie ein zweiter Charge auf Verdacht. */
    data class Undetermined(val reason: String) : GatewayReconciliation
}

/** Stabile Kommandoreferenz je **logischem** PSP-Aufruf: deterministisch aus Zahlung, Art und Folgenummer (gleiche Eingabe, gleiche Referenz, auch nach Prozessneustart); nennt weder Mandant noch Person. */
object CommandRefs {
    fun derive(businessKey: String, action: PaymentAction, sequence: Int = 1): String {
        require(businessKey.isNotBlank() && sequence >= 1)
        val h = MessageDigest.getInstance("SHA-256").digest("$businessKey|${action.name}|$sequence".toByteArray()).joinToString("") { "%02x".format(it) }
        return "cmd-${action.name.lowercase()}-${h.take(32)}"
    }
}

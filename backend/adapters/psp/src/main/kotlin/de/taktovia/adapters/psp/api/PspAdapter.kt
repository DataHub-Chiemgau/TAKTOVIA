package de.taktovia.adapters.psp.api

import de.taktovia.adapters.psp.internal.HttpPspAdapter
import java.net.URI
import java.security.MessageDigest
import java.time.Duration

/**
 * Stabile eigene Kommandoreferenz je **logischem** PSP-Aufruf (WP-M2-29, Leitlinie 6). Sie dient dem PSP als Idempotenzschlüssel und als Operationsreferenz; ein Retry
 * (nach Timeout, verlorener Antwort, 503) verwendet **dieselbe** Referenz und löst daher nie einen zweiten Charge aus. Eine neue Referenz entsteht nur für einen neuen fachlichen Vorgang.
 */
@JvmInline
value class PspCommandRef(val value: String) {
    init { require(FORMAT.matches(value)) { "ungültige Kommandoreferenz" } }
    override fun toString() = value

    companion object {
        private val FORMAT = Regex("[A-Za-z0-9._:-]{8,128}")

        /** Deterministisch aus Vorgang (z. B. `operation`-ID oder Zahlung) und Art: gleiche Eingabe, gleiche Referenz (auch nach Prozessneustart); nennt weder Mandant noch Person. */
        fun derive(businessKey: String, operation: PspOperation, sequence: Int = 1): PspCommandRef {
            require(businessKey.isNotBlank() && sequence >= 1)
            val h = MessageDigest.getInstance("SHA-256").digest("$businessKey|${operation.name}|$sequence".toByteArray()).joinToString("") { "%02x".format(it) }
            return PspCommandRef("cmd-${operation.name.lowercase()}-${h.take(32)}")
        }
    }
}

enum class PspOperation { AUTHORIZE, CAPTURE, VOID, REFUND, STATUS }

/** Zustand der Zahlung beim PSP (Kap. 03 §4.2, ohne die Aufruferzustände `CAPTURE_UNKNOWN`/`RECONCILING`; `DISPUTED` ist [PspPayment.disputeState]). */
enum class PspPaymentState { CREATED, REQUIRES_ACTION, AUTHORIZED, CAPTURE_PENDING, CAPTURED, AUTHORIZATION_FAILED, VOID_PENDING, VOIDED, REFUND_PENDING, PARTIALLY_REFUNDED, REFUNDED }

/** Eine beim PSP ausgeführte Operation (für den Abgleich unbestimmter Ergebnisse über die Kommandoreferenz). */
data class PspOperationRecord(val kind: PspOperation, val commandRef: String?, val amountMinor: Long?, val result: String?)

/** Zahlung beim PSP. Beträge in kleinster Einheit (Leitlinie 5), `bigint`/[Long]. [replayed]: die Antwort stammt aus der Idempotenz-Wiederholung des PSP (kein zweiter Effekt). */
data class PspPayment(
    val paymentId: String, val providerRef: String, val merchantRef: String, val state: PspPaymentState, val currency: String,
    val authorizedMinor: Long, val capturedMinor: Long, val refundedMinor: Long, val disputeState: String? = null,
    val operations: List<PspOperationRecord> = emptyList(), val replayed: Boolean = false,
) {
    /** Hat der PSP eine Operation dieser Art mit dieser Kommandoreferenz ausgeführt? (Abgleich nach [PspOutcome.Unknown]). */
    fun performed(kind: PspOperation, ref: PspCommandRef) = operations.any { it.kind == kind && it.commandRef == ref.value }
}

enum class PspErrorClass {
    /** Definitive Ablehnung durch den Zahlungsdienstleister (z. B. Capture/Void/Refund abgelehnt). Kein Effekt. */
    DECLINED,
    /** Definitiv unzulässige Anfrage (Betrag über Autorisierung, unbekannte Referenz, ungültige Eingabe, Schlüssel mit anderem Inhalt wiederverwendet). Kein Effekt. */
    REJECTED,
    /** Zustand der Zahlung erlaubt die Operation nicht (z. B. Capture nach Void, Operation läuft noch). Kein Effekt. */
    CONFLICT,
}

/**
 * Ergebnis eines PSP-Aufrufs. **[Unknown] ist kein Misserfolg**: Timeout, abgebrochene Verbindung nach dem Senden oder ein unklarer 5xx lassen offen, ob der PSP die Operation ausgeführt hat. Aufgelöst wird
 * nur über die Statusabfrage mit der Kommandoreferenz oder durch Wiederholung mit **derselben** Referenz; ein zweiter Charge auf Verdacht ist verboten.
 */
sealed interface PspOutcome {
    data class Ok(val payment: PspPayment) : PspOutcome
    data class Failed(val errorClass: PspErrorClass, val code: String, val httpStatus: Int?) : PspOutcome
    /** Vor der Ausführung nicht erreichbar oder überlastet (Verbindung verweigert, 429, 503): kein Effekt, Wiederholung mit derselben Referenz ist sicher. */
    data class Unavailable(val retryAfter: Duration?) : PspOutcome
    /** Ergebnis unbestimmt (Fehlerklasse `UNKNOWN`): die Zahlung gilt als `CAPTURE_UNKNOWN`/Abgleich beim Aufrufer, nicht als gescheitert. */
    data class Unknown(val commandRef: PspCommandRef?, val reason: String) : PspOutcome
}

/** Authorize: [paymentMethodRef] ist ein vom PSP gelieferter Referenzwert (Token), nie Kartendaten (SEC-008). [merchantRef] ist die eigene Händlerreferenz (je Zahlung genau einmal). */
data class PspAuthorize(val commandRef: PspCommandRef, val merchantRef: String, val amountMinor: Long, val currency: String, val paymentMethodRef: String) {
    init { require(amountMinor >= 1) { "Betrag >= 1" }; require(Regex("[A-Z]{3}").matches(currency)); require(merchantRef.isNotBlank()) }
}

/** Capture ([amountMinor] `null` = der autorisierte Betrag). */
data class PspCapture(val commandRef: PspCommandRef, val paymentId: String, val amountMinor: Long? = null) { init { require(amountMinor == null || amountMinor >= 1) } }
data class PspVoid(val commandRef: PspCommandRef, val paymentId: String)
data class PspRefund(val commandRef: PspCommandRef, val paymentId: String, val amountMinor: Long) { init { require(amountMinor >= 1) } }

/**
 * Port des Adapters `psp` (WP-M2-29): Authorize, Capture, Void, Refund und Status. **Nie innerhalb einer offenen SQL-Transaktion aufrufen** (Leitlinie 3); der Adapter prüft das
 * ([de.taktovia.platform.tenant.ExternalCallGuard]) und scheitert vor dem Senden mit `IllegalStateException`. Rohe Zahlungsdaten kennt der Port nicht.
 */
interface PspAdapter {
    val provider: String
    fun authorize(cmd: PspAuthorize): PspOutcome
    fun capture(cmd: PspCapture): PspOutcome
    fun void(cmd: PspVoid): PspOutcome
    fun refund(cmd: PspRefund): PspOutcome
    /** Statusabfrage nach `payment_id` oder PSP-Referenz. */
    fun status(paymentIdOrProviderRef: String): PspOutcome
    /** Statusabfrage nach der ursprünglichen Händlerreferenz (Abgleich, wenn die Antwort auf Authorize verloren ging). */
    fun statusByMerchantRef(merchantRef: String): PspOutcome
    /** Statusabfrage nach Kommandoreferenz (Abgleich nach [PspOutcome.Unknown] einer Capture-/Void-/Refund-Operation). */
    fun statusByCommandRef(ref: PspCommandRef): PspOutcome

    companion object {
        /** Adapter für das HTTP/JSON-Protokoll des PSP-Ports (Port-Entwurf unter `tests/sandbox/psp/openapi`): der Simulator des Testnetzes, später die Sandbox des gewählten PSP (D-03). [apiKey] liefert bei Bedarf den Schlüssel (aus der Secret-Verwaltung, nie fest verdrahtet). */
        fun http(provider: String, baseUrl: URI, timeout: Duration = Duration.ofSeconds(10), apiKey: () -> String? = { null }): PspAdapter = HttpPspAdapter(provider, baseUrl, timeout, apiKey)
    }
}

/** Ergebnis des Abgleichs nach [PspOutcome.Unknown] (Kap. 03 §4.2, `RECONCILING`). */
sealed interface PspReconciliation {
    /** Der PSP hat die Operation mit dieser Kommandoreferenz ausgeführt; [payment] ist der aktuelle Stand. */
    data class Performed(val payment: PspPayment) : PspReconciliation
    /** Der PSP kennt die Operation nicht: Wiederholung mit **derselben** Kommandoreferenz ist sicher. [payment] ist der Stand der Zahlung, soweit vorhanden. */
    data class NotPerformed(val payment: PspPayment?) : PspReconciliation
    /** Auch die Statusabfrage ist gescheitert: weiter unbestimmt (später erneut, nie ein zweiter Charge auf Verdacht). */
    data class Undetermined(val reason: String) : PspReconciliation
}

/** Abgleich einer unbestimmten Authorize-Operation über die ursprüngliche Händlerreferenz. */
fun PspAdapter.reconcileAuthorize(cmd: PspAuthorize): PspReconciliation = when (val o = statusByMerchantRef(cmd.merchantRef)) {
    is PspOutcome.Ok -> if (o.payment.performed(PspOperation.AUTHORIZE, cmd.commandRef)) PspReconciliation.Performed(o.payment) else PspReconciliation.NotPerformed(o.payment)
    is PspOutcome.Failed -> if (o.httpStatus == 404) PspReconciliation.NotPerformed(null) else PspReconciliation.Undetermined(o.code)
    is PspOutcome.Unavailable -> PspReconciliation.Undetermined("unavailable")
    is PspOutcome.Unknown -> PspReconciliation.Undetermined(o.reason)
}

/** Abgleich einer unbestimmten Capture-/Void-/Refund-Operation: Zahlung abfragen und die Kommandoreferenz in ihren Operationen suchen. */
fun PspAdapter.reconcile(kind: PspOperation, paymentId: String, ref: PspCommandRef): PspReconciliation = when (val o = status(paymentId)) {
    is PspOutcome.Ok -> if (o.payment.performed(kind, ref)) PspReconciliation.Performed(o.payment) else PspReconciliation.NotPerformed(o.payment)
    is PspOutcome.Failed -> PspReconciliation.Undetermined(o.code)
    is PspOutcome.Unavailable -> PspReconciliation.Undetermined("unavailable")
    is PspOutcome.Unknown -> PspReconciliation.Undetermined(o.reason)
}

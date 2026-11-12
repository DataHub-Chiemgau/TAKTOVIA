package de.taktovia.farepayment

import de.taktovia.farepayment.api.GatewayAuthorize
import de.taktovia.farepayment.api.GatewayCapture
import de.taktovia.farepayment.api.GatewayOutcome
import de.taktovia.farepayment.api.GatewayPayment
import de.taktovia.farepayment.api.GatewayReconciliation
import de.taktovia.farepayment.api.GatewayRefund
import de.taktovia.farepayment.api.GatewayState
import de.taktovia.farepayment.api.GatewayVoid
import de.taktovia.farepayment.api.PaymentAction
import de.taktovia.farepayment.api.PaymentGateway
import java.time.Duration
import java.util.UUID

/** Simulierter Prozessabbruch zwischen PSP-Erfolg und lokalem Commit (nur Tests). */
class PayCrash : RuntimeException("simulierter Prozessabbruch")

/**
 * Skriptbarer Zahlungsdienstleister für die Modultests (WP-M2-30, **kein** Simulator-Ersatz: der echte PSP-Simulator läuft in den Anwendungstests `PaymentSimulatorFlowTest`). Bildet die
 * Eigenschaften nach, auf die der Automat angewiesen ist: Idempotenz über die Kommandoreferenz (eine Wiederholung hat nie einen zweiten Effekt), unbestimmte Ergebnisse (Timeout ohne Effekt,
 * verlorene Antwort mit Effekt), Abgleich über Händler- und Kommandoreferenz, schwebende Verbuchung und SCA. [effects] zählt echte Wirkungen je Aktion (z. B. Belastungen).
 */
class PayScriptedPsp(override val provider: String = "scriptpsp") : PaymentGateway {
    enum class Fault { TIMEOUT_BEFORE_EFFECT, LOST_RESPONSE, CRASH_AFTER_EFFECT, DECLINE, UNAVAILABLE }
    enum class AuthMode { OK, SCA, DECLINE }

    class Pay(val paymentId: String, val providerRef: String, val merchantRef: String, var state: GatewayState, val currency: String, var authorized: Long, var captured: Long = 0, var refunded: Long = 0) {
        val performed = mutableListOf<Pair<PaymentAction, String>>()
        fun view() = GatewayPayment(paymentId, providerRef, merchantRef, state, currency, authorized, captured, refunded)
    }

    private val lock = Any()
    private val byId = LinkedHashMap<String, Pay>()
    private val replay = HashMap<String, GatewayOutcome>()
    private val faults = HashMap<PaymentAction, ArrayDeque<Fault>>()
    private val effectCounts = HashMap<PaymentAction, Int>()
    private val callCounts = HashMap<PaymentAction, Int>()
    @Volatile var authMode = AuthMode.OK
    /** `true`: Capture/Void/Refund werden erst mit [settle] wirksam (Zustand `*_PENDING` beim PSP). */
    @Volatile var delayedSettlement = false
    /** `true`: auch die Statusabfrage scheitert (Abgleich bleibt unbestimmt). */
    @Volatile var statusDown = false
    /** Hook vor dem Beginn eines Aufrufs (z. B. um zwei Worker zu verschränken). */
    @Volatile var beforeCall: (PaymentAction) -> Unit = {}
    /** Betrag, den der PSP bei Authorize tatsächlich autorisiert (`null` = angefragter Betrag). */
    @Volatile var authorizeAmountOverride: Long? = null

    fun inject(action: PaymentAction, vararg f: Fault) { synchronized(lock) { faults.getOrPut(action) { ArrayDeque() }.addAll(f) } }
    fun effects(a: PaymentAction): Int = synchronized(lock) { effectCounts[a] ?: 0 }
    fun calls(a: PaymentAction): Int = synchronized(lock) { callCounts[a] ?: 0 }
    fun payments(): List<Pay> = synchronized(lock) { byId.values.toList() }
    fun only(): Pay = payments().single()

    /** Verzögerte Verbuchung abschließen. */
    fun settle() = synchronized(lock) { byId.values.forEach { p -> when (p.state) { GatewayState.CAPTURE_PENDING -> p.state = GatewayState.CAPTURED; GatewayState.VOID_PENDING -> p.state = GatewayState.VOIDED
        GatewayState.REFUND_PENDING -> p.state = if (p.refunded == p.captured) GatewayState.REFUNDED else GatewayState.PARTIALLY_REFUNDED; else -> {} } } }

    /** Der Kunde schließt SCA ab (nach dem Zeitfenster eines Holds). */
    fun completeSca(success: Boolean = true) = synchronized(lock) { byId.values.filter { it.state == GatewayState.REQUIRES_ACTION }.forEach { it.state = if (success) GatewayState.AUTHORIZED else GatewayState.AUTHORIZATION_FAILED } }

    private fun next(a: PaymentAction): Fault? = faults[a]?.removeFirstOrNull()

    private fun run(a: PaymentAction, ref: String, effect: () -> GatewayOutcome): GatewayOutcome {
        beforeCall(a)
        synchronized(lock) {
            callCounts[a] = (callCounts[a] ?: 0) + 1
            val fault = next(a)
            when (fault) {
                Fault.UNAVAILABLE -> return GatewayOutcome.Unavailable(Duration.ofSeconds(1))
                Fault.TIMEOUT_BEFORE_EFFECT -> return GatewayOutcome.Unknown("timeout")
                else -> {}
            }
            replay[ref]?.let { return it }   // Idempotenz des PSP: gleiche Kommandoreferenz, gleiche Antwort, kein zweiter Effekt
            val result = if (fault == Fault.DECLINE) GatewayOutcome.Declined("DECLINED_BY_PROVIDER") else effect()
            if (result is GatewayOutcome.Ok) { effectCounts[a] = (effectCounts[a] ?: 0) + 1 }
            if (result !is GatewayOutcome.Conflict) replay[ref] = result
            if (fault == Fault.LOST_RESPONSE) return GatewayOutcome.Unknown("lost_response")
            if (fault == Fault.CRASH_AFTER_EFFECT) throw PayCrash()
            return result
        }
    }

    private fun find(id: String): Pay? = byId[id] ?: byId.values.firstOrNull { it.providerRef == id }

    override fun authorize(cmd: GatewayAuthorize): GatewayOutcome = run(PaymentAction.AUTHORIZE, cmd.commandRef) {
        if (byId.values.any { it.merchantRef == cmd.merchantRef }) return@run GatewayOutcome.Conflict("DUPLICATE_MERCHANT_REF")
        val amount = authorizeAmountOverride ?: cmd.amountMinor
        val p = Pay("pay_" + UUID.randomUUID().toString().take(8), "psp_" + UUID.randomUUID().toString().take(8), cmd.merchantRef,
            when (authMode) { AuthMode.OK -> GatewayState.AUTHORIZED; AuthMode.SCA -> GatewayState.REQUIRES_ACTION; AuthMode.DECLINE -> GatewayState.AUTHORIZATION_FAILED }, cmd.currency, amount)
        p.performed += PaymentAction.AUTHORIZE to cmd.commandRef
        byId[p.paymentId] = p
        GatewayOutcome.Ok(p.view())
    }

    override fun capture(cmd: GatewayCapture): GatewayOutcome = run(PaymentAction.CAPTURE, cmd.commandRef) {
        val p = find(cmd.paymentId) ?: return@run GatewayOutcome.Rejected("UNKNOWN_REFERENCE")
        if (p.state != GatewayState.AUTHORIZED) return@run GatewayOutcome.Conflict("INVALID_STATE")
        if (cmd.amountMinor > p.authorized) return@run GatewayOutcome.Rejected("AMOUNT_EXCEEDS_AUTHORIZED")
        p.captured = cmd.amountMinor; p.state = if (delayedSettlement) GatewayState.CAPTURE_PENDING else GatewayState.CAPTURED
        p.performed += PaymentAction.CAPTURE to cmd.commandRef
        GatewayOutcome.Ok(p.view())
    }

    override fun void(cmd: GatewayVoid): GatewayOutcome = run(PaymentAction.VOID, cmd.commandRef) {
        val p = find(cmd.paymentId) ?: return@run GatewayOutcome.Rejected("UNKNOWN_REFERENCE")
        if (p.state != GatewayState.AUTHORIZED) return@run GatewayOutcome.Conflict("INVALID_STATE")
        p.state = if (delayedSettlement) GatewayState.VOID_PENDING else GatewayState.VOIDED
        p.performed += PaymentAction.VOID to cmd.commandRef
        GatewayOutcome.Ok(p.view())
    }

    override fun refund(cmd: GatewayRefund): GatewayOutcome = run(PaymentAction.REFUND, cmd.commandRef) {
        val p = find(cmd.paymentId) ?: return@run GatewayOutcome.Rejected("UNKNOWN_REFERENCE")
        if (p.state != GatewayState.CAPTURED && p.state != GatewayState.PARTIALLY_REFUNDED) return@run GatewayOutcome.Conflict("INVALID_STATE")
        if (p.refunded + cmd.amountMinor > p.captured) return@run GatewayOutcome.Rejected("AMOUNT_EXCEEDS_CAPTURED")
        p.refunded += cmd.amountMinor
        p.state = if (delayedSettlement) GatewayState.REFUND_PENDING else if (p.refunded == p.captured) GatewayState.REFUNDED else GatewayState.PARTIALLY_REFUNDED
        p.performed += PaymentAction.REFUND to cmd.commandRef
        GatewayOutcome.Ok(p.view())
    }

    override fun status(paymentIdOrProviderRef: String): GatewayOutcome = synchronized(lock) {
        if (statusDown) return GatewayOutcome.Unknown("status down")
        find(paymentIdOrProviderRef)?.let { GatewayOutcome.Ok(it.view()) } ?: GatewayOutcome.NotFound
    }

    override fun statusByMerchantRef(merchantRef: String): GatewayOutcome = synchronized(lock) {
        if (statusDown) return GatewayOutcome.Unknown("status down")
        byId.values.firstOrNull { it.merchantRef == merchantRef }?.let { GatewayOutcome.Ok(it.view()) } ?: GatewayOutcome.NotFound
    }

    override fun reconcileAuthorize(cmd: GatewayAuthorize): GatewayReconciliation = when (val o = statusByMerchantRef(cmd.merchantRef)) {
        is GatewayOutcome.Ok -> if (synchronized(lock) { find(o.payment.paymentId)!!.performed.contains(PaymentAction.AUTHORIZE to cmd.commandRef) }) GatewayReconciliation.Performed(o.payment) else GatewayReconciliation.NotPerformed(o.payment)
        GatewayOutcome.NotFound -> GatewayReconciliation.NotPerformed(null)
        else -> GatewayReconciliation.Undetermined("status")
    }

    override fun reconcile(action: PaymentAction, paymentId: String, commandRef: String): GatewayReconciliation = when (val o = status(paymentId)) {
        is GatewayOutcome.Ok -> if (synchronized(lock) { find(paymentId)!!.performed.contains(action to commandRef) }) GatewayReconciliation.Performed(o.payment) else GatewayReconciliation.NotPerformed(o.payment)
        else -> GatewayReconciliation.Undetermined("status")
    }
}

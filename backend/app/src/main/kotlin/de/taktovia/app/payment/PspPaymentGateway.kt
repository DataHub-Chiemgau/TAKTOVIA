package de.taktovia.app.payment

import de.taktovia.adapters.psp.api.PspAdapter
import de.taktovia.adapters.psp.api.PspAuthorize
import de.taktovia.adapters.psp.api.PspCapture
import de.taktovia.adapters.psp.api.PspCommandRef
import de.taktovia.adapters.psp.api.PspErrorClass
import de.taktovia.adapters.psp.api.PspOperation
import de.taktovia.adapters.psp.api.PspOutcome
import de.taktovia.adapters.psp.api.PspPayment
import de.taktovia.adapters.psp.api.PspReconciliation
import de.taktovia.adapters.psp.api.PspRefund
import de.taktovia.adapters.psp.api.PspVoid
import de.taktovia.adapters.psp.api.reconcile
import de.taktovia.adapters.psp.api.reconcileAuthorize
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

/**
 * Setzt den Zahlungsport des Moduls `fare-payment` mit dem PSP-Adapter um (WP-M2-30). Die Zuordnung ist eins zu eins: die Fehlerklassen des Adapters (`DECLINED`, `REJECTED`, `CONFLICT`,
 * `Unavailable`, `Unknown`) bleiben unterscheidbar, damit ein unbestimmtes Ergebnis nie zu Erfolg oder Fehler verkürzt wird (Leitlinie 6). Aufrufe nie in einer SQL-Transaktion: der Adapter prüft das selbst.
 */
class PspPaymentGateway(private val psp: PspAdapter) : PaymentGateway {
    override val provider: String get() = psp.provider

    override fun authorize(cmd: GatewayAuthorize) = map(psp.authorize(toPsp(cmd)))
    override fun capture(cmd: GatewayCapture) = map(psp.capture(PspCapture(PspCommandRef(cmd.commandRef), cmd.paymentId, cmd.amountMinor)))
    override fun void(cmd: GatewayVoid) = map(psp.void(PspVoid(PspCommandRef(cmd.commandRef), cmd.paymentId)))
    override fun refund(cmd: GatewayRefund) = map(psp.refund(PspRefund(PspCommandRef(cmd.commandRef), cmd.paymentId, cmd.amountMinor)))
    override fun status(paymentIdOrProviderRef: String) = map(psp.status(paymentIdOrProviderRef))
    override fun statusByMerchantRef(merchantRef: String) = map(psp.statusByMerchantRef(merchantRef))

    override fun reconcileAuthorize(cmd: GatewayAuthorize): GatewayReconciliation = map(psp.reconcileAuthorize(toPsp(cmd)))

    override fun reconcile(action: PaymentAction, paymentId: String, commandRef: String): GatewayReconciliation {
        val kind = when (action) {
            PaymentAction.CAPTURE -> PspOperation.CAPTURE
            PaymentAction.VOID -> PspOperation.VOID
            PaymentAction.REFUND -> PspOperation.REFUND
            else -> throw IllegalArgumentException("kein Abgleich für $action")
        }
        return map(psp.reconcile(kind, paymentId, PspCommandRef(commandRef)))
    }

    private fun toPsp(c: GatewayAuthorize) = PspAuthorize(PspCommandRef(c.commandRef), c.merchantRef, c.amountMinor, c.currency, c.paymentMethodRef)

    private fun map(o: PspOutcome): GatewayOutcome = when (o) {
        is PspOutcome.Ok -> GatewayOutcome.Ok(map(o.payment))
        is PspOutcome.Failed -> when {
            o.httpStatus == 404 -> GatewayOutcome.NotFound
            o.errorClass == PspErrorClass.DECLINED -> GatewayOutcome.Declined(o.code)
            o.errorClass == PspErrorClass.REJECTED -> GatewayOutcome.Rejected(o.code)
            else -> GatewayOutcome.Conflict(o.code)
        }
        is PspOutcome.Unavailable -> GatewayOutcome.Unavailable(o.retryAfter)
        is PspOutcome.Unknown -> GatewayOutcome.Unknown(o.reason)
    }

    private fun map(r: PspReconciliation): GatewayReconciliation = when (r) {
        is PspReconciliation.Performed -> GatewayReconciliation.Performed(map(r.payment))
        is PspReconciliation.NotPerformed -> GatewayReconciliation.NotPerformed(r.payment?.let(::map))
        is PspReconciliation.Undetermined -> GatewayReconciliation.Undetermined(r.reason)
    }

    private fun map(p: PspPayment) = GatewayPayment(p.paymentId, p.providerRef, p.merchantRef, GatewayState.valueOf(p.state.name), p.currency, p.authorizedMinor, p.capturedMinor, p.refundedMinor)
}

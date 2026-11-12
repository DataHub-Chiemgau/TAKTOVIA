package de.taktovia.farepayment.internal

import de.taktovia.farepayment.api.GatewayAuthorize
import de.taktovia.farepayment.api.GatewayCapture
import de.taktovia.farepayment.api.GatewayOutcome
import de.taktovia.farepayment.api.GatewayPayment
import de.taktovia.farepayment.api.GatewayReconciliation
import de.taktovia.farepayment.api.GatewayRefund
import de.taktovia.farepayment.api.GatewayState
import de.taktovia.farepayment.api.GatewayVoid
import de.taktovia.farepayment.api.PaymentAction
import de.taktovia.farepayment.api.PaymentConfig
import de.taktovia.farepayment.api.PaymentGateway
import de.taktovia.platform.operation.OperationContext
import de.taktovia.platform.operation.OperationHandler
import de.taktovia.platform.operation.OperationOutcome
import de.taktovia.platform.operation.OperationRetryException
import de.taktovia.platform.statemachine.PaymentState
import de.taktovia.platform.tenant.TenantTransactions
import java.time.Duration

/**
 * Gemeinsame Hilfen der Operationshandler. **PSP-Aufrufe geschehen ausschließlich hier und nie in einer SQL-Transaktion** (Leitlinie 3): erst lesen (eigene kurze Transaktion), dann der Aufruf mit
 * der stabilen Kommandoreferenz, dann die lokale Wirkung zusammen mit dem gefencten Abschluss der Operation (`OperationContext.commit`). Eine Wirkung tritt so genau einmal ein, auch wenn zwei
 * Worker dieselbe Operation versuchen (der Fencing-Token lässt nur einen schreiben) und der PSP den zweiten Aufruf anhand der Kommandoreferenz als Wiederholung erkennt.
 */
internal abstract class PaymentHandler(override val kind: String, protected val tx: TenantTransactions, protected val core: PaymentCore, protected val gateway: PaymentGateway, protected val config: PaymentConfig) : OperationHandler {
    protected class Loaded(val op: POp, val payment: PRow)

    protected fun load(ctx: OperationContext): Loaded = tx.run(ctx.tenant, readOnly = true) { j ->
        val op = core.op(j, ctx.operationId)
        Loaded(op, core.find(j, op.paymentId) ?: error("Zahlung zur Operation fehlt"))
    }

    protected fun authorizeCommand(l: Loaded) = GatewayAuthorize(l.op.commandRef, l.payment.merchantRef!!, l.payment.requested!!, l.payment.currency, l.payment.paymentMethodRef!!)

    protected fun settleDelay(): Duration = config.settlementPoll
}

/** `AUTHORIZE_PAYMENT`: Autorisierung beim PSP; Timeout bleibt unbestimmt und wird über die Händlerreferenz abgeglichen (AT-15). */
internal class AuthorizeHandler(tx: TenantTransactions, core: PaymentCore, gateway: PaymentGateway, config: PaymentConfig) : PaymentHandler("AUTHORIZE_PAYMENT", tx, core, gateway, config) {
    override fun execute(ctx: OperationContext): OperationOutcome {
        val l = load(ctx)
        if (l.payment.state != PaymentState.CREATED) return OperationOutcome.Succeeded("ALREADY_APPLIED")   // Wiederholung nach Lease-Übernahme: die Wirkung ist bereits eingetreten
        return outcome(ctx, l, gateway.authorize(authorizeCommand(l)))
    }

    /** Abgleich: hat der PSP die Autorisierung mit dieser Kommandoreferenz ausgeführt, wird ihr Ergebnis übernommen; sonst wird mit **derselben** Referenz wiederholt (nie ein zweiter Charge). */
    override fun reconcile(ctx: OperationContext): OperationOutcome {
        val l = load(ctx)
        if (l.payment.state != PaymentState.CREATED) return OperationOutcome.Succeeded("ALREADY_APPLIED")
        val cmd = authorizeCommand(l)
        return when (val r = gateway.reconcileAuthorize(cmd)) {
            is GatewayReconciliation.Performed -> ctx.commit(r.payment.state.name) { j -> core.observe(j, l.payment.id, r.payment) }
            is GatewayReconciliation.NotPerformed -> outcome(ctx, l, gateway.authorize(cmd))
            is GatewayReconciliation.Undetermined -> OperationOutcome.Unknown("AUTHORIZE_OUTCOME_UNKNOWN")
        }
    }

    private fun outcome(ctx: OperationContext, l: Loaded, o: GatewayOutcome): OperationOutcome = when (o) {
        is GatewayOutcome.Ok -> ctx.commit(o.payment.state.name) { j -> core.observe(j, l.payment.id, o.payment) }
        is GatewayOutcome.Declined -> ctx.commit("AUTHORIZATION_FAILED") { j -> core.failAuthorization(j, l.payment.id, FailureCodes.DECLINED) }
        is GatewayOutcome.Rejected -> ctx.commit("AUTHORIZATION_FAILED") { j -> core.failAuthorization(j, l.payment.id, FailureCodes.REJECTED) }
        is GatewayOutcome.Conflict, GatewayOutcome.NotFound -> OperationOutcome.Unknown("AUTHORIZE_OUTCOME_UNKNOWN")
        is GatewayOutcome.Unavailable -> throw OperationRetryException("PSP_UNAVAILABLE", o.retryAfter)
        is GatewayOutcome.Unknown -> {
            tx.run(ctx.tenant) { j -> core.markAuthorizationUnknown(j, l.payment.id) }
            OperationOutcome.Unknown("AUTHORIZE_OUTCOME_UNKNOWN")
        }
    }
}

/** `CAPTURE_PAYMENT`: Einzug nach bestätigter Buchung; ein Fehler lässt die Buchung unberührt (dieses Modul kennt ihren Zustand nicht). */
internal class CaptureHandler(tx: TenantTransactions, core: PaymentCore, gateway: PaymentGateway, config: PaymentConfig) : PaymentHandler("CAPTURE_PAYMENT", tx, core, gateway, config) {
    private fun command(l: Loaded) = GatewayCapture(l.op.commandRef, l.payment.pspPaymentId ?: l.payment.providerRef!!, l.op.amountMinor!!)

    override fun execute(ctx: OperationContext): OperationOutcome {
        val l = load(ctx)
        return when (l.payment.state) {
            PaymentState.CAPTURE_PENDING -> outcome(ctx, l, gateway.capture(command(l)))
            PaymentState.CAPTURE_UNKNOWN, PaymentState.RECONCILING -> OperationOutcome.Unknown("CAPTURE_OUTCOME_UNKNOWN")
            else -> OperationOutcome.Succeeded("ALREADY_APPLIED")
        }
    }

    override fun reconcile(ctx: OperationContext): OperationOutcome {
        val l = load(ctx)
        if (l.payment.state == PaymentState.CAPTURE_UNKNOWN) tx.run(ctx.tenant) { j -> core.observe(j, l.payment.id, startedObservation(l.payment)) }   // Statusabfrage beginnt: CAPTURE_UNKNOWN -> RECONCILING
        else if (l.payment.state != PaymentState.CAPTURE_PENDING && l.payment.state != PaymentState.RECONCILING) return OperationOutcome.Succeeded("ALREADY_APPLIED")
        val cmd = command(l)
        return when (val r = gateway.reconcile(PaymentAction.CAPTURE, cmd.paymentId, cmd.commandRef)) {
            is GatewayReconciliation.Performed ->
                if (r.payment.state == GatewayState.CAPTURED) ctx.commit("CAPTURED") { j -> core.observe(j, l.payment.id, r.payment) }
                else OperationOutcome.Unknown("CAPTURE_PENDING_AT_PSP", settleDelay())   // beim PSP noch schwebend: nicht als Erfolg oder Fehler verkürzen
            // der PSP kennt den Einzug nicht: Wiederholung mit derselben Referenz ist sicher
            is GatewayReconciliation.NotPerformed -> outcome(ctx, l, gateway.capture(cmd))
            is GatewayReconciliation.Undetermined -> OperationOutcome.Unknown("CAPTURE_OUTCOME_UNKNOWN")
        }
    }

    private fun outcome(ctx: OperationContext, l: Loaded, o: GatewayOutcome): OperationOutcome = when (o) {
        is GatewayOutcome.Ok ->
            if (o.payment.state == GatewayState.CAPTURED) ctx.commit("CAPTURED") { j -> core.observe(j, l.payment.id, o.payment) }
            else OperationOutcome.Unknown("CAPTURE_PENDING_AT_PSP", settleDelay())
        is GatewayOutcome.Declined, is GatewayOutcome.Rejected -> {
            tx.run(ctx.tenant) { j -> core.captureFailed(j, l.payment.id, FailureCodes.CAPTURE_DECLINED) }
            OperationOutcome.Failed("CAPTURE_DECLINED")
        }
        is GatewayOutcome.Conflict, GatewayOutcome.NotFound -> OperationOutcome.Unknown("CAPTURE_OUTCOME_UNKNOWN")
        is GatewayOutcome.Unavailable -> throw OperationRetryException("PSP_UNAVAILABLE", o.retryAfter)
        is GatewayOutcome.Unknown -> {
            tx.run(ctx.tenant) { j -> core.markCaptureUnknown(j, l.payment.id) }
            OperationOutcome.Unknown("CAPTURE_OUTCOME_UNKNOWN")
        }
    }

    /** Beobachtung ohne PSP-Stand: nur der Übergang `CAPTURE_UNKNOWN` -> `RECONCILING` (der Stand entspricht dem Zustand, `CAPTURE_PENDING` ändert nichts). */
    private fun startedObservation(p: PRow) = GatewayPayment(p.pspPaymentId ?: "", p.providerRef ?: "", p.merchantRef ?: "", GatewayState.CAPTURE_PENDING, p.currency, p.authorized, 0, 0)
}

/** `VOID_PAYMENT`: Autorisierung aufheben (spät eingetroffen, abweichender Betrag oder Storno vor dem Einzug). */
internal class VoidHandler(tx: TenantTransactions, core: PaymentCore, gateway: PaymentGateway, config: PaymentConfig) : PaymentHandler("VOID_PAYMENT", tx, core, gateway, config) {
    private fun command(l: Loaded) = GatewayVoid(l.op.commandRef, l.payment.pspPaymentId ?: l.payment.providerRef!!)

    override fun execute(ctx: OperationContext): OperationOutcome {
        val l = load(ctx)
        if (l.payment.state != PaymentState.VOID_PENDING) return OperationOutcome.Succeeded("ALREADY_APPLIED")
        return outcome(ctx, l, gateway.void(command(l)))
    }

    override fun reconcile(ctx: OperationContext): OperationOutcome {
        val l = load(ctx)
        if (l.payment.state != PaymentState.VOID_PENDING) return OperationOutcome.Succeeded("ALREADY_APPLIED")
        val cmd = command(l)
        return when (val r = gateway.reconcile(PaymentAction.VOID, cmd.paymentId, cmd.commandRef)) {
            is GatewayReconciliation.Performed ->
                if (r.payment.state == GatewayState.VOIDED) ctx.commit("VOIDED") { j -> core.observe(j, l.payment.id, r.payment) } else OperationOutcome.Unknown("VOID_PENDING_AT_PSP", settleDelay())
            is GatewayReconciliation.NotPerformed -> outcome(ctx, l, gateway.void(cmd))
            is GatewayReconciliation.Undetermined -> OperationOutcome.Unknown("VOID_OUTCOME_UNKNOWN")
        }
    }

    private fun outcome(ctx: OperationContext, l: Loaded, o: GatewayOutcome): OperationOutcome = when (o) {
        is GatewayOutcome.Ok ->
            if (o.payment.state == GatewayState.VOIDED) ctx.commit("VOIDED") { j -> core.observe(j, l.payment.id, o.payment) } else OperationOutcome.Unknown("VOID_PENDING_AT_PSP", settleDelay())
        // `VOID_PENDING` verlässt nur `VOIDED`: eine Ablehnung ist ein Klärfall für den Betrieb, nie ein stilles Aufgeben
        is GatewayOutcome.Declined, is GatewayOutcome.Rejected -> OperationOutcome.ManualReview("VOID_DECLINED")
        is GatewayOutcome.Conflict, GatewayOutcome.NotFound -> OperationOutcome.Unknown("VOID_OUTCOME_UNKNOWN")
        is GatewayOutcome.Unavailable -> throw OperationRetryException("PSP_UNAVAILABLE", o.retryAfter)
        is GatewayOutcome.Unknown -> OperationOutcome.Unknown("VOID_OUTCOME_UNKNOWN")
    }
}

/** `RECONCILE_PAYMENT`: Statusabfrage beim PSP nach Webhook oder SCA-Rückkehr; nur der verifizierte Stand der Abfrage löst Übergänge aus (Leitlinie 6). */
internal class ReconcileHandler(tx: TenantTransactions, core: PaymentCore, gateway: PaymentGateway, config: PaymentConfig) : PaymentHandler("RECONCILE_PAYMENT", tx, core, gateway, config) {
    override fun execute(ctx: OperationContext): OperationOutcome {
        val l = load(ctx)
        val ref = l.payment.pspPaymentId ?: l.payment.providerRef ?: return OperationOutcome.Succeeded("NO_PSP_REFERENCE")
        return when (val o = gateway.status(ref)) {
            is GatewayOutcome.Ok -> ctx.commit("RECONCILED") { j -> core.observe(j, l.payment.id, o.payment) }
            GatewayOutcome.NotFound -> OperationOutcome.Succeeded("NOT_FOUND")
            is GatewayOutcome.Unavailable -> throw OperationRetryException("PSP_UNAVAILABLE", o.retryAfter)
            else -> OperationOutcome.Unknown("STATUS_UNKNOWN")
        }
    }

    override fun reconcile(ctx: OperationContext): OperationOutcome = execute(ctx)
}

/**
 * `REFUND_PAYMENT` (WP-M2-31): Erstattung nach Storno. Die Kommandoreferenz gehört dem Erstattungsfall (`refund_case.command_ref`), jede Wiederholung benutzt sie erneut (nie eine zweite Erstattung).
 * „Erstattet“ wird erst nach dem bestätigten PSP-Stand gebucht; eine definitive Ablehnung lässt den Fall `FAILED` mit sichtbarem offenem Betrag (die Buchung bleibt storniert), ein unbestimmtes
 * Ergebnis wird abgeglichen.
 */
internal class RefundHandler(tx: TenantTransactions, core: PaymentCore, gateway: PaymentGateway, config: PaymentConfig) : PaymentHandler("REFUND_PAYMENT", tx, core, gateway, config) {
    private class Target(val kase: CaseRow, val pspRef: String)

    private fun target(ctx: OperationContext, l: Loaded): Target? = tx.run(ctx.tenant, readOnly = true) { j -> core.caseByCommand(j, l.op.commandRef) }?.let { Target(it, l.payment.pspPaymentId ?: l.payment.providerRef!!) }

    private fun confirmed(o: de.taktovia.farepayment.api.GatewayPayment, l: Loaded) = o.refundedMinor > l.payment.refunded && (o.state == GatewayState.REFUNDED || o.state == GatewayState.PARTIALLY_REFUNDED)

    override fun execute(ctx: OperationContext): OperationOutcome {
        val l = load(ctx)
        val t = target(ctx, l) ?: return OperationOutcome.Succeeded("NO_CASE")
        if (t.kase.state != "REQUESTED") return OperationOutcome.Succeeded("ALREADY_APPLIED")
        return outcome(ctx, l, t, gateway.refund(GatewayRefund(l.op.commandRef, t.pspRef, t.kase.requested!!)))
    }

    override fun reconcile(ctx: OperationContext): OperationOutcome {
        val l = load(ctx)
        val t = target(ctx, l) ?: return OperationOutcome.Succeeded("NO_CASE")
        if (t.kase.state != "REQUESTED") return OperationOutcome.Succeeded("ALREADY_APPLIED")
        return when (val r = gateway.reconcile(PaymentAction.REFUND, t.pspRef, l.op.commandRef)) {
            is GatewayReconciliation.Performed ->
                if (confirmed(r.payment, l)) ctx.commit("REFUNDED") { j -> core.observe(j, l.payment.id, r.payment) } else OperationOutcome.Unknown("REFUND_PENDING_AT_PSP", settleDelay())
            is GatewayReconciliation.NotPerformed -> outcome(ctx, l, t, gateway.refund(GatewayRefund(l.op.commandRef, t.pspRef, t.kase.requested!!)))
            is GatewayReconciliation.Undetermined -> OperationOutcome.Unknown("REFUND_OUTCOME_UNKNOWN")
        }
    }

    private fun outcome(ctx: OperationContext, l: Loaded, t: Target, o: GatewayOutcome): OperationOutcome = when (o) {
        is GatewayOutcome.Ok ->
            if (confirmed(o.payment, l)) ctx.commit("REFUNDED") { j -> core.observe(j, l.payment.id, o.payment) } else OperationOutcome.Unknown("REFUND_PENDING_AT_PSP", settleDelay())
        is GatewayOutcome.Declined, is GatewayOutcome.Rejected -> {
            tx.run(ctx.tenant) { j -> core.refundFailed(j, t.kase.id, "REFUND_DECLINED") }
            OperationOutcome.Failed("REFUND_DECLINED")
        }
        is GatewayOutcome.Conflict, GatewayOutcome.NotFound -> OperationOutcome.Unknown("REFUND_OUTCOME_UNKNOWN")
        is GatewayOutcome.Unavailable -> throw OperationRetryException("PSP_UNAVAILABLE", o.retryAfter)
        is GatewayOutcome.Unknown -> OperationOutcome.Unknown("REFUND_OUTCOME_UNKNOWN")
    }
}

package de.taktovia.app.payment

import de.taktovia.app.identity.RequestContexts
import de.taktovia.app.operations.OperationResponses
import de.taktovia.bookingdispatch.api.Bookings
import de.taktovia.farepayment.api.BeginPayment
import de.taktovia.farepayment.api.PaymentView
import de.taktovia.farepayment.api.Payments
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.operation.OperationService
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.env.Environment
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * Zahlungsauswahl und SCA-Rückkehr im Checkout (WP-M2-30, LH-6.10-PAY-004). Berechtigung wie die Buchung (Fahrgast nur eigene, Betrieb nur Dienste des Scopes; fremde Buchung `404`).
 * Beide Endpunkte antworten `202` mit der Operation (`GET /v1/operations/{operation_id}`): der PSP wird nie im Request aufgerufen, sondern von der Operation (Lease, Fencing).
 */
@RestController
class PaymentController(private val bookings: Bookings, private val payments: Payments, private val ctxTx: ContextTransactions, private val operations: OperationService, env: Environment) {
    private val window = Duration.ofSeconds(env.getProperty("taktovia.payments.authorization-window-seconds", Long::class.java, 180))
    private val clock = Clock.systemUTC()

    /** `POST /v1/bookings/{id}/payment`: autorisiert exakt den Preis der Quote; abweichender Betrag `409 REQUOTE_REQUIRED`, Buchung nicht `CONFIRMING` `409 INVALID_STATE_TRANSITION`, Frist abgelaufen `409 HOLD_EXPIRED`. */
    @PostMapping("/v1/bookings/{booking_id}/payment")
    @Suppress("UNCHECKED_CAST")
    fun start(@PathVariable("booking_id") id: UUID, @RequestBody body: Map<String, Any?>, @RequestHeader("Idempotency-Key", required = false) key: String?, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {   // der Schlüssel ist Pflicht (Filter), der Controller braucht ihn nicht
        val ctx = RequestContexts.require(request)
        val pc = bookings.paymentContext(ctx, id)
        if (pc.state != "CONFIRMING") throw ProblemException(ErrorCode.INVALID_STATE_TRANSITION, "Die Buchung ist im Zustand ${pc.state} nicht zahlungspflichtig.")
        val amount = body["amount"] as Map<String, Any?>
        val begun = ctxTx.run(ctx) { j ->
            payments.beginIn(j, BeginPayment(pc.bookingId, pc.serviceId, pc.journeyId, pc.quoteId, (amount["amount_minor"] as Number).toLong(), amount["currency"] as String, body["payment_method_ref"] as String, pc.createdAt.plus(window)))
        }
        val op = ctxTx.run(ctx, readOnly = true) { j -> operations.find(j, begun.operationId) } ?: throw ProblemException.notFound()
        return OperationResponses.accepted(op)
    }

    /** `POST /v1/bookings/{id}/payment/return`: stellt einen Abgleich ein (die Rückkehr ändert den Zahlstatus nie selbst); ohne begonnene Zahlung `404`. */
    @PostMapping("/v1/bookings/{booking_id}/payment/return")
    fun scaReturn(@PathVariable("booking_id") id: UUID, @RequestHeader("Idempotency-Key", required = false) key: String?, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        val ctx = RequestContexts.require(request)
        bookings.paymentContext(ctx, id)   // Objektberechtigung
        val opId = ctxTx.run(ctx) { j -> payments.scaReturnIn(j, id) ?: payments.forBookingIn(j, id)?.operationId } ?: throw ProblemException.notFound()
        val op = ctxTx.run(ctx, readOnly = true) { j -> operations.find(j, opId) } ?: throw ProblemException.notFound()
        return OperationResponses.accepted(op)
    }

    companion object {
        private fun money(minor: Long, currency: String, exponent: Int) = linkedMapOf<String, Any?>("amount_minor" to minor, "currency" to currency, "exponent" to exponent)

        /** Zahlungsblock `RidePayment` (UI-Projektion, getrennt vom Buchungsstatus). */
        fun render(p: PaymentView, exponent: Int): Map<String, Any?> {
            val m = linkedMapOf<String, Any?>("id" to p.id.toString(), "status" to p.status.name)
            m["authorized"] = money(p.authorizedMinor, p.currency, exponent); m["captured"] = money(p.capturedMinor, p.currency, exponent); m["refunded"] = money(p.refundedMinor, p.currency, exponent)
            if (p.refundOpenMinor > 0) m["refund_pending"] = money(p.refundOpenMinor, p.currency, exponent)
            p.refund?.let { r ->
                m["refund"] = linkedMapOf<String, Any?>("status" to r.state, "requested" to money(r.requestedMinor ?: 0, p.currency, exponent), "refunded" to money(r.refundedMinor, p.currency, exponent)).also { o -> r.failureCode?.let { o["failure_code"] = it } }
            }
            p.failureCode?.let { m["failure_code"] = it }
            p.operationId?.let { m["operation_id"] = it.toString() }
            m["revision"] = p.revision
            return m
        }
    }
}

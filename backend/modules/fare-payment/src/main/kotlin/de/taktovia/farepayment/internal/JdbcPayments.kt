package de.taktovia.farepayment.internal

import de.taktovia.farepayment.api.BeginPayment
import de.taktovia.farepayment.api.BegunPayment
import de.taktovia.farepayment.api.CouplingKind
import de.taktovia.farepayment.api.CouplingTask
import de.taktovia.farepayment.api.FareQuotes
import de.taktovia.farepayment.api.PaymentAction
import de.taktovia.farepayment.api.PaymentConfig
import de.taktovia.farepayment.api.PaymentGateway
import de.taktovia.farepayment.api.CancelledBooking
import de.taktovia.farepayment.api.PaymentView
import de.taktovia.farepayment.api.RefundHint
import de.taktovia.farepayment.api.RestoreReconcileReport
import de.taktovia.farepayment.api.RefundRequest
import de.taktovia.farepayment.api.RefundView
import de.taktovia.farepayment.api.Payments
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.operation.OperationHandler
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.platform.statemachine.PaymentState
import de.taktovia.platform.tenant.TenantTransactions
import org.springframework.jdbc.core.JdbcOperations
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID

/** Zahlungsautomat (WP-M2-30): Anlegen der Zahlung, Lesen (Projektion), Arbeitsliste der Kopplung, Handler der Zahlungsoperationen. */
internal class JdbcPayments(
    private val tx: TenantTransactions, private val ctxTx: ContextTransactions, private val quotes: FareQuotes, private val outbox: Outbox, private val config: PaymentConfig, private val clock: Clock,
) : Payments {
    internal val core = PaymentCore(outbox, config, clock)

    override fun beginIn(j: JdbcOperations, cmd: BeginPayment): BegunPayment {
        val now = clock.instant()
        // Autorisiert wird exakt der Preis der Quote (`pay_at_platform_minor`); ein abweichender Betrag ist `REQUOTE_REQUIRED`, bevor etwas geschrieben oder beim PSP aufgerufen wird
        val quote = quotes.getIn(j, cmd.quoteId) ?: throw ProblemException.notFound()
        if (quote.payAtPlatformMinor != cmd.amountMinor || quote.currency != cmd.currency) throw ProblemException(ErrorCode.REQUOTE_REQUIRED, "Der Betrag weicht vom Preisstand der Buchung ab.")
        if (quote.payAtPlatformMinor < 1) throw ProblemException.invalid(Violation("/amount", ViolationCode.BELOW_MINIMUM))   // tariflich kostenfrei: keine Zahlung
        active(j, cmd.bookingId)?.let { existing -> return BegunPayment(view(j, existing), latestOperation(j, existing.id) ?: error("Zahlung ohne Operation"), replayed = true) }
        if (!now.isBefore(cmd.authorizeBy)) throw ProblemException(ErrorCode.HOLD_EXPIRED, "Die Zahlungsfrist der Reservierung ist abgelaufen.")
        val id = UUID.randomUUID()
        // höchstens eine wirksame Zahlung je Buchung (Teilindex): ein gleichzeitiger zweiter Beginn liefert die erste statt eines zweiten Einzugs
        val inserted = j.update(
            "INSERT INTO taktovia.payment_intent(tenant_id, id, journey_id, provider, state, currency, authorized_minor, booking_id, service_id, quote_id, requested_minor, merchant_ref, payment_method_ref, authorize_by) " +
                "VALUES (taktovia.current_tenant(), ?, ?, ?, 'CREATED', ?, 0, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (tenant_id, booking_id) WHERE booking_id IS NOT NULL AND state NOT IN ('AUTHORIZATION_FAILED', 'VOIDED') DO NOTHING",
            id, cmd.journeyId, config.provider, cmd.currency, cmd.bookingId, cmd.serviceId, cmd.quoteId, cmd.amountMinor, "mr-$id", cmd.paymentMethodRef, Timestamp.from(cmd.authorizeBy),
        )
        if (inserted == 0) {
            val existing = active(j, cmd.bookingId) ?: throw ProblemException(ErrorCode.CONFLICT, "Für die Buchung läuft bereits eine Zahlung.")
            return BegunPayment(view(j, existing), latestOperation(j, existing.id) ?: error("Zahlung ohne Operation"), replayed = true)
        }
        val row = core.lock(j, id)
        val op = core.enqueue(j, row, PaymentAction.AUTHORIZE, 1, cmd.amountMinor)
        return BegunPayment(view(j, row), op, replayed = false)
    }

    override fun scaReturnIn(j: JdbcOperations, bookingId: UUID): UUID? {
        val p = latest(j, bookingId) ?: return null
        val locked = core.lock(j, p.id)
        return core.enqueueReconcile(j, locked)
    }

    override fun reactToProviderEventIn(j: JdbcOperations, provider: String, providerRef: String) {
        val id = j.query("SELECT id FROM taktovia.payment_intent WHERE provider = ? AND provider_ref = ?", { rs, _ -> rs.getObject(1, UUID::class.java) }, provider, providerRef).firstOrNull() ?: return
        core.enqueueReconcile(j, core.lock(j, id))
    }

    override fun forBookingIn(j: JdbcOperations, bookingId: UUID): PaymentView? = (active(j, bookingId) ?: latest(j, bookingId))?.let { view(j, it) }

    override fun forBooking(ctx: RequestContext, bookingId: UUID): PaymentView? = ctxTx.run(ctx, readOnly = true) { j -> forBookingIn(j, bookingId) }

    override fun getIn(j: JdbcOperations, paymentId: UUID): PaymentView? = core.find(j, paymentId)?.let { view(j, it) }

    override fun couplingTasksIn(j: JdbcOperations, limit: Int): List<CouplingTask> = j.query(
        "SELECT id, booking_id, service_id, state, failure_code FROM taktovia.payment_intent WHERE booking_id IS NOT NULL AND service_id IS NOT NULL AND coupled_at IS NULL AND (" +
            "(state = 'AUTHORIZED' AND (failure_code IS NULL OR failure_code NOT IN ('AUTHORIZATION_EXPIRED', 'AMOUNT_MISMATCH', 'BOOKING_RELEASED'))) " +
            "OR state = 'AUTHORIZATION_FAILED' " +
            "OR (state IN ('VOID_PENDING', 'VOIDED') AND failure_code IN ('AUTHORIZATION_EXPIRED', 'AMOUNT_MISMATCH')) " +
            "OR (state IN ('CREATED', 'REQUIRES_ACTION') AND authorize_by IS NOT NULL AND authorize_by <= clock_timestamp() AND (failure_code IS NULL OR failure_code <> 'BOOKING_RELEASED'))) " +
            "ORDER BY created_at, id LIMIT ?",
        { rs, _ ->
            val state = PaymentState.valueOf(rs.getString(4))
            CouplingTask(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getObject(3, UUID::class.java),
                if (state == PaymentState.AUTHORIZED && rs.getString(5) == null) CouplingKind.CONFIRM_BOOKING else CouplingKind.RELEASE_BOOKING, state)
        }, limit,
    )

    override fun completeCouplingIn(j: JdbcOperations, paymentId: UUID, kind: CouplingKind) {
        when (kind) {
            CouplingKind.CONFIRM_BOOKING -> core.requestCapture(j, paymentId)
            CouplingKind.RELEASE_BOOKING -> { core.abandon(j, paymentId); core.markCoupled(j, paymentId) }
        }
    }

    override fun abandonIn(j: JdbcOperations, paymentId: UUID) = core.abandon(j, paymentId)

    override fun releaseAuthorizationIn(j: JdbcOperations, paymentId: UUID) = core.releaseAuthorization(j, paymentId)

    override fun onBookingCancelledIn(j: JdbcOperations, cancelled: CancelledBooking): RefundHint? = core.onBookingCancelled(j, cancelled)

    override fun requestRefundIn(j: JdbcOperations, request: RefundRequest): RefundView =
        (core.requestRefund(j, request.paymentId, request.caseKey, request.reasonCode, request.causeClass, request.amountMinor) ?: throw ProblemException(ErrorCode.CONFLICT, "Keine Erstattung möglich.")).view()

    override fun reconcileAfterRestoreIn(j: JdbcOperations, limit: Int): RestoreReconcileReport {
        require(limit in 1..5000)
        val ids = j.query(
            "SELECT id FROM taktovia.payment_intent WHERE state IN ('CREATED', 'REQUIRES_ACTION', 'CAPTURE_PENDING', 'CAPTURE_UNKNOWN', 'RECONCILING', 'VOID_PENDING', 'REFUND_PENDING') ORDER BY created_at, id LIMIT ?",
            { rs, _ -> rs.getObject(1, UUID::class.java) }, limit,
        )
        val queued = ArrayList<UUID>()
        var already = 0
        for (id in ids) {
            val row = core.lock(j, id)
            val before = core.hasQueuedReconcile(j, id)
            if (core.enqueueReconcile(j, row, byMerchantRef = true) == null) continue
            if (before) already++ else queued += id
        }
        return RestoreReconcileReport(ids.size, queued.size, already, queued)
    }

    override fun handlers(gateway: PaymentGateway): List<OperationHandler> = listOf(
        AuthorizeHandler(tx, core, gateway, config), CaptureHandler(tx, core, gateway, config), VoidHandler(tx, core, gateway, config), RefundHandler(tx, core, gateway, config), ReconcileHandler(tx, core, gateway, config),
    )

    // ---- Lesen ------------------------------------------------------------------------------------------------------------------------

    private fun active(j: JdbcOperations, bookingId: UUID): PRow? = j.query(
        "SELECT $PAYMENT_COLS FROM taktovia.payment_intent WHERE booking_id = ? AND state NOT IN ('AUTHORIZATION_FAILED', 'VOIDED')", PAYMENT_ROW, bookingId,
    ).firstOrNull()

    private fun latest(j: JdbcOperations, bookingId: UUID): PRow? = j.query(
        "SELECT $PAYMENT_COLS FROM taktovia.payment_intent WHERE booking_id = ? ORDER BY created_at DESC, id DESC LIMIT 1", PAYMENT_ROW, bookingId,
    ).firstOrNull()

    private fun latestOperation(j: JdbcOperations, paymentId: UUID): UUID? = j.query(
        "SELECT operation_id FROM taktovia.payment_operation WHERE payment_id = ? ORDER BY created_at DESC, seq DESC LIMIT 1", { rs, _ -> rs.getObject(1, UUID::class.java) }, paymentId,
    ).firstOrNull()

    private fun view(j: JdbcOperations, p: PRow): PaymentView = PaymentCore.view(p, latestOperation(j, p.id), core.openRefund(j, p.id), core.cases(j, p.id).lastOrNull()?.view())
}

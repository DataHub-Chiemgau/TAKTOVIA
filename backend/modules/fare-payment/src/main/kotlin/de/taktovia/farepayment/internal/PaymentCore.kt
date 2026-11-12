package de.taktovia.farepayment.internal

import de.taktovia.farepayment.api.CommandRefs
import de.taktovia.farepayment.api.GatewayPayment
import de.taktovia.farepayment.api.GatewayState
import de.taktovia.farepayment.api.PaymentAction
import de.taktovia.farepayment.api.PaymentConfig
import de.taktovia.farepayment.api.PaymentUiStatus
import de.taktovia.farepayment.api.PaymentView
import de.taktovia.platform.operation.OperationService
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.outbox.OutboxEvent
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.statemachine.PaymentState
import de.taktovia.platform.statemachine.StateMachines
import de.taktovia.platform.statemachine.StateTransitioner
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import java.sql.ResultSet
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Gelesene Zeile von `payment_intent` (nur innerhalb des Moduls). */
internal data class PRow(
    val id: UUID, val bookingId: UUID?, val serviceId: UUID?, val journeyId: UUID, val provider: String, val providerRef: String?, val pspPaymentId: String?, val merchantRef: String?,
    val paymentMethodRef: String?, val state: PaymentState, val revision: Long, val currency: String, val requested: Long?, val authorized: Long, val captured: Long, val refunded: Long,
    val authorizeBy: Instant?, val failureCode: String?, val coupledAt: Instant?,
)

/** Zeile von `payment_operation`. */
internal data class POp(val operationId: UUID, val paymentId: UUID, val action: PaymentAction, val seq: Int, val amountMinor: Long?, val commandRef: String)

/** Marker und Fehlercodes im Feld `payment_intent.failure_code` (nie Freitext). */
internal object FailureCodes {
    const val AUTHORIZE_UNKNOWN = "AUTHORIZE_OUTCOME_UNKNOWN"
    const val DECLINED = "DECLINED"
    const val REJECTED = "REJECTED"
    const val AUTHORIZATION_EXPIRED = "AUTHORIZATION_EXPIRED"
    const val AMOUNT_MISMATCH = "AMOUNT_MISMATCH"
    const val BOOKING_RELEASED = "BOOKING_RELEASED"
    const val CAPTURE_DECLINED = "CAPTURE_DECLINED"
    const val CAPTURE_AMOUNT_MISMATCH = "CAPTURE_AMOUNT_MISMATCH"
    const val PSP_STATE_DIVERGED = "PSP_STATE_DIVERGED"
    /** Gründe, aus denen eine (spät) eingetroffene Autorisierung aufgehoben wird, statt eine Buchung zu bestätigen. */
    val VOID_REASONS = setOf(AUTHORIZATION_EXPIRED, AMOUNT_MISMATCH, BOOKING_RELEASED)
}

internal const val PAYMENT_COLS = "id, booking_id, service_id, journey_id, provider, provider_ref, psp_payment_id, merchant_ref, payment_method_ref, state, revision, currency, requested_minor, " +
    "authorized_minor, captured_minor, refunded_minor, authorize_by, failure_code, coupled_at"

internal val PAYMENT_ROW = RowMapper { rs: ResultSet, _ ->
    PRow(
        rs.getObject("id", UUID::class.java), rs.getObject("booking_id", UUID::class.java), rs.getObject("service_id", UUID::class.java), rs.getObject("journey_id", UUID::class.java),
        rs.getString("provider"), rs.getString("provider_ref"), rs.getString("psp_payment_id"), rs.getString("merchant_ref"), rs.getString("payment_method_ref"),
        PaymentState.valueOf(rs.getString("state")), rs.getLong("revision"), rs.getString("currency"), rs.getObject("requested_minor") as Long?, rs.getLong("authorized_minor"),
        rs.getLong("captured_minor"), rs.getLong("refunded_minor"), rs.getTimestamp("authorize_by")?.toInstant(), rs.getString("failure_code"), rs.getTimestamp("coupled_at")?.toInstant(),
    )
}

/**
 * Kern des Zahlungsautomaten (WP-M2-30). Alle Methoden laufen in der Transaktion des Aufrufers (Mandantenkontext gesetzt) und sperren die Zeile `payment_intent` (`FOR UPDATE`), sodass
 * Webhook-Abgleich, Statusabfrage und zwei Worker einander nie überholen; jeder Zustandswechsel geht über den [StateTransitioner] (Tabelle des Automaten `payment`, Revision/CAS,
 * Outbox-Ereignis `payment.state_changed`). PSP-Aufrufe gibt es hier nicht (Leitlinie 3).
 */
internal class PaymentCore(private val outbox: Outbox, private val config: PaymentConfig, private val clock: Clock) {
    private val log = LoggerFactory.getLogger("taktovia.payment")
    private val transitioner = StateTransitioner(outbox, clock)
    private val operations = OperationService(clock)

    fun now(): Instant = clock.instant()

    fun find(j: JdbcOperations, id: UUID): PRow? = j.query("SELECT $PAYMENT_COLS FROM taktovia.payment_intent WHERE id = ?", PAYMENT_ROW, id).firstOrNull()

    fun lock(j: JdbcOperations, id: UUID): PRow =
        j.query("SELECT $PAYMENT_COLS FROM taktovia.payment_intent WHERE id = ? FOR UPDATE", PAYMENT_ROW, id).firstOrNull() ?: throw ProblemException.notFound()

    fun op(j: JdbcOperations, operationId: UUID): POp = j.query(
        "SELECT operation_id, payment_id, action, seq, amount_minor, command_ref FROM taktovia.payment_operation WHERE operation_id = ?",
        { rs, _ -> POp(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), PaymentAction.valueOf(rs.getString(3)), rs.getInt(4), rs.getObject(5) as Long?, rs.getString(6)) }, operationId,
    ).first()

    /** Eine Zahlungsoperation anlegen (Operation und Verknüpfung in der Transaktion des Aufrufers); die Zeile der Zahlung ist gesperrt. [seq] 1 für einmalige Aktionen. */
    fun enqueue(j: JdbcOperations, p: PRow, action: PaymentAction, seq: Int, amountMinor: Long?): UUID {
        val ref = CommandRefs.derive(p.id.toString(), action, seq)
        j.query("SELECT operation_id FROM taktovia.payment_operation WHERE payment_id = ? AND action = ? AND seq = ?", { rs, _ -> rs.getObject(1, UUID::class.java) }, p.id, action.name, seq)
            .firstOrNull()?.let { return it }
        val id = operations.enqueue(j, KIND_OF.getValue(action), now().plus(config.operationDeadline), p.journeyId)
        j.update(
            "INSERT INTO taktovia.payment_operation(tenant_id, operation_id, payment_id, action, seq, amount_minor, command_ref) VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?)",
            id, p.id, action.name, seq, amountMinor, ref,
        )
        return id
    }

    /** Abgleich einstellen: höchstens ein noch nicht begonnener Abgleich je Zahlung (ein bereits laufender hat den Stand eventuell vor dem Ereignis gelesen, dann folgt ein weiterer). */
    fun enqueueReconcile(j: JdbcOperations, p: PRow): UUID? {
        if (p.state in SETTLED || (p.providerRef == null && p.pspPaymentId == null)) return null
        j.query(
            "SELECT po.operation_id FROM taktovia.payment_operation po JOIN taktovia.operation o ON o.tenant_id = po.tenant_id AND o.id = po.operation_id " +
                "WHERE po.payment_id = ? AND po.action = 'RECONCILE' AND o.status = 'QUEUED'", { rs, _ -> rs.getObject(1, UUID::class.java) }, p.id,
        ).firstOrNull()?.let { return it }
        val seq = (j.queryForObject("SELECT COALESCE(max(seq), 0) FROM taktovia.payment_operation WHERE payment_id = ? AND action = 'RECONCILE'", Int::class.java, p.id) ?: 0) + 1
        return enqueue(j, p, PaymentAction.RECONCILE, seq, null)
    }

    // ---- Beobachteter (vom PSP verifizierter) Status --------------------------------------------------------------------------------

    /**
     * Wendet einen **verifizierten** PSP-Stand auf die Zahlung an (Statusabfrage oder Antwort eines Aufrufs, nie die Browser-Rückkehr und nie der Inhalt eines Webhooks). Idempotent: ein bereits
     * abgebildeter Stand ändert nichts und schreibt kein Ereignis; zwei gleichzeitige Anwendungen serialisiert die Zeilensperre. Liefert die ausgeführten Übergänge.
     */
    fun observe(j: JdbcOperations, id: UUID, obs: GatewayPayment): List<PaymentState> {
        var row = lock(j, id)
        if ((row.providerRef != null && row.providerRef != obs.providerRef) || (row.merchantRef != null && row.merchantRef != obs.merchantRef) || row.currency != obs.currency) {
            log.warn("PSP-Stand gehört nicht zu dieser Zahlung (Referenz oder Währung weicht ab); ignoriert")
            return emptyList()
        }
        if (row.providerRef == null) {
            j.update("UPDATE taktovia.payment_intent SET provider_ref = ?, psp_payment_id = COALESCE(psp_payment_id, ?) WHERE id = ?", obs.providerRef, obs.paymentId, id)
            row = row.copy(providerRef = obs.providerRef, pspPaymentId = row.pspPaymentId ?: obs.paymentId)
        } else if (row.pspPaymentId == null) {
            j.update("UPDATE taktovia.payment_intent SET psp_payment_id = ? WHERE id = ?", obs.paymentId, id)
            row = row.copy(pspPaymentId = obs.paymentId)
        }
        val done = mutableListOf<PaymentState>()
        var guard = 0
        while (guard++ < 6) {
            val next = nextTarget(row, obs) ?: break
            row = step(j, row, next, obs) ?: break
            done += next
        }
        return done
    }

    /** Nächster Zielzustand aus dem aktuellen Zustand und dem beobachteten PSP-Stand; `null` = nichts zu tun (bereits abgebildet oder noch nicht entscheidbar). */
    private fun nextTarget(row: PRow, obs: GatewayPayment): PaymentState? = when (row.state) {
        PaymentState.CREATED -> when (obs.state) {
            GatewayState.CREATED -> null
            GatewayState.REQUIRES_ACTION -> PaymentState.REQUIRES_ACTION
            GatewayState.AUTHORIZATION_FAILED -> PaymentState.AUTHORIZATION_FAILED
            else -> PaymentState.AUTHORIZED
        }
        PaymentState.REQUIRES_ACTION -> when (obs.state) {
            GatewayState.CREATED, GatewayState.REQUIRES_ACTION -> null
            GatewayState.AUTHORIZATION_FAILED -> PaymentState.AUTHORIZATION_FAILED
            else -> PaymentState.AUTHORIZED
        }
        // eine aufzuhebende Autorisierung (zu spät, falscher Betrag, Buchung freigegeben) wird nie bestätigt, sondern aufgehoben
        PaymentState.AUTHORIZED -> if (row.failureCode in FailureCodes.VOID_REASONS) PaymentState.VOID_PENDING else null
        PaymentState.CAPTURE_PENDING -> if (obs.state == GatewayState.CAPTURED) PaymentState.CAPTURED else null
        PaymentState.CAPTURE_UNKNOWN -> PaymentState.RECONCILING     // Statusabfrage läuft (Kap. 03 §4.2)
        PaymentState.RECONCILING -> when (obs.state) {
            GatewayState.CAPTURED -> PaymentState.CAPTURED
            GatewayState.AUTHORIZED -> PaymentState.AUTHORIZED
            GatewayState.VOID_PENDING, GatewayState.VOIDED -> PaymentState.VOID_PENDING
            else -> null
        }
        PaymentState.VOID_PENDING -> if (obs.state == GatewayState.VOIDED) PaymentState.VOIDED else null
        else -> null   // CAPTURED und Erstattungszustände: WP-M2-31; Endzustände ohne Übergang
    }

    /** Führt einen Übergang samt Spaltenänderung und Ereignissen aus; `null` = nicht ausführbar (Sicherheitsabbruch, Stand bleibt). */
    private fun step(j: JdbcOperations, row: PRow, to: PaymentState, obs: GatewayPayment): PRow? {
        val id = row.id
        when (to) {
            PaymentState.AUTHORIZED -> {
                if (row.state == PaymentState.RECONCILING) return move(j, row, PaymentState.AUTHORIZED)   // Abgleich: nicht eingezogen, Autorisierung gültig
                if (obs.authorizedMinor < 1) { markFailure(j, id, FailureCodes.REJECTED); return null }
                j.update("UPDATE taktovia.payment_intent SET authorized_minor = ? WHERE id = ?", obs.authorizedMinor, id)
                val reason = when {
                    row.requested != null && obs.authorizedMinor != row.requested -> FailureCodes.AMOUNT_MISMATCH
                    row.failureCode == FailureCodes.BOOKING_RELEASED -> FailureCodes.BOOKING_RELEASED
                    row.authorizeBy != null && !now().isBefore(row.authorizeBy) -> FailureCodes.AUTHORIZATION_EXPIRED   // SCA länger als der Hold
                    else -> null
                }
                if (reason != null) markFailure(j, id, reason) else clearMarker(j, id)
                val moved = move(j, row.copy(authorized = obs.authorizedMinor), PaymentState.AUTHORIZED)
                event(j, moved, if (reason == null) "payment.authorized" else "payment.authorization_failed", if (reason == null) null else reason, obs.authorizedMinor)
                return moved.copy(failureCode = reason)
            }
            PaymentState.AUTHORIZATION_FAILED -> {
                markFailure(j, id, FailureCodes.DECLINED, onlyIfNull = true); clearMarker(j, id)
                val moved = move(j, row, PaymentState.AUTHORIZATION_FAILED)
                event(j, moved, "payment.authorization_failed", row.failureCode?.takeIf { it != FailureCodes.AUTHORIZE_UNKNOWN } ?: FailureCodes.DECLINED, null)
                return moved
            }
            PaymentState.CAPTURED -> {
                if (obs.capturedMinor < 1 || obs.capturedMinor > row.authorized) { markFailure(j, id, FailureCodes.CAPTURE_AMOUNT_MISMATCH); return null }
                j.update("UPDATE taktovia.payment_intent SET captured_minor = ? WHERE id = ?", obs.capturedMinor, id)
                val moved = move(j, row.copy(captured = obs.capturedMinor), PaymentState.CAPTURED)
                event(j, moved, "payment.captured", null, obs.capturedMinor)
                return moved
            }
            PaymentState.VOID_PENDING -> {
                val moved = move(j, row, PaymentState.VOID_PENDING)
                enqueue(j, moved, PaymentAction.VOID, 1, null)
                return moved
            }
            else -> return move(j, row, to)   // REQUIRES_ACTION, RECONCILING, VOIDED
        }
    }

    fun move(j: JdbcOperations, row: PRow, to: PaymentState): PRow {
        val t = transitioner.transition(j, StateMachines.PAYMENT, row.id, to, row.revision)
        return row.copy(state = to, revision = t.revision)
    }

    // ---- Zustände ohne PSP-Beobachtung --------------------------------------------------------------------------------------------------

    /** Definitive Ablehnung (PSP `Declined`/`Rejected` bei Authorize): `CREATED`/`REQUIRES_ACTION` → `AUTHORIZATION_FAILED`; später Eingetroffenes ändert nichts. */
    fun failAuthorization(j: JdbcOperations, id: UUID, code: String) {
        val row = lock(j, id)
        if (row.state != PaymentState.CREATED && row.state != PaymentState.REQUIRES_ACTION) return
        markFailure(j, id, code)
        val moved = move(j, row, PaymentState.AUTHORIZATION_FAILED)
        event(j, moved, "payment.authorization_failed", code, null)
    }

    /** Timeout bei der Autorisierung: der Zustand bleibt `CREATED`, der Marker macht den Zahlstatus für die UI unbestimmt (`UNKNOWN`), die Operation gleicht ab (nie eine zweite Belastung). */
    fun markAuthorizationUnknown(j: JdbcOperations, id: UUID) {
        val n = j.update(
            "UPDATE taktovia.payment_intent SET failure_code = ?, revision = revision + 1 WHERE id = ? AND state = 'CREATED' AND failure_code IS DISTINCT FROM ?",
            FailureCodes.AUTHORIZE_UNKNOWN, id, FailureCodes.AUTHORIZE_UNKNOWN,
        )
        if (n == 1) event(j, find(j, id)!!, "payment.outcome_unknown", null, null, phase = "AUTHORIZE")
    }

    /** Timeout beim Einzug: `CAPTURE_PENDING` → `CAPTURE_UNKNOWN` (nie als Fehlschlag verkürzt). */
    fun markCaptureUnknown(j: JdbcOperations, id: UUID) {
        val row = lock(j, id)
        if (row.state != PaymentState.CAPTURE_PENDING) return
        val moved = move(j, row, PaymentState.CAPTURE_UNKNOWN)
        event(j, moved, "payment.outcome_unknown", null, null, phase = "CAPTURE")
    }

    /**
     * Einzug definitiv abgelehnt: die Zahlung wird nicht stillschweigend als bezahlt oder gescheitert abgelegt, sondern als Klärfall geführt (`CAPTURE_PENDING` → `CAPTURE_UNKNOWN` → `RECONCILING`,
     * Fehlercode `CAPTURE_DECLINED`); die Buchung bleibt davon unberührt (dieses Modul kennt ihren Zustand nicht).
     */
    fun captureFailed(j: JdbcOperations, id: UUID, code: String) {
        var row = lock(j, id)
        if (row.state == PaymentState.RECONCILING) { markFailure(j, id, code); return }   // bereits Klärfall (Abgleich nach Timeout): nur der Fehlercode kommt hinzu
        if (row.state != PaymentState.CAPTURE_PENDING) return
        markFailure(j, id, code)
        row = move(j, row, PaymentState.CAPTURE_UNKNOWN)
        event(j, row, "payment.outcome_unknown", null, null, phase = "CAPTURE")
        move(j, row, PaymentState.RECONCILING)
    }

    // ---- Kopplung an die Buchung ---------------------------------------------------------------------------------------------------------

    fun requestCapture(j: JdbcOperations, id: UUID) {
        val row = lock(j, id)
        if (row.state == PaymentState.AUTHORIZED && row.failureCode !in FailureCodes.VOID_REASONS) {
            val moved = move(j, row, PaymentState.CAPTURE_PENDING)
            enqueue(j, moved, PaymentAction.CAPTURE, 1, moved.authorized)
        }
        if (row.coupledAt == null) j.update("UPDATE taktovia.payment_intent SET coupled_at = clock_timestamp() WHERE id = ?", id)
    }

    /** Die Buchung will die Zahlung nicht mehr: `AUTHORIZED` wird aufgehoben (nie eingezogen), Ausstehendes später aufgehoben; die Kopplung ist vollzogen. */
    fun releaseAuthorization(j: JdbcOperations, id: UUID) {
        val row = lock(j, id)
        when (row.state) {
            PaymentState.AUTHORIZED -> {
                markFailure(j, id, FailureCodes.BOOKING_RELEASED)
                val moved = move(j, row, PaymentState.VOID_PENDING)
                enqueue(j, moved, PaymentAction.VOID, 1, null)
            }
            PaymentState.CREATED, PaymentState.REQUIRES_ACTION -> markFailure(j, id, FailureCodes.BOOKING_RELEASED)
            else -> {}
        }
        markCoupled(j, id)
    }

    fun markCoupled(j: JdbcOperations, id: UUID) { j.update("UPDATE taktovia.payment_intent SET coupled_at = clock_timestamp() WHERE id = ? AND coupled_at IS NULL", id) }

    /** Buchung ohne wirksame Autorisierung freigegeben: eine spätere Autorisierung wird aufgehoben. Nur solange noch nicht autorisiert. */
    fun abandon(j: JdbcOperations, id: UUID) {
        val row = lock(j, id)
        if (row.state == PaymentState.CREATED || row.state == PaymentState.REQUIRES_ACTION) markFailure(j, id, FailureCodes.BOOKING_RELEASED)
    }

    // ---- Hilfen ------------------------------------------------------------------------------------------------------------------------------

    private fun markFailure(j: JdbcOperations, id: UUID, code: String, onlyIfNull: Boolean = false) {
        if (onlyIfNull) j.update("UPDATE taktovia.payment_intent SET failure_code = ? WHERE id = ? AND (failure_code IS NULL OR failure_code = ?)", code, id, FailureCodes.AUTHORIZE_UNKNOWN)
        else j.update("UPDATE taktovia.payment_intent SET failure_code = ? WHERE id = ?", code, id)
    }

    /** Der Marker „Autorisierung unbestimmt“ gilt nicht mehr, sobald der Zustand verlassen wird. */
    private fun clearMarker(j: JdbcOperations, id: UUID) { j.update("UPDATE taktovia.payment_intent SET failure_code = NULL WHERE id = ? AND failure_code = ?", id, FailureCodes.AUTHORIZE_UNKNOWN) }

    /** Fachereignis zur neuen Revision der Zahlung (zusätzlich zu `payment.state_changed`); Payload ohne Zahlungsmitteldaten. */
    private fun event(j: JdbcOperations, p: PRow, type: String, failureCode: String?, amountMinor: Long?, phase: String? = null) {
        val payload = linkedMapOf<String, Any?>("payment_id" to p.id.toString())
        p.bookingId?.let { payload["booking_id"] = it.toString() }
        when (type) {
            "payment.authorized" -> { payload["authorized_minor"] = amountMinor; payload["currency"] = p.currency }
            "payment.authorization_failed" -> payload["failure_code"] = failureCode
            "payment.captured" -> { payload["captured_minor"] = amountMinor; payload["currency"] = p.currency }
            "payment.outcome_unknown" -> payload["phase"] = phase
        }
        outbox.append(j, OutboxEvent(p.id, p.revision, type, payload, now()))
    }

    companion object {
        val KIND_OF = mapOf(
            PaymentAction.AUTHORIZE to "AUTHORIZE_PAYMENT", PaymentAction.CAPTURE to "CAPTURE_PAYMENT", PaymentAction.VOID to "VOID_PAYMENT",
            PaymentAction.REFUND to "REFUND_PAYMENT", PaymentAction.RECONCILE to "RECONCILE_PAYMENT",
        )
        /** Zustände, in denen kein Abgleich mehr nötig ist. */
        val SETTLED = setOf(PaymentState.AUTHORIZATION_FAILED, PaymentState.VOIDED, PaymentState.REFUNDED)

        /** UI-Projektion des Zahlstatus (Kap. 05 §5.9). */
        fun status(p: PRow): PaymentUiStatus = when {
            p.state == PaymentState.CREATED && p.failureCode == FailureCodes.AUTHORIZE_UNKNOWN -> PaymentUiStatus.UNKNOWN
            p.state == PaymentState.CAPTURE_UNKNOWN || p.state == PaymentState.RECONCILING -> PaymentUiStatus.UNKNOWN
            p.state == PaymentState.REFUND_PENDING -> if (p.refunded > 0) PaymentUiStatus.PARTIALLY_REFUNDED else PaymentUiStatus.CAPTURED
            else -> PaymentUiStatus.valueOf(p.state.name)
        }

        fun view(p: PRow, operationId: UUID?, refundOpen: Long = 0) = PaymentView(
            p.id, p.bookingId, p.journeyId, p.state, status(p), p.currency, p.requested, p.authorized, p.captured, p.refunded, refundOpen, p.failureCode, p.revision, p.authorizeBy, operationId,
        )
    }
}

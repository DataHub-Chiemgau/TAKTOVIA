package de.taktovia.platform.operation

import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.outbox.RetrySchedule
import de.taktovia.platform.outbox.TenantSource
import de.taktovia.platform.statemachine.OperationState
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcOperations
import java.time.Clock

/** Ergebnis eines Runner-Schritts: Endstand nach dem Schreiben bzw. `leaseLost` (der Worker hat nichts geschrieben). */
data class RunResult(val id: java.util.UUID, val state: OperationState?, val leaseLost: Boolean = false)

/** Was ein Handler während seiner Lease tun darf; jede Schreibwirkung trägt den Fencing-Token. */
class OperationContext internal constructor(
    private val runner: OperationRunner, private val claimed: ClaimedOperation,
) {
    val tenant: TenantId get() = claimed.tenant
    val operationId get() = claimed.id
    val kind get() = claimed.kind
    val journeyId get() = claimed.journeyId
    val attempt get() = claimed.attempt
    val fencingToken get() = claimed.fencingToken
    val operationKey get() = claimed.operationKey
    val deadlineAt get() = claimed.deadlineAt

    /** Verlängert die Lease (lang laufende Schritte); [LeaseLostException], wenn sie inzwischen übernommen wurde. */
    fun renew() = runner.renew(claimed)

    /**
     * Lokale Wirkung und Erfolg in **einer** Transaktion: erst der gefencete Abschluss (hält die Zeilensperre), dann [effect]. Hat der Worker seine Lease
     * verloren, wird beides zurückgerollt ([LeaseLostException]); eine Wirkung tritt so genau einmal ein, auch wenn zwei Worker sie versuchen.
     */
    fun commit(resultCode: String = "OK", effect: (JdbcOperations) -> Unit): OperationOutcome.Completed {
        runner.tx.run(claimed.tenant) { j ->
            runner.store.transition(j, claimed.id, claimed.fencingToken, OperationState.SUCCEEDED, resultCode, null)
            effect(j)
        }
        return OperationOutcome.Completed
    }
}

/**
 * Operation-Runner (WP-M1-15, Kap. 02 §7.3): nimmt fällige Operationen per Lease auf, führt sie über den [OperationHandler] der Art aus und schreibt
 * das Ergebnis mit Fencing. Nach Prozessabbruch übernimmt ein anderer Worker, sobald die Lease abgelaufen ist; der Fencing-Token steigt dabei, sodass der
 * alte Worker nichts mehr schreiben kann. Regeln:
 *  - `FAILED` nur nach bestimmtem Ergebnis ([OperationOutcome.Failed]) oder wenn die Operation nachweislich nie gestartet wurde (Deadline vor dem ersten Versuch).
 *  - Unbestimmtes (unerwartete Ausnahme, Abbruch nach Start, Deadline im Lauf) führt nach RECONCILING und wird abgeglichen, nie zu Erfolg/Fehler verkürzt;
 *    im Abgleich mit überschrittener Deadline folgt MANUAL_REVIEW.
 *  - Ein Handler, der vor jeder Wirkung scheitert, wirft [OperationRetryException]: zurück nach QUEUED mit Backoff.
 * Mandanten kommen aus der [TenantSource] des Betriebs (RLS erlaubt keine Aufzählung, OP-06/OP-08).
 */
class OperationRunner(
    internal val tx: TenantTransactions,
    handlers: List<OperationHandler>,
    outbox: Outbox = Outbox(),
    private val clock: Clock = Clock.systemUTC(),
    private val config: OperationConfig = OperationConfig(),
    val workerId: String = "worker",
) {
    private val log = LoggerFactory.getLogger("taktovia.operation")
    internal val store = OperationStore(outbox, clock)
    private val handlers = handlers.associateBy { it.kind }

    /** Ein Durchlauf über alle Mandanten der Quelle; Fehler eines Mandanten stoppen die anderen nicht. Anzahl der bearbeiteten Operationen. */
    fun runOnce(tenants: TenantSource): Int = tenants.tenants().distinct().sumOf { t -> runCatching { runTenant(t) }.getOrDefault(0) }

    fun runTenant(tenant: TenantId): Int { var n = 0; while (n < config.maxPerTenantRun && runNext(tenant) != null) n++; return n }

    /** Beansprucht und bearbeitet die nächste fällige Operation; `null`, wenn nichts fällig ist. */
    fun runNext(tenant: TenantId): RunResult? = claimNext(tenant)?.let { execute(it) }

    fun claimNext(tenant: TenantId): ClaimedOperation? {
        val now = clock.instant()
        return tx.run(tenant) { j -> store.claimNext(j, tenant, now, now.plus(config.lease), workerId) }
    }

    internal fun renew(c: ClaimedOperation) { tx.run(c.tenant) { j -> store.renew(j, c.id, c.fencingToken, clock.instant().plus(config.lease)) } }

    /** Führt eine beanspruchte Operation aus und schreibt das Ergebnis; verliert der Worker dabei seine Lease, schreibt er nichts ([RunResult.leaseLost]). */
    fun execute(c: ClaimedOperation): RunResult = try {
        RunResult(c.id, finish(c, decide(c)))
    } catch (e: LeaseLostException) {
        log.warn("Lease verloren operation={} token={}", c.id, c.fencingToken)
        RunResult(c.id, null, leaseLost = true)
    }

    private fun decide(c: ClaimedOperation): OperationOutcome {
        val now = clock.instant()
        if (!c.deadlineAt.isAfter(now)) {   // Deadline überschritten: kein Handler mehr, Ausgang hängt davon ab, ob je eine Wirkung möglich war
            return when (c.previousStatus) {
                OperationState.QUEUED -> OperationOutcome.Failed("DEADLINE_EXCEEDED")           // nie gestartet: bestimmt
                OperationState.RUNNING -> OperationOutcome.Unknown("DEADLINE_EXCEEDED")         // gestartet, Ausgang unbekannt: abgleichen
                else -> OperationOutcome.ManualReview("DEADLINE_EXCEEDED")                      // schon im Abgleich: manuelle Prüfung
            }
        }
        val handler = handlers[c.kind] ?: return OperationOutcome.ManualReview("NO_HANDLER")
        val ctx = OperationContext(this, c)
        return try {
            if (c.status == OperationState.RECONCILING) handler.reconcile(ctx) else handler.execute(ctx) // sql-lint: ok Handler-Aufruf, kein SQL
        } catch (e: LeaseLostException) { throw e
        } catch (e: OperationRetryException) {
            if (c.status == OperationState.RUNNING) OperationOutcome.Retry(e.resultCode, e.delay ?: RetrySchedule.delay(c.attempt.coerceAtLeast(1))) else OperationOutcome.Unknown(e.resultCode, e.delay)
        } catch (e: Exception) {
            log.warn("Operation {} Art {}: unerwartete Ausnahme ({}); Ausgang unbekannt, wird abgeglichen", c.id, c.kind, e.javaClass.simpleName)
            OperationOutcome.Unknown("UNKNOWN_OUTCOME")
        }
    }

    private fun finish(c: ClaimedOperation, outcome: OperationOutcome): OperationState {
        val now = clock.instant()
        fun write(to: OperationState, code: String?, next: java.time.Instant? = null): OperationState {
            tx.run(c.tenant) { j -> store.transition(j, c.id, c.fencingToken, to, code, next) }
            return to
        }
        return when (outcome) {
            OperationOutcome.Completed -> OperationState.SUCCEEDED
            is OperationOutcome.Succeeded -> write(OperationState.SUCCEEDED, outcome.resultCode)
            is OperationOutcome.Failed -> write(OperationState.FAILED, outcome.resultCode)
            is OperationOutcome.ManualReview -> write(OperationState.MANUAL_REVIEW, outcome.resultCode)
            is OperationOutcome.Retry -> write(OperationState.QUEUED, outcome.resultCode, now.plus(outcome.delay))
            is OperationOutcome.Unknown -> write(OperationState.RECONCILING, outcome.resultCode, now.plus(outcome.retryAfter ?: config.reconcileDelay))
        }
    }
}

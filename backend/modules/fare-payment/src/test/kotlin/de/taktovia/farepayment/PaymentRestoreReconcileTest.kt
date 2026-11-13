package de.taktovia.farepayment

import de.taktovia.farepayment.api.CouplingKind
import de.taktovia.farepayment.api.PaymentAction
import de.taktovia.platform.operation.OperationConfig
import de.taktovia.platform.operation.OperationRunner
import de.taktovia.platform.statemachine.PaymentState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M5-14: Abgleichlauf nach einem Wiederanlauf (Restore). Die Datenbank steht auf dem Stand des letzten wiederherstellbaren Commits, der Zahlungsdienstleister (Simulator
 * [PayScriptedPsp]) kann weiter sein: eine Zahlung ist dort vollzogen, lokal aber unbestimmt oder verloren. Der Lauf stellt Abgleiche anhand der Händler- bzw. Providerreferenz ein,
 * wiederholt nie einen unsicheren Schritt mit neuer Referenz und erzeugt unter parallelem Webhook genau eine Zustandswirkung.
 */
class PaymentRestoreReconcileTest {
    @BeforeEach
    fun db() = PaymentTestDb.require()

    private fun sweep(w: PayTestWorld) = w.fx.tx.run(w.tenant) { j -> w.payments.reconcileAfterRestoreIn(j) }

    @Test
    fun abgleich_findet_beim_psp_vollzogene_aber_lokal_verlorene_zahlung_ohne_zweite_belastung() {
        val psp = PayScriptedPsp(); val w = PayTestWorld(psp)
        w.use {
            val b = w.newBooking(); val begun = w.begin(b)
            psp.inject(PaymentAction.AUTHORIZE, PayScriptedPsp.Fault.CRASH_AFTER_EFFECT)
            w.drain(1)
            assertEquals(PaymentState.CREATED, w.view(b)!!.state, "lokal ist die Autorisierung verloren")
            assertEquals(1, psp.effects(PaymentAction.AUTHORIZE), "beim PSP ist sie vollzogen")
            val report = sweep(w)
            assertEquals(1, report.enqueued); assertEquals(listOf(w.view(b)!!.id), report.paymentIds)
            assertEquals(0, sweep(w).enqueued, "ein zweiter Lauf stellt keinen weiteren Abgleich ein, solange der erste offen ist")
            w.drain()
            val v = w.view(b)!!
            assertEquals(PaymentState.AUTHORIZED, v.state, "genau ein konsistenter Zustand"); assertEquals(950, v.authorizedMinor)
            assertEquals(1, psp.effects(PaymentAction.AUTHORIZE)); assertEquals(1, psp.calls(PaymentAction.AUTHORIZE), "der Abgleich ruft Authorize nicht erneut auf")
            assertEquals(1, w.events("payment.authorized", v.id)); assertEquals(1, w.events("payment.state_changed", v.id))
            assertEquals(1, psp.payments().size, "keine zweite Zahlung beim PSP")
            // die ursprüngliche Operation läuft später ohne Wirkung zu Ende
            w.clock.advance(Duration.ofHours(2)); w.drain()
            assertEquals(1, psp.calls(PaymentAction.AUTHORIZE)); assertEquals(1, w.events("payment.state_changed", v.id)); assertEquals("SUCCEEDED/ALREADY_APPLIED", w.opStatus(begun.operationId))
            assertEquals(0, sweep(w).enqueued, "nach dem Abgleich ist nichts mehr offen")
        }
    }

    @Test
    fun abgleich_ohne_psp_spur_laesst_die_zahlung_unveraendert_und_belastet_nie() {
        val psp = PayScriptedPsp(); val w = PayTestWorld(psp)
        w.use {
            val b = w.newBooking(); w.begin(b)    // die Autorisierung kam beim PSP nie an (Restore vor dem Worker, PSP zunächst nicht erreichbar)
            psp.inject(PaymentAction.AUTHORIZE, PayScriptedPsp.Fault.UNAVAILABLE)
            val report = sweep(w)
            assertEquals(1, report.enqueued)
            w.drain()                             // beide Operationen laufen (Reihenfolge beliebig): der Abgleich findet nichts, die Autorisierung wartet auf den PSP
            assertEquals(PaymentState.CREATED, w.view(b)!!.state)
            assertEquals(0, psp.effects(PaymentAction.AUTHORIZE))
            assertEquals(1, psp.calls(PaymentAction.AUTHORIZE), "nur die ursprüngliche Autorisierungsoperation ruft den PSP, der Abgleichlauf nie")
            assertEquals("SUCCEEDED/NOT_FOUND", w.fx.admin.queryForObject(
                "SELECT o.status || '/' || COALESCE(o.result_code, '') FROM taktovia.operation o JOIN taktovia.payment_operation po ON po.tenant_id = o.tenant_id AND po.operation_id = o.id WHERE po.action = 'RECONCILE' AND o.tenant_id = ?",
                String::class.java, w.tenant.value))
        }
    }

    @Test
    fun abgleich_bleibt_bei_ausgefallener_statusabfrage_unbestimmt() {
        val psp = PayScriptedPsp(); val w = PayTestWorld(psp)
        w.use {
            val b = w.newBooking(); w.begin(b)
            psp.inject(PaymentAction.AUTHORIZE, PayScriptedPsp.Fault.CRASH_AFTER_EFFECT); w.drain(1)
            psp.statusDown = true
            sweep(w); w.drain(1)
            assertEquals(PaymentState.CREATED, w.view(b)!!.state, "ohne verifizierten PSP-Stand kein Übergang")
            assertEquals(1, psp.calls(PaymentAction.AUTHORIZE), "und kein zweiter Aufruf auf Verdacht")
            psp.statusDown = false; w.clock.advance(Duration.ofHours(2)); sweep(w); w.drain()
            assertEquals(PaymentState.AUTHORIZED, w.view(b)!!.state); assertEquals(1, psp.effects(PaymentAction.AUTHORIZE))
        }
    }

    @Test
    fun abgleich_parallel_zu_webhook_und_zwei_workern_erzeugt_genau_eine_zustandswirkung() {
        val psp = PayScriptedPsp(); val w = PayTestWorld(psp)
        w.use {
            val b = w.newBooking(); w.begin(b); w.drain()
            w.fx.tx.run(w.tenant) { j -> w.payments.completeCouplingIn(j, w.view(b)!!.id, CouplingKind.CONFIRM_BOOKING) }
            psp.inject(PaymentAction.CAPTURE, PayScriptedPsp.Fault.LOST_RESPONSE)
            w.drain(1)
            assertEquals(PaymentState.CAPTURE_UNKNOWN, w.view(b)!!.state)
            assertEquals(1, psp.effects(PaymentAction.CAPTURE), "der Einzug ist beim PSP vollzogen, die Antwort ging verloren")
            val providerRef = w.fx.admin.queryForObject("SELECT provider_ref FROM taktovia.payment_intent WHERE tenant_id = ?", String::class.java, w.tenant.value)!!
            val n = 12; val pool = Executors.newFixedThreadPool(n); val start = CountDownLatch(1)
            val cfg = OperationConfig(reconcileDelay = Duration.ofHours(1), lease = Duration.ofSeconds(60))
            val futures = (0 until n).map { i ->
                pool.submit {
                    start.await()
                    when (i % 3) {
                        0 -> sweep(w)                                                                                                         // Abgleichlauf nach Restore
                        1 -> w.fx.tx.run(w.tenant) { j -> w.payments.reactToProviderEventIn(j, psp.provider, providerRef) }                    // PSP-Webhook
                        else -> OperationRunner(w.fx.tx, w.payments.handlers(psp), w.outbox, w.clock, cfg, "w$i").let { r -> repeat(4) { r.runNext(w.tenant) } }
                    }
                }
            }
            start.countDown(); futures.forEach { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
            w.drain()
            val v = w.view(b)!!
            assertEquals(PaymentState.CAPTURED, v.state); assertEquals(950, v.capturedMinor)
            assertEquals(1, psp.effects(PaymentAction.CAPTURE), "keine zweite Belastung")
            assertEquals(1, w.events("payment.captured", v.id), "genau eine fachliche Wirkung")
            val toCaptured = w.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id = ? AND event_type = 'payment.state_changed' AND aggregate_id = ? AND payload->>'to' = 'CAPTURED'", w.tenant.value, v.id)
            assertEquals(1, toCaptured, "der Übergang nach CAPTURED geschieht genau einmal")
            assertTrue(sweep(w).enqueued == 0, "danach ist nichts mehr offen")
        }
    }
}

package de.taktovia.farepayment

import de.taktovia.farepayment.api.CancelledBooking
import de.taktovia.farepayment.api.CouplingKind
import de.taktovia.farepayment.api.PaymentAction
import de.taktovia.farepayment.api.PaymentConfig
import de.taktovia.farepayment.api.PaymentUiStatus
import de.taktovia.farepayment.api.RefundRequest
import de.taktovia.farepayment.api.RefundRule
import de.taktovia.farepayment.api.RefundStatus
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.statemachine.PaymentState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M2-31: Void und Erstattung nach Storno gegen die echte Datenbank unter der Runtime-Rolle, echte Operationen (Lease, Fencing) und eine skriptbare Gegenstelle ([PayScriptedPsp]).
 * Der Storno selbst gehört der Buchung; hier wird die Reaktion des Zahlungsmoduls ([de.taktovia.farepayment.api.Payments.onBookingCancelledIn]) geprüft, die in derselben Transaktion wie der
 * Storno läuft (Void bzw. Erstattung werden dort nur **beauftragt**, der PSP-Aufruf folgt als Operation).
 */
class PaymentRefundTest {
    private fun world(psp: PayScriptedPsp = PayScriptedPsp(), refund: RefundRule = RefundRule()) =
        PayTestWorld(psp, config = PaymentConfig(provider = psp.provider, operationDeadline = Duration.ofDays(1), refund = refund))

    /** Autorisiert, Buchung bestätigt (Kopplung), eingezogen. */
    private fun PayTestWorld.captured(bookingState: String = "CONFIRMED"): PayBooking {
        val b = newBooking(bookingState); begin(b); drain()
        fx.tx.run(tenant) { j -> payments.completeCouplingIn(j, view(b)!!.id, CouplingKind.CONFIRM_BOOKING) }; drain()
        check(view(b)!!.state == PaymentState.CAPTURED) { "Testaufbau: ${view(b)!!.state}" }
        return b
    }

    private fun PayTestWorld.cancel(b: PayBooking, reason: String = "PASSENGER_REQUEST", cause: String = "FAHRGAST") =
        fx.tx.run(tenant) { j -> payments.onBookingCancelledIn(j, CancelledBooking(b.id, reason, cause)) }

    private fun PayTestWorld.caseState(b: PayBooking) = fx.admin.queryForList("SELECT state FROM taktovia.refund_case WHERE payment_id = ? ORDER BY created_at", String::class.java, view(b)!!.id).map { it!! }

    @Test
    fun void_bei_autorisierter_zahlung_ohne_einzug() {
        val psp = PayScriptedPsp(); val w = world(psp)
        w.use {
            val b = w.newBooking("CONFIRMED"); w.begin(b); w.drain()
            val hint = w.cancel(b)!!
            assertEquals(RefundStatus.VOID_REQUESTED, hint.status); assertEquals(950, hint.amountMinor); assertEquals("EUR", hint.currency)
            assertEquals(PaymentState.VOID_PENDING, w.view(b)!!.state)
            w.drain()
            val v = w.view(b)!!
            assertEquals(PaymentState.VOIDED, v.state); assertEquals(PaymentUiStatus.VOIDED, v.status); assertEquals(0, v.capturedMinor)
            assertEquals(1, psp.effects(PaymentAction.VOID), "genau ein Void"); assertEquals(0, psp.effects(PaymentAction.CAPTURE), "kein Einzug"); assertEquals(0, psp.effects(PaymentAction.REFUND))
            // Wiederholung des Stornos (oder erneuter Aufruf der Reaktion) erzeugt keinen zweiten Void
            assertEquals(RefundStatus.NONE, w.cancel(b)!!.status); w.drain()
            assertEquals(1, psp.effects(PaymentAction.VOID)); assertEquals("CONFIRMED" to 3L, w.bookingState(b), "das Zahlungsmodul schreibt die Buchung nie")
        }
    }

    @Test
    fun vollerstattung_nach_einzug_erst_nach_psp_bestaetigung_erstattet() {
        val psp = PayScriptedPsp(); val w = world(psp)
        w.use {
            val b = w.captured()
            val hint = w.cancel(b)!!
            assertEquals(RefundStatus.REQUESTED, hint.status); assertEquals(950, hint.amountMinor)
            val open = w.view(b)!!
            assertEquals(PaymentState.REFUND_PENDING, open.state); assertEquals(PaymentUiStatus.CAPTURED, open.status, "bis zur Bestätigung gilt der bisherige Stand, nicht „erstattet“")
            assertEquals(950, open.refundOpenMinor); assertEquals(0, open.refundedMinor); assertEquals("REQUESTED", open.refund!!.state)
            assertEquals(0, psp.calls(PaymentAction.REFUND), "beauftragt, aber noch nicht beim PSP ausgeführt (kein PSP-Aufruf in der Stornotransaktion)")
            w.drain()
            val v = w.view(b)!!
            assertEquals(PaymentState.REFUNDED, v.state); assertEquals(PaymentUiStatus.REFUNDED, v.status); assertEquals(950, v.refundedMinor); assertEquals(0, v.refundOpenMinor); assertEquals("CONFIRMED", v.refund!!.state)
            assertEquals(1, psp.effects(PaymentAction.REFUND)); assertEquals(1, w.events("payment.refund_recorded", v.id)); assertEquals(1, w.eventsOf("payment.refund_requested", v.id))
            assertEquals(listOf("CONFIRMED"), w.caseState(b)); assertEquals("CONFIRMED" to 3L, w.bookingState(b))
        }
    }

    @Test
    fun erstattung_ueber_erfasstem_betrag_wird_abgewiesen() {
        val psp = PayScriptedPsp(); val w = world(psp)
        w.use {
            val b = w.captured(); val id = w.view(b)!!.id
            for (amount in listOf(951L, 5000L)) {
                val e = assertThrows(ProblemException::class.java) { w.fx.tx.run(w.tenant) { j -> w.payments.requestRefundIn(j, RefundRequest(id, UUID.randomUUID(), "OPERATOR_CANCELLATION", "BETREIBER", amount)) } }
                assertEquals(ErrorCode.CONFLICT, e.code, "Betrag $amount")
            }
            assertEquals(0, w.count("SELECT count(*) FROM taktovia.refund_case WHERE payment_id = ?", id)); assertEquals(PaymentState.CAPTURED, w.view(b)!!.state)
            w.fx.tx.run(w.tenant) { j -> w.payments.requestRefundIn(j, RefundRequest(id, UUID.randomUUID(), "OPERATOR_CANCELLATION", "BETREIBER", 950)) }   // genau der erfasste Betrag ist zulässig
            val e = assertThrows(ProblemException::class.java) { w.fx.tx.run(w.tenant) { j -> w.payments.requestRefundIn(j, RefundRequest(id, UUID.randomUUID(), "OPERATOR_CANCELLATION", "BETREIBER", 1)) } }
            assertEquals(ErrorCode.CONFLICT, e.code, "die Summe der Aufträge überschreitet den erfassten Betrag")
            assertEquals(0, psp.calls(PaymentAction.REFUND) + w.count("SELECT count(*) FROM taktovia.refund_case WHERE payment_id = ? AND requested_minor > 950", id))
        }
    }

    @Test
    fun zwei_parallele_erstattungsauftraege_ueberschreiten_nie_den_erfassten_betrag() {
        val psp = PayScriptedPsp(); val w = world(psp)
        w.use {
            val b = w.captured(); val id = w.view(b)!!.id
            val n = 14; val pool = Executors.newFixedThreadPool(n); val start = CountDownLatch(1)
            val results = (1..n).map { pool.submit<Boolean> {
                start.await()
                try { w.fx.tx.run(w.tenant) { j -> w.payments.requestRefundIn(j, RefundRequest(id, UUID.randomUUID(), "OPERATOR_CANCELLATION", "BETREIBER", 100)) }; true }
                catch (e: ProblemException) { assertEquals(ErrorCode.CONFLICT, e.code); false }
            } }
            start.countDown(); val accepted = results.count { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
            assertEquals(9, accepted, "9 x 100 passen unter den erfassten Betrag 950, jeder weitere Auftrag wird abgewiesen")
            assertEquals(900, w.count("SELECT sum(requested_minor) FROM taktovia.refund_case WHERE payment_id = ?", id))
            w.drain(60)
            val v = w.view(b)!!
            val dbg = w.fx.admin.queryForList("SELECT po.seq || ':' || o.status || '/' || COALESCE(o.result_code, '') || '/' || (SELECT c.state FROM taktovia.refund_case c WHERE c.command_ref = po.command_ref) FROM taktovia.operation o JOIN taktovia.payment_operation po ON po.operation_id = o.id WHERE po.action = 'REFUND' AND o.tenant_id = ? ORDER BY po.seq", String::class.java, w.tenant.value).toString()
            assertEquals(900, v.refundedMinor, "Operationen $dbg, Zustand ${v.state}, Fälle ${w.caseState(b)}"); assertEquals(PaymentState.PARTIALLY_REFUNDED, v.state); assertEquals(9, psp.effects(PaymentAction.REFUND)); assertTrue(v.refundedMinor <= v.capturedMinor)
            assertEquals(List(9) { "CONFIRMED" }, w.caseState(b)); assertEquals(0, v.refundOpenMinor)
        }
    }

    @Test
    fun webhook_duplikat_und_wiederholtes_storno_erhoehen_refunded_minor_nicht() {
        val psp = PayScriptedPsp(); val w = world(psp)
        w.use {
            val b = w.captured()
            psp.delayedSettlement = true   // der PSP bestätigt die Erstattung erst später (Zustand REFUND_PENDING beim PSP)
            w.cancel(b); w.drain(3)
            assertEquals(PaymentState.REFUND_PENDING, w.view(b)!!.state, "noch nicht bestätigt: nie „erstattet“"); assertEquals(0, w.view(b)!!.refundedMinor)
            psp.settle()
            val ref = w.fx.admin.queryForObject("SELECT provider_ref FROM taktovia.payment_intent WHERE id = ?", String::class.java, w.view(b)!!.id)!!
            repeat(5) { w.fx.tx.run(w.tenant) { j -> w.payments.reactToProviderEventIn(j, psp.provider, ref) } }   // doppelt zugestellte PSP-Ereignisse
            w.clock.advance(Duration.ofHours(2)); w.drain(10)
            val v = w.view(b)!!
            assertEquals(PaymentState.REFUNDED, v.state); assertEquals(950, v.refundedMinor); assertEquals(1, w.events("payment.refund_recorded", v.id))
            // weitere Ereignisse und ein wiederholtes Storno ändern nichts mehr
            repeat(3) { w.fx.tx.run(w.tenant) { j -> w.payments.reactToProviderEventIn(j, psp.provider, ref) } }; w.drain(10)
            assertEquals(RefundStatus.NONE, w.cancel(b)!!.status); w.drain(10)
            assertEquals(950, w.view(b)!!.refundedMinor); assertEquals(1, psp.effects(PaymentAction.REFUND)); assertEquals(1, w.count("SELECT count(*) FROM taktovia.refund_case WHERE payment_id = ?", v.id))
        }
    }

    @Test
    fun prozessabbruch_nach_psp_erfolg_abgleich_ohne_zweite_erstattung() {
        val psp = PayScriptedPsp(); val w = world(psp)
        w.use {
            val b = w.captured(); w.cancel(b)
            psp.inject(PaymentAction.REFUND, PayScriptedPsp.Fault.CRASH_AFTER_EFFECT)
            w.drain(1)
            assertEquals(PaymentState.REFUND_PENDING, w.view(b)!!.state, "lokal ist nichts angekommen"); assertEquals(1, psp.effects(PaymentAction.REFUND)); assertEquals(0, w.view(b)!!.refundedMinor)
            w.clock.advance(Duration.ofHours(2)); w.drain()
            val v = w.view(b)!!
            assertEquals(PaymentState.REFUNDED, v.state); assertEquals(950, v.refundedMinor); assertEquals(1, psp.effects(PaymentAction.REFUND)); assertEquals(1, psp.calls(PaymentAction.REFUND), "der Abgleich wiederholt den Aufruf nicht")
            // unbestimmtes Ergebnis (Timeout ohne Wirkung): der Abgleich findet nichts und wiederholt mit derselben Kommandoreferenz
            val psp2 = PayScriptedPsp(); val w2 = world(psp2)
            w2.use {
                val b2 = w2.captured(); w2.cancel(b2); psp2.inject(PaymentAction.REFUND, PayScriptedPsp.Fault.TIMEOUT_BEFORE_EFFECT); w2.drain(1)
                assertEquals(0, psp2.effects(PaymentAction.REFUND)); assertEquals(PaymentState.REFUND_PENDING, w2.view(b2)!!.state)
                w2.clock.advance(Duration.ofHours(2)); w2.drain()
                assertEquals(PaymentState.REFUNDED, w2.view(b2)!!.state); assertEquals(1, psp2.effects(PaymentAction.REFUND))
            }
        }
    }

    @Test
    fun fehlgeschlagene_erstattung_buchung_bleibt_storniert_betrag_bleibt_offen_sichtbar() {
        val psp = PayScriptedPsp(); val w = world(psp)
        w.use {
            val b = w.captured("CANCELED")   // die Buchung ist storniert (der Storno gehört der Buchung)
            w.cancel(b); psp.inject(PaymentAction.REFUND, PayScriptedPsp.Fault.DECLINE)
            w.drain()
            val v = w.view(b)!!
            assertEquals(PaymentState.REFUND_PENDING, v.state, "kein stilles Aufgeben: die Zahlung bleibt im Erstattungszustand"); assertEquals(0, v.refundedMinor)
            assertEquals(950, v.refundOpenMinor, "der offene Betrag bleibt sichtbar"); assertEquals("FAILED", v.refund!!.state); assertEquals("REFUND_DECLINED", v.refund!!.failureCode)
            assertEquals(1, w.eventsOf("payment.refund_failed", v.id)); assertEquals(0, w.events("payment.refund_recorded", v.id)); assertEquals(0, psp.effects(PaymentAction.REFUND))
            assertEquals("CANCELED" to 3L, w.bookingState(b), "die Buchung bleibt storniert und unberührt")
            assertTrue(w.opStatus(w.fx.admin.queryForObject("SELECT operation_id FROM taktovia.payment_operation WHERE payment_id = ? AND action = 'REFUND'", UUID::class.java, v.id)!!).startsWith("FAILED/REFUND_DECLINED"))
        }
    }

    @Test
    fun erstattungshoehe_je_grund_betreiberverursachte_regel_bei_fahrzeugausfall() {
        // Testregel (D-05 offen): Kundenstorno 50 %, betreiberverursacht 100 %, No-Show 0 %
        val psp = PayScriptedPsp(); val w = world(psp, RefundRule(customerPercent = 50, operatorPercent = 100, noShowPercent = 0))
        w.use {
            val expectations = listOf(
                Triple("PASSENGER_REQUEST", "FAHRGAST", 475L), Triple("VEHICLE_OUTAGE", "FAHRZEUGAUSFALL", 950L), Triple("OPERATOR_CANCELLATION", "BETREIBER", 950L), Triple("SAFETY", "BETREIBER", 950L), Triple("NO_SHOW", "FAHRGAST", 0L),
            )
            for ((reason, cause, amount) in expectations) {
                val b = w.captured(); val hint = w.cancel(b, reason, cause)!!
                assertEquals(amount, hint.amountMinor, "$reason ($cause)")
                if (amount == 0L) { assertEquals(RefundStatus.NOT_DUE, hint.status); assertEquals(PaymentState.CAPTURED, w.view(b)!!.state, "kein Anspruch: keine Erstattung beauftragt") }
                else { assertEquals(RefundStatus.REQUESTED, hint.status); w.drain(); assertEquals(amount, w.view(b)!!.refundedMinor); assertEquals(if (amount == 950L) PaymentState.REFUNDED else PaymentState.PARTIALLY_REFUNDED, w.view(b)!!.state) }
            }
            assertEquals(4, psp.effects(PaymentAction.REFUND), "eine Erstattung je Anspruch, keine für den No-Show")
        }
    }

    @Test
    fun storno_waehrend_laufendem_einzug_wartet_auf_den_einzug_und_erstattet_danach() {
        val psp = PayScriptedPsp(); val w = world(psp)
        w.use {
            val b = w.newBooking("CONFIRMED"); w.begin(b); w.drain()
            psp.delayedSettlement = true
            w.fx.tx.run(w.tenant) { j -> w.payments.completeCouplingIn(j, w.view(b)!!.id, CouplingKind.CONFIRM_BOOKING) }; w.drain(2)   // der Einzug schwebt beim PSP
            assertEquals(PaymentState.CAPTURE_PENDING, w.view(b)!!.state)
            val hint = w.cancel(b)!!
            assertEquals(RefundStatus.PENDING_CAPTURE, hint.status); assertEquals(listOf("PENDING_CAPTURE"), w.caseState(b)); assertEquals(0, psp.effects(PaymentAction.REFUND))
            psp.settle(); psp.delayedSettlement = false
            w.clock.advance(Duration.ofHours(2)); w.drain(20)
            val v = w.view(b)!!
            assertEquals(PaymentState.REFUNDED, v.state); assertEquals(950, v.refundedMinor); assertEquals(1, psp.effects(PaymentAction.CAPTURE)); assertEquals(1, psp.effects(PaymentAction.REFUND)); assertEquals(listOf("CONFIRMED"), w.caseState(b))
        }
    }

    @Test
    fun storno_ohne_zahlung_oder_vor_der_autorisierung_beauftragt_nichts_und_hebt_eine_spaetere_autorisierung_auf() {
        val psp = PayScriptedPsp(); val w = world(psp)
        w.use {
            assertNull(w.cancel(w.newBooking("CANCELED")), "keine Zahlung: kein Erstattungshinweis")
            val b = w.newBooking("CONFIRMING"); psp.authMode = PayScriptedPsp.AuthMode.SCA; w.begin(b); w.drain()
            assertEquals(PaymentState.REQUIRES_ACTION, w.view(b)!!.state)
            assertEquals(RefundStatus.NONE, w.cancel(b)!!.status); assertEquals(0, w.count("SELECT count(*) FROM taktovia.refund_case WHERE tenant_id = ?", w.tenant.value))
            psp.completeSca(true); w.fx.tx.run(w.tenant) { j -> w.payments.scaReturnIn(j, b.id) }; w.drain()
            val v = w.view(b)!!
            assertEquals(PaymentState.VOIDED, v.state, "die später erfolgreiche Autorisierung einer stornierten Buchung wird aufgehoben"); assertEquals("BOOKING_RELEASED", v.failureCode); assertEquals(0, psp.effects(PaymentAction.CAPTURE))
            assertNotNull(v.operationId)
        }
    }

    @Test
    fun erstattungsereignisse_entsprechen_den_schemas() {
        val psp = PayScriptedPsp(); val w = world(psp)
        w.use {
            val ok = w.captured(); w.cancel(ok); w.drain()
            val failed = w.captured(); w.cancel(failed); psp.inject(PaymentAction.REFUND, PayScriptedPsp.Fault.DECLINE); w.drain()
            val registry = com.networknt.schema.SchemaRegistry.withDialect(com.networknt.schema.dialect.Dialects.getDraft202012())
            val mapper = tools.jackson.databind.json.JsonMapper.builder().build()
            for (type in listOf("payment.refund_requested", "payment.refund_recorded", "payment.refund_failed")) {
                val schema = registry.getSchema(java.nio.file.Files.readString(PaymentTestDb.repoRoot().resolve("api/events/$type.v1.schema.json")))
                val payloads = w.fx.admin.queryForList("SELECT payload::text FROM taktovia.outbox WHERE tenant_id = ? AND event_type = ?", String::class.java, w.tenant.value, type)
                assertTrue(payloads.isNotEmpty(), "Ereignis $type wurde geschrieben")
                payloads.forEach { assertEquals(emptyList<String>(), schema.validate(mapper.readTree(it)).map { m -> m.message }, "$type: $it") }
            }
        }
    }
}

package de.taktovia.adapters.psp

import de.taktovia.adapters.psp.api.PspAdapter
import de.taktovia.adapters.psp.api.PspAuthorize
import de.taktovia.adapters.psp.api.PspCapture
import de.taktovia.adapters.psp.api.PspCommandRef
import de.taktovia.adapters.psp.api.PspErrorClass
import de.taktovia.adapters.psp.api.PspOperation
import de.taktovia.adapters.psp.api.PspOutcome
import de.taktovia.adapters.psp.api.PspPayment
import de.taktovia.adapters.psp.api.PspPaymentState
import de.taktovia.adapters.psp.api.PspReconciliation
import de.taktovia.adapters.psp.api.PspRefund
import de.taktovia.adapters.psp.api.reconcile
import de.taktovia.adapters.psp.api.reconcileAuthorize
import de.taktovia.adapters.psp.api.PspVoid
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.net.URI
import java.time.Duration
import java.util.UUID

/**
 * PSP-Adapter gegen den echten PSP-Simulator (WP-M2-29): alle fünf Operationen, Fehlerklassen, Timeout/verlorene Antwort/Duplikat/503 mit derselben Kommandoreferenz.
 * Ergebnisse sind Simulator-Ergebnisse, kein Nachweis gegen einen realen PSP (siehe `psp-adapter.md`, WP-M0-29).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PspAdapterSimulatorTest {
    private lateinit var sim: PspSimulatorHarness
    private lateinit var psp: PspAdapter
    private lateinit var fast: PspAdapter   // kurzes Zeitlimit für Timeout-Fälle

    @BeforeAll fun up() { sim = PspSimulatorHarness.start(); psp = PspAdapter.http("simpsp", URI(sim.url), Duration.ofSeconds(10)); fast = PspAdapter.http("simpsp", URI(sim.url), Duration.ofMillis(700)) }
    @AfterAll fun down() { if (::sim.isInitialized) sim.close() }
    @BeforeEach fun clean() { sim.delete("/control/faults") }

    private fun ref(op: PspOperation) = PspCommandRef.derive(UUID.randomUUID().toString(), op)
    private fun merchant() = "m-" + UUID.randomUUID()
    private fun ok(o: PspOutcome): PspPayment = (o as? PspOutcome.Ok ?: throw AssertionError("erwartet Ok, war $o")).payment
    private fun authorized(amount: Long = 1250, method: String = "pm_ok") = ok(psp.authorize(PspAuthorize(ref(PspOperation.AUTHORIZE), merchant(), amount, "EUR", method)))

    @Test
    fun alle_fuenf_operationen_gegen_den_simulator() {
        val p = authorized(1250)
        assertEquals(PspPaymentState.AUTHORIZED, p.state); assertEquals(1250L, p.authorizedMinor)
        assertEquals(p.paymentId, ok(psp.status(p.paymentId)).paymentId, "Status nach payment_id")
        assertEquals(p.paymentId, ok(psp.status(p.providerRef)).paymentId, "Status nach PSP-Referenz")
        assertEquals(p.paymentId, ok(psp.statusByMerchantRef(p.merchantRef)).paymentId, "Status nach Händlerreferenz")
        val cap = ok(psp.capture(PspCapture(ref(PspOperation.CAPTURE), p.paymentId, 1000)))
        assertEquals(PspPaymentState.CAPTURED, cap.state); assertEquals(1000L, cap.capturedMinor)
        val ref1 = ok(psp.refund(PspRefund(ref(PspOperation.REFUND), p.paymentId, 400)))
        assertEquals(PspPaymentState.PARTIALLY_REFUNDED, ref1.state); assertEquals(400L, ref1.refundedMinor)
        val ref2 = ok(psp.refund(PspRefund(ref(PspOperation.REFUND), p.paymentId, 600)))
        assertEquals(PspPaymentState.REFUNDED, ref2.state); assertEquals(1000L, ref2.refundedMinor)
        val q = authorized(700)
        val v = ok(psp.void(PspVoid(ref(PspOperation.VOID), q.paymentId)))
        assertEquals(PspPaymentState.VOIDED, v.state)
        assertEquals(PspPaymentState.AUTHORIZED, authorized(300).state)
    }

    @Test
    fun ablehnung_und_unzulaessige_operationen_haben_eigene_fehlerklassen_und_keinen_effekt() {
        assertEquals(PspPaymentState.AUTHORIZATION_FAILED, authorized(500, "pm_decline").state, "Ablehnung der Autorisierung ist ein gültiges Zahlungsergebnis")
        assertEquals(PspPaymentState.REQUIRES_ACTION, authorized(500, "pm_sca").state)
        val p = authorized(1000)
        val over = psp.capture(PspCapture(ref(PspOperation.CAPTURE), p.paymentId, 1001)) as PspOutcome.Failed
        assertEquals(PspErrorClass.REJECTED, over.errorClass); assertEquals("AMOUNT_EXCEEDS_AUTHORIZED", over.code)
        assertEquals(PspPaymentState.AUTHORIZED, ok(psp.status(p.paymentId)).state, "abgelehnter Capture ändert nichts")
        ok(psp.void(PspVoid(ref(PspOperation.VOID), p.paymentId)))
        assertEquals(PspErrorClass.CONFLICT, (psp.capture(PspCapture(ref(PspOperation.CAPTURE), p.paymentId)) as PspOutcome.Failed).errorClass, "Capture nach Void")
        assertEquals(PspErrorClass.REJECTED, (psp.status("pay_unbekannt") as PspOutcome.Failed).errorClass, "unbekannte Referenz")
        sim.fault("capture", "decline", times = 1)
        val q = authorized(900)
        assertEquals(PspErrorClass.DECLINED, (psp.capture(PspCapture(ref(PspOperation.CAPTURE), q.paymentId)) as PspOutcome.Failed).errorClass, "definitive Ablehnung des Capture")
        assertEquals(PspPaymentState.AUTHORIZED, ok(psp.status(q.paymentId)).state)
    }

    @Test
    fun timeout_liefert_unbestimmt_und_die_wiederholung_verwendet_dieselbe_kommandoreferenz() {
        val p = authorized(1250)
        val cmd = PspCapture(ref(PspOperation.CAPTURE), p.paymentId, 1250)
        sim.fault("capture", "timeout", times = 1, holdSeconds = 3.0)   // Wirkung nicht angewandt, keine Antwort innerhalb des Zeitlimits
        val first = fast.capture(cmd)
        assertEquals(PspOutcome.Unknown(cmd.commandRef, "timeout"), first, "Timeout ist unbestimmt, kein Misserfolg")
        // Abgleich über die Statusabfrage mit der Kommandoreferenz: der PSP hat die Operation nicht ausgeführt
        assertTrue(psp.reconcile(PspOperation.CAPTURE, p.paymentId, cmd.commandRef) is PspReconciliation.NotPerformed)
        assertEquals(404, (psp.statusByCommandRef(cmd.commandRef) as PspOutcome.Failed).httpStatus, "der PSP kennt die Kommandoreferenz nicht")
        val retry = ok(psp.capture(cmd))   // Wiederholung mit demselben Kommando (gleiche Referenz)
        assertEquals(PspPaymentState.CAPTURED, retry.state)
        val calls = sim.calls("capture").filter { it["payment_id"]?.asString() == p.paymentId }
        assertEquals(2, calls.size); assertEquals(setOf(cmd.commandRef.value), calls.map { it["idempotency_key"].asString() }.toSet(), "beide Versuche mit derselben Kommandoreferenz")
        assertEquals(1, calls.count { it["effect_applied"].asBoolean() }, "genau eine Wirkung")
    }

    @Test
    fun timeout_der_autorisierung_ohne_wirkung_wird_als_nicht_ausgefuehrt_abgeglichen() {
        val cmd = PspAuthorize(ref(PspOperation.AUTHORIZE), merchant(), 300, "EUR", "pm_ok")
        sim.fault("authorize", "timeout", times = 1, holdSeconds = 3.0)
        assertTrue(fast.authorize(cmd) is PspOutcome.Unknown)
        assertEquals(PspReconciliation.NotPerformed(null), psp.reconcileAuthorize(cmd))
        assertEquals(PspPaymentState.AUTHORIZED, ok(psp.authorize(cmd)).state, "Wiederholung mit derselben Referenz")
        assertEquals(1, sim.calls("authorize").count { it["merchant_ref"].asString() == cmd.merchantRef && it["effect_applied"].asBoolean() })
    }

    @Test
    fun verlorene_antwort_wird_ueber_status_und_wiederholung_aufgeloest_ohne_zweiten_charge() {
        val cmd = PspAuthorize(ref(PspOperation.AUTHORIZE), merchant(), 2000, "EUR", "pm_ok")
        sim.fault("authorize", "lost_response", times = 1)
        assertEquals(PspOutcome.Unknown(cmd.commandRef, "connection_lost"), fast.authorize(cmd))
        val resolved = (psp.reconcileAuthorize(cmd) as PspReconciliation.Performed).payment   // die Wirkung ist beim PSP vorhanden
        assertTrue(resolved.performed(PspOperation.AUTHORIZE, cmd.commandRef)); assertEquals(PspPaymentState.AUTHORIZED, resolved.state)
        assertEquals(resolved.paymentId, ok(psp.statusByCommandRef(cmd.commandRef)).paymentId)
        val retry = ok(psp.authorize(cmd))
        assertTrue(retry.replayed, "Antwort des PSP aus der Idempotenz-Wiederholung"); assertEquals(resolved.paymentId, retry.paymentId, "dieselbe Zahlung, kein zweiter Charge")
        // nur eine Zahlung zu dieser Händlerreferenz (zweite Autorisierung ohne Schlüssel würde der Simulator mit 409 abweisen)
        assertEquals(resolved.paymentId, ok(psp.statusByMerchantRef(cmd.merchantRef)).paymentId)
        // capture mit verlorener Antwort: genau ein Einzug
        sim.fault("capture", "lost_response", times = 1)
        val cap = PspCapture(ref(PspOperation.CAPTURE), resolved.paymentId)
        assertTrue(fast.capture(cap) is PspOutcome.Unknown)
        assertTrue(ok(psp.capture(cap)).replayed)
        val final = ok(psp.status(resolved.paymentId)); assertEquals(2000L, final.capturedMinor); assertEquals(1, final.operations.count { it.kind == PspOperation.CAPTURE })
    }

    @Test
    fun doppelt_eintreffende_anfrage_fuehrt_zu_genau_einer_wirkung() {
        val p = authorized(800)
        sim.fault("capture", "duplicate", times = 1)
        val cmd = PspCapture(ref(PspOperation.CAPTURE), p.paymentId)
        assertEquals(PspPaymentState.CAPTURED, ok(psp.capture(cmd)).state)
        val final = ok(psp.status(p.paymentId))
        assertEquals(800L, final.capturedMinor); assertEquals(1, final.operations.count { it.kind == PspOperation.CAPTURE })
    }

    @Test
    fun nicht_erreichbar_ist_kein_effekt_und_die_wiederholung_gelingt_mit_derselben_referenz() {
        val cmd = PspAuthorize(ref(PspOperation.AUTHORIZE), merchant(), 100, "EUR", "pm_ok")
        sim.fault("authorize", "unavailable", times = 1)
        val first = psp.authorize(cmd)
        assertTrue(first is PspOutcome.Unavailable, first.toString()); assertEquals(Duration.ofSeconds(1), (first as PspOutcome.Unavailable).retryAfter)
        assertEquals(PspPaymentState.AUTHORIZED, ok(psp.authorize(cmd)).state)
        val dead = PspAdapter.http("simpsp", URI("http://127.0.0.1:1"), Duration.ofSeconds(2))   // verweigerte Verbindung: nie gesendet
        assertTrue(dead.authorize(cmd) is PspOutcome.Unavailable)
    }

    @Test
    fun dieselbe_referenz_mit_anderem_inhalt_wird_nie_als_wiederholung_behandelt() {
        val p = authorized(1000)
        val cmd = ref(PspOperation.CAPTURE)
        ok(psp.capture(PspCapture(cmd, p.paymentId, 600)))
        val reuse = psp.refund(PspRefund(cmd, p.paymentId, 100))   // gleicher Schlüssel, anderer Vorgang
        assertTrue(reuse is PspOutcome.Failed && reuse.errorClass == PspErrorClass.REJECTED, reuse.toString())
    }

    @Test
    fun kommandoreferenz_ist_deterministisch_je_vorgang_und_art() {
        assertEquals(PspCommandRef.derive("op-1", PspOperation.CAPTURE), PspCommandRef.derive("op-1", PspOperation.CAPTURE))
        assertNotEquals(PspCommandRef.derive("op-1", PspOperation.CAPTURE), PspCommandRef.derive("op-1", PspOperation.VOID))
        assertNotEquals(PspCommandRef.derive("op-1", PspOperation.CAPTURE), PspCommandRef.derive("op-2", PspOperation.CAPTURE))
        assertNotEquals(PspCommandRef.derive("op-1", PspOperation.REFUND, 1), PspCommandRef.derive("op-1", PspOperation.REFUND, 2), "zweite Teilerstattung ist ein neuer Vorgang")
        assertThrows(IllegalArgumentException::class.java) { PspCommandRef("kurz") }
    }

    @Test
    fun aufruf_innerhalb_einer_offenen_transaktion_scheitert_vor_dem_senden() {
        val before = sim.calls("authorize").size
        TransactionSynchronizationManager.initSynchronization(); TransactionSynchronizationManager.setActualTransactionActive(true)
        try {
            val e = assertThrows(IllegalStateException::class.java) { psp.authorize(PspAuthorize(ref(PspOperation.AUTHORIZE), merchant(), 100, "EUR", "pm_ok")) }
            assertTrue(e.message!!.contains("Leitlinie 3"))
            assertThrows(IllegalStateException::class.java) { psp.status("x") }
        } finally { TransactionSynchronizationManager.setActualTransactionActive(false); TransactionSynchronizationManager.clearSynchronization() }
        assertEquals(before, sim.calls("authorize").size, "nichts gesendet")
    }
}

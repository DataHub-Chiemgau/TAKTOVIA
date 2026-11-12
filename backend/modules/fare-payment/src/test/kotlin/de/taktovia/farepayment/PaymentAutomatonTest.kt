package de.taktovia.farepayment

import de.taktovia.farepayment.api.CouplingKind
import de.taktovia.farepayment.api.PaymentAction
import de.taktovia.farepayment.api.PaymentUiStatus
import de.taktovia.platform.operation.OperationConfig
import de.taktovia.platform.operation.OperationRunner
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.statemachine.PaymentState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
 * WP-M2-30 / LH-6.10-PAY-004: Zahlungsautomat gegen die echte Datenbank unter der Runtime-Rolle, echte Operationen (Lease, Fencing) und eine skriptbare Gegenstelle ([PayScriptedPsp], kein
 * Simulator: der echte PSP-Simulator läuft in `PaymentSimulatorFlowTest` der Anwendung). Die Buchung ist ein Datensatz; das Zahlungsmodul schreibt sie nie, jeder Test prüft das über den Zustand
 * und die Revision der Buchung.
 */
class PaymentAutomatonTest {
    private fun world(psp: PayScriptedPsp = PayScriptedPsp()) = PayTestWorld(psp)

    @Test
    fun authorize_und_capture_positiv_ohne_buchungsbezug_im_zahlungsmodul() {
        val psp = PayScriptedPsp(); val w = world(psp)
        w.use {
            val b = w.newBooking("CONFIRMING")
            val begun = w.begin(b)
            assertFalse(begun.replayed); assertEquals(PaymentState.CREATED, begun.payment.state); assertEquals(950, begun.payment.requestedMinor)
            assertEquals(0, psp.calls(PaymentAction.AUTHORIZE), "der Beginn der Zahlung ruft den PSP nicht (kein PSP-Aufruf in der Transaktion der Zahlungsauswahl)")
            w.drain()
            val authorized = w.view(b)!!
            assertEquals(PaymentState.AUTHORIZED, authorized.state); assertEquals(PaymentUiStatus.AUTHORIZED, authorized.status); assertEquals(950, authorized.authorizedMinor)
            assertEquals("SUCCEEDED/AUTHORIZED", w.opStatus(begun.operationId))
            assertEquals(1, w.events("payment.authorized", authorized.id))
            // OP-5: Voraussetzung für CONFIRMED ist die Autorisierung; die Anwendung bestätigt die Buchung und meldet die Kopplung zurück
            val task = w.fx.tx.run(w.tenant) { j -> w.payments.couplingTasksIn(j) }.single()
            assertEquals(CouplingKind.CONFIRM_BOOKING, task.kind); assertEquals(b.id, task.bookingId)
            w.fx.tx.run(w.tenant) { j -> w.payments.completeCouplingIn(j, task.paymentId, CouplingKind.CONFIRM_BOOKING) }
            assertEquals(PaymentState.CAPTURE_PENDING, w.view(b)!!.state)
            assertEquals(emptyList<Any>(), w.fx.tx.run(w.tenant) { j -> w.payments.couplingTasksIn(j) }, "Kopplung ist vollzogen")
            w.drain()
            val captured = w.view(b)!!
            assertEquals(PaymentState.CAPTURED, captured.state); assertEquals(950, captured.capturedMinor); assertEquals(1, psp.effects(PaymentAction.CAPTURE))
            assertEquals(1, w.events("payment.captured", captured.id))
            assertEquals("CONFIRMING" to 3L, w.bookingState(b), "das Zahlungsmodul schreibt die Buchung nie")
            // jeder Übergang genau einmal: CREATED->AUTHORIZED->CAPTURE_PENDING->CAPTURED
            assertEquals(3, w.events("payment.state_changed", captured.id))
        }
    }

    @Test
    fun zahl_und_buchungsstatus_getrennt_gespeichert_und_abrufbar() {
        val psp = PayScriptedPsp(); val w = world(psp)
        w.use {
            // getrennte Speicherung: keine gemeinsamen Zustandsfelder in beiden Tabellen
            val bookingCols = w.fx.admin.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema = 'taktovia' AND table_name = 'booking'", String::class.java).map { it!! }
            val paymentCols = w.fx.admin.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema = 'taktovia' AND table_name = 'payment_intent'", String::class.java).map { it!! }
            assertTrue(bookingCols.none { it.contains("pay") || it.contains("captur") || it.contains("refund") }, "booking führt keinen Zahlstatus: $bookingCols")
            assertTrue(paymentCols.none { it.startsWith("booking_state") || it == "booking_status" }, "payment_intent führt keinen Buchungsstatus: $paymentCols")
            // jede Kombination ist darstellbar und getrennt abrufbar: Zahlstatus bleibt, wenn sich der Buchungsstatus ändert, und umgekehrt
            val combos = listOf("CONFIRMING" to PaymentState.CREATED, "CONFIRMED" to PaymentState.CAPTURE_UNKNOWN, "CONFIRMED" to PaymentState.CAPTURED, "CANCELED" to PaymentState.AUTHORIZATION_FAILED, "COMPLETED" to PaymentState.RECONCILING)
            for ((bookingState, paymentState) in combos) {
                val b = w.newBooking(bookingState)
                val p = w.begin(b).payment
                if (paymentState != PaymentState.CREATED) {
                    // Testaufbau: Zustand ohne PSP-Gegenstelle setzen (Übergangstrigger für die Dauer dieser Transaktion aus)
                    w.fx.admin.dataSource!!.connection.use { c ->
                        c.autoCommit = false
                        c.createStatement().use { it.execute("SET LOCAL session_replication_role = replica") }
                        c.prepareStatement("UPDATE taktovia.payment_intent SET state = ?, provider_ref = 'psp-${p.id}', authorized_minor = 950, captured_minor = ? WHERE id = ?").use {
                            it.setString(1, paymentState.name); it.setLong(2, if (paymentState == PaymentState.CAPTURED) 950 else 0); it.setObject(3, p.id); it.executeUpdate() }
                        c.commit()
                    }
                }
                val v = w.view(b)!!
                assertEquals(paymentState, v.state, "Zahlstatus $paymentState bei Buchung $bookingState"); assertEquals(bookingState, w.bookingState(b).first)
                assertEquals(if (paymentState == PaymentState.CAPTURE_UNKNOWN || paymentState == PaymentState.RECONCILING) PaymentUiStatus.UNKNOWN else PaymentUiStatus.valueOf(paymentState.name), v.status)
            }
        }
    }

    @Test
    fun capture_fehler_laesst_buchung_bestaetigt() {
        val psp = PayScriptedPsp(); val w = world(psp)
        w.use {
            val b = w.newBooking("CONFIRMING"); val begun = w.begin(b); w.drain()
            // die Anwendung hat die Buchung bestätigt (Revision 4) und stößt den Einzug an
            w.fx.admin.update("UPDATE taktovia.booking SET state = 'CONFIRMED', revision = 4 WHERE id = ?", b.id)
            w.fx.tx.run(w.tenant) { j -> w.payments.completeCouplingIn(j, begun.payment.id, CouplingKind.CONFIRM_BOOKING) }
            psp.inject(PaymentAction.CAPTURE, PayScriptedPsp.Fault.DECLINE)
            w.drain()
            val v = w.view(b)!!
            assertEquals(PaymentState.RECONCILING, v.state, "Klärfall, nicht stillschweigend bezahlt oder gescheitert"); assertEquals(PaymentUiStatus.UNKNOWN, v.status); assertEquals("CAPTURE_DECLINED", v.failureCode)
            assertEquals(0, v.capturedMinor); assertEquals(0, psp.effects(PaymentAction.CAPTURE))
            assertEquals("CONFIRMED" to 4L, w.bookingState(b), "Buchung bleibt bestätigt, weder storniert noch abgeschlossen, Revision unverändert")
            assertEquals(0, w.events("payment.captured", v.id)); assertEquals(1, w.events("payment.outcome_unknown", v.id))
            assertTrue(w.opStatus(w.fx.admin.queryForObject("SELECT operation_id FROM taktovia.payment_operation WHERE payment_id = ? AND action = 'CAPTURE'", UUID::class.java, v.id)!!).startsWith("FAILED/CAPTURE_DECLINED"))
            // die Kopplung bleibt offen für den Betrieb, die Zahlung wird nicht noch einmal auf Verdacht eingezogen
            w.clock.advance(Duration.ofHours(3)); w.drain()
            assertEquals(1, psp.calls(PaymentAction.CAPTURE), "kein zweiter Capture-Versuch auf Verdacht")
        }
    }

    @Test
    fun psp_timeout_unbestimmt_genau_eine_belastung() {
        // AT-15, Variante 1: Antwort verloren, der PSP hat autorisiert
        val psp = PayScriptedPsp(); val w = world(psp)
        w.use {
            val b = w.newBooking(); val begun = w.begin(b)
            psp.inject(PaymentAction.AUTHORIZE, PayScriptedPsp.Fault.LOST_RESPONSE)
            w.drain(1)
            val unknown = w.view(b)!!
            assertEquals(PaymentState.CREATED, unknown.state); assertEquals(PaymentUiStatus.UNKNOWN, unknown.status, "Zahlstatus unbestimmt")
            assertEquals("RECONCILING/AUTHORIZE_OUTCOME_UNKNOWN", w.opStatus(begun.operationId)); assertEquals(1, psp.effects(PaymentAction.AUTHORIZE))
            assertEquals(1, w.events("payment.outcome_unknown", unknown.id)); assertEquals("CONFIRMING" to 3L, w.bookingState(b))
            // kein Abgleich vor der Fälligkeit; danach über die Händlerreferenz (PSP-Referenz ist lokal noch unbekannt)
            assertEquals(0, w.drain()); w.clock.advance(Duration.ofHours(2)); w.drain()
            val settled = w.view(b)!!
            assertEquals(PaymentState.AUTHORIZED, settled.state); assertEquals(PaymentUiStatus.AUTHORIZED, settled.status); assertEquals(1, psp.effects(PaymentAction.AUTHORIZE), "genau eine Belastung")
            assertEquals("SUCCEEDED/AUTHORIZED", w.opStatus(begun.operationId))
            // ein später eintreffendes identisches PSP-Ereignis ändert nichts: Abgleich über die jetzt bekannte PSP-Referenz
            val ref = w.fx.admin.queryForObject("SELECT provider_ref FROM taktovia.payment_intent WHERE id = ?", String::class.java, settled.id)!!
            repeat(3) { w.fx.tx.run(w.tenant) { j -> w.payments.reactToProviderEventIn(j, psp.provider, ref) } }
            w.drain(); w.drain()
            assertEquals(PaymentState.AUTHORIZED, w.view(b)!!.state); assertEquals(1, w.events("payment.authorized", settled.id)); assertEquals(1, psp.effects(PaymentAction.AUTHORIZE))
            assertEquals(1, w.count("SELECT count(*) FROM taktovia.payment_intent WHERE tenant_id = ?", w.tenant.value), "keine zweite Zahlung")
        }
        // Variante 2: Anfrage nie angekommen: der Abgleich findet nichts und wiederholt mit derselben Kommandoreferenz
        val psp2 = PayScriptedPsp(); val w2 = world(psp2)
        w2.use {
            val b = w2.newBooking(); val begun = w2.begin(b)
            psp2.inject(PaymentAction.AUTHORIZE, PayScriptedPsp.Fault.TIMEOUT_BEFORE_EFFECT)
            w2.drain(1)
            assertEquals(PaymentUiStatus.UNKNOWN, w2.view(b)!!.status); assertEquals(0, psp2.effects(PaymentAction.AUTHORIZE))
            w2.clock.advance(Duration.ofHours(2)); w2.drain()
            assertEquals(PaymentState.AUTHORIZED, w2.view(b)!!.state); assertEquals(1, psp2.effects(PaymentAction.AUTHORIZE)); assertEquals("SUCCEEDED/AUTHORIZED", w2.opStatus(begun.operationId))
            assertEquals(1, psp2.payments().size)
        }
    }

    @Test
    fun zwei_worker_gleiche_operation_fencing() {
        val psp = PayScriptedPsp(); val w = world(psp)
        w.use {
            val b = w.newBooking(); val begun = w.begin(b)
            val cfg = OperationConfig(reconcileDelay = Duration.ofHours(1), lease = Duration.ofSeconds(60))
            val workerB = OperationRunner(w.fx.tx, w.payments.handlers(psp), w.outbox, w.clock, cfg, "worker-b")
            val a = w.runner.claimNext(w.tenant)!!
            assertEquals(null, workerB.claimNext(w.tenant), "solange die Lease gilt, nimmt kein zweiter Worker die Operation auf")
            w.clock.advance(Duration.ofSeconds(61))   // Worker A „hängt“: Lease läuft ab
            val takeover = workerB.claimNext(w.tenant)!!
            assertEquals(a.id, takeover.id); assertTrue(takeover.fencingToken > a.fencingToken)
            // der alte Worker ruft noch den PSP (die Wirkung dort ist idempotent), darf aber nichts mehr schreiben
            val oldResult = w.runner.execute(a)
            assertTrue(oldResult.leaseLost, "der Worker mit altem Token schreibt nichts")
            assertEquals(PaymentState.CREATED, w.view(b)!!.state)
            val newResult = workerB.execute(takeover)
            assertFalse(newResult.leaseLost)
            val v = w.view(b)!!
            assertEquals(PaymentState.AUTHORIZED, v.state); assertEquals("SUCCEEDED/AUTHORIZED", w.opStatus(begun.operationId))
            assertEquals(1, psp.effects(PaymentAction.AUTHORIZE), "eine Belastung trotz zweier Aufrufe: gleiche Kommandoreferenz"); assertEquals(2, psp.calls(PaymentAction.AUTHORIZE))
            assertEquals(1, w.events("payment.authorized", v.id)); assertEquals(1, w.events("payment.state_changed", v.id))
        }
    }

    @Test
    fun autorisierung_abgelehnt_endet_in_authorization_failed_und_gibt_die_buchung_frei() {
        val psp = PayScriptedPsp(); val w = world(psp)
        w.use {
            val b = w.newBooking(); psp.authMode = PayScriptedPsp.AuthMode.DECLINE
            val first = w.begin(b); w.drain()
            val v = w.view(b)!!
            assertEquals(PaymentState.AUTHORIZATION_FAILED, v.state); assertEquals(PaymentUiStatus.AUTHORIZATION_FAILED, v.status); assertEquals("DECLINED", v.failureCode)
            assertEquals(0, w.events("payment.authorized", v.id)); assertEquals(1, w.events("payment.authorization_failed", v.id))
            assertEquals("CONFIRMING" to 3L, w.bookingState(b), "keine Buchung wird durch die Zahlung bestätigt")
            // die Anwendung gibt Buchung und Hold frei: Arbeitsliste fordert es an, danach ist sie leer
            val task = w.fx.tx.run(w.tenant) { j -> w.payments.couplingTasksIn(j) }.single()
            assertEquals(CouplingKind.RELEASE_BOOKING, task.kind)
            w.fx.tx.run(w.tenant) { j -> w.payments.completeCouplingIn(j, task.paymentId, CouplingKind.RELEASE_BOOKING) }
            assertTrue(w.fx.tx.run(w.tenant) { j -> w.payments.couplingTasksIn(j) }.isEmpty())
            // anderer Zahlungsweg möglich: eine neue Zahlung für dieselbe Buchung (die abgelehnte blockiert nicht)
            psp.authMode = PayScriptedPsp.AuthMode.OK
            val second = w.begin(b, method = "pm_other")
            assertFalse(second.replayed); assertTrue(second.payment.id != first.payment.id)
            w.drain(); assertEquals(PaymentState.AUTHORIZED, w.view(b)!!.state)
        }
        // definitive Ablehnung des Aufrufs (Declined statt Zustand beim PSP) führt zum selben Ergebnis
        val psp2 = PayScriptedPsp(); val w2 = world(psp2)
        w2.use {
            val b = w2.newBooking(); psp2.inject(PaymentAction.AUTHORIZE, PayScriptedPsp.Fault.DECLINE)
            val begun = w2.begin(b); w2.drain()
            assertEquals(PaymentState.AUTHORIZATION_FAILED, w2.view(b)!!.state); assertEquals("SUCCEEDED/AUTHORIZATION_FAILED", w2.opStatus(begun.operationId))
            assertEquals(0, psp2.effects(PaymentAction.AUTHORIZE))
        }
    }

    @Test
    fun abweichender_betrag_wird_abgewiesen() {
        val psp = PayScriptedPsp(); val w = world(psp)
        w.use {
            val b = w.newBooking()
            for (amount in listOf(b.amountMinor + 1, b.amountMinor - 1, 0L, b.amountMinor * 2)) {
                val e = assertThrows(ProblemException::class.java) { w.begin(b, amount = amount) }
                assertTrue(e.code == ErrorCode.REQUOTE_REQUIRED || e.code == ErrorCode.VALIDATION_FAILED, "Betrag $amount: ${e.code}")
            }
            assertEquals(0, w.count("SELECT count(*) FROM taktovia.payment_intent WHERE tenant_id = ?", w.tenant.value)); assertEquals(0, w.count("SELECT count(*) FROM taktovia.payment_operation WHERE tenant_id = ?", w.tenant.value))
            assertEquals(0, psp.calls(PaymentAction.AUTHORIZE), "vor jeder Wirkung abgewiesen")
            // der PSP autorisiert einen anderen Betrag als angefragt: nie bestätigt, nie eingezogen, sondern aufgehoben
            psp.authorizeAmountOverride = 900
            w.begin(b); w.drain()
            val v = w.view(b)!!
            assertEquals(PaymentState.VOIDED, v.state); assertEquals("AMOUNT_MISMATCH", v.failureCode); assertEquals(0, v.capturedMinor); assertEquals(0, psp.effects(PaymentAction.CAPTURE)); assertEquals(1, psp.effects(PaymentAction.VOID))
            assertEquals(0, w.events("payment.authorized", v.id)); assertEquals("RELEASE_BOOKING", w.fx.tx.run(w.tenant) { j -> w.payments.couplingTasksIn(j) }.single().kind.name)
        }
    }

    @Test
    fun sca_laenger_als_hold_kein_abgelaufener_platz_autorisierung_aufgehoben() {
        val psp = PayScriptedPsp(); val w = world(psp)
        w.use {
            val b = w.newBooking(); psp.authMode = PayScriptedPsp.AuthMode.SCA
            val begun = w.begin(b, authorizeBy = w.clock.instant().plusSeconds(180)); w.drain()
            assertEquals(PaymentState.REQUIRES_ACTION, w.view(b)!!.state); assertEquals("SUCCEEDED/REQUIRES_ACTION", w.opStatus(begun.operationId))
            // der Kunde schließt SCA erst nach Ablauf der Frist ab; die Rückkehr selbst ändert nichts, erst der verifizierte Status
            w.clock.advance(Duration.ofSeconds(200)); psp.completeSca(true)
            assertEquals(PaymentState.REQUIRES_ACTION, w.view(b)!!.state, "die Rückkehr allein ändert den Zahlstatus nicht")
            w.fx.tx.run(w.tenant) { j -> w.payments.scaReturnIn(j, b.id) }
            w.drain()
            val v = w.view(b)!!
            assertEquals(PaymentState.VOIDED, v.state, "die nachträgliche Autorisierung wird aufgehoben"); assertEquals("AUTHORIZATION_EXPIRED", v.failureCode)
            assertEquals(1, psp.effects(PaymentAction.VOID)); assertEquals(0, psp.effects(PaymentAction.CAPTURE)); assertEquals(0, w.events("payment.authorized", v.id)); assertEquals(1, w.events("payment.authorization_failed", v.id))
            assertEquals("CONFIRMING" to 3L, w.bookingState(b), "kein abgelaufener Platz wird durch die Zahlung bestätigt")
            assertEquals(CouplingKind.RELEASE_BOOKING, w.fx.tx.run(w.tenant) { j -> w.payments.couplingTasksIn(j) }.single().kind)
            // der Beginn der Zahlung nach Ablauf der Frist wird gar nicht erst angenommen
            val e = assertThrows(ProblemException::class.java) { w.begin(w.newBooking(), authorizeBy = w.clock.instant().minusSeconds(1)) }
            assertEquals(ErrorCode.HOLD_EXPIRED, e.code)
        }
        // innerhalb der Frist wird dieselbe SCA bestätigt
        val psp2 = PayScriptedPsp(); val w2 = world(psp2)
        w2.use {
            val b = w2.newBooking(); psp2.authMode = PayScriptedPsp.AuthMode.SCA
            w2.begin(b, authorizeBy = w2.clock.instant().plusSeconds(180)); w2.drain(); w2.clock.advance(Duration.ofSeconds(60)); psp2.completeSca(true)
            w2.fx.tx.run(w2.tenant) { j -> w2.payments.scaReturnIn(j, b.id) }; w2.drain()
            assertEquals(PaymentState.AUTHORIZED, w2.view(b)!!.state)
        }
    }

    @Test
    fun doppelte_sca_rueckkehr_und_webhook_parallel_zu_statusabfrage() {
        val psp = PayScriptedPsp(); val w = world(psp)
        w.use {
            val b = w.newBooking(); psp.authMode = PayScriptedPsp.AuthMode.SCA
            w.begin(b); w.drain()
            val providerRef = w.fx.admin.queryForObject("SELECT provider_ref FROM taktovia.payment_intent WHERE tenant_id = ?", String::class.java, w.tenant.value)!!
            psp.completeSca(true)
            val n = 16; val pool = Executors.newFixedThreadPool(n); val start = CountDownLatch(1)
            val cfg = OperationConfig(reconcileDelay = Duration.ofHours(1), lease = Duration.ofSeconds(60))
            val futures = (0 until n).map { i ->
                pool.submit {
                    start.await()
                    when (i % 4) {
                        0 -> w.fx.tx.run(w.tenant) { j -> w.payments.scaReturnIn(j, b.id) }                                              // doppelte SCA-Rückkehr
                        1 -> w.fx.tx.run(w.tenant) { j -> w.payments.reactToProviderEventIn(j, psp.provider, providerRef) }              // Webhook
                        else -> OperationRunner(w.fx.tx, w.payments.handlers(psp), w.outbox, w.clock, cfg, "w$i").let { r -> repeat(3) { r.runNext(w.tenant) } }   // Statusabfragen laufen parallel
                    }
                }
            }
            start.countDown(); futures.forEach { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
            w.drain()
            val v = w.view(b)!!
            assertEquals(PaymentState.AUTHORIZED, v.state)
            assertEquals(1, w.events("payment.authorized", v.id), "genau eine fachliche Wirkung"); assertEquals(2, w.events("payment.state_changed", v.id), "CREATED->REQUIRES_ACTION->AUTHORIZED, nie doppelt")
            assertEquals(1, psp.effects(PaymentAction.AUTHORIZE)); assertEquals(1, w.count("SELECT count(*) FROM taktovia.payment_intent WHERE tenant_id = ?", w.tenant.value))
            assertEquals(0, w.count("SELECT count(*) FROM taktovia.operation WHERE tenant_id = ? AND status NOT IN ('SUCCEEDED')", w.tenant.value), "alle Operationen sind abgeschlossen")
        }
    }

    @Test
    fun prozessabbruch_nach_psp_erfolg_abgleich_ohne_zweite_belastung() {
        val psp = PayScriptedPsp(); val w = world(psp)
        w.use {
            val b = w.newBooking(); val begun = w.begin(b)
            // Abbruch zwischen PSP-Erfolg und lokalem Commit bei der Autorisierung
            psp.inject(PaymentAction.AUTHORIZE, PayScriptedPsp.Fault.CRASH_AFTER_EFFECT)
            w.drain(1)
            assertEquals(PaymentState.CREATED, w.view(b)!!.state, "lokal ist nichts angekommen"); assertEquals(1, psp.effects(PaymentAction.AUTHORIZE)); assertTrue(w.opStatus(begun.operationId).startsWith("RECONCILING"))
            w.clock.advance(Duration.ofHours(2)); w.drain()
            assertEquals(PaymentState.AUTHORIZED, w.view(b)!!.state); assertEquals(1, psp.effects(PaymentAction.AUTHORIZE)); assertEquals(1, psp.calls(PaymentAction.AUTHORIZE), "der Abgleich wiederholt den Aufruf nicht")
            // und beim Einzug
            w.fx.tx.run(w.tenant) { j -> w.payments.completeCouplingIn(j, w.view(b)!!.id, CouplingKind.CONFIRM_BOOKING) }
            psp.inject(PaymentAction.CAPTURE, PayScriptedPsp.Fault.CRASH_AFTER_EFFECT)
            w.drain(1)
            assertEquals(PaymentState.CAPTURE_PENDING, w.view(b)!!.state); assertEquals(1, psp.effects(PaymentAction.CAPTURE))
            w.clock.advance(Duration.ofHours(2)); w.drain()
            val v = w.view(b)!!
            assertEquals(PaymentState.CAPTURED, v.state); assertEquals(950, v.capturedMinor); assertEquals(1, psp.effects(PaymentAction.CAPTURE)); assertEquals(1, psp.calls(PaymentAction.CAPTURE)); assertEquals(1, w.events("payment.captured", v.id))
        }
    }

    @Test
    fun capture_timeout_bleibt_unbestimmt_bis_zum_abgleich() {
        val psp = PayScriptedPsp(); val w = world(psp)
        w.use {
            val b = w.newBooking("CONFIRMED"); w.begin(b); w.drain()
            w.fx.tx.run(w.tenant) { j -> w.payments.completeCouplingIn(j, w.view(b)!!.id, CouplingKind.CONFIRM_BOOKING) }
            psp.inject(PaymentAction.CAPTURE, PayScriptedPsp.Fault.LOST_RESPONSE)
            w.drain(1)
            val unknown = w.view(b)!!
            assertEquals(PaymentState.CAPTURE_UNKNOWN, unknown.state); assertEquals(PaymentUiStatus.UNKNOWN, unknown.status); assertEquals(1, psp.effects(PaymentAction.CAPTURE))
            assertEquals("CONFIRMED" to 3L, w.bookingState(b))
            w.clock.advance(Duration.ofHours(2)); w.drain()
            val v = w.view(b)!!
            assertEquals(PaymentState.CAPTURED, v.state); assertEquals(1, psp.effects(PaymentAction.CAPTURE), "kein zweiter Capture auf Verdacht"); assertEquals(1, psp.calls(PaymentAction.CAPTURE))
            assertEquals(5, w.events("payment.state_changed", v.id), "CREATED->AUTHORIZED->CAPTURE_PENDING->CAPTURE_UNKNOWN->RECONCILING->CAPTURED")
        }
    }

    @Test
    fun beginn_ist_idempotent_und_parallel_hoechstens_eine_zahlung_je_buchung() {
        val psp = PayScriptedPsp(); val w = world(psp)
        w.use {
            val b = w.newBooking()
            val first = w.begin(b); val again = w.begin(b, method = "pm_andere")
            assertTrue(again.replayed); assertEquals(first.payment.id, again.payment.id); assertEquals(first.operationId, again.operationId)
            val b2 = w.newBooking(); val pool = Executors.newFixedThreadPool(10); val start = CountDownLatch(1)
            val results = (1..10).map { pool.submit<Pair<UUID, Boolean>> { start.await(); w.begin(b2).let { it.payment.id to it.replayed } } }
            start.countDown(); val all = results.map { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
            assertEquals(1, all.map { it.first }.toSet().size, "alle gleichzeitigen Beginne liefern dieselbe Zahlung"); assertEquals(1, all.count { !it.second })
            assertEquals(1, w.count("SELECT count(*) FROM taktovia.payment_intent WHERE booking_id = ?", b2.id)); assertEquals(1, w.count("SELECT count(*) FROM taktovia.payment_operation WHERE payment_id = ?", all.first().first))
            w.drain(); assertEquals(2, psp.effects(PaymentAction.AUTHORIZE), "eine Autorisierung je Buchung")
        }
    }

    @Test
    fun fremder_mandant_unbekannte_referenz_und_webhook_ohne_zahlung() {
        val psp = PayScriptedPsp(); val a = world(psp); val b = PayTestWorld(PayScriptedPsp())
        a.use { b.use {
            val booking = a.newBooking(); val begun = a.begin(booking); a.drain()
            // fremder Mandant: Zahlung und Operation sind nicht sichtbar, die Quote nicht auffindbar (404)
            assertNull(b.fx.tx.run(b.tenant) { j -> b.payments.forBookingIn(j, booking.id) }); assertNull(b.fx.tx.run(b.tenant) { j -> b.payments.getIn(j, begun.payment.id) })
            val e = assertThrows(ProblemException::class.java) { b.begin(booking) }
            assertEquals(ErrorCode.NOT_FOUND, e.code)
            // ein Ereignis zu einer unbekannten oder fremden PSP-Referenz erzeugt weder Zahlung noch Operation noch Wirkung
            val before = a.count("SELECT count(*) FROM taktovia.operation WHERE tenant_id = ?", a.tenant.value)
            b.fx.tx.run(b.tenant) { j -> b.payments.reactToProviderEventIn(j, psp.provider, "psp_unbekannt"); b.payments.reactToProviderEventIn(j, psp.provider, a.fx.admin.queryForObject("SELECT provider_ref FROM taktovia.payment_intent WHERE id = ?", String::class.java, begun.payment.id)!!) }
            assertEquals(0, b.count("SELECT count(*) FROM taktovia.payment_intent WHERE tenant_id = ?", b.tenant.value)); assertEquals(0, b.count("SELECT count(*) FROM taktovia.operation WHERE tenant_id = ?", b.tenant.value))
            assertEquals(before, a.count("SELECT count(*) FROM taktovia.operation WHERE tenant_id = ?", a.tenant.value))
            assertNotNull(a.view(booking))
        } }
    }

    @Test
    fun ereignisse_entsprechen_den_schemas() {
        val psp = PayScriptedPsp(); val w = world(psp)
        w.use {
            // autorisiert + eingezogen
            val ok = w.newBooking(); w.begin(ok); w.drain()
            w.fx.tx.run(w.tenant) { j -> w.payments.completeCouplingIn(j, w.view(ok)!!.id, CouplingKind.CONFIRM_BOOKING) }; w.drain()
            // abgelehnt
            psp.authMode = PayScriptedPsp.AuthMode.DECLINE; val bad = w.newBooking(); w.begin(bad); w.drain()
            // unbestimmt
            psp.authMode = PayScriptedPsp.AuthMode.OK; val unknown = w.newBooking(); w.begin(unknown); psp.inject(PaymentAction.AUTHORIZE, PayScriptedPsp.Fault.LOST_RESPONSE); w.drain(1)
            val registry = com.networknt.schema.SchemaRegistry.withDialect(com.networknt.schema.dialect.Dialects.getDraft202012())
            val mapper = tools.jackson.databind.json.JsonMapper.builder().build()
            val types = listOf("payment.authorized", "payment.authorization_failed", "payment.captured", "payment.outcome_unknown", "payment.state_changed")
            for (type in types) {
                val schemaFile = if (type == "payment.state_changed") "platform.statechanged" else type
                val schema = registry.getSchema(java.nio.file.Files.readString(PaymentTestDb.repoRoot().resolve("api/events/$schemaFile.v1.schema.json")))
                val payloads = w.fx.admin.queryForList("SELECT payload::text FROM taktovia.outbox WHERE tenant_id = ? AND event_type = ?", String::class.java, w.tenant.value, type)
                assertTrue(payloads.isNotEmpty(), "Ereignis $type wurde geschrieben")
                payloads.forEach { assertEquals(emptyList<String>(), schema.validate(mapper.readTree(it)).map { m -> m.message }, "$type: $it") }
            }
        }
    }
}

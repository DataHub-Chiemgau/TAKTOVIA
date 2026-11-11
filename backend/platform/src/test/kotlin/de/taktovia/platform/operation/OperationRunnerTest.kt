package de.taktovia.platform.operation

import de.taktovia.platform.TestDatabase
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.outbox.OutboxTestSupport
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.statemachine.OperationState
import de.taktovia.platform.tenant.TenantId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Operation-Runner mit Lease und Fencing (WP-M1-15) gegen die echte Datenbank unter der Runtime-Rolle. Der Prozessabbruch wird durch Claimen ohne
 * Abschluss und Zeitvorlauf der Testuhr nachgestellt; der externe Partner ist ein Testadapter mit Idempotenzschlüssel (kein echter Partner in M1).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OperationRunnerTest {
    private lateinit var s: OutboxTestSupport
    private val clock = OutboxTestSupport.TestClock()
    private val svc = OperationService(clock)
    private val lease = Duration.ofSeconds(60)

    @BeforeAll fun up() { TestDatabase.require(); s = OutboxTestSupport() }
    @AfterAll fun down() = s.close()

    /** Externer Testadapter: führt die Wirkung je Idempotenzschlüssel höchstens einmal aus, jeder Aufruf wird gezählt. */
    class TestPartner {
        val effects = ConcurrentHashMap<String, Int>(); val calls = AtomicInteger()
        fun execute(key: String): String { calls.incrementAndGet(); effects.putIfAbsent(key, 1); return "ref-$key" }
    }

    private fun handler(kind: String = "TEST_COMMAND", onExecute: (OperationContext) -> OperationOutcome, onReconcile: (OperationContext) -> OperationOutcome = { OperationOutcome.ManualReview("RECONCILE_NOT_SUPPORTED") }) =
        object : OperationHandler {
            override val kind = kind
            override fun execute(ctx: OperationContext) = onExecute(ctx)
            override fun reconcile(ctx: OperationContext) = onReconcile(ctx)
        }

    private fun runner(h: OperationHandler, worker: String = "w-" + UUID.randomUUID()) = OperationRunner(s.tx, listOf(h), s.outbox, clock, OperationConfig(lease = lease), worker)
    private fun enqueue(t: TenantId, deadline: Instant = clock.instant().plus(Duration.ofHours(1))): UUID = s.tx.run(t) { svc.enqueue(it, "TEST_COMMAND", deadline) }
    private fun view(t: TenantId, id: UUID) = s.tx.run(t, readOnly = true) { svc.find(it, id) }
    private fun row(t: TenantId, id: UUID) = s.read(t) { it.queryForMap("SELECT status, fencing_token, attempts, revision, result_code, lease_until, next_attempt_at FROM taktovia.operation WHERE id = ?", id) }
    private fun events(t: TenantId, id: UUID) = s.read(t) { it.queryForList("SELECT aggregate_revision || ':' || (payload->>'from') || '>' || (payload->>'to') FROM taktovia.outbox WHERE aggregate_id = ? AND event_type = 'operation.state_changed' ORDER BY aggregate_revision", String::class.java, id) }

    @Test fun operation_laeuft_durch() {
        val t = s.newTenant(); val id = enqueue(t); val key = AtomicInteger()
        assertEquals(OperationState.QUEUED, view(t, id)!!.status)
        val r = runner(handler(onExecute = { key.incrementAndGet(); OperationOutcome.Succeeded("DONE") }))
        assertEquals(OperationState.SUCCEEDED, r.runNext(t)!!.state)
        assertEquals(1, key.get())
        val row = row(t, id)
        assertEquals("SUCCEEDED", row["status"]); assertEquals(1L, row["fencing_token"]); assertEquals(1, row["attempts"]); assertEquals("DONE", row["result_code"]); assertNull(row["lease_until"])
        assertEquals(listOf("2:QUEUED>RUNNING", "3:RUNNING>SUCCEEDED"), events(t, id), "je Übergang genau ein Ereignis, Revisionen lückenlos")
        assertNull(r.runNext(t), "nichts mehr offen")
        assertEquals("DONE", view(t, id)!!.resultCode)
    }

    @Test fun worker_mit_verlorener_lease_kann_nicht_mehr_schreiben() {
        val t = s.newTenant(); val id = enqueue(t)
        val a = runner(handler(onExecute = { OperationOutcome.Succeeded("A") }), "worker-a"); val b = runner(handler(onExecute = { OperationOutcome.Succeeded("B") }), "worker-b")
        val claimedA = a.claimNext(t)!!
        assertEquals(1L, claimedA.fencingToken)
        clock.advance(lease.plusSeconds(1))                           // Lease von A ist abgelaufen
        val claimedB = b.claimNext(t)!!
        assertEquals(2L, claimedB.fencingToken, "Übernahme erhöht den Fencing-Token monoton")
        // A schreibt mit dem alten Token: abgewiesen, der Zustand bleibt unverändert
        assertTrue(a.execute(claimedA).leaseLost, "alter Token schreibt nichts")
        assertThrows<LeaseLostException> { s.tx.run(t) { j -> a.store.renew(j, claimedA.id, claimedA.fencingToken, clock.instant().plus(lease)) } }
        assertEquals("RUNNING", row(t, id)["status"]); assertEquals(2L, row(t, id)["fencing_token"])
        // B schließt ab
        assertEquals(OperationState.SUCCEEDED, b.execute(claimedB).state)
        assertEquals("B", row(t, id)["result_code"])
    }

    @Test fun nach_prozessabbruch_nimmt_anderer_worker_nach_leaseablauf_auf_geschaeftswirkung_genau_einmal() {
        val t = s.newTenant(); val id = enqueue(t); val partner = TestPartner()
        val h = handler(onExecute = { ctx -> partner.execute(ctx.operationKey); OperationOutcome.Succeeded("PARTNER_OK") })
        val w1 = runner(h, "worker-1"); val w2 = runner(h, "worker-2")
        val claimed = w1.claimNext(t)!!
        partner.execute(claimed.operationKey)                          // Nebenwirkung eingetreten ... und Prozess stirbt vor dem Speichern
        assertNull(w2.runNext(t), "Lease von worker-1 gilt noch: nichts zu übernehmen")
        clock.advance(lease.plusSeconds(1))
        val resumed = w2.runNext(t)!!
        assertEquals(OperationState.SUCCEEDED, resumed.state)
        assertEquals(1, partner.effects.size, "Geschäftswirkung genau einmal (Idempotenzschlüssel = Operationsschlüssel)")
        assertEquals(2, partner.calls.get(), "zweiter Aufruf mit demselben Schlüssel, vom Partner dedupliziert")
        assertEquals(2L, row(t, id)["fencing_token"]); assertEquals(2, row(t, id)["attempts"])
        assertEquals(listOf("2:QUEUED>RUNNING", "3:RUNNING>SUCCEEDED"), events(t, id), "Übernahme im Zustand RUNNING erzeugt keinen Zustandswechsel")
    }

    @Test fun zwei_worker_konkurrieren_um_dieselbe_operation() {
        val t = s.newTenant(); val id = enqueue(t); val runs = AtomicInteger()
        val h = handler(onExecute = { runs.incrementAndGet(); Thread.sleep(200); OperationOutcome.Succeeded("DONE") })
        val pool = Executors.newFixedThreadPool(8); val start = CountDownLatch(1)
        val results = (1..8).map { i -> pool.submit<RunResult?> { start.await(); runner(h, "w$i").runNext(t) } }
        start.countDown()
        val done = results.map { it.get(30, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(1, runs.get(), "genau ein Worker führt aus")
        assertEquals(1, done.count { it != null }); assertEquals(1L, row(t, id)["fencing_token"]); assertEquals("SUCCEEDED", row(t, id)["status"])
    }

    @Test fun viele_operationen_und_worker_jede_genau_einmal() {
        val t = s.newTenant(); val ids = (1..24).map { enqueue(t) }; val runs = ConcurrentHashMap<String, AtomicInteger>()
        val h = handler(onExecute = { ctx -> runs.computeIfAbsent(ctx.operationKey) { AtomicInteger() }.incrementAndGet(); OperationOutcome.Succeeded() })
        val pool = Executors.newFixedThreadPool(6)
        val futures = (1..6).map { i -> pool.submit<Int> { val r = runner(h, "w$i"); var n = 0; while (r.runNext(t) != null) n++; n } }
        assertEquals(24, futures.sumOf { it.get(60, TimeUnit.SECONDS) }); pool.shutdown()
        assertEquals(24, runs.size); assertTrue(runs.values.all { it.get() == 1 })
        assertTrue(ids.all { row(t, it)["status"] == "SUCCEEDED" })
    }

    @Test fun unerlaubter_zustandsuebergang_wird_abgewiesen_und_aendert_nichts() {
        val t = s.newTenant(); val id = enqueue(t); val r = runner(handler(onExecute = { OperationOutcome.Succeeded() }))
        val e = assertThrows<ProblemException> { s.tx.run(t) { j -> r.store.transition(j, id, 0, OperationState.SUCCEEDED, "OK", null) } }
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, e.code)
        assertEquals("QUEUED", row(t, id)["status"]); assertEquals(emptyList<String>(), events(t, id))
        // Endzustand ist endgültig
        r.runNext(t)
        val claimed = row(t, id)
        assertEquals("SUCCEEDED", claimed["status"])
        assertThrows<ProblemException> { s.tx.run(t) { j -> r.store.transition(j, id, 1, OperationState.QUEUED, null, null) } }
    }

    @Test fun abgelaufene_deadline_wird_nie_ausgefuehrt() {
        val t = s.newTenant(); val runs = AtomicInteger()
        val h = handler(onExecute = { runs.incrementAndGet(); OperationOutcome.Succeeded() }, onReconcile = { runs.incrementAndGet(); OperationOutcome.Succeeded() })
        // nie gestartet: determiniert FAILED
        val fresh = enqueue(t, clock.instant().minusSeconds(1))
        assertEquals(OperationState.FAILED, runner(h).runNext(t)!!.state)
        assertEquals("DEADLINE_EXCEEDED", row(t, fresh)["result_code"])
        // gestartet, Prozess abgebrochen, Deadline inzwischen überschritten: Wirkung unbekannt -> RECONCILING, nie FAILED
        val started = enqueue(t, clock.instant().plusSeconds(30))
        runner(h).claimNext(t)!!
        clock.advance(Duration.ofSeconds(61))
        assertEquals(OperationState.RECONCILING, runner(h).runNext(t)!!.state)
        assertEquals("DEADLINE_EXCEEDED", row(t, started)["result_code"])
        // im Abgleich und Deadline überschritten: manuelle Prüfung
        clock.advance(Duration.ofHours(1))
        assertEquals(OperationState.MANUAL_REVIEW, runner(h).runNext(t)!!.state)
        assertEquals(0, runs.get(), "bei überschrittener Deadline läuft kein Handler")
        clock.advance(Duration.ofHours(-1).minusSeconds(61))
    }

    @Test fun unbestimmtes_ergebnis_wird_abgeglichen_nie_zu_erfolg_oder_fehler_verkuerzt() {
        val t = s.newTenant(); val id = enqueue(t); val reconciles = AtomicInteger(); val executes = AtomicInteger()
        val h = handler(onExecute = { executes.incrementAndGet(); OperationOutcome.Unknown("PARTNER_RESULT_UNKNOWN", Duration.ofSeconds(10)) },
            onReconcile = { if (reconciles.incrementAndGet() < 3) OperationOutcome.Unknown("PARTNER_RESULT_UNKNOWN", Duration.ofSeconds(10)) else OperationOutcome.Succeeded("CONFIRMED") })
        val r = runner(h)
        assertEquals(OperationState.RECONCILING, r.runNext(t)!!.state)
        assertEquals("PARTNER_RESULT_UNKNOWN", view(t, id)!!.resultCode)
        assertEquals(10, view(t, id)!!.retryAfterSeconds, "Wiederabfrage-Hinweis aus next_attempt_at")
        assertNull(r.runNext(t), "vor next_attempt_at wird nicht abgeglichen")
        clock.advance(Duration.ofSeconds(11)); assertEquals(OperationState.RECONCILING, r.runNext(t)!!.state)
        clock.advance(Duration.ofSeconds(11)); assertEquals(OperationState.RECONCILING, r.runNext(t)!!.state)
        clock.advance(Duration.ofSeconds(11)); assertEquals(OperationState.SUCCEEDED, r.runNext(t)!!.state)
        assertEquals(1, executes.get(), "execute läuft nur einmal, danach nur noch reconcile"); assertEquals(3, reconciles.get())
        assertEquals(listOf("2:QUEUED>RUNNING", "3:RUNNING>RECONCILING", "4:RECONCILING>SUCCEEDED"), events(t, id), "Verbleib in RECONCILING ist kein Zustandswechsel")
    }

    @Test fun failed_nur_nach_determiniertem_ergebnis() {
        val t = s.newTenant()
        // unerwartete Ausnahme = Ergebnis unbekannt: RECONCILING, nie FAILED
        val tc = s.newTenant(); val crash = enqueue(tc)
        assertEquals(OperationState.RECONCILING, runner(handler(onExecute = { error("Verbindung abgebrochen") })).runNext(tc)!!.state)
        assertEquals("UNKNOWN_OUTCOME", row(tc, crash)["result_code"])
        // Retry vor jeder Wirkung: zurück in QUEUED mit Backoff, danach Erfolg
        val retry = enqueue(t); val n = AtomicInteger()
        val r = runner(handler(onExecute = { if (n.incrementAndGet() == 1) throw OperationRetryException("TEMPORARY", Duration.ofSeconds(5)) else OperationOutcome.Succeeded() }))
        assertEquals(OperationState.QUEUED, r.runNext(t)!!.state)
        assertEquals("QUEUED", row(t, retry)["status"]); assertNull(row(t, retry)["lease_until"])
        assertNull(r.runNext(t), "Backoff")
        clock.advance(Duration.ofSeconds(6)); assertEquals(OperationState.SUCCEEDED, r.runNext(t)!!.state)
        // ausdrücklich bestimmtes Fehlerergebnis
        val t2 = s.newTenant(); val failed = enqueue(t2)
        assertEquals(OperationState.FAILED, runner(handler(onExecute = { OperationOutcome.Failed("REJECTED_BY_PARTNER") })).runNext(t2)!!.state)
        assertEquals("FAILED", row(t2, failed)["status"])
    }

    @Test fun wirkung_und_abschluss_in_einer_transaktion_mit_fencing() {
        val t = s.newTenant(); val id = enqueue(t); val agg = UUID.randomUUID(); val outbox = Outbox()
        fun effect(j: org.springframework.jdbc.core.JdbcOperations) = outbox.append(j, de.taktovia.platform.outbox.OutboxEvent(agg, 1, "test.effect.happened", mapOf("n" to 1), clock.instant()))
        val a = runner(handler(onExecute = { it.commit("A_DONE") { j -> effect(j) } }), "a"); val b = runner(handler(onExecute = { it.commit("B_DONE") { j -> effect(j) } }), "b")
        val ca = a.claimNext(t)!!
        clock.advance(lease.plusSeconds(1))
        val cb = b.claimNext(t)!!
        assertTrue(a.execute(ca).leaseLost, "alter Token: Wirkung und Abschluss werden gemeinsam zurückgerollt")
        assertEquals(0, s.read(t) { it.queryForObject("SELECT count(*) FROM taktovia.outbox WHERE event_type = 'test.effect.happened'", Int::class.java) })
        assertEquals(OperationState.SUCCEEDED, b.execute(cb).state)
        assertEquals(1, s.read(t) { it.queryForObject("SELECT count(*) FROM taktovia.outbox WHERE event_type = 'test.effect.happened'", Int::class.java) }, "Wirkung genau einmal")
        assertEquals("B_DONE", row(t, id)["result_code"])
    }

    @Test fun lease_kann_verlaengert_werden_und_schuetzt_vor_uebernahme() {
        val t = s.newTenant(); enqueue(t); val a = runner(handler(onExecute = { OperationOutcome.Succeeded() }), "a"); val b = runner(handler(onExecute = { OperationOutcome.Succeeded() }), "b")
        val c = a.claimNext(t)!!
        clock.advance(Duration.ofSeconds(50)); s.tx.run(t) { a.store.renew(it, c.id, c.fencingToken, clock.instant().plus(lease)) }
        clock.advance(Duration.ofSeconds(50))
        assertNull(b.claimNext(t), "verlängerte Lease schützt")
        clock.advance(Duration.ofSeconds(11))
        assertEquals(2L, b.claimNext(t)!!.fencingToken)
    }

    @Test fun fremder_mandant_sieht_die_operation_nicht() {
        val a = s.newTenant(); val b = s.newTenant(); val id = enqueue(a)
        assertNull(view(b, id)); assertTrue(view(a, id) != null)
        assertNull(runner(handler(onExecute = { OperationOutcome.Succeeded() })).runNext(b), "Worker für B findet nichts von A")
    }

    @Test fun art_ausserhalb_des_katalogs_wird_abgewiesen() {
        val t = s.newTenant()
        assertThrows<IllegalArgumentException> { s.tx.run(t) { svc.enqueue(it, "UNBEKANNT", clock.instant().plusSeconds(5)) } }
        assertTrue(OperationKind.ALL.containsAll(listOf("HOLD", "CONFIRM", "CANCEL", "RECONCILE", "PUBLISH_CONFIG", "TEST_COMMAND")))
    }
}

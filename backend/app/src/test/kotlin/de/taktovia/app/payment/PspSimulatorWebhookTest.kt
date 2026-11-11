package de.taktovia.app.payment

import de.taktovia.adapters.psp.api.PspAdapter
import de.taktovia.adapters.psp.api.PspAuthorize
import de.taktovia.adapters.psp.api.PspCapture
import de.taktovia.adapters.psp.api.PspCommandRef
import de.taktovia.adapters.psp.api.PspOperation
import de.taktovia.adapters.psp.api.PspOutcome
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.URI
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Provider-Ereignisse des **echten PSP-Simulators** (Kindprozess, echte HTTP-Zustellung mit seiner Signatur `t=<epoch>,v1=<hmac>`) am echten Endpunkt der Anwendung (WP-M2-29):
 * 100 identische Webhooks, ungültige Signatur, verspätete Zustellung, und Webhook parallel zur synchronen Antwort des Adapters. Simulator-Ergebnisse, kein Nachweis gegen einen realen PSP.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PspSimulatorWebhookTest {
    private lateinit var sim: PspAppSimulator
    private lateinit var psp: PspAdapter
    private lateinit var tenantId: java.util.UUID
    private val tenant get() = tenantId
    private lateinit var target: String

    @BeforeAll fun up() {
        val server = PspEventEnv.server
        sim = PspAppSimulator.start(); tenantId = PspEventEnv.simulatorTenant(sim.webhookSecret()).value
        target = "http://127.0.0.1:${server.port}/v1/payments/provider-events/simpsp"
        sim.configure(target, auto = false)
        psp = PspAdapter.http("simpsp", URI(sim.url), Duration.ofSeconds(10))
    }
    @AfterAll fun down() { if (::sim.isInitialized) sim.close() }

    private fun authorize(amount: Long = 1250): String {
        val r = psp.authorize(PspAuthorize(PspCommandRef.derive(UUID.randomUUID().toString(), PspOperation.AUTHORIZE), "m-" + UUID.randomUUID(), amount, "EUR", "pm_ok"))
        return (r as PspOutcome.Ok).payment.paymentId
    }
    private fun rows(eventId: String) = PspEventEnv.count("SELECT count(*) FROM taktovia.payment_event WHERE tenant_id = ? AND provider = 'simpsp' AND event_id = ?", tenant, eventId)
    private fun outbox(eventId: String) = PspEventEnv.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id = ? AND event_type = 'payment.provider_event_received' AND payload->>'event_id' = ?", tenant, eventId)
    private fun lastEvent(paymentId: String) = sim.events().last { it["data"]["payment_id"].asString() == paymentId }["event_id"].asString()
    private fun statuses(result: tools.jackson.databind.JsonNode) = result["deliveries"].values().map { it["http_status"].asInt() }

    @Test
    fun hundert_identische_simulator_webhooks_werden_einmal_wirksam() {
        val eventId = lastEvent(authorize())
        val pool = Executors.newFixedThreadPool(5); val start = CountDownLatch(1)
        val futures = (1..5).map { pool.submit<List<Int>> { start.await(); statuses(sim.deliver(eventId, "valid", repeat = 20)) } }   // 5 x 20 gleichzeitig = 100 identische Zustellungen
        start.countDown(); val all = futures.flatMap { it.get(120, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(100, all.size); assertEquals(1, all.count { it == 202 }, all.groupingBy { it }.eachCount().toString()); assertEquals(99, all.count { it == 200 })
        assertEquals(1, rows(eventId)); assertEquals(1, outbox(eventId), "genau eine fachliche Wirkung")
    }

    @Test
    fun ungueltige_signatur_und_verspaetete_zustellung_werden_abgewiesen_ohne_verarbeitung() {
        val eventId = lastEvent(authorize(700))
        assertEquals(List(3) { 401 }, statuses(sim.deliver(eventId, "bad_signature", repeat = 3)))
        assertEquals(List(3) { 401 }, statuses(sim.deliver(eventId, "late", repeat = 3, lateSeconds = 900)), "Replay außerhalb des Zeitfensters (±300 s)")
        assertEquals(0, rows(eventId)); assertEquals(0, outbox(eventId))
        assertEquals(listOf(202), statuses(sim.deliver(eventId, "valid")), "Gegenprobe: gültig zugestellt")
        assertEquals(1, rows(eventId))
    }

    @Test
    fun webhook_parallel_zur_synchronen_antwort_genau_eine_wirkung_je_ereignis() {
        sim.configure(target, auto = true)   // der Simulator stellt jedes Ereignis selbst zu, während die synchronen Aufrufe laufen
        try {
            val before = sim.events().size
            val stop = AtomicBoolean(false)
            val redeliver = Thread {   // zusätzlich: Wiederholungen bereits entstandener Ereignisse, gleichzeitig zu den synchronen Antworten
                while (!stop.get()) { sim.events().drop(before).take(4).forEach { runCatching { sim.deliver(it["event_id"].asString(), "valid", repeat = 3) } }; Thread.sleep(20) }
            }.also { it.start() }
            val pool = Executors.newFixedThreadPool(8)
            val finals = (1..16).map { i -> pool.submit<Long> {
                val id = authorize(1000L + i)
                val cap = psp.capture(PspCapture(PspCommandRef.derive("cap-$id", PspOperation.CAPTURE), id))
                (cap as PspOutcome.Ok).payment.capturedMinor
            } }.map { it.get(120, TimeUnit.SECONDS) }
            pool.shutdown(); stop.set(true); redeliver.join()
            assertEquals((1..16).map { 1000L + it }, finals, "synchrone Antworten unverändert")
            val ids = sim.events().drop(before).map { it["event_id"].asString() }
            assertEquals(32, ids.size)   // je Zahlung: authorized, captured
            // alle automatischen Zustellungen sind angekommen (kurz warten: der Simulator stellt asynchron zu, einmalig und ohne eigenen Retry-Zeitplan)
            fun waitForAll(seconds: Long) { val deadline = System.currentTimeMillis() + seconds * 1000; while (ids.any { rows(it) == 0 } && System.currentTimeMillis() < deadline) Thread.sleep(100) }
            waitForAll(20)
            // eine Zustellung, die der Absender wegen Last nicht rechtzeitig quittiert bekam, wiederholt ein PSP (at-least-once): die Wiederholung darf nie eine zweite Wirkung haben
            ids.filter { rows(it) == 0 }.forEach { sim.deliver(it, "valid") }
            waitForAll(10)
            ids.forEach { assertEquals(1, rows(it), "payment_event $it; Zustellungen: ${sim.deliveries().filter { d -> d["event_id"].asString() == it }.map { d -> "${d["http_status"]}/${d["error"]}" }}"); assertEquals(1, outbox(it), "Wirkung $it") }
            val auto = sim.deliveries().filter { it["event_id"].asString() in ids }
            assertTrue(auto.all { it["http_status"].isNull || it["http_status"].asInt() in setOf(200, 202) }, "nie ein Fehlerstatus: " + auto.map { it["http_status"].toString() }.groupingBy { it }.eachCount())
        } finally { sim.configure(target, auto = false) }
    }
}

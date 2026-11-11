package de.taktovia.platform.outbox

import de.taktovia.platform.TestDatabase
import de.taktovia.platform.tenant.TenantId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DuplicateKeyException
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** WP-M1-14: Outbox und Relay gegen echte Datenbank; der Broker ist ein Speicher-Broker (RabbitMQ ist hier nicht ausführbar). */
class OutboxRelayTest {
    companion object {
        private lateinit var s: OutboxTestSupport
        @BeforeAll @JvmStatic fun setup() { TestDatabase.require(); s = OutboxTestSupport() }
        @AfterAll @JvmStatic fun teardown() { if (::s.isInitialized) s.close() }
    }

    private fun relay(t: TenantId, pub: MessagePublisher, clock: OutboxTestSupport.TestClock = OutboxTestSupport.TestClock(), config: RelayConfig = RelayConfig(),
                      policy: RecipientPolicy = RecipientPolicy.OWNER_ONLY, alerts: de.taktovia.platform.alert.OperatorAlertPort = s.alerts) =
        OutboxRelay(s.tx, pub, { listOf(t) }, alerts, s.security, policy, clock, config, jitter = { 1.0 })
    private fun published(t: TenantId) = s.read(t) { it.queryForList("SELECT event_id FROM taktovia.outbox WHERE published_at IS NOT NULL", UUID::class.java) }.toSet()
    private fun count(t: TenantId, where: String) = s.read(t) { it.queryForObject("SELECT count(*) FROM taktovia.outbox WHERE $where", Int::class.java) }

    @Test fun `Rollback hinterlaesst keinen Outbox-Eintrag, Commit genau einen je Aggregatrevision und Ereignistyp`() {
        val t = s.newTenant(); val agg = UUID.randomUUID()
        assertThrows<IllegalStateException> { s.tx.run(t) { j -> s.outbox.append(j, OutboxEvent(agg, 1, "test.thing.changed", mapOf("a" to 1), java.time.Instant.now())); error("Fachfehler") } }
        assertEquals(0, count(t, "true"))
        s.append(t, agg, 1)
        assertEquals(1, count(t, "aggregate_id = '$agg'"))
        assertThrows<DuplicateKeyException> { s.append(t, agg, 1) }          // gleiche Revision und Typ
        s.append(t, agg, 1, type = "test.thing.other"); s.append(t, agg, 2)  // anderer Typ bzw. andere Revision sind erlaubt
        assertEquals(3, count(t, "aggregate_id = '$agg'"))
    }

    @Test fun `zwanzig parallele Schreiber derselben Aggregatrevision ergeben genau einen Eintrag`() {
        val t = s.newTenant(); val agg = UUID.randomUUID(); val start = CountDownLatch(1); val exec = Executors.newFixedThreadPool(10)
        try {
            val fs = (1..20).map { exec.submit(Callable { start.await(); runCatching { s.append(t, agg, 1) }.isSuccess }) }
            start.countDown(); assertEquals(1, fs.count { it.get() })
        } finally { exec.shutdownNow() }
        assertEquals(1, count(t, "aggregate_id = '$agg'"))
    }

    @Test fun `Ende-zu-Ende Zustellung vom Outbox-Eintrag bis zur Wirkung im Empfaengermandanten`() {
        val t = s.newTenant(); val pub = InMemoryMessagePublisher()
        val ids = (1..3).map { s.append(t, UUID.randomUUID(), 1, payload = mapOf("vehicle" to UUID.randomUUID().toString())) }
        val st = relay(t, pub).runOnce()
        assertEquals(RelayStats(published = 3), st); assertEquals(ids.toSet(), published(t))
        assertEquals(3, pub.messages.size); assertTrue(pub.messages.all { it.routingKey == "events.$t.test.thing.changed" })
        val e = EventEnvelope.fromJson(pub.messages.first().body)
        assertEquals(t.value, e.ownerTenantId); assertEquals(t.value, e.recipientTenantId); assertEquals(1, e.aggregateRevision)
        assertEquals(RelayStats(), relay(t, pub).runOnce(), "nichts mehr offen")
        val inbox = InboxProcessor(s.tx, s.security, { _, _, _ -> error("kein Snapshot nötig") })
        val h = VehicleHandler(s)
        for (m in pub.messages) assertEquals(InboxOutcome.PROCESSED, inbox.process("vehicles", m.body, h))
        assertEquals(3, h.effects.get()); assertEquals(3, s.read(t) { it.queryForObject("SELECT count(*) FROM taktovia.vehicle", Int::class.java) })
    }

    @Test fun `nach Abbruch des Relays werden unveroeffentlichte Eintraege ohne Verlust nachgeliefert`() {
        val t = s.newTenant(); val clock = OutboxTestSupport.TestClock(); val ids = (1..5).map { s.append(t, UUID.randomUUID(), 1) }.toSet()
        val dying = InMemoryMessagePublisher().also { it.failure = { object : Error("Relay-Prozess abgebrochen") {} } }
        relay(t, dying, clock).runOnce()                       // Prozess stirbt nach dem Beanspruchen, vor jeder Zustellung
        assertTrue(published(t).isEmpty()); assertEquals(5, count(t, "claimed_until IS NOT NULL"))
        val other = InMemoryMessagePublisher(); relay(t, other, clock).runOnce()
        assertEquals(0, other.messages.size, "Lease des toten Relays gilt noch")
        clock.advance(Duration.ofMinutes(2))                   // Lease abgelaufen
        assertEquals(RelayStats(published = 5), relay(t, other, clock).runOnce())
        assertEquals(ids, published(t)); assertEquals(ids, other.messages.map { UUID.fromString(it.messageId) }.toSet())
    }

    @Test fun `Abbruch nach Veroeffentlichung vor Markierung liefert ein Duplikat, das die Inbox mit einer Wirkung verwirft`() {
        val t = s.newTenant(); val clock = OutboxTestSupport.TestClock()
        s.append(t, UUID.randomUUID(), 1, payload = mapOf("vehicle" to UUID.randomUUID().toString()))
        val seen = InMemoryMessagePublisher()
        val dyingAfter = object : MessagePublisher { override fun publish(routingKey: String, messageId: String, body: String) { seen.publish(routingKey, messageId, body); throw object : Error("tot") {} } }
        relay(t, dyingAfter, clock).runOnce(); clock.advance(Duration.ofMinutes(2)); relay(t, seen, clock).runOnce()
        assertEquals(2, seen.messages.size, "mindestens einmal: zweimal zugestellt")
        val inbox = InboxProcessor(s.tx, s.security, { _, _, _ -> error("x") }); val h = VehicleHandler(s)
        assertEquals(listOf(InboxOutcome.PROCESSED, InboxOutcome.DUPLICATE), seen.messages.map { inbox.process("vehicles", it.body, h) })
        assertEquals(1, h.effects.get())
    }

    @Test fun `zwei Relay-Instanzen gleichzeitig liefern jeden Eintrag genau einmal`() {
        val t = s.newTenant(); val pub = InMemoryMessagePublisher(); val ids = (1..200).map { s.append(t, UUID.randomUUID(), 1) }.toSet()
        val start = CountDownLatch(1); val exec = Executors.newFixedThreadPool(2)
        try {
            val fs = (1..2).map { exec.submit { start.await(); repeat(5) { relay(t, pub, config = RelayConfig(batchSize = 25)).runOnce() } } }
            start.countDown(); fs.forEach { it.get() }
        } finally { exec.shutdownNow() }
        val delivered = pub.messages.map { it.messageId }
        assertEquals(200, delivered.size, "keine Doppelzustellung, kein Verlust"); assertEquals(ids.map { it.toString() }.toSet(), delivered.toSet())
        assertEquals(ids, published(t))
    }

    @Test fun `Retry-Staffel 1s 5s 30s 2min 10min dann exponentiell mit Jitter bis hoechstens 24 Stunden`() {
        assertEquals(listOf(1L, 5L, 30L, 120L, 600L), (1..5).map { RetrySchedule.delay(it) { 1.0 }.seconds })
        assertEquals(1200L, RetrySchedule.delay(6) { 1.0 }.seconds); assertEquals(600L, RetrySchedule.delay(6) { 0.0 }.seconds)
        assertEquals(Duration.ofHours(24), RetrySchedule.delay(40) { 1.0 }); assertTrue(RetrySchedule.delay(400) { 1.0 } <= Duration.ofHours(24))
    }

    @Test fun `erschoepfte Zustellversuche ergeben Dead-Letter und genau eine Betreiberwarnung, die den Empfaenger erreicht`() {
        val t = s.newTenant(); val clock = OutboxTestSupport.TestClock(); val pub = InMemoryMessagePublisher().also { it.failure = { IllegalStateException("Broker down") } }
        val received = CopyOnWriteArrayList<de.taktovia.platform.alert.AlertNotification>()
        val reg = io.micrometer.core.instrument.simple.SimpleMeterRegistry()
        val alerts = de.taktovia.platform.alert.OperatorAlertService({ received.add(it) }, reg, { }, clock)   // eigener Empfängerkanal je Test
        val id = s.append(t, UUID.randomUUID(), 1); val r = relay(t, pub, clock, RelayConfig(maxAttempts = 3), alerts = alerts)
        assertEquals(RelayStats(retried = 1), r.runOnce()); assertEquals(RelayStats(), r.runOnce(), "vor Fälligkeit keine Wiederholung")
        clock.advance(Duration.ofSeconds(2)); assertEquals(RelayStats(retried = 1), r.runOnce())
        assertEquals(0, received.size, "bis zur Erschöpfung keine Warnung")
        clock.advance(Duration.ofSeconds(10)); assertEquals(RelayStats(deadLettered = 1), r.runOnce())
        assertNotNull(s.read(t) { it.queryForObject("SELECT dead_lettered_at FROM taktovia.outbox WHERE event_id = ?", java.sql.Timestamp::class.java, id) })
        assertEquals("publish_failed", s.read(t) { it.queryForObject("SELECT last_error FROM taktovia.outbox WHERE event_id = ?", String::class.java, id) })
        repeat(3) { clock.advance(Duration.ofDays(1)); assertEquals(RelayStats(), r.runOnce()) }
        assertEquals(1.0, reg.counter("taktovia_operator_warnings_total", "source", "outbox-relay", "kind", "dead_letter", "severity", "critical").count(), "genau eine Warnung")
        assertEquals(1, received.size); assertEquals("outbox-relay/dead_letter", received.single().dedupKey); assertEquals(t.value, received.single().tenantRef)
    }

    @Test fun `Ereignis ohne zulaessige Empfaengeradressierung wird nicht zugestellt, sondern Dead-Letter mit Warnung und Sicherheitsereignis`() {
        val a = s.newTenant(); val b = s.newTenant(); val pub = InMemoryMessagePublisher()
        val id = s.append(a, UUID.randomUUID(), 1, recipient = b)
        assertEquals(RelayStats(deadLettered = 1), relay(a, pub).runOnce()); assertEquals(0, pub.messages.size)
        assertEquals("recipient_not_allowed", s.read(a) { it.queryForObject("SELECT last_error FROM taktovia.outbox WHERE event_id = ?", String::class.java, id) })
        assertEquals(1, s.read(a) { it.queryForObject("SELECT count(*) FROM taktovia.audit_event WHERE action = 'security.event.recipient_mismatch' AND object_ref = ?", Int::class.java, "event:$id") })
        // mit Freigabe (Richtlinie) wird zugestellt
        val id2 = s.append(a, UUID.randomUUID(), 1, recipient = b)
        assertEquals(RelayStats(published = 1), relay(a, pub, policy = { o, r -> o == a.value && r == b.value }).runOnce())
        assertEquals("events.$b.test.thing.changed", pub.messages.single().routingKey); assertNull(null); assertTrue(published(a).contains(id2))
    }

    @Test fun `Fehler bei einem Mandanten stoppt die anderen nicht`() {
        val a = s.newTenant(); val b = s.newTenant(); val pub = InMemoryMessagePublisher(); s.append(a); s.append(b)
        val n = AtomicInteger()
        val flaky = object : MessagePublisher { override fun publish(routingKey: String, messageId: String, body: String) { if (n.getAndIncrement() == 0) throw IllegalStateException("x"); pub.publish(routingKey, messageId, body) } }
        val st = OutboxRelay(s.tx, flaky, { listOf(a, b) }, s.alerts, s.security, clock = OutboxTestSupport.TestClock()).runOnce()
        assertEquals(RelayStats(published = 1, retried = 1), st)
    }
}

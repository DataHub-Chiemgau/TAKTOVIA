package de.taktovia.platform.outbox

import de.taktovia.platform.TestDatabase
import de.taktovia.platform.tenant.TenantId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcOperations
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Wirkung im Empfängermandanten: legt je Ereignis ein Fahrzeug an (Primärschlüssel -> eine zweite Wirkung würde scheitern). */
class VehicleHandler(private val s: OutboxTestSupport) : InboxHandler {
    val effects = AtomicInteger(); val snapshots = CopyOnWriteArrayList<AggregateSnapshot>(); val contexts = CopyOnWriteArrayList<UUID?>()
    override fun handle(jdbc: JdbcOperations, event: EventEnvelope) {
        contexts.add(jdbc.queryForObject("SELECT taktovia.current_tenant()", UUID::class.java))
        val v = event.payload.get("vehicle")?.asString() ?: UUID.randomUUID().toString()
        jdbc.update("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (taktovia.current_tenant(),?,'x','{}'::jsonb)", UUID.fromString(v))
        effects.incrementAndGet()
    }
    override fun applySnapshot(jdbc: JdbcOperations, snapshot: AggregateSnapshot) { snapshots.add(snapshot) }
}

/** WP-M1-14: Inbox-Baustein gegen echte Datenbank. */
class InboxProcessorTest {
    companion object {
        private lateinit var s: OutboxTestSupport
        @BeforeAll @JvmStatic fun setup() { TestDatabase.require(); s = OutboxTestSupport() }
        @AfterAll @JvmStatic fun teardown() { if (::s.isInitialized) s.close() }
    }

    private val mapper = tools.jackson.databind.json.JsonMapper.builder().build()
    private fun env(owner: TenantId, recipient: TenantId?, agg: UUID, rev: Long, payload: Map<String, Any?> = mapOf("vehicle" to UUID.randomUUID().toString()),
                    id: UUID = UUID.randomUUID(), occurred: Instant = Instant.parse("2026-02-03T04:05:06Z"), schema: String = "1.0") =
        EventEnvelope(id, "test.thing.changed", schema, agg, rev, occurred, Instant.parse("2026-02-03T04:05:07Z"), owner.value, recipient?.value, UUID.randomUUID(),
            mapper.valueToTree(payload))
    private fun processor(fetch: SnapshotFetcher = SnapshotFetcher { _, _, _ -> error("Snapshot nicht erwartet") }, policy: RecipientPolicy = RecipientPolicy.OWNER_ONLY) =
        InboxProcessor(s.tx, s.security, fetch, policy)
    private fun audits(t: TenantId, action: String, id: UUID) =
        s.read(t) { it.queryForObject("SELECT count(*) FROM taktovia.audit_event WHERE action = ? AND object_ref = ?", Int::class.java, action, "event:$id") }

    @Test fun `dreifach zugestelltes Ereignis Revision 8 wirkt genau einmal, spaetere Revision 7 setzt den Zustand nicht zurueck und loest Nachlesen aus`() {
        val t = s.newTenant(); val agg = UUID.randomUUID(); val h = VehicleHandler(s); val fetched = AtomicInteger()
        val p = processor(SnapshotFetcher { _, _, a -> fetched.incrementAndGet(); AggregateSnapshot(a, 8, mapper.createObjectNode()) })
        val e8 = env(t, t, agg, 8)
        assertEquals(listOf(InboxOutcome.PROCESSED, InboxOutcome.DUPLICATE, InboxOutcome.DUPLICATE), (1..3).map { p.process("c1", e8.toJson(), h) })
        assertEquals(1, h.effects.get())
        assertEquals(InboxOutcome.STALE, p.process("c1", env(t, t, agg, 7).toJson(), h))
        assertEquals(1, h.effects.get(), "kein Zurücksetzen"); assertEquals(1, fetched.get(), "Nachlesen ausgelöst"); assertTrue(h.snapshots.isEmpty())
    }

    @Test fun `Luecke wird per Snapshot geschlossen, ein noch aelterer Snapshot fuehrt zur erneuten Zustellung`() {
        val t = s.newTenant(); val agg = UUID.randomUUID(); val h = VehicleHandler(s)
        val ok = processor(SnapshotFetcher { _, _, a -> AggregateSnapshot(a, 3, mapper.createObjectNode().put("state", "x")) })
        assertEquals(InboxOutcome.PROCESSED, ok.process("c1", env(t, t, agg, 1).toJson(), h))
        val gap = env(t, t, agg, 3)
        val behind = processor(SnapshotFetcher { _, _, a -> AggregateSnapshot(a, 2, mapper.createObjectNode()) })
        assertThrows<SnapshotNotYetAvailableException> { behind.process("c1", gap.toJson(), h) }
        assertEquals(0, s.read(t) { it.queryForObject("SELECT count(*) FROM taktovia.inbox WHERE event_id = ?", Int::class.java, gap.eventId) }, "nichts festgeschrieben")
        assertEquals(InboxOutcome.GAP_SNAPSHOT, ok.process("c1", gap.toJson(), h)); assertEquals(3, h.snapshots.single().revision); assertEquals(1, h.effects.get())
        assertEquals(InboxOutcome.PROCESSED, ok.process("c1", env(t, t, agg, 4).toJson(), h)); assertEquals(2, h.effects.get())
    }

    @Test fun `gleiche event_id mit anderem Payloadhash wird nicht verarbeitet und als Sicherheitsereignis gemeldet`() {
        val t = s.newTenant(); val agg = UUID.randomUUID(); val h = VehicleHandler(s); val p = processor()
        val e = env(t, t, agg, 1); assertEquals(InboxOutcome.PROCESSED, p.process("c1", e.toJson(), h))
        val tampered = env(t, t, agg, 1, payload = mapOf("vehicle" to UUID.randomUUID().toString()), id = e.eventId)
        assertEquals(InboxOutcome.REJECTED_PAYLOAD_MISMATCH, p.process("c1", tampered.toJson(), h))
        assertEquals(1, h.effects.get()); assertEquals(1, audits(t, "security.event.payload_hash_mismatch", e.eventId))
        // beanspruchter Hash passt nicht zum Inhalt (Korruption beim Transport)
        val corrupt = env(t, t, UUID.randomUUID(), 1).let { it.copy(payloadSha256 = "0".repeat(64)) }
        assertEquals(InboxOutcome.REJECTED_PAYLOAD_MISMATCH, p.process("c1", corrupt.toJson(), h)); assertEquals(1, h.effects.get())
        assertEquals(1, audits(t, "security.event.payload_hash_mismatch", corrupt.eventId))
    }

    @Test fun `Verarbeitung nur im Kontext des adressierten Empfaengermandanten, Ereignis ohne passende Adressierung wird nicht zugestellt`() {
        val a = s.newTenant(); val b = s.newTenant(); val h = VehicleHandler(s); val p = processor(); val agg = UUID.randomUUID()
        val noRecipient = env(a, null, agg, 1); val foreign = env(a, b, agg, 1)
        assertEquals(InboxOutcome.REJECTED_NO_RECIPIENT, p.process("c1", noRecipient.toJson(), h))
        assertEquals(InboxOutcome.REJECTED_RECIPIENT, p.process("c1", foreign.toJson(), h))
        assertEquals(0, h.effects.get())
        assertEquals(0, s.read(b) { it.queryForObject("SELECT count(*) FROM taktovia.inbox", Int::class.java) })
        assertEquals(1, audits(a, "security.event.recipient_mismatch", noRecipient.eventId)); assertEquals(1, audits(a, "security.event.recipient_mismatch", foreign.eventId))
        // freigegebene Adressierung: Wirkung entsteht im Kontext des Empfängers B, nicht des Eigentümers A
        val allowed = processor(policy = { o, r -> o == a.value && r == b.value })
        assertEquals(InboxOutcome.PROCESSED, allowed.process("c1", env(a, b, agg, 1).toJson(), h))
        assertEquals(listOf<UUID?>(b.value), h.contexts.toList())
        assertEquals(1, s.read(b) { it.queryForObject("SELECT count(*) FROM taktovia.vehicle", Int::class.java) }); assertEquals(0, s.read(a) { it.queryForObject("SELECT count(*) FROM taktovia.vehicle", Int::class.java) })
    }

    @Test fun `zwei Consumer-Instanzen verarbeiten dasselbe Ereignis parallel mit einer Wirkung`() {
        val t = s.newTenant(); val h = VehicleHandler(s); val p = processor(); val e = env(t, t, UUID.randomUUID(), 1).toJson()
        val start = CountDownLatch(1); val exec = Executors.newFixedThreadPool(8)
        val outcomes = try {
            val fs = (1..8).map { exec.submit(Callable { start.await(); p.process("c1", e, h) }) }; start.countDown(); fs.map { it.get() }
        } finally { exec.shutdownNow() }
        assertEquals(1, outcomes.count { it == InboxOutcome.PROCESSED }); assertEquals(7, outcomes.count { it == InboxOutcome.DUPLICATE })
        assertEquals(1, h.effects.get()); assertEquals(1, s.read(t) { it.queryForObject("SELECT count(*) FROM taktovia.vehicle", Int::class.java) })
    }

    @Test fun `Ereigniszeit, Empfangszeit und Aggregatversion sind getrennt gespeichert und abfragbar`() {
        val t = s.newTenant(); val agg = UUID.randomUUID(); val e = env(t, t, agg, 5, occurred = Instant.parse("2020-01-01T00:00:00Z"))
        val before = Instant.now()
        assertEquals(InboxOutcome.PROCESSED, processor().process("c1", e.toJson(), VehicleHandler(s)))
        val row = s.read(t) { it.queryForMap("SELECT occurred_at, received_at, aggregate_revision FROM taktovia.inbox WHERE event_id = ?", e.eventId) }
        assertEquals(Instant.parse("2020-01-01T00:00:00Z"), (row["occurred_at"] as java.sql.Timestamp).toInstant())
        assertTrue(!(row["received_at"] as java.sql.Timestamp).toInstant().isBefore(before.minusSeconds(1)), "Empfangszeit ist die Verarbeitungszeit, nicht die Ereigniszeit")
        assertEquals(5L, row["aggregate_revision"])
        val o = s.append(t, agg, 1, occurredAt = Instant.parse("2019-05-05T00:00:00Z"))
        val orow = s.read(t) { it.queryForMap("SELECT occurred_at, recorded_at, aggregate_revision FROM taktovia.outbox WHERE event_id = ?", o) }
        assertEquals(Instant.parse("2019-05-05T00:00:00Z"), (orow["occurred_at"] as java.sql.Timestamp).toInstant()); assertTrue((orow["recorded_at"] as java.sql.Timestamp).toInstant().isAfter(before.minusSeconds(1)))
    }

    @Test fun `unbekannte Hauptversion und kaputte Huelle werden abgewiesen`() {
        val t = s.newTenant(); val h = VehicleHandler(s); val p = processor()
        assertEquals(InboxOutcome.REJECTED_SCHEMA, p.process("c1", env(t, t, UUID.randomUUID(), 1, schema = "2.0").toJson(), h))
        assertEquals(InboxOutcome.PROCESSED, p.process("c1", env(t, t, UUID.randomUUID(), 1, schema = "1.7").toJson(), h), "neue Nebenversion bleibt lesbar")
        for (bad in listOf("kein json", "[]", "{}", "{\"event_id\":\"x\"}")) assertEquals(InboxOutcome.REJECTED_SCHEMA, p.process("c1", bad, h), bad)
        assertEquals(1, h.effects.get())
    }

    @Test fun `die serialisierte Huelle enthaelt genau die Felder des JSON-Schemas`() {
        val schema = mapper.readTree(java.nio.file.Files.readString(java.nio.file.Path.of("../../api/events/platform.eventenvelope.v1.schema.json")))
        val t = TenantId(UUID.randomUUID()); val json = mapper.readTree(env(t, t, UUID.randomUUID(), 1).toJson())
        assertEquals(schema.get("properties").propertyNames().toSet(), json.propertyNames().toSet())
        val req = schema.get("required"); assertTrue((0 until req.size()).map { req.get(it).asString() }.all { json.has(it) })
        assertTrue(Regex("[0-9a-f]{64}").matches(json.get("payload_sha256").asString()))
        // Hash ist unabhängig von Schlüsselreihenfolge und Formatierung
        assertEquals(EventEnvelope.sha256(mapper.readTree("{\"b\":1.0,\"a\":[1,2]}")), EventEnvelope.sha256(mapper.readTree("{ \"a\": [1, 2], \"b\": 1 }")))
    }

    @Test fun `hundert identische Zustellungen erzeugen genau eine fachliche Wirkung (Dedupe-Baustein des Contract-Falls hundert_identische_webhooks)`() {
        val t = s.newTenant(); val h = VehicleHandler(s); val p = processor(); val e = env(t, t, UUID.randomUUID(), 1).toJson()
        val start = CountDownLatch(1); val exec = Executors.newFixedThreadPool(16)
        val outcomes = try {
            val fs = (1..100).map { exec.submit(Callable { start.await(); p.process("webhook", e, h) }) }; start.countDown(); fs.map { it.get() }
        } finally { exec.shutdownNow() }
        assertEquals(1, outcomes.count { it == InboxOutcome.PROCESSED }); assertEquals(99, outcomes.count { it == InboxOutcome.DUPLICATE })
        assertEquals(1, h.effects.get()); assertEquals(1, s.read(t) { it.queryForObject("SELECT count(*) FROM taktovia.inbox", Int::class.java) })
    }
}

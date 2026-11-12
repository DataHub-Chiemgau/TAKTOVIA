package de.taktovia.booking

import de.taktovia.bookingdispatch.RideOfferFixture
import de.taktovia.bookingdispatch.api.AttemptOutcome
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.dialect.Dialects
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** WP-M2-11: ein `travel_attempt` je fachlich neuer Suche, Retries zählen nicht neu (Kap. 04 §4.3); Datentrennung der Mandanten; keine Adressen oder Personenbezüge. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TravelAttemptTest {
    private lateinit var fx: RideOfferFixture
    @BeforeAll fun up() { fx = RideOfferFixture() }
    @AfterAll fun down() = fx.close()

    private fun attempts(tenant: de.taktovia.platform.tenant.TenantId) = fx.count("SELECT count(*) FROM taktovia.travel_attempt WHERE tenant_id = ?", tenant.value)

    @Test
    fun retry_zaehlt_nicht_neu_mandanten_getrennt() {
        val a = fx.world(); val b = fx.world()
        val api = fx.requests(); val ca = fx.passenger(a.tenant); val cb = fx.passenger(b.tenant)
        val cmd = fx.command(a, "2026-11-02T10:00:00+01:00", key = "schluessel-a-0000001")
        val first = api.request(ca, cmd)
        val retry = api.request(ca, cmd)
        assertFalse(first.retry); assertTrue(retry.retry)
        assertEquals(first.attemptId, retry.attemptId, "technischer Retry: derselbe Versuch")
        assertEquals(first.offers, retry.offers, "das gespeicherte Ergebnis wird wiederholt")
        assertEquals(1, attempts(a.tenant))
        // bewusst neue Suche (neuer Schlüssel, gleicher Inhalt) erzeugt einen neuen Versuch
        val fresh = api.request(ca, cmd.copy(idempotencyKey = "schluessel-a-0000002"))
        assertTrue(fresh.attemptId != first.attemptId); assertEquals(2, attempts(a.tenant))
        // gleicher Schlüssel, anderer Inhalt: Konflikt, kein neuer Versuch
        val conflict = assertThrows<ProblemException> { api.request(ca, cmd.copy(passengers = 3)) }
        assertEquals(ErrorCode.IDEMPOTENCY_CONFLICT, conflict.code); assertEquals(2, attempts(a.tenant))
        // anderer Akteur mit demselben Schlüsselwert hat einen eigenen Versuch (Scope enthält den Akteur)
        val other = api.request(fx.passenger(a.tenant), cmd)
        assertTrue(other.attemptId != first.attemptId); assertEquals(3, attempts(a.tenant))
        // Mandanten sind getrennt zählbar; unter der Runtime-Rolle sind fremde Versuche unsichtbar
        api.request(cb, fx.command(b, "2026-11-02T10:00:00+01:00", key = "schluessel-b-0000001"))
        assertEquals(1, attempts(b.tenant)); assertEquals(3, attempts(a.tenant))
        assertEquals(1, fx.tx.run(b.tenant) { j -> j.queryForObject("SELECT count(*) FROM taktovia.travel_attempt", Int::class.java)!! })
        assertEquals(0, fx.tx.run(b.tenant) { j -> j.queryForObject("SELECT count(*) FROM taktovia.travel_attempt WHERE id = ?", Int::class.java, first.attemptId)!! })
        // je Versuch genau ein Ereignis
        assertEquals(3, fx.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id = ? AND event_type = 'booking.attempt_recorded'", a.tenant.value))
    }

    @Test
    fun hundert_parallele_identische_requests_ein_versuch() {
        val w = fx.world(); val api = fx.requests(); val ctx = fx.passenger(w.tenant)
        val cmd = fx.command(w, "2026-11-02T10:00:00+01:00", key = "parallel-schluessel-001")
        val pool = Executors.newFixedThreadPool(50); val start = CountDownLatch(1)
        try {
            val futures = (1..100).map { pool.submit<de.taktovia.bookingdispatch.api.RideOfferResult> { start.await(); api.request(ctx, cmd) } }
            start.countDown()
            val results = futures.map { it.get(60, TimeUnit.SECONDS) }
            assertEquals(1, results.map { it.attemptId }.toSet().size, "alle 100 Aufrufe führen zum selben Versuch")
            assertEquals(1, results.count { !it.retry }, "genau ein Aufruf hat den Versuch angelegt")
            assertEquals(1, results.map { it.offers }.toSet().size, "alle liefern dasselbe Ergebnis")
            assertEquals(AttemptOutcome.OFFERED, results.first().outcome)
        } finally { pool.shutdownNow() }
        assertEquals(1, attempts(w.tenant))
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id = ? AND event_type = 'booking.attempt_recorded'", w.tenant.value))
    }

    @Test
    fun travel_attempt_ohne_adresse_und_personenbezug() {
        val w = fx.world(); val subject = "fahrgast-geheim-4711"; val ctx = fx.passenger(w.tenant, subject)
        fx.requests().request(ctx, fx.command(w, "2026-11-02T10:00:00+01:00", key = "adresse-schluessel-001"))
        // Schemaprüfung: keine Spalte, die Adresse, Koordinate oder Person tragen könnte
        val columns = fx.count("SELECT count(*) FROM information_schema.columns WHERE table_schema = 'taktovia' AND table_name = 'travel_attempt' " +
            "AND (column_name ~* '(address|adress|street|lat|lon|geom|point|name|mail|phone|subject|actor|user|passenger_id|label)')")
        assertEquals(0, columns, "keine Orts- oder Personenspalte in travel_attempt")
        // Inhaltsprüfung: die ganze Zeile als Text enthält weder Koordinaten noch den Akteur noch den Idempotency-Schlüssel
        val row = fx.tx.run(w.tenant) { j -> j.queryForObject("SELECT t::text FROM taktovia.travel_attempt t", String::class.java)!! }
        for (secret in listOf("13.74", "51.05", "13.80", "51.10", subject, "adresse-schluessel-001", "Markt", "Bahnhof")) assertFalse(row.contains(secret), "Zeile enthält '$secret'")
    }

    @Test
    fun ereignis_attempt_recorded_entspricht_dem_schema_ohne_orte() {
        val w = fx.world(); val api = fx.requests(); val ctx = fx.passenger(w.tenant)
        api.request(ctx, fx.command(w, "2026-11-02T10:00:00+01:00", key = "ereignis-schluessel-001"))                 // Zeitfenster geliefert
        api.request(ctx, fx.command(w, "2026-11-02T08:10:00+01:00", key = "ereignis-schluessel-002"))                 // zu knapp: LEAD_TIME
        val schema = SchemaRegistry.withDefaultDialect(Dialects.getDraft202012())
            .getSchema(java.nio.file.Files.readString(java.nio.file.Path.of(System.getProperty("taktovia.repoRoot")).resolve("api/events/booking.attempt_recorded.v1.schema.json")))
        val payloads = fx.tx.run(w.tenant) { j -> j.queryForList("SELECT payload::text FROM taktovia.outbox WHERE event_type = 'booking.attempt_recorded' ORDER BY occurred_at, event_id", String::class.java).map { it!! } }
        assertEquals(2, payloads.size)
        for (p in payloads) {
            assertEquals(emptyList<String>(), schema.validate(p, InputFormat.JSON).map { it.toString() })
            for (secret in listOf("13.74", "51.05", "Markt")) assertFalse(p.contains(secret), "Ereignis enthält '$secret'")
        }
        assertTrue(payloads.any { it.contains("\"LEAD_TIME\"") && it.contains("\"REJECTED\"") })
    }
}

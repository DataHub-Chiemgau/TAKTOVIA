package de.taktovia.booking

import com.networknt.schema.SchemaRegistry
import com.networknt.schema.dialect.Dialects
import de.taktovia.bookingdispatch.BookingTestDb
import de.taktovia.bookingdispatch.OfferObjectsFixture
import de.taktovia.bookingdispatch.api.AttemptOutcome
import de.taktovia.bookingdispatch.api.HoldRideOffer
import de.taktovia.bookingdispatch.api.OfferState
import de.taktovia.farepayment.api.FareComponentCode
import de.taktovia.platform.api.Etag
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * WP-M2-21 / LH-6.4-BKG-002 und WP-M2-09 im Zusammenspiel: Angebotsobjekt mit Ablauf und Revisionsbindung gegen die echte Datenbank unter der Runtime-Rolle. Jetzt ist Montag,
 * 2. November 2026, 07:00Z; Angebotsgültigkeit 300 s (also bis 07:05Z), Direktfahrzeit 600 s. Der Preis für zwei Personen ist 950 (EUR, 7 % Steuer enthalten).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RideOfferTest {
    private lateinit var fx: OfferObjectsFixture
    private val json = JsonMapper.builder().build()

    @BeforeAll fun up() { fx = OfferObjectsFixture() }
    @AfterAll fun down() = fx.close()

    private fun hold(ctx: de.taktovia.platform.context.RequestContext, offerId: UUID, revision: Long, key: String = "hold-" + UUID.randomUUID(), maxGross: Long = 950) =
        fx.offers.hold(ctx, offerId, HoldRideOffer(revision, key, maxGross, "EUR"))

    private fun problem(block: () -> Unit) = assertThrows<ProblemException> { block() }

    @Test
    fun angebot_unveraendert_bei_get_und_etag() {
        val w = fx.world(); val ctx = fx.passenger(w.tenant); val run = fx.run(w)
        val issued = fx.issue(w, ctx, fx.assigning(run))
        // Pflichtinhalt: ID, Ablauf, Preisstand (fare_quote), Abholpunkt und Dienstkennung
        assertEquals(Instant.parse("2026-11-02T07:05:00Z"), issued.expiresAt)
        assertEquals(950, issued.quote.payAtPlatformMinor); assertEquals("EUR", issued.quote.currency)
        assertNotNull(issued.pickupStop); assertNotNull(issued.dropoffStop); assertEquals(1, issued.revision)
        assertEquals(setOf(FareComponentCode.TRANSPORT, FareComponentCode.SURCHARGE, FareComponentCode.TAX), issued.quote.components.map { it.code }.toSet())
        val first = fx.offers.get(ctx, issued.id)
        assertEquals(w.serviceId, first.serviceId); assertEquals(issued.id, first.id); assertEquals(OfferState.OFFERED, first.state)
        assertEquals(issued.quote.id, first.quote.id); assertEquals(issued.expiresAt, first.expiresAt); assertEquals(1, first.runRevision); assertEquals(1, first.revision)
        // GET liefert unverändert denselben Inhalt und ETag, auch nach Zeitablauf innerhalb der Frist und nach einer später veröffentlichten Tarifrevision (Preisstand gebunden)
        fx.advance(Duration.ofSeconds(120))
        fx.publishTariff(w, 900, 100, from = "2027-01-01T00:00:00Z", to = null)
        val second = fx.offers.get(ctx, issued.id)
        assertEquals(first, second)
        assertEquals(Etag.format(first.revision), Etag.format(second.revision))
        assertEquals(950, second.quote.payAtPlatformMinor); assertEquals(first.quote.contentHash, second.quote.contentHash)
        // Wiederholung der Anfrage (gleicher Idempotency-Key) liefert dasselbe Angebot, kein neues
        val key = "retry-" + UUID.randomUUID()
        val cmd = fx.base.command(w, "2026-11-02T10:30:00+01:00", key = key)
        fx.advance(Duration.ofSeconds(-120))
        val a = fx.requests().request(ctx, cmd); val b = fx.requests().request(ctx, cmd)
        assertTrue(b.retry); assertEquals(a.offers.single().offer!!.id, b.offers.single().offer!!.id); assertEquals(a.offers.single().offer!!.quote, b.offers.single().offer!!.quote)
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.ride_offer WHERE attempt_id = ?", a.attemptId))
        // Ereignis „Angebot erstellt“ mit Schema; Versuch, Quote, Angebot und Ereignis kamen gemeinsam
        val payload = fx.base.eventPayloads("booking.offer_created", issued.id).single()
        val schema = SchemaRegistry.withDialect(Dialects.getDraft202012()).getSchema(Files.readString(BookingTestDb.repoRoot().resolve("api/events/booking.offer_created.v1.schema.json")))
        assertEquals(emptyList<String>(), schema.validate(json.readTree(payload)).map { it.message }, payload)
    }

    @Test
    fun abgelaufenes_angebot_ergibt_409_offer_expired() {
        val w = fx.world(); val ctx = fx.passenger(w.tenant); val run = fx.run(w)
        val issued = fx.issue(w, ctx, fx.assigning(run))
        fx.advance(Duration.ofSeconds(301))
        val p = problem { hold(ctx, issued.id, 1) }
        assertEquals(ErrorCode.OFFER_EXPIRED, p.code); assertEquals(409, p.code.status)
        // keine Buchung, kein Hold, Angebot zeigt sich als abgelaufen
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.capacity_hold WHERE tenant_id = ?", w.tenant.value))
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.booking WHERE tenant_id = ?", w.tenant.value))
        assertEquals(OfferState.EXPIRED, fx.offers.get(ctx, issued.id).state)
        // Ablauf geht vor veralteter Revision und falschem Betrag
        assertEquals(ErrorCode.OFFER_EXPIRED, problem { hold(ctx, issued.id, 99, maxGross = 1) }.code)
    }

    @Test
    fun zeitgrenze_exakt_bei_expires_at() {
        val w = fx.world(); val ctx = fx.passenger(w.tenant); val run = fx.run(w, OfferObjectsFixture.cap(4, 1))
        val before = fx.issue(w, ctx, fx.assigning(run)); val atBoundary = fx.issue(w, ctx, fx.assigning(run))
        // eine Millisekunde vor expires_at gilt das Angebot noch, ab `now >= expires_at` ist es abgelaufen (Serverzeit, steuerbare Uhr)
        fx.clock.set(before.expiresAt.minusMillis(1))
        assertEquals("HELD", hold(ctx, before.id, 1).hold.state)
        fx.clock.set(atBoundary.expiresAt)
        assertEquals(ErrorCode.OFFER_EXPIRED, problem { hold(ctx, atBoundary.id, 1) }.code)
        assertEquals(OfferState.EXPIRED, fx.offers.get(ctx, atBoundary.id).state)
        fx.clock.set(fx.base.now)
    }

    @Test
    fun fremdes_angebot_404() {
        val w = fx.world(); val owner = fx.passenger(w.tenant); val run = fx.run(w)
        val issued = fx.issue(w, owner, fx.assigning(run))
        val other = fx.passenger(w.tenant)                                  // anderer Fahrgast desselben Mandanten
        val foreign = fx.world(); val foreignCtx = fx.passenger(foreign.tenant)   // anderer Mandant
        for (ctx in listOf(other, foreignCtx)) {
            assertEquals(ErrorCode.NOT_FOUND, problem { fx.offers.get(ctx, issued.id) }.code)
            assertEquals(ErrorCode.NOT_FOUND, problem { hold(ctx, issued.id, 1) }.code)
            assertEquals(ErrorCode.NOT_FOUND, problem { fx.offers.release(ctx, issued.id, 1) }.code)
        }
        assertEquals(ErrorCode.NOT_FOUND, problem { fx.offers.get(owner, UUID.randomUUID()) }.code, "unbekannt wie fremd")
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.capacity_hold WHERE tenant_id = ?", w.tenant.value))
    }

    @Test
    fun fehlender_if_match_428_und_veralteter_412() {
        val w = fx.world(); val ctx = fx.passenger(w.tenant); val run = fx.run(w)
        val issued = fx.issue(w, ctx, fx.assigning(run))
        // 428 entsteht am HTTP-Rand (`Etag.parseIfMatch`, hier dessen Vertrag; die HTTP-Ebene prüft `RideOfferHoldHttpApiTest` in der Anwendung)
        assertEquals(ErrorCode.PRECONDITION_REQUIRED, problem { Etag.parseIfMatch(null) }.code); assertEquals(428, ErrorCode.PRECONDITION_REQUIRED.status)
        val p = problem { hold(ctx, issued.id, 7) }
        assertEquals(ErrorCode.REVISION_MISMATCH, p.code); assertEquals(412, p.code.status)
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.capacity_hold WHERE tenant_id = ?", w.tenant.value))
        assertEquals("HELD", hold(ctx, issued.id, 1).hold.state)
    }

    @Test
    fun veraendertes_angebot_requote_oder_revision_mismatch() {
        val w = fx.world(); val ctx = fx.passenger(w.tenant); val run = fx.run(w)
        val issued = fx.issue(w, ctx, fx.assigning(run))
        // Preis: höherer wie niedrigerer akzeptierter Betrag und andere Währung weichen vom eingefrorenen Preisstand ab
        for (accepted in listOf(949L, 951L, 0L)) assertEquals(ErrorCode.REQUOTE_REQUIRED, problem { hold(ctx, issued.id, 1, maxGross = accepted) }.code, "akzeptiert $accepted")
        assertEquals(ErrorCode.REQUOTE_REQUIRED, problem { fx.offers.hold(ctx, issued.id, HoldRideOffer(1, "k-" + UUID.randomUUID(), 950, "USD")) }.code)
        // Planrevision: der Umlauf wurde nach dem Angebot fortgeschrieben, das Angebot ist veraltet (kein stilles Übernehmen)
        fx.adminUpdate("UPDATE taktovia.vehicle_run SET revision = revision + 1 WHERE id = ?", run.runId)
        assertEquals(ErrorCode.REQUOTE_REQUIRED, problem { hold(ctx, issued.id, 1) }.code)
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.capacity_hold WHERE tenant_id = ?", w.tenant.value), "keine Kapazität gehalten, keine Buchung")
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.booking WHERE tenant_id = ?", w.tenant.value))
        // Tarif nach dem Angebot ändert den Preisstand nicht: der Betrag des Angebots bleibt der akzeptierte
        val w2 = fx.world(); val ctx2 = fx.passenger(w2.tenant); val run2 = fx.run(w2)
        val o2 = fx.issue(w2, ctx2, fx.assigning(run2))
        fx.publishTariff(w2, 2000, 0, from = "2027-01-01T00:00:00Z", to = null)
        assertEquals("HELD", hold(ctx2, o2.id, 1, maxGross = 950).hold.state)
        // ohne Planbindung (kein Umlauf zugeordnet) gibt es keine Kapazität: 409 CAPACITY_UNAVAILABLE
        val unassigned = fx.issue(w2, ctx2)
        assertEquals(ErrorCode.CAPACITY_UNAVAILABLE, problem { hold(ctx2, unassigned.id, 1) }.code)
    }

    @Test
    fun parallele_holds_hoechstens_einer() {
        val w = fx.world(); val ctx = fx.passenger(w.tenant); val run = fx.run(w)
        val issued = fx.issue(w, ctx, fx.assigning(run))
        val n = 12; val start = CountDownLatch(1); val pool = Executors.newFixedThreadPool(n)
        val results = try {
            val fs = (0 until n).map { pool.submit(Callable { start.await(); runCatching { hold(ctx, issued.id, 1) } }) }
            start.countDown(); fs.map { it.get() }
        } finally { pool.shutdownNow() }
        assertEquals(1, results.count { it.isSuccess }, "höchstens einer erfolgreich")
        // jeder andere endet mit einem definierten Fachgrund (veraltete Revision), nie mit einer unbehandelten Ausnahme
        results.filter { it.isFailure }.forEach { assertTrue(it.exceptionOrNull() is ProblemException && (it.exceptionOrNull() as ProblemException).code in setOf(ErrorCode.REVISION_MISMATCH, ErrorCode.INVALID_STATE_TRANSITION), it.exceptionOrNull().toString()) }
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.capacity_hold WHERE run_id = ? AND state = 'HELD'", run.runId))
        assertEquals(2L, fx.offers.get(ctx, issued.id).revision)
        // gleicher Schlüssel parallel: alle erhalten denselben Hold
        val o2 = fx.issue(w, ctx, fx.assigning(fx.run(w))); val key = "same-" + UUID.randomUUID()
        val start2 = CountDownLatch(1); val pool2 = Executors.newFixedThreadPool(6)
        val same = try {
            val fs = (0 until 6).map { pool2.submit(Callable { start2.await(); hold(ctx, o2.id, 1, key) }) }
            start2.countDown(); fs.map { it.get() }
        } finally { pool2.shutdownNow() }
        assertEquals(1, same.map { it.hold.id }.toSet().size); assertEquals(1, same.count { !it.replayed })
    }

    @Test
    fun fare_quote_mutation_unter_runtime_rolle_abgewiesen() {
        val w = fx.world(); val ctx = fx.passenger(w.tenant)
        val issued = fx.issue(w, ctx)
        for (sql in listOf("UPDATE taktovia.fare_quote SET pay_at_platform_minor = 1 WHERE id = ?", "DELETE FROM taktovia.fare_quote WHERE id = ?",
            "UPDATE taktovia.fare_component SET amount_minor = 1 WHERE quote_id = ?", "DELETE FROM taktovia.fare_component WHERE quote_id = ?")) {
            assertThrows<Exception>(sql) { fx.base.tx.run(w.tenant) { j -> j.update(sql, issued.quote.id) } }
        }
        // der Inhalt des Angebots ist unter der Runtime-Rolle nicht änderbar (Spaltenrecht), Löschen ebenso
        for (sql in listOf("UPDATE taktovia.ride_offer SET expires_at = expires_at + interval '1 hour' WHERE id = ?", "UPDATE taktovia.ride_offer SET quote_id = quote_id WHERE id = ?", "DELETE FROM taktovia.ride_offer WHERE id = ?")) {
            assertThrows<Exception>(sql) { fx.base.tx.run(w.tenant) { j -> j.update(sql, issued.id) } }
        }
        assertEquals(950, fx.offers.get(ctx, issued.id).quote.payAtPlatformMinor)
    }

    @Test
    fun wiederholter_hold_nach_ablauf_idempotent() {
        val w = fx.world(); val ctx = fx.passenger(w.tenant); val run = fx.run(w)
        val issued = fx.issue(w, ctx, fx.assigning(run))
        val first = hold(ctx, issued.id, 1, key = "retry-after-expiry-0001")
        assertEquals("HELD", first.hold.state); assertEquals(false, first.replayed)
        // nach expires_at des Angebots: dieselbe Anfrage liefert das ursprüngliche Ergebnis, nicht OFFER_EXPIRED
        fx.advance(Duration.ofSeconds(400))
        val again = hold(ctx, issued.id, 1, key = "retry-after-expiry-0001")
        assertEquals(first.hold.id, again.hold.id); assertEquals(true, again.replayed); assertEquals(first.offerRevision, again.offerRevision)
        // ein anderer Schlüssel ist ein neuer Befehl: Angebot ist nicht mehr zu halten (abgelaufen bzw. bereits gehalten), nie ein zweiter Hold
        assertTrue(problem { hold(ctx, issued.id, 2, key = "other-key-after-expiry-02") }.code in setOf(ErrorCode.INVALID_STATE_TRANSITION, ErrorCode.OFFER_EXPIRED, ErrorCode.REVISION_MISMATCH))
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.capacity_hold WHERE run_id = ?", run.runId))
        fx.clock.set(fx.base.now)
    }

    @Test
    fun kein_tarif_ergibt_503_ohne_angebot_und_ohne_ablehnung() {
        val w = fx.world(withTariff = false); val ctx = fx.passenger(w.tenant)
        val cmd = fx.base.command(w, "2026-11-02T10:00:00+01:00")
        val p = problem { fx.requests().request(ctx, cmd) }
        assertEquals(ErrorCode.SERVICE_UNAVAILABLE, p.code)
        // Versuch ist festgehalten, aber als technischer Fehler (keine fachliche Ablehnung), ohne Quote und ohne Angebot
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.travel_attempt WHERE tenant_id = ? AND outcome = 'TECHNICAL_ERROR'", w.tenant.value))
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.travel_attempt WHERE tenant_id = ? AND outcome = 'REJECTED'", w.tenant.value))
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.fare_quote WHERE tenant_id = ?", w.tenant.value))
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.ride_offer WHERE tenant_id = ?", w.tenant.value))
        // sobald ein Tarif veröffentlicht ist, führt derselbe Befehl (gleicher Schlüssel) denselben Versuch fort und liefert das Angebot
        fx.publishTariff(w, 450, 50)
        val ok = fx.requests().request(ctx, cmd)
        assertEquals(AttemptOutcome.OFFERED, ok.outcome); assertNotNull(ok.offers.single().offer)
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.travel_attempt WHERE tenant_id = ?", w.tenant.value))
        assertNotEquals(0, fx.count("SELECT count(*) FROM taktovia.fare_quote WHERE tenant_id = ?", w.tenant.value))
    }
}

package de.taktovia.bookingdispatch

import com.networknt.schema.SchemaRegistry
import com.networknt.schema.dialect.Dialects
import de.taktovia.bookingdispatch.api.BookingDemand
import de.taktovia.bookingdispatch.api.BookingdispatchApi
import de.taktovia.bookingdispatch.api.HoldRideOffer
import de.taktovia.bookingdispatch.api.OfferState
import de.taktovia.bookingdispatch.api.PromiseCore
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WP-M2-22 / LH-6.5-OPT-009: Ablauf von Holds. Die Prüfung bei jedem Zugriff (Serverzeit der Datenbank) macht Kapazität frei, auch bevor der Expiry-Job lief; der Job setzt den Zustand
 * unter demselben Lock wie Hold, Freigabe und Bestätigung. Ein Hold ist nie zugleich abgelaufen und belegt.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HoldExpiryTest {
    private lateinit var fx: OfferObjectsFixture
    private lateinit var api: BookingdispatchApi
    private val json = JsonMapper.builder().build()
    private val promise = PromiseCore("2026-11-02T10:00:00+01:00", "2026-11-02T10:15:00+01:00", "2026-11-02T10:10:00+01:00", "2026-11-02T10:30:00+01:00", 500, "EUR", "std-24h", "standard")
    private val one = BookingDemand.of(1, 0, emptySet(), emptyList())

    @BeforeAll fun up() { fx = OfferObjectsFixture(); api = BookingdispatchApi.create(fx.base.ctxTx) }
    @AfterAll fun down() = fx.close()

    private fun events(type: String, id: UUID) = fx.base.eventPayloads(type, id)

    @Test
    fun ablauf_gibt_kapazitaet_frei_und_nie_abgelaufen_und_belegt() {
        val w = fx.world(); val ctx = fx.passenger(w.tenant); val run = fx.run(w, OfferObjectsFixture.cap(1, 0))
        val short = fx.offersWithTtl(Duration.ofMillis(400))
        val a = fx.issue(w, ctx, fx.assigning(run), passengers = 1); val b = fx.issue(w, ctx, fx.assigning(run), passengers = 1)
        val held = short.hold(ctx, a.id, HoldRideOffer(1, "k-" + UUID.randomUUID(), 500, "EUR"))
        // solange der Hold gilt, ist der letzte Platz belegt
        assertEquals(ErrorCode.CAPACITY_UNAVAILABLE, assertThrows<ProblemException> { short.hold(ctx, b.id, HoldRideOffer(1, "k-" + UUID.randomUUID(), 500, "EUR")) }.code)
        Thread.sleep(600)
        // Zugriffsprüfung: der Job lief noch nicht, der Zustand steht in der Tabelle noch auf HELD, die Sicht zeigt EXPIRED und der Platz ist frei
        assertEquals("HELD", fx.stateOf(held.hold.id))
        assertEquals("EXPIRED", fx.offers.get(ctx, a.id).hold!!.state); assertEquals(OfferState.EXPIRED, fx.offers.get(ctx, a.id).state)
        assertEquals(ErrorCode.HOLD_EXPIRED, assertThrows<ProblemException> { api.holds.confirm(ctx, held.hold.id, w.serviceId, promise, one) }.code, "abgelaufen: keine Bestätigung")
        assertEquals("HELD", short.hold(ctx, b.id, HoldRideOffer(1, "k-" + UUID.randomUUID(), 500, "EUR")).hold.state, "freigegebene Kapazität ist wieder buchbar, ohne den Job abzuwarten")
        // Job: setzt den abgelaufenen Hold (und sein Angebot) auf EXPIRED, genau einmal, fortsetzbar; den neuen gültigen Hold lässt er unberührt
        assertEquals(1, fx.expiry.runOnce(w.tenant))
        assertEquals("EXPIRED", fx.stateOf(held.hold.id)); assertEquals(0, fx.expiry.runOnce(w.tenant), "zweiter Lauf: nichts mehr zu tun")
        assertEquals(OfferState.EXPIRED, fx.offers.get(ctx, a.id).state)
        assertEquals("HELD", fx.offers.get(ctx, b.id).hold!!.state)
        assertEquals(1, events("booking.hold_expired", held.hold.id).size)
        val schema = SchemaRegistry.withDialect(Dialects.getDraft202012()) { s ->
            s.schemas(mapOf("https://taktovia.example/schemas/resource-vector.v1.schema.json" to Files.readString(BookingTestDb.repoRoot().resolve("docs/umsetzungsplan/vertraege/schemas/resource-vector.v1.schema.json"))))
        }.getSchema(Files.readString(BookingTestDb.repoRoot().resolve("api/events/booking.hold_expired.v1.schema.json")))
        val ev = events("booking.hold_expired", held.hold.id).single()
        assertEquals(emptyList<String>(), schema.validate(json.readTree(ev)).map { it.message }, ev)
        // nie abgelaufen und belegt: kein verfallener Hold hat eine Buchung, belegt wird nur durch gültige HELD und CONSUMED
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.capacity_hold h JOIN taktovia.booking bk ON bk.hold_id = h.id WHERE h.tenant_id = ? AND h.state = 'EXPIRED'", w.tenant.value))
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.capacity_hold WHERE run_id = ? AND state = 'HELD' AND expires_at > clock_timestamp()", run.runId))
    }

    @Test
    fun job_und_bestaetigung_am_ablaufzeitpunkt_hoechstens_ein_ausgang() {
        val w = fx.world(); val ctx = fx.passenger(w.tenant)
        val short = fx.offersWithTtl(Duration.ofMillis(300)); val rnd = java.util.Random(20261112)
        var consumed = 0; var expired = 0
        repeat(14) { round ->
            val run = fx.run(w, OfferObjectsFixture.cap(1, 0))
            val offer = fx.issue(w, ctx, fx.assigning(run), passengers = 1)
            val held = short.hold(ctx, offer.id, HoldRideOffer(1, "k-" + UUID.randomUUID(), 500, "EUR"))
            val stop = AtomicBoolean(false); val start = CountDownLatch(1); val pool = Executors.newFixedThreadPool(2)
            val confirm = try {
                // der Job läuft laufend (jedes Aufräumen unter dem Lock des Umlaufs), die Bestätigung trifft um den Ablaufzeitpunkt herum ein
                pool.submit { start.await(); while (!stop.get()) { fx.expiry.runOnce(w.tenant); Thread.sleep(3) } }
                val c = pool.submit(Callable { start.await(); Thread.sleep(220L + rnd.nextInt(160)); runCatching { api.holds.confirm(ctx, held.hold.id, w.serviceId, promise, one) } })
                start.countDown(); c.get()
            } finally { Thread.sleep(50); stop.set(true); pool.shutdown(); pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS) }
            fx.expiry.runOnce(w.tenant)
            val state = fx.stateOf(held.hold.id); val bookings = fx.count("SELECT count(*) FROM taktovia.booking WHERE hold_id = ?", held.hold.id)
            if (confirm.isSuccess) { assertEquals("CONSUMED", state, "Runde $round"); assertEquals(1, bookings); consumed++ }
            else {
                assertEquals(ErrorCode.HOLD_EXPIRED, (confirm.exceptionOrNull() as ProblemException).code, "Runde $round")
                assertEquals("EXPIRED", state); assertEquals(0, bookings, "nie abgelaufen und gebucht"); expired++
            }
        }
        assertEquals(14, consumed + expired)
        assertTrue(consumed > 0 && expired > 0, "die Zeitpunkte um den Ablauf herum müssen beide Ausgänge erreichen (bestätigt $consumed, abgelaufen $expired)")
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.capacity_hold h JOIN taktovia.booking bk ON bk.hold_id = h.id WHERE h.tenant_id = ? AND h.state <> 'CONSUMED'", w.tenant.value))
    }
}

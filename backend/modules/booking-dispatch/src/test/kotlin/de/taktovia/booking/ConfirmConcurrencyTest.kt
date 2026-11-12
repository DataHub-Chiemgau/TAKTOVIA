package de.taktovia.booking

import de.taktovia.bookingdispatch.OfferObjectsFixture
import de.taktovia.bookingdispatch.RideOfferFixture
import de.taktovia.bookingdispatch.api.ConfirmBooking
import de.taktovia.bookingdispatch.api.HoldRideOffer
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * WP-M2-23 / LH-6.4-BKG-003, PH-AT-004, AT-03: Bestätigungen unter Konkurrenz gegen die echte Datenbank unter der Runtime-Rolle, echte parallele Transaktionen auf getrennten Verbindungen.
 * Jeder Versuch ist der vollständige Weg Hold -> Confirm eines eigenen Fahrgasts mit eigenem Angebot („unterschiedliche fachliche Anfragen“).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConfirmConcurrencyTest {
    private lateinit var cf: ConfirmFixture

    @BeforeAll fun up() { cf = ConfirmFixture() }
    @AfterAll fun down() = cf.close()

    /** Ergebnis eines Versuchs: Buchung oder fachlicher Ablehnungsgrund (jede andere Ausnahme, etwa ein Deadlock, bleibt eine Ausnahme und lässt den Test scheitern). */
    private sealed interface Attempt { data class Booked(val id: UUID) : Attempt; data class Rejected(val code: ErrorCode) : Attempt }

    private fun hundertStyle(n: Int, run: OfferObjectsFixture.Run, w: RideOfferFixture.World, intervals: (Int) -> Pair<Int, Int> = { 0 to 2 }): List<Attempt> {
        val prepared = (0 until n).map { i ->
            val (f, t) = intervals(i); val p = cf.passenger(w)
            cf.fx.issue(w, p.ctx, cf.fx.assigning(run, 1, f, t), 1, 0) to p
        }
        return race(prepared.map { (offer, p) -> Callable<Attempt> {
            try {
                val h = cf.fx.offers.hold(p.ctx, offer.id, HoldRideOffer(offer.revision, "k-" + UUID.randomUUID(), offer.quote.payAtPlatformMinor, "EUR"))
                Attempt.Booked(cf.bookings.confirm(p.ctx, ConfirmBooking(h.hold.id, h.offerRevision, offer.quote.payAtPlatformMinor, "EUR")).booking.id)
            } catch (e: ProblemException) { Attempt.Rejected(e.code) }
        } })
    }

    private fun <T> race(tasks: List<Callable<T>>): List<T> {
        val pool = Executors.newFixedThreadPool(tasks.size.coerceAtMost(64)); val start = CountDownLatch(1)
        try {
            val futures = tasks.map { t -> pool.submit(Callable { start.await(); t.call() }) }
            start.countDown()
            return futures.map { it.get() }
        } finally { pool.shutdownNow() }
    }

    private fun assertNeverOverCapacity(runId: UUID, segments: Int, seats: Int, wheelchair: Int) {
        for ((i, occ) in cf.fx.base.occupancy(runId, segments).withIndex()) {
            assertTrue(occ.first in 0..seats, "Abschnitt $i: Sitze ${occ.first} von $seats"); assertTrue(occ.second in 0..wheelchair, "Abschnitt $i: Rollstuhlplätze ${occ.second}")
        }
    }

    @Test
    fun hundert_parallele_confirms_ein_sitz() {
        val w = cf.fx.world(); val run = cf.fx.run(w, OfferObjectsFixture.cap(4, 1))
        repeat(3) { cf.booked(w, run, passengers = 1) }                     // 3 von 4 Sitzen belegt: genau ein Sitz frei
        val results = hundertStyle(100, run, w)
        val booked = results.filterIsInstance<Attempt.Booked>(); val rejected = results.filterIsInstance<Attempt.Rejected>()
        assertEquals(1, booked.size, "höchstens eine neue Sitzreservierung")
        assertEquals(99, rejected.size)
        assertTrue(rejected.all { it.code == ErrorCode.CAPACITY_UNAVAILABLE }, "alle Abweisungen mit eindeutigem Grund: ${rejected.map { it.code }.toSet()}")
        assertEquals(4, cf.count("SELECT count(*) FROM taktovia.booking WHERE tenant_id = ?", w.tenant.value))
        assertNeverOverCapacity(run.runId, 1, 4, 1)
        assertEquals(4, cf.fx.base.occupancy(run.runId, 1).maxOf { it.first })
        // keine negative Belegung, Zähler der Buchungsnummern lückenlos (Abweisungen vergeben keine Nummer)
        assertEquals(4, cf.count("SELECT count(DISTINCT booking_no) FROM taktovia.booking WHERE tenant_id = ?", w.tenant.value))
        assertEquals(4, cf.count("SELECT last_number FROM taktovia.booking_number_counter WHERE tenant_id = ?", w.tenant.value))
    }

    @Test
    fun zwei_clients_letzter_platz_hoechstens_eine_buchung() {
        repeat(5) {
            val w = cf.fx.world(); val run = cf.fx.run(w, OfferObjectsFixture.cap(1, 0))
            val results = hundertStyle(2, run, w)
            assertEquals(1, results.count { it is Attempt.Booked }, "AT-03: zwei Clients, ein Platz: $results")
            assertEquals(1, cf.count("SELECT count(*) FROM taktovia.booking WHERE tenant_id = ?", w.tenant.value))
            assertNeverOverCapacity(run.runId, 1, 1, 0)
        }
    }

    @Test
    fun rollstuhlplatz_belegt_sitz_frei_und_routenrevision_veraendert() {
        val w = cf.fx.world(); val run = cf.fx.run(w, OfferObjectsFixture.cap(4, 1))
        // Rollstuhlplatz belegt (1 Person im Rollstuhl), Sitze frei
        cf.booked(w, run, passengers = 1, wheelchair = 1)
        val wheel = cf.passenger(w); val seat = cf.passenger(w)
        val oWheel = cf.fx.issue(w, wheel.ctx, cf.fx.assigning(run), 1, 1)
        val oSeat = cf.fx.issue(w, seat.ctx, cf.fx.assigning(run), 1, 0)
        val r = race(listOf(
            Callable<Attempt> { attempt(wheel, oWheel) }, Callable<Attempt> { attempt(seat, oSeat) },
        ))
        assertEquals(Attempt.Rejected(ErrorCode.CAPACITY_UNAVAILABLE), r[0], "zweiter Rollstuhl: Platz belegt")
        assertTrue(r[1] is Attempt.Booked, "Sitz frei: Buchung gelingt")
        assertNeverOverCapacity(run.runId, 1, 4, 1)
        // veränderte Routenrevision zwischen Hold und Confirm: der Hold wird nie still umgedeutet, es entsteht keine Buchung
        val p = cf.passenger(w); val h = cf.held(w, run, p, passengers = 1)
        cf.fx.adminUpdate("UPDATE taktovia.vehicle_run SET revision = revision + 1 WHERE id = ?", run.runId)
        val ex = assertThrows<ProblemException> { cf.confirm(h) }
        assertEquals(ErrorCode.CAPACITY_UNAVAILABLE, ex.code)
        assertEquals(0, cf.count("SELECT count(*) FROM taktovia.booking WHERE hold_id = ?", h.holdId))
        assertEquals("HELD", cf.fx.stateOf(h.holdId))
    }

    private fun attempt(p: ConfirmFixture.Passenger, offer: de.taktovia.bookingdispatch.api.IssuedOffer): Attempt = try {
        val h = cf.fx.offers.hold(p.ctx, offer.id, HoldRideOffer(offer.revision, "k-" + UUID.randomUUID(), offer.quote.payAtPlatformMinor, "EUR"))
        Attempt.Booked(cf.bookings.confirm(p.ctx, ConfirmBooking(h.hold.id, h.offerRevision, offer.quote.payAtPlatformMinor, "EUR")).booking.id)
    } catch (e: ProblemException) { Attempt.Rejected(e.code) }

    @Test
    fun confirm_parallel_expiry_und_release_ein_ausgang() {
        val w = cf.fx.world(); val run = cf.fx.run(w, OfferObjectsFixture.cap(8, 1))
        val short = cf.fx.offersWithTtl(Duration.ofMillis(300))
        val outcomes = mutableSetOf<String>()
        repeat(12) {
            val h = cf.held(w, run, passengers = 1, offers = short)
            Thread.sleep(250 + (it % 4) * 25L)    // rund um den Ablaufzeitpunkt
            val results = race(listOf(
                Callable { runCatching { cf.confirm(h) } }, Callable { runCatching { short.release(h.passenger.ctx, h.offer.id, h.offerRevision) } }, Callable { runCatching { cf.fx.expiry.runOnce(w.tenant) } },
            ))
            // nie ein unbehandelter Fehler: Ablehnungen sind fachlich definiert
            results.forEach { r -> r.exceptionOrNull()?.let { e -> assertTrue(e is ProblemException, "unerwartet: $e") } }
            val state = cf.fx.stateOf(h.holdId); outcomes += state
            val bookings = cf.count("SELECT count(*) FROM taktovia.booking WHERE hold_id = ?", h.holdId)
            assertTrue(state in setOf("CONSUMED", "RELEASED", "EXPIRED"), "Zustand $state")
            assertEquals(if (state == "CONSUMED") 1 else 0, bookings, "Buchung genau dann, wenn der Hold verbraucht wurde (Zustand $state)")
            // genau ein Ausgang: der Confirm gelingt nur bei CONSUMED, die Freigabe nur bei RELEASED (sonst Fehler oder Wiederholung ohne Wirkung)
            assertEquals(state == "CONSUMED", results[0].isSuccess, "Confirm gelingt genau bei CONSUMED (Zustand $state)")
        }
        assertTrue(outcomes.isNotEmpty())
    }

    @Test
    fun gegenlaeufige_abschnittsreihenfolge_kein_deadlock() {
        val w = cf.fx.world(); val run = cf.fx.run(w, OfferObjectsFixture.cap(4, 1))
        // abwechselnd [0,2) und [1,3), dazu in umgekehrter Startreihenfolge: gegenläufige Abschnittsreihenfolge als Deadlock-Provokation
        val results = hundertStyle(40, run, w) { i -> if (i % 2 == 0) 0 to 2 else 1 to 3 }
        assertTrue(results.all { it is Attempt.Booked || (it as Attempt.Rejected).code == ErrorCode.CAPACITY_UNAVAILABLE }, "nur Buchung oder definierter Fachgrund: ${results.filterIsInstance<Attempt.Rejected>().map { it.code }.toSet()}")
        assertEquals(4, results.count { it is Attempt.Booked }, "alle überlappen am Abschnitt 1: höchstens 4 Sitze")
        assertNeverOverCapacity(run.runId, 2, 4, 1)
    }

    @Test
    fun abbruch_mitten_in_transaktion_atomar() {
        val w = cf.fx.world(); val run = cf.fx.run(w)
        val h = cf.held(w, run)
        val t = w.tenant.value
        // Erzwungener Abbruch nach allen Schreibzugriffen (Hold verbraucht, Buchung, Journey-Hülle, Nummer): das Ereignis `booking.confirmed` dieses Mandanten wird per Trigger abgewiesen
        cf.fx.adminUpdate("CREATE OR REPLACE FUNCTION taktovia.m2_23_fail_outbox() RETURNS trigger LANGUAGE plpgsql AS \$f\$ BEGIN RAISE EXCEPTION 'erzwungener Abbruch' USING ERRCODE = '57014'; END \$f\$")
        cf.fx.adminUpdate("CREATE TRIGGER m2_23_abort BEFORE INSERT ON taktovia.outbox FOR EACH ROW WHEN (NEW.event_type = 'booking.confirmed' AND NEW.tenant_id = '$t'::uuid) EXECUTE FUNCTION taktovia.m2_23_fail_outbox()")
        try {
            assertThrows<Exception> { cf.confirm(h) }
        } finally {
            cf.fx.adminUpdate("DROP TRIGGER m2_23_abort ON taktovia.outbox")
            cf.fx.adminUpdate("DROP FUNCTION taktovia.m2_23_fail_outbox()")
        }
        assertEquals(0, cf.count("SELECT count(*) FROM taktovia.booking WHERE tenant_id = ?", t), "keine Buchung")
        assertEquals(0, cf.count("SELECT count(*) FROM taktovia.journey WHERE tenant_id = ?", t), "keine Journey-Hülle")
        assertEquals(0, cf.count("SELECT count(*) FROM taktovia.booking_number_counter WHERE tenant_id = ?", t), "keine vergebene Buchungsnummer")
        assertEquals(0, cf.count("SELECT count(*) FROM taktovia.passenger_trip WHERE tenant_id = ?", t))
        assertEquals("HELD", cf.fx.stateOf(h.holdId), "Hold nicht verbraucht")
        assertEquals(0, cf.fx.base.eventPayloads("booking.confirmed", h.holdId).size + cf.count("SELECT count(*) FROM taktovia.outbox WHERE event_type = 'booking.confirmed' AND tenant_id = ?", t))
        // danach gelingt dieselbe Bestätigung (Zustand war unverändert)
        assertEquals("CONFIRMING", cf.confirm(h).booking.state)
    }
}

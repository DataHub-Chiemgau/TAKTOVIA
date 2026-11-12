package de.taktovia.booking

import de.taktovia.bookingdispatch.OfferObjectsFixture
import de.taktovia.bookingdispatch.RideOfferFixture
import de.taktovia.bookingdispatch.api.BookingReason
import de.taktovia.bookingdispatch.api.CancelBooking
import de.taktovia.bookingdispatch.api.ConfirmBooking
import de.taktovia.bookingdispatch.api.HistoryView
import de.taktovia.bookingdispatch.api.HoldRideOffer
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.statemachine.BookingState
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
 * WP-M2-26 / LH-6.4-BKG-005: Storno unter Konkurrenz gegen die echte Datenbank unter der Runtime-Rolle (echte parallele Transaktionen). Invarianten nach jedem Lauf: Kapazität nie über dem
 * physischen Maximum und nie negativ, Hold und Buchung stimmen überein (aktive Buchung = verbrauchter Hold, stornierte Buchung = freigegebener Hold), Historie und Revision deckungsgleich.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CancelConcurrencyTest {
    private lateinit var cf: ConfirmFixture

    @BeforeAll fun up() { cf = ConfirmFixture() }
    @AfterAll fun down() = cf.close()

    private fun <T> race(tasks: List<Callable<T>>): List<T> {
        val pool = Executors.newFixedThreadPool(tasks.size.coerceAtMost(32)); val start = CountDownLatch(1)
        try { val f = tasks.map { t -> pool.submit(Callable { start.await(); t.call() }) }; start.countDown(); return f.map { it.get() } } finally { pool.shutdownNow() }
    }

    /** Fachliche Ablehnung als Ergebnis, jede andere Ausnahme (Deadlock, Constraint) bleibt eine Ausnahme und lässt den Test scheitern. */
    private fun <T> attempt(block: () -> T): Result<T> = try { Result.success(block()) } catch (e: ProblemException) { Result.failure(e) }

    private fun assertConsistent(w: RideOfferFixture.World, run: OfferObjectsFixture.Run, cap: Int) {
        val t = w.tenant.value
        for ((i, o) in cf.fx.base.occupancy(run.runId, 1).withIndex()) assertTrue(o.first in 0..cap, "Abschnitt $i belegt ${o.first} von $cap")
        assertEquals(0, cf.count("SELECT count(*) FROM taktovia.booking b JOIN taktovia.capacity_hold h ON h.tenant_id = b.tenant_id AND h.id = b.hold_id WHERE b.tenant_id = ? AND b.state IN ('CONFIRMING','CONFIRMED') AND h.state <> 'CONSUMED'", t), "aktive Buchung ohne verbrauchten Hold")
        assertEquals(0, cf.count("SELECT count(*) FROM taktovia.booking b JOIN taktovia.capacity_hold h ON h.tenant_id = b.tenant_id AND h.id = b.hold_id WHERE b.tenant_id = ? AND b.state = 'CANCELED' AND h.state <> 'RELEASED'", t), "stornierte Buchung ohne freigegebenen Hold")
        assertEquals(0, cf.count("SELECT count(*) FROM taktovia.booking b WHERE b.tenant_id = ? AND b.revision <> (SELECT count(*) FROM taktovia.booking_status_history h WHERE h.tenant_id = b.tenant_id AND h.booking_id = b.id)", t), "Historie = Revision")
    }

    @Test
    fun storno_parallel_zu_confirm_nie_ueberbelegt() {
        repeat(3) {
            val w = cf.world(); val run = cf.fx.run(w, OfferObjectsFixture.cap(1, 0)); val op = cf.operator(w.tenant, setOf(w.serviceId))
            val (h1, b1) = cf.booked(w, run, passengers = 1)                              // der einzige Sitz ist belegt
            cf.bookings.transition(op, b1.id, BookingState.CONFIRMED, BookingReason.PAYMENT_AUTHORIZED, 1)
            val newcomers = (1..6).map { val p = cf.passenger(w); cf.fx.issue(w, p.ctx, cf.fx.assigning(run), 1, 0) to p }
            val tasks = mutableListOf<Callable<Result<Any>>>(Callable { attempt { cf.bookings.cancel(h1.passenger.ctx, b1.id, CancelBooking(2, BookingReason.PASSENGER_REQUEST)) } })
            newcomers.forEach { (offer, p) -> tasks += Callable { attempt {
                val h = cf.fx.offers.hold(p.ctx, offer.id, HoldRideOffer(offer.revision, "k-" + UUID.randomUUID(), offer.quote.payAtPlatformMinor, "EUR"))
                cf.bookings.confirm(p.ctx, ConfirmBooking(h.hold.id, h.offerRevision, offer.quote.payAtPlatformMinor, "EUR"))
            } } }
            val results = race(tasks)
            assertTrue(results[0].isSuccess, "das Storno gelingt: ${results[0]}")
            val failures = results.drop(1).filter { it.isFailure }.map { (it.exceptionOrNull() as ProblemException).code }.toSet()
            assertTrue(failures.all { it == ErrorCode.CAPACITY_UNAVAILABLE }, "nur der definierte Fachgrund: $failures")
            assertTrue(results.drop(1).count { it.isSuccess } <= 1, "höchstens ein Neubucher auf den einen Platz")
            assertEquals("CANCELED", cf.bookings.get(op, b1.id).state)
            assertConsistent(w, run, 1)
            assertEquals(results.drop(1).count { it.isSuccess }, cf.count("SELECT count(*) FROM taktovia.booking WHERE tenant_id = ? AND state IN ('CONFIRMING','CONFIRMED')", w.tenant.value))
        }
    }

    @Test
    fun doppeltes_storno_eine_freigabe() {
        repeat(4) { round ->
            val w = cf.world(); val run = cf.fx.run(w, OfferObjectsFixture.cap(4, 0)); val op = cf.operator(w.tenant, setOf(w.serviceId))
            val (h, b) = cf.booked(w, run, passengers = 2)
            val results = race(listOf(
                Callable { attempt { cf.bookings.cancel(h.passenger.ctx, b.id, CancelBooking(1, BookingReason.PASSENGER_REQUEST)) } },
                Callable { attempt { cf.bookings.cancel(if (round % 2 == 0) op else h.passenger.ctx, b.id, CancelBooking(1, if (round % 2 == 0) BookingReason.OPERATOR_CANCELLATION else BookingReason.PASSENGER_REQUEST)) } },
            ))
            assertEquals(1, results.count { it.isSuccess }, "genau ein Storno: $results")
            assertEquals(ErrorCode.REVISION_MISMATCH, (results.single { it.isFailure }.exceptionOrNull() as ProblemException).code)
            assertEquals(1, cf.fx.base.eventPayloads("booking.cancelled", b.id).size, "genau eine Erstattungsentscheidung/ein Ereignis")
            assertEquals("RELEASED", cf.fx.stateOf(h.holdId)); assertEquals(0, cf.fx.base.occupancy(run.runId, 1).maxOf { it.first }, "keine doppelte Freigabe, nie negativ")
            assertEquals(3, cf.bookings.history(op, b.id, HistoryView.AUDIT).size)
            assertConsistent(w, run, 4)
        }
    }

    @Test
    fun storno_parallel_expiry_und_neubuchung_ein_ausgang() {
        val short = cf.fx.offersWithTtl(Duration.ofMillis(350))
        repeat(8) { round ->
            val w = cf.world(); val run = cf.fx.run(w, OfferObjectsFixture.cap(2, 0))
            val (h1, b1) = cf.booked(w, run, passengers = 1)                                   // belegt einen der zwei Sitze
            val pending = cf.held(w, run, passengers = 1, offers = short)                      // ein zweiter Sitz mit kurzem Hold: läuft rund um das Storno ab
            val rebook = cf.passenger(w).let { p -> cf.fx.issue(w, p.ctx, cf.fx.assigning(run), 1, 0) to p }
            Thread.sleep(300 + (round % 3) * 20L)
            val results = race(listOf<Callable<Result<Any>>>(
                Callable { attempt { cf.bookings.cancel(h1.passenger.ctx, b1.id, CancelBooking(1, BookingReason.PASSENGER_REQUEST)) } },
                Callable { attempt { cf.fx.expiry.runOnce(w.tenant) } },
                Callable { attempt { cf.confirm(pending) } },
                Callable { attempt {
                    val (offer, p) = rebook
                    val h = cf.fx.offers.hold(p.ctx, offer.id, HoldRideOffer(offer.revision, "k-" + UUID.randomUUID(), offer.quote.payAtPlatformMinor, "EUR"))
                    cf.bookings.confirm(p.ctx, ConfirmBooking(h.hold.id, h.offerRevision, offer.quote.payAtPlatformMinor, "EUR"))
                } },
            ))
            assertTrue(results[0].isSuccess, "das Storno gelingt: ${results[0]}")
            results.drop(2).filter { it.isFailure }.forEach { assertTrue((it.exceptionOrNull() as ProblemException).code in setOf(ErrorCode.HOLD_EXPIRED, ErrorCode.CAPACITY_UNAVAILABLE, ErrorCode.INVALID_STATE_TRANSITION), "${it.exceptionOrNull()}") }
            val pendingState = cf.fx.stateOf(pending.holdId)
            // `HELD` ist nur zulässig, wenn der Hold inzwischen abgelaufen ist (der Job lief vor dem Ablaufzeitpunkt): er belegt dann nichts mehr und lässt sich nicht bestätigen
            assertTrue(pendingState in setOf("CONSUMED", "EXPIRED") || (pendingState == "HELD" && cf.count("SELECT count(*) FROM taktovia.capacity_hold WHERE id = ? AND expires_at <= clock_timestamp()", pending.holdId) == 1), "Hold des Wartenden: $pendingState")
            assertEquals(pendingState == "CONSUMED", results[2].isSuccess, "Bestätigung gelingt genau dann, wenn der Hold verbraucht wurde (Zustand $pendingState)")
            assertConsistent(w, run, 2)
        }
    }

    @Test
    fun abbruch_mitten_in_transaktion_unveraendert() {
        val w = cf.world(); val run = cf.fx.run(w, OfferObjectsFixture.cap(4, 0)); val op = cf.operator(w.tenant, setOf(w.serviceId)); val t = w.tenant.value
        val (h, b0) = cf.booked(w, run)
        cf.bookings.transition(op, b0.id, BookingState.CONFIRMED, BookingReason.PAYMENT_AUTHORIZED, 1)
        val b = cf.bookings.get(op, b0.id)
        val from = h.offer.pickupStop!!.revisionId; val to = h.offer.dropoffStop!!.revisionId; val mv = de.taktovia.bookingdispatch.api.StopMovement(b.id, de.taktovia.bookingdispatch.api.ResourceVector(2, 0))
        val pickup = java.time.Instant.parse("2026-11-02T09:00:00Z")
        cf.runs.appendRevision(op, run.runId, 1, listOf(
            de.taktovia.bookingdispatch.api.PlanStop(0, from, pickup, boardings = listOf(mv), legDurationSec = 600, legDistanceM = 5000),
            de.taktovia.bookingdispatch.api.PlanStop(1, to, pickup.plusSeconds(600), alightings = listOf(mv)),
        ), de.taktovia.bookingdispatch.api.RevisionCause.MANUAL_CHANGE, listOf(b.id))
        // Abbruch nach allen Schreibzugriffen (Übergänge, Hold-Freigabe, Planrevision): das Ereignis `booking.cancelled` dieses Mandanten wird per Trigger abgewiesen
        cf.fx.adminUpdate("CREATE OR REPLACE FUNCTION taktovia.m2_26_fail_outbox() RETURNS trigger LANGUAGE plpgsql AS \$f\$ BEGIN RAISE EXCEPTION 'erzwungener Abbruch' USING ERRCODE = '57014'; END \$f\$")
        cf.fx.adminUpdate("CREATE TRIGGER m2_26_abort BEFORE INSERT ON taktovia.outbox FOR EACH ROW WHEN (NEW.event_type = 'booking.cancelled' AND NEW.tenant_id = '$t'::uuid) EXECUTE FUNCTION taktovia.m2_26_fail_outbox()")
        try { assertThrows<Exception> { cf.bookings.cancel(h.passenger.ctx, b.id, CancelBooking(2, BookingReason.PASSENGER_REQUEST)) } }
        finally { cf.fx.adminUpdate("DROP TRIGGER m2_26_abort ON taktovia.outbox"); cf.fx.adminUpdate("DROP FUNCTION taktovia.m2_26_fail_outbox()") }
        // Buchung, Hold, Plan, Historie und Ereignisse unverändert
        val after = cf.bookings.get(op, b.id)
        assertEquals("CONFIRMED", after.state); assertEquals(2, after.revision); assertEquals("CONSUMED", cf.fx.stateOf(h.holdId))
        assertEquals(2, cf.runs.run(op, run.runId).revision); assertEquals(2, cf.runs.currentPlan(op, run.runId).stops.size)
        assertEquals(2, cf.count("SELECT count(*) FROM taktovia.booking_status_history WHERE booking_id = ?", b.id))
        assertEquals(0, cf.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id = ? AND event_type IN ('booking.cancelled', 'dispatch.run_revised') AND aggregate_revision > 2", t))
        assertEquals(2, cf.fx.base.occupancy(run.runId, 1).maxOf { it.first })
        // danach gelingt dasselbe Storno
        assertEquals("CANCELED", cf.bookings.cancel(h.passenger.ctx, b.id, CancelBooking(2, BookingReason.PASSENGER_REQUEST)).booking.state)
        assertConsistent(w, run, 4)
    }
}

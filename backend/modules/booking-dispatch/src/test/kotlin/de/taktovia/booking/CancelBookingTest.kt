package de.taktovia.booking

import de.taktovia.bookingdispatch.OfferObjectsFixture
import de.taktovia.bookingdispatch.api.BookingDetail
import de.taktovia.bookingdispatch.api.BookingReason
import de.taktovia.bookingdispatch.api.CancelBooking
import de.taktovia.bookingdispatch.api.HistoryView
import de.taktovia.bookingdispatch.api.PlanStop
import de.taktovia.bookingdispatch.api.ResourceVector
import de.taktovia.bookingdispatch.api.RevisionCause
import de.taktovia.bookingdispatch.api.StopMovement
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.statemachine.BookingState
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * WP-M2-26 / LH-6.4-BKG-005, AT-07 (Routenteil): Storno einer Buchung gegen die echte Datenbank unter der Runtime-Rolle mit steuerbarer Uhr. Preis 2 Personen = 950; das Abholfenster der Angebote
 * beginnt 2026-11-02 09:00Z, das Jetzt der Fixture ist 07:00Z.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CancelBookingTest {
    private lateinit var cf: ConfirmFixture
    private val pickup = Instant.parse("2026-11-02T09:00:00Z")
    private val now0 = Instant.parse("2026-11-02T07:00:00Z")

    @BeforeAll fun up() { cf = ConfirmFixture() }
    @AfterAll fun down() = cf.close()
    @AfterEach fun resetClock() = cf.fx.clock.set(now0)

    private fun code(block: () -> Unit) = assertThrows<ProblemException> { block() }.code
    private fun cancel(ctx: RequestContext, b: BookingDetail, reason: BookingReason = BookingReason.PASSENGER_REQUEST, rev: Long = b.revision) = cf.bookings.cancel(ctx, b.id, CancelBooking(rev, reason))
    private fun seats(runId: UUID) = cf.fx.base.occupancy(runId, 1).maxOf { it.first }
    private fun entries(id: UUID) = cf.count("SELECT count(*) FROM taktovia.booking_status_history WHERE booking_id = ?", id)

    @Test
    fun storno_gibt_kapazitaet_frei_und_neue_planrevision() {
        val w = cf.world(deadlineMin = 120); val run = cf.fx.run(w, OfferObjectsFixture.cap(2, 0)); val op = cf.operator(w.tenant, setOf(w.serviceId))
        val (h, b0) = cf.booked(w, run)                              // 2 Sitze: die Buchung belegt den ganzen Wagen
        cf.bookings.transition(op, b0.id, BookingState.CONFIRMED, BookingReason.PAYMENT_AUTHORIZED, 1)
        val b = cf.bookings.get(op, b0.id)
        assertEquals(2, seats(run.runId))
        // voller Wagen: ein weiterer Hold scheitert; der Plan (Revision 2) enthält die Buchung mit Einstieg und Ausstieg
        assertEquals(ErrorCode.CAPACITY_UNAVAILABLE, code { cf.held(w, run, passengers = 1) })
        val from = h.offer.pickupStop!!.revisionId; val to = h.offer.dropoffStop!!.revisionId; val mv = StopMovement(b.id, ResourceVector(2, 0))
        cf.runs.appendRevision(op, run.runId, 1, listOf(PlanStop(0, from, pickup, boardings = listOf(mv), legDurationSec = 600, legDistanceM = 5000), PlanStop(1, to, pickup.plusSeconds(600), alightings = listOf(mv))), RevisionCause.MANUAL_CHANGE, listOf(b.id))
        // Storno durch den Fahrgast genau an der Fristgrenze (Frist 120 min vor dem Abholfenster): noch innerhalb
        cf.fx.clock.set(pickup.minus(Duration.ofMinutes(120)))
        val r = cancel(h.passenger.ctx, b)
        assertEquals("CANCELED", r.booking.state); assertEquals(h.holdId, r.releasedHoldId); assertEquals(4, r.booking.revision)
        assertEquals("RELEASED", cf.fx.stateOf(h.holdId)); assertEquals(0, seats(run.runId), "Kapazität auf allen Abschnitten wieder frei")
        // Fahrtenplan (Fahrerauftrag): neue Revision 3 ohne die Stopps der Buchung, Revision 2 bleibt lesbar
        assertEquals(3L, r.planRevision); assertEquals(3, cf.runs.run(op, run.runId).revision)
        assertEquals(emptyList<PlanStop>(), cf.runs.currentPlan(op, run.runId).stops)
        assertTrue(cf.runs.plan(op, run.runId, 2).stops.any { s -> s.boardings.any { it.bookingId == b.id } })
        assertEquals(1, cf.count("SELECT count(*) FROM taktovia.outbox WHERE event_type = 'dispatch.run_revised' AND aggregate_id = ? AND aggregate_revision = 3", run.runId))
        // Historie mit Grund, Ereignis mit Grund, Initiator
        val hist = cf.bookings.history(op, b.id, HistoryView.AUDIT)
        assertEquals(listOf("CONFIRMING", "CONFIRMED", "CANCEL_PENDING", "CANCELED"), hist.map { it.toState })
        assertEquals(listOf(BookingReason.PASSENGER_REQUEST, BookingReason.CANCEL_COMPLETED), hist.drop(2).map { it.reason }); assertEquals("PASSENGER", hist[2].actorKind)
        val ev = cf.fx.base.eventPayloads("booking.cancelled", b.id).single()
        assertTrue(ev.contains("PASSENGER_REQUEST") && ev.contains(h.holdId.toString()) && ev.contains("\"initiator\": \"PASSENGER\""), ev)
        // die Kapazität ist wirklich frei: eine neue Buchung auf denselben Platz (aktueller Planstand) gelingt
        cf.fx.clock.set(now0)
        assertEquals("CONFIRMING", cf.booked(w, run, planRevision = 3).second.state)
        assertEquals(2, seats(run.runId))
    }

    @Test
    fun storno_aus_confirming_kompensiert() {
        val w = cf.world(); val run = cf.fx.run(w)
        val (h, b) = cf.booked(w, run)
        val r = cancel(h.passenger.ctx, b)
        assertEquals("CANCELED", r.booking.state); assertEquals(3, r.booking.revision); assertEquals(null, r.planRevision, "der Plan enthielt die Buchung nicht")
        assertEquals(listOf("CONFIRMING", "COMPENSATING", "CANCELED"), cf.bookings.history(cf.operator(w.tenant, setOf(w.serviceId)), b.id, HistoryView.AUDIT).map { it.toState })
        assertEquals("RELEASED", cf.fx.stateOf(h.holdId)); assertEquals(0, seats(run.runId))
        assertEquals("CANCELED", cf.stringOf("SELECT j.state FROM taktovia.journey j JOIN taktovia.booking b ON b.tenant_id = j.tenant_id AND b.journey_id = j.id WHERE b.id = ?", b.id), "die Journey-Hülle spiegelt den Zustand")
    }

    @Test
    fun storno_ausserhalb_frist_abgewiesen() {
        val w = cf.world(deadlineMin = 120); val run = cf.fx.run(w); val op = cf.operator(w.tenant, setOf(w.serviceId))
        val (h, b) = cf.booked(w, run)
        cf.fx.clock.set(pickup.minus(Duration.ofMinutes(120)).plusMillis(1))     // eine Millisekunde nach der Fristgrenze
        assertEquals(ErrorCode.CANCEL_DEADLINE_EXPIRED, code { cancel(h.passenger.ctx, b) })
        assertEquals("CONFIRMING", cf.bookings.get(op, b.id).state); assertEquals("CONSUMED", cf.fx.stateOf(h.holdId)); assertEquals(1, entries(b.id)); assertEquals(2, seats(run.runId))
        // Betrieb im Dienstscope ist an die Frist des Fahrgasts nicht gebunden
        assertEquals("CANCELED", cancel(op, b, BookingReason.OPERATOR_CANCELLATION).booking.state)
        // Planungsdefault (ohne Parameter): 60 Minuten
        val w2 = cf.world(); val run2 = cf.fx.run(w2); val (h2, b2) = cf.booked(w2, run2); val (h3, b3) = cf.booked(w2, run2)
        cf.fx.clock.set(pickup.minus(Duration.ofMinutes(59)))
        assertEquals(ErrorCode.CANCEL_DEADLINE_EXPIRED, code { cancel(h2.passenger.ctx, b2) })
        cf.fx.clock.set(pickup.minus(Duration.ofMinutes(60)))
        assertEquals("CANCELED", cancel(h3.passenger.ctx, b3).booking.state)
        assertEquals("CONFIRMING", cf.bookings.get(h2.passenger.ctx, b2.id).state)
    }

    @Test
    fun verbleibende_zusagen_bleiben_eingehalten() {
        val w = cf.world(); val run = cf.fx.run(w, OfferObjectsFixture.cap(4, 0)); val op = cf.operator(w.tenant, setOf(w.serviceId))
        val (h1, b1) = cf.booked(w, run, passengers = 1); val (_, b2) = cf.booked(w, run, passengers = 1)
        val from = h1.offer.pickupStop!!.revisionId; val to = h1.offer.dropoffStop!!.revisionId
        fun mv(b: BookingDetail) = StopMovement(b.id, ResourceVector(1, 0))
        // b1 steigt am ersten Stopp ein und am Zwischenstopp aus (nur für b1), b2 fährt bis zum Ende
        cf.runs.appendRevision(op, run.runId, 1, listOf(
            PlanStop(0, from, pickup, boardings = listOf(mv(b1), mv(b2)), legDurationSec = 600, legDistanceM = 5000),
            PlanStop(1, to, pickup.plusSeconds(600), alightings = listOf(mv(b1)), legDurationSec = 300, legDistanceM = 2500),
            PlanStop(2, to, pickup.plusSeconds(900), alightings = listOf(mv(b2))),
        ), RevisionCause.MANUAL_CHANGE, listOf(b1.id, b2.id))
        val r = cancel(h1.passenger.ctx, b1)
        assertEquals(3L, r.planRevision)
        val after = cf.runs.currentPlan(op, run.runId).stops
        // der Zwischenstopp entfällt, b2 behält Einstieg und Ausstieg zu unveränderten Planzeiten, die Fahrzeit zum Folgestopp deckt die entfallene Teilstrecke
        assertEquals(2, after.size); assertEquals(listOf(0, 1), after.map { it.seq })
        assertEquals(pickup, after[0].plannedAt); assertEquals(pickup.plusSeconds(900), after[1].plannedAt)
        assertEquals(listOf(b2.id), after[0].boardings.map { it.bookingId }); assertEquals(listOf(b2.id), after[1].alightings.map { it.bookingId })
        assertEquals(900, after[0].legDurationSec); assertEquals(7500, after[0].legDistanceM); assertEquals(null, after[1].legDurationSec)
        assertTrue(after.none { s -> (s.boardings + s.alightings).any { it.bookingId == b1.id } })
        assertEquals("CONFIRMING", cf.bookings.get(op, b2.id).state); assertEquals("CONSUMED", cf.fx.stateOf(cf.stringOf("SELECT hold_id::text FROM taktovia.booking WHERE id = ?", b2.id).let(UUID::fromString)))
        assertEquals(1, seats(run.runId), "nur b2 belegt noch")
    }

    @Test
    fun unzulaessiger_zustand_grund_und_header_abgewiesen() {
        val w = cf.world(reasons = listOf("PASSENGER_REQUEST", "OPERATOR_CANCELLATION")); val run = cf.fx.run(w); val op = cf.operator(w.tenant, setOf(w.serviceId))
        val (h, b) = cf.booked(w, run)
        // Grund: Fahrgast darf nur Kundenstorno (403), nicht-Stornogrund und für den Dienst nicht zulässiger Grund (422)
        assertEquals(ErrorCode.FORBIDDEN, code { cancel(h.passenger.ctx, b, BookingReason.VEHICLE_OUTAGE) })
        assertEquals(ErrorCode.VALIDATION_FAILED, code { cancel(op, b, BookingReason.HOLD_CONFIRMED) })
        assertEquals(ErrorCode.VALIDATION_FAILED, code { cancel(op, b, BookingReason.CANCEL_COMPLETED) })
        assertEquals(ErrorCode.VALIDATION_FAILED, code { cancel(op, b, BookingReason.VEHICLE_OUTAGE) }, "für diesen Dienst nicht zugelassen")
        // veraltete Revision: 412
        assertEquals(ErrorCode.REVISION_MISMATCH, code { cancel(h.passenger.ctx, b, rev = 9) })
        // nichts hat sich geändert
        assertEquals("CONFIRMING", cf.bookings.get(op, b.id).state); assertEquals(1, cf.bookings.get(op, b.id).revision); assertEquals("CONSUMED", cf.fx.stateOf(h.holdId)); assertEquals(1, entries(b.id)); assertEquals(2, seats(run.runId))
        // bereits storniert: 409, ebenso abgeschlossen
        cancel(h.passenger.ctx, b)
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, code { cancel(h.passenger.ctx, b, rev = 3) })
        val (_, c) = cf.booked(w, run, passengers = 1)
        cf.bookings.transition(op, c.id, BookingState.CONFIRMED, BookingReason.PAYMENT_AUTHORIZED, 1); cf.bookings.transition(op, c.id, BookingState.COMPLETED, BookingReason.TRIP_COMPLETED, 2)
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, code { cancel(op, cf.bookings.get(op, c.id), BookingReason.OPERATOR_CANCELLATION) })
        assertEquals(3, entries(c.id)); assertEquals(1, seats(run.runId), "die abgeschlossene Buchung belegt weiter")
    }

    @Test
    fun fremde_buchung_404() {
        val w = cf.world(); val run = cf.fx.run(w)
        val (h, b) = cf.booked(w, run)
        val other = cf.passenger(w); val w2 = cf.world()
        assertEquals(ErrorCode.NOT_FOUND, code { cancel(other.ctx, b) })
        assertEquals(ErrorCode.NOT_FOUND, code { cancel(cf.operator(w.tenant, setOf(UUID.randomUUID())), b, BookingReason.OPERATOR_CANCELLATION) })
        assertEquals(ErrorCode.NOT_FOUND, code { cancel(cf.operator(w.tenant, emptySet()), b, BookingReason.OPERATOR_CANCELLATION) })
        assertEquals(ErrorCode.NOT_FOUND, code { cancel(cf.operator(w2.tenant, setOf(w2.serviceId)), b, BookingReason.OPERATOR_CANCELLATION) })
        assertEquals(ErrorCode.NOT_FOUND, code { cancel(cf.passenger(w2).ctx, b) })
        assertEquals(ErrorCode.NOT_FOUND, code { cf.bookings.cancel(h.passenger.ctx, UUID.randomUUID(), CancelBooking(1, BookingReason.PASSENGER_REQUEST)) })
        assertEquals("CONFIRMING", cf.bookings.get(h.passenger.ctx, b.id).state); assertEquals(1, entries(b.id))
    }
}

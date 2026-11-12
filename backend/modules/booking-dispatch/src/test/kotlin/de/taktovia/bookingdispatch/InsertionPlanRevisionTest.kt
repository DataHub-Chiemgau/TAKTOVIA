package de.taktovia.bookingdispatch

import de.taktovia.bookingdispatch.InsertionTestSupport.demand
import de.taktovia.bookingdispatch.InsertionTestSupport.offered
import de.taktovia.bookingdispatch.api.BookingdispatchApi
import de.taktovia.bookingdispatch.api.HoldOutcome
import de.taktovia.bookingdispatch.api.InsertionOutcome
import de.taktovia.bookingdispatch.api.RejectionReason
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID

/**
 * WP-M2-17 / LH-6.5-OPT-003, Kern gegen den echten Python-Worker (Prozess, HTTP `/v1/insert`) und die echte Datenbank: jede Variante nennt die Planrevision, gegen die sie gerechnet wurde;
 * eine Variante auf veralteter Revision wird beim Hold als Revisionskonflikt abgewiesen, zwei Anfragen auf denselben Umlauf erhalten Varianten mit jeweils eigener (derselben) Revision.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InsertionPlanRevisionTest {
    private lateinit var fx: BookingFixture
    private lateinit var api: BookingdispatchApi
    private lateinit var worker: InsertionTestSupport.WorkerProcess

    @BeforeAll fun up() {
        worker = InsertionTestSupport.WorkerProcess()
        fx = BookingFixture(); api = BookingdispatchApi.create(fx.ctxTx)
    }
    @AfterAll fun down() { if (::worker.isInitialized) worker.close(); if (::fx.isInitialized) fx.close() }

    private fun flow() = RideFlow(fx, api, InsertionTestSupport.service(worker), fx.createRun("""{"schema_version":"1.0","seats":4,"wheelchair":1,"equipment":["RAMP"]}"""))

    @Test
    fun veraltete_planrevision_ergibt_revisionskonflikt_bei_hold() {
        val f = flow()
        val dx = demand(UUID.randomUUID(), 3, 6, 600, 1200, 1500, 2400)
        val dy = demand(UUID.randomUUID(), 4, 5, 0, 5000, 0, 9000)
        // beide Anfragen rechnen auf Revision 1 des Umlaufs und nennen sie
        val vx = offered(f.find(dx)).first(); val vy = offered(f.find(dy)).first()
        assertEquals(1L, vx.planRevision); assertEquals(1L, vy.planRevision)
        assertEquals(listOf(2, 5), vx.stops.map { InsertionTestSupport.stopKeys[it.stopRevisionId]!!.substring(1).toInt() - 1 }.let { listOf(it[0], it[1]) })
        // die erste Variante wird gehalten: Revision 2
        val placed = f.hold(vx, dx) as HoldOutcome.Placed
        assertEquals(2L, placed.hold.runRevision)
        // die zweite Variante ist veraltet: Revisionskonflikt, nichts gespeichert
        val stale = f.hold(vy, dy)
        assertEquals(HoldOutcome.Rejected(RejectionReason.REVISION_CONFLICT, null), stale)
        assertEquals(2L, api.runs.run(f.ctx, f.run.runId).revision)
        assertTrue(api.runs.currentPlan(f.ctx, f.run.runId).stops.none { s -> s.boardings.any { it.bookingId == dy.bookingId } })
        // neu gerechnet auf der aktuellen Revision: die Variante nennt Revision 2 und wird gehalten; sie bezieht die gehaltene Buchung des ersten ein
        val fresh = offered(f.find(dy)).first()
        assertEquals(2L, fresh.planRevision)
        assertTrue(fresh.stops.any { s -> s.boardings.any { it.bookingId == dx.bookingId } })
        assertTrue(f.hold(fresh, dy) is HoldOutcome.Placed)
        assertEquals(3L, api.runs.run(f.ctx, f.run.runId).revision)
    }

    @Test
    fun ohne_zulaessige_position_kommt_eine_begruendete_ablehnung() {
        val f = flow()
        val out = f.find(demand(UUID.randomUUID(), 4, 5, 0, 10, 0, 20))   // Abholfenster endet, bevor das Fahrzeug den Halt erreichen kann
        assertTrue(out is InsertionOutcome.Rejected)
        out as InsertionOutcome.Rejected
        assertEquals("NO_FEASIBLE_POSITION", out.reason); assertTrue(out.grounds.getOrDefault("PICKUP_WINDOW", 0) > 0)
    }
}

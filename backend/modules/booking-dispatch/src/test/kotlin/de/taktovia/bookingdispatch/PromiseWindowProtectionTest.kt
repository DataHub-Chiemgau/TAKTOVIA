package de.taktovia.bookingdispatch

import de.taktovia.bookingdispatch.InsertionTestSupport.PARAMS
import de.taktovia.bookingdispatch.InsertionTestSupport.at
import de.taktovia.bookingdispatch.InsertionTestSupport.demand
import de.taktovia.bookingdispatch.InsertionTestSupport.offered
import de.taktovia.bookingdispatch.InsertionTestSupport.stop
import de.taktovia.bookingdispatch.api.BookingdispatchApi
import de.taktovia.bookingdispatch.api.HoldOutcome
import de.taktovia.bookingdispatch.api.InsertionOutcome
import de.taktovia.bookingdispatch.api.PlanStop
import de.taktovia.bookingdispatch.api.RejectionReason
import de.taktovia.bookingdispatch.api.RevisionCause
import de.taktovia.bookingdispatch.api.StopMovement
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * WP-M2-19 / LH-6.5-OPT-006: Schutz zugesagter Zeitfenster gegen den echten Python-Worker und die echte Datenbank (Runtime-Rolle, echte parallele Transaktionen). Szenario: X (H3 nach H6) ist
 * bestätigt, Abholung nominal 600 s nach `T0`; eine neue Buchung N (H2 nach H3, vor X) schiebt die Abholung von X um 120 s. Das Zusagefenster von X bestimmt, ob das zulässig ist.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PromiseWindowProtectionTest {
    private lateinit var fx: BookingFixture
    private lateinit var api: BookingdispatchApi
    private lateinit var worker: InsertionTestSupport.WorkerProcess

    @BeforeAll fun up() {
        worker = InsertionTestSupport.WorkerProcess()
        fx = BookingFixture(); api = BookingdispatchApi.create(fx.ctxTx)
    }
    @AfterAll fun down() { if (::worker.isInitialized) worker.close(); if (::fx.isInitialized) fx.close() }

    private fun flow() = RideFlow(fx, api, InsertionTestSupport.service(worker), fx.createRun("""{"schema_version":"1.0","seats":4,"wheelchair":1,"equipment":["RAMP"]}"""))
    private fun x(pl: Long) = demand(UUID.randomUUID(), 3, 6, 600, pl, 1500, 2400)
    private fun nBeforeX() = demand(UUID.randomUUID(), 2, 3, 0, 400, 0, 700)    // zwingend vor dem Abholstopp von X
    private fun scalarLong(sql: String, vararg a: Any?): Long = fx.admin().use { ds -> ds.connection.use { c -> c.prepareStatement(sql).use { ps -> a.forEachIndexed { i, v -> ps.setObject(i + 1, v) }; ps.executeQuery().use { it.next(); it.getLong(1) } } } }

    @Test
    fun einfuegung_haelt_bestehende_fenster_ein() {
        val f = flow()
        val wx = f.book(x(pl = 1200), PARAMS)
        val wn = f.book(nBeforeX(), PARAMS)
        // X wurde um 120 s geschoben (720 s), liegt aber weiter im zugesagten Fenster [600, 1200]
        val ride = de.taktovia.bookingdispatch.api.PromiseWindowGuard.rides(api.runs.currentPlan(f.ctx, f.run.runId).stops).getValue(wx.bookingId)
        assertEquals(at(720), ride.pickupAt)
        assertTrue(!ride.pickupAt.isBefore(wx.pickupEarliest) && !ride.pickupAt.isAfter(wx.pickupLatest))
        // Gegenprobe auf den Tabellen: Planzeiten in route_stop gegen booking.promise_snapshot, in jeder Revision, in der X steht
        val outside = scalarLong(
            "SELECT count(*) FROM taktovia.route_stop p JOIN taktovia.capacity_hold h ON h.run_id = p.run_id JOIN taktovia.booking b ON b.hold_id = h.id " +
                "WHERE p.run_id = ? AND p.boardings @> jsonb_build_array(jsonb_build_object('booking_id', h.promise_window->>'booking_id')) AND h.promise_window->>'booking_id' = ? " +
                "AND (p.planned_at < (b.promise_snapshot->>'pickup_earliest')::timestamptz OR p.planned_at > (b.promise_snapshot->>'pickup_latest')::timestamptz)",
            f.run.runId, wx.bookingId.toString(),
        )
        assertEquals(0L, outside)
        val inRevisions = scalarLong("SELECT count(DISTINCT run_revision) FROM taktovia.route_stop WHERE run_id = ? AND boardings @> jsonb_build_array(jsonb_build_object('booking_id', ?::text))", f.run.runId, wx.bookingId.toString())
        assertEquals(2L, inRevisions)   // Revision 2 (X allein) und Revision 3 (mit N)
        assertTrue(api.audit.auditRun(f.ctx, f.run.runId).ok)
        assertEquals(wn.bookingId, wn.bookingId)
    }

    @Test
    fun fensterverletzende_einfuegung_wird_nicht_angeboten_und_beim_hold_und_confirm_abgewiesen() {
        // (1) nicht angeboten: X hat kein Spiel (Abholfenster endet bei 600 s), N würde X auf 720 s schieben
        val f = flow()
        val wx = f.book(x(pl = 600), PARAMS)
        val out = f.find(nBeforeX())
        assertTrue(out is InsertionOutcome.Rejected, "Einfügung nur durch Fensterverletzung möglich: nicht angeboten")
        assertTrue((out as InsertionOutcome.Rejected).grounds.containsKey("COMMITMENT_PICKUP_WINDOW"))

        // (2) beim Hold abgewiesen: ein von Hand gebauter, formal gültiger Plan, der X auf 720 s schiebt
        val dn = nBeforeX()
        val rev = api.runs.run(f.ctx, f.run.runId).revision
        val before = api.runs.currentPlan(f.ctx, f.run.runId)
        val (xPick, xDrop) = before.stops
        val handmade = listOf(
            PlanStop(0, stop(2).stopRevisionId, at(300), listOf(StopMovement(dn.bookingId, dn.vector)), emptyList(), 300, 3000),
            PlanStop(1, stop(3).stopRevisionId, at(660), emptyList(), listOf(StopMovement(dn.bookingId, dn.vector)), 0, 0),
            xPick.copy(seq = 2, plannedAt = at(720)), xDrop.copy(seq = 3, plannedAt = at(1680)),
        )
        val window = de.taktovia.bookingdispatch.api.PromiseWindow(dn.bookingId, dn.pickupEarliest, dn.pickupLatest, dn.arrivalEarliest, dn.arrivalLatest, PARAMS.limits, 300, 60)
        val rejected = api.holds.place(
            f.ctx, de.taktovia.bookingdispatch.api.PlaceHold(
                f.run.runId, rev, f.run.serviceId, de.taktovia.bookingdispatch.api.TimeWindow(at(0), at(14400)), 0, 1, dn.vector, reference = "ref-" + UUID.randomUUID(),
                proposal = de.taktovia.bookingdispatch.api.PlanProposal(handmade, window),
            ),
        )
        assertEquals(HoldOutcome.Rejected(RejectionReason.PROMISE_WINDOW_VIOLATION, null), rejected)
        assertEquals(before, api.runs.currentPlan(f.ctx, f.run.runId), "abgewiesener Hold ändert den Plan nicht")
        assertEquals(wx.bookingId, wx.bookingId)

        // (3) beim Confirm abgewiesen: N ist gehalten, danach reißt eine Planänderung von Hand das Fenster von X
        val g = flow()
        g.book(x(pl = 1200), PARAMS)
        val held = offered(g.find(dn)).first()
        val placed = g.hold(held, dn) as HoldOutcome.Placed
        val plan = api.runs.currentPlan(g.ctx, g.run.runId)
        api.runs.appendRevision(g.ctx, g.run.runId, plan.revision, plan.stops.map { it.copy(plannedAt = it.plannedAt.plusSeconds(1000)) }, RevisionCause.MANUAL_CHANGE)
        val ex = assertThrows(ProblemException::class.java) {
            api.holds.confirm(g.ctx, placed.hold.id, g.run.serviceId, InsertionTestSupport.promiseOf(held.window), de.taktovia.bookingdispatch.api.BookingDemand(1, dn.vector, emptySet()))
        }
        assertEquals(ErrorCode.CAPACITY_UNAVAILABLE, ex.code)
        assertEquals(0L, scalarLong("SELECT count(*) FROM taktovia.booking WHERE hold_id = ?", placed.hold.id))
    }

    @Test
    fun spaetere_parameteraenderung_veraendert_kein_gespeichertes_zusagefenster() {
        val f = flow()
        val wx = f.book(x(pl = 1200), PARAMS)
        val stored = { fx.admin().use { ds -> ds.connection.use { c -> c.prepareStatement("SELECT promise_window::text, (SELECT promise_snapshot::text FROM taktovia.booking b WHERE b.hold_id = h.id) FROM taktovia.capacity_hold h WHERE promise_window->>'booking_id' = ?").use { ps -> ps.setString(1, wx.bookingId.toString()); ps.executeQuery().use { it.next(); it.getString(1) to it.getString(2) } } } } }
        val before = stored()
        // neue Parameter (größeres Abholfenster, mehr Karenz, höhere Grenzen) gelten nur für neue Buchungen
        val wide = PARAMS.copy(limits = de.taktovia.bookingdispatch.api.HardLimits(120, 200, 30, "cfg-neu"))
        val wn = f.book(demand(UUID.randomUUID(), 4, 5, 0, 5000, 0, 9000), wide)
        assertEquals("cfg-neu", wn.limits.configRevisionId)
        assertEquals(before, stored(), "Zusagefenster und Zusage von X bleiben unverändert")
        assertEquals("cfg-1", de.taktovia.bookingdispatch.api.PromiseWindow.parse(before.first).limits.configRevisionId)
        // die Datenbank lässt keine Änderung des Fensters zu (auch nicht durch den Eigentümer)
        val ex = assertThrows(Exception::class.java) { fx.migratorExec(fx.tenantA, "UPDATE taktovia.capacity_hold SET promise_window = jsonb_set(promise_window, '{max_wait_min}', '240') WHERE promise_window->>'booking_id' = ?", wx.bookingId.toString()) }
        assertTrue(generateSequence(ex as Throwable) { it.cause }.any { it.message?.contains("immutable") == true })
    }

    @Test
    fun parallele_einfuegungen_hoechstens_eine_gehalten() {
        val f = flow()
        f.book(x(pl = 800), PARAMS)                         // eine Einfügung vor X schiebt auf 720 s (zulässig), zwei auf 840 s (Fenster gerissen)
        val d1 = nBeforeX(); val d2 = nBeforeX()
        val v1 = offered(f.find(d1)).first(); val v2 = offered(f.find(d2)).first()
        assertEquals(v1.planRevision, v2.planRevision)       // einzeln zulässig, beide auf derselben Revision gerechnet
        val pool = Executors.newFixedThreadPool(2); val go = CountDownLatch(1)
        val results = listOf(v1 to d1, v2 to d2).map { (v, d) -> pool.submit<HoldOutcome> { go.await(); f.hold(v, d) } }
        go.countDown()
        val outcomes = results.map { it.get() }; pool.shutdown()
        assertEquals(1, outcomes.count { it is HoldOutcome.Placed }, "höchstens eine Buchung wird gehalten: $outcomes")
        assertEquals(1, outcomes.count { it == HoldOutcome.Rejected(RejectionReason.REVISION_CONFLICT, null) })
        val loserIndex = outcomes.indexOfFirst { it !is HoldOutcome.Placed }
        val (loserVariant, loserDemand) = listOf(v1 to d1, v2 to d2)[loserIndex]
        // neu geplant auf der aktuellen Revision: das gerissene Fenster macht die Einfügung unzulässig
        val replan = f.find(loserDemand)
        assertTrue(replan is InsertionOutcome.Rejected && replan.grounds.containsKey("COMMITMENT_PICKUP_WINDOW"), "Neuplanung: $replan")
        // mit der aktuellen Revision, aber dem veralteten Plan der Variante: die gehaltene Buchung fehlt, der Hold wird wegen der Zusagen abgewiesen
        val current = api.runs.run(f.ctx, f.run.runId).revision
        assertEquals(HoldOutcome.Rejected(RejectionReason.PROMISE_WINDOW_VIOLATION, null), f.hold(loserVariant, loserDemand, expectedRevision = current))
        // kein zugesagtes Fenster verletzt, in keiner Revision; genau eine der beiden Buchungen hat ein Zusagefenster
        assertTrue(api.audit.auditRun(f.ctx, f.run.runId).ok)
        assertEquals(1L, scalarLong("SELECT count(*) FROM taktovia.capacity_hold WHERE run_id = ? AND promise_window->>'booking_id' IN (?, ?)", f.run.runId, d1.bookingId.toString(), d2.bookingId.toString()))
    }
}

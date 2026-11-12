package de.taktovia.bookingdispatch

import de.taktovia.bookingdispatch.api.AttemptOutcome
import de.taktovia.bookingdispatch.api.CandidateGate
import de.taktovia.bookingdispatch.api.CandidateGateResult
import de.taktovia.bookingdispatch.api.CandidateSearchRequest
import de.taktovia.bookingdispatch.api.RejectionCode
import de.taktovia.bookingdispatch.api.RejectionMetric
import de.taktovia.bookingdispatch.api.RideOfferResult
import de.taktovia.bookingdispatch.api.RidePlace
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.servicegeo.api.ReviseServiceArea
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * WP-M2-12 / LH-6.5-OPT-001: Zulässigkeit nach Bedienzeit und Gebiet **vor** der Fahrzeugsuche, gegen die echte Datenbank unter der Runtime-Rolle. Jetzt: Montag, 2. November 2026,
 * 08:00 Ortszeit; Dienst täglich 06:00-22:00; Gebiet Länge 13.60-13.90, Breite 50.95-51.25; Haltepunkte Markt (13.74/51.05) und Bahnhof (13.80/51.10), `stop_walk_max_m` 400.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EligibilityCheckTest {
    private lateinit var fx: RideOfferFixture
    private lateinit var w: RideOfferFixture.World

    /** Zähler der Kandidatensuche (der „Solver“): bei einer abgelehnten Anfrage muss er bei 0 bleiben. */
    private class CountingGate(val result: CandidateGateResult = CandidateGateResult.Reached) : CandidateGate {
        val calls = AtomicInteger(); var last: CandidateSearchRequest? = null
        override fun check(ctx: de.taktovia.platform.context.RequestContext, request: CandidateSearchRequest): CandidateGateResult { calls.incrementAndGet(); last = request; return result }
    }

    @BeforeAll fun up() { fx = RideOfferFixture(); w = fx.world() }
    @AfterAll fun down() = fx.close()

    private fun attemptRow(tenant: de.taktovia.platform.tenant.TenantId, id: java.util.UUID): Triple<String?, List<String>, String> = fx.tx.run(tenant) { j ->
        j.queryForObject("SELECT primary_rejection, secondary_rejections::text, outcome FROM taktovia.travel_attempt WHERE id = ?",
            { rs, _ -> Triple(rs.getString(1), rs.getString(2).trim('{', '}').split(',').filter { it.isNotEmpty() }, rs.getString(3)) }, id)!!
    }

    private fun noAssignment(world: RideOfferFixture.World) {
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.capacity_hold WHERE tenant_id = ?", world.tenant.value), "kein Hold")
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.vehicle_run WHERE tenant_id = ?", world.tenant.value), "keine Planrevision")
    }

    @Test
    fun zulaessige_anfrage_erreicht_kandidatensuche() {
        val gate = CountingGate(); val api = fx.requests(gate = gate); val ctx = fx.passenger(w.tenant)
        val r = api.request(ctx, fx.command(w, "2026-11-02T10:00:00+01:00"))
        assertEquals(AttemptOutcome.OFFERED, r.outcome); assertEquals(1, r.offers.size); assertNull(r.rejection)
        assertEquals(1, gate.calls.get(), "genau ein Aufruf der Kandidatensuche")
        gate.last!!.let {
            assertEquals(w.serviceId, it.serviceId); assertEquals(2, it.demand.seats); assertEquals(w.stopA, it.pickupStopId, "nächster Haltepunkt am Start")
            assertEquals(Instant.parse("2026-11-02T09:00:00Z"), it.pickupWindow.earliestAt)
        }
        val row = attemptRow(w.tenant, r.attemptId)
        assertNull(row.first); assertEquals("OFFERED", row.third)
        // grobe Ortskennung statt Koordinate
        val coarse = fx.tx.run(w.tenant) { j -> j.queryForObject("SELECT coarse_origin || '|' || coarse_destination FROM taktovia.travel_attempt WHERE id = ?", String::class.java, r.attemptId)!! }
        assertEquals("area:${w.areaKey}|area:${w.areaKey}", coarse)
    }

    @Test
    fun ausserhalb_betriebszeit_ohne_fahrzeugzuweisung_abgelehnt() {
        val gate = CountingGate(); val api = fx.requests(gate = gate); val ctx = fx.passenger(w.tenant)
        val r = api.request(ctx, fx.command(w, "2026-11-02T23:00:00+01:00"))
        assertEquals(AttemptOutcome.REJECTED, r.outcome); assertTrue(r.offers.isEmpty()); assertEquals(RejectionCode.OUTSIDE_HOURS, r.rejection!!.primary)
        assertEquals(0, gate.calls.get(), "kein Fahrzeugkandidat ermittelt")
        // Ankunft nach Betriebsschluss genügt ebenfalls (Fahrt endet außerhalb der Betriebszeit)
        val late = api.request(ctx, fx.command(w, "2026-11-02T21:55:00+01:00"))
        assertEquals(RejectionCode.OUTSIDE_HOURS, late.rejection!!.primary); assertEquals(0, gate.calls.get())
        noAssignment(w)
    }

    @Test
    fun ausserhalb_gebiet_ohne_fahrzeugzuweisung_abgelehnt() {
        val gate = CountingGate(); val api = fx.requests(gate = gate); val ctx = fx.passenger(w.tenant)
        val outsideStart = api.request(ctx, fx.command(w, "2026-11-02T10:00:00+01:00", origin = RidePlace(14.50, 51.05)))
        assertEquals(RejectionCode.OUTSIDE_AREA, outsideStart.rejection!!.primary)
        val outsideTarget = api.request(ctx, fx.command(w, "2026-11-02T10:00:00+01:00", destination = RidePlace(13.80, 52.50)))
        assertEquals(RejectionCode.OUTSIDE_AREA, outsideTarget.rejection!!.primary)
        assertEquals(0, gate.calls.get()); noAssignment(w)
        assertEquals("outside", fx.tx.run(w.tenant) { j -> j.queryForObject("SELECT coarse_origin FROM taktovia.travel_attempt WHERE id = ?", String::class.java, outsideStart.attemptId) })
    }

    @Test
    fun gebiet_zum_fahrtzeitpunkt_nicht_wirksam_abgelehnt() {
        val closed = fx.world(areaTo = Instant.parse("2026-11-10T00:00:00Z"))
        val gate = CountingGate(); val api = fx.requests(gate = gate); val ctx = fx.passenger(closed.tenant)
        assertEquals(AttemptOutcome.OFFERED, api.request(ctx, fx.command(closed, "2026-11-09T10:00:00+01:00")).outcome, "vor dem Ende wirksam")
        val calls = gate.calls.get()
        val r = api.request(ctx, fx.command(closed, "2026-11-12T10:00:00+01:00"))
        assertEquals(RejectionCode.OUTSIDE_AREA, r.rejection!!.primary, "Gebiet zum Fahrtzeitpunkt nicht mehr wirksam")
        assertEquals(calls, gate.calls.get()); noAssignment(closed)
    }

    @Test
    fun kein_haltepunkt_in_stop_walk_max_m_abgelehnt() {
        val gate = CountingGate(); val api = fx.requests(gate = gate); val ctx = fx.passenger(w.tenant)
        // im Gebiet, aber weit weg von jedem Haltepunkt
        val r = api.request(ctx, fx.command(w, "2026-11-02T10:00:00+01:00", origin = RidePlace(13.65, 51.20)))
        assertEquals(RejectionCode.NO_STOP_IN_RANGE, r.rejection!!.primary); assertEquals(0, gate.calls.get())
        // knapp innerhalb 400 m (ca. 0,003 Grad Breite = 333 m) wird ein Haltepunkt gefunden
        val near = api.request(ctx, fx.command(w, "2026-11-02T10:00:00+01:00", origin = RidePlace(13.74, 51.053)))
        assertEquals(AttemptOutcome.OFFERED, near.outcome)
        // knapp außerhalb (0,0045 Grad = 500 m)
        val far = api.request(ctx, fx.command(w, "2026-11-02T10:00:00+01:00", origin = RidePlace(13.74, 51.0545)))
        assertEquals(RejectionCode.NO_STOP_IN_RANGE, far.rejection!!.primary)
    }

    @Test
    fun rollstuhl_ungeeigneter_haltepunkt_abgelehnt() {
        val unverified = fx.world(wheelchair = "UNVERIFIED")
        val gate = CountingGate(); val api = fx.requests(gate = gate); val ctx = fx.passenger(unverified.tenant)
        val chair = api.request(ctx, fx.command(unverified, "2026-11-02T10:00:00+01:00", wheelchair = 1))
        assertEquals(RejectionCode.NO_ACCESSIBLE_STOP, chair.rejection!!.primary, "ungeprüfter Haltepunkt wird nie für Rollstuhlnutzung angeboten")
        assertEquals(0, gate.calls.get())
        assertEquals(RejectionMetric.EQUIPMENT, chair.rejection!!.primary.metric)
        // ohne Rollstuhlbedarf ist derselbe Haltepunkt zulässig
        assertEquals(AttemptOutcome.OFFERED, api.request(ctx, fx.command(unverified, "2026-11-02T10:00:00+01:00")).outcome)
        // ausdrücklich ungeeignet ("NO") ebenso abgelehnt; geeigneter Haltepunkt ("YES") wird angeboten
        val no = fx.world(wheelchair = "NO")
        assertEquals(RejectionCode.NO_ACCESSIBLE_STOP, api.request(fx.passenger(no.tenant), fx.command(no, "2026-11-02T10:00:00+01:00", wheelchair = 1)).rejection!!.primary)
        val yes = fx.world(wheelchair = "YES")
        val ok = api.request(fx.passenger(yes.tenant), fx.command(yes, "2026-11-02T10:00:00+01:00", wheelchair = 1))
        assertEquals(AttemptOutcome.OFFERED, ok.outcome); assertEquals(2, gate.calls.get(), "nur die beiden zulässigen Anfragen erreichen die Suche")
    }

    @Test
    fun ablehnung_schreibt_travel_attempt_primary_rejection() {
        val gate = CountingGate(); val api = fx.requests(gate = gate); val ctx = fx.passenger(w.tenant)
        // Betriebszeit und Gebiet verletzt: Primärgrund nach Priorität (Betriebszeit), der Rest als Sekundärgrund
        val r = api.request(ctx, fx.command(w, "2026-11-02T23:00:00+01:00", origin = RidePlace(14.50, 51.05)))
        assertEquals(RejectionCode.OUTSIDE_HOURS, r.rejection!!.primary)
        assertTrue(RejectionCode.OUTSIDE_AREA in r.rejection!!.secondary)
        val row = attemptRow(w.tenant, r.attemptId)
        assertEquals("OUTSIDE_HOURS", row.first); assertEquals("REJECTED", row.third)
        assertTrue("OUTSIDE_AREA" in row.second, "Nebengründe in secondary_rejections: ${row.second}"); assertTrue("OUTSIDE_HOURS" !in row.second)
    }

    @Test
    fun keine_fahrzeugzuweisung_solver_zaehler_null_kein_hold_keine_planrevision() {
        val gate = CountingGate(); val travelCalls = AtomicInteger()
        val api = fx.requests(travel = { _, _ -> travelCalls.incrementAndGet(); 600 }, gate = gate)
        val ctx = fx.passenger(w.tenant)
        val fresh = fx.world()
        val cmds = listOf(
            fx.command(fresh, "2026-11-02T23:30:00+01:00"), fx.command(fresh, "2026-11-02T10:00:00+01:00", origin = RidePlace(14.5, 51.0)), fx.command(fresh, "2026-11-02T08:20:00+01:00"),
        )
        for (c in cmds) assertEquals(AttemptOutcome.REJECTED, api.request(fx.passenger(fresh.tenant), c).outcome)
        assertEquals(0, gate.calls.get(), "Solver-/Kandidatensuchzähler bleibt 0")
        noAssignment(fresh)
        assertEquals(3, fx.count("SELECT count(*) FROM taktovia.travel_attempt WHERE tenant_id = ? AND outcome = 'REJECTED'", fresh.tenant.value))
        assertNotNull(ctx)
    }

    @Test
    fun betriebsschluss_und_frist_getrennt_zugeordnet() {
        val api = fx.requests(); val ctx = fx.passenger(w.tenant)
        val closed = api.request(ctx, fx.command(w, "2026-11-02T22:30:00+01:00")).rejection!!.primary
        val frist = api.request(ctx, fx.command(w, "2026-11-02T08:10:00+01:00")).rejection!!.primary
        assertEquals(RejectionCode.OUTSIDE_HOURS, closed); assertEquals(RejectionCode.LEAD_TIME, frist)
        assertTrue(closed != frist, "Betrieb geschlossen und Frist sind getrennte Gründe")
        assertEquals(RejectionMetric.TIME_WINDOW, closed.metric); assertEquals(RejectionMetric.TIME_WINDOW, frist.metric)   // beide zählen in Zeitfensterablehnungen
        assertEquals(RejectionMetric.AREA, RejectionCode.OUTSIDE_AREA.metric)
    }

    @Test
    fun gebiet_fremder_mandant_wird_nicht_verwendet() {
        val a = fx.world(); val b = fx.world()
        val api = fx.requests(); val cb = fx.passenger(b.tenant)
        // Mandant B hat ein Gebiet nur weit entfernt: Anfrage in A's Gebiet ist für B außerhalb, obwohl A dort ein Gebiet hat
        fx.geo.areas.revise(b.admin, b.areaKey, 1, ReviseServiceArea(RideOfferFixture.square(10.0, 48.0, 0.3), Instant.parse("2026-02-01T00:00:00Z"), null))
        val r = api.request(cb, fx.command(b, "2026-11-02T10:00:00+01:00"))
        assertEquals(RejectionCode.OUTSIDE_AREA, r.rejection!!.primary)
        assertEquals(AttemptOutcome.OFFERED, api.request(fx.passenger(a.tenant), fx.command(a, "2026-11-02T10:00:00+01:00")).outcome)
        // der Dienst eines anderen Mandanten ist unbekannt (404), nicht „außerhalb“
        val foreign = assertThrows<ProblemException> { api.request(cb, fx.command(a, "2026-11-02T10:00:00+01:00")) }
        assertEquals(ErrorCode.NOT_FOUND, foreign.code)
    }

    @Test
    fun gebietsrevisionswechsel_parallel_verwendet_eine_revision() {
        val p = fx.world(); val api = fx.requests(); val ctx = fx.passenger(p.tenant)
        val pool = Executors.newFixedThreadPool(8); val go = CountDownLatch(1)
        try {
            val readers = (1..40).map { pool.submit<RideOfferResult> { go.await(); api.request(ctx, fx.command(p, "2026-11-02T10:00:00+01:00")) } }
            val writer = pool.submit {
                go.await()
                // neue Revision ab 01.11. (vor dem Wunsch): Gebiet wandert weg, alter Zustand und neuer Zustand sind je für sich konsistent
                fx.geo.areas.revise(p.admin, p.areaKey, 1, ReviseServiceArea(RideOfferFixture.square(10.0, 48.0, 0.3), Instant.parse("2026-11-01T00:00:00Z"), null))
            }
            go.countDown(); writer.get(60, TimeUnit.SECONDS)
            val results = readers.map { it.get(60, TimeUnit.SECONDS) }
            for (x in results) when (x.outcome) {
                AttemptOutcome.OFFERED -> assertTrue(x.rejection == null)                                       // alte Revision, vollständig
                AttemptOutcome.REJECTED -> {                                                                   // neue Revision, vollständig: Start und Ziel außerhalb, nur Gebietsgründe
                    assertEquals(RejectionCode.OUTSIDE_AREA, x.rejection!!.primary)
                    assertTrue(x.rejection!!.secondary.all { it == RejectionCode.NO_STOP_IN_RANGE || it == RejectionCode.OUTSIDE_AREA }, "keine Mischzustände: ${x.rejection}")
                }
                AttemptOutcome.TECHNICAL_ERROR -> throw AssertionError("kein technischer Fehler erwartet")
            }
        } finally { pool.shutdownNow() }
        // nach dem Wechsel gilt durchgehend die neue Revision
        repeat(5) { assertEquals(RejectionCode.OUTSIDE_AREA, api.request(ctx, fx.command(p, "2026-11-02T10:00:00+01:00")).rejection!!.primary) }
    }
}

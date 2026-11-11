package de.taktovia.bookingdispatch

import de.taktovia.bookingdispatch.InsertionTestSupport.PARAMS
import de.taktovia.bookingdispatch.InsertionTestSupport.at
import de.taktovia.bookingdispatch.InsertionTestSupport.demand
import de.taktovia.bookingdispatch.InsertionTestSupport.offered
import de.taktovia.bookingdispatch.api.BookingdispatchApi
import de.taktovia.bookingdispatch.api.HardLimits
import de.taktovia.bookingdispatch.api.InsertionOutcome
import de.taktovia.bookingdispatch.api.InsertionService
import de.taktovia.bookingdispatch.api.InsertionWorker
import de.taktovia.bookingdispatch.api.PlannedRide
import de.taktovia.bookingdispatch.api.PromiseWindow
import de.taktovia.bookingdispatch.api.PromiseWindowGuard
import de.taktovia.bookingdispatch.api.WindowViolationCode
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.UUID

/**
 * WP-M2-18 / LH-6.5-OPT-005: harte Grenzen für Wartezeit, Umweg und Verspätung. Reine Grenzfälle der Prüfregel, Varianten des echten Python-Workers an den Grenzen (knapp innerhalb
 * angeboten, knapp außerhalb nicht), Verwerfen eines verletzenden Worker-Ergebnisses im Kern und der automatische Prüfer über alle bestätigten Buchungen und alle Planrevisionen.
 * Messdefinitionen sind offen (OP-17); die Tests legen die in `PromiseWindow` dokumentierte Planungsannahme zugrunde.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HardLimitsValidatorTest {
    private lateinit var fx: BookingFixture
    private lateinit var api: BookingdispatchApi
    private lateinit var worker: InsertionTestSupport.WorkerProcess
    private val json = JsonMapper.builder().build()

    @BeforeAll fun up() {
        worker = InsertionTestSupport.WorkerProcess()
        fx = BookingFixture(); api = BookingdispatchApi.create(fx.ctxTx)
    }
    @AfterAll fun down() { if (::worker.isInitialized) worker.close(); if (::fx.isInitialized) fx.close() }

    private fun flow(svc: InsertionService = InsertionTestSupport.service(worker)) = RideFlow(fx, api, svc, fx.createRun("""{"schema_version":"1.0","seats":4,"wheelchair":1,"equipment":["RAMP"]}"""))
    private fun limits(wait: Int = 60, detour: Int = 100, grace: Int = 0) = PARAMS.copy(limits = HardLimits(wait, detour, grace, "cfg-test"))

    /** Szenario: X (H3 nach H6) ist bestätigt; N fährt von H2 nach H6, Abholung 300 s, Ausstieg 1620 s, 1260 s an Bord bei 1200 s Direktfahrt (5 %). */
    private fun withX(f: RideFlow) = f.book(demand(UUID.randomUUID(), 3, 6, 600, 1200, 1500, 2400), limits())
    private fun n(al: Long = 1620) = demand(UUID.randomUUID(), 2, 6, 0, 400, 0, al)

    // ---- reine Prüfregel (Grenzwerte) ----

    private fun window(maxWait: Int = 5, detour: Int = 10, grace: Int = 1) = PromiseWindow(
        UUID.randomUUID(), at(0), at(900), at(1000), at(2000), HardLimits(maxWait, detour, grace), directSec = 1000, pickupDwellSec = 60,
    )

    @Test
    fun grenzwerte_der_pruefregel_knapp_innerhalb_und_ausserhalb() {
        val w = window()
        fun codes(pickup: Long, drop: Long) = PromiseWindowGuard.check(w, PlannedRide(w.bookingId, at(pickup), at(drop)))
        // Wartezeit = Grenze (300 s) ok, +1 s nicht; Abholfenster (bis 900 s) bleibt unberührt
        assertEquals(emptyList<WindowViolationCode>(), codes(300, 300 + 60 + 1000))
        assertEquals(listOf(WindowViolationCode.MAX_WAIT_EXCEEDED), codes(301, 301 + 60 + 1000))
        // Umweg = 10 % (1100 s an Bord bei 1000 s direkt) ok, 1101 s nicht
        assertEquals(emptyList<WindowViolationCode>(), codes(100, 100 + 60 + 1100))
        assertEquals(listOf(WindowViolationCode.MAX_DETOUR_EXCEEDED), codes(100, 100 + 60 + 1101))
        // Ankunft = Zusage plus Karenz (2000 + 60 s) ok, +1 s nicht
        assertEquals(emptyList<WindowViolationCode>(), codes(100, 2060).filter { it != WindowViolationCode.MAX_DETOUR_EXCEEDED })
        assertTrue(WindowViolationCode.ARRIVAL_AFTER_GRACE in codes(100, 2061))
    }

    @Test
    fun werte_ausserhalb_des_katalogs_sind_auch_hier_unzulaessig() {
        assertThrows(IllegalArgumentException::class.java) { HardLimits(241, 100, 0) }
        assertThrows(IllegalArgumentException::class.java) { HardLimits(60, 201, 0) }
        assertEquals(HardLimits(240, 200, 240).maxWaitMin, 240)
    }

    // ---- Varianten des Workers an den Grenzen ----

    @Test
    fun variante_ueber_grenze_wird_nicht_angeboten() {
        val f = flow(); withX(f)
        // Kontrolle: bei großzügigen Grenzen wird angeboten; Umweg von N 5 % (1260 s an Bord, 1200 s direkt)
        assertTrue(offered(f.find(n(), limits(wait = 60, detour = 5))).isNotEmpty(), "Umweg = Grenze (5 %) wird angeboten")
        val detour = f.find(n(), limits(detour = 4))
        assertTrue(detour is InsertionOutcome.Rejected, "Umweg über der Grenze (4 %) wird nicht angeboten")
        assertTrue(((detour as InsertionOutcome.Rejected).grounds.keys.any { it.contains("MAX_DETOUR") }))
        // Wartezeit: Abholung nach 300 s, Grenze 5 min knapp innerhalb, 4 min knapp außerhalb
        assertTrue(offered(f.find(n(), limits(wait = 5))).isNotEmpty())
        assertTrue((f.find(n(), limits(wait = 4)) as InsertionOutcome.Rejected).grounds.containsKey("MAX_WAIT"))
        // Verspätung: Ankunft 1620 s; Zusage 1619 s ohne Karenz nicht, mit Karenz von einer Minute ja
        assertTrue(f.find(n(al = 1619), limits(grace = 0)) is InsertionOutcome.Rejected)
        assertTrue(offered(f.find(n(al = 1619), limits(grace = 1))).isNotEmpty())
        // jede angebotene Variante nennt die Konfigurationsrevision der Grenzen im Zusagefenster
        assertEquals("cfg-test", offered(f.find(n(), limits())).first().window.limits.configRevisionId)
    }

    @Test
    fun verletzendes_ergebnis_des_workers_wird_im_kern_verworfen() {
        val real = worker.worker
        // ein fehlerhafter Worker verschiebt den letzten Stopp jeder Variante um zwei Stunden (Ankunft weit nach dem Zusagefenster)
        val faulty = InsertionWorker { body, budget: Duration ->
            val reply = json.readTree(real.insert(body, budget)) as tools.jackson.databind.node.ObjectNode
            reply.get("variants").forEach { v ->
                val last = v.get("stops").let { it.get(it.size() - 1) } as tools.jackson.databind.node.ObjectNode
                last.put("planned_at", last.get("planned_at").asLong() + 7200)
            }
            json.writeValueAsString(reply)
        }
        val f = flow(InsertionService(InsertionTestSupport.CorridorRouting(), faulty))
        val out = f.find(demand(UUID.randomUUID(), 3, 6, 600, 1200, 1500, 2400), limits())
        assertTrue(out is InsertionOutcome.Rejected, "keine Variante darf ungeprüft angeboten werden")
        assertEquals("NO_VALID_VARIANT", (out as InsertionOutcome.Rejected).reason)
    }

    // ---- automatischer Prüfer über alle bestätigten Fahrten ----

    @Test
    fun bestaetigte_fahrten_halten_alle_harten_grenzen() {
        val f = flow()
        val wx = withX(f)
        val wn = f.book(n(), limits(wait = 5, detour = 5))               // vor und nach X, an beiden Grenzen
        val wz = f.book(demand(UUID.randomUUID(), 4, 5, 0, 5000, 0, 9000), limits())
        val report = api.audit.auditRun(f.ctx, f.run.runId)
        assertTrue(report.ok, "Verstöße: ${report.findings}")
        assertEquals(3, report.bookings)
        assertTrue(report.revisions >= 4 && report.checkedRides >= 6, "alle Planrevisionen mit der Buchung geprüft: $report")
        // unabhängige Gegenprobe direkt auf den Tabellen: Zeiten aus route_stop, Grenzen aus der gespeicherten Zusage (promise_snapshot) und dem Zusagefenster des Holds
        fx.admin().use { ds -> ds.connection.use { c ->
            c.prepareStatement(
                "SELECT h.promise_window->>'booking_id', p.run_revision, p.planned_at, d.planned_at, (h.promise_window->>'max_wait_min')::int, (h.promise_window->>'max_detour_pct')::int, " +
                    "(h.promise_window->>'direct_sec')::int, (h.promise_window->>'pickup_dwell_sec')::int, (h.promise_window->>'arrival_grace_min')::int, " +
                    "b.promise_snapshot->>'pickup_earliest', b.promise_snapshot->>'pickup_latest', b.promise_snapshot->>'arrival_latest' " +
                    "FROM taktovia.capacity_hold h JOIN taktovia.booking b ON b.hold_id = h.id " +
                    "JOIN taktovia.route_stop p ON p.run_id = h.run_id AND p.boardings @> jsonb_build_array(jsonb_build_object('booking_id', h.promise_window->>'booking_id')) " +
                    "JOIN taktovia.route_stop d ON d.run_id = h.run_id AND d.run_revision = p.run_revision AND d.alightings @> jsonb_build_array(jsonb_build_object('booking_id', h.promise_window->>'booking_id')) " +
                    "WHERE h.run_id = ? AND h.state = 'CONSUMED'",
            ).use { ps ->
                ps.setObject(1, f.run.runId)
                ps.executeQuery().use { rs ->
                    var rows = 0
                    while (rs.next()) {
                        rows++
                        val pick = rs.getTimestamp(3).toInstant(); val drop = rs.getTimestamp(4).toInstant()
                        val pe = java.time.OffsetDateTime.parse(rs.getString(10)).toInstant(); val pl = java.time.OffsetDateTime.parse(rs.getString(11)).toInstant()
                        val al = java.time.OffsetDateTime.parse(rs.getString(12)).toInstant()
                        val wait = Duration.between(pe, pick).seconds; val ride = Duration.between(pick, drop).seconds - rs.getInt(8); val direct = rs.getInt(7)
                        assertTrue(wait <= rs.getInt(5) * 60L, "Wartezeit ${rs.getString(1)} rev ${rs.getLong(2)}")
                        assertTrue((ride - direct) * 100 <= rs.getInt(6).toLong() * direct, "Umweg ${rs.getString(1)} rev ${rs.getLong(2)}")
                        assertTrue(!drop.isAfter(al.plusSeconds(rs.getInt(9) * 60L)), "Verspätung ${rs.getString(1)} rev ${rs.getLong(2)}")
                        assertTrue(!pick.isBefore(pe) && !pick.isAfter(pl), "Abholfenster ${rs.getString(1)}")
                    }
                    assertTrue(rows >= 6, "mindestens 3 Buchungen in mehreren Revisionen: $rows")
                }
            }
        } }
        assertEquals(setOf(wx.bookingId, wn.bookingId, wz.bookingId).size, 3)
    }

    @Test
    fun parallele_bestaetigungen_heben_umweg_der_ersten_buchung_nicht_ueber_die_grenze() {
        val f = flow()
        f.book(demand(UUID.randomUUID(), 3, 6, 600, 1200, 1500, 2400), limits(detour = 14))   // X: 900 s direkt, höchstens 14 % Umweg (126 s)
        val d1 = demand(UUID.randomUUID(), 4, 5, 0, 9000, 0, 20000); val d2 = demand(UUID.randomUUID(), 4, 5, 0, 9000, 0, 20000)
        val v1 = offered(f.find(d1)).first(); val v2 = offered(f.find(d2)).first()
        // einzeln liegt jede Einfügung zwischen den Stopps von X (zwei Bedienzeiten = 120 s, 13,3 %), beide zusammen hoben X auf 26,7 %
        assertTrue(v1.posPickup in 1..1 && v2.posPickup in 1..1 && v1.planRevision == v2.planRevision)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2); val go = java.util.concurrent.CountDownLatch(1)
        val out = listOf(v1 to d1, v2 to d2).map { (v, d) -> pool.submit<de.taktovia.bookingdispatch.api.HoldOutcome> { go.await(); f.hold(v, d) } }
        go.countDown(); val results = out.map { it.get() }; pool.shutdown()
        assertEquals(1, results.count { it is de.taktovia.bookingdispatch.api.HoldOutcome.Placed }, "höchstens eine Buchung wird gehalten: $results")
        // die unterlegene Anfrage wird neu geplant: auf der aktuellen Revision liegt keine zweite Einfügung mehr zwischen den Stopps von X
        val (lv, ld) = listOf(v1 to d1, v2 to d2)[results.indexOfFirst { it !is de.taktovia.bookingdispatch.api.HoldOutcome.Placed }]
        val replanned = offered(f.find(ld))
        assertTrue(replanned.isNotEmpty() && replanned.all { it.posPickup != 1 && it.planRevision > lv.planRevision })
        f.book(ld)
        val report = api.audit.auditRun(f.ctx, f.run.runId)
        assertTrue(report.ok, "Verstöße: ${report.findings}")
        assertTrue(report.findings.none { it.code == WindowViolationCode.MAX_DETOUR_EXCEEDED })
    }
}

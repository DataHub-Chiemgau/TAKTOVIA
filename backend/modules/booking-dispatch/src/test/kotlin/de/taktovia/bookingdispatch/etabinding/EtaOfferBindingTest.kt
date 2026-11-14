package de.taktovia.bookingdispatch.etabinding

import de.taktovia.booking.ConfirmFixture
import de.taktovia.bookingdispatch.EtaTestFixture
import de.taktovia.bookingdispatch.RideOfferFixture
import de.taktovia.bookingdispatch.StubRoads
import de.taktovia.bookingdispatch.api.CandidateGate
import de.taktovia.bookingdispatch.api.DirectTravelTime
import de.taktovia.bookingdispatch.api.EtaApi
import de.taktovia.bookingdispatch.api.EtaFallbackReason
import de.taktovia.bookingdispatch.api.EtaRoadSegment
import de.taktovia.bookingdispatch.api.EtaStopKeys
import de.taktovia.bookingdispatch.api.RideOfferProposal
import de.taktovia.bookingdispatch.api.RideOfferRequests
import de.taktovia.bookingdispatch.api.RideOfferView
import de.taktovia.platform.context.RequestContext
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WP-M4-66 (Kriterien 1, 2, 5 und konkurrierender Test): die Angebotsbildung der Disposition bildet das Zeitfenster mit dem ETA-Grundmodell. Am **echten Angebot** (Datenbank, Runtime-Rolle, Tarif,
 * Preisstand): Haltezeit und Sicherheitsreserve stecken im Fenster, kein Fenster verlangt eine Geschwindigkeitsüberschreitung, dieselbe Strecke ergibt unabhängig vom Fahrer dieselbe Grundzeit, ohne
 * Prognosemöglichkeit weist das Angebot den Rückfallgrund aus, und ein Konfigurationswechsel während der Angebotsbildung ergibt nie ein Angebot mit zwei Versionen.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EtaOfferBindingTest {
    private lateinit var cf: ConfirmFixture
    private lateinit var efx: EtaTestFixture

    @BeforeAll fun up() { cf = ConfirmFixture(); efx = EtaTestFixture() }
    @AfterAll fun down() { efx.close(); cf.close() }

    private val worldParams = mapOf("min_booking_lead_min" to 30, "max_booking_horizon_days" to 14, "pickup_window_min" to 15, "arrival_grace_min" to 5, "stop_walk_max_m" to 400)

    /** Strecke Markt (H1) nach Bahnhof (H2): 1000 m bei 50 km/h und 2000 m bei 30 km/h (Mindestfahrzeit 312 s), Routingzeit 360 s. */
    private fun roads(abTime: Int = 360, segments: List<EtaRoadSegment>? = StubRoads.AB) = StubRoads("v1", mapOf("v1" to StubRoads.table(abTime, 90, segments, StubRoads.BC)))

    private inner class Scene(val roads: StubRoads = roads()) {
        val w: RideOfferFixture.World = cf.world().also { efx.approveModel(it.tenant, 1) }
        val keys = EtaStopKeys { _, rev -> if (cf.stringOf("SELECT stop_id::text FROM taktovia.stop_revision WHERE id = ?", rev) == w.stopA.toString()) "H1" else "H2" }
        val eta: EtaApi = EtaApi.create(cf.fx.base.ctxTx, cf.fx.base.config.effective, roads, cf.fx.clock)
        val requests: RideOfferRequests = RideOfferRequests.create(cf.fx.base.ctxTx, cf.fx.base.outbox, cf.fx.base.geo, cf.fx.base.config.effective, cf.fx.clock, DirectTravelTime { _, _ -> 600 },
            CandidateGate.PASS_THROUGH, quotes = cf.fx.quotes, etaSource = eta.offerTime(keys))
        val passenger: RequestContext get() = cf.passenger(w).ctx

        fun offer(passengerCtx: RequestContext = passenger): Pair<RideOfferProposal, RideOfferView> {
            val r = requests.request(passengerCtx, cf.fx.base.command(w, "2026-11-02T10:00:00+01:00", passengers = 1))
            val p = r.offers.single()
            return p to cf.fx.offers.get(passengerCtx, p.offer!!.id)
        }
    }

    @Test
    fun angebotsfenster_aus_dem_grundmodell_mit_haltezeit_und_sicherheitsreserve_und_einer_version() {
        val s = Scene()
        val (p, view) = s.offer()
        // Planungswerte des Modells: Haltezeit 30 s, Reserve p85 = 15 % der Referenzzeit (360 s) = 54 s; Referenzzeit 360 s (rechtmäßige Mindestzeit 312 s)
        assertEquals(30 + 360, p.directSeconds, "Grundzeit = Haltezeit plus Referenzfahrzeit")
        val start = Instant.parse("2026-11-02T09:00:00Z")
        assertEquals(start, p.pickupWindow.earliestAt)
        assertEquals(start.plusSeconds(390), p.arrivalWindow.earliestAt, "früheste Ankunft: Haltezeit plus Referenzzeit, nie schneller als erlaubt")
        assertEquals(start.plus(Duration.ofMinutes(15)).plusSeconds(390 + 54), p.arrivalWindow.latestAt, "spätester Rand trägt zusätzlich die Sicherheitsreserve")
        // eine Modell-/Konfigurationsversion je Angebot, am gespeicherten Angebot gelesen
        assertEquals(1, p.eta!!.modelVersion); assertNotNull(p.eta!!.configVersion); assertEquals(54, p.eta!!.bufferSeconds); assertNull(p.eta!!.fallbackReason)
        assertEquals(p.eta, view.eta); assertEquals(p.arrivalWindow, view.arrivalWindow); assertEquals(390, view.directSeconds)
        // Prüfroutine am ausgegebenen Fenster: frühestes Ankunftsfenster nie unter Haltezeit plus Mindestfahrzeit aus Länge und zulässiger Geschwindigkeit
        val legalMin = 30 + (1000 * 36 / 500 + 2000 * 36 / 300)   // 72 s + 240 s
        assertTrue(Duration.between(p.pickupWindow.earliestAt, p.arrivalWindow.earliestAt).seconds >= legalMin, "kein Fenster verlangt eine Geschwindigkeitsüberschreitung")
        assertTrue(Duration.between(p.pickupWindow.latestAt, p.arrivalWindow.latestAt).seconds >= legalMin)
    }

    @Test
    fun zu_schnelle_eingabewerte_verlaengern_auf_die_mindestfahrzeit_nie_verengen() {
        val s = Scene(roads(abTime = 100))   // Routing meldet 100 s, die Streckenlimits erlauben frühestens 312 s
        val (p, _) = s.offer()
        assertEquals(30 + 312, p.directSeconds, "Grundzeit nie unter Haltezeit plus rechtmäßiger Mindestfahrzeit")
        assertTrue(Duration.between(p.pickupWindow.earliestAt, p.arrivalWindow.earliestAt).seconds >= 342)
    }

    @Test
    fun ohne_geschwindigkeitsattribute_oder_modell_gilt_die_planungsannahme_und_der_grund_wird_ausgewiesen() {
        // Straßendaten ohne Geschwindigkeitsattribute (heute der Fall für den Routing-Worker): fail-closed für die Prognose, das Angebot trägt den Grund
        val s = Scene(roads(segments = null))
        val (p, view) = s.offer()
        assertEquals(600, p.directSeconds, "Planungsannahme")
        assertEquals(EtaFallbackReason.SPEED_LIMIT_DATA_UNAVAILABLE.name, p.eta!!.fallbackReason); assertEquals(0, p.eta!!.bufferSeconds)
        assertEquals(p.eta, view.eta)
        // ohne freigegebene Modellrevision
        val none = cf.world()
        val s2 = Scene(roads()).let { sc -> sc }
        val noModel = RideOfferRequests.create(cf.fx.base.ctxTx, cf.fx.base.outbox, cf.fx.base.geo, cf.fx.base.config.effective, cf.fx.clock, DirectTravelTime { _, _ -> 600 }, CandidateGate.PASS_THROUGH, quotes = cf.fx.quotes,
            etaSource = s2.eta.offerTime(EtaStopKeys { _, rev -> if (cf.stringOf("SELECT stop_id::text FROM taktovia.stop_revision WHERE id = ?", rev) == none.stopA.toString()) "H1" else "H2" }))
        val r = noModel.request(cf.passenger(none).ctx, cf.fx.base.command(none, "2026-11-02T10:00:00+01:00", passengers = 1)).offers.single()
        assertEquals(EtaFallbackReason.NO_APPROVED_MODEL.name, r.eta!!.fallbackReason); assertNull(r.eta!!.modelVersion); assertEquals(600, r.directSeconds)
    }

    @Test
    fun zwei_angebote_fuer_dieselbe_strecke_mit_verschiedenen_fahrern_beruhen_auf_derselben_grundzeit() {
        val s = Scene()
        // zwei Umläufe mit Schichten verschiedener Fahrer im Pool des Dienstes: die Angebotsbildung kennt keine Fahrer-Angabe (ETA-001)
        val r1 = cf.fx.run(s.w); val r2 = cf.fx.run(s.w)
        cf.fx.adminUpdate("UPDATE taktovia.staff_shift SET driver_ref = ? WHERE id = ?", UUID.randomUUID(), r1.shiftId)
        cf.fx.adminUpdate("UPDATE taktovia.staff_shift SET driver_ref = ? WHERE id = ?", UUID.randomUUID(), r2.shiftId)
        val a = s.offer().first; val b = s.offer().first
        assertEquals(a.directSeconds, b.directSeconds); assertEquals(a.arrivalWindow, b.arrivalWindow); assertEquals(a.eta, b.eta)
        // und auch nach Änderung der Fahrerzuordnung unverändert
        cf.fx.adminUpdate("UPDATE taktovia.staff_shift SET driver_ref = ? WHERE id = ?", UUID.randomUUID(), r1.shiftId)
        assertEquals(a.directSeconds, s.offer().first.directSeconds)
    }

    @Test
    fun konfigurationswechsel_waehrend_der_angebotsbildung_jedes_angebot_traegt_genau_eine_version() {
        val s = Scene()
        val base = Instant.parse("2026-03-01T00:00:00Z")
        val dwellByRevision = java.util.concurrent.ConcurrentHashMap<UUID, Int>()
        fun publish(k: Int) { val dwell = 20 + k; dwellByRevision[efx.publish(s.w.tenant, worldParams + mapOf("stop_dwell_sec" to dwell), effectiveFrom = base.plusSeconds(k.toLong()))] = dwell }
        publish(0)
        val stop = AtomicBoolean(false); val pool = Executors.newFixedThreadPool(7)
        try {
            val publisher = pool.submit { var k = 1; while (k <= 25 && !stop.get()) { publish(k); k++ } }
            val start = CountDownLatch(1)
            val workers = (0 until 6).map { pool.submit<List<Pair<RideOfferProposal, RideOfferView>>> {
                start.await()
                val out = ArrayList<Pair<RideOfferProposal, RideOfferView>>()
                while (!publisher.isDone || out.size < 4) out += s.offer()
                out
            } }
            start.countDown(); publisher.get(30, TimeUnit.SECONDS)
            val offers = workers.flatMap { it.get(60, TimeUnit.SECONDS) }
            assertTrue(offers.size >= 24)
            val byVersion = offers.groupBy { it.first.eta!!.configVersion }
            // je Konfigurationsversion genau eine Grundzeit (Haltezeit dieser Version plus Referenzzeit), nie eine Mischung zweier Stände
            byVersion.forEach { (v, list) -> assertEquals(1, list.map { it.first.directSeconds }.toSet().size, "Version $v mit zwei Grundzeiten: ${list.map { it.first.directSeconds }}") }
            offers.forEach { (p, view) ->
                assertNotNull(p.eta!!.configVersion); assertEquals(p.eta, view.eta)
                assertEquals(p.arrivalWindow, view.arrivalWindow); assertTrue(p.directSeconds in 360 + 20..360 + 45, "Haltezeit einer veröffentlichten Revision: ${p.directSeconds}")
            }
            assertTrue(byVersion.size >= 2, "der Wechsel war während des Laufs sichtbar (${byVersion.size} Versionen)")
        } finally { stop.set(true); pool.shutdownNow() }
    }
}

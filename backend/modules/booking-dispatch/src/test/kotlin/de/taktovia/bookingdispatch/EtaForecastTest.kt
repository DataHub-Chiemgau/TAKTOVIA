package de.taktovia.bookingdispatch

import de.taktovia.bookingdispatch.api.BookingdispatchApi
import de.taktovia.bookingdispatch.api.EtaApi
import de.taktovia.bookingdispatch.api.EtaBasis
import de.taktovia.bookingdispatch.api.EtaFallbackReason
import de.taktovia.bookingdispatch.api.EtaForecast
import de.taktovia.bookingdispatch.api.EtaForecastRequest
import de.taktovia.bookingdispatch.api.EtaForecastStop
import de.taktovia.bookingdispatch.api.EtaQuantile
import de.taktovia.bookingdispatch.api.EtaRoadSegment
import de.taktovia.bookingdispatch.api.EtaSpeedRules
import de.taktovia.bookingdispatch.api.EtaStopKeys
import de.taktovia.bookingdispatch.api.MatrixInfo
import de.taktovia.bookingdispatch.api.RevisionCause
import de.taktovia.bookingdispatch.api.RouteInfo
import de.taktovia.bookingdispatch.api.RoutingPort
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.platform.readports.PositionReadPort
import de.taktovia.platform.readports.PositionView
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WP-M4-62 / LH-I.3-ETA-002: regelkonforme Prognose (Referenzfahrzeit + Haltezeiten + Unsicherheitspuffer). Originalabnahme: kein ausgegebenes Zeitfenster erfordert nach den verwendeten
 * Routendaten eine Geschwindigkeitsüberschreitung. Gegen die echte Datenbank; Straßendaten mit Geschwindigkeitsattributen sind ein Stub (Valhalla ist hier nicht ausführbar, D-09).
 * Der Nachweis an echten Angeboten und den Anzeigen folgt mit WP-M4-66.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EtaForecastTest {
    private lateinit var fx: EtaTestFixture
    private val at: Instant = Instant.parse("2026-11-02T07:00:00Z")
    private val chain = EtaForecastRequest(listOf(EtaForecastStop("H1"), EtaForecastStop("H2"), EtaForecastStop("H3")), "car", at)

    @BeforeAll fun up() { fx = EtaTestFixture() }
    @AfterAll fun down() = fx.close()

    private fun stub(abTime: Int = 360, bcTime: Int = 90, ab: List<EtaRoadSegment>? = StubRoads.AB, bc: List<EtaRoadSegment>? = StubRoads.BC) =
        StubRoads("v1", mapOf("v1" to StubRoads.table(abTime, bcTime, ab, bc)))

    @Test
    fun kein_zeitfenster_erfordert_geschwindigkeitsueberschreitung() {
        val t = fx.tenant(); val ctx = fx.context(t)
        // Teststrecken mit bekannten Tempolimits, darunter Anbieterzeiten unter der Mindestfahrzeit (zu schnelle Eingabewerte)
        val cases = listOf(stub(360, 90), stub(312, 60), stub(100, 10), stub(5000, 5000), stub(1, 1, listOf(EtaRoadSegment(3000, 120)), listOf(EtaRoadSegment(500, 20)))
            .also { it.tables["v1"] = StubRoads.table(1, 1, listOf(EtaRoadSegment(3000, 120)), listOf(EtaRoadSegment(500, 20))).mapValues { (k, v) -> if (k.first == "H1") Triple(v.first, 3000, v.third) else v } })
        for ((n, roads) in cases.withIndex()) {
            val f = fx.eta(roads).forecast.forecast(ctx, chain)
            assertNull(f.fallbackReason, "Fall $n")
            val table = roads.tables.getValue("v1")
            f.legs.forEach { leg ->
                val segs = table.getValue(chain.stops[leg.index].stopKey to chain.stops[leg.index + 1].stopKey).third!!
                // unabhängige Gleitkomma-Nachrechnung: implizite Geschwindigkeit je Abschnitt (Zeit proportional zur Mindestfahrzeit verteilt) nie über dem Limit
                val minTotal = segs.sumOf { it.lengthMeters / (it.speedLimitKmh!! / 3.6) }
                segs.forEach { s ->
                    val seconds = leg.referenceSeconds * (s.lengthMeters / (s.speedLimitKmh!! / 3.6)) / minTotal
                    val impliedKmh = s.lengthMeters / seconds * 3.6
                    assertTrue(impliedKmh <= s.speedLimitKmh!! + 1e-6, "Fall $n: ${impliedKmh} km/h über Limit ${s.speedLimitKmh}")
                }
                assertEquals(emptyList<Int>(), EtaSpeedRules.violations(segs, leg.referenceSeconds))
                // auch das früheste Ankunftsfenster enthält mindestens die Mindestfahrzeit
                val earliestDrive = Duration.between(f.stops[leg.index].departureEarliest, f.stops[leg.index + 1].arrivalEarliest).seconds
                assertTrue(earliestDrive >= minTotal - 1e-6, "Fall $n: Fenster ${earliestDrive}s unter Mindestfahrzeit ${minTotal}s")
            }
        }
    }

    @Test
    fun haltezeit_und_reserve_als_eigene_anteile_mit_quantil() {
        val t = fx.tenant(); val ctx = fx.context(t); val api = fx.eta(stub())
        val p85 = api.forecast.forecast(ctx, chain)   // Planungsdefault p85: 15 %, Mindestreserve 10 s
        assertEquals(EtaQuantile.P85, p85.quantile)
        assertEquals(360 + 90, p85.referenceSeconds)
        assertEquals(3 * 30, p85.dwellSeconds, "Haltezeitanteil: drei Stopps mit der Planungs-Haltezeit des Modells")
        assertEquals(54 + 14, p85.bufferSeconds, "Reserve p85: ceil(15 % von 360) + ceil(15 % von 90)")
        assertEquals(72 + 18, p85.uncertaintySeconds, "Unsicherheit: Reserve bis p90 (20 %)")
        val last = p85.stops.last()
        assertEquals(at.plusSeconds((p85.referenceSeconds + 2 * 30).toLong()), last.arrivalEarliest)
        assertEquals(at.plusSeconds((p85.referenceSeconds + 2 * 30 + p85.bufferSeconds).toLong()), last.arrivalLatest)
        // die Reserve folgt dem konfigurierten Quantil
        fx.publish(t, mapOf("eta_model_quantile" to "p50", "stop_dwell_sec" to 45))
        val p50 = api.forecast.forecast(ctx, chain)
        assertEquals(EtaQuantile.P50, p50.quantile)
        assertEquals(18 + 10, p50.bufferSeconds, "p50: ceil(5 % von 360) = 18, 5 % von 90 = 5 -> Mindestreserve 10")
        assertEquals(3 * 45, p50.dwellSeconds, "konfigurierte Haltezeit je Stopp")
        assertEquals(p85.referenceSeconds, p50.referenceSeconds, "die Referenzfahrzeit bleibt unverändert")
        fx.publish(t, mapOf("eta_model_quantile" to "p90"), effectiveFrom = Instant.parse("2026-02-01T00:00:00Z"))
        assertEquals(72 + 18, api.forecast.forecast(ctx, chain).bufferSeconds)
        assertTrue(p50.configVersion != p85.configVersion, "andere Konfiguration, andere Version")
    }

    @Test
    fun rollstuhl_stopp_verlaengert_haltezeit() {
        val t = fx.tenant(); val ctx = fx.context(t); val api = fx.eta(stub())
        val plain = api.forecast.forecast(ctx, chain)
        val wheelchairChain = chain.copy(stops = listOf(EtaForecastStop("H1"), EtaForecastStop("H2", wheelchairHandling = true), EtaForecastStop("H3")))
        val withChair = api.forecast.forecast(ctx, wheelchairChain)
        assertEquals(120, withChair.dwellSeconds - plain.dwellSeconds, "Planungswert des Modells: wheelchair_boarding_sec 120")
        assertEquals(120, withChair.stops[1].wheelchairBoardingSeconds); assertEquals(150, withChair.stops[1].dwellSeconds); assertEquals(0, withChair.stops[0].wheelchairBoardingSeconds)
        assertEquals(plain.referenceSeconds, withChair.referenceSeconds); assertEquals(plain.bufferSeconds, withChair.bufferSeconds)
        assertEquals(plain.stops.last().arrivalEarliest.plusSeconds(120), withChair.stops.last().arrivalEarliest, "der Rollstuhl-Stopp verschiebt alle folgenden Zeiten")
        fx.publish(t, mapOf("wheelchair_boarding_sec" to 90))
        assertEquals(90, api.forecast.forecast(ctx, wheelchairChain).dwellSeconds - plain.dwellSeconds, "konfigurierte Einstiegszeit")
    }

    @Test
    fun stop_dwell_sec_unter_systemgrenze_wird_abgewiesen() {
        val t = fx.tenant()
        val e = assertThrows<ProblemException> { fx.publish(t, mapOf("stop_dwell_sec" to 9)) }
        assertEquals(listOf(ViolationCode.BELOW_MINIMUM), e.violations.map { it.code })
        assertEquals(0, fx.adminInt("SELECT count(*) FROM taktovia.config_revision WHERE tenant_id = ?", t.value))
        // Datenbank: gleiche Regel auch am Katalog der Anwendung vorbei
        val sql = assertThrows<Exception> {
            fx.tx.run(t) { j -> j.update("INSERT INTO taktovia.config_revision(tenant_id,id,scope_kind,scope_id,revision,schema_version,values_json,effective_from,created_by,reason) " +
                "VALUES (?,gen_random_uuid(),'TENANT',?,1,'1','{\"stop_dwell_sec\": 9}'::jsonb,now(),'user:t','Test')", t.value, t.value) }
        }
        assertTrue(sql.message!!.contains("BELOW_MINIMUM"), sql.message)
        fx.publish(t, mapOf("stop_dwell_sec" to 10))   // die Grenze selbst ist zulässig
        assertEquals(3 * 10, fx.eta(stub()).forecast.forecast(fx.context(t), chain).dwellSeconds)
        // auch eine zu kleine Planungs-Haltezeit des Modells unterschreitet die Systemgrenze nie
        val low = fx.tenant(approve = false); fx.approveModel(low, 1, parameters = """{"default_stop_dwell_sec":2}""")
        assertEquals(3 * 10, fx.eta(stub()).forecast.forecast(fx.context(low), chain).dwellSeconds)
    }

    @Test
    fun zu_schnelle_eingabewerte_verengen_fenster_nicht() {
        val t = fx.tenant(); val ctx = fx.context(t)
        val honest = fx.eta(stub(312, 60)).forecast.forecast(ctx, chain)   // Anbieterzeit genau an der Mindestfahrzeit
        for (fast in listOf(1, 50, 200, 311)) {
            val f = fx.eta(stub(fast, 5)).forecast.forecast(ctx, chain)
            assertNull(f.fallbackReason)
            assertTrue(!f.stops.last().arrivalEarliest.isBefore(honest.stops.last().arrivalEarliest), "frühestes Ende bei Anbieterzeit $fast s nicht enger als bei der Mindestfahrzeit")
            assertTrue(!f.stops.last().arrivalLatest.isBefore(honest.stops.last().arrivalLatest), "spätestes Ende nicht enger")
            assertEquals(honest, f.copy(), "zu schnelle Werte verändern die Prognose nicht gegenüber der Mindestfahrzeit")
        }
    }

    @Test
    fun toleranz_ist_kein_freibrief_fuer_ueberschreitung() {
        val t = fx.tenant(); val ctx = fx.context(t)
        val tooFast = stub(300, 40)   // 300 s < 312 s Mindestfahrzeit; bei Limit + 10 km/h wäre die Mindestfahrzeit kleiner (ca. 267 s)
        val before = fx.eta(tooFast).forecast.forecast(ctx, chain)
        fx.publish(t, mapOf("eta_safe_speed_tolerance" to 10))
        val after = fx.eta(tooFast).forecast.forecast(ctx, chain)
        val withTolerance = StubRoads.AB.sumOf { it.lengthMeters / ((it.speedLimitKmh!! + 10) / 3.6) }
        assertTrue(withTolerance < 300, "Gegenprobe: mit Toleranz als Freibrief wären 300 s zulässig gewesen")
        assertEquals(10, after.settings!!.safeSpeedToleranceKmh, "die Toleranz ist wirksam konfiguriert")
        assertEquals(312, after.legs[0].referenceSeconds, "die zulässige Geschwindigkeit bleibt das Limit der Routendaten")
        assertEquals(before.stops, after.stops, "gleiche Fenster mit und ohne Toleranz"); assertEquals(before.legs, after.legs)
        assertEquals(listOf(0, 1), EtaSpeedRules.violations(StubRoads.AB, 300), "die Prüfroutine kennt keine Toleranz")
    }

    @Test
    fun abschnitt_ohne_limit_ergibt_fallbackgrund() {
        val t = fx.tenant(); val ctx = fx.context(t)
        // ein Abschnitt ohne Limit: kein Fenster, kein stilles Annehmen eines hohen Limits
        val missing = fx.eta(stub(ab = listOf(EtaRoadSegment(1000, 50), EtaRoadSegment(2000, null)))).forecast.forecast(ctx, chain)
        assertEquals(EtaFallbackReason.SPEED_LIMIT_MISSING, missing.fallbackReason); assertTrue(missing.stops.isEmpty() && missing.legs.isEmpty())
        assertEquals(EtaFallbackReason.SPEED_LIMIT_MISSING, fx.eta(stub(bc = listOf(EtaRoadSegment(500, 0)))).forecast.forecast(ctx, chain).fallbackReason, "Limit 0 ist keine Angabe")
        // Routendaten ganz ohne Geschwindigkeitsattribute
        assertEquals(EtaFallbackReason.SPEED_LIMIT_DATA_UNAVAILABLE, fx.eta(stub(ab = null)).forecast.forecast(ctx, chain).fallbackReason)
        // der Routingport des Workers liefert keine Attribute: fail-closed
        val port = object : RoutingPort {
            override fun route(ctx: RequestContext, originStop: String, destinationStop: String, profile: String) =
                RouteInfo(originStop, destinationStop, 100, 900, emptyList(), profile, "grid-1", "grid")
            override fun walk(ctx: RequestContext, originStop: String, destinationStop: String) = route(ctx, originStop, destinationStop, "walk")
            override fun matrix(ctx: RequestContext, originStops: List<String>, destinationStops: List<String>, profile: String): MatrixInfo = throw UnsupportedOperationException()
        }
        val viaPort = fx.eta(EtaApi.roadsFrom(port))
        assertEquals(EtaFallbackReason.SPEED_LIMIT_DATA_UNAVAILABLE, viaPort.forecast.forecast(ctx, chain).fallbackReason)
        assertEquals(100, viaPort.reference.reference(ctx, de.taktovia.bookingdispatch.api.EtaReferenceRequest("H1", "H2", "car", at)).timeSeconds, "die Referenzzeit selbst braucht keine Limits")
        // widersprüchliche Routendaten (Summe der Abschnitte passt nicht zur Strecke)
        val inconsistent = StubRoads("v1", mapOf("v1" to StubRoads.table().mapValues { (k, v) -> if (k.first == "H1") Triple(v.first, 9999, v.third) else v }))
        assertEquals(EtaFallbackReason.ROUTE_DATA_INCONSISTENT, fx.eta(inconsistent).forecast.forecast(ctx, chain).fallbackReason)
        assertNull(EtaSpeedRules.legalMinimumSeconds(listOf(EtaRoadSegment(100, 50), EtaRoadSegment(100, null))))
    }

    @Test
    fun konfigurationswechsel_waehrend_angebotsbildung_genau_eine_version() {
        val t = fx.tenant(); val ctx = fx.context(t); val api = fx.eta(stub())
        val base = Instant.parse("2026-03-01T00:00:00Z")
        val quantiles = listOf("p50", "p75", "p85", "p90")
        val published = java.util.concurrent.ConcurrentHashMap<UUID, Triple<Int, Int, String>>()   // Revision -> (Haltezeit, Einstiegszeit, Quantil)
        fun publishRevision(k: Int) {
            val dwell = 20 + k; val wc = 100 + k; val q = quantiles[k % 4]
            val id = fx.publish(t, mapOf("stop_dwell_sec" to dwell, "wheelchair_boarding_sec" to wc, "eta_model_quantile" to q), effectiveFrom = base.plusSeconds(k.toLong()))
            published[id] = Triple(dwell, wc, q)
        }
        publishRevision(0)
        val startAt = base.plusSeconds(10_000)
        val request = chain.copy(startAt = startAt, stops = listOf(EtaForecastStop("H1"), EtaForecastStop("H2", true), EtaForecastStop("H3")))
        val stop = AtomicBoolean(false); val pool = Executors.newFixedThreadPool(9)
        try {
            val publisher = pool.submit { var k = 1; while (k <= 30 && !stop.get()) { publishRevision(k); k++ } }
            val start = CountDownLatch(1)
            val workers = (0 until 8).map { pool.submit(Callable<List<Pair<EtaForecast, Map<String, String>>>> {
                start.await()
                val out = ArrayList<Pair<EtaForecast, Map<String, String>>>()
                while (!publisher.isDone || out.size < 5) {
                    val snap = api.forecast.snapshot(ctx, null, null, startAt)
                    out += api.forecast.forecast(ctx, request, snap) to snap.sources
                }
                out
            }) }
            start.countDown()
            publisher.get(); val results = workers.flatMap { it.get() }
            assertTrue(results.size >= 40)
            val versions = HashSet<String>()
            for ((f, sources) in results) {
                assertNull(f.fallbackReason)
                // alle drei Parameter stammen aus derselben Konfigurationsrevision, nie aus einer Mischung zweier Revisionen
                val ids = listOf("stop_dwell_sec", "wheelchair_boarding_sec", "eta_model_quantile").map { sources.getValue(it) }.toSet()
                assertEquals(1, ids.size, "Mischung zweier Konfigurationsstände: $sources")
                val rev = UUID.fromString(ids.single().removePrefix("revision:"))
                val (dwell, wc, q) = published.getValue(rev)
                assertEquals(dwell, f.settings!!.stopDwellSec); assertEquals(wc, f.settings!!.wheelchairBoardingSec); assertEquals(q, f.quantile!!.code)
                assertEquals(3 * dwell + wc, f.dwellSeconds, "die Prognose rechnet mit genau den Werten ihrer Version")
                assertNotNull(f.configVersion); versions += f.configVersion!!
            }
            assertTrue(versions.size >= 2, "der Wechsel war während des Laufs sichtbar (mindestens zwei Versionen beobachtet)")
        } finally { stop.set(true); pool.shutdownNow() }
    }

    // ---- Leseport „ETA je Stopp“ (WP-M4-62, Kriterium 5) -------------------------------------------------------------------------------------------------------------------------------

    private class FixedPositions(var position: PositionView?) : PositionReadPort { override fun latest(ctx: RequestContext, vehicleId: UUID) = position }

    private inner class PortWorld {
        val bfx = BookingFixture()
        val run = bfx.createRun("""{"schema_version":"1.0","seats":4,"wheelchair":1,"equipment":["RAMP"]}""")
        val tenant = bfx.tenantA
        val ctx = bfx.context(tenant)
        val runs = BookingdispatchApi.create(bfx.ctxTx).runs
        // Uhr in einem jeweils eigenen Zeitfenster dieses Laufs: Konfigurationsrevisionen früherer Läufe überschneiden sich nicht mit diesem Zeitfenster
        val now: Instant = Instant.parse("2100-01-01T00:00:00Z").plusSeconds(java.util.concurrent.ThreadLocalRandom.current().nextLong(0, 5_000_000) * 60)
        val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)
        val keys = (0..2).associate { RunPlanTestSupport.stopRev(it) to "H${it + 1}" }
        val plannedAt = now.plusSeconds(600)
        init {
            if (fx.adminInt("SELECT count(*) FROM taktovia.eta_model_revision WHERE tenant_id = ?", tenant.value) == 0) fx.approveModel(tenant, 1)
            val rides = listOf(Triple(UUID.randomUUID(), RunPlanTestSupport.vec(1, 1), 0 to 2))
            val stops = RunPlanTestSupport.planOf(3, rides).map { it.copy(plannedAt = plannedAt.plusSeconds(600L * it.seq), legDurationSec = if (it.seq == 2) null else 300, legDistanceM = if (it.seq == 2) null else 1000) }
            runs.appendRevision(ctx, run.runId, 1, stops, RevisionCause.REPLAN)
        }
        fun api(positions: PositionReadPort) = EtaApi.create(fx.ctxTx, fx.config.effective, stub(), clock, runs, positions, EtaStopKeys { _, id -> keys.getValue(id) })
        fun position(ageSec: Long) = PositionView(run.vehicleId, UUID.randomUUID(), 13.7, 51.0, now.minusSeconds(ageSec))
        fun close() = bfx.close()
    }

    @Test
    fun leseport_liefert_prognose_mit_datenstand_fuer_laufende_fahrt() {
        val w = PortWorld()
        try {
            val fresh = w.position(30)
            val report = w.api(FixedPositions(fresh)).stopEta!!.etaPerStop(w.ctx, w.run.runId, "car", null)
            assertNull(report.fallbackReason); assertEquals(fresh.measuredAt, report.dataStand, "Datenstand = Messzeit der letzten Position")
            assertEquals(3, report.stops.size); assertEquals(1, report.modelVersion); assertNotNull(report.configVersion); assertEquals("v1", report.roadDataVersion)
            assertTrue(report.stops.all { it.basis == EtaBasis.LIVE_POSITION })
            assertTrue(report.stops.zipWithNext().all { (a, b) -> b.forecastEarliest > a.forecastEarliest && !b.forecastLatest.isBefore(b.forecastEarliest) })
            assertEquals(w.plannedAt, report.stops[0].promisedAt)
        } finally { w.close() }
    }

    @Test
    fun veraltete_positionsdaten_ergeben_fallbackgrund_zusage_unveraendert() {
        val w = PortWorld()
        try {
            val plan = w.runs.currentPlan(w.ctx, w.run.runId).stops.associate { it.seq to it.plannedAt }
            // Standard laut Katalog: eta_max_data_age_sec 120 s, Rückfall PLAN_TIME
            val stale = w.api(FixedPositions(w.position(121))).stopEta!!.etaPerStop(w.ctx, w.run.runId, "car", null)
            assertEquals(EtaFallbackReason.POSITION_STALE, stale.fallbackReason)
            assertTrue(stale.stops.all { it.basis == EtaBasis.PLAN_TIME }, "keine scheinbar aktuelle Prognose")
            stale.stops.forEach { assertEquals(plan.getValue(it.seq), it.promisedAt, "verbindliche Zusage unverändert"); assertEquals(it.promisedAt, it.forecastEarliest); assertEquals(it.promisedAt, it.forecastLatest) }
            assertEquals(w.now.minusSeconds(121), stale.dataStand)
            assertNull(w.api(FixedPositions(w.position(119))).stopEta!!.etaPerStop(w.ctx, w.run.runId, "car", null).fallbackReason, "innerhalb des Höchstalters")
            val missing = w.api(FixedPositions(null)).stopEta!!.etaPerStop(w.ctx, w.run.runId, "car", null)
            assertEquals(EtaFallbackReason.POSITION_MISSING, missing.fallbackReason); assertNull(missing.dataStand)
            // Rückfall REFERENCE_FORECAST: Referenzprognose ab dem Plananker ohne Live-Bezug, Zusage weiter unverändert; konfiguriertes Höchstalter 60 s
            fx.publish(w.tenant, mapOf("eta_fallback_policy" to "REFERENCE_FORECAST", "eta_max_data_age_sec" to 60), effectiveFrom = w.now, effectiveTo = w.now.plusSeconds(3600))
            val ref = w.api(FixedPositions(w.position(90))).stopEta!!.etaPerStop(w.ctx, w.run.runId, "car", null)
            assertEquals(EtaFallbackReason.POSITION_STALE, ref.fallbackReason, "das konfigurierte Höchstalter gilt (90 s > 60 s)")
            assertTrue(ref.stops.all { it.basis == EtaBasis.REFERENCE_FORECAST })
            ref.stops.forEach { assertEquals(plan.getValue(it.seq), it.promisedAt) }
            assertEquals(plan.getValue(0), ref.stops[0].forecastEarliest, "Anker = Plan, ohne Live-Bezug")
            assertNotEquals(ref.stops[1].promisedAt, ref.stops[1].forecastEarliest.minusSeconds(1000), "tatsächlich berechnet, nicht aus dem Plan kopiert")
        } finally { w.close() }
    }
}

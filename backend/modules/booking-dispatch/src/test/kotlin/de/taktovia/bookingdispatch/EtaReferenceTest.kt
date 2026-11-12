package de.taktovia.bookingdispatch

import de.taktovia.bookingdispatch.api.EtaFallbackReason
import de.taktovia.bookingdispatch.api.EtaForecastRequest
import de.taktovia.bookingdispatch.api.EtaForecastStop
import de.taktovia.bookingdispatch.api.EtaReferenceRequest
import de.taktovia.bookingdispatch.api.EtaRoadSegment
import de.taktovia.bookingdispatch.api.EtaSpeedRules
import de.taktovia.bookingdispatch.api.RoutingFailure
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.ViolationCode
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
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * WP-M4-61 / LH-I.3-ETA-001: fahrerneutrale Referenzfahrzeit (Originalabnahme: ein Referenzlauf mit identischen Straßendaten ergibt unabhängig von der Fahrer-ID dieselbe Grundzeit).
 * Gegen die echte Datenbank (Modellrevision, RLS, Konfiguration); der Routingdienst ist ein Stub mit Versionen, Geschwindigkeitsattributen und Ausfallschalter
 * (Valhalla/OSM ist hier nicht ausführbar, D-09; der Worker liefert keine Geschwindigkeitsattribute).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EtaReferenceTest {
    private lateinit var fx: EtaTestFixture
    private val at: Instant = Instant.parse("2026-11-02T07:00:00Z")

    @BeforeAll fun up() { fx = EtaTestFixture() }
    @AfterAll fun down() = fx.close()

    private fun roads(version: String = "osm-2026-11") = StubRoads(version, mapOf(version to StubRoads.table()))
    private fun request(from: String = "H1", to: String = "H2", pinned: String? = null) = EtaReferenceRequest(from, to, "car", at, pinned)

    @Test
    fun grundzeit_unabhaengig_von_fahrer_id() {
        val t = fx.tenant(); val api = fx.eta(roads())
        // Fahrerkontexte des Aufrufers: die Schnittstelle nimmt keine Fahrer-Kennung, der Kontext (Akteur/Subjekt) darf das Ergebnis nicht beeinflussen
        val drivers = (1..6).map { fx.context(t, subject = "fahrer-${it}-${java.util.UUID.randomUUID()}") }
        val results = drivers.map { api.reference.reference(it, request()) }
        assertEquals(1, results.toSet().size, "alle Fahrerkontexte ergeben dasselbe Ergebnis")
        assertEquals(360, results.first().timeSeconds)
        // parallel und wiederholt: bitgleich
        val pool = Executors.newFixedThreadPool(8); val start = CountDownLatch(1)
        try {
            val futures = (0 until 40).map { i -> pool.submit(Callable { start.await(); api.reference.reference(drivers[i % drivers.size], request()) }) }
            start.countDown()
            futures.forEach { assertEquals(results.first(), it.get()) }
        } finally { pool.shutdownNow() }
        // Referenzfahrzeit hängt nicht von der konfigurierten Personalisierung ab (in P1 nicht aktivierbar)
        assertEquals(results.first(), api.reference.reference(drivers.first(), request()))
    }

    @Test
    fun referenzzeit_nie_unter_rechtmaessiger_mindestfahrzeit() {
        val t = fx.tenant(); val ctx = fx.context(t)
        // Referenzmenge: je Strecke Sollzeit der rechtmäßigen Mindestfahrzeit (Länge/Limit je Abschnitt) – unabhängig vom Code unter Test mit Gleitkomma nachgerechnet
        val routes = mapOf(
            ("H1" to "H2") to Triple(360, StubRoads.AB, 312), ("H2" to "H3") to Triple(90, StubRoads.BC, 60),
            ("H3" to "H4") to Triple(700, listOf(EtaRoadSegment(5000, 100), EtaRoadSegment(1000, 50)), 252),   // 180 + 72 = 252 s
            ("H4" to "H5") to Triple(10, listOf(EtaRoadSegment(1500, 30)), 180),                                  // Anbieter meldet zu schnell: 10 s statt mindestens 180 s
            ("H5" to "H6") to Triple(0, emptyList<EtaRoadSegment>(), 0),
        )
        val stub = StubRoads("v1", mapOf("v1" to routes.mapValues { (_, v) -> Triple(v.first, v.second.sumOf { it.lengthMeters }, v.second) }))
        val api = fx.eta(stub)
        for ((key, expected) in routes) {
            val r = api.reference.reference(ctx, EtaReferenceRequest(key.first, key.second, "car", at))
            val independent = Math.ceil(expected.second.sumOf { it.lengthMeters / (it.speedLimitKmh!! / 3.6) } - 1e-9).toInt()
            assertEquals(expected.third, independent, "Sollzeit der Referenzstrecke $key")
            assertNotNull(r.timeSeconds, "für jede Strecke der Referenzmenge liegt eine Referenzfahrzeit vor: $key")
            assertTrue(r.timeSeconds!! >= expected.third, "Referenzzeit ${r.timeSeconds} unter rechtmäßiger Mindestfahrzeit ${expected.third} auf $key")
            assertEquals(expected.third, r.legalMinimumSeconds)
        }
        assertEquals(180, api.reference.reference(ctx, EtaReferenceRequest("H4", "H5", "car", at)).timeSeconds, "zu schnelle Anbieterzeit wird auf die Mindestfahrzeit verlängert")
        // die Prüfroutine selbst: ein Fenster unter der Mindestfahrzeit verlangt eine Überschreitung, eines darüber nicht; ohne Limit kein stilles Annehmen
        assertEquals(listOf(0, 1), EtaSpeedRules.violations(StubRoads.AB, 311))
        assertEquals(emptyList<Int>(), EtaSpeedRules.violations(StubRoads.AB, 312))
        assertNull(EtaSpeedRules.legalMinimumSeconds(listOf(EtaRoadSegment(100, null))))
    }

    @Test
    fun prognose_traegt_modell_und_strassendatenversion() {
        val t = fx.tenant(); val ctx = fx.context(t); val api = fx.eta(roads())
        val r = api.reference.reference(ctx, request())
        assertEquals(1, r.modelVersion); assertEquals("osm-2026-11", r.roadDataVersion); assertEquals("stub", r.provider)
        // eine neuere freigegebene Revision gilt für neue Aufrufe
        fx.approveModel(t, 2)
        assertEquals(2, api.reference.reference(ctx, request()).modelVersion)
        // dieselbe Prognose-Kette trägt beide Versionen
        val f = api.forecast.forecast(ctx, EtaForecastRequest(listOf(EtaForecastStop("H1"), EtaForecastStop("H2"), EtaForecastStop("H3")), "car", at))
        assertNull(f.fallbackReason); assertEquals(2, f.modelVersion); assertEquals("osm-2026-11", f.roadDataVersion); assertNotNull(f.configVersion)
        // an eine Straßendatenversion gebundene Revision: eine andere Version wird nicht still akzeptiert
        val bound = fx.tenant(roadDataVersion = "osm-2026-10")
        val rb = api.reference.reference(fx.context(bound), request())
        assertNull(rb.timeSeconds); assertEquals(EtaFallbackReason.ROAD_DATA_VERSION_NOT_APPROVED, rb.fallbackReason)
        // gebundene Anfrage
        assertEquals(EtaFallbackReason.ROAD_DATA_VERSION_MISMATCH, api.reference.reference(ctx, request(pinned = "osm-2025-01")).fallbackReason)
    }

    @Test
    fun personalisierung_in_p1_nicht_aktivierbar() {
        val t = fx.tenant(); val ctx = fx.context(t); val api = fx.eta(roads())
        val before = api.reference.reference(ctx, request())
        // Schreibzugriff wird abgewiesen (Sicherheitsinvariante, feste Vorgabe false)
        val e = assertThrows<ProblemException> { fx.publish(t, mapOf("eta_personalization_enabled" to true)) }
        assertEquals(listOf(ViolationCode.SAFETY_INVARIANT_VIOLATED), e.violations.map { it.code })
        assertEquals(0, fx.adminInt("SELECT count(*) FROM taktovia.config_revision WHERE tenant_id = ?", t.value), "keine Zeile entstanden")
        // auch am Katalog vorbei per SQL: die Datenbank prüft dieselbe Regel
        val sqlError = assertThrows<Exception> {
            fx.tx.run(t) { j -> j.update("INSERT INTO taktovia.config_revision(tenant_id,id,scope_kind,scope_id,revision,schema_version,values_json,effective_from,created_by,reason) " +
                "VALUES (?,gen_random_uuid(),'TENANT',?,1,'1','{\"eta_personalization_enabled\": true}'::jsonb,now(),'user:t','Test')", t.value, t.value) }
        }
        assertTrue(sqlError.message!!.contains("SAFETY_INVARIANT_VIOLATED"), sqlError.message)
        // der feste Wert false ist zulässig und ändert nichts; die fahrerneutrale Referenz bleibt aktiv
        fx.publish(t, mapOf("eta_personalization_enabled" to false))
        assertEquals(before, api.reference.reference(ctx, request()))
        val effective = fx.config.effective.resolve(ctx, de.taktovia.config.api.EffectiveRequest(parameter = "eta_personalization_enabled", at = at)).items.single()
        assertEquals(false, effective.value)
    }

    @Test
    fun routingdienst_ausfall_liefert_fallbackgrund() {
        val t = fx.tenant(); val ctx = fx.context(t); val stub = roads(); val api = fx.eta(stub)
        for ((failure, reason) in listOf(RoutingFailure.PROVIDER_UNAVAILABLE to EtaFallbackReason.ROUTING_UNAVAILABLE, RoutingFailure.PROVIDER_TIMEOUT to EtaFallbackReason.ROUTING_TIMEOUT,
            RoutingFailure.NO_ROUTE to EtaFallbackReason.NO_ROUTE, RoutingFailure.NOT_A_STOP to EtaFallbackReason.ROUTING_REQUEST_INVALID)) {
            stub.failure = failure
            val r = api.reference.reference(ctx, request())
            assertNull(r.timeSeconds, "kein geschätzter Wert bei $failure"); assertEquals(reason, r.fallbackReason)
        }
        stub.failure = null
        assertEquals(360, api.reference.reference(ctx, request()).timeSeconds)
        // unbekannte Strecke: definiert, nie ein Schätzwert
        assertEquals(EtaFallbackReason.NO_ROUTE, api.reference.reference(ctx, request("H9", "H8")).fallbackReason)
        // ohne freigegebene Modellrevision gibt es keine Referenz
        val none = fx.tenant(approve = false)
        assertEquals(EtaFallbackReason.NO_APPROVED_MODEL, api.reference.reference(fx.context(none), request()).fallbackReason)
    }

    @Test
    fun strassendatenwechsel_waehrend_lauf_genau_eine_version() {
        val tA = fx.tenant(); val tB = fx.tenant(); fx.approveModel(tB, 7, buffers = """{"p50":1,"p75":2,"p85":3,"p90":4}""")
        val v1 = StubRoads.table(360, 90); val v2 = StubRoads.table(480, 120)
        val stub = StubRoads("v1", mapOf("v1" to v1, "v2" to v2)); val api = fx.eta(stub)
        val chain = EtaForecastRequest(listOf(EtaForecastStop("H1"), EtaForecastStop("H2"), EtaForecastStop("H3")), "car", at)
        // deterministisch: der Wechsel erfolgt nach der ersten Teilstrecke eines Laufs und wirkt erst für den nächsten Aufruf
        val flipped = AtomicInteger()
        stub.onRoute = { if (flipped.getAndIncrement() == 0) stub.current.set("v2") }
        val during = api.forecast.forecast(fx.context(tA), chain)
        stub.onRoute = null
        assertNull(during.fallbackReason)
        assertEquals("v1", during.roadDataVersion); assertEquals(listOf(360, 90), during.legs.map { it.referenceSeconds }, "beide Teilstrecken aus Version v1, keine Mischung")
        val after = api.forecast.forecast(fx.context(tA), chain)
        assertEquals("v2", after.roadDataVersion); assertEquals(listOf(480, 120), after.legs.map { it.referenceSeconds })
        // parallel: Wechsel in engem Takt, Mandant A und B gleichzeitig; jede Prognose trägt genau eine Version und die Revision ihres Mandanten
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val pool = Executors.newFixedThreadPool(10)
        try {
            val flipper = pool.submit { var i = 0; while (!stop.get()) { stub.current.set(if (i++ % 2 == 0) "v1" else "v2"); Thread.sleep(0, 200_000) } }
            val start = CountDownLatch(1)
            val work = (0 until 8).map { w -> pool.submit(Callable {
                start.await()
                (0 until 25).map {
                    val tenant = if (w % 2 == 0) tA else tB
                    tenant to api.forecast.forecast(fx.context(tenant), chain)
                }
            }) }
            start.countDown()
            val results = work.flatMap { it.get() }
            stop.set(true); flipper.get()
            assertTrue(results.isNotEmpty())
            for ((tenant, f) in results) {
                assertNull(f.fallbackReason)
                val expected = if (f.roadDataVersion == "v1") listOf(360, 90) else listOf(480, 120)
                assertTrue(f.roadDataVersion in setOf("v1", "v2"))
                assertEquals(expected, f.legs.map { it.referenceSeconds }, "Prognose mischt Straßendatenversionen")
                assertEquals(if (tenant == tA) 1 else 7, f.modelVersion, "Revisionen der Mandanten dürfen sich nicht vermischen (RLS)")
            }
        } finally { stop.set(true); pool.shutdownNow() }
    }
}

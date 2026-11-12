package de.taktovia.servicegeo

import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.servicegeo.GeoTestSupport.problem
import de.taktovia.servicegeo.GeoTestSupport.square
import de.taktovia.servicegeo.api.AreaStatus
import de.taktovia.servicegeo.api.CreateServiceArea
import de.taktovia.servicegeo.api.CreateStop
import de.taktovia.servicegeo.api.FavoritePlace
import de.taktovia.servicegeo.api.GeoPoint
import de.taktovia.servicegeo.api.GeocodeCandidate
import de.taktovia.servicegeo.api.Geocoder
import de.taktovia.servicegeo.api.PlaceHit
import de.taktovia.servicegeo.api.PlaceNotice
import de.taktovia.servicegeo.api.PlaceQuery
import de.taktovia.servicegeo.api.PlaceType
import de.taktovia.servicegeo.api.PublishServiceRevision
import de.taktovia.servicegeo.api.RelationEnd
import de.taktovia.servicegeo.api.ReviseStop
import de.taktovia.servicegeo.api.ServicegeoApi
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WP-M2-38 / LH-6.3-PAX-002 gegen die echte Datenbank unter der Runtime-Rolle: die fünf Eingabearten (Adresse, POI, Haltestellenname, Favorit, Kartenpunkt) liefern
 * validierte Koordinaten; Geocoder-Ausfall, Zeitlimit, ungültige Eingaben, inaktive Haltestellen, Bediengebiet, Markup, Datentrennung, Nebenläufigkeit.
 * Der Geocoder ist ein deterministischer Ersatz hinter derselben Schnittstelle (keine echte OSM-Quelle in dieser Umgebung, D-09).
 */
private val t0 = Instant.parse("2026-01-01T00:00:00Z")

class PlaceSearchTest {
    private val fx = fixture
    private val acc = mapOf("schema_version" to "1.0", "step_free" to "YES", "wheelchair_boarding" to "UNVERIFIED")

    /** Deterministischer Ersatz für die Adress-/POI-Quelle; Verhalten je Test einstellbar. */
    private class FakeGeocoder : Geocoder {
        @Volatile var mode = "ok"
        @Volatile var delayMs = 0L
        @Volatile var extra: List<GeocodeCandidate> = emptyList()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val seenTexts = java.util.concurrent.CopyOnWriteArrayList<String>()
        override fun search(text: String, bias: GeoPoint?, limit: Int): List<GeocodeCandidate> {
            calls.incrementAndGet(); seenTexts += text
            if (delayMs > 0) Thread.sleep(delayMs)
            if (mode == "fail") throw IllegalStateException("Quelle nicht erreichbar")
            val base = listOf(
                GeocodeCandidate(PlaceType.ADDRESS, "Hauptstraße 1, Dresden", 51.05, 13.74, "osm", t0),
                GeocodeCandidate(PlaceType.POI, "Zwinger (Sehenswürdigkeit)", 51.053, 13.734, "osm", t0),
            )
            val q = text.lowercase()
            return (base + extra).filter { it.label.lowercase().contains(q) }
        }
        override fun reverse(point: GeoPoint): GeocodeCandidate? {
            calls.incrementAndGet()
            if (mode == "fail") throw IllegalStateException("Quelle nicht erreichbar")
            return GeocodeCandidate(PlaceType.ADDRESS, "Teststraße 5, Dresden", point.latitude, point.longitude, "osm", t0)
        }
    }

    private class Scenario(fx: StructureFixture, val geocoder: FakeGeocoder = FakeGeocoder(), timeoutMs: Long = 2000) {
        val w = fx.world(); val ctx: RequestContext = GeoTestSupport.ctx(fx, w)
        val geo: ServicegeoApi = ServicegeoApi.create(fx.ctxTx, fx.audit, fx.config, geocoder = geocoder, geocoderTimeout = java.time.Duration.ofMillis(timeoutMs))
        val svc = GeoTestSupport.service(fx, w, ctx)
        init {
            geo.serviceRevisions.publish(ctx, svc, 0, PublishServiceRevision("AREA_ON_DEMAND", listOf(mapOf("weekdays" to listOf("MON"), "from" to "06:00", "to" to "22:00")), emptyList<Any>(), Instant.parse("2026-01-01T00:00:00Z"), null, "Test"))
            geo.areas.create(ctx, CreateServiceArea(svc, square(13.7, 51.0), Instant.parse("2026-01-01T00:00:00Z"), null))   // Dresden-Quadrat 13.7..13.8 / 51.0..51.1
        }
        fun stop(name: String, lon: Double = 13.74, lat: Double = 51.05, active: Boolean = true): UUID =
            geo.stops.create(ctx, CreateStop(null, name, mapOf("latitude" to lat, "longitude" to lon),
                mapOf("schema_version" to "1.0", "step_free" to "YES", "wheelchair_boarding" to "UNVERIFIED"), listOf("AREA_ON_DEMAND"), active, Instant.parse("2026-01-01T00:00:00Z"), null)).stopId
        fun deactivate(id: UUID) {
            val cur = geo.stops.get(ctx, id)
            geo.stops.revise(ctx, id, cur.revision, ReviseStop(cur.name, mapOf("latitude" to cur.point.latitude, "longitude" to cur.point.longitude), cur.accessibility, cur.trafficProducts, false, Instant.parse("2026-02-01T00:00:00Z"), null))
        }
        fun search(q: PlaceQuery) = geo.places.search(ctx, q)
    }

    private fun valid(h: PlaceHit) {
        assertTrue(h.point.latitude in -90.0..90.0 && h.point.longitude in -180.0..180.0, "WGS84")
        assertFalse(h.point.latitude == 0.0 && h.point.longitude == 0.0, "nie 0/0")
        assertTrue(h.label.isNotBlank()); assertTrue(h.source.isNotBlank())
    }

    @Test
    fun fuenf_eingabearten_liefern_validierte_koordinaten() {
        val s = Scenario(fx); val stop = s.stop("Altmarkt")
        val fav = FavoritePlace("Zuhause", GeoPoint(13.75, 51.04), null)
        val address = s.search(PlaceQuery(text = "Hauptstraße", serviceId = s.svc)).hits.single { it.type == PlaceType.ADDRESS }
        val poi = s.search(PlaceQuery(text = "Zwinger", serviceId = s.svc)).hits.single { it.type == PlaceType.POI }
        val byStop = s.search(PlaceQuery(text = "altm", serviceId = s.svc)).hits.single { it.type == PlaceType.STOP }
        val byFav = s.search(PlaceQuery(text = "zuhause", favorites = listOf(fav), serviceId = s.svc)).hits.single { it.type == PlaceType.FAVORITE }
        val byMap = s.search(PlaceQuery(mapPoint = GeoPoint(13.741, 51.051), serviceId = s.svc)).hits.single()
        assertEquals(PlaceType.MAP_POINT, byMap.type)
        listOf(address, poi, byStop, byFav, byMap).forEach(::valid)
        assertEquals(stop, byStop.stopId); assertEquals("stop_registry", byStop.source); assertEquals(0, byStop.nearestStop!!.distanceMeters)
        assertEquals(51.05, address.point.latitude); assertEquals("osm", address.source)
        assertEquals(13.75, byFav.point.longitude); assertEquals("favorite", byFav.source)
        assertEquals("Teststraße 5, Dresden", byMap.label, "Kartenpunkt: Rückwärtsauflösung liefert die Adresse")
        assertEquals(51.051, byMap.point.latitude)
        // jeder Treffer ist als Start verwendbar: Dienstzone und nächster Haltepunkt sind zugeordnet
        assertEquals(AreaStatus.INSIDE, address.serviceArea); assertEquals(stop, address.nearestStop!!.stopId)
    }

    @Test
    fun treffer_wird_in_ride_offers_als_start_akzeptiert() {
        // POST /v1/ride-offers prüft Start und Ziel über die Relationsprüfung (WP-M2-06); ein Ortstreffer muss als Start/Ziel ohne weiteres zulässig sein.
        val s = Scenario(fx); val stop = s.stop("Altmarkt")
        val hits = listOf(
            s.search(PlaceQuery(text = "Hauptstraße")).hits.single(), s.search(PlaceQuery(text = "Zwinger")).hits.single(), s.search(PlaceQuery(text = "Altmarkt")).hits.single(),
            s.search(PlaceQuery(text = "Zu", favorites = listOf(FavoritePlace("Zuhause", GeoPoint(13.75, 51.04), null)))).hits.single(),
            s.search(PlaceQuery(mapPoint = GeoPoint(13.76, 51.06))).hits.single(),
        )
        assertEquals(setOf(PlaceType.ADDRESS, PlaceType.POI, PlaceType.STOP, PlaceType.FAVORITE, PlaceType.MAP_POINT), hits.map { it.type }.toSet())
        for (h in hits) {
            val end = if (h.stopId != null) RelationEnd(point = h.point, stopId = h.stopId) else RelationEnd(point = h.point)
            val d = s.geo.relations.check(s.ctx, s.svc, h.point, end, Instant.parse("2026-06-01T10:00:00Z"))
            assertTrue(d.admissible, "${h.type}: $d")
        }
        assertEquals(stop, hits.single { it.type == PlaceType.STOP }.stopId)
    }

    @Test
    fun treffer_ausserhalb_gebiet_wird_gekennzeichnet() {
        val s = Scenario(fx)
        s.geocoder.extra = listOf(GeocodeCandidate(PlaceType.ADDRESS, "Hauptstraße 9, Berlin", 52.52, 13.40, "osm", t0))
        val hits = s.search(PlaceQuery(text = "Hauptstraße", serviceId = s.svc)).hits
        assertEquals(AreaStatus.INSIDE, hits.single { it.label.contains("Dresden") }.serviceArea)
        val berlin = hits.single { it.label.contains("Berlin") }
        assertEquals(AreaStatus.OUTSIDE, berlin.serviceArea); assertNull(berlin.nearestStop)
        assertEquals(AreaStatus.OUTSIDE, s.search(PlaceQuery(mapPoint = GeoPoint(2.35, 48.85), serviceId = s.svc)).hits.single().serviceArea)
        assertEquals(AreaStatus.UNKNOWN, s.search(PlaceQuery(mapPoint = GeoPoint(2.35, 48.85))).hits.single().serviceArea, "ohne Dienst keine Aussage")
    }

    @Test
    fun geocoder_ausfall_laesst_haltestelle_favorit_kartenpunkt_nutzbar() {
        val s = Scenario(fx); s.stop("Hauptmarkt"); s.geocoder.mode = "fail"
        val fav = FavoritePlace("Hauptwohnung", GeoPoint(13.75, 51.04), null)
        val r = s.search(PlaceQuery(text = "Haupt", favorites = listOf(fav)))
        assertEquals(setOf(PlaceType.STOP, PlaceType.FAVORITE), r.hits.map { it.type }.toSet())
        assertTrue(PlaceNotice.GEOCODER_UNAVAILABLE in r.notices); assertFalse(PlaceNotice.NO_MATCH in r.notices)
        val map = s.search(PlaceQuery(mapPoint = GeoPoint(13.74, 51.05)))
        assertEquals(PlaceType.MAP_POINT, map.hits.single().type); assertEquals("Punkt auf der Karte", map.hits.single().label)
        assertTrue(PlaceNotice.GEOCODER_UNAVAILABLE in map.notices)
        // reiner Adresstext: nur der Hinweis, keine Koordinate
        val onlyAddress = s.search(PlaceQuery(text = "Hauptstraße 1"))
        assertTrue(onlyAddress.hits.isEmpty()); assertEquals(setOf(PlaceNotice.GEOCODER_UNAVAILABLE, PlaceNotice.NO_MATCH), onlyAddress.notices)
    }

    @Test
    fun geocoder_timeout_blockiert_die_anderen_quellen_nicht() {
        val s = Scenario(fx, timeoutMs = 150); s.stop("Hauptmarkt"); s.geocoder.delayMs = 3000
        val started = System.nanoTime()
        val r = s.search(PlaceQuery(text = "Haupt"))
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(elapsedMs < 2000, "Zeitlimit greift ($elapsedMs ms)")
        assertEquals(listOf(PlaceType.STOP), r.hits.map { it.type }); assertTrue(PlaceNotice.GEOCODER_UNAVAILABLE in r.notices)
    }

    @Test
    fun unaufloesbarer_text_ergibt_keine_nullkoordinate() {
        val s = Scenario(fx)
        s.geocoder.extra = listOf(
            GeocodeCandidate(PlaceType.ADDRESS, "Nullinsel xyzzy", 0.0, 0.0, "osm", t0),
            GeocodeCandidate(PlaceType.ADDRESS, "Ausserhalb xyzzy", 95.0, 10.0, "osm", t0),
            GeocodeCandidate(PlaceType.ADDRESS, "NaN xyzzy", Double.NaN, 10.0, "osm", t0),
        )
        val r = s.search(PlaceQuery(text = "xyzzy"))
        assertTrue(r.hits.isEmpty(), "ungültige Kandidaten des Geocoders werden verworfen: ${r.hits}"); assertEquals(setOf(PlaceNotice.NO_MATCH), r.notices)
        // ungültige Eingaben: 422 mit Feldverweis
        fun pointer(q: PlaceQuery) = problem { s.search(q) }.also { assertEquals(ErrorCode.VALIDATION_FAILED, it.code) }.violations.single().pointer
        assertEquals("/q", pointer(PlaceQuery(text = "")))
        assertEquals("/q", pointer(PlaceQuery(text = " a ")))
        assertEquals("/q", pointer(PlaceQuery(text = "x".repeat(201))))
        assertEquals("/q", pointer(PlaceQuery(text = "Haupt\u0000straße")))
        assertEquals("/q", pointer(PlaceQuery()))
        assertEquals("/map_point", pointer(PlaceQuery(text = "Haupt", mapPoint = GeoPoint(13.7, 51.0))))
        assertEquals("/map_point", pointer(PlaceQuery(mapPoint = GeoPoint(0.0, 0.0))))
        assertEquals(ViolationCode.TOO_SHORT, problem { s.search(PlaceQuery(text = "a")) }.violations.single().code)
        assertThrowsOutOfRange()
    }

    private fun assertThrowsOutOfRange() {
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> { GeoPoint(13.7, 91.0) }   // Wertebereich schon beim Bilden der Koordinate
    }

    @Test
    fun inaktive_haltestelle_nicht_in_vorschlaegen() {
        val s = Scenario(fx); val on = s.stop("Bahnhof Nord"); val off = s.stop("Bahnhof Süd", lat = 51.06)
        s.deactivate(off)
        val ids = s.search(PlaceQuery(text = "Bahnhof", serviceId = s.svc)).hits.mapNotNull { it.stopId }
        assertEquals(listOf(on), ids)
        // als Favorit bleibt der Ort nutzbar, der inaktive Haltepunkt wird nicht als buchbar vorgeschlagen
        val fav = s.search(PlaceQuery(text = "Süd", favorites = listOf(FavoritePlace("Bahnhof Süd privat", GeoPoint(13.74, 51.06), off)))).hits.single { it.type == PlaceType.FAVORITE }
        assertNull(fav.stopId); assertEquals(51.06, fav.point.latitude)
        val activeFav = s.search(PlaceQuery(text = "Nord", favorites = listOf(FavoritePlace("Bahnhof Nord privat", GeoPoint(13.74, 51.05), on)))).hits.single { it.type == PlaceType.FAVORITE }
        assertEquals(on, activeFav.stopId)
    }

    @Test
    fun fremde_favoriten_nie_sichtbar() {
        // Dieses Modul liest nie Konten: Favoriten kommen ausschließlich aus der Anfrage (die Anwendung liefert die des Akteurs). Ohne eigene Favoriten findet die
        // Suche keinen, auch wenn in einem anderen Mandanten dieselben Bezeichnungen und Haltepunkte existieren.
        val a = Scenario(fx); val b = Scenario(fx)
        val foreignStop = b.stop("Privatziel")
        val own = a.search(PlaceQuery(text = "Privat", favorites = emptyList()))
        assertTrue(own.hits.none { it.type == PlaceType.FAVORITE || it.type == PlaceType.STOP }, own.hits.toString())
        // ein fremder Haltepunkt (anderer Mandant) wird auch als Favoritenbezug nie buchbar
        val crossed = a.search(PlaceQuery(text = "Privat", favorites = listOf(FavoritePlace("Privatziel", GeoPoint(13.74, 51.05), foreignStop)))).hits.single()
        assertNull(crossed.stopId)
    }

    @Test
    fun markup_in_eingabe_wird_nicht_interpretiert() {
        val s = Scenario(fx); s.stop("<b>Fett</b> Halt & 'Co'")
        val text = "<script>alert(1)</script>"
        s.geocoder.extra = listOf(GeocodeCandidate(PlaceType.POI, "$text Kiosk", 51.05, 13.74, "osm", t0))
        val r = s.search(PlaceQuery(text = text))
        assertEquals(text, s.geocoder.seenTexts.last(), "Text wird unverändert (nicht bereinigt, nicht ausgeführt) als Daten weitergegeben")
        assertEquals("$text Kiosk", r.hits.single().label)
        val byName = s.search(PlaceQuery(text = "<b>fett</b>")).hits.single { it.type == PlaceType.STOP }
        assertEquals("<b>Fett</b> Halt & 'Co'", byName.label)
        // SQL-/LIKE-Sonderzeichen wirken nicht als Muster
        assertTrue(s.search(PlaceQuery(text = "%%")).hits.none { it.type == PlaceType.STOP })
        assertTrue(s.search(PlaceQuery(text = "'; DROP TABLE taktovia.stop_revision; --")).hits.isEmpty())
        assertEquals(1, s.search(PlaceQuery(text = "Halt")).hits.count { it.type == PlaceType.STOP })
    }

    @Test
    fun suchtext_nicht_in_metriklabels_und_logs() {
        val s = Scenario(fx); s.stop("Geheimziel Eins")
        val marker = "Geheimziel-" + UUID.randomUUID()
        val out = ByteArrayOutputStream(); val err = ByteArrayOutputStream()
        val oldOut = System.out; val oldErr = System.err
        val julRecords = java.util.concurrent.CopyOnWriteArrayList<String>()
        val handler = object : java.util.logging.Handler() {
            override fun publish(r: java.util.logging.LogRecord) { julRecords += r.message + (r.parameters?.joinToString() ?: "") }
            override fun flush() {}; override fun close() {}
        }
        val root = java.util.logging.Logger.getLogger("")
        System.setOut(PrintStream(out, true)); System.setErr(PrintStream(err, true)); root.addHandler(handler)
        try {
            s.search(PlaceQuery(text = marker)); s.search(PlaceQuery(text = "Geheimziel", bias = GeoPoint(13.7312, 51.0412)))
            s.geocoder.mode = "fail"; s.search(PlaceQuery(text = marker)); s.search(PlaceQuery(mapPoint = GeoPoint(13.7312, 51.0412)))
        } finally { System.setOut(oldOut); System.setErr(oldErr); root.removeHandler(handler) }
        val all = out.toString() + err.toString() + julRecords.joinToString("\n")
        assertFalse(all.contains(marker) || all.contains("Geheimziel"), "Suchtext erscheint nicht in Ausgaben/Logs")
        assertFalse(all.contains("13.7312") || all.contains("51.0412"), "Suchkoordinate erscheint nicht in Ausgaben/Logs")
    }

    @Test
    fun parallele_suchanfragen_liefern_unabhaengige_konsistente_ergebnisse() {
        val s = Scenario(fx); repeat(5) { s.stop("Rathaus $it", lat = 51.01 + it * 0.01) }
        val pool = Executors.newFixedThreadPool(8)
        try {
            val results = (1..16).map { i -> pool.submit<Pair<Int, Set<String>>> { i to s.search(PlaceQuery(text = if (i % 2 == 0) "Rathaus" else "Hauptstraße", serviceId = s.svc)).hits.map { it.label }.toSet() } }.map { it.get() }
            for ((i, labels) in results) {
                if (i % 2 == 0) assertEquals(5, labels.size, "Rathaus-Suche liefert immer alle fünf") else assertEquals(setOf("Hauptstraße 1, Dresden"), labels)
            }
        } finally { pool.shutdownNow() }
    }

    @Test
    fun deaktivierung_parallel_zur_suche_liefert_aktive_revision_oder_gar_nichts() {
        val s = Scenario(fx); val id = s.stop("Flüchtiger Halt")
        val stop = AtomicBoolean(false); val pool = Executors.newFixedThreadPool(2); val started = CountDownLatch(1)
        val bad = java.util.concurrent.CopyOnWriteArrayList<String>()
        val searcher = pool.submit {
            started.countDown()
            while (!stop.get()) {
                val hit = s.search(PlaceQuery(text = "Flüchtiger")).hits.singleOrNull { it.type == PlaceType.STOP }
                if (hit != null && hit.stopId != id) bad += "falsche Haltestelle"
                if (hit != null) { val cur = s.geo.stops.get(s.ctx, id, 1); if (!cur.active) bad += "revision 1 ist inaktiv" }
            }
        }
        started.await(); Thread.sleep(50); s.deactivate(id); Thread.sleep(100); stop.set(true)
        searcher.get(10, TimeUnit.SECONDS); pool.shutdownNow()
        assertTrue(bad.isEmpty(), bad.toString())
        assertTrue(s.search(PlaceQuery(text = "Flüchtiger")).hits.none { it.type == PlaceType.STOP }, "nach der Deaktivierung nie mehr vorgeschlagen")
    }

    companion object {
        private val fixture by lazy { StructureFixture() }
        @JvmStatic @AfterAll fun close() { fixture.close() }
    }
}

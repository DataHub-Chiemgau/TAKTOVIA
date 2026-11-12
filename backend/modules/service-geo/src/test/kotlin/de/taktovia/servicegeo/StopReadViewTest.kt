package de.taktovia.servicegeo

import de.taktovia.platform.problem.ErrorCode
import de.taktovia.servicegeo.GeoTestSupport.problem
import de.taktovia.servicegeo.api.CreateServiceArea
import de.taktovia.servicegeo.api.CreateStop
import de.taktovia.servicegeo.api.GeoPoint
import de.taktovia.servicegeo.api.PublishServiceRevision
import de.taktovia.servicegeo.api.ReviseStop
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/** WP-M2-40 / LH-6.3-PAX-004: Lesesicht Fahrgast auf Haltepunkte und Bediengebiet gegen die echte Datenbank unter der Runtime-Rolle. */
class StopReadViewTest {
    private val fx = fixture
    private val t0 = Instant.parse("2026-01-01T00:00:00Z")
    private val acc = mapOf("schema_version" to "1.0", "step_free" to "YES", "wheelchair_boarding" to "UNVERIFIED")
    private fun pt(lat: Double = 51.05, lon: Double = 13.74) = mapOf("latitude" to lat, "longitude" to lon)

    private fun stop(ctx: de.taktovia.platform.context.RequestContext, name: String, lat: Double = 51.05, lon: Double = 13.74, products: List<String> = listOf("AREA_ON_DEMAND"), active: Boolean = true) =
        fx.geo.stops.create(ctx, CreateStop(null, name, pt(lat, lon), acc, products, active, t0, null))

    private fun service(w: StructureFixture.World, ctx: de.taktovia.platform.context.RequestContext): UUID {
        val svc = GeoTestSupport.service(fx, w, ctx)
        fx.geo.serviceRevisions.publish(ctx, svc, 0, PublishServiceRevision("AREA_ON_DEMAND", listOf(mapOf("weekdays" to listOf("MON"), "from" to "06:00", "to" to "22:00")), emptyList<Any>(), t0, null, "Test"))
        return svc
    }

    @Test
    fun inaktive_dienstfremde_haltepunkte_und_interne_felder_nie_in_fahrgastsicht() {
        val w = fx.world(); val admin = GeoTestSupport.ctx(fx, w); val svc = service(w, admin)
        val ok = stop(admin, "Marktplatz")
        stop(admin, "Nur Linie", products = listOf("LINE_ON_DEMAND"))
        val off = stop(admin, "Stillgelegt", active = false)
        fx.geo.stops.create(admin, CreateStop(null, "Zukunft", pt(), acc, listOf("AREA_ON_DEMAND"), true, Instant.parse("2099-01-01T00:00:00Z"), null))
        val passenger = fx.context(w.tenant, scopes = setOf("geo:read"))
        val at = Instant.parse("2026-06-01T10:00:00Z")

        // mit Dienst: nur aktiv, wirksam und dem Verkehrsprodukt zulässig
        assertEquals(listOf(ok.stopId), fx.geo.passenger.stops(passenger, svc, at, null, null, 50).map { it.stopId })
        // auch ein Pfleger mit Stammdatenrecht sieht in dieser Sicht keine inaktiven Haltepunkte
        assertTrue(fx.geo.passenger.stops(admin, null, at, null, null, 50).none { it.stopId == off.stopId })
        assertEquals(ErrorCode.NOT_FOUND, problem { fx.geo.passenger.stop(passenger, off.stopId, at) }.code)
        // Dienst ohne wirksame Dienstversion: leer
        assertTrue(fx.geo.passenger.stops(passenger, GeoTestSupport.service(fx, w, admin), at, null, null, 50).isEmpty())
        // interne Felder sind im Typ der Sicht nicht vorhanden (approved_by, Aktivstatus, Verkehrsprodukte, Gültigkeit)
        val names = de.taktovia.servicegeo.api.PassengerStopView::class.java.declaredFields.map { it.name }.toSet()
        assertEquals(setOf("stopId", "revision", "name", "point", "accessibility", "distanceMeters"), names)
    }

    @Test
    fun fremder_mandant_sieht_keine_haltepunkte() {
        val a = fx.world(); val b = fx.world(); val ctxA = GeoTestSupport.ctx(fx, a); val ctxB = fx.context(b.tenant, scopes = setOf("geo:read"))
        val s = stop(ctxA, "Marktplatz")
        fx.geo.areas.create(ctxA, CreateServiceArea(service(a, ctxA), GeoTestSupport.square(13.7, 51.0), t0, null))
        assertTrue(fx.geo.passenger.stops(ctxB, null, null, null, null, 50).isEmpty())
        assertTrue(fx.geo.passenger.stops(ctxB, null, null, GeoPoint(13.74, 51.05), 5000, 50).isEmpty())
        assertEquals(ErrorCode.NOT_FOUND, problem { fx.geo.passenger.stop(ctxB, s.stopId, null) }.code)
        assertTrue(fx.geo.passenger.areas(ctxB, null, null, 50).isEmpty())
        assertEquals(1, fx.geo.passenger.stops(fx.context(a.tenant, scopes = setOf("geo:read")), null, null, null, null, 50).size)
    }

    @Test
    fun umkreis_sortiert_nach_entfernung_mit_meterangabe_und_reichweite() {
        val w = fx.world(); val admin = GeoTestSupport.ctx(fx, w)
        val near = stop(admin, "Zweite", 51.0510, 13.74); val nearest = stop(admin, "Erste", 51.0501, 13.74); stop(admin, "Weit", 51.2, 13.74)
        val res = fx.geo.passenger.stops(fx.context(w.tenant, scopes = setOf("geo:read")), null, null, GeoPoint(13.74, 51.05), 500, 50)
        assertEquals(listOf(nearest.stopId, near.stopId), res.map { it.stopId })
        assertTrue(res[0].distanceMeters!! in 8..14, "ca. 11 m: ${res[0].distanceMeters}"); assertTrue(res[1].distanceMeters!! in 105..118)
        assertTrue(fx.geo.passenger.stops(admin, null, null, GeoPoint(14.5, 50.0), 500, 50).isEmpty(), "kein Haltepunkt in Reichweite: leere Liste")
        assertEquals(1, fx.geo.passenger.stops(admin, null, null, GeoPoint(13.74, 51.05), 500, 1).size, "Umfang begrenzt")
        assertNull(fx.geo.passenger.stops(admin, null, null, null, null, 50).first().distanceMeters)
    }

    @Test
    fun deaktivierung_waehrend_die_liste_offen_ist_der_haltepunkt_ist_danach_nicht_mehr_lesbar() {
        val w = fx.world(); val admin = GeoTestSupport.ctx(fx, w); val passenger = fx.context(w.tenant, scopes = setOf("geo:read"))
        val s = stop(admin, "Marktplatz")
        assertEquals(s.stopId, fx.geo.passenger.stop(passenger, s.stopId, null).stopId)
        fx.geo.stops.revise(admin, s.stopId, 1, ReviseStop("Marktplatz", pt(), acc, listOf("AREA_ON_DEMAND"), false, Instant.now().minusSeconds(60), null))
        assertEquals(ErrorCode.NOT_FOUND, problem { fx.geo.passenger.stop(passenger, s.stopId, null) }.code)
        assertTrue(fx.geo.passenger.stops(passenger, null, null, null, null, 50).isEmpty())
    }

    @Test
    fun gebietsgeometrie_der_fahrgastsicht_ist_generalisiert_und_zeitpunktbezogen() {
        val w = fx.world(); val admin = GeoTestSupport.ctx(fx, w); val svc = service(w, admin)
        // fein gezackter Rand: 0.0001-Grad-Zacken (ca. 10 m) auf einem Quadrat
        val ring = mutableListOf<List<Double>>(); val x0 = 13.7; val y0 = 51.0
        for (i in 0..100) ring += listOf(x0 + i * 0.001, y0 + (if (i % 2 == 0) 0.0 else 0.00007))
        ring += listOf(x0 + 0.1, y0 + 0.1); ring += listOf(x0, y0 + 0.1); ring += ring.first()
        val exact = mapOf("type" to "MultiPolygon", "coordinates" to listOf(listOf(ring)))
        val a = fx.geo.areas.create(admin, CreateServiceArea(svc, exact, t0, Instant.parse("2027-01-01T00:00:00Z")))
        val passenger = fx.context(w.tenant, scopes = setOf("geo:read"))
        val view = fx.geo.passenger.areas(passenger, svc, Instant.parse("2026-06-01T00:00:00Z"), 10).single()
        assertEquals(a.id, view.id)
        fun points(m: Map<String, Any?>) = ((m["coordinates"] as List<*>)[0] as List<*>)[0].let { (it as List<*>).size }
        assertTrue(points(view.area) < points(exact), "Geometrie vereinfacht: ${points(view.area)} < ${points(exact)}")
        assertEquals("MultiPolygon", view.area["type"])
        assertTrue(view.areaSquareKm > 50.0 && view.areaSquareKm < 100.0, "km2 ${view.areaSquareKm}")
        assertEquals(51.0, view.bounds.minLatitude, 1e-9)
        // zum gewählten Zeitpunkt wirksam: nach Ablauf kein Gebiet
        assertTrue(fx.geo.passenger.areas(passenger, svc, Instant.parse("2027-06-01T00:00:00Z"), 10).isEmpty())
        assertEquals(ErrorCode.NOT_FOUND, problem { fx.geo.passenger.area(passenger, a.id, Instant.parse("2027-06-01T00:00:00Z")) }.code)
        assertEquals(ErrorCode.NOT_FOUND, problem { fx.geo.passenger.area(passenger, UUID.randomUUID(), null) }.code)
    }

    private companion object {
        private val fixture by lazy { StructureFixture() }
        @JvmStatic @org.junit.jupiter.api.AfterAll fun close() { fixture.close() }
    }
}

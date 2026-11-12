package de.taktovia.app.geo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/** WP-M2-05 / GEO-002 über die echte Anwendung: Haltepunkte pflegen, Aktivstatus und Verkehrsprodukte, Fahrgastsicht, 422/428/412, Rechte und Mandantentrennung. */
class StopHttpApiTest {
    private fun stop(name: String = "Marktplatz", lat: String = "51.05", lon: String = "13.74", acc: String = """{"schema_version":"1.0","step_free":"YES","wheelchair_boarding":"UNVERIFIED"}""",
                     products: String = """["AREA_ON_DEMAND"]""", active: Boolean = true, from: String = "2026-01-01T00:00:00+01:00", stopId: String? = null) =
        """{${stopId?.let { "\"stop_id\":\"$it\"," } ?: ""}"name":"$name","point":{"latitude":$lat,"longitude":$lon},"accessibility":$acc,"traffic_products":$products,"active":$active,"valid_from":"$from"}"""
    private fun update(name: String = "Marktplatz", active: Boolean, from: String, products: String = """["AREA_ON_DEMAND"]""") =
        """{"name":"$name","point":{"latitude":51.05,"longitude":13.74},"accessibility":{"schema_version":"1.0","step_free":"YES","wheelchair_boarding":"UNVERIFIED"},"traffic_products":$products,"active":$active,"valid_from":"$from"}"""
    private val hours = """{"traffic_product":"AREA_ON_DEMAND","weekly_hours":[{"weekdays":["MON","TUE","WED","THU","FRI","SAT","SUN"],"from":"00:00","to":"24:00"}],"exceptions":[],"valid_from":"2026-01-01T00:00:00+01:00","valid_to":null,"reason":"Test"}"""

    @Test
    fun anlegen_lesen_deaktivieren_fahrgastsicht_etag() {
        val w = GeoWorld(); val svc = w.service()
        assertEquals(200, w.put("/v1/services/$svc/operating-hours", hours, "0").status)
        val created = w.post("/v1/stops", stop())
        assertEquals(201, created.status, created.text)
        val id = created.json["stop_id"].asString()
        assertEquals("\"1\"", created.header("ETag")); assertEquals("/v1/stops/$id", created.header("Location"))
        assertTrue(created.json["active"].asBoolean()); assertEquals("AREA_ON_DEMAND", created.json["traffic_products"][0].asString()); assertEquals("user:${w.adminSubject}", created.json["approved_by"].asString())
        // Fahrgast findet den aktiven Haltepunkt (Dienstsicht und Umkreis), ohne service_id sieht er ebenfalls nur aktive
        val search = w.get("/v1/stops?service_id=$svc&near_latitude=51.0501&near_longitude=13.7401&near_radius_m=200", w.passenger)
        assertEquals(200, search.status, search.text); assertEquals(listOf(id), search.json["items"].values().map { it["stop_id"].asString() })
        assertEquals(1, w.get("/v1/stops", w.passenger).json["items"].values().count())
        assertEquals(200, w.get("/v1/stops/$id", w.passenger).status)
        // Vorbedingungen
        assertEquals(428, w.send("PUT", "/v1/stops/$id", update(active = false, from = "2026-06-01T00:00:00+02:00")).status)
        assertEquals(412, w.put("/v1/stops/$id", update(active = false, from = "2026-06-01T00:00:00+02:00"), "9").status)
        // Deaktivierung als Revision
        val off = w.put("/v1/stops/$id", update(active = false, from = "2026-06-01T00:00:00+02:00"), "1")
        assertEquals(200, off.status, off.text); assertEquals(2, off.json["revision"].asInt()); assertEquals(false, off.json["active"].asBoolean()); assertEquals("\"2\"", off.header("ETag"))
        // Fahrgäste: weder in der Suche noch per ID; Pflege sieht die inaktive Revision und die alte bleibt lesbar
        assertEquals(0, w.get("/v1/stops?service_id=$svc&at=2026-07-01T10:00:00%2B02:00", w.passenger).json["items"].values().count())
        assertEquals(0, w.get("/v1/stops", w.passenger).json["items"].values().count())
        assertEquals(0, w.get("/v1/stops?active=true", w.passenger).json["items"].values().count())
        assertEquals(404, w.get("/v1/stops/$id", w.passenger).status)
        assertEquals(1, w.get("/v1/stops?active=false").json["items"].values().count())
        assertEquals(0, w.get("/v1/stops?active=true").json["items"].values().count())
        val old = w.get("/v1/stops/$id?revision=1")
        assertEquals(200, old.status, old.text); assertTrue(old.json["active"].asBoolean()); assertTrue(old.json["valid_to"].asString().startsWith("2026-05-31T22:00"))
        // vor der Deaktivierung war er noch buchbar
        assertEquals(1, w.get("/v1/stops?service_id=$svc&at=2026-05-01T10:00:00%2B02:00", w.passenger).json["items"].values().count())
    }

    @Test
    fun verkehrsprodukt_des_dienstes_muss_zugelassen_sein() {
        val w = GeoWorld(); val svc = w.service()
        w.put("/v1/services/$svc/operating-hours", hours, "0")
        val line = w.post("/v1/stops", stop(name = "Linie", products = """["LINE_ON_DEMAND"]""")); assertEquals(201, line.status, line.text)
        val area = w.post("/v1/stops", stop(name = "Gebiet", products = """["AREA_ON_DEMAND","LINE_ON_DEMAND"]""")); assertEquals(201, area.status, area.text)
        val names = w.get("/v1/stops?service_id=$svc", w.passenger).json["items"].values().map { it["name"].asString() }
        assertEquals(listOf("Gebiet"), names)
        // Dienst ohne Dienstversion: kein Betrieb, keine Haltepunkte
        assertEquals(0, w.get("/v1/stops?service_id=${w.service()}", w.passenger).json["items"].values().count())
    }

    @Test
    fun ungueltige_eingaben_422_nichts_gespeichert_rechte_404_403() {
        val w = GeoWorld(); val other = GeoWorld()
        assertEquals(422, w.post("/v1/stops", stop(lat = "91")).status)
        val lon = w.post("/v1/stops", stop(lon = "-181")); assertEquals(422, lon.status, lon.text)
        val acc = w.post("/v1/stops", stop(acc = """{"schema_version":"1.0","step_free":"MAYBE","wheelchair_boarding":"YES"}""")); assertEquals(422, acc.status, acc.text)
        val noProduct = w.post("/v1/stops", stop(products = "[]")); assertEquals(422, noProduct.status, noProduct.text)
        assertEquals("/traffic_products", noProduct.json["violations"][0]["pointer"].asString())
        assertEquals(0, w.get("/v1/stops?active=false").json["items"].values().count()); assertEquals(0, w.get("/v1/stops").json["items"].values().count())
        assertEquals(403, w.post("/v1/stops", stop(), w.passenger).status)
        val id = w.post("/v1/stops", stop()).json["stop_id"].asString()
        assertEquals(409, w.post("/v1/stops", stop(stopId = id)).status)
        assertEquals(403, w.put("/v1/stops/$id", update(active = false, from = "2026-06-01T00:00:00+02:00"), "1", w.passenger).status)
        assertEquals(404, other.get("/v1/stops/$id").status); assertEquals(404, other.put("/v1/stops/$id", update(active = false, from = "2026-06-01T00:00:00+02:00"), "1").status)
        assertEquals(0, other.get("/v1/stops?active=false").json["items"].values().count())
        assertEquals(422, w.get("/v1/stops?near_latitude=51").status)
        assertEquals(1, w.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='geo.stop.changed' AND object_ref=? AND actor_ref=?", w.tenant.value, "stop:$id", "user:${w.adminSubject}"))
        assertEquals(1, w.count("SELECT count(*) FROM taktovia.stop_revision WHERE tenant_id=? AND approved_by=?", w.tenant.value, "user:${w.adminSubject}"))
        assertTrue(UUID.fromString(id).version() == 4)
    }
}

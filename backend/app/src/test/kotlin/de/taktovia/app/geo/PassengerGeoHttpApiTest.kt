package de.taktovia.app.geo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** WP-M2-40 / PAX-004 über die echte Anwendung: Fahrgastsicht auf Haltepunkte und Bediengebiet (HTTP-Form, Datentrennung). */
class PassengerGeoHttpApiTest {
    private val acc = """{"schema_version":"1.0","step_free":"YES","wheelchair_boarding":"UNVERIFIED"}"""
    private fun stop(name: String, lat: String) = """{"name":"$name","point":{"latitude":$lat,"longitude":13.74},"accessibility":$acc,"traffic_products":["AREA_ON_DEMAND"],"active":true,"valid_from":"2026-01-01T00:00:00+01:00"}"""
    private val square = """{"type":"MultiPolygon","coordinates":[[[[13.7,51.0],[13.8,51.0],[13.8,51.1],[13.7,51.1],[13.7,51.0]]]]}"""

    @Test
    fun fahrgastsicht_ohne_interne_felder_mit_entfernung_und_generalisiertem_gebiet() {
        val w = GeoWorld(); val svc = w.service()
        assertEquals(201, w.post("/v1/stops", stop("Marktplatz", "51.0501")).status)
        val other = GeoWorld()
        val list = w.get("/v1/stops?near_latitude=51.05&near_longitude=13.74&near_radius_m=500", w.passenger)
        assertEquals(200, list.status, list.text)
        val item = list.json["items"][0]
        assertEquals(setOf("stop_id", "revision", "name", "point", "accessibility", "distance_m"), item.propertyNames().toSet())
        assertFalse(item.has("approved_by")); assertEquals(11, item["distance_m"].asInt(), "ca. 11 m")
        assertNull(list.json["next_cursor"].takeIf { !it.isNull })
        // Pflege sieht weiterhin die volle Sicht
        assertEquals(true, w.get("/v1/stops").json["items"][0].has("approved_by"))
        // Gebiet: Fahrgastsicht ohne Zonenrolle/Revisions-ID, mit Fläche und Umgrenzung
        assertEquals(201, w.post("/v1/service-areas", """{"service_id":"$svc","area":$square,"valid_from":"2026-01-01T00:00:00+01:00","valid_to":null}""").status)
        val areas = w.get("/v1/service-areas?service_id=$svc", w.passenger)
        assertEquals(200, areas.status, areas.text)
        val a = areas.json["items"][0]
        assertEquals(setOf("id", "service_id", "revision", "area", "area_square_km", "bounds", "valid_from", "valid_to"), a.propertyNames().toSet())
        assertEquals(true, a["area_square_km"].asDouble() > 50.0)
        assertEquals(200, w.get("/v1/service-areas/${a["id"].asString()}", w.passenger).status)
        // fremder Mandant: nichts sichtbar, 404 auf fremde Haltepunkt-ID
        val id = w.get("/v1/stops").json["items"][0]["stop_id"].asString()
        assertEquals(0, other.get("/v1/stops", other.passenger).json["items"].size())
        assertEquals(404, other.get("/v1/stops/$id", other.passenger).status)
        assertEquals(0, other.get("/v1/service-areas", other.passenger).json["items"].size())
    }
}

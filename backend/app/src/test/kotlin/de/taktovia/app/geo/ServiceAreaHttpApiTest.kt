package de.taktovia.app.geo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/** WP-M2-04 / GEO-001 über die echte Anwendung: GeoJSON-Eingabe, 422 mit Feldverweis, ETag/If-Match, Revisionen, Rechte (403), Mandantentrennung (404), Audit. */
class ServiceAreaHttpApiTest {
    /** Koordinaten mit höchstens sechs Nachkommastellen (längere Ziffernfolgen hält der Kartendatenfilter SEC-008 für Kartennummern). */
    private fun f(v: Double) = "%.6f".format(java.util.Locale.ROOT, v)
    private fun square(x: Double, y: Double, s: Double = 0.1) = """{"type":"MultiPolygon","coordinates":[[[[${f(x)},${f(y)}],[${f(x + s)},${f(y)}],[${f(x + s)},${f(y + s)}],[${f(x)},${f(y + s)}],[${f(x)},${f(y)}]]]]}"""
    private fun create(service: String, area: String = square(13.7, 51.0), from: String = "2026-12-01T00:00:00+01:00", to: String? = null) =
        """{"service_id":"$service","area":$area,"valid_from":"$from","valid_to":${to?.let { "\"$it\"" } ?: "null"}}"""
    private fun update(area: String, from: String, to: String? = null) = """{"area":$area,"valid_from":"$from","valid_to":${to?.let { "\"$it\"" } ?: "null"}}"""

    @Test
    fun anlegen_lesen_revision_etag_und_vorbedingungen() {
        val w = GeoWorld(); val svc = w.service()
        val created = w.post("/v1/service-areas", create(svc))
        assertEquals(201, created.status, created.text)
        val id = created.json["id"].asString()
        assertEquals("\"1\"", created.header("ETag")); assertEquals("/v1/service-areas/$id", created.header("Location")); assertEquals(1, created.json["revision"].asInt())
        assertEquals("MultiPolygon", created.json["area"]["type"].asString())
        assertEquals(200, w.get("/v1/service-areas/$id").status)
        assertEquals(428, w.send("PUT", "/v1/service-areas/$id", update(square(13.8, 51.0), "2027-01-01T00:00:00+01:00")).status)
        assertEquals(412, w.put("/v1/service-areas/$id", update(square(13.8, 51.0), "2027-01-01T00:00:00+01:00"), "7").status)
        val r2 = w.put("/v1/service-areas/$id", update(square(13.8, 51.0), "2027-01-01T00:00:00+01:00"), "1")
        assertEquals(200, r2.status, r2.text); assertEquals(2, r2.json["revision"].asInt()); assertEquals(id, r2.json["id"].asString()); assertEquals("\"2\"", r2.header("ETag"))
        val old = w.get("/v1/service-areas/$id?revision=1")
        assertEquals(200, old.status, old.text); assertEquals(1, old.json["revision"].asInt()); assertTrue(old.json["valid_to"].asString().startsWith("2026-12-31T23:00"))
        assertEquals(2, w.get("/v1/service-areas/$id").json["revision"].asInt())
        val list = w.get("/v1/service-areas?service_id=$svc")
        assertEquals(1, list.json["items"].values().count(), list.text); assertEquals(2, list.json["items"][0]["revision"].asInt())
        assertEquals(0, w.get("/v1/service-areas?service_id=${UUID.randomUUID()}").json["items"].values().count())
        assertEquals(200, w.get("/v1/service-areas?service_id=$svc", w.passenger).status, "Fahrgäste sehen das Bediengebiet")
    }

    @Test
    fun ungueltige_geometrie_422_mit_feldverweis_nichts_gespeichert() {
        val w = GeoWorld(); val svc = w.service()
        val bow = w.post("/v1/service-areas", create(svc, area = """{"type":"MultiPolygon","coordinates":[[[[0,0],[1,1],[1,0],[0,1],[0,0]]]]}"""))
        assertEquals(422, bow.status, bow.text); assertEquals("/area", bow.json["violations"][0]["pointer"].asString())
        val open = w.post("/v1/service-areas", create(svc, area = """{"type":"MultiPolygon","coordinates":[[[[0,0],[1,0],[1,1],[0,1],[0,0.5]]]]}"""))
        assertEquals(422, open.status, open.text); assertEquals("/area/coordinates/0/0", open.json["violations"][0]["pointer"].asString())
        assertEquals(422, w.post("/v1/service-areas", create(svc, area = square(1_500_000.0, 6_700_000.0))).status)                  // falsches Koordinatensystem
        assertEquals(422, w.post("/v1/service-areas", create(svc, area = """{"type":"MultiPolygon","crs":{"type":"name"},"coordinates":[]}""")).status)
        assertEquals(422, w.post("/v1/service-areas", create(svc, to = "2026-11-01T00:00:00+01:00")).status)                          // valid_to <= valid_from
        assertEquals(0, w.get("/v1/service-areas?service_id=$svc").json["items"].values().count())
    }

    @Test
    fun ueberlappung_409_rechte_403_fremder_mandant_404_audit_je_aenderung() {
        val w = GeoWorld(); val other = GeoWorld(); val svc = w.service()
        val a = w.post("/v1/service-areas", create(svc, from = "2026-12-01T00:00:00+01:00", to = "2027-06-01T00:00:00+02:00"))
        assertEquals(201, a.status, a.text); val id = a.json["id"].asString()
        val overlap = w.post("/v1/service-areas", create(svc, area = square(14.0, 51.0), from = "2027-01-01T00:00:00+01:00"))
        assertEquals(409, overlap.status, overlap.text)
        assertEquals(403, w.post("/v1/service-areas", create(svc), w.passenger).status)
        assertEquals(403, w.put("/v1/service-areas/$id", update(square(13.7, 51.0), "2027-06-01T00:00:00+02:00"), "1", w.passenger).status)
        assertEquals(404, other.get("/v1/service-areas/$id").status)
        assertEquals(404, other.put("/v1/service-areas/$id", update(square(13.7, 51.0), "2027-06-01T00:00:00+02:00"), "1").status)
        assertEquals(404, other.post("/v1/service-areas", create(svc)).status)
        assertEquals(0, other.get("/v1/service-areas?service_id=$svc").json["items"].values().count())
        val r2 = w.put("/v1/service-areas/$id", update(square(13.7, 51.0, 0.2), "2027-06-01T00:00:00+02:00"), "1", w.planner)
        assertEquals(200, r2.status, r2.text)
        assertEquals(1, w.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='geo.service_area.changed' AND object_ref=? AND actor_ref=? AND redacted_changes->>'change'='created'", w.tenant.value, "service_area:$id", "user:${w.adminSubject}"))
        assertEquals(1, w.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='geo.service_area.changed' AND object_ref=? AND actor_ref=? AND redacted_changes->>'change'='revised'", w.tenant.value, "service_area:$id", "user:${w.plannerSubject}"))
        assertFalse(w.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='geo.service_area.changed' AND redacted_changes::text LIKE '%MultiPolygon%'", w.tenant.value) > 0, "keine Geometrie im Audit")
    }
}

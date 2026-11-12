package de.taktovia.app.geo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** WP-M2-06 / GEO-003 über die echte Anwendung: Zonenrolle am Bediengebiet, erlaubte Zielpunkte, Relationsprüfung („Testrelation mit nicht erlaubtem Ziel wird abgelehnt“), Rechte, Audit. */
class RelationRuleHttpApiTest {
    private fun f(v: Double) = "%.6f".format(java.util.Locale.ROOT, v)
    private fun square(x: Double, y: Double, s: Double = 0.1) = """{"type":"MultiPolygon","coordinates":[[[[${f(x)},${f(y)}],[${f(x + s)},${f(y)}],[${f(x + s)},${f(y + s)}],[${f(x)},${f(y + s)}],[${f(x)},${f(y)}]]]]}"""
    private fun area(svc: String, x: Double, role: String?) =
        """{"service_id":"$svc","area":${square(x, 51.0)},"valid_from":"2026-01-01T00:00:00+01:00","valid_to":null${role?.let { ",\"zone_role\":\"$it\"" } ?: ""}}"""
    private fun stop(name: String, lon: Double, lat: Double) =
        """{"name":"$name","point":{"latitude":${f(lat)},"longitude":${f(lon)}},"accessibility":{"schema_version":"1.0","step_free":"YES","wheelchair_boarding":"UNVERIFIED"},"traffic_products":["AREA_ON_DEMAND"],"valid_from":"2026-01-01T00:00:00+01:00"}"""
    private fun rule(ids: List<String>, reason: String = "Zielpunkte", from: String = "2026-01-01T00:00:00+01:00") =
        """{"destination_stop_ids":[${ids.joinToString(",") { "\"$it\"" }}],"valid_from":"$from","valid_to":null,"reason":"$reason"}"""
    private fun check(svc: String, to: String, from: String = "origin_latitude=51.05&origin_longitude=13.05") =
        "/v1/services/$svc/relation-check?$from&$to&at=2026-06-01T10:00:00%2B02:00"

    @Test
    fun testrelation_mit_nicht_erlaubtem_ziel_wird_abgelehnt_zonen_und_zielpunkte() {
        val w = GeoWorld(); val svc = w.service()
        assertEquals("BOTH", w.post("/v1/service-areas", area(svc, 12.0, null)).json["zone_role"].asString(), "Standardrolle")
        // BOTH-Zone ersetzen: eigener Dienst mit getrennten Zonen
        val s = w.service()
        val origin = w.post("/v1/service-areas", area(s, 13.0, "ORIGIN")); assertEquals(201, origin.status, origin.text); assertEquals("ORIGIN", origin.json["zone_role"].asString())
        assertEquals(201, w.post("/v1/service-areas", area(s, 14.0, "DESTINATION")).status)
        assertEquals(409, w.post("/v1/service-areas", area(s, 15.0, "ORIGIN")).status, "zweite wirksame Startzone")
        assertEquals(422, w.post("/v1/service-areas", area(s, 15.0, "ZIEL")).status)
        val ok = w.get(check(s, "destination_latitude=51.05&destination_longitude=14.05"), w.passenger)
        assertEquals(200, ok.status, ok.text); assertTrue(ok.json["admissible"].asBoolean()); assertTrue(ok.json["reason"].isNull)
        // Zielpunktmenge: nur Ziel 1 erlaubt
        val z1 = w.post("/v1/stops", stop("Ziel 1", 14.02, 51.02)).json["stop_id"].asString(); val z2 = w.post("/v1/stops", stop("Ziel 2", 14.05, 51.05)).json["stop_id"].asString()
        val put = w.put("/v1/services/$s/relation-rules", rule(listOf(z1)), "0"); assertEquals(200, put.status, put.text); assertEquals(1, put.json["revision"].asInt()); assertEquals("\"1\"", put.header("ETag"))
        assertEquals(listOf(z1), w.get("/v1/services/$s/relation-rules", w.passenger).json["destination_stop_ids"].values().map { it.asString() })
        assertTrue(w.get(check(s, "destination_latitude=51.0&destination_longitude=14.0&destination_stop_id=$z1"), w.passenger).json["admissible"].asBoolean())
        val denied = w.get(check(s, "destination_latitude=51.0&destination_longitude=14.0&destination_stop_id=$z2"), w.passenger)
        assertEquals(200, denied.status, denied.text); assertEquals(false, denied.json["admissible"].asBoolean()); assertEquals("RULE_FORBIDDEN", denied.json["reason"].asString())
        val outside = w.get(check(s, "destination_latitude=40.0&destination_longitude=20.0"), w.passenger)
        assertEquals("OUTSIDE_AREA", outside.json["reason"].asString())
        // Rückrichtung unabhängig: Start in der Zielzone ist nicht erlaubt
        assertEquals("OUTSIDE_AREA", w.get(check(s, "destination_latitude=51.05&destination_longitude=13.05", from = "origin_latitude=51.05&origin_longitude=14.05"), w.passenger).json["reason"].asString())
        // Deaktivierung des erlaubten Zielpunkts: nicht mehr zulässig
        val stopDoc = w.get("/v1/stops/$z1").json
        val off = w.put("/v1/stops/$z1", """{"name":"Ziel 1","point":{"latitude":51.02,"longitude":14.02},"accessibility":{"schema_version":"1.0","step_free":"YES","wheelchair_boarding":"UNVERIFIED"},"traffic_products":["AREA_ON_DEMAND"],"active":false,"valid_from":"2026-05-01T00:00:00+02:00"}""", stopDoc["revision"].asString())
        assertEquals(200, off.status, off.text)
        assertEquals("RULE_FORBIDDEN", w.get(check(s, "destination_latitude=51.0&destination_longitude=14.0&destination_stop_id=$z1"), w.passenger).json["reason"].asString())
        // Eingabefehler der Prüfung
        assertEquals(422, w.get("/v1/services/$s/relation-check?origin_latitude=51&origin_longitude=13", w.passenger).status, "Ziel fehlt")
        assertEquals(422, w.get("/v1/services/$s/relation-check?origin_latitude=95&origin_longitude=13&destination_latitude=51&destination_longitude=14&destination_stop_id=$z1", w.passenger).status)
    }

    @Test
    fun regelpflege_vorbedingungen_rechte_fremder_mandant_404_audit() {
        val w = GeoWorld(); val other = GeoWorld(); val s = w.service(); val path = "/v1/services/$s/relation-rules"
        val z = w.post("/v1/stops", stop("Ziel", 14.02, 51.02)).json["stop_id"].asString()
        assertEquals(0, w.get(path).json["revision"].asInt()); assertEquals("\"0\"", w.get(path).header("ETag"))
        assertEquals(428, w.send("PUT", path, rule(listOf(z))).status)
        assertEquals(412, w.put(path, rule(listOf(z)), "5").status)
        val bad = w.put(path, rule(listOf("00000000-0000-4000-8000-000000000099")), "0"); assertEquals(422, bad.status, bad.text)
        assertEquals("/destination_stop_ids/0", bad.json["violations"][0]["pointer"].asString())
        assertEquals(422, w.put(path, rule(listOf(z), reason = ""), "0").status)
        assertEquals(403, w.put(path, rule(listOf(z)), "0", w.passenger).status)
        assertEquals(404, other.get(path).status); assertEquals(404, other.put(path, rule(emptyList()), "0").status)
        assertEquals(404, other.get("/v1/services/$s/relation-check?origin_latitude=51&origin_longitude=13&destination_latitude=51&destination_longitude=14", other.passenger).status)
        assertEquals(200, w.put(path, rule(listOf(z), reason = "Pilot Nordkreis"), "0", w.planner).status)
        assertEquals(409, w.put(path, rule(emptyList(), from = "2025-12-01T00:00:00+01:00"), "1").status, "beginnt vor der offenen Vorgängerversion: überlappend")
        assertEquals(1, w.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='geo.relation_rule.published' AND actor_ref=? AND redacted_changes->>'reason'=?", w.tenant.value, "user:${w.plannerSubject}", "Pilot Nordkreis"))
    }
}

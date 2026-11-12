package de.taktovia.app.geo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** WP-M2-03 über die echte Anwendung (Test-Issuer statt Keycloak): Betriebszeiten pflegen, lesen, versionieren, Betriebsstatus, Rechte, Mandantentrennung, Audit. */
class ServiceOperatingHoursApiTest {
    private fun body(from: String = "2026-01-01T00:00:00+01:00", to: String? = null, reason: String = "Fahrplan", days: String = """["MON","TUE","WED","THU","FRI","SAT","SUN"]""",
                     fromT: String = "06:00", toT: String = "22:00", exceptions: String = "[]") =
        """{"traffic_product":"AREA_ON_DEMAND","weekly_hours":[{"weekdays":$days,"from":"$fromT","to":"$toT"}],"exceptions":$exceptions,"valid_from":"$from","valid_to":${to?.let { "\"$it\"" } ?: "null"},"reason":"$reason"}"""

    @Test
    fun pflege_lesen_revision_etag_und_vorbedingungen() {
        val w = GeoWorld(); val svc = w.service(); val path = "/v1/services/$svc/operating-hours"
        val empty = w.get(path)
        assertEquals(200, empty.status, empty.text); assertEquals(0, empty.json["revision"].asInt()); assertTrue(empty.json["weekly_hours"].isNull); assertEquals("\"0\"", empty.header("ETag"))
        assertEquals(428, w.send("PUT", path, body(), extra = emptyMap()).status)                       // fehlender If-Match
        val first = w.put(path, body(), "0")
        assertEquals(200, first.status, first.text); assertEquals(1, first.json["revision"].asInt()); assertEquals("\"1\"", first.header("ETag")); assertEquals("Europe/Berlin", first.json["timezone"].asString())
        assertEquals(412, w.put(path, body(from = "2026-06-01T00:00:00+02:00"), "0").status)           // veraltet
        val second = w.put(path, body(from = "2026-06-01T00:00:00+02:00", reason = "Sommer", toT = "23:00"), "1")
        assertEquals(200, second.status, second.text); assertEquals(2, second.json["revision"].asInt())
        val head = w.get(path); assertEquals(2, head.json["revision"].asInt()); assertEquals("\"2\"", head.header("ETag"))
        val list = w.get("$path/revisions")
        assertEquals(200, list.status, list.text); assertEquals(listOf(2, 1), list.json["items"].values().map { it["revision"].asInt() })
        assertTrue(list.json["items"][1]["valid_to"].asString().startsWith("2026-05-31T22:00"), "Vorgänger endet mit dem Beginn der neuen Version")
        assertEquals(listOf(1), w.get("$path/revisions?before_revision=2").json["items"].values().map { it["revision"].asInt() })
        val firstPage = w.get("$path/revisions?limit=1")
        assertEquals(listOf(2), firstPage.json["items"].values().map { it["revision"].asInt() }); assertEquals(2, firstPage.json["next_before_revision"].asInt())
    }

    @Test
    fun ungueltige_eingaben_422_ueberlappung_409() {
        val w = GeoWorld(); val svc = w.service(); val path = "/v1/services/$svc/operating-hours"
        val bad = w.put(path, body(fromT = "25:61"), "0"); assertEquals(422, bad.status, bad.text)
        assertEquals("/weekly_hours/0/from", bad.json["violations"][0]["pointer"].asString())
        assertEquals(422, w.put(path, body(reason = ""), "0").status)
        assertEquals(422, w.put(path, body(to = "2025-01-01T00:00:00+01:00"), "0").status)               // valid_to vor valid_from
        assertEquals(422, w.put(path, body().replace("Europe/Berlin", "x").replace("weekdays", "wochentage"), "0").status)
        assertEquals(0, w.get(path).json["revision"].asInt(), "nichts gespeichert")
        // ungültige Zeitzone am Dienst bleibt 422 (Dienstkopf)
        assertEquals(422, w.env.post("/v1/services", """{"operator_id":"${w.operator}","name":"X","timezone":"Mars/Olympus"}""", w.admin, extra = w.idem()).status)
        assertEquals(200, w.put(path, body(from = "2026-06-01T00:00:00+02:00", to = "2026-09-01T00:00:00+02:00"), "0").status)
        val overlap = w.put(path, body(from = "2026-05-01T00:00:00+02:00", to = "2026-07-01T00:00:00+02:00"), "1")
        assertEquals(409, overlap.status, overlap.text)
        assertEquals(1, w.get(path).json["revision"].asInt())
    }

    @Test
    fun zwei_parallele_aktivierungen_desselben_zeitraums_genau_eine_gewinnt() {
        val w = GeoWorld(); val svc = w.service(); val path = "/v1/services/$svc/operating-hours"
        val pool = Executors.newFixedThreadPool(2); val go = CountDownLatch(1)
        try {
            val futures = (1..2).map { n -> pool.submit<Int> { go.await(); w.put(path, body(from = "2026-03-01T00:00:00+01:00", to = "2026-09-01T00:00:00+02:00", reason = "Antrag $n"), "0").status } }
            go.countDown()
            val statuses = futures.map { it.get(30, TimeUnit.SECONDS) }.sorted()
            assertEquals(listOf(200, 412), statuses)
            assertEquals(1, w.get(path).json["revision"].asInt())
        } finally { pool.shutdownNow() }
    }

    @Test
    fun betriebsstatus_ohne_hinterlegte_zeit_ausser_betrieb_und_dst_tag_mit_beiden_offsets() {
        val w = GeoWorld(); val svc = w.service()
        val none = w.get("/v1/services/$svc/availability?at=2026-12-01T10:00:00%2B01:00", w.passenger)
        assertEquals(200, none.status, none.text); assertFalse(none.json["in_operation"].asBoolean()); assertTrue(none.json["next_opening"].isNull)
        assertEquals(200, w.put("/v1/services/$svc/operating-hours", body(days = """["SUN"]""", fromT = "02:00", toT = "04:00", from = "2026-01-01T00:00:00+01:00"), "0").status)
        // 25.10.2026 ist ein Sonntag; 02:30 kommt zweimal vor
        val summer = w.get("/v1/services/$svc/availability?at=2026-10-25T02:30:00%2B02:00", w.passenger)
        val winter = w.get("/v1/services/$svc/availability?at=2026-10-25T02:30:00%2B01:00", w.passenger)
        assertTrue(summer.json["in_operation"].asBoolean(), summer.text); assertTrue(winter.json["in_operation"].asBoolean(), winter.text)
        assertFalse(w.get("/v1/services/$svc/availability?at=2026-10-26T02:30:00%2B01:00", w.passenger).json["in_operation"].asBoolean())   // Montag
        // Fahrgäste sehen keine internen Felder
        assertEquals(setOf("service_id", "at", "in_operation", "next_opening", "timezone", "traffic_product"), summer.json.propertyNames().toSet())
        assertEquals(422, w.get("/v1/services/$svc/availability?at=2026-10-25T02:30:00", w.passenger).status)   // ohne Offset
    }

    @Test
    fun rechte_fremder_mandant_404_fahrgast_403_audit_mit_akteur_und_grund() {
        val w = GeoWorld(); val other = GeoWorld(); val svc = w.service(); val path = "/v1/services/$svc/operating-hours"
        assertEquals(404, other.get(path).status); assertEquals(404, other.put(path, body(), "0").status); assertEquals(404, other.get("/v1/services/$svc/availability", other.passenger).status)
        assertEquals(403, w.put(path, body(), "0", w.passenger).status); assertEquals(403, w.get(path, w.passenger).status)
        // Disponent: Leserecht (services:read), kein Schreibrecht; ohne Dienst im Dienstscope ist der Dienst für ihn wie nicht vorhanden, mit Scope lesbar
        val dispatcher = UUID.randomUUID(); w.env.grant(w.tenant, dispatcher, "dispatcher"); val scoped = UUID.randomUUID(); w.env.grant(w.tenant, scoped, "dispatcher", listOf(UUID.fromString(svc)))
        assertEquals(403, w.put(path, body(), "0", w.env.token(dispatcher)).status)
        assertEquals(404, w.get(path, w.env.token(dispatcher)).status)
        assertEquals(200, w.get(path, w.env.token(scoped)).status)
        assertEquals(403, w.put(path, body(), "0", w.env.token(scoped)).status)
        // Planer mit Stammdatenrecht pflegt; Akteur und Grund stehen im Audit, das Ereignis in der Outbox
        val r = w.put(path, body(reason = "Pilotstart Nordkreis"), "0", w.planner)
        assertEquals(200, r.status, r.text)
        assertEquals(1, w.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='geo.service_revision.published' AND actor_ref=? AND redacted_changes->>'reason'=?",
            w.tenant.value, "user:${w.plannerSubject}", "Pilotstart Nordkreis"))
        assertEquals(1, w.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND event_type='service.revision_published' AND aggregate_id=?", w.tenant.value, UUID.fromString(svc)))
        assertEquals(0, w.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='geo.service_revision.published' AND actor_ref=?", w.tenant.value, "user:$dispatcher"))
    }
}

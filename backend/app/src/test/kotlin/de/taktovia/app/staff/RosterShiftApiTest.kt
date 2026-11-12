package de.taktovia.app.staff

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/** WP-M4-03 über die echte Anwendung (HTTP, Runtime-Rolle, Test-Issuer): `/v1/rosters/shifts` mit ETag/If-Match, 404/412/422/428, Rechten und Mandantentrennung. */
class RosterShiftApiTest {
    private val w by lazy { ShiftHttpWorld() }

    @Test
    fun mitternachtsschicht_anlegen_lesen_und_etag() {
        val c = w.post(w.body(extra = ""","break_plan":{"schema_version":"1.0","breaks":[{"offset_min":240,"duration_min":30,"paid":false}]}"""))
        assertEquals(201, c.status, c.text); assertEquals("\"1\"", c.header("ETag"))
        val id = c.json["id"].asString()
        assertEquals("2026-12-01", c.json["date"].asString()); assertEquals(480, c.json["duration_min"].asInt()); assertEquals("2026-12-02T06:00", c.json["ends_local"].asString())
        assertEquals(2, c.json["staffing_need"].asInt()); assertEquals("DRAFT", c.json["state"].asString()); assertTrue(c.json["driver_ref"].isNull)
        val r = w.get("/v1/rosters/shifts/$id?roster_id=${w.roster}")
        assertEquals(200, r.status, r.text); assertEquals((c.json as tools.jackson.databind.node.ObjectNode).deepCopy().without("conflicts"), r.json, "Lesen prüft keine Konflikte; Anlegen liefert die Hinweise")
        val l = w.get("/v1/rosters/shifts?roster_id=${w.roster}")
        assertEquals(listOf(id), l.json["items"].values().map { it["id"].asString() })
        assertEquals(1, w.g.count("SELECT count(*) FROM taktovia.audit_event WHERE action = 'staff.roster_shift.created' AND tenant_id = ?", w.g.tenant.value))
    }

    @Test
    fun fehler_422_404_412_428_403() {
        assertEquals(422, w.post(w.body("2026-12-02T06:00:00", "2026-12-01T22:00:00")).status)
        val gap = w.post(w.body("2027-03-28T02:30:00", "2027-03-28T10:00:00")); assertEquals(422, gap.status); assertEquals("LOCAL_TIME_NONEXISTENT", gap.json["violations"][0]["code"].asString())
        assertEquals("LOCAL_TIME_AMBIGUOUS", w.post(w.body("2026-10-25T02:30:00", "2026-10-25T10:00:00")).json["violations"][0]["code"].asString())
        assertEquals(404, w.post(w.body(depotId = UUID.randomUUID().toString())).status)
        val id = w.post(w.body()).json["id"].asString()
        assertEquals(428, w.put(id, w.body(need = 3), null).status)
        assertEquals(412, w.put(id, w.body(need = 3), "9").status)
        val ok = w.put(id, w.body(need = 3), "1"); assertEquals(200, ok.status, ok.text); assertEquals("\"2\"", ok.header("ETag")); assertEquals(3, ok.json["staffing_need"].asInt())
        assertEquals(403, w.post(w.body(), w.dispatcherToken).status); assertEquals(403, w.get("/v1/rosters/shifts?roster_id=${w.roster}", w.dispatcherToken).status)
        assertEquals(200, w.get("/v1/rosters/shifts?roster_id=${w.roster}", w.hrToken).status)
        assertEquals(404, w.get("/v1/rosters/shifts/$id?roster_id=${UUID.randomUUID()}").status)
        // anderer Mandant sieht die Schicht nicht
        val other = ShiftHttpWorld()
        assertEquals(404, other.get("/v1/rosters/shifts/$id?roster_id=${w.roster}").status)
    }
}

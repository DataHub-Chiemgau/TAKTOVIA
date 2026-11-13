package de.taktovia.app.fleet

import de.taktovia.app.identity.IdentityEnv
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * LH-6.22-DMG-005 / WP-M3-45 (Triage) und DMG-006 / WP-M3-46 (Sofortsperre, priorisierter Alarm) über die echte Anwendung: `POST /v1/defects/{id}/triage` mit `If-Match` (428/412), 403 ohne
 * Rolle und bei Herabstufung durch die Disposition, 422 mit Feldverweis ohne Grund, 404 für fremde Akten; Sperre im selben Zug, Alarm in der Arbeitsliste der Leitstelle mit Fallnummer und Zahl der
 * betroffenen Fahrten (ohne Fahrgastdaten), Aktenhistorie mit Klassifikation, Sperre und Zuweisung.
 */
class DefectTriageApiTest {
    private val env = IdentityEnv
    private fun idem() = mapOf("Idempotency-Key" to "k-" + UUID.randomUUID())

    private fun report(w: DefectApiTest.World, vehicle: UUID = w.vehicle, category: String = "BODY", extra: String = ""): Pair<String, String> {
        val r = env.post("/v1/defects", w.body(vehicle = vehicle, category = category, extra = extra), w.dispatcherToken, null, idem()); assertEquals(201, r.status, r.text)
        return r.json["id"].asString() to r.header("ETag")!!
    }

    private fun triage(id: String, token: String, body: String, ifMatch: String? = null) =
        env.server.send("POST", "/v1/defects/$id/triage", body, env.headers(token, extra = idem() + (if (ifMatch != null) mapOf("If-Match" to ifMatch) else emptyMap())))

    @Test
    fun triage_klassifiziert_sperrt_und_zeigt_aktenhistorie() {
        val w = DefectApiTest.World(env); val (id, etag) = report(w)
        val first = env.get("/v1/defects/$id", w.workshopToken)
        assertEquals("COSMETIC", first.json["severity"].asString(), "Regelmatrix bei der Anlage"); assertEquals("dmg-klassen-v1", first.json["severity_rule_version"].asString())
        assertEquals("COSMETIC", first.json["suggested_severity"].asString()); assertFalse(first.json["blocks_vehicle"].asBoolean())
        val assignee = UUID.randomUUID()
        val r = triage(id, w.dispatcherToken, """{"severity":"SAFETY_CRITICAL","decision":"REVIEWED","assignee_ref":"$assignee","reason":"Riss im Rahmen"}""", etag)
        assertEquals(200, r.status, r.text)
        assertEquals("SAFETY_CRITICAL", r.json["severity"].asString()); assertEquals("REVIEWED", r.json["status"].asString()); assertEquals(assignee.toString(), r.json["assignee_ref"].asString())
        assertTrue(r.json["blocks_vehicle"].asBoolean()); assertEquals("\"${r.json["revision"].asLong()}\"", r.header("ETag"))
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.vehicle_block WHERE vehicle_id=? AND status='ACTIVE' AND source='SAFETY' AND reason_code='DEFECT_CRITICAL'", w.vehicle))
        // Aktenhistorie (AT-34, P1-Anteil): Klassifikation, Zuweisung, Sperre und Statuswechsel mit Akteur und Zeit
        val h = env.get("/v1/defects/$id/status-history", w.workshopToken)
        assertEquals(200, h.status, h.text)
        val kinds = h.json["timeline"].values().map { it["kind"].asString() }.toSet()
        assertEquals(setOf("STATUS", "CLASSIFICATION", "ASSIGNMENT", "VEHICLE_BLOCK"), kinds)
        assertTrue(h.json["timeline"].values().all { it["actor"].asString().startsWith("user:") && it["occurred_at"].asString().isNotEmpty() })
        assertEquals(403, env.get("/v1/defects/$id/status-history", w.driverToken).status)
    }

    @Test
    fun fehlerfaelle_428_412_403_422_404() {
        val w = DefectApiTest.World(env); val (id, etag) = report(w)
        val ok = """{"severity":"COSMETIC","decision":"REVIEWED"}"""
        assertEquals(428, triage(id, w.dispatcherToken, ok).status)
        assertEquals(412, triage(id, w.dispatcherToken, ok, "\"9\"").status)
        assertEquals(403, triage(id, w.driverToken, ok, etag).status)
        assertEquals(403, triage(id, w.passengerToken, ok, etag).status)
        assertEquals(403, triage(id, w.adminToken, ok, etag).status)
        val noReason = triage(id, w.dispatcherToken, """{"severity":"OPERATIONALLY_RELEVANT","decision":"REVIEWED"}""", etag)
        assertEquals(422, noReason.status); assertEquals("/reason", noReason.json["violations"][0]["pointer"].asString())
        assertEquals(422, triage(id, w.dispatcherToken, """{"severity":"KRITISCH","decision":"REVIEWED"}""", etag).status)
        assertEquals(422, triage(id, w.dispatcherToken, """{"severity":"COSMETIC","decision":"CLOSED"}""", etag).status)
        assertEquals(422, triage(id, w.dispatcherToken, """{"severity":"COSMETIC","decision":"REVIEWED","status":"CLOSED"}""", etag).status, "unbekannte Felder")
        assertEquals(404, triage(id, DefectApiTest.World(env).workshopToken, ok, etag).status)
        assertEquals(404, triage(UUID.randomUUID().toString(), w.workshopToken, ok, etag).status)
        assertEquals("NEW", env.string("SELECT status FROM taktovia.defect_case WHERE id=?", UUID.fromString(id)).single())
    }

    @Test
    fun herabstufung_nur_werkstatt_und_hebt_sperre_nicht_auf() {
        val w = DefectApiTest.World(env)
        // Meldung mit Bremsmangel: sicherheitskritisch nach der Regelmatrix, Fahrzeug sofort gesperrt (DMG-006, AT-28)
        val (id, etag) = report(w, category = "BRAKES", extra = ""","kind":"MALFUNCTION","vehicle_area":"WHEELS_TIRES","observed_effect":"NOT_ROADWORTHY"""")
        val g = env.get("/v1/defects/$id", w.dispatcherToken)
        assertEquals("SAFETY_CRITICAL", g.json["severity"].asString()); assertTrue(g.json["blocks_vehicle"].asBoolean())
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.vehicle_block WHERE vehicle_id=? AND status='ACTIVE'", w.vehicle))
        val down = """{"severity":"COSMETIC","decision":"REVIEWED","reason":"Sensorfehler, Bremsprobe in Ordnung"}"""
        assertEquals(403, triage(id, w.dispatcherToken, down, etag).status)
        val r = triage(id, w.workshopToken, down, etag)
        assertEquals(200, r.status, r.text); assertEquals("COSMETIC", r.json["severity"].asString())
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.vehicle_block WHERE vehicle_id=? AND status='ACTIVE'", w.vehicle), "Herabstufung hebt die Sperre nicht auf")
        assertTrue(r.json["blocks_vehicle"].asBoolean())
    }

    @Test
    fun kritische_meldung_alarmiert_die_leitstelle_priorisiert_ohne_fahrgastdaten() {
        val w = DefectApiTest.World(env)
        val (id, _) = report(w, category = "TIRES_WHEELS", extra = ""","kind":"CRACK_BREAK","vehicle_area":"WHEELS_TIRES","observed_effect":"FUNCTION_LIMITED"""")
        val case = env.get("/v1/defects/$id", w.dispatcherToken).json["case_number"].asString()
        val list = env.get("/v1/operator/alerts", w.dispatcherToken)
        assertEquals(200, list.status, list.text)
        val alert = list.json["items"].values().firstOrNull { it["ref_id"].asString() == id }
        assertNotNull(alert, list.text)
        assertEquals("VEHICLE_BLOCK_CRITICAL", alert!!["kind"].asString()); assertEquals("CRITICAL", alert["priority"].asString()); assertEquals(case, alert["case_number"].asString())
        assertEquals(w.vehicle.toString(), alert["vehicle_id"].asString()); assertEquals(0, alert["affected_run_count"].asInt())
        assertEquals(1, alert["escalation_level"].asInt(), "kritisch: ohne Eskalationsverzögerung")
        assertFalse(alert.toString().contains("Kratzer"), "keine Beschreibung/Diagnose im Alarm")
        assertEquals(403, env.get("/v1/operator/alerts", w.driverToken).status)
    }
}

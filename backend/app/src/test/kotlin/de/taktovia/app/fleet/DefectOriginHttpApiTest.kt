package de.taktovia.app.fleet

import de.taktovia.app.identity.IdentityEnv
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * WP-M3-20 über die echte Anwendung (HTTP, Runtime-Rolle, Test-Issuer statt Keycloak): `POST /v1/defects` mit Herkunft Prüfschritt, wiederholtes Auslösen (200, dieselbe Akte), Gegenreferenz in
 * `GET /v1/inspections/{id}` (`defect_refs`) und `GET /v1/defects/{id}` (`source_*`), 422 am Feld, 404 für fremden Mandanten, Fahrer ohne Bezug und fremden Lauf.
 */
class DefectOriginHttpApiTest {
    private val env = IdentityEnv
    private fun idem(k: String = "k-" + UUID.randomUUID()) = mapOf("Idempotency-Key" to k)

    private class Started(val w: InspectionApiTest.World, val id: String, val vehicle: UUID)

    private fun started(): Started {
        val w = InspectionApiTest.World(env); w.template("PKW")
        // Fahrerbezug der Schadenmeldung (Annahme OP-34, wie `DefectApiTest`): `driver_ref` der Schicht ist die IdP-Kennung des Fahrers
        val vehicle = w.vehicle("PKW"); val shift = w.shift(driver = w.account, vehicle = vehicle)
        val c = env.post("/v1/inspections", w.body(shift, vehicle), w.token, null, idem()); check(c.status == 201) { c.text }
        return Started(w, c.json["id"].asString(), vehicle)
    }

    private fun answer(step: String, status: String) = """{"step_code":"$step","answer":"$status",${if (status == "DEFECT") """"note":"Riss",""" else ""}"answered_at":"${Instant.now().minusSeconds(20)}"}"""

    /** Entwurf mit negativem Schritt S2 (nicht kritisch) über die API. */
    private fun draft(s: Started) {
        val r = env.server.send("PUT", "/v1/inspections/${s.id}", """{"answers":[${answer("S2", "DEFECT")},${answer("S3", "OK")}]}""", env.headers(s.w.token, extra = idem() + mapOf("If-Match" to "\"1\"")), "application/json")
        check(r.status == 200) { r.text }
    }

    private fun report(s: Started, step: String = "S2", token: String = s.w.token, inspection: String = s.id, key: String = "k-" + UUID.randomUUID()) = env.post("/v1/defects",
        """{"vehicle_id":"${s.vehicle}","occurred_at":"${Instant.now().minusSeconds(300)}","category_code":"GLASS","description":"Riss in der Scheibe","source_inspection_id":"$inspection","source_step_code":"$step","confirm_new_despite_known":true}""",
        token, null, idem(key))

    @Test
    fun mangel_melden_aus_negativem_pruefschritt_beidseitig_navigierbar_und_wiederholbar() {
        val s = started(); draft(s)
        val first = report(s); assertEquals(201, first.status, first.text)
        assertEquals(s.id, first.json["source_inspection_id"].asString()); assertEquals("S2", first.json["source_step_code"].asString()); assertEquals("NEW", first.json["status"].asString())
        assertTrue(Regex("^SCH-\\d{4}-\\d{6}$").matches(first.json["case_number"].asString()))
        // wiederholtes Auslösen (anderer Schlüssel, anderes Gerät): dieselbe Akte, 200
        val again = report(s); assertEquals(200, again.status, again.text); assertEquals(first.json["id"].asString(), again.json["id"].asString())
        // Prüfung -> Akte und Akte -> Prüfschritt
        val inspection = env.get("/v1/inspections/${s.id}", s.w.token); assertEquals(200, inspection.status, inspection.text)
        assertEquals(1, inspection.json["defect_refs"].size()); assertEquals("S2", inspection.json["defect_refs"][0]["step_code"].asString())
        assertEquals(first.json["id"].asString(), inspection.json["defect_refs"][0]["defect_id"].asString()); assertEquals(first.json["case_number"].asString(), inspection.json["defect_refs"][0]["case_number"].asString())
        val defect = env.get("/v1/defects/${first.json["id"].asString()}", s.w.token); assertEquals(200, defect.status, defect.text)
        assertEquals(s.id, defect.json["source_inspection_id"].asString()); assertEquals("S2", defect.json["source_step_code"].asString()); assertTrue(defect.json["source_incident_id"].isNull)
        // die Disposition sieht dieselbe Verknüpfung
        assertEquals(1, env.get("/v1/inspections/${s.id}", s.w.dispatcherToken).json["defect_refs"].size())
    }

    @Test
    fun feldfehler_und_grenzen() {
        val s = started(); draft(s)
        // nicht negativ beantwortet (S3 = OK) und nie beantwortet: 422 am Schritt
        for (step in listOf("S3", "S1")) {
            val r = report(s, step); assertEquals(422, r.status, r.text); assertEquals("/source_step_code", r.json["violations"][0]["pointer"].asString(), step)
        }
        // Prüflauf ohne Schrittangabe: 422 am Feld; Prüfschritt und Incident zugleich: 422
        val noStep = env.post("/v1/defects", """{"vehicle_id":"${s.vehicle}","occurred_at":"${Instant.now().minusSeconds(60)}","category_code":"GLASS","description":"x","source_inspection_id":"${s.id}"}""", s.w.token, null, idem())
        assertEquals(422, noStep.status, noStep.text); assertEquals("/source_step_code", noStep.json["violations"][0]["pointer"].asString())
        // fremder Mandant, anderer Fahrer ohne Bezug, unbekannter Lauf: 404
        val other = started()
        assertEquals(404, report(s, token = other.w.token).status); assertEquals(404, report(s, token = s.w.otherToken).status); assertEquals(404, report(s, inspection = UUID.randomUUID().toString()).status)
        assertEquals(404, env.get("/v1/inspections/${s.id}", other.w.token).status)
    }
}

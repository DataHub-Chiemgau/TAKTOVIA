package de.taktovia.app.fleet

import de.taktovia.app.identity.IdentityEnv
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * WP-M3-17 / LH-6.21-CHK-008 über die echte Anwendung (HTTP, Runtime-Rolle, Test-Issuer statt Keycloak): unplausibler Kilometerstand und abweichende Fahrzeugidentität werden mit 422 und
 * Vorwert abgewiesen, `POST /v1/inspections/{id}/answers/{step}/justification` gibt begründet frei (nicht der Fahrer), der Abschluss übernimmt Akteur, Zeit und Begründung in den Nachweis.
 */
class ShiftStartValuesApiTest {
    private val env = IdentityEnv
    private fun idem(k: String = "k-" + UUID.randomUUID()) = mapOf("Idempotency-Key" to k)
    private fun im(rev: Long) = mapOf("If-Match" to "\"$rev\"")

    private class S(val w: InspectionApiTest.World, val id: String, val label: String, val vehicle: UUID)

    private fun started(): S {
        val w = InspectionApiTest.World(env)
        env.exec("INSERT INTO taktovia.qualification_revision(tenant_id,id,driver_id,qualification_code,revision,valid_from,approval_status,driver_seq) VALUES (?,?,?,'PBEFG',1,now() - interval '30 days','APPROVED',1)", w.tenant.value, UUID.randomUUID(), w.driverId)
        env.exec("UPDATE taktovia.driver SET qualification_seq = 1 WHERE id = ?", w.driverId)
        val steps = """[{"step_code":"ODO","title":"Kilometerstand","input_kind":"ODOMETER_KM","mandatory":true,"safety_critical":false},""" +
            """{"step_code":"ENERGY","title":"Tankstand","input_kind":"ENERGY_PCT","mandatory":true,"safety_critical":false},""" +
            """{"step_code":"IDENT","title":"Fahrzeugkennung","input_kind":"VEHICLE_IDENTITY","mandatory":true,"safety_critical":false}]"""
        val created = env.post("/v1/operator/checklist-templates", """{"name":"Start","vehicle_class":"PKW","service_mode":"STANDARD","steps":$steps}""", env.token(w.author), null, idem())
        check(created.status == 201) { created.text }
        val p = env.server.send("POST", "/v1/operator/checklist-templates/${created.json["id"].asString()}/publish", null, env.headers(env.token(w.reviewer), extra = idem() + im(1)), null)
        check(p.status == 200) { p.text }
        val vehicle = w.vehicle("PKW"); val label = "FZ-" + vehicle.toString().take(6).uppercase()
        env.exec("UPDATE taktovia.vehicle SET vehicle_label = ?, last_confirmed_odometer_km = 10000 WHERE id = ?", label, vehicle)
        val shift = w.shift(vehicle = vehicle)
        val c = env.post("/v1/inspections", w.body(shift, vehicle), w.token, null, idem())
        check(c.status == 201) { c.text }
        return S(w, c.json["id"].asString(), label, vehicle)
    }

    private fun ans(odo: String = "12000", energy: String = "60", ident: String) = """[{"step_code":"ODO","answer":"OK","number_value":$odo,"answered_at":"${Instant.now().minusSeconds(20)}"},""" +
        """{"step_code":"ENERGY","answer":"OK","number_value":$energy,"answered_at":"${Instant.now().minusSeconds(20)}"},""" +
        """{"step_code":"IDENT","answer":"OK","text_value":"$ident","answered_at":"${Instant.now().minusSeconds(20)}"}]"""

    private fun submit(s: S, answers: String, rev: Long) = env.server.send("POST", "/v1/inspections/${s.id}/submit",
        """{"answers":$answers,"client_submitted_at":"${Instant.now().minusSeconds(2)}"}""", env.headers(s.w.token, extra = idem() + im(rev)), "application/json")

    private fun justify(s: S, step: String, body: String, token: String, rev: Long) = env.server.send("POST", "/v1/inspections/${s.id}/answers/$step/justification", body, env.headers(token, extra = idem() + im(rev)), "application/json")

    @Test
    fun unplausible_werte_422_mit_vorwert_begruendete_freigabe_und_nachweis() {
        val s = started()
        // Kilometerstand unter dem Vorwert: 422 mit Verweis und Vorwert, nichts gespeichert
        val low = submit(s, ans(odo = "9000", ident = s.label), 1)
        assertEquals(422, low.status, low.text); assertEquals("/answers/ODO/number_value", low.json["violations"][0]["pointer"].asString())
        assertTrue(low.json["detail"].asString().contains("Vorwert 10000 km"), low.text)
        // Verwechslung und Energie außerhalb des Bereichs
        val wrong = submit(s, ans(ident = "FZ-FALSCH"), 1)
        assertEquals(422, wrong.status); assertEquals("/answers/IDENT/text_value", wrong.json["violations"][0]["pointer"].asString())
        assertEquals(422, submit(s, ans(energy = "101", ident = s.label), 1).status)
        assertEquals(0, env.count("SELECT count(*) FROM taktovia.inspection_answer WHERE tenant_id=? AND inspection_id=?", s.w.tenant.value, UUID.fromString(s.id)))
        // begründete Freigabe: Fahrer und Fahrgast nicht, leere Begründung nicht, Disposition ja (If-Match, Revision steigt)
        val body = """{"number_value":9000,"reason":"Tacho defekt, Leitstelle bestätigt"}"""
        assertEquals(403, justify(s, "ODO", body, s.w.token, 1).status)
        assertEquals(403, justify(s, "ODO", body, s.w.passengerToken, 1).status)
        assertEquals(422, justify(s, "ODO", """{"number_value":9000,"reason":"  "}""", s.w.dispatcherToken, 1).status)
        assertEquals(428, env.server.send("POST", "/v1/inspections/${s.id}/answers/ODO/justification", body, env.headers(s.w.dispatcherToken, extra = idem()), "application/json").status)
        assertEquals(412, justify(s, "ODO", body, s.w.dispatcherToken, 7).status)
        assertEquals(404, justify(s, "ODO", body, InspectionApiTest.World(env).dispatcherToken, 1).status)
        val ok = justify(s, "ODO", body, s.w.dispatcherToken, 1)
        assertEquals(200, ok.status, ok.text); assertEquals("\"2\"", ok.header("ETag"))
        val odo = ok.json["answers"].first { it["step_code"].asString() == "ODO" }
        assertEquals("BEGRUENDET_FREIGEGEBEN", odo["plausibility"].asString()); assertEquals("Tacho defekt, Leitstelle bestätigt", odo["justification"]["reason"].asString())
        assertTrue(odo["justification"]["justified_by"].asString().startsWith("user:"))
        // Abschluss mit dem freigegebenen Wert und plausibler Identität: Akteur, Zeit und Begründung stehen im Prüfnachweis; der Vorwert bleibt
        val done = submit(s, ans(odo = "9000", ident = s.label), 2)
        assertEquals(200, done.status, done.text); assertEquals("PASSED", done.json["outcome"].asString())
        val kept = done.json["answers"].first { it["step_code"].asString() == "ODO" }
        assertEquals(odo["justification"], kept["justification"]); assertEquals(10000, kept["previous_value"].asInt())
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.vehicle WHERE id = ? AND last_confirmed_odometer_km = 10000", s.vehicle))
        // später unverändert lesbar
        assertEquals(kept, env.get("/v1/inspections/${s.id}", s.w.dispatcherToken).json["answers"].first { it["step_code"].asString() == "ODO" })
    }

    @Test
    fun plausible_startwerte_werden_gespeichert_und_der_kilometerstand_fortgeschrieben() {
        val s = started()
        val done = submit(s, ans(odo = "10450", energy = "35.5", ident = s.label.lowercase()), 1)
        assertEquals(200, done.status, done.text)
        val a = done.json["answers"]
        assertEquals("PLAUSIBEL", a.first { it["step_code"].asString() == "ODO" }["plausibility"].asString()); assertEquals(s.label.lowercase(), a.first { it["step_code"].asString() == "IDENT" }["text_value"].asString())
        assertEquals(35.5, a.first { it["step_code"].asString() == "ENERGY" }["number_value"].asDouble(), 0.0001)
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.vehicle WHERE id = ? AND last_confirmed_odometer_km = 10450", s.vehicle))
    }
}

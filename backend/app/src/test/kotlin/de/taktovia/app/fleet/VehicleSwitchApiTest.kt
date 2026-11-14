package de.taktovia.app.fleet

import de.taktovia.app.contract.W14C2ContractCases
import de.taktovia.app.identity.IdentityEnv
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * WP-M3-22 / LH-6.21-CHK-015 über die echte Anwendung (HTTP, Runtime-Rolle, Test-Issuer statt Keycloak, **echte** Schichtzuweisung aus `staff-time`): Wechsel durch den Fahrer, Fahrerlauf
 * `GET /v1/driver/shifts/{id}/run` (neues Fahrzeug, „Prüfung erforderlich“, keine Aufträge), neue Prüfung des Ersatzfahrzeugs, danach Freigabe; Idempotenz, Rechte, fremde Schicht und fremder
 * Mandant (404), Überlappung des Ersatzfahrzeugs (409, echte Regel der Schichtzuweisung), Eingabefehler.
 */
class VehicleSwitchApiTest {
    private val env = IdentityEnv
    private fun idem(k: String = "k-" + UUID.randomUUID()) = mapOf("Idempotency-Key" to k)

    private fun switch(s: W14C2ContractCases.Setup, body: String = s.body(), token: String = s.w.token, key: Map<String, String> = idem(), shift: UUID = s.shift) =
        env.post("/v1/shifts/$shift/vehicle-switches", body, token, null, key)

    private fun answers() = (1..3).joinToString(",") { """{"step_code":"S$it","answer":"OK","answered_at":"${Instant.now().minusSeconds(20)}"}""" }

    /** Prüflauf starten und bestehen (Fahrer); liefert die Kennung des Laufs. */
    private fun inspect(s: W14C2ContractCases.Setup, vehicle: UUID, kind: String): String {
        val c = env.post("/v1/inspections", s.w.body(s.shift, vehicle, kind = kind), s.w.token, null, idem()); check(c.status == 201) { c.text }
        val id = c.json["id"].asString()
        val r = env.server.send("POST", "/v1/inspections/$id/submit", """{"answers":[${answers()}],"client_submitted_at":"${Instant.now().minusSeconds(2)}"}""",
            env.headers(s.w.token, extra = idem() + mapOf("If-Match" to "\"1\"")), "application/json")
        check(r.status == 200) { r.text }
        return id
    }

    @Test
    fun wechsel_fahrerlauf_neue_pruefung_dann_freigabe() {
        val s = W14C2ContractCases.Setup(); s.w.template("PKW")
        inspect(s, s.vehicle, "PRE_SHIFT")
        val before = env.get("/v1/driver/shifts/${s.shift}/run", s.w.token)
        assertEquals(200, before.status, before.text); assertEquals("RELEASED", before.json["release_state"].asString()); assertEquals(s.vehicle.toString(), before.json["vehicle_id"].asString())
        val revisionBefore = env.count("SELECT revision FROM taktovia.staff_shift WHERE id=?", s.shift)

        val key = idem()
        val c = switch(s, key = key)
        assertEquals(201, c.status, c.text)
        assertEquals(s.vehicle.toString(), c.json["from_vehicle_id"].asString()); assertEquals(s.replacement.toString(), c.json["to_vehicle_id"].asString())
        assertEquals("DRIVER", c.json["actor_role"].asString()); assertEquals(1, c.json["revoked_releases"].asInt()); assertTrue(c.json["inspection_required"].asBoolean())
        // Wiederholung mit demselben Schlüssel: dieselbe Antwort, kein zweiter Wechsel
        val again = switch(s, key = key)
        assertEquals(201, again.status, again.text); assertEquals("true", again.header("Idempotent-Replayed")); assertEquals(c.json["id"].asString(), again.json["id"].asString())
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.shift_vehicle_switch WHERE shift_id=?", s.shift))
        // echte Schichtzuweisung: Fahrzeug getauscht, Revision gezählt, Zustand und Fahrer unverändert, Schichtänderung im Audit
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.staff_shift WHERE id=? AND vehicle_id=? AND state='STARTED' AND revision=?", s.shift, s.replacement, revisionBefore + 1))
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='staff.shift.changed' AND object_ref=?", s.w.tenant.value, "staff_shift:${s.shift}"))
        // alte Freigabe widerrufen
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.resource_release WHERE shift_id=? AND vehicle_id=? AND revoked_at IS NOT NULL AND revoke_reason='VEHICLE_CHANGED'", s.shift, s.vehicle))

        // Fahrerlauf: neues Fahrzeug, nicht freigegeben, Prüfung erforderlich, keine Aufträge
        val run = env.get("/v1/driver/shifts/${s.shift}/run", s.w.token)
        assertEquals(200, run.status, run.text)
        assertEquals(s.replacement.toString(), run.json["vehicle_id"].asString()); assertEquals("NOT_RELEASED", run.json["release_state"].asString())
        assertEquals("NO_INSPECTION", run.json["missing_requirements"][0]["code"].asString()); assertEquals(0, run.json["stops"].size())
        // Leitstelle sieht die Historie, der Fahrer ebenfalls
        assertEquals(1, env.get("/v1/shifts/${s.shift}/vehicle-switches", s.w.dispatcherToken).json["items"].size()); assertEquals(1, env.get("/v1/shifts/${s.shift}/vehicle-switches", s.w.token).json["items"].size())

        // neue fahrzeugspezifische Prüfung des Ersatzfahrzeugs, danach Freigabe
        inspect(s, s.replacement, "VEHICLE_CHANGE")
        val after = env.get("/v1/driver/shifts/${s.shift}/run", s.w.token)
        assertEquals("RELEASED", after.json["release_state"].asString(), after.text); assertEquals(0, after.json["missing_requirements"].size())
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.resource_release WHERE shift_id=? AND vehicle_id=? AND revoked_at IS NULL", s.shift, s.replacement))
    }

    @Test
    fun negativfaelle_rechte_fremde_schicht_mandant_ueberlappung_und_eingaben() {
        val s = W14C2ContractCases.Setup()
        // Rechte: Fahrgast 403, ohne Anmeldung 401, anderer Fahrer 404, fremder Mandant 404, unbekannte Schicht 404
        assertEquals(403, switch(s, token = s.w.passengerToken).status)
        assertEquals(401, env.server.post("/v1/shifts/${s.shift}/vehicle-switches", s.body(), idem()).status)
        assertEquals(404, switch(s, token = s.w.otherToken).status)
        val o = W14C2ContractCases.Setup()
        assertEquals(404, switch(s, token = o.w.token).status); assertEquals(404, switch(s, token = o.w.dispatcherToken).status)
        assertEquals(404, switch(s, shift = UUID.randomUUID()).status)
        assertEquals(404, env.get("/v1/shifts/${s.shift}/vehicle-switches", o.w.dispatcherToken).status)
        // Eingabefehler: Pflichtfelder, unbekannte Felder, ungültiger Grund, OTHER ohne Hinweis, Tenant im Body (kein Eingabeweg)
        assertEquals(422, switch(s, body = """{"new_vehicle_id":"${s.replacement}","reason_code":"BREAKDOWN"}""").status)
        assertEquals(404, switch(s, body = s.body().dropLast(1) + ""","tenant_id":"${o.w.tenant.value}"}""").status, "ein Mandant im Body ist kein Eingabeweg")
        assertEquals(422, switch(s, body = s.body().dropLast(1) + ""","unbekannt":1}""").status)
        assertEquals(422, switch(s, body = s.body(reason = "WEIL_ICH_WILL")).status)
        assertEquals(422, switch(s, body = s.body(reason = "OTHER")).status)
        // Überlappung: das Ersatzfahrzeug fährt in derselben Zeit eine andere Schicht (echte Regel der Schichtzuweisung): 409, nichts geändert, Freigabe bleibt
        val other = s.w.shift(driver = s.w.otherDriverId, vehicle = s.replacement)
        val clash = switch(s)
        assertEquals(409, clash.status, clash.text)
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.staff_shift WHERE id=? AND vehicle_id=?", s.shift, s.vehicle)); assertEquals(0, env.count("SELECT count(*) FROM taktovia.shift_vehicle_switch WHERE shift_id=?", s.shift))
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.staff_shift WHERE id=?", other))
        // veralteter Ausgangsstand: 409; beendete Schicht: 409
        val v3 = s.w.vehicle("PKW")
        assertEquals(409, switch(s, body = s.body(to = v3, from = UUID.randomUUID())).status)
        val ended = W14C2ContractCases.Setup(); env.exec("UPDATE taktovia.staff_shift SET state='ENDED', ended_at=now() WHERE id=?", ended.shift)
        assertEquals(409, switch(ended).status)
        // fremde Schicht-Historie für den Fahrer: 404
        assertEquals(404, env.get("/v1/shifts/${s.shift}/vehicle-switches", s.w.otherToken).status)
        assertFalse(env.get("/v1/shifts/${s.shift}/vehicle-switches", s.w.token).json["items"].size() > 0)
    }
}

package de.taktovia.app.fleet

import de.taktovia.app.identity.IdentityEnv
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * WP-M3-21 / CHK-012, DMG-012 über die echte Anwendung (HTTP, Runtime-Rolle, Test-Issuer): Altschäden des Prüflaufs (minimierte Projektion), Quittierung (201/200, Idempotenz, kein Verursacherfeld),
 * Rechte, 404 für fremden Lauf, fremdes Fahrzeug und fremden Mandanten, Hinweis auf offene Fälle je Fahrzeug und 409 der Neuanlage ohne Bestätigung.
 */
class KnownDefectApiTest {
    private val env = IdentityEnv
    private fun idem(k: String = "k-" + UUID.randomUUID()) = mapOf("Idempotency-Key" to k)

    private fun defect(w: InspectionApiTest.World, vehicle: UUID, description: String = "Kratzer an der Beifahrertür"): String =
        env.post("/v1/defects", """{"vehicle_id":"$vehicle","occurred_at":"${Instant.now().minusSeconds(3600)}","category_code":"BODY","description":"$description"}""", w.dispatcherToken, null, idem()).also { check(it.status == 201) { it.text } }.json["id"].asString()

    private fun run(w: InspectionApiTest.World, vehicle: UUID): String {
        val s = w.shift(vehicle = vehicle)
        return env.post("/v1/inspections", w.body(s, vehicle), w.token, null, idem()).also { check(it.status == 201) { it.text } }.json["id"].asString()
    }

    private fun ack(w: InspectionApiTest.World, run: String, defect: String, token: String = w.token, body: String = "{}", key: Map<String, String> = idem()) =
        env.post("/v1/inspections/$run/known-defects/$defect/acknowledge", body, token, null, key)

    @Test
    fun altschaeden_anzeigen_quittieren_idempotent_ohne_verursacher() {
        val w = InspectionApiTest.World(env); w.template("PKW"); val vehicle = w.vehicle("PKW"); val d = defect(w, vehicle, "geheime Beschreibung"); val other = defect(w, w.vehicle("PKW"))
        val run = run(w, vehicle)
        val before = env.count("SELECT count(*) FROM taktovia.defect_case WHERE tenant_id=?", w.tenant.value)
        val l = env.get("/v1/inspections/$run/known-defects", w.token)
        assertEquals(200, l.status, l.text)
        assertEquals(listOf(d), l.json["defects"].values().map { it["defect_id"].asString() }); assertTrue(l.json["acknowledgement_required"].asBoolean()); assertEquals(1, l.json["unacknowledged_count"].asInt())
        // minimierte Projektion: keine Beschreibung, kein Ort, kein Melder
        assertFalse(l.text.contains("geheime Beschreibung")); assertFalse(l.json["defects"][0].has("description")); assertFalse(l.json["defects"][0].has("reported_by"))
        // Quittierung: 201, Wiederholung mit demselben Schlüssel dieselbe Antwort, zweites Gerät (anderer Schlüssel) 200 mit demselben Eintrag, kein zweiter Eintrag
        val key = idem(); val a = ack(w, run, d, key = key)
        assertEquals(201, a.status, a.text); assertTrue(a.json["acknowledged"].asBoolean()); assertEquals(1, a.json["acknowledged_defect_revision"].asInt())
        assertEquals(201, ack(w, run, d, key = key).status)
        val second = ack(w, run, d)
        assertEquals(200, second.status, second.text); assertEquals(a.json["acknowledged_at"].asString(), second.json["acknowledged_at"].asString())
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.known_defect_acknowledgement WHERE tenant_id=?", w.tenant.value))
        assertEquals(before, env.count("SELECT count(*) FROM taktovia.defect_case WHERE tenant_id=?", w.tenant.value))   // keine neue Akte
        assertEquals(0, env.get("/v1/inspections/$run/known-defects", w.token).json["unacknowledged_count"].asInt())
        // jedes mitgesendete Feld (Verursacher, Schuld) wird mit 422 abgewiesen und nie gespeichert
        val other2 = defect(w, vehicle)
        val bad = ack(w, run, other2, body = """{"caused_by_me":true}""")
        assertEquals(422, bad.status, bad.text); assertEquals(1, env.count("SELECT count(*) FROM taktovia.known_defect_acknowledgement WHERE tenant_id=?", w.tenant.value))
        assertTrue(other.isNotBlank())
    }

    @Test
    fun rechte_fremde_laeufe_fahrzeuge_und_mandanten_404() {
        val w = InspectionApiTest.World(env); w.template("PKW"); val vehicle = w.vehicle("PKW"); val d = defect(w, vehicle); val run = run(w, vehicle)
        val foreignVehicle = w.vehicle("PKW"); val foreignDefect = defect(w, foreignVehicle)
        // anderer Fahrer ohne Bezug: 404; Fahrgast: 403; Disposition liest (200), quittiert nicht (403); ohne Anmeldung 401
        assertEquals(404, env.get("/v1/inspections/$run/known-defects", w.otherToken).status)
        assertEquals(404, ack(w, run, d, token = w.otherToken).status)
        assertEquals(403, env.get("/v1/inspections/$run/known-defects", w.passengerToken).status)
        assertEquals(200, env.get("/v1/inspections/$run/known-defects", w.dispatcherToken).status)
        assertEquals(403, ack(w, run, d, token = w.dispatcherToken).status)
        assertEquals(401, env.server.post("/v1/inspections/$run/known-defects/$d/acknowledge", "{}", idem()).status)
        // Akte eines anderen Fahrzeugs und unbekannte Akte: 404
        assertEquals(404, ack(w, run, foreignDefect).status); assertEquals(404, ack(w, run, UUID.randomUUID().toString()).status)
        // Fahrzeug ohne Bezug: 404; mit Bezug (Schicht) die offenen Fälle desselben Fahrzeugs und nur dessen
        assertEquals(404, env.get("/v1/vehicles/$foreignVehicle/known-defects", w.token).status)
        val v = env.get("/v1/vehicles/$vehicle/known-defects", w.token)
        assertEquals(200, v.status, v.text); assertEquals(listOf(d), v.json["defects"].values().map { it["defect_id"].asString() }); assertFalse(v.json["acknowledgement_required"].asBoolean())
        // fremder Mandant: Lauf, Fahrzeug und Quittierung nicht sichtbar
        val o = InspectionApiTest.World(env)
        assertEquals(404, env.get("/v1/inspections/$run/known-defects", o.token).status); assertEquals(404, env.get("/v1/inspections/$run/known-defects", o.dispatcherToken).status)
        assertEquals(404, env.get("/v1/vehicles/$vehicle/known-defects", o.dispatcherToken).status); assertEquals(404, ack(o, run, d, token = o.token).status)
        assertEquals(0, env.count("SELECT count(*) FROM taktovia.known_defect_acknowledgement WHERE tenant_id=?", w.tenant.value))
    }

    @Test
    fun neuanlage_eines_fahrers_bei_offenen_faellen_ohne_bestaetigung_409() {
        val w = InspectionApiTest.World(env); w.template("PKW"); val vehicle = w.vehicle("PKW"); val d = defect(w, vehicle)
        w.shift(driver = w.account, vehicle = vehicle)   // Bezug der Schadenmeldung (WP-M3-43): driver_ref = IdP-Kennung
        val body = """{"vehicle_id":"$vehicle","occurred_at":"${Instant.now().minusSeconds(60)}","category_code":"BODY","description":"Kratzer an der Beifahrertür"}"""
        val n = env.count("SELECT count(*) FROM taktovia.defect_case WHERE tenant_id=?", w.tenant.value)
        val c = env.post("/v1/defects", body, w.token, null, idem())
        assertEquals(409, c.status, c.text); assertEquals("CONFLICT", c.json["code"].asString()); assertTrue(c.json["detail"].asString().startsWith("KNOWN_OPEN_DEFECTS"))
        assertEquals(n, env.count("SELECT count(*) FROM taktovia.defect_case WHERE tenant_id=?", w.tenant.value))
        // der Hinweis zeigt den bestehenden Fall; mit Bestätigung bleibt die Neuanlage möglich
        assertEquals(listOf(d), env.get("/v1/vehicles/${vehicle}/known-defects", w.token).json["defects"].values().map { it["defect_id"].asString() })
        val ok = env.post("/v1/defects", body.dropLast(1) + ""","confirm_new_despite_known":true}""", w.token, null, idem())
        assertEquals(201, ok.status, ok.text); assertEquals(n + 1, env.count("SELECT count(*) FROM taktovia.defect_case WHERE tenant_id=?", w.tenant.value))
        // die Disposition meldet wie bisher ohne Bestätigung
        assertEquals(201, env.post("/v1/defects", body, w.dispatcherToken, null, idem()).status)
    }
}

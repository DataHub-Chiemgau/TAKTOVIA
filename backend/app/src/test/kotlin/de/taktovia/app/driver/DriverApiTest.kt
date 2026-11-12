package de.taktovia.app.driver

import de.taktovia.app.identity.IdentityEnv
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M3-24 über die echte Anwendung (HTTP, Runtime-Rolle, Test-Issuer statt Keycloak): Anmeldung und Sitzung (abgelaufenes Token, gesperrte Sitzung), Gerätebindung,
 * Ereigniseingang mit Dedupe (100 identische Wiederholungen über HTTP = eine Wirkung), manipulierte `event_id`, Rechte (nur Fahrer), `tenant_id` im Body.
 * Die Flutter-App selbst ist hier nicht beteiligt (nicht ausgeführt); die Gegenstelle ist der echte Server.
 */
class DriverApiTest {
    private val env = IdentityEnv
    private fun idem() = mapOf("Idempotency-Key" to "k-" + UUID.randomUUID())

    private class World(val env: IdentityEnv) {
        val tenant = env.tenant(); val driver = UUID.randomUUID(); val otherDriver = UUID.randomUUID(); val passenger = UUID.randomUUID(); val dispatcher = UUID.randomUUID()
        val vehicle = UUID.randomUUID(); val shift = UUID.randomUUID()
        init {
            env.grant(tenant, driver, "driver"); env.grant(tenant, otherDriver, "driver"); env.grant(tenant, passenger, "passenger"); env.grant(tenant, dispatcher, "dispatcher")
            env.tx.run(tenant) { j ->
                j.update("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (?,?,'fixture','{}')", tenant.value, vehicle)
                j.update("INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state) VALUES (?,?,?,?,now(),now()+interval '8 hours','Europe/Berlin',0,'PLANNED')",
                    tenant.value, shift, driver, vehicle)
            }
        }
    }

    private fun register(token: String, device: UUID = UUID.randomUUID()) = env.post("/v1/driver/devices", """{"device_id":"$device","platform":"ANDROID","app_version":"1.0.0"}""", token, null, idem())

    private fun event(w: World, device: UUID, id: UUID = UUID.randomUUID(), type: String = "BREAK_START", extra: String = "") =
        """{"event_id":"$id","device_id":"$device","shift_id":"${w.shift}","vehicle_id":"${w.vehicle}","plan_revision":1,"event_type":"$type","occurred_at":"2026-11-12T08:00:00Z","device_recorded_at":"2026-11-12T08:00:00Z","offline_recorded":true$extra}"""

    @Test
    fun online_sync_geraet_binden_und_ereignis_wirkt_einmal() {
        val w = World(env); val token = env.token(w.driver); val dev = UUID.randomUUID()
        val reg = register(token, dev)
        assertEquals(201, reg.status, reg.text); assertEquals("\"1\"", reg.header("ETag")); assertEquals("/v1/driver/devices/$dev", reg.header("Location"))
        val id = UUID.randomUUID()
        val first = env.post("/v1/driver/events", event(w, dev, id), token, null, idem())
        assertEquals(200, first.status, first.text); assertEquals("APPLIED", first.json["state"].asString()); assertEquals(1, first.json["current_plan_revision"].asInt())
        assertTrue(first.json["reason_code"].isNull)
        // zweite Sendung mit neuem Idempotency-Key (Registerfrist abgelaufen o. ä.): dauerhaft dedupliziert
        val second = env.post("/v1/driver/events", event(w, dev, id), token, null, idem())
        assertEquals(200, second.status, second.text); assertEquals("DUPLICATE", second.json["state"].asString()); assertEquals(first.json["received_at"].asString(), second.json["received_at"].asString())
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.driver_event WHERE tenant_id=? AND event_id=?", w.tenant.value, id))
        // gleicher Idempotency-Key und gleicher Inhalt: das Register wiederholt die erste Antwort, keine zweite Wirkung
        val key = idem(); val a = env.post("/v1/driver/events", event(w, dev), token, null, key); val b = env.post("/v1/driver/events", a.text.let { event(w, dev, UUID.fromString(a.json["event_id"].asString())) }, token, null, key)
        assertEquals(200, a.status); assertEquals(200, b.status); assertEquals("APPLIED", b.json["state"].asString(), "Register wiederholt die gespeicherte Antwort")
    }

    @Test
    fun hundert_identische_wiederholungen_ueber_http_ergeben_eine_wirkung() {
        val w = World(env); val token = env.token(w.driver); val dev = UUID.randomUUID(); assertEquals(201, register(token, dev).status)
        val id = UUID.randomUUID(); val body = event(w, dev, id)   // Statusereignis: die Wirkung auf Buchung und Fahrt prüft DriverEventEffectsHttpTest
        val pool = Executors.newFixedThreadPool(16); val start = CountDownLatch(1)
        val futures = (1..100).map { pool.submit<Pair<Int, String>> { start.await(); val r = env.post("/v1/driver/events", body, token, null, idem()); r.status to (if (r.status == 200) r.json["state"].asString() else r.text) } }
        start.countDown(); val results = futures.map { it.get(120, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(emptyList<Any>(), results.filter { it.first != 200 })
        val states = results.groupingBy { it.second }.eachCount()
        assertEquals(1, states["APPLIED"], states.toString()); assertEquals(99, states["DUPLICATE"], states.toString())
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.driver_event WHERE tenant_id=? AND event_id=?", w.tenant.value, id))
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND event_type='driver.event_recorded'", w.tenant.value))
    }

    @Test
    fun abgelaufene_sitzung_und_gesperrte_sitzung_erhalten_401_und_es_entsteht_nichts() {
        val w = World(env); val sid = "sid-" + UUID.randomUUID(); val token = env.token(w.driver, sid); val dev = UUID.randomUUID(); assertEquals(201, register(token, dev).status)
        val expired = env.idp.sign(env.idp.humanClaims(sub = w.driver, acr = "loa2", amr = listOf("pwd", "otp"), lifetime = -60).also { it["sid"] = sid })
        val e = env.post("/v1/driver/events", event(w, dev), expired, null, idem())
        assertEquals(401, e.status, e.text)
        // Sitzung serverseitig sperren („alle Geräte abmelden“): jede weitere Anfrage trotz gültigem Token 401
        val out = env.postIdem("/v1/me/sessions/revoke-all", "{}", token)
        assertTrue(out.status in 200..204, out.text)
        val blocked = env.post("/v1/driver/events", event(w, dev), token, null, idem())
        assertEquals(401, blocked.status, blocked.text); assertTrue((blocked.header("WWW-Authenticate") ?: "").contains("session revoked"), "die App erkennt die Sperre am Header")
        assertEquals(0, env.count("SELECT count(*) FROM taktovia.driver_event WHERE tenant_id=?", w.tenant.value), "kein Ereignis aus der gesperrten Sitzung")
        // erneute Anmeldung (neue Sitzung): das Gerät muss neu gebunden werden, bis dahin 403
        Thread.sleep(1100)   // neue Anmeldung nach der Abmeldezeitmarke
        val again = env.token(w.driver, "sid-" + UUID.randomUUID())
        assertEquals(403, env.post("/v1/driver/events", event(w, dev), again, null, idem()).status)
        assertEquals(201, register(again, dev).status); assertEquals(200, env.post("/v1/driver/events", event(w, dev), again, null, idem()).status)
    }

    @Test
    fun manipulierte_ereignis_id_gleiche_id_anderer_inhalt_und_fremdes_ereignis() {
        val w = World(env); val token = env.token(w.driver); val dev = UUID.randomUUID(); assertEquals(201, register(token, dev).status)
        val bad = env.post("/v1/driver/events", event(w, dev).replace(Regex("\"event_id\":\"[^\"]+\""), "\"event_id\":\"keine-uuid\""), token, null, idem())
        assertEquals(422, bad.status, bad.text)
        val id = UUID.randomUUID()
        assertEquals(200, env.post("/v1/driver/events", event(w, dev, id), token, null, idem()).status)
        val conflict = env.post("/v1/driver/events", event(w, dev, id, "DISRUPTION"), token, null, idem())
        assertEquals(409, conflict.status, conflict.text); assertEquals("IDEMPOTENCY_CONFLICT", conflict.json["code"].asString())
        // ein anderer Fahrer sendet dieselbe event_id: nicht auffindbar, kein Hinweis auf das Ereignis des ersten
        val otherToken = env.token(w.otherDriver); val otherDev = UUID.randomUUID(); assertEquals(201, register(otherToken, otherDev).status)
        val foreign = env.post("/v1/driver/events", event(w, otherDev, id), otherToken, null, idem())
        assertEquals(404, foreign.status, foreign.text)
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.driver_event WHERE tenant_id=? AND event_id=?", w.tenant.value, id))
    }

    @Test
    fun rechte_fremde_schicht_fremder_mandant_und_tenant_id_im_body() {
        val w = World(env); val other = World(env); val token = env.token(w.driver); val dev = UUID.randomUUID(); assertEquals(201, register(token, dev).status)
        // nur Fahrer: Fahrgast und Disponent verweigert
        assertEquals(403, env.post("/v1/driver/events", event(w, dev), env.token(w.passenger), null, idem()).status)
        assertEquals(403, env.post("/v1/driver/events", event(w, dev), env.token(w.dispatcher), null, idem()).status)
        assertEquals(403, register(env.token(w.passenger)).status)
        // Schicht des anderen Mandanten, Schicht eines anderen Fahrers
        val foreignShift = event(w, dev).replace(w.shift.toString(), other.shift.toString())
        assertEquals(404, env.post("/v1/driver/events", foreignShift, token, null, idem()).status)
        val otherToken = env.token(w.otherDriver); val otherDev = UUID.randomUUID(); assertEquals(201, register(otherToken, otherDev).status)
        assertEquals(404, env.post("/v1/driver/events", event(w, otherDev), otherToken, null, idem()).status, "Schicht gehört einem anderen Fahrer")
        // tenant_id im Body gibt kein Recht: fremder Wert wie „nicht vorhanden“ (404, Sicherheitsereignis), eigener Wert ist ein unbekanntes Feld (422)
        val withTenant = env.post("/v1/driver/events", event(w, dev, extra = ""","tenant_id":"${other.tenant}""""), token, null, idem())
        assertEquals(404, withTenant.status, withTenant.text)
        assertEquals(1, env.events(w.tenant, "security.tenant.mismatch"))
        assertEquals(422, env.post("/v1/driver/events", event(w, dev, extra = ""","tenant_id":"${w.tenant}""""), token, null, idem()).status)
        assertEquals(0, env.count("SELECT count(*) FROM taktovia.driver_event WHERE tenant_id IN (?,?)", w.tenant.value, other.tenant.value))
        // CHECK_IN ohne Buchung wird bereits vom Vertrag (bedingtes Schema) abgewiesen
        assertEquals(422, env.post("/v1/driver/events", event(w, dev, type = "CHECK_IN"), token, null, idem()).status)
    }

    @Test
    fun geraet_beenden_if_match_und_fremdes_geraet() {
        val w = World(env); val token = env.token(w.driver); val dev = UUID.randomUUID(); assertEquals(201, register(token, dev).status)
        assertEquals(428, env.delete("/v1/driver/devices/$dev", token).status, "If-Match fehlt")
        assertEquals(412, env.delete("/v1/driver/devices/$dev", token, ifMatch = 9).status)
        assertEquals(404, env.delete("/v1/driver/devices/$dev", env.token(w.otherDriver), ifMatch = 1).status, "fremdes Gerät")
        assertEquals(204, env.delete("/v1/driver/devices/$dev", token, ifMatch = 1).status)
        val after = env.post("/v1/driver/events", event(w, dev), token, null, idem())
        assertEquals(403, after.status, after.text)
        assertEquals(409, register(token, dev).status, "beendete Bindung bleibt beendet")
        assertNotEquals(0, env.events(w.tenant, "driver.device.revoked") + 1)
        assertEquals(1, env.events(w.tenant, "driver.device.revoked")); assertEquals(1, env.events(w.tenant, "driver.device.registered"))
    }
}

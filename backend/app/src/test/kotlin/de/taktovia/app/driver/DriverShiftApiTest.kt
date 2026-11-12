package de.taktovia.app.driver

import de.taktovia.app.identity.IdentityEnv
import de.taktovia.platform.tenant.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M3-25 über die echte Anwendung (HTTP, Runtime-Rolle, Test-Issuer statt Keycloak): Fahrer meldet sich an (Konto mit Mitgliedschaft Rolle `driver`), sieht seine Schicht, tritt Dienst an
 * und liest den Run. Ohne gültige Schicht oder ohne Einsatzfreigabe keine Aufträge; fremde Schicht und fremder Mandant 404; Konto ohne Fahrerrolle 403; gesperrte Sitzung 401;
 * zwei Geräte gleichzeitig ein Zustand; Entzug der Zuweisung beendet die Auslieferung. Die Flutter-App ist nicht beteiligt (nicht ausgeführt); Gegenstelle ist der echte Server.
 */
class DriverShiftApiTest {
    private val env = IdentityEnv
    private fun idem() = mapOf("Idempotency-Key" to "k-" + UUID.randomUUID())

    private class World(val env: IdentityEnv) {
        val tenant: TenantId = env.tenant("Dienst-" + UUID.randomUUID())
        val account = UUID.randomUUID(); val otherAccount = UUID.randomUUID(); val dispatcher = UUID.randomUUID(); val passenger = UUID.randomUUID()
        val driverId: UUID; val otherDriverId: UUID; val vehicle: UUID = UUID.randomUUID()
        init {
            val m1 = env.grant(tenant, account, "driver"); val m2 = env.grant(tenant, otherAccount, "driver"); env.grant(tenant, dispatcher, "dispatcher"); env.grant(tenant, passenger, "passenger")
            driverId = driver(m1); otherDriverId = driver(m2)
            env.exec("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config,vehicle_label) VALUES (?,?,'fixture','{}','A-12')", tenant.value, vehicle)
        }
        private fun driver(membership: UUID): UUID = UUID.randomUUID().also { env.exec("INSERT INTO taktovia.driver(tenant_id,id,membership_id,display_name,contact_channel,contact_value) VALUES (?,?,?,'Fahrer','APP','x')", tenant.value, it, membership) }
        val token = env.token(account); val otherToken = env.token(otherAccount); val dispatcherToken = env.token(dispatcher); val passengerToken = env.token(passenger)

        /** Schicht über die Leitstellen-API: von vor fünf Minuten bis in vier Stunden (innerhalb der Antrittstoleranz). */
        fun shift(driver: UUID = driverId, vehicle: UUID = this.vehicle, from: Instant = Instant.now().minusSeconds(300), to: Instant = Instant.now().plusSeconds(4 * 3600)): String =
            env.post("/v1/shifts", """{"driver_id":"$driver","vehicle_id":"$vehicle","starts_at":"$from","ends_at":"$to","timezone":"Europe/Berlin"}""", dispatcherToken, extra = mapOf("Idempotency-Key" to "s-" + UUID.randomUUID()))
                .also { check(it.status == 201) { it.text } }.json["id"].asString()

        fun device(t: String = token): String = UUID.randomUUID().toString().also {
            check(env.post("/v1/driver/devices", """{"device_id":"$it","platform":"ANDROID","app_version":"1.0.0"}""", t, extra = mapOf("Idempotency-Key" to "d-" + UUID.randomUUID())).status == 201)
        }
        fun start(shift: String, device: String, t: String = token, vehicleId: UUID = vehicle) =
            env.post("/v1/driver/shift-starts", """{"shift_id":"$shift","vehicle_id":"$vehicleId","device_id":"$device"}""", t, extra = mapOf("Idempotency-Key" to "st-" + UUID.randomUUID()))
        fun release(shift: String) {
            env.exec("INSERT INTO taktovia.resource_release(tenant_id,id,vehicle_id,shift_id,safety_revision,inspection_ref,qualification_revision,valid_until) VALUES (?,?,?,?,1,?,1,now()+interval '6 hours')",
                tenant.value, UUID.randomUUID(), vehicle, UUID.fromString(shift), UUID.randomUUID())
            env.exec("UPDATE taktovia.vehicle SET active_block=false WHERE tenant_id=? AND id=?", tenant.value, vehicle)
        }
    }

    @Test
    fun anmeldung_antritt_und_run_nur_mit_freigabe() {
        val w = World(env)
        val me = env.get("/v1/me", w.token); assertEquals(200, me.status); assertTrue(me.json["roles"].values().any { it.asString() == "driver" }); assertEquals(w.tenant.toString(), me.json["tenant_id"].asString())
        assertEquals(0, env.get("/v1/driver/shifts", w.token).json["items"].size(), "ohne Zuweisung der Leerzustand")
        val s = w.shift(); val dev = w.device()
        val mine = env.get("/v1/driver/shifts", w.token); assertEquals(200, mine.status, mine.text)
        assertEquals(s, mine.json["items"][0]["shift_id"].asString()); assertEquals("A-12", mine.json["items"][0]["vehicle_label"].asString()); assertEquals("PLANNED", mine.json["items"][0]["state"].asString())
        // vor dem Antritt: Run 200, Leerzustand
        val pre = env.get("/v1/driver/shifts/$s/run", w.token); assertEquals(200, pre.status, pre.text); assertEquals(false, pre.json["shift_valid"].asBoolean()); assertEquals(0, pre.json["stops"].size())
        val st = w.start(s, dev); assertEquals(200, st.status, st.text); assertEquals(false, st.json["already_started"].asBoolean()); assertEquals("STARTED", st.json["shift"]["state"].asString()); assertEquals(true, st.json["shift"]["valid"].asBoolean())
        // Antritt allein ist keine Einsatzfreigabe
        val run = env.get("/v1/driver/shifts/$s/run", w.token); assertEquals(200, run.status, run.text)
        assertEquals("NOT_RELEASED", run.json["release_state"].asString()); assertEquals("NO_INSPECTION", run.json["missing_requirements"][0]["code"].asString()); assertEquals(0, run.json["stops"].size()); assertEquals("\"0\"", run.header("ETag"))
        w.release(s)
        assertEquals("RELEASED", env.get("/v1/driver/shifts/$s/run", w.token).json["release_state"].asString())
        // Audit mit Akteur, Gerät und Zeit; keine Arbeitszeiterfassung
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='staff.shift.started' AND actor_ref=? AND redacted_changes::text LIKE ?", w.tenant.value, "user:${w.account}", "%$dev%"))
        assertEquals(0, env.count("SELECT count(*) FROM taktovia.time_event WHERE tenant_id=?", w.tenant.value))
        val end = env.post("/v1/driver/shift-ends", """{"shift_id":"$s","device_id":"$dev"}""", w.token, extra = idem()); assertEquals(200, end.status, end.text); assertEquals("ENDED", end.json["state"].asString())
        assertEquals(1, env.events(w.tenant, "staff.shift.ended"))
    }

    @Test
    fun fremde_schicht_fremder_mandant_rolle_und_tenant_id_im_body() {
        val w = World(env); val other = World(env)
        val s = w.shift(); val dev = w.device(); val otherDev = w.device(w.otherToken)
        // fremde Schicht (anderer Fahrer desselben Mandanten) und Schicht eines anderen Mandanten: einheitlich 404
        for (path in listOf("/v1/driver/shifts/$s/run")) assertEquals(404, env.get(path, w.otherToken).status)
        assertEquals(404, w.start(s, otherDev, w.otherToken).status)
        val foreign = other.shift(); assertEquals(404, env.get("/v1/driver/shifts/$foreign/run", w.token).status); assertEquals(404, w.start(foreign, dev).status)
        assertEquals(404, env.get("/v1/driver/shifts/${UUID.randomUUID()}/run", w.token).status)
        assertEquals(0, env.get("/v1/driver/shifts", w.otherToken).json["items"].size(), "fremde Schichten erscheinen nie in der eigenen Liste")
        // Konto ohne Fahrerrolle (Disponent, Fahrgast): 403
        assertEquals(403, env.get("/v1/driver/shifts", w.dispatcherToken).status); assertEquals(403, env.get("/v1/driver/shifts/$s/run", w.passengerToken).status)
        assertEquals(403, w.start(s, dev, w.dispatcherToken).status)
        // abweichendes Fahrzeug abgewiesen, tenant_id im Body ohne Recht (fremder Wert 404 mit Sicherheitsereignis, eigener Wert 422)
        assertEquals(422, w.start(s, dev, vehicleId = UUID.randomUUID()).status)
        val extra = { t: Any -> env.post("/v1/driver/shift-starts", """{"shift_id":"$s","vehicle_id":"${w.vehicle}","device_id":"$dev","tenant_id":"$t"}""", w.token, extra = mapOf("Idempotency-Key" to "x-" + UUID.randomUUID())) }
        assertEquals(404, extra(other.tenant).status); assertEquals(422, extra(w.tenant).status)
        assertEquals("PLANNED", env.string("SELECT state FROM taktovia.staff_shift WHERE id=?", UUID.fromString(s)).single())
    }

    @Test
    fun gesperrte_sitzung_kein_antritt_und_zwei_geraete_ein_zustand() {
        val w = World(env); val s = w.shift(); val sid = "sid-" + UUID.randomUUID(); val token = env.token(w.account, sid); val dev = w.device(token)
        val out = env.postIdem("/v1/me/sessions/revoke-all", "{}", token); assertTrue(out.status in 200..204, out.text)
        val blocked = w.start(s, dev, token); assertEquals(401, blocked.status, blocked.text)
        assertEquals("PLANNED", env.string("SELECT state FROM taktovia.staff_shift WHERE id=?", UUID.fromString(s)).single())
        // zwei Geräte desselben Fahrers (zwei Sitzungen) treten gleichzeitig an: genau ein Antritt
        Thread.sleep(1100)
        val t1 = env.token(w.account, "sid-" + UUID.randomUUID()); val t2 = env.token(w.account, "sid-" + UUID.randomUUID()); val d1 = w.device(t1); val d2 = w.device(t2)
        val pool = Executors.newFixedThreadPool(2); val go = CountDownLatch(1)
        val results = listOf(t1 to d1, t2 to d2).map { (t, d) -> pool.submit<Pair<Int, Boolean>> { go.await(); val r = w.start(s, d, t); r.status to (r.status == 200 && r.json["already_started"].asBoolean()) } }
        go.countDown(); val out2 = results.map { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(listOf(200, 200), out2.map { it.first }, out2.toString()); assertEquals(1, out2.count { !it.second }, "genau ein Antritt")
        assertEquals(1, env.events(w.tenant, "staff.shift.started"))
    }

    @Test
    fun entzug_beendet_die_auslieferung_und_abgelaufene_schicht_liefert_nichts() {
        val w = World(env); val s = w.shift(); val dev = w.device(); w.release(s)
        assertEquals(200, w.start(s, dev).status)
        assertEquals("RELEASED", env.get("/v1/driver/shifts/$s/run", w.token).json["release_state"].asString())
        val cur = env.get("/v1/shifts/$s", w.dispatcherToken)
        val upd = env.put("/v1/shifts/$s", """{"driver_id":"${w.driverId}","vehicle_id":"${w.vehicle}","starts_at":"${cur.json["starts_at"].asString()}","ends_at":"${cur.json["ends_at"].asString()}","timezone":"Europe/Berlin","withdrawn":true}""", w.dispatcherToken, cur.header("ETag"))
        assertEquals(200, upd.status, upd.text)
        val run = env.get("/v1/driver/shifts/$s/run", w.token)
        assertEquals(200, run.status); assertEquals("CANCELLED", run.json["shift_state"].asString()); assertEquals(false, run.json["shift_valid"].asBoolean()); assertEquals(0, run.json["stops"].size())
        assertEquals(0, env.get("/v1/driver/shifts", w.token).json["items"].size())
        assertEquals(409, w.start(s, dev).status, "nach Entzug kein Antritt")
        // abgelaufene Schicht (Ende in der Vergangenheit, anderes Fahrzeug): Antritt 409, der Run bleibt leer
        val pastVehicle = UUID.randomUUID().also { v -> env.exec("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config,vehicle_label) VALUES (?,?,'fixture','{}',?)", w.tenant.value, v, "P-" + v.toString().take(6)) }
        val past = w.shift(from = Instant.now().minusSeconds(7200), to = Instant.now().minusSeconds(3600), vehicle = pastVehicle)
        val d2 = w.device()
        assertEquals(409, w.start(past, d2, vehicleId = pastVehicle).status)
        assertEquals(0, env.get("/v1/driver/shifts/$past/run", w.token).json["stops"].size())
    }
}

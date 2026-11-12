package de.taktovia.app.driver

import de.taktovia.app.identity.IdentityEnv
import de.taktovia.platform.tenant.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * WP-M3-34 über die echte Anwendung (HTTP, Runtime-Rolle, Test-Issuer): Positionsannahme der Fahrer-App und `GET /v1/operator/vehicles` mit letzter Position, Messzeit und Veraltet-Kennzeichen.
 * Positiv: laufender Strom, aktuelle Position nicht veraltet. Negativ: Position ohne Dienst (409), fremdes Fahrzeug und fremde Schicht (404), Zukunftszeit (einzeln abgewiesen), Rechte, `tenant_id`
 * im Body, fremder Mandant. AT-10: eine 40 s alte Messung ist veraltet und zeigt den letzten Stand; nachgelieferte ältere Punkte verdrängen die neuere Position nicht.
 * Die Flutter-App ist nicht beteiligt (nicht ausgeführt); Gegenstelle ist der echte Server.
 */
class DriverPositionApiTest {
    private val env = IdentityEnv
    private fun idem() = mapOf("Idempotency-Key" to "k-" + UUID.randomUUID())

    private class World(val env: IdentityEnv) {
        val tenant: TenantId = env.tenant("Position-" + UUID.randomUUID())
        val account = UUID.randomUUID(); val dispatcher = UUID.randomUUID(); val callcenter = UUID.randomUUID()
        val vehicle: UUID = UUID.randomUUID(); val driverId: UUID = UUID.randomUUID()
        init {
            val m = env.grant(tenant, account, "driver"); env.grant(tenant, dispatcher, "dispatcher"); env.grant(tenant, callcenter, "callcenter")
            env.exec("INSERT INTO taktovia.driver(tenant_id,id,membership_id,display_name,contact_channel,contact_value) VALUES (?,?,?,'Fahrer','APP','x')", tenant.value, driverId, m)
            env.exec("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config,vehicle_label) VALUES (?,?,'fixture','{}','P-1')", tenant.value, vehicle)
        }
        val token = env.token(account); val dispatcherToken = env.token(dispatcher); val callcenterToken = env.token(callcenter)
        fun shift(): String = env.post("/v1/shifts", """{"driver_id":"$driverId","vehicle_id":"$vehicle","starts_at":"${Instant.now().minusSeconds(600)}","ends_at":"${Instant.now().plusSeconds(4 * 3600)}","timezone":"Europe/Berlin"}""",
            dispatcherToken, extra = mapOf("Idempotency-Key" to "s-" + UUID.randomUUID())).also { check(it.status == 201) { it.text } }.json["id"].asString()
        fun device(): String = UUID.randomUUID().toString().also { check(env.post("/v1/driver/devices", """{"device_id":"$it","platform":"ANDROID","app_version":"1.0.0"}""", token, extra = mapOf("Idempotency-Key" to "d-" + UUID.randomUUID())).status == 201) }
        /** Dienstantritt; der Antritt wird danach auf vor einer Stunde zurückdatiert (Testaufbau: der Dienst läuft schon, damit auch ältere Messzeiten „im Dienst“ liegen). */
        fun start(shift: String, device: String) = env.post("/v1/driver/shift-starts", """{"shift_id":"$shift","vehicle_id":"$vehicle","device_id":"$device"}""", token, extra = mapOf("Idempotency-Key" to "st-" + UUID.randomUUID()))
            .also { if (it.status == 200) env.exec("UPDATE taktovia.staff_shift SET started_at = now() - interval '1 hour' WHERE tenant_id = ? AND id = ?", tenant.value, UUID.fromString(shift)) }
        fun sample(ageSeconds: Long, lat: Double = 51.05, lon: Double = 13.74, at: Instant = Instant.now().minusSeconds(ageSeconds)) = """{"position":{"latitude":$lat,"longitude":$lon},"accuracy_meters":4.5,"measured_at":"$at","speed_mps":8.0,"heading_deg":90}"""
        fun send(shift: String, device: String, vararg samples: String, vehicleId: UUID = vehicle, t: String = token) =
            env.post("/v1/driver/positions", """{"shift_id":"$shift","vehicle_id":"$vehicleId","device_id":"$device","samples":[${samples.joinToString(",")}]}""", t, extra = mapOf("Idempotency-Key" to "p-" + UUID.randomUUID()))
        fun operatorVehicle(t: String = dispatcherToken) = env.get("/v1/operator/vehicles", t).also { check(it.status == 200) { it.text } }.json["items"].values().first { it["vehicle_id"].asString() == vehicle.toString() }
    }

    @Test
    fun laufender_strom_aktuelle_position_ist_nicht_veraltet_und_nur_im_angetretenen_dienst() {
        val w = World(env); val s = w.shift(); val d = w.device()
        // ohne angetretenen Dienst: nichts angenommen, nichts gespeichert, die Leitstelle zeigt keine Position (veraltet)
        val early = w.send(s, d, w.sample(1)); assertEquals(409, early.status, early.text); assertEquals("INVALID_STATE_TRANSITION", early.json["code"].asString())
        assertEquals(0, env.count("SELECT count(*) FROM taktovia.position_sample WHERE tenant_id=?", w.tenant.value))
        val none = w.operatorVehicle(); assertTrue(none["position"].isNull); assertTrue(none["position_stale"].asBoolean()); assertTrue(none["position_age_seconds"].isNull)
        assertEquals(200, w.start(s, d).status)
        val ok = w.send(s, d, w.sample(3, 51.050, 13.740), w.sample(1, 51.051, 13.741)); assertEquals(200, ok.status, ok.text)
        assertEquals(2, ok.json["accepted"].asInt()); assertEquals(0, ok.json["rejected"].asInt()); assertTrue(ok.json["received_at"].asString().endsWith("Z"))
        val v = w.operatorVehicle()
        assertFalse(v["position_stale"].asBoolean(), v.toString()); assertEquals(51.051, v["position"]["latitude"].asDouble(), 1e-9); assertEquals(13.741, v["position"]["longitude"].asDouble(), 1e-9)
        assertTrue(v["position_age_seconds"].asInt() in 0..10); assertTrue(v["position_measured_at"].asString().isNotEmpty()); assertTrue(v["position_received_at"].asString().isNotEmpty())
        // Messzeit und Empfangszeit sind getrennt gespeichert; Geschwindigkeit und Richtung werden nicht gespeichert
        assertEquals(2, env.count("SELECT count(*) FROM taktovia.position_sample WHERE tenant_id=? AND received_at > measured_at", w.tenant.value))
        assertEquals(0, env.count("SELECT count(*) FROM information_schema.columns WHERE table_name='position_sample' AND column_name IN ('speed_mps','heading_deg')"))
    }

    @Test
    fun at_10_veraltet_nach_der_schwelle_mit_letztem_stand_und_nachgelieferte_punkte_ersetzen_nicht() {
        val w = World(env); val s = w.shift(); val d = w.device(); assertEquals(200, w.start(s, d).status)
        assertEquals(200, w.send(s, d, w.sample(40, 51.10, 13.50)).status)          // der Strom ist 40 s alt: über der Schwelle von 30 s
        val stale = w.operatorVehicle(); assertTrue(stale["position_stale"].asBoolean()); assertEquals(51.10, stale["position"]["latitude"].asDouble(), 1e-9); assertTrue(stale["position_age_seconds"].asInt() >= 40)
        // Wiederverbindung: ein älterer Punkt (5 Minuten) wird nachgeliefert und gespeichert, ist aber nie die aktuelle Position
        val late = w.send(s, d, w.sample(300, 50.0, 12.0)); assertEquals(1, late.json["accepted"].asInt())
        val after = w.operatorVehicle(); assertEquals(51.10, after["position"]["latitude"].asDouble(), 1e-9); assertTrue(after["position_stale"].asBoolean())
        assertEquals(2, env.count("SELECT count(*) FROM taktovia.position_sample WHERE tenant_id=?", w.tenant.value))
        // ein frischer Punkt macht die Position wieder aktuell
        assertEquals(200, w.send(s, d, w.sample(1, 51.20, 13.60)).status); assertFalse(w.operatorVehicle()["position_stale"].asBoolean())
        // erneute Sendung (Outbox-Wiederholung desselben Punkts) erzeugt keine zweite Zeile
        val at = Instant.now().minusSeconds(100); w.send(s, d, w.sample(0, at = at)); w.send(s, d, w.sample(0, at = at))
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.position_sample WHERE tenant_id=? AND measured_at=?", w.tenant.value, java.sql.Timestamp.from(at)))
    }

    @Test
    fun zukunftszeit_fremdes_fahrzeug_fremde_schicht_rechte_und_tenant_id() {
        val w = World(env); val other = World(env); val s = w.shift(); val d = w.device(); assertEquals(200, w.start(s, d).status)
        val future = w.send(s, d, w.sample(0, at = Instant.now().plusSeconds(120)), w.sample(2))
        assertEquals(200, future.status, future.text); assertEquals(1, future.json["accepted"].asInt()); assertEquals(1, future.json["rejected"].asInt()); assertEquals("FUTURE_TIME", future.json["rejections"][0]["reason"].asString()); assertEquals(0, future.json["rejections"][0]["index"].asInt())
        assertEquals(404, w.send(s, d, w.sample(1), vehicleId = other.vehicle).status, "fremdes Fahrzeug")
        val foreignShift = other.shift()
        assertEquals(404, w.send(foreignShift, d, w.sample(1)).status, "Schicht eines anderen Mandanten")
        assertEquals(404, w.send(UUID.randomUUID().toString(), d, w.sample(1)).status)
        // Rechte: nur Fahrer sendet, nur Leitstellenrollen lesen
        assertEquals(403, w.send(s, d, w.sample(1), t = w.dispatcherToken).status); assertEquals(403, env.get("/v1/operator/vehicles", w.token).status)
        assertEquals(200, env.get("/v1/operator/vehicles", w.callcenterToken).status)
        // tenant_id im Body gibt kein Recht: fremder Wert 404 mit Sicherheitsereignis, eigener Wert 422 als unbekanntes Feld
        fun withTenant(t: Any) = env.post("/v1/driver/positions", """{"shift_id":"$s","vehicle_id":"${w.vehicle}","device_id":"$d","tenant_id":"$t","samples":[${w.sample(1)}]}""", w.token, extra = idem())
        assertEquals(404, withTenant(other.tenant).status); assertEquals(422, withTenant(w.tenant).status)
        // Eingabefehler: leere Liste, Breite außerhalb, Zeit ohne Offset
        assertEquals(422, w.send(s, d).status); assertEquals(422, w.send(s, d, w.sample(1, lat = 95.0)).status)
        assertEquals(422, w.send(s, d, """{"position":{"latitude":1.0,"longitude":1.0},"accuracy_meters":1,"measured_at":"2026-12-01T07:00:00"}""").status)
        // Mandantentrennung der Leitstellenliste: Mandant B sieht kein Fahrzeug und keine Position von Mandant A
        val b = env.get("/v1/operator/vehicles", other.dispatcherToken); assertEquals(200, b.status); assertFalse(b.text.contains(w.vehicle.toString()))
        assertEquals(422, env.get("/v1/operator/vehicles?depot_id=${UUID.randomUUID()}", w.dispatcherToken).status, "kein Betriebshofbezug in M3")
        assertEquals(422, env.get("/v1/operator/vehicles?state=EGAL", w.dispatcherToken).status)
        assertEquals("NOT_READY", w.operatorVehicle()["operational_state"].asString())
    }

    @Test
    fun beendeter_dienst_nimmt_nur_noch_nachlieferungen_aus_dem_dienst_an() {
        val w = World(env); val s = w.shift(); val d = w.device(); assertEquals(200, w.start(s, d).status)
        assertEquals(200, env.post("/v1/driver/shift-ends", """{"shift_id":"$s","device_id":"$d"}""", w.token, extra = idem()).status)
        val r = w.send(s, d, w.sample(0), w.sample(5, at = Instant.now().minusSeconds(7200)))   // jetzt (nach dem Ende) und zwei Stunden zuvor (vor dem Antritt)
        assertEquals(200, r.status, r.text); assertEquals(0, r.json["accepted"].asInt()); assertEquals(2, r.json["rejected"].asInt()); assertTrue(r.json["rejections"].values().all { it["reason"].asString() == "OUTSIDE_SERVICE" })
    }
}

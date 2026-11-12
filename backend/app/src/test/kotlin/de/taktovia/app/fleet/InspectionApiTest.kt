package de.taktovia.app.fleet

import de.taktovia.app.identity.IdentityEnv
import de.taktovia.platform.tenant.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * WP-M3-13 / LH-6.21-CHK-002 über die echte Anwendung (HTTP, Runtime-Rolle, Test-Issuer statt Keycloak): `POST /v1/inspections` (Idempotency-Key, 201/200, ETag, Location, serverbestimmte Vorlage),
 * `GET /v1/inspections/{id}`, Rechte, 404 für fremde Schicht und fremden Mandanten, 409 ohne Dienstanmeldung und ohne eindeutige Vorlage. Planungsannahme PA-08 (Fahrzeugklasse = `vehicle_type`,
 * Bedienart = `operation_kind`).
 */
class InspectionApiTest {
    private val env = IdentityEnv
    private fun idem(k: String = "k-" + UUID.randomUUID()) = mapOf("Idempotency-Key" to k)

    class World(val env: IdentityEnv) {
        val tenant: TenantId = env.tenant()
        val account = UUID.randomUUID(); val other = UUID.randomUUID(); val dispatcher = UUID.randomUUID(); val passenger = UUID.randomUUID(); val author = UUID.randomUUID(); val reviewer = UUID.randomUUID()
        val driverId: UUID; val otherDriverId: UUID
        init {
            val m1 = env.grant(tenant, account, "driver"); val m2 = env.grant(tenant, other, "driver")
            env.grant(tenant, dispatcher, "dispatcher"); env.grant(tenant, passenger, "passenger"); env.grant(tenant, author, "workshop"); env.grant(tenant, reviewer, "tenant_admin")
            driverId = driver(m1); otherDriverId = driver(m2)
        }
        private fun driver(membership: UUID): UUID = UUID.randomUUID().also { env.exec("INSERT INTO taktovia.driver(tenant_id,id,membership_id,display_name,contact_channel,contact_value) VALUES (?,?,?,'Fahrer','APP','x')", tenant.value, it, membership) }
        val token = env.token(account); val otherToken = env.token(other); val dispatcherToken = env.token(dispatcher); val passengerToken = env.token(passenger)
        private val authorToken = env.token(author); private val reviewerToken = env.token(reviewer)

        fun vehicle(cls: String?, mode: String = "STANDARD"): UUID = UUID.randomUUID().also {
            env.exec("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config,vehicle_type,operation_kind) VALUES (?,?,'fixture','{}',?,?)", tenant.value, it, cls, mode)
        }

        /** Schicht des Fahrerstamms; `started` = Dienstantritt erfolgt (Zustand STARTED mit Gerät). */
        fun shift(driver: UUID = driverId, vehicle: UUID, started: Boolean = true, fromHours: Int = -1, toHours: Int = 7): UUID = UUID.randomUUID().also { id ->
            val device = UUID.randomUUID()
            if (started) env.exec("INSERT INTO taktovia.driver_device(tenant_id,id,issuer,subject,idp_session_id,platform,app_version) VALUES (?,?,'https://idp.test',?,?,'ANDROID','1.0.0')", tenant.value, device, driver.toString(), "sid-" + device)
            env.exec("INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state,started_at,started_device_id) VALUES (?,?,?,?,now()+(?||' hours')::interval,now()+(?||' hours')::interval,'Europe/Berlin',0,?,?,?)",
                tenant.value, id, driver, vehicle, fromHours.toString(), toHours.toString(), if (started) "STARTED" else "PLANNED", if (started) java.sql.Timestamp.from(Instant.now().minusSeconds(300)) else null, if (started) device else null)
        }

        /** Freigegebene Vorlage über die API (Vier-Augen: Werkstatt legt an, Betriebsleitung gibt frei). */
        fun template(cls: String, mode: String = "STANDARD", steps: Int = 3, readinessStep: Boolean = false): String {
            val body = """{"name":"Prüfung $cls","vehicle_class":"$cls","service_mode":"$mode","steps":[""" + (1..steps).joinToString(",") { """{"step_code":"S$it","title":"Punkt $it","input_kind":"OK_DEFECT_NA","mandatory":true,"safety_critical":false}""" } +
                (if (readinessStep) """,{"step_code":"EINSATZBEREITSCHAFT","title":"Einsatzbereit","input_kind":"READINESS_YES_NO","mandatory":true,"safety_critical":false}""" else "") + "]}"
            val c = env.post("/v1/operator/checklist-templates", body, authorToken, null, mapOf("Idempotency-Key" to "t-" + UUID.randomUUID())); check(c.status == 201) { c.text }
            val id = c.json["id"].asString()
            val p = env.server.send("POST", "/v1/operator/checklist-templates/$id/publish", null, env.headers(reviewerToken, extra = mapOf("Idempotency-Key" to "p-" + UUID.randomUUID(), "If-Match" to "\"1\"")), null); check(p.status == 200) { p.text }
            return id
        }

        fun body(shift: UUID, vehicle: UUID, extra: String = "", kind: String = "PRE_SHIFT") = """{"shift_id":"$shift","vehicle_id":"$vehicle","kind":"$kind","client_started_at":"${Instant.now().minusSeconds(5)}"$extra}"""
    }

    private fun create(w: World, body: String, token: String = w.token, key: Map<String, String> = idem()) = env.post("/v1/inspections", body, token, null, key)

    @Test
    fun start_liefert_serverbestimmte_vorlage_idempotent_und_lesbar() {
        val w = World(env); val tpl = w.template("PKW", steps = 4); val wrong = w.template("BUS", steps = 2)
        val vehicle = w.vehicle("PKW"); val shift = w.shift(vehicle = vehicle)
        val key = idem(); val body = w.body(shift, vehicle, extra = ""","template_revision_id":"$wrong"""")
        val c = create(w, body, key = key)
        assertEquals(201, c.status, c.text); assertEquals("\"1\"", c.header("ETag"))
        val id = c.json["id"].asString(); assertEquals("/v1/inspections/$id", c.header("Location"))
        // exakt die freigegebene Vorlage; der mitgesendete Wert bleibt wirkungslos
        assertEquals(tpl, c.json["template_revision_id"].asString()); assertEquals("IN_PROGRESS", c.json["status"].asString()); assertEquals(w.driverId.toString(), c.json["driver_ref"].asString())
        assertEquals(4, c.json["template"]["step_count"].asInt()); assertEquals(4, c.json["template"]["steps"].size()); assertEquals("S1", c.json["template"]["steps"][0]["step_code"].asString())
        assertEquals("INSPECTION_OPEN", c.json["missing_requirements"][0]["code"].asString()); assertTrue(c.json["release"].isNull)
        // derselbe Schlüssel: dieselbe Antwort, kein zweiter Lauf; anderer Inhalt unter demselben Schlüssel: 409 IDEMPOTENCY_CONFLICT
        val again = create(w, body, key = key)
        assertEquals(201, again.status, again.text); assertEquals("true", again.header("Idempotent-Replayed")); assertEquals(id, again.json["id"].asString())
        val conflict = create(w, w.body(shift, vehicle, kind = "VEHICLE_CHANGE"), key = key)
        assertEquals(409, conflict.status, conflict.text); assertEquals("IDEMPOTENCY_CONFLICT", conflict.json["code"].asString())
        // zweites Gerät, anderer Schlüssel: der offene Lauf, unverändert (200)
        val second = create(w, w.body(shift, vehicle))
        assertEquals(200, second.status, second.text); assertEquals(id, second.json["id"].asString())
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.inspection_run WHERE tenant_id=?", w.tenant.value))
        // Lesen: Fahrer den eigenen Lauf mit fester Vorlage, Disposition jeden; ein anderer Fahrer und Fahrgast nicht
        val g = env.get("/v1/inspections/$id", w.token)
        assertEquals(200, g.status, g.text); assertEquals("\"1\"", g.header("ETag")); assertEquals(tpl, g.json["template_revision_id"].asString()); assertEquals(4, g.json["template"]["steps"].size())
        assertEquals(200, env.get("/v1/inspections/$id", w.dispatcherToken).status)
        assertEquals(404, env.get("/v1/inspections/$id", w.otherToken).status)
        assertEquals(403, env.get("/v1/inspections/$id", w.passengerToken).status)
        assertEquals(404, env.get("/v1/inspections/${UUID.randomUUID()}", w.token).status)
        // andere Mandanten sehen den Lauf nicht
        val o = World(env); assertEquals(404, env.get("/v1/inspections/$id", o.token).status); assertEquals(404, env.get("/v1/inspections/$id", o.dispatcherToken).status)
    }

    @Test
    fun negativfaelle_rechte_fremde_schicht_anmeldung_und_vorlage() {
        val w = World(env); w.template("PKW")
        val vehicle = w.vehicle("PKW"); val shift = w.shift(vehicle = vehicle)
        // Rechte: Fahrgast und Disposition starten nicht, ohne Anmeldung 401
        assertEquals(403, create(w, w.body(shift, vehicle), token = w.passengerToken).status)
        assertEquals(403, create(w, w.body(shift, vehicle), token = w.dispatcherToken).status)
        assertEquals(401, env.server.post("/v1/inspections", w.body(shift, vehicle), idem()).status)
        // Schicht eines anderen Fahrers: 404 (kein Hinweis auf die Existenz); Schicht eines anderen Mandanten: 404
        assertEquals(404, create(w, w.body(shift, vehicle), token = w.otherToken).status)
        val o = World(env); val ov = o.vehicle("PKW"); val os = o.shift(vehicle = ov)
        assertEquals(404, create(w, w.body(os, ov)).status)
        assertEquals(404, create(w, w.body(UUID.randomUUID(), vehicle)).status)
        // ein fremder Mandant im Body: kein Eingabeweg (404)
        assertEquals(404, create(w, w.body(shift, vehicle, extra = ""","tenant_id":"${o.tenant.value}"""")).status)
        // ohne Dienstanmeldung: 409, kein Lauf
        val v2 = w.vehicle("PKW"); val planned = w.shift(vehicle = v2, started = false, fromHours = 8, toHours = 12)
        val np = create(w, w.body(planned, v2))
        assertEquals(409, np.status, np.text); assertEquals("INVALID_STATE_TRANSITION", np.json["code"].asString())
        // keine passende freigegebene Vorlage: 409 mit stabilem Kennwort, kein Lauf, Hinweis an die Leitstelle
        val bus = w.vehicle("BUS"); val sb = w.shift(vehicle = bus, driver = w.otherDriverId)
        val nt = create(w, w.body(sb, bus), token = w.otherToken)
        assertEquals(409, nt.status, nt.text); assertEquals("CONFLICT", nt.json["code"].asString()); assertTrue(nt.json["detail"].asString().startsWith("TEMPLATE_UNAVAILABLE"))
        assertEquals(0, env.count("SELECT count(*) FROM taktovia.inspection_run WHERE tenant_id=? AND shift_id=?", w.tenant.value, sb))
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND event_type='inspection.template_unavailable'", w.tenant.value))
        // Eingabefehler: 422 mit Feldverweis (Schema), Fahrzeug nicht das der Schicht: 422
        val bad = create(w, """{"shift_id":"$shift","vehicle_id":"$vehicle","kind":"NIE","client_started_at":"gestern"}""")
        assertEquals(422, bad.status, bad.text)
        assertEquals(422, create(w, w.body(shift, w.vehicle("PKW"))).status)
        assertFalse(env.count("SELECT count(*) FROM taktovia.inspection_run WHERE tenant_id=?", w.tenant.value) > 0)
    }
}

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
 * LH-6.22-DMG-001 / DMG-002, WP-M3-43 über die echte Anwendung (HTTP, Runtime-Rolle, Test-Issuer): Rechte (Fahrer, Disposition, Werkstatt melden; Support und Fahrgast nicht),
 * Anlage ohne Buchung (201, ETag, Fallnummer), 422 mit Feldverweis, Idempotenz über `Idempotency-Key`, Fahrerbezug (404), Listenfilter über Fahrzeug und Datum, fremder Mandant 404.
 * Spätere Arbeitspakete (WP-M3-44/-47/-48) ergänzen ihre Fälle in eigenen Klassen.
 */
class DefectApiTest {
    private val env = IdentityEnv
    private fun idem(k: String = "k-" + UUID.randomUUID()) = mapOf("Idempotency-Key" to k)

    class World(val env: IdentityEnv) {
        val tenant: TenantId = env.tenant()
        val dispatcher = UUID.randomUUID(); val workshop = UUID.randomUUID(); val driver = UUID.randomUUID(); val support = UUID.randomUUID(); val passenger = UUID.randomUUID()
        val admin = UUID.randomUUID()
        init {
            env.grant(tenant, dispatcher, "dispatcher"); env.grant(tenant, workshop, "workshop"); env.grant(tenant, driver, "driver"); env.grant(tenant, support, "support")
            env.grant(tenant, passenger, "passenger"); env.grant(tenant, admin, "tenant_admin")
        }
        val dispatcherToken = env.token(dispatcher); val workshopToken = env.token(workshop); val driverToken = env.token(driver); val supportToken = env.token(support)
        val passengerToken = env.token(passenger); val adminToken = env.token(admin)
        val vehicle: UUID = vehicle()
        fun vehicle(): UUID = UUID.randomUUID().also { id -> env.tx.run(tenant) { j -> j.update("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (?,?,'fixture','{}')", tenant.value, id) } }
        fun shift(driver: UUID, vehicle: UUID = this.vehicle) = env.exec("INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state) VALUES (?,?,?,?,now()-interval '1 hour',now()+interval '7 hours','Europe/Berlin',0,'PUBLISHED')", tenant.value, UUID.randomUUID(), driver, vehicle)
        fun body(vehicle: UUID = this.vehicle, category: String = "BODY", extra: String = "", occurred: Instant = Instant.now().minusSeconds(3600), description: String = "Kratzer an der Beifahrertür") =
            """{"vehicle_id":"$vehicle","occurred_at":"$occurred","category_code":"$category","description":"$description"$extra}"""
    }

    private fun create(w: World, body: String = w.body(), token: String = w.dispatcherToken, key: Map<String, String> = idem()) = env.post("/v1/defects", body, token, null, key)

    @Test
    fun meldung_ohne_buchung_201_mit_fallnummer_etag_und_lesen() {
        val w = World(env)
        val c = create(w)
        assertEquals(201, c.status, c.text); assertEquals("\"1\"", c.header("ETag"))
        val id = c.json["id"].asString(); assertEquals("/v1/defects/$id", c.header("Location"))
        assertTrue(Regex("^SCH-\\d{4}-\\d{6}$").matches(c.json["case_number"].asString()))
        assertEquals("NEW", c.json["status"].asString()); assertEquals(w.dispatcher.toString(), c.json["reported_by"].asString())
        assertEquals(0, env.count("SELECT count(*) FROM taktovia.booking WHERE tenant_id=?", w.tenant.value))
        val g = env.get("/v1/defects/$id", w.workshopToken)
        assertEquals(200, g.status, g.text); assertEquals("\"1\"", g.header("ETag")); assertEquals(c.json["case_number"].asString(), g.json["case_number"].asString())
        assertEquals("Kratzer an der Beifahrertür", g.json["description"].asString())
        // Auffindbar über Fahrzeug und Datum
        val from = Instant.now().minusSeconds(3600); val to = Instant.now().plusSeconds(3600)
        val other = w.vehicle(); create(w, w.body(other))
        val l = env.get("/v1/defects?vehicle_id=${w.vehicle}&reported_from=$from&reported_to=$to", w.supportToken.let { w.workshopToken })
        assertEquals(200, l.status, l.text); assertEquals(listOf(id), l.json["items"].values().map { it["id"].asString() })
        assertEquals(0, env.get("/v1/defects?vehicle_id=${w.vehicle}&reported_from=$to", w.workshopToken).json["items"].size())
        assertEquals(422, env.get("/v1/defects?reported_from=gestern", w.workshopToken).status)
        assertEquals(422, env.get("/v1/defects?status=KAPUTT", w.workshopToken).status)
    }

    @Test
    fun rechte_fahrgast_support_und_ohne_anmeldung() {
        val w = World(env)
        assertEquals(403, create(w, token = w.passengerToken).status)
        assertEquals(403, create(w, token = w.supportToken).status)
        assertEquals(403, create(w, token = w.adminToken).status)
        assertEquals(401, env.server.post("/v1/defects", w.body(), mapOf("Idempotency-Key" to "k-" + UUID.randomUUID())).status)
        assertEquals(403, env.get("/v1/defects", w.passengerToken).status)
        assertEquals(0, env.count("SELECT count(*) FROM taktovia.defect_case WHERE tenant_id=?", w.tenant.value))
        // Werkstatt meldet
        assertEquals(201, create(w, token = w.workshopToken).status)
    }

    @Test
    fun fehlende_pflichtfelder_zukunftszeit_und_clientwerte_ergeben_422_mit_feldverweis() {
        val w = World(env)
        for (f in listOf("vehicle_id", "occurred_at", "category_code", "description")) {
            val body = w.body().replace(Regex(""""$f":"[^"]*",?"""), "").replace(",}", "}")
            val r = create(w, body)
            assertEquals(422, r.status, "$f: ${r.text}")
            assertTrue(r.json["violations"].values().any { it["pointer"].asString() == "/$f" }, "$f: ${r.text}")
        }
        val future = create(w, w.body(occurred = Instant.now().plusSeconds(7200)))
        assertEquals(422, future.status, future.text); assertEquals("/occurred_at", future.json["violations"][0]["pointer"].asString())
        // ein fremder Mandant im Body ist „nicht vorhanden“ (404, Sicherheitsereignis), der eigene ein unbekanntes Feld (422)
        assertEquals(404, create(w, w.body(extra = ""","tenant_id":"${UUID.randomUUID()}"""")).status)
        for (k in listOf("tenant_id" to w.tenant.value, "reported_by" to UUID.randomUUID(), "status" to "CLOSED", "case_number" to "SCH-2000-000001")) {
            val r = create(w, w.body(extra = ""","${k.first}":"${k.second}""""))
            assertEquals(422, r.status, "${k.first}: ${r.text}")
        }
        assertEquals(422, create(w, w.body(category = "ERFUNDEN")).status)
        assertEquals(0, env.count("SELECT count(*) FROM taktovia.defect_case WHERE tenant_id=?", w.tenant.value))
    }

    @Test
    fun gleicher_idempotency_key_erzeugt_keine_zweite_akte() {
        val w = World(env); val key = idem(); val same = w.body()
        val a = create(w, same, key = key); val b = create(w, same, key = key)
        assertEquals(201, a.status, a.text); assertEquals(201, b.status, b.text); assertEquals("true", b.header("Idempotent-Replayed")); assertEquals(a.json["id"].asString(), b.json["id"].asString())
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.defect_case WHERE tenant_id=?", w.tenant.value))
        // derselbe Schlüssel mit anderem Inhalt: Konflikt, keine zweite Akte
        val c = create(w, w.body(category = "GLASS"), key = key)
        assertEquals(409, c.status, c.text)
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.defect_case WHERE tenant_id=?", w.tenant.value))
        // dieselbe Geräteereignis-ID mit neuem Schlüssel: dieselbe Akte, 200
        val ev = UUID.randomUUID()
        val d = create(w, w.body(extra = ""","device_event_id":"$ev""""), key = idem()); val e = create(w, w.body(extra = ""","device_event_id":"$ev""""), key = idem())
        assertEquals(201, d.status, d.text); assertEquals(200, e.status, e.text); assertEquals(d.json["id"].asString(), e.json["id"].asString())
        assertEquals(2, env.count("SELECT count(*) FROM taktovia.defect_case WHERE tenant_id=?", w.tenant.value))
    }

    @Test
    fun fahrer_nur_mit_fahrzeugbezug_und_nur_eigene_akten() {
        val w = World(env)
        val noRelation = create(w, token = w.driverToken)
        assertEquals(404, noRelation.status, noRelation.text)
        w.shift(w.driver)
        val ok = create(w, token = w.driverToken)
        assertEquals(201, ok.status, ok.text); assertEquals(w.driver.toString(), ok.json["reported_by"].asString())
        val foreign = create(w).json["id"].asString()
        assertEquals(404, env.get("/v1/defects/$foreign", w.driverToken).status)
        assertEquals(listOf(ok.json["id"].asString()), env.get("/v1/defects", w.driverToken).json["items"].values().map { it["id"].asString() })
        assertEquals(200, env.get("/v1/defects/$foreign", w.dispatcherToken).status)
    }

    @Test
    fun fremder_mandant_ist_404_und_fremdes_fahrzeug_nicht_meldbar() {
        val a = World(env); val b = World(env)
        val da = create(a).json["id"].asString()
        assertEquals(404, env.get("/v1/defects/$da", b.dispatcherToken).status)
        assertEquals(0, env.get("/v1/defects", b.dispatcherToken).json["items"].size())
        assertEquals(404, create(b, b.body(a.vehicle)).status)
        assertFalse(env.get("/v1/defects", b.dispatcherToken).text.contains(da))
        assertEquals(0, env.count("SELECT count(*) FROM taktovia.defect_case WHERE tenant_id=? AND vehicle_id=?", b.tenant.value, a.vehicle))
    }
}

package de.taktovia.app.identity

import de.taktovia.platform.tenant.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * LH-I.4-MUL-004 / WP-M1-34 über die echte Anwendung (HTTP, Runtime-Rolle, Test-Issuer statt Keycloak): Rollenzuordnung nur für berechtigte Rollen
 * (Disponent und Werkstatt schreiben, Support liest), vier Rollen je Fahrzeug, Wechsel zum Stichtag mit As-of-Abfrage, 404/409/422, genau ein Outbox-Ereignis und Audit je Änderung.
 */
class VehicleRolesApiTest {
    private val env = IdentityEnv
    private fun idem() = mapOf("Idempotency-Key" to "k-" + UUID.randomUUID())

    /** Mandant mit Disponent (schreibt), Support (liest), Fahrgast, vier Organisationen und einem Fixture-Fahrzeug (Stammteil, M3; vom Administrator angelegt). */
    private class World(val env: IdentityEnv) {
        val tenant = env.tenant(); val dispatcher = UUID.randomUUID(); val support = UUID.randomUUID(); val passenger = UUID.randomUUID()
        val vehicle = UUID.randomUUID(); val orgs = List(5) { UUID.randomUUID() }
        init {
            env.grant(tenant, dispatcher, "dispatcher"); env.grant(tenant, support, "support"); env.grant(tenant, passenger, "passenger")
            val le = UUID.randomUUID()
            env.tx.run(tenant) { j ->
                j.update("INSERT INTO taktovia.legal_entity(tenant_id,id,name,country) VALUES (?,?,'LE','DE')", tenant.value, le)
                orgs.forEachIndexed { i, o -> j.update("INSERT INTO taktovia.organization(tenant_id,id,legal_entity_id,name,federation_ref,roles) VALUES (?,?,?,?,?,CAST(? AS text[]))", tenant.value, o, le, "Org $i", "f-$o", "{TRANSPORT_COMPANY}") }
                j.update("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (?,?,'fixture','{}')", tenant.value, vehicle)
            }
        }
        val dispatcherToken = env.token(dispatcher); val supportToken = env.token(support); val passengerToken = env.token(passenger)
    }

    private fun assign(w: World, role: String, org: UUID, from: String, to: String? = null, vehicle: UUID = w.vehicle, token: String = w.dispatcherToken) =
        env.post("/v1/vehicle-roles", """{"vehicle_id":"$vehicle","role_code":"$role","organization_id":"$org","valid_from":"$from"${if (to != null) ""","valid_to":"$to"""" else ""}}""", token, null, idem())

    @Test
    fun vier_rollen_je_einzeln_aenderbar_und_as_of_ueber_die_api() {
        val w = World(env)
        val roles = listOf("OWNER", "OPERATOR", "DISPATCH_PROVIDER", "BILLING_PARTNER")
        roles.forEachIndexed { i, r ->
            val c = assign(w, r, w.orgs[i], "2026-01-01T00:00:00Z")
            assertEquals(201, c.status, c.text); assertEquals("\"1\"", c.header("ETag")); assertEquals("/v1/vehicle-roles/${c.json["id"].asString()}", c.header("Location"))
            assertEquals(r, c.json["role_code"].asString()); assertEquals(w.orgs[i].toString(), c.json["organization_id"].asString())
        }
        // Eigentümerwechsel zum Stichtag
        val change = assign(w, "OWNER", w.orgs[4], "2030-07-01T00:00:00Z")
        assertEquals(201, change.status, change.text)
        fun ownerAt(ts: String): String? = env.get("/v1/vehicle-roles?vehicle_id=${w.vehicle}&as_of=$ts", w.supportToken).json["items"].values().firstOrNull { it["role_code"].asString() == "OWNER" }?.get("organization_id")?.asString()
        assertEquals(w.orgs[0].toString(), ownerAt("2030-06-30T23:59:59Z"), "vor dem Stichtag gilt weiterhin der alte Eigentümer")
        assertEquals(w.orgs[4].toString(), ownerAt("2030-07-01T00:00:00Z"), "ab dem Stichtag gilt der neue")
        assertEquals(null, ownerAt("2025-12-31T23:59:59Z"), "vor allem Beginn gilt keiner")
        val all = env.get("/v1/vehicle-roles?vehicle_id=${w.vehicle}", w.supportToken)
        assertEquals(200, all.status); assertEquals(5, all.json["items"].size())
        val closed = all.json["items"].values().single { it["role_code"].asString() == "OWNER" && it["organization_id"].asString() == w.orgs[0].toString() }
        assertEquals("2030-07-01T00:00:00Z", closed["valid_to"].asString()); assertEquals(2L, closed["revision"].asLong())
        // genau ein Outbox-Ereignis und ein Audit je Änderung (4 + 1), Akteur = Disponent
        assertEquals(5, env.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND event_type='vehicle.role_assignment_changed'", w.tenant.value))
        assertEquals(5, env.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='fleet.vehicle_role.changed' AND actor_ref=?", w.tenant.value, "user:${w.dispatcher}"))
    }

    @Test
    fun aenderung_ohne_recht_403_und_ohne_wirkung() {
        val w = World(env)
        assertEquals(403, assign(w, "OWNER", w.orgs[0], "2026-01-01T00:00:00Z", token = w.supportToken).status, "Support darf nur lesen")
        assertEquals(403, assign(w, "OWNER", w.orgs[0], "2026-01-01T00:00:00Z", token = w.passengerToken).status)
        assertEquals(403, env.get("/v1/vehicle-roles", w.passengerToken).status)
        assertEquals(200, env.get("/v1/vehicle-roles", w.supportToken).status)
        assertEquals(0, env.count("SELECT count(*) FROM taktovia.vehicle_assignment WHERE tenant_id=?", w.tenant.value))
        assertEquals(0, env.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND event_type='vehicle.role_assignment_changed'", w.tenant.value))
    }

    @Test
    fun fremder_mandant_404_ueberlappung_409_rueckwirkend_422() {
        val a = World(env); val b = World(env)
        assertEquals(404, assign(a, "OWNER", b.orgs[0], "2026-01-01T00:00:00Z").status, "Organisation eines fremden Mandanten")
        assertEquals(404, assign(a, "OWNER", a.orgs[0], "2026-01-01T00:00:00Z", vehicle = b.vehicle).status, "Fahrzeug eines fremden Mandanten")
        assertEquals(404, assign(a, "OWNER", a.orgs[0], "2026-01-01T00:00:00Z", vehicle = UUID.randomUUID()).status)
        assertEquals(0, env.count("SELECT count(*) FROM taktovia.vehicle_assignment WHERE tenant_id IN (?, ?)", a.tenant.value, b.tenant.value))
        assertEquals(0, env.get("/v1/vehicle-roles?vehicle_id=${b.vehicle}", a.supportToken).json["items"].size())

        assertEquals(201, assign(a, "OWNER", a.orgs[0], "2026-01-01T00:00:00Z", "2026-06-01T00:00:00Z").status)
        assertEquals(201, assign(a, "OWNER", a.orgs[1], "2026-06-01T00:00:00Z", "2030-07-01T00:00:00Z").status)
        assertEquals(409, assign(a, "OWNER", a.orgs[2], "2029-01-01T00:00:00Z").status, "Überlappung mit laufendem/künftigem Intervall")
        val retro = assign(a, "OWNER", a.orgs[2], "2026-03-01T00:00:00Z", "2026-04-01T00:00:00Z")
        assertEquals(422, retro.status, retro.text); assertTrue(retro.json["violations"].values().any { it["pointer"].asString() == "/valid_from" }, retro.text)
        assertEquals(422, assign(a, "OWNER", a.orgs[2], "2030-08-01T00:00:00Z", "2030-08-01T00:00:00Z").status, "Ende nicht nach Beginn")
        assertEquals(422, env.post("/v1/vehicle-roles", """{"vehicle_id":"${a.vehicle}","role_code":"MIETER","organization_id":"${a.orgs[0]}","valid_from":"2026-01-01T00:00:00Z"}""", a.dispatcherToken, null, idem()).status, "unbekannte Rolle")
        assertEquals(422, env.get("/v1/vehicle-roles?as_of=gestern", a.supportToken).status)
        assertEquals(2, env.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND event_type='vehicle.role_assignment_changed'", a.tenant.value))
    }

    @Test
    fun paralleler_eigentuemerwechsel_gleicher_stichtag_ueber_http_genau_einer() {
        val w = World(env); assertEquals(201, assign(w, "OWNER", w.orgs[0], "2026-01-01T00:00:00Z").status)
        val pool = Executors.newFixedThreadPool(6); val start = CountDownLatch(1)
        val fut = (1..6).map { n -> pool.submit<Int> { start.await(); assign(w, "OWNER", w.orgs[1 + n % 4], "2030-07-01T00:00:00Z").status } }
        start.countDown()
        val r = fut.map { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(1, r.count { it == 201 }, r.toString()); assertEquals(5, r.count { it == 409 }, r.toString())
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.vehicle_assignment WHERE tenant_id=? AND role='OWNER' AND valid_to IS NULL", w.tenant.value))
        assertEquals(2, env.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND event_type='vehicle.role_assignment_changed'", w.tenant.value))
    }

    @Test
    fun liste_ist_seitenweise_und_filtergebunden() {
        val w = World(env)
        listOf("OWNER", "OPERATOR", "DISPATCH_PROVIDER", "BILLING_PARTNER").forEachIndexed { i, r -> assign(w, r, w.orgs[i], "2026-01-01T00:00:00Z") }
        val p1 = env.get("/v1/vehicle-roles?limit=3&vehicle_id=${w.vehicle}", w.supportToken)
        assertEquals(3, p1.json["items"].size()); assertTrue(!p1.json["next_cursor"].isNull)
        val p2 = env.get("/v1/vehicle-roles?limit=3&vehicle_id=${w.vehicle}&cursor=" + p1.json["next_cursor"].asString(), w.supportToken)
        assertEquals(1, p2.json["items"].size()); assertTrue(p2.json["next_cursor"].isNull)
        // der Cursor gehört zum Filter: ohne den Fahrzeugfilter ist er ungültig
        assertEquals(400, env.get("/v1/vehicle-roles?limit=3&cursor=" + p1.json["next_cursor"].asString(), w.supportToken).status)
        assertEquals(TenantId(w.tenant.value), w.tenant)
    }
}

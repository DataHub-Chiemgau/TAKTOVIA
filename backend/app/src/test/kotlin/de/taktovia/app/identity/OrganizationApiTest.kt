package de.taktovia.app.identity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * LH-I.4-MUL-003 / WP-M1-05 über die echte Anwendung (HTTP, Runtime-Rolle, Test-Issuer statt Keycloak): vier Organisationen mit getrennten
 * vertraglichen Rollen, 422/404/409/412, Mandantenanlage der Plattformverwaltung ohne Fachdatensicht, gesperrter Mandant.
 */
class OrganizationApiTest {
    private val env = IdentityEnv
    private fun idem() = mapOf("Idempotency-Key" to "k-" + UUID.randomUUID())

    private class World(val env: IdentityEnv) {
        val tenant = env.tenant(); val admin = UUID.randomUUID(); val passenger = UUID.randomUUID()
        init { env.grant(tenant, admin, "tenant_admin"); env.grant(tenant, passenger, "passenger") }
        val token = env.token(admin)
        fun legalEntity(name: String = "Rechtsträger"): String {
            val r = env.post("/v1/legal-entities", """{"name":"$name","country":"DE"}""", token, null, mapOf("Idempotency-Key" to "le-" + UUID.randomUUID()))
            check(r.status == 201) { r.text }
            return r.json["id"].asString()
        }
    }

    private fun createOrg(w: World, le: String, roles: String, ref: String = "fed-" + UUID.randomUUID(), name: String = "Org") =
        env.post("/v1/organizations", """{"legal_entity_id":"$le","name":"$name","federation_ref":"$ref","roles":$roles}""", w.token, null, idem())

    @Test
    fun vier_organisationen_speichern_vertragliche_rollen_getrennt_ueber_die_api() {
        val w = World(env); val le = w.legalEntity()
        val roles = listOf("PLATFORM_OPERATOR", "TASK_AUTHORITY", "BROKER", "ACTUAL_CARRIER")
        val ids = roles.map { r ->
            val c = createOrg(w, le, """["$r"]""", name = "Org $r")
            assertEquals(201, c.status, c.text); assertEquals("\"1\"", c.header("ETag"))
            assertEquals("/v1/organizations/${c.json["id"].asString()}", c.header("Location"))
            c.json["id"].asString()
        }
        ids.zip(roles).forEach { (id, r) ->
            val got = env.get("/v1/organizations/$id", w.token)
            assertEquals(200, got.status); assertEquals(listOf(r), got.json["roles"].values().map { it.asString() })
        }
        val list = env.get("/v1/organizations?limit=10", w.token)
        assertEquals(4, list.json["items"].size()); assertEquals(roles.toSet(), list.json["items"].values().flatMap { it["roles"].values().map { r -> r.asString() } }.toSet())
        // eine Organisation mit mehreren Rollen
        assertEquals(setOf("BROKER", "CLIENT"), createOrg(w, le, """["BROKER","CLIENT"]""").json["roles"].values().map { it.asString() }.toSet())
    }

    @Test
    fun unbekannte_rolle_ergibt_422_mit_pointer() {
        val w = World(env); val le = w.legalEntity()
        for (bad in listOf("""["OPERATOR"]""", """["transport_company"]""", """[]""", """["CLIENT","CLIENT"]""", """["CLIENT","KUNDE"]""")) {
            val r = createOrg(w, le, bad)
            assertEquals(422, r.status, "$bad: ${r.text}")
            assertTrue(r.json["violations"].values().any { it["pointer"].asString().startsWith("/roles") }, r.text)
        }
        assertEquals(0, env.count("SELECT count(*) FROM taktovia.organization WHERE tenant_id=?", w.tenant.value))
        val ok = createOrg(w, le, """["CLIENT"]""")
        assertEquals(422, env.put("/v1/organizations/${ok.json["id"].asString()}", """{"legal_entity_id":"$le","name":"x","roles":["KUNDE"]}""", w.token, "\"1\"").status)
    }

    @Test
    fun fremder_mandant_ist_weder_les_noch_referenzierbar_404() {
        val a = World(env); val b = World(env)
        val leA = a.legalEntity(); val leB = b.legalEntity()
        val orgB = createOrg(b, leB, """["CLIENT"]""").json["id"].asString()
        assertEquals(404, env.get("/v1/organizations/$orgB", a.token).status)
        assertEquals(404, env.put("/v1/organizations/$orgB", """{"legal_entity_id":"$leA","name":"x","roles":["CLIENT"]}""", a.token, "\"1\"").status)
        assertEquals(404, env.put("/v1/legal-entities/$leB", """{"name":"x","country":"DE"}""", a.token, "\"1\"").status)
        assertEquals(404, createOrg(a, leB, """["CLIENT"]""").status, "Rechtsträger eines fremden Mandanten ist nicht referenzierbar")
        assertTrue(env.get("/v1/organizations", a.token).json["items"].values().none { it["id"].asString() == orgB })
        assertTrue(env.get("/v1/legal-entities", a.token).json["items"].values().none { it["id"].asString() == leB })
        assertEquals(1, env.get("/v1/organizations/$orgB", b.token).json["revision"].asLong())
    }

    @Test
    fun rechte_doppelte_federation_ref_und_revisionen() {
        val w = World(env); val le = w.legalEntity()
        val passengerToken = env.token(w.passenger)
        assertEquals(403, env.post("/v1/organizations", """{"legal_entity_id":"$le","name":"x","federation_ref":"f","roles":["CLIENT"]}""", passengerToken, null, idem()).status)
        assertEquals(403, env.get("/v1/organizations", passengerToken).status)
        val ref = "dup-" + UUID.randomUUID()
        val first = createOrg(w, le, """["CLIENT"]""", ref); assertEquals(201, first.status)
        assertEquals(409, createOrg(w, le, """["CLIENT"]""", ref).status)
        val id = first.json["id"].asString()
        assertEquals(428, env.server.send("PUT", "/v1/organizations/$id", """{"legal_entity_id":"$le","name":"x","roles":["CLIENT"]}""", env.headers(w.token, extra = idem())).status)
        val ok = env.put("/v1/organizations/$id", """{"legal_entity_id":"$le","name":"Neu","roles":["CLIENT","BROKER"]}""", w.token, "\"1\"")
        assertEquals(200, ok.status, ok.text); assertEquals("\"2\"", ok.header("ETag"))
        assertEquals(412, env.put("/v1/organizations/$id", """{"legal_entity_id":"$le","name":"Alt","roles":["CLIENT"]}""", w.token, "\"1\"").status)
        // Audit und Ereignis: Anlegen + Ändern je genau eines
        assertEquals(2, env.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND object_ref=? AND action='identity.organization.changed'", w.tenant.value, "organization:$id"))
        assertEquals(2, env.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND aggregate_id=? AND event_type='organization.changed'", w.tenant.value, UUID.fromString(id)))
        // Rechtsträger: Update mit If-Match
        val leUpdate = env.put("/v1/legal-entities/$le", """{"name":"Neuer Name","country":"AT"}""", w.token, "\"1\"")
        assertEquals(200, leUpdate.status, leUpdate.text); assertEquals(2L, leUpdate.json["revision"].asLong())
        assertEquals(412, env.put("/v1/legal-entities/$le", """{"name":"x","country":"AT"}""", w.token, "\"1\"").status)
    }

    @Test
    fun paralleles_anlegen_derselben_federation_ref_ueber_http_genau_ein_erfolg() {
        val w = World(env); val le = w.legalEntity(); val ref = "par-" + UUID.randomUUID()
        val pool = Executors.newFixedThreadPool(6); val start = CountDownLatch(1)
        val fut = (1..6).map { pool.submit<Int> { start.await(); createOrg(w, le, """["BROKER"]""", ref).status } }
        start.countDown()
        val r = fut.map { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(1, r.count { it == 201 }, r.toString()); assertEquals(5, r.count { it == 409 }, r.toString())
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND event_type='organization.changed' AND payload->>'federation_ref'=?", w.tenant.value, ref))
    }

    private fun createTenant(opToken: String, firstAdmin: UUID, name: String = "Mandant " + UUID.randomUUID()) = env.post("/v1/platform/tenants",
        """{"name":"$name","legal_entity":{"name":"Stadtwerke GmbH","country":"DE"},"first_admin":{"issuer":"${env.idp.issuer}","subject":"$firstAdmin"},"contact_email":"info@example.org"}""",
        opToken, null, idem())

    @Test
    fun mandantenanlage_nur_durch_plattformbetreiber_und_ohne_fachdatensicht() {
        val op = env.operator(); val opToken = env.token(op); val firstAdmin = UUID.randomUUID()
        val created = createTenant(opToken, firstAdmin)
        assertEquals(201, created.status, created.text)
        val tenant = UUID.fromString(created.json["id"].asString())
        assertEquals("ACTIVE", created.json["status"].asString())
        // der erste Administrator arbeitet sofort im neuen Mandanten
        val me = env.get("/v1/me", env.token(firstAdmin)); assertEquals(200, me.status); assertEquals(tenant.toString(), me.json["tenant_id"].asString())
        assertEquals(200, env.get("/v1/organizations", env.token(firstAdmin)).status)
        // der Betreiber hat keine Mitgliedschaft und damit keine Fachdatensicht (auch nicht per manipuliertem Mandantenkontext)
        assertEquals(403, env.get("/v1/me", opToken).status)
        for (path in listOf("/v1/organizations", "/v1/legal-entities", "/v1/memberships")) assertEquals(403, env.get(path, opToken).status, path)
        // Audit: Anlage durch den Betreiber im neuen Mandanten
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='identity.tenant.created' AND actor_ref=?", tenant, "user:$op"))
        // Mandantenadministrator, Nichtbetreiber ohne Mitgliedschaft, Betreiber ohne zweiten Faktor
        assertEquals(403, createTenant(env.token(firstAdmin), UUID.randomUUID()).status)
        assertEquals(403, createTenant(env.token(UUID.randomUUID()), UUID.randomUUID()).status)
        assertEquals(403, createTenant(env.token(op, acr = "loa1"), UUID.randomUUID()).status)
        assertEquals(401, env.post("/v1/platform/tenants", "{}", "kein-token", null, idem()).status)
        // Eingabefehler 422 ohne Anlage
        val bad = env.post("/v1/platform/tenants", """{"name":"x","legal_entity":{"name":"y","country":"Deutschland"},"first_admin":{"issuer":"i","subject":"s"},"contact_email":"a@b.de"}""", opToken, null, idem())
        assertEquals(422, bad.status)
        assertEquals(0, env.count("SELECT count(*) FROM taktovia.tenant WHERE name='x'"))
    }

    @Test
    fun suspended_mandant_verweigert_jeden_fachzugriff_und_wechsel_ist_auditiert() {
        val op = env.operator(); val opToken = env.token(op); val admin = UUID.randomUUID()
        val tenant = createTenant(opToken, admin).json["id"].asString()
        val adminToken = env.token(admin)
        assertEquals(200, env.get("/v1/organizations", adminToken).status)
        fun change(status: String, token: String = opToken, target: String = tenant) =
            env.post("/v1/platform/tenant-status-changes", """{"managed_tenant_id":"$target","status":"$status"}""", token, null, idem())

        assertEquals(403, change("SUSPENDED", adminToken).status, "Mandantenadministrator darf den Status nicht ändern")
        val s = change("SUSPENDED"); assertEquals(200, s.status, s.text)
        assertEquals("ACTIVE", s.json["from"].asString()); assertEquals("SUSPENDED", s.json["status"].asString())
        for (path in listOf("/v1/organizations", "/v1/legal-entities", "/v1/memberships", "/v1/me")) assertEquals(403, env.get(path, adminToken).status, "$path in SUSPENDED")
        assertEquals(200, change("ACTIVE").status)
        assertEquals(200, env.get("/v1/organizations", adminToken).status)
        assertEquals(200, change("CLOSED").status)
        assertEquals(403, env.get("/v1/organizations", adminToken).status)
        assertEquals(409, change("ACTIVE").status, "CLOSED ist endgültig")
        assertEquals(404, change("SUSPENDED", target = UUID.randomUUID().toString()).status)
        assertEquals(422, change("GELOESCHT").status)
        assertEquals(3, env.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='identity.tenant.status_changed' AND actor_ref=?", UUID.fromString(tenant), "user:$op"))
    }
}

package de.taktovia.app.identity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * LH-I.7-CFG-001 / WP-M1-24 über die echte Anwendung (HTTP, Runtime-Rolle, Test-Issuer statt Keycloak): Der Mandantenadministrator ändert Kontakt und
 * Standardwährung ohne Eingriff des Plattformbetreibers; Währungsprüfung, Rechte, fremder Mandant, ETag/412, Audit, Mandant B unverändert.
 */
class TenantProfileApiTest {
    private val env = IdentityEnv
    private fun idem() = mapOf("Idempotency-Key" to "k-" + UUID.randomUUID())

    /** Ein Mandant, wie ihn die Plattformverwaltung anlegt (Profilrevision 1 inklusive), samt Administrator; der Betreiber tritt danach nicht mehr auf. */
    private class World(val env: IdentityEnv, email: String = "info@example.org") {
        val admin = UUID.randomUUID(); val tenant: UUID
        init {
            val op = env.operator()
            val r = env.post("/v1/platform/tenants", """{"name":"Mandant ${UUID.randomUUID()}","legal_entity":{"name":"Stadtwerke GmbH","country":"DE"},"first_admin":{"issuer":"${env.idp.issuer}","subject":"$admin"},"contact_email":"$email"}""",
                env.token(op), null, mapOf("Idempotency-Key" to "w-" + UUID.randomUUID()))
            check(r.status == 201) { r.text }
            tenant = UUID.fromString(r.json["id"].asString())
        }
        val adminToken = env.token(admin)
    }

    private fun body(email: String = "neu@example.org", currency: String = "CHF", name: String = "Stadtwerke Beispiel GmbH") =
        """{"legal_name":"$name","contact_email":"$email","default_currency":"$currency","address":"Musterstraße 1, 12345 Musterstadt"}"""

    @Test
    fun administrator_aendert_kontakt_und_waehrung_ohne_plattformrolle() {
        val w = World(env)
        val first = env.get("/v1/company-profile", w.adminToken)
        assertEquals(200, first.status, first.text); assertEquals("\"1\"", first.header("ETag"))
        assertEquals("info@example.org", first.json["contact_email"].asString()); assertEquals("EUR", first.json["default_currency"].asString())
        // Nur das Token des Mandantenadministrators: kein Betreiberkonto, keine Plattformrolle
        val put = env.put("/v1/company-profile", body(), w.adminToken, "\"1\"")
        assertEquals(200, put.status, put.text); assertEquals("\"2\"", put.header("ETag")); assertEquals(2L, put.json["revision"].asLong())
        val reloaded = env.get("/v1/company-profile", w.adminToken)
        assertEquals("neu@example.org", reloaded.json["contact_email"].asString()); assertEquals("CHF", reloaded.json["default_currency"].asString()); assertEquals("\"2\"", reloaded.header("ETag"))
        // Audit mit Akteur und Zeitpunkt; Ereignis genau einmal
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='config.tenant_profile.changed' AND actor_ref=? AND occurred_at IS NOT NULL AND redacted_changes->>'revision'='2'", w.tenant, "user:${w.admin}"))
        assertEquals(2, env.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND event_type='tenant.profile_changed'", w.tenant))
    }

    @Test
    fun ungueltige_waehrung_ergibt_feldfehler_und_aendert_nichts() {
        val w = World(env)
        for (bad in listOf("XXY", "EU", "eur")) {
            val r = env.put("/v1/company-profile", body(currency = bad), w.adminToken, "\"1\"")
            assertEquals(422, r.status, "$bad: ${r.text}")
            assertTrue(r.json["violations"].values().any { it["pointer"].asString() == "/default_currency" }, r.text)
        }
        assertEquals("\"1\"", env.get("/v1/company-profile", w.adminToken).header("ETag"))
        // auch die Mandantenanlage mit unbekannter Währung scheitert vollständig (eine Transaktion)
        val op = env.operator(); val name = "Waehrung-" + UUID.randomUUID()
        val create = env.post("/v1/platform/tenants", """{"name":"$name","legal_entity":{"name":"LE","country":"DE"},"first_admin":{"issuer":"i","subject":"s"},"contact_email":"a@b.de","default_currency":"XXY"}""", env.token(op), null, idem())
        assertEquals(422, create.status, create.text)
        assertEquals(0, env.count("SELECT count(*) FROM taktovia.tenant WHERE name=?", name))
    }

    @Test
    fun nicht_administrator_403_und_fremdes_profil_404() {
        val a = World(env); val b = World(env, email = "b@example.org")
        val dispatcher = UUID.randomUUID(); val passenger = UUID.randomUUID()
        env.grant(UUID.fromString(a.tenant.toString()).let { de.taktovia.platform.tenant.TenantId(it) }, dispatcher, "dispatcher")
        env.grant(de.taktovia.platform.tenant.TenantId(a.tenant), passenger, "passenger")
        // Lesen darf der Disponent, ändern nur der Administrator; der Fahrgast darf weder noch
        assertEquals(200, env.get("/v1/company-profile", env.token(dispatcher)).status)
        assertEquals(403, env.put("/v1/company-profile", body(), env.token(dispatcher), "\"1\"").status)
        assertEquals(403, env.get("/v1/company-profile", env.token(passenger)).status)
        assertEquals(403, env.put("/v1/company-profile", body(), env.token(passenger), "\"1\"").status)
        // das Profil eines fremden Mandanten ist nicht ansprechbar: wie „nicht vorhanden“ (404), nichts ändert sich
        assertEquals(404, env.get("/v1/company-profile?tenant_id=${b.tenant}", a.adminToken).status)
        assertEquals(404, env.server.send("PUT", "/v1/company-profile?tenant_id=${b.tenant}", body(), env.headers(a.adminToken, extra = idem() + ("If-Match" to "\"1\""))).status)
        assertEquals(404, env.server.send("GET", "/v1/company-profile", null, env.headers(a.adminToken, extra = mapOf("X-Tenant-Id" to b.tenant.toString()))).status)
        val inB = env.get("/v1/company-profile", b.adminToken)
        assertEquals("\"1\"", inB.header("ETag")); assertEquals("b@example.org", inB.json["contact_email"].asString())
    }

    @Test
    fun aenderung_in_a_laesst_profil_b_unveraendert_und_etag_regeln() {
        val a = World(env); val b = World(env, email = "b@example.org")
        assertEquals(200, env.put("/v1/company-profile", body(email = "a-neu@example.org"), a.adminToken, "\"1\"").status)
        val inB = env.get("/v1/company-profile", b.adminToken)
        assertEquals("b@example.org", inB.json["contact_email"].asString()); assertEquals("EUR", inB.json["default_currency"].asString()); assertEquals("\"1\"", inB.header("ETag"))
        assertEquals(0, env.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='config.tenant_profile.changed' AND redacted_changes->>'revision'='2'", b.tenant))
        // veralteter ETag 412, fehlender 428
        assertEquals(412, env.put("/v1/company-profile", body(email = "x@example.org"), a.adminToken, "\"1\"").status)
        assertEquals(428, env.server.send("PUT", "/v1/company-profile", body(email = "x@example.org"), env.headers(a.adminToken, extra = idem())).status)
        // unveränderter Inhalt: keine neue Revision
        val same = env.put("/v1/company-profile", body(email = "a-neu@example.org"), a.adminToken, "\"2\"")
        assertEquals(200, same.status); assertEquals("\"2\"", same.header("ETag"))
        assertEquals(2, env.count("SELECT count(*) FROM taktovia.config_revision WHERE tenant_id=? AND scope_kind='TENANT_PROFILE'", a.tenant))
        // Optionale Felder ohne Wert fehlen in der Antwort (keine null-Werte)
        assertFalse(env.get("/v1/company-profile", a.adminToken).json.has("contact_phone"))
    }

    @Test
    fun paralleler_schreibzugriff_ueber_http_liefert_genau_einen_erfolg_und_sonst_412() {
        val w = World(env)
        val pool = Executors.newFixedThreadPool(6); val start = CountDownLatch(1)
        val fut = (1..6).map { n -> pool.submit<Int> { start.await(); env.put("/v1/company-profile", body(email = "p$n@example.org"), w.adminToken, "\"1\"").status } }
        start.countDown()
        val r = fut.map { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(1, r.count { it == 200 }, r.toString()); assertEquals(5, r.count { it == 412 }, r.toString())
        assertEquals(2, env.count("SELECT count(*) FROM taktovia.config_revision WHERE tenant_id=? AND scope_kind='TENANT_PROFILE'", w.tenant))
    }
}

package de.taktovia.app.identity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** LH-6.17-INT-002 / WP-M1-06: Mitgliedschaften erteilen und entziehen (UI-18-Basis) über die echte Anwendung. */
class MembershipAdminApiTest {
    private val env = IdentityEnv
    private fun idem() = mapOf("Idempotency-Key" to "k-" + UUID.randomUUID())
    private fun create(token: String, subject: String, role: String = "dispatcher", extra: String = "", context: String? = null) =
        env.post("/v1/memberships", """{"issuer":"${env.idp.issuer}","subject":"$subject","role_code":"$role"$extra}""", token, context, idem())

    @Test
    fun erteilen_und_entziehen_ueber_die_api_auditiert_mit_ereignissen() {
        val t = env.tenant(); val admin = UUID.randomUUID(); val newcomer = UUID.randomUUID()
        env.grant(t, admin, "tenant_admin")
        val adminToken = env.token(admin)

        val created = create(adminToken, newcomer.toString(), "support")
        assertEquals(201, created.status, created.text)
        assertEquals("\"1\"", created.header("ETag")); assertEquals("/v1/memberships/${created.json["id"].asString()}", created.header("Location"))
        assertEquals(1L, created.json["revision"].asLong()); assertEquals("support", created.json["role_code"].asString())
        val id = UUID.fromString(created.json["id"].asString())
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND aggregate_id=? AND event_type='membership.granted'", t.value, id))
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND object_ref=? AND action='identity.membership.granted'", t.value, "membership:$id"))

        // die neue Person kann sich jetzt im Mandanten bewegen, die Liste zeigt sie
        assertEquals(200, env.get("/v1/me", env.token(newcomer)).status)
        assertTrue(env.get("/v1/memberships", adminToken).json["items"].values().any { it["id"].asString() == id.toString() })

        val revoked = env.server.send("DELETE", "/v1/memberships/$id", null, env.headers(adminToken, extra = mapOf("If-Match" to "\"1\"") + idem()))
        assertEquals(204, revoked.status, revoked.text)
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND aggregate_id=? AND event_type='membership.revoked'", t.value, id))
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND object_ref=? AND action='identity.membership.revoked'", t.value, "membership:$id"))
        assertEquals(403, env.get("/v1/me", env.token(newcomer)).status, "nach dem Entzug kein Zugriff mehr")
    }

    @Test
    fun fehlerfaelle_403_404_409_412_428_422() {
        val a = env.tenant(); val b = env.tenant(); val admin = UUID.randomUUID(); val passenger = UUID.randomUUID()
        env.grant(a, admin, "tenant_admin"); env.grant(a, passenger, "passenger")
        val foreign = env.grant(b, UUID.randomUUID(), "support")
        val adminToken = env.token(admin)

        assertEquals(403, create(env.token(passenger), "x").status, "ohne Berechtigung")
        assertEquals(403, env.server.send("DELETE", "/v1/memberships/$foreign", null, env.headers(env.token(passenger), extra = mapOf("If-Match" to "\"1\"") + idem())).status)
        assertEquals(404, env.server.send("DELETE", "/v1/memberships/$foreign", null, env.headers(adminToken, extra = mapOf("If-Match" to "\"1\"") + idem())).status, "fremder Mandant: wie nicht vorhanden")

        val subject = "dup-" + UUID.randomUUID()
        val first = create(adminToken, subject)
        assertEquals(201, first.status)
        val dup = create(adminToken, subject)
        assertEquals(409, dup.status); assertEquals("CONFLICT", dup.json["code"].asString())
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND event_type='membership.granted' AND payload->>'subject'=?", a.value, subject))

        val id = first.json["id"].asString()
        assertEquals(428, env.server.send("DELETE", "/v1/memberships/$id", null, env.headers(adminToken, extra = idem())).status, "ohne If-Match")
        assertEquals(412, env.server.send("DELETE", "/v1/memberships/$id", null, env.headers(adminToken, extra = mapOf("If-Match" to "\"9\"") + idem())).status, "veraltete Revision")
        assertEquals(204, env.server.send("DELETE", "/v1/memberships/$id", null, env.headers(adminToken, extra = mapOf("If-Match" to "\"1\"") + idem())).status)
        assertEquals(409, env.server.send("DELETE", "/v1/memberships/$id", null, env.headers(adminToken, extra = mapOf("If-Match" to "\"2\"") + idem())).status, "bereits entzogen")
        assertEquals(422, create(adminToken, "x", role = "Ungültig!").status, "Rollencode-Format")
        assertEquals(422, create(adminToken, "x", extra = ""","valid_from":"2026-01-02T00:00:00Z","valid_to":"2026-01-01T00:00:00Z"""").status, "Gültigkeit umgekehrt")
    }

    @Test
    fun paralleles_erteilen_ueber_http_erzeugt_genau_eine_zeile_und_ein_ereignis() {
        val t = env.tenant(); val admin = UUID.randomUUID(); env.grant(t, admin, "tenant_admin")
        val token = env.token(admin); val subject = "par-" + UUID.randomUUID()
        val pool = Executors.newFixedThreadPool(6); val start = CountDownLatch(1)
        val statuses = (1..6).map { pool.submit<Int> { start.await(); create(token, subject, "support").status } }
        start.countDown()
        val r = statuses.map { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(1, r.count { it == 201 }, r.toString()); assertEquals(5, r.count { it == 409 }, r.toString())
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.membership WHERE tenant_id=? AND subject=?", t.value, subject))
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND event_type='membership.granted' AND payload->>'subject'=?", t.value, subject))
    }

    @Test
    fun privilegierte_rolle_nur_in_der_mitgliedschaft_verlangt_den_zweiten_faktor() {
        val t = env.tenant(); val s = UUID.randomUUID(); env.grant(t, s, "tenant_admin")
        val weak = env.get("/v1/me", env.token(s, acr = "loa1"))
        assertEquals(403, weak.status, "Administrator ohne zweiten Faktor, Rolle steht nur in membership")
        assertTrue(weak.header("WWW-Authenticate")!!.contains("insufficient_user_authentication"))
        assertEquals(1, env.events(t, "security.auth.mfa_missing"))
        assertEquals(200, env.get("/v1/me", env.token(s, acr = "loa2")).status)
        val plain = UUID.randomUUID(); env.grant(t, plain, "support")
        assertEquals(200, env.get("/v1/me", env.token(plain, acr = "loa1")).status, "nicht privilegiert: ein Faktor genügt")
    }

    @Test
    fun liste_ist_seitenweise_und_auf_den_mandanten_begrenzt() {
        val t = env.tenant(); val other = env.tenant(); val admin = UUID.randomUUID()
        env.grant(t, admin, "tenant_admin"); repeat(4) { env.grant(t, UUID.randomUUID(), "support") }; env.grant(other, UUID.randomUUID(), "support")
        val token = env.token(admin)
        val p1 = env.get("/v1/memberships?limit=2", token)
        assertEquals(2, p1.json["items"].size()); assertTrue(!p1.json["next_cursor"].isNull)
        val p2 = env.get("/v1/memberships?limit=10&cursor=" + p1.json["next_cursor"].asString(), token)
        assertEquals(3, p2.json["items"].size()); assertTrue(p2.json["next_cursor"].isNull)
        assertEquals(5, (p1.json["items"].values() + p2.json["items"].values()).map { it["id"].asString() }.toSet().size)
        // der Cursor gehört zu Mandant und Akteur: ein anderer Akteur kann ihn nicht verwenden
        val admin2 = UUID.randomUUID(); env.grant(t, admin2, "tenant_admin")
        assertEquals(400, env.get("/v1/memberships?cursor=" + p1.json["next_cursor"].asString(), env.token(admin2)).status)
    }
}

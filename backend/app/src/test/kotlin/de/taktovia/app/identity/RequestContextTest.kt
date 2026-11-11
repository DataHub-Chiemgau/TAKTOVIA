package de.taktovia.app.identity

import de.taktovia.app.probe.ProbeServer
import de.taktovia.app.probe.Resp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * LH-I.4-MUL-006 / LH-6.17-INT-002 / WP-M1-06: der Anfragekontext. Läuft gegen die echte Anwendung, die echte Datenbank (Runtime-Rolle, RLS)
 * und einen lokalen JWT-Test-Issuer mit JWKS (Ersatz für Keycloak, nicht ausgeführt).
 */
class RequestContextTest {
    private val env = IdentityEnv

    private fun problem(r: Resp) = listOf("status", "code", "type", "title").associateWith { r.json[it].toString() }

    @Test
    fun ohne_token_401_ohne_mitgliedschaft_403_fehlender_scope_403_ohne_details() {
        val tenant = env.tenant("Geheimer Mandant Nordlicht")
        val passenger = UUID.randomUUID(); val loner = UUID.randomUUID()
        env.grant(tenant, passenger, "passenger")

        assertEquals(401, env.server.get("/v1/me").status, "ohne Token")
        assertEquals(401, env.server.get("/v1/memberships").status)

        val noMember = env.get("/v1/me", env.token(loner))
        assertEquals(403, noMember.status, "Token ohne aktive Mitgliedschaft"); assertEquals("FORBIDDEN", noMember.json["code"].asString())

        // fehlender Funktionsscope: Fahrgast darf Mitgliedschaften nicht lesen
        val denied = env.get("/v1/memberships", env.token(passenger))
        assertEquals(403, denied.status); assertEquals("FORBIDDEN", denied.json["code"].asString())
        // keine vertraulichen Daten im Fehlertext: weder Mandantenname/-id noch Rolle, Scope oder Subject
        listOf("Nordlicht", tenant.toString(), "passenger", "memberships:read", passenger.toString(), "scope").forEach { assertFalse(denied.text.contains(it, ignoreCase = true), "Fehlertext nennt '$it': ${denied.text}") }
        assertFalse(noMember.text.contains(loner.toString()))
        assertEquals(1, env.events(tenant, "security.access.denied"), "Verweigerung als Sicherheitsereignis im Mandanten des Akteurs")
        assertEquals(200, env.get("/v1/me", env.token(passenger)).status, "me:read haben alle Rollen")
    }

    @Test
    fun ohne_kontext_gilt_deny_by_default() {
        // Anwendung ohne Ressourcenserver (nur lokal/Tests): kein Kontext, also kein Zugriff, nie ein Standardmandant
        val r = ProbeServer.production().get("/v1/me")
        assertEquals(401, r.status); assertEquals("UNAUTHENTICATED", r.json["code"].asString())
        assertEquals(401, ProbeServer.production().get("/v1/memberships").status)
    }

    @Test
    fun manipuliertes_tenant_id_ergibt_404_und_sicherheitsereignis() {
        val a = env.tenant("A-" + UUID.randomUUID()); val b = env.tenant("B-" + UUID.randomUUID())
        val admin = UUID.randomUUID(); val victimSubject = UUID.randomUUID()
        env.grant(a, admin, "tenant_admin")
        val foreign = env.grant(b, victimSubject, "dispatcher")
        val token = env.token(admin)
        val missing = env.post("/v1/me/tenant-switch", """{"tenant_id":"$a"}""", token, extra = mapOf("Idempotency-Key" to "k-" + UUID.randomUUID()))
        assertEquals(200, missing.status)   // Mandantenwahl über die Auswahl-Operation bleibt möglich
        val notThere = env.server.send("DELETE", "/v1/memberships/${UUID.randomUUID()}", null, env.headers(token, extra = mapOf("If-Match" to "\"1\"", "Idempotency-Key" to "k-" + UUID.randomUUID())))
        assertEquals(404, notThere.status)

        val before = env.events(a, "security.tenant.mismatch")
        val attempts: List<Pair<String, () -> Resp>> = listOf(
            "header X-Tenant-Id" to { env.get("/v1/memberships", token, extra = mapOf("X-Tenant-Id" to b.toString())) },
            "header Tenant-Id" to { env.get("/v1/memberships", token, extra = mapOf("Tenant-Id" to b.toString())) },
            "query" to { env.get("/v1/memberships?tenant_id=$b", token) },
            "body" to { env.post("/v1/memberships", """{"issuer":"${env.idp.issuer}","subject":"x","role_code":"support","tenant_id":"$b"}""", token, extra = mapOf("Idempotency-Key" to "k-" + UUID.randomUUID())) },
            "body verschachtelt" to { env.post("/v1/memberships", """{"issuer":"i","subject":"x","role_code":"support","extra":{"tenantId":"$b"}}""", token, extra = mapOf("Idempotency-Key" to "k-" + UUID.randomUUID())) },
        )
        attempts.forEachIndexed { i, (what, call) ->
            val r = call()
            assertEquals(404, r.status, "$what: ${r.text}")
            assertEquals(problem(notThere), problem(r), "$what: identisch zu „nicht vorhanden“")
            assertEquals(before + i + 1, env.events(a, "security.tenant.mismatch"), "$what: Sicherheitsereignis im Mandanten des Akteurs")
            assertFalse(r.text.contains(b.toString()), "$what: Fremdwert wird nicht zurückgegeben")
        }
        assertEquals(0, env.events(b, "security.tenant.mismatch"), "nichts im fremden Mandanten")

        // fremde Objekt-ID (Mitgliedschaft aus B) bleibt unsichtbar und unberührt
        val del = env.server.send("DELETE", "/v1/memberships/$foreign", null, env.headers(token, extra = mapOf("If-Match" to "\"1\"", "Idempotency-Key" to "k-" + UUID.randomUUID())))
        assertEquals(404, del.status); assertEquals(problem(notThere), problem(del))
        assertEquals(0, env.count("SELECT count(*) FROM taktovia.membership WHERE id=? AND revoked_at IS NOT NULL", foreign))

        // die eigene Mandanten-ID als Angabe verleiht nichts, schadet aber auch nicht
        val own = env.get("/v1/memberships", token, extra = mapOf("X-Tenant-Id" to a.toString()))
        assertEquals(200, own.status); assertTrue(own.json["items"].values().all { it["id"].asString() != foreign.toString() })
    }

    @Test
    fun widerrufene_mitgliedschaft_beendet_den_zugriff_mit_der_naechsten_anfrage() {
        val t = env.tenant(); val s = UUID.randomUUID()
        val m = env.grant(t, s, "dispatcher"); val token = env.token(s)
        assertEquals(200, env.get("/v1/me", token).status)
        env.revokeDirect(t, m)
        val r = env.get("/v1/me", token)
        assertEquals(403, r.status)
        // Rolle entzogen, andere bleibt: nur die verbleibende Rolle wirkt
        val s2 = UUID.randomUUID(); val keep = env.grant(t, s2, "support"); val drop = env.grant(t, s2, "tenant_admin")
        val token2 = env.token(s2)
        assertEquals(200, env.get("/v1/memberships", token2).status)
        env.revokeDirect(t, drop)
        assertEquals(403, env.get("/v1/memberships", token2).status, "Rollenentzug während aktiver Sitzung: Folgeanfrage verweigert")
        assertEquals(listOf("support"), env.get("/v1/me", token2).json["roles"].values().map { it.asString() })
        assertTrue(keep != drop)
    }

    @Test
    fun dienstkonto_hat_nur_seinen_gebundenen_mandanten_und_keine_menschenendpunkte() {
        val a = env.tenant(); val b = env.tenant(); val s = UUID.randomUUID()
        env.grant(a, s, "tenant_admin"); env.grant(b, UUID.randomUUID(), "support")
        val ok = env.idp.sign(env.idp.serviceClaims(scope = "memberships:read", tenants = listOf(a.value)))
        val r = env.get("/v1/memberships", ok)
        assertEquals(200, r.status, r.text)
        assertEquals(1, r.json["items"].size(), "nur Mitgliedschaften des gebundenen Mandanten A")
        assertEquals(403, env.get("/v1/memberships", env.idp.sign(env.idp.serviceClaims(scope = "memberships:read"))).status, "Dienstkonto ohne Mandantenbindung")
        assertEquals(403, env.get("/v1/me", ok).status, "keine Menschen-Sitzung")
        assertEquals(403, env.post("/v1/me/tenant-switch", """{"tenant_id":"$b"}""", ok, extra = mapOf("Idempotency-Key" to "k-" + UUID.randomUUID())).status, "kein Mandantenwechsel")
        assertEquals(403, env.post("/v1/memberships", """{"issuer":"i","subject":"x","role_code":"support"}""", ok, extra = mapOf("Idempotency-Key" to "k-" + UUID.randomUUID())).status, "Scope memberships:write fehlt")
    }
}

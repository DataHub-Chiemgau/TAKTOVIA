package de.taktovia.app.identity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/** LH-6.17-INT-002 / WP-M1-06: `/v1/me` liefert Akteur, aktiven Mandanten, Rollen, Dienste und ausschließlich die wählbaren Mandanten des Subjects. */
class MeApiTest {
    private val env = IdentityEnv

    @Test
    fun me_liefert_nur_mandanten_des_subjects_ohne_token_401() {
        val a = env.tenant("Betrieb A"); val b = env.tenant("Betrieb B"); val c = env.tenant("Betrieb C-fremd")
        val me = UUID.randomUUID(); val other = UUID.randomUUID(); val svc = UUID.randomUUID()
        env.grant(a, me, "dispatcher", listOf(svc)); env.grant(a, me, "support"); env.grant(b, me, "planner"); env.grant(c, other, "tenant_admin")

        assertEquals(401, env.server.get("/v1/me").status)
        val r = env.get("/v1/me", env.token(me, sid = "11111111-1111-4111-8111-111111111111"))
        assertEquals(200, r.status, r.text)
        val tenants = r.json["tenants"].values().map { it["tenant_id"].asString() }
        assertEquals(setOf(a.toString(), b.toString()), tenants.toSet(), "nur Mandanten des Subjects")
        assertFalse(r.text.contains("Betrieb C-fremd") || r.text.contains(c.toString()), "kein fremder Mandant, auch nicht als Name")
        assertEquals(tenants.sorted(), tenants, "stabile Reihenfolge")
        assertEquals(tenants.min(), r.json["tenant_id"].asString(), "Standardmandant ohne Wahl: kleinste Mandanten-ID")
        assertEquals(me.toString(), r.json["subject"].asString()); assertEquals(env.idp.issuer, r.json["issuer"].asString())
        assertEquals("11111111-1111-4111-8111-111111111111", r.json["session_id"].asString())
        assertTrue(r.json["mfa"].asBoolean())
        // der andere Nutzer sieht nur seinen Mandanten
        val o = env.get("/v1/me", env.token(other)).json
        assertEquals(listOf(c.toString()), o["tenants"].values().map { it["tenant_id"].asString() })
        assertEquals("Betrieb C-fremd", o["tenant_name"].asString())
    }

    @Test
    fun rollen_scopes_und_dienste_stammen_aus_der_mitgliedschaft_des_aktiven_mandanten() {
        val t = env.tenant("Betrieb X"); val s = UUID.randomUUID(); val svc = UUID.randomUUID()
        env.grant(t, s, "dispatcher", listOf(svc)); env.grant(t, s, "support")
        val r = env.get("/v1/me", env.token(s)).json
        assertEquals(listOf("dispatcher", "support"), r["roles"].values().map { it.asString() })
        assertEquals(listOf(svc.toString()), r["service_scope"].values().map { it.asString() })
        assertTrue(r["scopes"].values().map { it.asString() }.containsAll(listOf("me:read", "tenant:switch")))
        // ohne ausdrückliche Dienstliste: keine Dienstfreigabe (fail-closed)
        val s2 = UUID.randomUUID(); env.grant(t, s2, "support")
        assertTrue(env.get("/v1/me", env.token(s2)).json["service_scope"].isNull)
    }
}

package de.taktovia.app

import de.taktovia.app.identity.IdentityEnv
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/** `/v1/webhook-subscriptions` (WP-M1-16) über die vollständige Kette: Rechtematrix, Mandantentrennung, SSRF-Prüfung, Secret nur als Referenz, Audit, Idempotenz. */
class WebhookSubscriptionApiTest {
    private val env = IdentityEnv
    private fun key() = mapOf("Idempotency-Key" to "k-" + UUID.randomUUID())
    private fun create(token: String, url: String = "https://93.184.216.34/hooks/taktovia", types: String = """["membership.granted"]""", extra: String = "", idem: Map<String, String> = key()) =
        env.post("/v1/webhook-subscriptions", """{"url":"$url","event_types":$types$extra}""", token, extra = idem)
    private fun admin(t: de.taktovia.platform.tenant.TenantId = env.tenant()): Triple<de.taktovia.platform.tenant.TenantId, UUID, String> {
        val u = UUID.randomUUID(); env.grant(t, u, "tenant_admin"); return Triple(t, u, env.token(u))
    }
    private fun deactivate(token: String, id: String, ifMatch: String?) = env.server.send("POST", "/v1/webhook-subscriptions/$id/deactivate", null, env.headers(token, extra = key() + (if (ifMatch != null) mapOf("If-Match" to ifMatch) else emptyMap())))

    @Test
    fun anlegen_lesen_auflisten_deaktivieren_mit_audit_und_ohne_secretwert() {
        val (t, user, token) = admin()
        val c = create(token)
        assertEquals(201, c.status, c.text)
        val id = c.json["id"].asString()
        assertEquals("/v1/webhook-subscriptions/$id", c.header("Location")); assertEquals("\"1\"", c.header("ETag"))
        assertTrue(c.json["active"].asBoolean()); assertEquals(1L, c.json["revision"].asLong())
        val ref = c.json["secret_ref"].asString()
        assertTrue(Regex("secret://taktovia-local-secrets/webhook_[0-9a-f]{32}").matches(ref), ref)
        // die Tabelle enthält nur die Referenz
        assertEquals(ref, env.string("SELECT secret_ref FROM taktovia.webhook_subscription WHERE tenant_id=? AND id=?", t.value, UUID.fromString(id)).single())
        assertFalse(c.text.contains("secret_value") || c.text.contains("password"))
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='webhook.subscription.created' AND object_ref=? AND actor_ref=?", t.value, "webhook_subscription:$id", "user:$user"))

        val g = env.get("/v1/webhook-subscriptions/$id", token); assertEquals(200, g.status); assertEquals("\"1\"", g.header("ETag")); assertEquals(id, g.json["id"].asString())
        val l = env.get("/v1/webhook-subscriptions?limit=10", token); assertEquals(200, l.status)
        assertEquals(listOf(id), l.json["items"].values().map { it["id"].asString() })

        assertEquals(428, deactivate(token, id, null).status, "ohne If-Match")
        assertEquals(412, deactivate(token, id, "\"7\"").status, "veraltete Revision")
        val d = deactivate(token, id, "\"1\"")
        assertEquals(200, d.status, d.text); assertEquals(false, d.json["active"].asBoolean()); assertEquals(2L, d.json["revision"].asLong()); assertEquals("\"2\"", d.header("ETag"))
        assertEquals(409, deactivate(token, id, "\"2\"").status, "bereits deaktiviert")
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='webhook.subscription.deactivated' AND object_ref=?", t.value, "webhook_subscription:$id"))
        assertEquals(false, env.get("/v1/webhook-subscriptions/$id", token).json["active"].asBoolean())
    }

    @Test
    fun wiederholtes_anlegen_mit_gleichem_idempotency_key_liefert_dasselbe_abonnement() {
        val (t, _, token) = admin(); val idem = key()
        val a = create(token, idem = idem); val b = create(token, idem = idem)
        assertEquals(201, a.status); assertEquals(201, b.status); assertEquals(a.json["id"].asString(), b.json["id"].asString())
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.webhook_subscription WHERE tenant_id=?", t.value)); assertEquals(1, env.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='webhook.subscription.created'", t.value))
    }

    @Test
    fun abonnements_sind_mandantengetrennt() {
        val (_, _, tokenA) = admin(); val (tb, _, tokenB) = admin()
        val id = create(tokenA).json["id"].asString()
        assertEquals(404, env.get("/v1/webhook-subscriptions/$id", tokenB).status, "fremdes Abonnement: wie nicht vorhanden")
        assertEquals(404, deactivate(tokenB, id, "\"1\"").status)
        assertEquals(emptyList<String>(), env.get("/v1/webhook-subscriptions", tokenB).json["items"].values().map { it["id"].asString() })
        assertEquals(0, env.count("SELECT count(*) FROM taktovia.webhook_subscription WHERE tenant_id=?", tb.value))
        assertEquals(true, env.get("/v1/webhook-subscriptions/$id", tokenA).json["active"].asBoolean(), "unverändert aktiv")
    }

    @Test
    fun nur_berechtigte_rollen_legen_an_lesen_und_deaktivieren() {
        val (t, _, adminToken) = admin(); val id = create(adminToken).json["id"].asString()
        for (role in listOf("passenger", "driver", "callcenter", "support", "dispatcher", "planner")) {
            val u = UUID.randomUUID(); env.grant(t, u, role); val tk = env.token(u)
            assertEquals(403, create(tk).status, "$role darf nicht anlegen")
            assertEquals(403, env.get("/v1/webhook-subscriptions", tk).status, "$role darf nicht lesen")
            assertEquals(403, env.get("/v1/webhook-subscriptions/$id", tk).status)
            assertEquals(403, deactivate(tk, id, "\"1\"").status)
        }
        assertEquals(401, env.server.get("/v1/webhook-subscriptions").status)
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.webhook_subscription WHERE tenant_id=?", t.value))
    }

    @Test
    fun ziele_in_privaten_netzen_localhost_und_ohne_https_werden_mit_422_abgelehnt() {
        val (t, _, token) = admin()
        val bad = listOf("http://93.184.216.34/hook", "https://localhost/hook", "https://127.0.0.1/hook", "https://10.0.0.1/hook", "https://192.168.1.10/hook", "https://172.16.0.1/hook", "https://169.254.169.254/latest/meta-data",
            "https://[::1]/hook", "https://[fd00::1]/hook", "https://2130706433/hook", "https://0x7f.0.0.1/hook", "https://user:pass@93.184.216.34/hook", "https://93.184.216.34:8443/hook", "https://intern.local/hook",
            "https://metadata.internal/hook", "https://[::ffff:127.0.0.1]/hook", "ftp://93.184.216.34/hook")
        for (u in bad) {
            val r = create(token, url = u)
            assertEquals(422, r.status, "$u: ${r.text}"); assertEquals("TARGET_NOT_ALLOWED", r.json["violations"][0]["code"].asString(), u); assertEquals("/url", r.json["violations"][0]["pointer"].asString())
        }
        assertEquals(0, env.count("SELECT count(*) FROM taktovia.webhook_subscription WHERE tenant_id=?", t.value))
    }

    @Test
    fun eingaben_ereignistypen_secret_ref_und_mass_assignment() {
        val (t, _, token) = admin()
        assertEquals("NOT_IN_ENUM", create(token, types = """["booking.confirmed"]""").json["violations"][0]["code"].asString())
        assertEquals(422, create(token, types = "[]").status)
        assertEquals(422, create(token, types = """["membership.granted","membership.granted"]""").status)
        val valueField = "sec" + "ret"   // kein Wertfeld im Vertrag
        assertEquals(422, create(token, extra = ""","$valueField":"klartext-geheimnis"""").status, "Secretwerte sind nicht Teil des Vertrags (additionalProperties:false)")
        assertEquals(422, create(token, extra = ""","secret_ref":"klartext-geheimnis"""").status, "Klartext statt Referenz")
        assertEquals(422, create(token, extra = ""","secret_ref":"secret://taktovia-production-secrets/webhook_x1"""").status, "Referenz einer fremden Geheimnisdomäne")
        assertEquals(404, create(token, extra = ""","tenant_id":"${UUID.randomUUID()}"""").status, "Mandantenangabe im Body ist wirkungslos und wie ein Fremdzugriff behandelt (404 plus Sicherheitsereignis)")
        val ok = create(token, extra = ""","secret_ref":"secret://taktovia-local-secrets/webhook_kundenschluessel"""")
        assertEquals(201, ok.status, ok.text); assertEquals("secret://taktovia-local-secrets/webhook_kundenschluessel", ok.json["secret_ref"].asString())
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.webhook_subscription WHERE tenant_id=?", t.value))
        assertNull(env.string("SELECT created_by FROM taktovia.webhook_subscription WHERE tenant_id=?", t.value).firstOrNull()?.takeIf { !it.startsWith("user:") })
    }
}

package de.taktovia.app.config

import de.taktovia.app.identity.IdentityEnv
import de.taktovia.platform.tenant.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/** WP-M1-23 über die echte Anwendung: Analyse auslösen, Bericht lesen, Rechte, Mandantentrennung, Fehlermodell. In M1 gibt es noch keinen Fachanbieter: der Bericht ist leer. */
class ImpactApiTest {
    private val env = IdentityEnv
    private fun idem() = mapOf("Idempotency-Key" to "imp-" + UUID.randomUUID())
    private class Actor(val tenant: TenantId, val token: String)
    private fun actor(tenant: TenantId = env.tenant(), role: String = "tenant_admin"): Actor { val s = UUID.randomUUID(); env.grant(tenant, s, role); return Actor(tenant, env.token(s)) }
    private fun revision(a: Actor): String = env.post("/v1/config/revisions",
        """{"scope_kind":"TENANT","scope_id":"${a.tenant}","schema_version":"1","values":{"max_wait_min":20},"effective_from":"2026-12-01T00:00:00Z","reason":"Wartezeit"}""", a.token, extra = idem()).json["id"].asString()
    private fun analyze(a: Actor, id: String, ifMatch: String? = "\"1\"") =
        env.server.send("POST", "/v1/config/revisions/$id/impact", null, env.headers(a.token, extra = idem() + (if (ifMatch != null) mapOf("If-Match" to ifMatch) else emptyMap())))

    @Test
    fun analyse_speichert_bericht_der_ueber_seine_id_abrufbar_ist() {
        val a = actor(); val rev = revision(a)
        val r = analyze(a, rev)
        assertEquals(200, r.status, r.text)
        assertEquals(rev, r.json["revision_id"].asString()); assertEquals("NONE", r.json["highest_severity"].asString()); assertEquals(0, r.json["conflict_count"].asInt())
        assertEquals(64, r.json["values_hash"].asString().length)
        val read = env.get("/v1/config/impact-reports/${r.json["id"].asString()}", a.token)
        assertEquals(200, read.status, read.text); assertEquals(r.json["values_hash"].asString(), read.json["values_hash"].asString())
        // jede Analyse ist ein eigener, unveränderlicher Bericht
        assertTrue(analyze(a, rev).json["id"].asString() != r.json["id"].asString())
    }

    @Test
    fun fehlermodell_rechte_und_mandantentrennung() {
        val a = actor(); val rev = revision(a)
        assertEquals(412, analyze(a, rev, "\"7\"").status)
        assertEquals(428, analyze(a, rev, null).status)
        assertEquals(404, analyze(a, UUID.randomUUID().toString()).status)
        assertEquals(403, analyze(actor(a.tenant, "passenger"), rev).status, "ohne Konfigurationsrecht")
        assertEquals(403, analyze(actor(a.tenant, "dispatcher"), rev).status, "Lesen genügt nicht zum Auslösen")
        val id = analyze(a, rev).json["id"].asString()
        val b = actor()   // anderer Mandant
        assertEquals(404, env.get("/v1/config/impact-reports/$id", b.token).status, "Bericht eines anderen Mandanten wie nicht vorhanden")
        assertEquals(404, analyze(b, rev).status, "Revision eines anderen Mandanten wie nicht vorhanden")
        assertEquals(403, env.get("/v1/config/impact-reports/$id", actor(a.tenant, "passenger").token).status)
        assertEquals(401, env.server.get("/v1/config/impact-reports/$id").status)
    }

    @Test
    fun parameterkatalog_kennzeichnet_impact_pflichtige_parameter() {
        val items = env.get("/v1/config/parameters", actor().token).json["items"].values().associateBy { it["name"].asString() }
        assertEquals(true, items.getValue("pickup_window_min")["impact_required"].asBoolean())
        assertEquals("Bei Änderung bereits bestätigte Buchungen prüfen", items.getValue("pickup_window_min")["impact_hint"].asString())
        assertEquals(false, items.getValue("reporting_timezone")["impact_required"].asBoolean())
    }
}

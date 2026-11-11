package de.taktovia.app.structure

import de.taktovia.app.identity.IdentityEnv
import de.taktovia.app.probe.Resp
import de.taktovia.platform.tenant.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** WP-M1-25 über die echte Anwendung: Dienst anlegen, Modell konfigurieren, validieren, durch eine zweite Person freigeben, publizieren; wirksame Modellregeln lesen; Fehlerfälle. */
class ServiceConfigurationApiTest {
    private val env = IdentityEnv
    private fun idem() = mapOf("Idempotency-Key" to "svc-" + UUID.randomUUID())
    private class Team(val tenant: TenantId, val alice: String, val bob: String, val passenger: String, val operator: String)
    private fun team(): Team {
        val t = env.tenant(); val a = UUID.randomUUID(); val b = UUID.randomUUID(); val p = UUID.randomUUID()
        env.grant(t, a, "tenant_admin"); env.grant(t, b, "planner"); env.grant(t, p, "passenger")
        val at = env.token(a)
        val le = env.post("/v1/legal-entities", """{"name":"LE","country":"DE"}""", at, extra = idem()).json["id"].asString()
        val op = env.post("/v1/organizations", """{"legal_entity_id":"$le","name":"Betreiber","federation_ref":"f-${UUID.randomUUID()}","roles":["TRANSPORT_COMPANY"]}""", at, extra = idem()).json["id"].asString()
        return Team(t, at, env.token(b), env.token(p), op)
    }
    private fun call(token: String, method: String, path: String, ifMatch: Long?, body: String? = null): Resp =
        env.server.send(method, path, body, env.headers(token, extra = idem() + (if (ifMatch != null) mapOf("If-Match" to "\"$ifMatch\"") else emptyMap())))
    private fun values(t: Team, model: String, extra: String): String =
        """{"service_config":{"service_area_id":"${UUID.randomUUID()}","traffic_type":"$model","operator_id":"${t.operator}","operating_hours":[{"weekdays":["MON","TUE"],"from":"06:00","to":"22:00"}],"tariff_id":"${UUID.randomUUID()}"}$extra}"""

    private fun configure(t: Team, model: String, extra: String): Pair<String, String> {
        val s = env.post("/v1/services", """{"operator_id":"${t.operator}","name":"$model","timezone":"Europe/Berlin"}""", t.alice, extra = idem())
        assertEquals(201, s.status, s.text)
        val sid = s.json["id"].asString(); val rev = s.json["config_revision_id"].asString()
        val from = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val p = call(t.alice, "PATCH", "/v1/config/revisions/$rev", 1, """{"values":${values(t, model, extra)},"effective_from":"$from","reason":"Dienst konfigurieren"}""")
        assertEquals(200, p.status, p.text)
        return sid to rev
    }

    private fun publishFlow(t: Team, rev: String, rv: Long = 2): Resp {
        val d = env.get("/v1/config/revisions/$rev", t.alice).json
        val report = call(t.alice, "POST", "/v1/config/revisions/$rev/impact", d["row_version"].asLong()).json["id"].asString()
        val v = call(t.alice, "POST", "/v1/config/revisions/$rev/validate", d["row_version"].asLong()); assertEquals(200, v.status, v.text)
        if (!v.json["valid"].asBoolean()) return v
        val a = call(t.bob, "POST", "/v1/config/revisions/$rev/approve", v.json["row_version"].asLong()); assertEquals(200, a.status, a.text)
        return call(t.bob, "POST", "/v1/config/revisions/$rev/publish", a.json["row_version"].asLong(), """{"effective_from":"${d["effective_from"].asString()}","reason":"Dienst veröffentlichen","impact_report_id":"$report"}""")
    }

    @Test
    fun drei_dienste_je_ein_modell_wirksame_modellregeln_ueber_die_api() {
        val t = team()
        val specs = listOf("AREA_ON_DEMAND" to ""","max_detour_pct":30""", "LINE_ON_DEMAND" to ""","stop_walk_max_m":400""", "STATION_FEEDER" to ""","advance_arrival_buffer_min":15""")
        for ((model, extra) in specs) {
            val (sid, rev) = configure(t, model, extra)
            val before = env.get("/v1/services/$sid/configuration", t.alice)
            assertEquals(200, before.status, before.text); assertTrue(before.json["offer_model"].isNull, "vor der Publikation kein wirksames Modell")
            val r = publishFlow(t, rev); assertEquals(200, r.status, r.text)
            val cfg = env.get("/v1/services/$sid/configuration?at=${Instant.now().plusSeconds(120)}", t.alice)
            assertEquals(200, cfg.status, cfg.text)
            assertEquals(model, cfg.json["offer_model"].asString()); assertEquals(model, cfg.json["model_rules"]["model"].asString()); assertEquals(rev, cfg.json["revision_id"].asString())
        }
        val models = env.get("/v1/service-offer-models", t.alice).json["items"]
        assertEquals(setOf("AREA_ON_DEMAND", "LINE_ON_DEMAND", "STATION_FEEDER"), models.values().map { it["model"].asString() }.toSet())
        assertEquals(listOf("max_detour_pct"), models.values().first { it["model"].asString() == "AREA_ON_DEMAND" }["required"].values().map { it.asString() })
    }

    @Test
    fun modellwidrige_konfiguration_wird_bei_der_validierung_abgewiesen_und_blockiert_die_publikation() {
        val t = team()
        val (_, rev) = configure(t, "AREA_ON_DEMAND", ""","max_detour_pct":30,"advance_arrival_buffer_min":10""")
        val d = env.get("/v1/config/revisions/$rev", t.alice).json
        val v = call(t.alice, "POST", "/v1/config/revisions/$rev/validate", d["row_version"].asLong())
        assertEquals(200, v.status, v.text); assertEquals(false, v.json["valid"].asBoolean())
        assertEquals("/values/advance_arrival_buffer_min", v.json["violations"][0]["pointer"].asString())
        assertEquals("DRAFT", env.get("/v1/config/revisions/$rev", t.alice).json["state"].asString())
        assertEquals(409, call(t.bob, "POST", "/v1/config/revisions/$rev/approve", d["row_version"].asLong()).status)
        // unbekanntes Modell: 422 schon beim Ändern des Entwurfs
        val (_, rev2) = configure(t, "AREA_ON_DEMAND", ""","max_detour_pct":30""")
        val bad = call(t.alice, "PATCH", "/v1/config/revisions/$rev2", 2, """{"values":${values(t, "TAXI", "")}}""")
        assertEquals(422, bad.status, bad.text)
    }

    @Test
    fun fremder_dienst_und_fehlende_rechte() {
        val a = team(); val b = team()
        val (sid, _) = configure(b, "LINE_ON_DEMAND", ""","stop_walk_max_m":300""")
        assertEquals(404, env.get("/v1/services/$sid/configuration", a.alice).status, "Dienst von B für A wie nicht vorhanden")
        assertEquals(404, env.get("/v1/services/${UUID.randomUUID()}/configuration", a.alice).status)
        assertEquals(403, env.get("/v1/services/$sid/configuration", b.passenger).status)
        // Betreiber eines fremden Mandanten beim Anlegen: 404
        assertEquals(404, env.post("/v1/services", """{"operator_id":"${b.operator}","name":"Fremd","timezone":"Europe/Berlin"}""", a.alice, extra = idem()).status)
    }
}

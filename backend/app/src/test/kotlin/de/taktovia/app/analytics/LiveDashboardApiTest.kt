package de.taktovia.app.analytics

import de.taktovia.analytics.api.AnalyticsApi
import de.taktovia.app.identity.IdentityEnv
import de.taktovia.platform.outbox.EventEnvelope
import de.taktovia.platform.tenant.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

/**
 * WP-M4-60 / KPI-014 über die echte Anwendung (HTTP, Runtime-Rolle, Test-Issuer): `GET /v1/analytics/metrics?breakdown=live` liefert die vier getrennten Werte mit Datenstand; die Fahrzeugsperre ist nach der
 * Projektion sichtbar, ein dienstbeschränkter Benutzer sieht nur seine Dienste, ein fremder Mandant nie, unzulässige Parameter sind 400.
 */
class LiveDashboardApiTest {
    private val env = IdentityEnv
    private val mapper = JsonMapper.builder().build()
    private val analytics get() = env.server.ctx.getBean(AnalyticsApi::class.java)

    private class World(val env: IdentityEnv) {
        val tenant = env.tenant("Live-" + UUID.randomUUID())
        val op = UUID.randomUUID(); val s1 = UUID.randomUUID(); val s2 = UUID.randomUUID(); val v1 = UUID.randomUUID(); val v2 = UUID.randomUUID()
        val planner = UUID.randomUUID(); val scoped = UUID.randomUUID(); val driver = UUID.randomUUID()
        val plannerToken: String; val scopedToken: String; val driverToken: String
        init {
            val t = tenant.value; val le = UUID.randomUUID()
            env.exec("INSERT INTO taktovia.legal_entity(tenant_id,id,name,country) VALUES (?,?,'LE','DE')", t, le)
            env.exec("INSERT INTO taktovia.organization(tenant_id,id,legal_entity_id,name,federation_ref,roles) VALUES (?,?,?,'Org',?,CAST('{TRANSPORT_COMPANY}' AS text[]))", t, op, le, "f-$op")
            listOf(s1, s2).forEach { s ->
                val rev = UUID.randomUUID()
                env.exec("INSERT INTO taktovia.config_revision(tenant_id,id,scope_kind,scope_id,revision,schema_version,values_json,effective_from,created_by,reason) VALUES (?,?,'SERVICE',?,1,'1','{}','2026-01-01','user:test','Test')", t, rev, s)
                env.exec("INSERT INTO taktovia.service(tenant_id,id,operator_id,name,timezone,config_revision_id,created_by) VALUES (?,?,?,'Dienst','Europe/Berlin',?,'user:test')", t, s, op, rev)
            }
            listOf(v1 to s1, v2 to s2).forEach { (v, s) ->
                env.exec("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config,vehicle_label,created_by) VALUES (?,?,'org','{\"seats\":4,\"wheelchair\":0}','V-${v.toString().take(6)}','user:test')", t, v)
                env.exec("INSERT INTO taktovia.vehicle_pool_assignment(tenant_id,id,service_id,vehicle_id,valid_from,created_by) VALUES (?,?,?,?,'2026-01-01','user:test')", t, UUID.randomUUID(), s, v)
            }
            env.grant(tenant, planner, "planner"); env.grant(tenant, scoped, "dispatcher", listOf(s1)); env.grant(tenant, driver, "driver")
            plannerToken = env.token(planner); scopedToken = env.token(scoped); driverToken = env.token(driver)
        }
    }

    private fun block(t: TenantId, vehicle: UUID, at: Instant) {
        val b = UUID.randomUUID()
        analytics.facts.consume(t, EventEnvelope(UUID.randomUUID(), "fleet.vehicle_blocked", "1.0", b, 1, at, at, t.value, t.value, UUID.randomUUID(),
            mapper.readTree("""{"vehicle_id":"$vehicle","block_id":"$b","source":"SAFETY","reason_code":"MANUAL","safety_revision":2}""")).toJson())
    }

    private fun tiles(json: tools.jackson.databind.JsonNode) = (0 until json["live"]["tiles"].size()).map { json["live"]["tiles"][it] }
    private fun tile(json: tools.jackson.databind.JsonNode, id: String) = tiles(json).first { it["id"].asString() == id }

    @Test
    fun vier_getrennte_werte_mit_datenstand_und_sperre_sichtbar() {
        val w = World(env)
        val empty = env.get("/v1/analytics/metrics?breakdown=live", w.plannerToken)
        assertEquals(200, empty.status, empty.text)
        assertEquals(listOf("open_requests", "available_capacity", "critical_delays", "vehicle_outages"), tiles(empty.json).map { it["id"].asString() })
        assertEquals("NO_DATA", tile(empty.json, "vehicle_outages")["state"].asString()); assertTrue(tile(empty.json, "vehicle_outages")["value"].isNull, "ohne Ereignis nie 0")
        assertEquals(30, empty.json["live"]["refresh_seconds"].asInt()); assertTrue(empty.json["live"]["tolerance_min"].asInt() >= 0); assertEquals(15, empty.json["live"]["alert_min"].asInt())

        block(w.tenant, w.v1, Instant.now())
        val r = env.get("/v1/analytics/metrics?breakdown=live", w.plannerToken)
        assertEquals(200, r.status, r.text)
        assertEquals(1, tile(r.json, "vehicle_outages")["value"].asInt()); assertEquals("FRESH", tile(r.json, "vehicle_outages")["state"].asString())
        assertNotNull(tile(r.json, "vehicle_outages")["as_of"].asString()); assertEquals("TENANT", r.json["live"]["scope"].asString())
    }

    @Test
    fun dienstbeschraenkter_benutzer_fremder_mandant_und_ungueltige_parameter() {
        val w = World(env); val other = World(env)
        block(w.tenant, w.v1, Instant.now()); block(w.tenant, w.v2, Instant.now()); block(other.tenant, other.v1, Instant.now())
        assertEquals(2, tile(env.get("/v1/analytics/metrics?breakdown=live", w.plannerToken).json, "vehicle_outages")["value"].asInt())
        val scoped = env.get("/v1/analytics/metrics?breakdown=live", w.scopedToken)
        assertEquals(200, scoped.status, scoped.text)
        assertEquals(1, tile(scoped.json, "vehicle_outages")["value"].asInt(), "nur der berechtigte Dienst"); assertEquals("SERVICES", scoped.json["live"]["scope"].asString())
        // fremder Mandant: nie sichtbar (der Mandant folgt allein dem Token)
        assertEquals(1, tile(env.get("/v1/analytics/metrics?breakdown=live", other.plannerToken).json, "vehicle_outages")["value"].asInt())
        assertEquals(404, env.get("/v1/analytics/metrics?breakdown=live&tenant_id=${other.tenant.value}", w.plannerToken).status)
        // Zeitraum/Filter sind für live nicht zulässig; ohne Berichtsrecht 403
        assertEquals(400, env.get("/v1/analytics/metrics?breakdown=live&from=2026-12-01", w.plannerToken).status)
        assertEquals(400, env.get("/v1/analytics/metrics?breakdown=live&service_id=${w.s1}", w.plannerToken).status)
        assertEquals(403, env.get("/v1/analytics/metrics?breakdown=live", w.driverToken).status)
        assertEquals(401, env.get("/v1/analytics/metrics?breakdown=live", "ungueltig").status)
    }
}

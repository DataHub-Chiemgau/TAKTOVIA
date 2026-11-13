package de.taktovia.app.fleet

import de.taktovia.app.identity.IdentityEnv
import de.taktovia.fleetsafety.api.FleetsafetyApi
import de.taktovia.fleetsafety.api.WorkflowEvent
import de.taktovia.fleetsafety.api.WorkflowEventKinds
import de.taktovia.platform.tenant.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * WP-M3-53 über die echte Anwendung (Test-Issuer statt Keycloak, echte Datenbank): Incidents der Leitstelle (`/v1/operator/incidents`). Ein Fahrzeugausfall aus Sperre, Schaden und Störung
 * erscheint genau einmal; Dienst- und Mandantengrenzen, Rolle, Filter, Seitenform und ETag.
 */
class IncidentHttpApiTest {
    private val env = IdentityEnv
    private val fleet: FleetsafetyApi get() = env.server.ctx.getBean(FleetsafetyApi::class.java)

    private fun token(t: TenantId, role: String, services: List<UUID>? = emptyList()): String { val s = UUID.randomUUID(); env.grant(t, s, role, services); return env.token(s) }

    private fun vehicle(t: TenantId): UUID = UUID.randomUUID().also { v -> env.tx.run(t) { j -> j.update("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (?,?,'fixture','{}')", t.value, v) } }

    private fun outage(t: TenantId, vehicle: UUID, kind: String = WorkflowEventKinds.VEHICLE_BLOCKED): UUID? =
        env.tx.run(t) { j -> fleet.incidentWorkflow.handle(j, WorkflowEvent(UUID.randomUUID(), kind, vehicle, Instant.now(), correlationId = UUID.randomUUID())).incidentId }

    @Test
    fun fahrzeugausfall_erscheint_genau_einmal_mit_etag_und_verknuepfungen() {
        val t = env.tenant(); val d = token(t, "dispatcher"); val v = vehicle(t)
        val id = outage(t, v)!!
        assertEquals(id, outage(t, v, WorkflowEventKinds.DISRUPTION_REPORTED), "Störungsmeldung zum selben Fahrzeug: derselbe Incident")
        val list = env.get("/v1/operator/incidents", d); assertEquals(200, list.status, list.text)
        val items = list.json["items"].values().toList(); assertEquals(1, items.size)
        val item = items.single(); assertEquals(id.toString(), item["id"].asString()); assertEquals("VEHICLE_OUTAGE", item["kind"].asString()); assertEquals("REPLACEMENT_SEARCH", item["status"].asString())
        assertEquals(v.toString(), item["vehicle_id"].asString()); assertEquals("VEHICLE_OUTAGE:$v", item["dedupe_key"].asString()); assertFalse(item["replacement_operation_id"].isNull)
        assertTrue(list.json.has("as_of") && list.json["next_cursor"].isNull)
        val one = env.get("/v1/operator/incidents/$id", d); assertEquals(200, one.status, one.text); assertEquals("\"2\"", one.header("ETag"))
        assertEquals(item["replacement_operation_id"].asString(), one.json["replacement_operation_id"].asString())
        // Filter
        assertEquals(1, env.get("/v1/operator/incidents?status=REPLACEMENT_SEARCH&vehicle_id=$v", d).json["items"].size())
        assertEquals(0, env.get("/v1/operator/incidents?status=OPEN", d).json["items"].size())
        assertEquals(422, env.get("/v1/operator/incidents?status=GIBTES_NICHT", d).status)
        assertEquals(422, env.get("/v1/operator/incidents?vehicle_id=kein-uuid", d).status)
    }

    @Test
    fun rolle_mandant_und_dienst_grenzen() {
        val t = env.tenant(); val v = vehicle(t); val id = outage(t, v)!!
        // ohne Rolle (Fahrgast, Fahrer, Betriebsleitung ohne Fachrolle) 403
        for (role in listOf("passenger", "driver", "finance")) {
            assertEquals(403, env.get("/v1/operator/incidents", token(t, role)).status, role); assertEquals(403, env.get("/v1/operator/incidents/$id", token(t, role)).status, role)
        }
        // berechtigte Rollen der Leitstelle lesen
        for (role in listOf("callcenter", "support", "dispatcher", "planner", "workshop")) assertEquals(200, env.get("/v1/operator/incidents/$id", token(t, role)).status, role)
        // fremder Mandant: 404, in der Liste nichts
        val other = env.tenant(); val od = token(other, "dispatcher")
        assertEquals(404, env.get("/v1/operator/incidents/$id", od).status); assertEquals(0, env.get("/v1/operator/incidents", od).json["items"].size())
        // fremder Dienst: der Incident gehört zum Dienst des Fahrzeugpools
        val svc = UUID.randomUUID()
        env.tx.run(t) { j ->
            val cfg = UUID.randomUUID(); val org = UUID.randomUUID(); val le = UUID.randomUUID()
            j.update("INSERT INTO taktovia.legal_entity(tenant_id,id,name,country) VALUES (?,?,'LE','DE')", t.value, le)
            j.update("INSERT INTO taktovia.organization(tenant_id,id,legal_entity_id,name,federation_ref,roles) VALUES (?,?,?,'Org',?,CAST('{TRANSPORT_COMPANY}' AS text[]))", t.value, org, le, "f-$org")
            j.update("INSERT INTO taktovia.config_revision(tenant_id,id,scope_kind,scope_id,revision,schema_version,values_json,effective_from,created_by,reason) VALUES (?,?,'SERVICE',?,1,'1',CAST('{}' AS jsonb),'2026-01-01T00:00:00Z','user:t','Test')", t.value, cfg, svc)
            j.update("INSERT INTO taktovia.service(tenant_id,id,operator_id,name,timezone,config_revision_id,created_by) VALUES (?,?,?,'Dienst','Europe/Berlin',?,'user:t')", t.value, svc, org, cfg)
            j.update("INSERT INTO taktovia.vehicle_pool_assignment(tenant_id,id,service_id,vehicle_id,valid_from,created_by) VALUES (?,?,?,?,'2026-01-01T00:00:00Z','user:t')", t.value, UUID.randomUUID(), svc, v)
        }
        val scoped = outage(t, v) // derselbe offene Incident: Dienstbezug entstand mit dem ersten Ereignis (ohne Pool) und bleibt mandantenweit
        assertEquals(id, scoped)
        val v2 = vehicle(t)
        env.tx.run(t) { j -> j.update("INSERT INTO taktovia.vehicle_pool_assignment(tenant_id,id,service_id,vehicle_id,valid_from,created_by) VALUES (?,?,?,?,'2026-01-01T00:00:00Z','user:t')", t.value, UUID.randomUUID(), svc, v2) }
        val dienstIncident = outage(t, v2)!!
        assertEquals(404, env.get("/v1/operator/incidents/$dienstIncident", token(t, "dispatcher", services = listOf(UUID.randomUUID()))).status)
        assertEquals(200, env.get("/v1/operator/incidents/$dienstIncident", token(t, "dispatcher", services = listOf(svc))).status)
        assertFalse(env.get("/v1/operator/incidents", token(t, "dispatcher", services = listOf(UUID.randomUUID()))).text.contains(dienstIncident.toString()))
        assertEquals(404, env.get("/v1/operator/incidents/${UUID.randomUUID()}", token(t, "dispatcher")).status)
    }

    /** WP-M3-55: Ablaufprotokoll im Detail (Zeitleiste), nur lesend; jede schreibende Anfrage am Incident und am Protokoll wird abgewiesen. */
    @Test
    fun ablaufprotokoll_im_detail_geordnet_und_ohne_schreibwege() {
        val t = env.tenant(); val d = token(t, "dispatcher"); val v = vehicle(t)
        val id = outage(t, v)!!
        outage(t, v, WorkflowEventKinds.DISRUPTION_REPORTED)
        val detail = env.get("/v1/operator/incidents/$id", d); assertEquals(200, detail.status, detail.text)
        val log = detail.json["workflow_log"].values().toList()
        assertEquals(listOf("EVALUATE_RULE", "OPEN_INCIDENT", "START_REPLACEMENT_SEARCH", "EVALUATE_RULE", "OPEN_INCIDENT", "START_REPLACEMENT_SEARCH"), log.map { it["action"].asString() })
        assertEquals((1..log.size).toList(), log.map { it["seq"].asInt() }, "geordnet, lückenlos")
        assertEquals(listOf("MATCHED:2", "CREATED", "STARTED", "MATCHED:2", "JOINED", "ALREADY_STARTED"), log.map { it["outcome"].asString() })
        assertEquals("VEHICLE_BLOCKED", log[0]["event_type"].asString()); assertEquals("DISRUPTION_REPORTED", log[3]["event_type"].asString())
        assertEquals(1, log[0]["rule_revision_no"].asInt()); assertEquals("system:workflow", log[0]["actor"].asString()); assertFalse(log[0]["correlation_id"].isNull)
        assertEquals(detail.json["replacement_operation_id"].asString(), log[2]["ref"].asString())
        // die Liste trägt das Protokoll nicht (nur das Detail)
        assertFalse(env.get("/v1/operator/incidents", d).json["items"][0].has("workflow_log"))
        // keine schreibenden Wege: PUT, PATCH, DELETE und POST am Incident und am Protokoll sind keine Operation des Vertrags
        for ((method, path) in listOf("PUT" to "/v1/operator/incidents/$id", "PATCH" to "/v1/operator/incidents/$id", "DELETE" to "/v1/operator/incidents/$id", "POST" to "/v1/operator/incidents/$id",
            "POST" to "/v1/operator/incidents/$id/workflow-log", "DELETE" to "/v1/operator/incidents/$id/workflow-log")) {
            val r = env.server.send(method, path, "{}", env.headers(d, extra = mapOf("Idempotency-Key" to "w-" + UUID.randomUUID(), "If-Match" to "\"2\"")), "application/json")
            assertTrue(r.status in setOf(404, 405), "$method $path: ${r.status} ${r.text}")
        }
        assertEquals(6, env.get("/v1/operator/incidents/$id", d).json["workflow_log"].size(), "das Protokoll ist unverändert")
        // ohne Rolle 403, fremder Mandant 404
        assertEquals(403, env.get("/v1/operator/incidents/$id", token(t, "passenger")).status)
        assertEquals(404, env.get("/v1/operator/incidents/$id", token(env.tenant(), "dispatcher")).status)
    }
}

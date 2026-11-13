package de.taktovia.app.contract

import de.taktovia.app.identity.IdentityEnv
import de.taktovia.fleetsafety.api.FleetsafetyApi
import de.taktovia.fleetsafety.api.WorkflowEvent
import de.taktovia.fleetsafety.api.WorkflowEventKinds
import java.time.Instant
import java.util.UUID

/**
 * Vertragsfälle des Clusters wave13-c4 (WP-M3-53/-55 Incidents, WP-M3-20 Abweichung): je Operation ein positiver Fall und ein Fehlerfall gegen die echte Anwendung.
 */
internal object W13C4ContractCases {
    private val env = IdentityEnv

    private class World {
        val tenant = env.tenant("Vertrag-W13C4-" + UUID.randomUUID())
        private fun token(role: String): String { val s = UUID.randomUUID(); env.grant(tenant, s, role); return env.token(s) }
        val dispatcher = token("dispatcher"); val passenger = token("passenger")
    }

    /** Ein Incident im Mandanten: der Workflow eröffnet ihn aus einer Sperre (Fahrzeug per Fixture). */
    private fun incident(w: World): UUID = env.tx.run(w.tenant) { j ->
        val vehicle = UUID.randomUUID()
        j.update("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (?,?,'fixture','{}')", w.tenant.value, vehicle)
        env.server.ctx.getBean(FleetsafetyApi::class.java).incidentWorkflow.handle(j, WorkflowEvent(UUID.randomUUID(), WorkflowEventKinds.VEHICLE_BLOCKED, vehicle, Instant.now())).incidentId!!
    }

    private fun get(path: String, token: String) = ContractRequest("GET", path, null, env.headers(token))

    val cases: List<ContractCase> = listOf(
        ContractCase("listOperatorIncidents",
            200, { val w = World(); incident(w); get("/v1/operator/incidents", w.dispatcher) },
            403, { val w = World(); get("/v1/operator/incidents", w.passenger) }, server = { env.server }),
        ContractCase("getOperatorIncident",
            200, { val w = World(); get("/v1/operator/incidents/${incident(w)}", w.dispatcher) },
            404, { val w = World(); get("/v1/operator/incidents/${UUID.randomUUID()}", w.dispatcher) }, server = { env.server }),
    )
}

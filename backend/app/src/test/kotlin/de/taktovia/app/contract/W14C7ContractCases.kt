package de.taktovia.app.contract

import de.taktovia.app.identity.IdentityEnv
import java.util.UUID

/** Vertragsfälle des Clusters wave14-c7 (WP-M3-41 Operative Fahrzeugsperre): je neuer Operation ein positiver Fall und ein Fehlerfall. Eigene Klasse je Cluster. */
internal object W14C7ContractCases {
    private val env = IdentityEnv

    private class World {
        val tenant = env.tenant("W14C7-" + UUID.randomUUID()); val dispatcher = UUID.randomUUID(); val driver = UUID.randomUUID(); val vehicle = UUID.randomUUID()
        init {
            env.grant(tenant, dispatcher, "dispatcher"); env.grant(tenant, driver, "driver")
            env.exec("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config,vehicle_label) VALUES (?,?,'fixture','{}','W14C7-1')", tenant.value, vehicle)
        }
        val token = env.token(dispatcher); val driverToken = env.token(driver)
        fun headers(token: String, ifMatch: Long) = env.headers(token, extra = mapOf("Idempotency-Key" to "c-" + UUID.randomUUID(), "If-Match" to "\"$ifMatch\""))
        fun block(): String = env.post("/v1/operator/vehicles/$vehicle/blocks", """{"reason_code":"OPERATIONAL_DISRUPTION"}""", token, null, mapOf("Idempotency-Key" to "c-" + UUID.randomUUID(), "If-Match" to "\"1\"")).json["id"].asString()
    }

    val cases: List<ContractCase> = listOf(
        ContractCase("createVehicleBlock",
            201, { val w = World(); ContractRequest("POST", "/v1/operator/vehicles/${w.vehicle}/blocks", """{"reason_code":"OPERATIONAL_DISRUPTION"}""", w.headers(w.token, 1)) },
            403, { val w = World(); ContractRequest("POST", "/v1/operator/vehicles/${w.vehicle}/blocks", """{"reason_code":"OPERATIONAL_DISRUPTION"}""", w.headers(w.driverToken, 1)) }, server = { IdentityEnv.server }),
        ContractCase("liftVehicleBlock",
            200, { val w = World(); val b = w.block(); ContractRequest("POST", "/v1/operator/vehicles/${w.vehicle}/blocks/$b/lift", "{}", w.headers(w.token, 1)) },
            404, { val w = World(); ContractRequest("POST", "/v1/operator/vehicles/${w.vehicle}/blocks/${UUID.randomUUID()}/lift", "{}", w.headers(w.token, 1)) }, server = { IdentityEnv.server }),
    )
}

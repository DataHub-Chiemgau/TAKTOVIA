package de.taktovia.app.contract

import de.taktovia.app.identity.IdentityEnv
import java.util.UUID

/** Vertragsfälle des Clusters wave12-c3: Fahrgastliste des Check-ins (WP-M3-30, `listExpectedPassengers`), geprüft gegen `api/driver.yaml`. */
internal object W12C3ContractCases {
    private val env = IdentityEnv

    private class World {
        val tenant = env.tenant("Vertrag-Fahrgastliste-" + UUID.randomUUID())
        val account = UUID.randomUUID(); val passenger = UUID.randomUUID()
        val vehicle: UUID = UUID.randomUUID(); val shift: UUID = UUID.randomUUID()
        init {
            env.grant(tenant, account, "driver"); env.grant(tenant, passenger, "passenger")
            env.exec("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config,vehicle_label) VALUES (?,?,'fixture','{}','V-L')", tenant.value, vehicle)
            env.exec("INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state) VALUES (?,?,?,?,now(),now()+interval '8 hours','Europe/Berlin',0,'PLANNED')",
                tenant.value, shift, account, vehicle)
        }
        val token = env.token(account); val passengerToken = env.token(passenger)
    }

    val cases: List<ContractCase> = listOf(
        ContractCase("listExpectedPassengers",
            200, { val w = World(); ContractRequest("GET", "/v1/driver/shifts/${w.shift}/passengers", null, env.headers(w.token)) },
            404, { val w = World(); ContractRequest("GET", "/v1/driver/shifts/${UUID.randomUUID()}/passengers", null, env.headers(w.token)) }, server = { IdentityEnv.server }),
    )
}

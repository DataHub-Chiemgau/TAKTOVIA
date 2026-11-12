package de.taktovia.app.contract

import de.taktovia.app.identity.IdentityEnv
import java.util.UUID

/** Vertragsfälle des Clusters wave9-c5 (WP-M4-09 Soll/Ist): je Operation ein positiver Fall und ein Fehlerfall. */
internal object C5bContractCases {
    private val env = IdentityEnv

    private class World {
        val tenant = env.tenant("Vertrag-C5b-" + UUID.randomUUID())
        val driver = UUID.randomUUID(); val passenger = UUID.randomUUID()
        init { env.grant(tenant, driver, "driver"); env.grant(tenant, passenger, "passenger") }
        val driverToken = env.token(driver); val passengerToken = env.token(passenger)
    }

    val cases: List<ContractCase> = listOf(
        ContractCase("listTimeTargetActual",
            200, { val w = World(); ContractRequest("GET", "/v1/time-events/target-actual?from=2026-12-01&to=2026-12-02", null, env.headers(w.driverToken)) },
            403, { val w = World(); ContractRequest("GET", "/v1/time-events/target-actual?from=2026-12-01&to=2026-12-02", null, env.headers(w.passengerToken)) }, server = { env.server }),
    )
}

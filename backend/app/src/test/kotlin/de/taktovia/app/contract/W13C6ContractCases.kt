package de.taktovia.app.contract

import de.taktovia.app.identity.IdentityEnv
import java.util.UUID

/** Vertragsfälle des Clusters wave13-c6 (WP-M4-46 bis -50, `GET /v1/analytics/metrics`): positiver Fall und Fehlerfall. */
internal object W13C6ContractCases {
    private val env = IdentityEnv

    private class World {
        val tenant = env.tenant("Vertrag-W13C6-" + UUID.randomUUID()); val dispatcher = UUID.randomUUID(); val driver = UUID.randomUUID()
        init { env.grant(tenant, dispatcher, "dispatcher"); env.grant(tenant, driver, "driver") }
        val dispatcherToken = env.token(dispatcher); val driverToken = env.token(driver)
    }

    val cases: List<ContractCase> = listOf(
        ContractCase("getAnalyticsMetrics",
            200, { val w = World(); ContractRequest("GET", "/v1/analytics/metrics?from=2026-12-01&to=2026-12-02&breakdown=rejection_reason", null, env.headers(w.dispatcherToken)) },
            403, { val w = World(); ContractRequest("GET", "/v1/analytics/metrics", null, env.headers(w.driverToken)) }, server = { env.server }),
    )
}

package de.taktovia.app.contract

import de.taktovia.app.identity.IdentityEnv
import java.util.UUID

/** Vertragsfälle des Clusters wave13-c5 (WP-M4-42, `/v1/analytics/vehicle-km`): positiver Fall (200) und Fehlerfall (422 ohne Pflichtparameter). */
internal object W13C5ContractCases {
    private val env = IdentityEnv

    private class World {
        val tenant = env.tenant("Vertrag-W13C5-" + UUID.randomUUID()); val finance = UUID.randomUUID()
        init { env.grant(tenant, finance, "finance") }
        val token = env.token(finance)
    }

    val cases: List<ContractCase> = listOf(
        ContractCase("getVehicleKmClasses",
            200, { val w = World(); ContractRequest("GET", "/v1/analytics/vehicle-km?metric=VEHICLE_KM_CLASSES&from=2026-06-01&to=2026-06-07", null, env.headers(w.token)) },
            422, { val w = World(); ContractRequest("GET", "/v1/analytics/vehicle-km?metric=VEHICLE_KM_CLASSES&from=2026-06-01", null, env.headers(w.token)) }, server = { env.server }),
    )
}

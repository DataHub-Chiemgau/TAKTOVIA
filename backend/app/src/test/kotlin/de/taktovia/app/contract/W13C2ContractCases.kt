package de.taktovia.app.contract

import de.taktovia.app.identity.IdentityEnv
import java.util.UUID

/**
 * Vertragsfälle des Clusters wave13-c2 (WP-M3-36 Leitstelle Livebetrieb; WP-M3-09 Readiness-Gate; WP-M3-16 Sperre durch kritischen Mangel): je neuer Operation ein positiver Fall und ein Fehlerfall.
 * Eigene Klasse je Cluster.
 */
internal object W13C2ContractCases {
    private val env = IdentityEnv

    /** Mandant mit Disponent (Dienstscope leer, Fahrzeug ohne Dienstzuordnung: nur Mandantengrenze), Fahrer und einem Fahrzeug. */
    class World {
        val tenant = env.tenant("W13C2-" + UUID.randomUUID()); val dispatcher = UUID.randomUUID(); val driver = UUID.randomUUID(); val vehicle = UUID.randomUUID()
        init {
            env.grant(tenant, dispatcher, "dispatcher"); env.grant(tenant, driver, "driver")
            env.exec("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config,vehicle_label) VALUES (?,?,'fixture','{}','W13C2-1')", tenant.value, vehicle)
        }
        val dispatcherToken = env.token(dispatcher); val driverToken = env.token(driver)
    }

    val cases: List<ContractCase> = listOf(
        ContractCase("getOperatorVehicle",
            200, { val w = World(); ContractRequest("GET", "/v1/operator/vehicles/${w.vehicle}", null, env.headers(w.dispatcherToken)) },
            404, { val w = World(); ContractRequest("GET", "/v1/operator/vehicles/${UUID.randomUUID()}", null, env.headers(w.dispatcherToken)) }, server = { IdentityEnv.server }),
    )
}

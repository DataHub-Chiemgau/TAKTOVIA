package de.taktovia.app.contract

import de.taktovia.app.fleet.VehiclePoolApiTest
import de.taktovia.app.fleet.VehicleMasterApiTest
import de.taktovia.app.identity.IdentityEnv
import java.time.Instant
import java.util.UUID

/** Vertragsfälle der Dienstzuordnung des Fahrzeugpools (WP-M3-06): je Operation ein positiver Fall und ein Fehlerfall gegen die echte Anwendung. */
internal object VehiclePoolContractCases {
    private fun idem() = mapOf("Idempotency-Key" to "contract-" + UUID.randomUUID())
    private fun pool() = VehiclePoolApiTest.Pool(VehicleMasterApiTest.World(IdentityEnv), IdentityEnv)
    private fun path(p: VehiclePoolApiTest.Pool, extra: String = "") = "/v1/operator/vehicles/${p.vehicle}/service-assignments$extra"
    private fun assignment(p: VehiclePoolApiTest.Pool): String = IdentityEnv.post(path(p), p.body(p.serviceA), p.w.dispatcherToken, null, idem()).json["id"].asString()

    val cases: List<ContractCase> = listOf(
        ContractCase("listVehicleServiceAssignments",
            200, { val p = pool(); assignment(p); ContractRequest("GET", path(p), headers = p.w.env.headers(p.w.supportToken)) },
            404, { val p = pool(); ContractRequest("GET", "/v1/operator/vehicles/${UUID.randomUUID()}/service-assignments", headers = p.w.env.headers(p.w.supportToken)) }, server = { IdentityEnv.server }),
        ContractCase("createVehicleServiceAssignment",
            201, { val p = pool(); ContractRequest("POST", path(p), p.body(p.serviceA), p.w.env.headers(p.w.dispatcherToken, extra = idem())) },
            409, { val p = pool(); assignment(p); ContractRequest("POST", path(p), p.body(p.serviceA), p.w.env.headers(p.w.dispatcherToken, extra = idem())) }, server = { IdentityEnv.server }),
        ContractCase("endVehicleServiceAssignment",
            200, { val p = pool(); val id = assignment(p); ContractRequest("POST", path(p, "/$id/end"), """{"valid_to":"${Instant.now().plusSeconds(86_400)}"}""", p.w.env.headers(p.w.dispatcherToken, extra = idem() + ("If-Match" to "\"1\""))) },
            412, { val p = pool(); val id = assignment(p); ContractRequest("POST", path(p, "/$id/end"), """{"valid_to":"${Instant.now().plusSeconds(86_400)}"}""", p.w.env.headers(p.w.dispatcherToken, extra = idem() + ("If-Match" to "\"9\""))) }, server = { IdentityEnv.server }),
    )
}

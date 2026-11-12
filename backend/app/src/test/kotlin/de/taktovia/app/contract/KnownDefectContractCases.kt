package de.taktovia.app.contract

import de.taktovia.app.fleet.InspectionApiTest
import de.taktovia.app.identity.IdentityEnv
import java.time.Instant
import java.util.UUID

/** Vertragsfälle der Altschaden-Quittierung (WP-M3-21): je Operation ein positiver Fall und ein Fehlerfall gegen die echte Anwendung. */
internal object KnownDefectContractCases {
    private fun idem() = mapOf("Idempotency-Key" to "contract-" + UUID.randomUUID())

    private class Setup(val w: InspectionApiTest.World, val vehicle: UUID, val defect: String, val run: String)

    private fun setup(): Setup {
        val w = InspectionApiTest.World(IdentityEnv).also { it.template("PKW") }
        val vehicle = w.vehicle("PKW")
        val defect = IdentityEnv.post("/v1/defects", """{"vehicle_id":"$vehicle","occurred_at":"${Instant.now().minusSeconds(3600)}","category_code":"BODY","description":"Kratzer"}""", w.dispatcherToken, null, idem()).json["id"].asString()
        val shift = w.shift(vehicle = vehicle)
        val run = IdentityEnv.post("/v1/inspections", w.body(shift, vehicle), w.token, null, idem()).json["id"].asString()
        return Setup(w, vehicle, defect, run)
    }

    val cases: List<ContractCase> = listOf(
        ContractCase("listKnownDefects",
            200, { val s = setup(); ContractRequest("GET", "/v1/inspections/${s.run}/known-defects", headers = s.w.env.headers(s.w.token)) },
            404, { val s = setup(); ContractRequest("GET", "/v1/inspections/${UUID.randomUUID()}/known-defects", headers = s.w.env.headers(s.w.token)) }, server = { IdentityEnv.server }),
        ContractCase("acknowledgeKnownDefect",
            201, { val s = setup(); ContractRequest("POST", "/v1/inspections/${s.run}/known-defects/${s.defect}/acknowledge", "{}", s.w.env.headers(s.w.token, extra = idem())) },
            404, { val s = setup(); ContractRequest("POST", "/v1/inspections/${s.run}/known-defects/${UUID.randomUUID()}/acknowledge", "{}", s.w.env.headers(s.w.token, extra = idem())) }, server = { IdentityEnv.server }),
        ContractCase("listVehicleKnownDefects",
            200, { val s = setup(); ContractRequest("GET", "/v1/vehicles/${s.vehicle}/known-defects", headers = s.w.env.headers(s.w.token)) },
            404, { val s = setup(); ContractRequest("GET", "/v1/vehicles/${UUID.randomUUID()}/known-defects", headers = s.w.env.headers(s.w.token)) }, server = { IdentityEnv.server }),
    )
}

package de.taktovia.app.contract

import de.taktovia.app.fleet.VehicleMasterApiTest
import de.taktovia.app.identity.IdentityEnv
import java.util.UUID

/** Vertragsfälle des Fahrzeugstamms und der Kapazitätskonfiguration (WP-M3-03/-04): je Operation ein positiver Fall und ein Fehlerfall gegen die echte Anwendung. */
internal object FleetMasterContractCases {
    private fun idem() = mapOf("Idempotency-Key" to "contract-" + UUID.randomUUID())
    private const val CAPACITY_BODY = """{"spaces":{"seat_places":6,"wheelchair_spaces":2,"seats_replaced_per_wheelchair":2},"combinations":[{"code":"STANDARD","wheelchair_places":0},{"code":"ONE_WC","wheelchair_places":1}],"active_combination":"ONE_WC","restrictions":[]}"""

    private fun w() = VehicleMasterApiTest.World(IdentityEnv)
    private fun vehicle(w: VehicleMasterApiTest.World): String = IdentityEnv.post("/v1/operator/vehicle-master", w.body(), w.dispatcherToken, null, idem()).json["id"].asString()

    val cases: List<ContractCase> = listOf(
        ContractCase("listVehicleMasters",
            200, { val w = w(); ContractRequest("GET", "/v1/operator/vehicle-master", headers = w.env.headers(w.supportToken)) },
            403, { val w = w(); ContractRequest("GET", "/v1/operator/vehicle-master", headers = w.env.headers(w.passengerToken)) }, server = { IdentityEnv.server }),
        ContractCase("createVehicleMaster",
            201, { val w = w(); ContractRequest("POST", "/v1/operator/vehicle-master", w.body(), w.env.headers(w.dispatcherToken, extra = idem())) },
            422, { val w = w(); ContractRequest("POST", "/v1/operator/vehicle-master", w.body(seats = "-1"), w.env.headers(w.dispatcherToken, extra = idem())) }, server = { IdentityEnv.server }),
        ContractCase("getVehicleMaster",
            200, { val w = w(); ContractRequest("GET", "/v1/operator/vehicle-master/${vehicle(w)}", headers = w.env.headers(w.supportToken)) },
            404, { val w = w(); ContractRequest("GET", "/v1/operator/vehicle-master/${UUID.randomUUID()}", headers = w.env.headers(w.supportToken)) }, server = { IdentityEnv.server }),
        ContractCase("updateVehicleMaster",
            200, { val w = w(); val id = vehicle(w); ContractRequest("PUT", "/v1/operator/vehicle-master/$id", w.body(seats = "5"), w.env.headers(w.dispatcherToken, extra = idem() + ("If-Match" to "\"1\""))) },
            412, { val w = w(); val id = vehicle(w); ContractRequest("PUT", "/v1/operator/vehicle-master/$id", w.body(seats = "5"), w.env.headers(w.dispatcherToken, extra = idem() + ("If-Match" to "\"9\""))) }, server = { IdentityEnv.server }),
        ContractCase("getVehicleCapacity",
            200, { val w = w(); ContractRequest("GET", "/v1/operator/vehicle-master/${vehicle(w)}/capacity", headers = w.env.headers(w.supportToken)) },
            404, { val w = w(); ContractRequest("GET", "/v1/operator/vehicle-master/${UUID.randomUUID()}/capacity", headers = w.env.headers(w.supportToken)) }, server = { IdentityEnv.server }),
        ContractCase("updateVehicleCapacity",
            200, { val w = w(); val id = vehicle(w); ContractRequest("PATCH", "/v1/operator/vehicle-master/$id/capacity", CAPACITY_BODY, w.env.headers(w.dispatcherToken, extra = idem() + ("If-Match" to "\"1\""))) },
            422, { val w = w(); val id = vehicle(w); ContractRequest("PATCH", "/v1/operator/vehicle-master/$id/capacity", """{"spaces":{"seat_places":6,"wheelchair_spaces":2,"seats_replaced_per_wheelchair":4}}""", w.env.headers(w.dispatcherToken, extra = idem() + ("If-Match" to "\"1\""))) }, server = { IdentityEnv.server }),
    )
}

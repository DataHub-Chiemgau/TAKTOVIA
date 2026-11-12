package de.taktovia.app.contract

import de.taktovia.app.fleet.InspectionApiTest
import de.taktovia.app.identity.IdentityEnv
import java.util.UUID

/** Vertragsfälle des Prüflauf-Starts (WP-M3-13): je Operation ein positiver Fall und ein Fehlerfall gegen die echte Anwendung. */
internal object InspectionContractCases {
    private fun idem() = mapOf("Idempotency-Key" to "contract-" + UUID.randomUUID())
    private fun w() = InspectionApiTest.World(IdentityEnv).also { it.template("PKW") }
    private fun started(w: InspectionApiTest.World): String {
        val v = w.vehicle("PKW"); val s = w.shift(vehicle = v)
        return IdentityEnv.post("/v1/inspections", w.body(s, v), w.token, null, idem()).json["id"].asString()
    }

    val cases: List<ContractCase> = listOf(
        ContractCase("createInspection",
            201, { val w = w(); val v = w.vehicle("PKW"); val s = w.shift(vehicle = v); ContractRequest("POST", "/v1/inspections", w.body(s, v), w.env.headers(w.token, extra = idem())) },
            404, { val w = w(); val v = w.vehicle("PKW"); ContractRequest("POST", "/v1/inspections", w.body(UUID.randomUUID(), v), w.env.headers(w.token, extra = idem())) }, server = { IdentityEnv.server }),
        ContractCase("getInspection",
            200, { val w = w(); ContractRequest("GET", "/v1/inspections/${started(w)}", headers = w.env.headers(w.token)) },
            404, { val w = w(); ContractRequest("GET", "/v1/inspections/${UUID.randomUUID()}", headers = w.env.headers(w.token)) }, server = { IdentityEnv.server }),
    )
}

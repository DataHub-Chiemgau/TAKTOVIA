package de.taktovia.app.contract

import de.taktovia.app.fleet.DefectApiTest
import de.taktovia.app.identity.IdentityEnv
import java.util.UUID

/** Vertragsfälle der Schadenakte (WP-M3-43 ff.): je Operation ein positiver Fall und ein Fehlerfall gegen die echte Anwendung. */
internal object DefectContractCases {
    private fun idem() = mapOf("Idempotency-Key" to "contract-" + UUID.randomUUID())
    private fun w() = DefectApiTest.World(IdentityEnv)
    private fun defect(w: DefectApiTest.World): String = IdentityEnv.post("/v1/defects", w.body(), w.dispatcherToken, null, idem()).json["id"].asString()

    val cases: List<ContractCase> = listOf(
        ContractCase("createDefect",
            201, { val w = w(); ContractRequest("POST", "/v1/defects", w.body(), w.env.headers(w.dispatcherToken, extra = idem())) },
            422, { val w = w(); ContractRequest("POST", "/v1/defects", w.body().replace(Regex(""""description":"[^"]*""""), """"description":"""""), w.env.headers(w.dispatcherToken, extra = idem())) }, server = { IdentityEnv.server }),
        ContractCase("getDefect",
            200, { val w = w(); ContractRequest("GET", "/v1/defects/${defect(w)}", headers = w.env.headers(w.workshopToken)) },
            404, { val w = w(); ContractRequest("GET", "/v1/defects/${UUID.randomUUID()}", headers = w.env.headers(w.workshopToken)) }, server = { IdentityEnv.server }),
        ContractCase("listDefects",
            200, { val w = w(); defect(w); ContractRequest("GET", "/v1/defects?vehicle_id=${w.vehicle}", headers = w.env.headers(w.workshopToken)) },
            403, { val w = w(); ContractRequest("GET", "/v1/defects", headers = w.env.headers(w.passengerToken)) }, server = { IdentityEnv.server }),
    )
}

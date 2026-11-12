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

/** Vertragsfälle des Statusautomaten der Schadenakte (WP-M3-47). */
internal object DefectStateContractCases {
    private fun idem() = mapOf("Idempotency-Key" to "contract-" + UUID.randomUUID())
    private fun w() = DefectApiTest.World(IdentityEnv)
    private fun defect(w: DefectApiTest.World): String = IdentityEnv.post("/v1/defects", w.body(), w.dispatcherToken, null, idem()).json["id"].asString()

    val cases: List<ContractCase> = listOf(
        ContractCase("transitionDefect",
            200, { val w = w(); ContractRequest("POST", "/v1/defects/${defect(w)}/transitions", """{"to":"REVIEWED"}""", w.env.headers(w.dispatcherToken, extra = idem() + ("If-Match" to "\"1\""))) },
            409, { val w = w(); ContractRequest("POST", "/v1/defects/${defect(w)}/transitions", """{"to":"CLOSED"}""", w.env.headers(w.workshopToken, extra = idem() + ("If-Match" to "\"1\""))) }, server = { IdentityEnv.server }),
        ContractCase("listDefectStatusHistory",
            200, { val w = w(); ContractRequest("GET", "/v1/defects/${defect(w)}/status-history", headers = w.env.headers(w.workshopToken)) },
            403, { val w = w(); ContractRequest("GET", "/v1/defects/${defect(w)}/status-history", headers = w.env.headers(w.driverToken)) }, server = { IdentityEnv.server }),
    )
}

/** Vertragsfälle der unveränderlichen Historie der Schadenakte (WP-M3-48). */
internal object DefectHistoryContractCases {
    private fun idem() = mapOf("Idempotency-Key" to "contract-" + UUID.randomUUID())
    private fun w() = DefectApiTest.World(IdentityEnv)
    private fun defect(w: DefectApiTest.World): String = IdentityEnv.post("/v1/defects", w.body(), w.dispatcherToken, null, idem()).json["id"].asString()

    val cases: List<ContractCase> = listOf(
        ContractCase("amendDefect",
            200, { val w = w(); ContractRequest("POST", "/v1/defects/${defect(w)}/amendments", """{"change_reason":"Beschreibung präzisiert","description":"Kratzer links"}""", w.env.headers(w.dispatcherToken, extra = idem())) },
            422, { val w = w(); ContractRequest("POST", "/v1/defects/${defect(w)}/amendments", """{"description":"ohne Grund"}""", w.env.headers(w.dispatcherToken, extra = idem())) }, server = { IdentityEnv.server }),
        ContractCase("listDefectHistory",
            200, { val w = w(); ContractRequest("GET", "/v1/defects/${defect(w)}/history", headers = w.env.headers(w.workshopToken)) },
            403, { val w = w(); ContractRequest("GET", "/v1/defects/${defect(w)}/history", headers = w.env.headers(w.dispatcherToken)) }, server = { IdentityEnv.server }),
        ContractCase("getDefectAsOf",
            200, { val w = w(); ContractRequest("GET", "/v1/defects/${defect(w)}/as-of?at=${java.time.Instant.now().plusSeconds(5)}", headers = w.env.headers(w.workshopToken)) },
            404, { val w = w(); ContractRequest("GET", "/v1/defects/${defect(w)}/as-of?at=2020-01-01T00:00:00Z", headers = w.env.headers(w.workshopToken)) }, server = { IdentityEnv.server }),
    )
}

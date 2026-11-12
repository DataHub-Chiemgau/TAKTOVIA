package de.taktovia.app.contract

import de.taktovia.app.driver.DriverMasterApiTest
import de.taktovia.app.identity.IdentityEnv
import java.util.UUID

/** Vertragsfälle des Fahrerstamms und der Qualifikationsrevisionen (WP-M3-05): je Operation ein positiver Fall und ein Fehlerfall gegen die echte Anwendung. */
internal object DriverMasterContractCases {
    private fun idem() = mapOf("Idempotency-Key" to "contract-" + UUID.randomUUID())
    private fun w() = DriverMasterApiTest.World(IdentityEnv)
    private fun driver(w: DriverMasterApiTest.World): String = IdentityEnv.post("/v1/drivers", w.body(), w.hrToken, null, idem()).json["id"].asString()

    val cases: List<ContractCase> = listOf(
        ContractCase("listDrivers",
            200, { val w = w(); ContractRequest("GET", "/v1/drivers", headers = w.env.headers(w.dispatcherToken)) },
            403, { val w = w(); ContractRequest("GET", "/v1/drivers", headers = w.env.headers(w.supportToken)) }, server = { IdentityEnv.server }),
        ContractCase("createDriver",
            201, { val w = w(); ContractRequest("POST", "/v1/drivers", w.body(), w.env.headers(w.hrToken, extra = idem())) },
            409, { val w = w(); val m = w.driverMembership(); IdentityEnv.post("/v1/drivers", w.body(m), w.hrToken, null, idem()); ContractRequest("POST", "/v1/drivers", w.body(m), w.env.headers(w.hrToken, extra = idem())) }, server = { IdentityEnv.server }),
        ContractCase("getDriver",
            200, { val w = w(); ContractRequest("GET", "/v1/drivers/${driver(w)}", headers = w.env.headers(w.dispatcherToken)) },
            404, { val w = w(); ContractRequest("GET", "/v1/drivers/${UUID.randomUUID()}", headers = w.env.headers(w.hrToken)) }, server = { IdentityEnv.server }),
        ContractCase("updateDriver",
            200, { val w = w(); val id = driver(w); ContractRequest("PUT", "/v1/drivers/$id", """{"display_name":"Neu","contact_channel":"APP","contact_value":"x","active":true}""", w.env.headers(w.hrToken, extra = idem() + ("If-Match" to "\"1\""))) },
            412, { val w = w(); val id = driver(w); ContractRequest("PUT", "/v1/drivers/$id", """{"display_name":"Neu","contact_channel":"APP","contact_value":"x","active":true}""", w.env.headers(w.hrToken, extra = idem() + ("If-Match" to "\"9\""))) }, server = { IdentityEnv.server }),
        ContractCase("listDriverQualifications",
            200, { val w = w(); ContractRequest("GET", "/v1/drivers/${driver(w)}/qualifications", headers = w.env.headers(w.hrToken)) },
            404, { val w = w(); ContractRequest("GET", "/v1/drivers/${UUID.randomUUID()}/qualifications", headers = w.env.headers(w.hrToken)) }, server = { IdentityEnv.server }),
        ContractCase("createDriverQualification",
            201, { val w = w(); val id = driver(w); ContractRequest("POST", "/v1/drivers/$id/qualifications", w.qual(), w.env.headers(w.hrToken, extra = idem() + ("If-Match" to "\"1\""))) },
            422, { val w = w(); val id = driver(w); ContractRequest("POST", "/v1/drivers/$id/qualifications", w.qual(code = "klein"), w.env.headers(w.hrToken, extra = idem() + ("If-Match" to "\"1\""))) }, server = { IdentityEnv.server }),
    )
}

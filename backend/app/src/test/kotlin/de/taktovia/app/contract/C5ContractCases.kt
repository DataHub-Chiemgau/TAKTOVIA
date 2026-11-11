package de.taktovia.app.contract

import de.taktovia.app.identity.IdentityEnv
import java.util.UUID

/** Vertragsfälle des Clusters wave7-c5 (WP-M4-06 Abwesenheit, WP-M4-08 Zeitereignisse, WP-M4-21 Belegupload): je Operation ein positiver Fall und ein Fehlerfall. */
internal object C5ContractCases {
    private val env = IdentityEnv
    private fun idem() = mapOf("Idempotency-Key" to "contract-" + UUID.randomUUID())

    private class World {
        val tenant = env.tenant("Vertrag-C5-" + UUID.randomUUID())
        val hr = UUID.randomUUID(); val planner = UUID.randomUUID(); val passenger = UUID.randomUUID(); val driver = UUID.randomUUID()
        init { env.grant(tenant, hr, "staff_planning"); env.grant(tenant, planner, "planner"); env.grant(tenant, passenger, "passenger"); env.grant(tenant, driver, "driver") }
        val hrToken = env.token(hr); val plannerToken = env.token(planner); val passengerToken = env.token(passenger); val driverToken = env.token(driver)
        fun absence(): String = env.post("/v1/absences", """{"person_ref":"${UUID.randomUUID()}","from":"2027-03-01T00:00:00Z","to":"2027-03-06T00:00:00Z","kind":"LEAVE"}""", hrToken, extra = idem()).also { check(it.status == 201) { it.text } }.json["id"].asString()
    }

    private val absenceBody get() = """{"person_ref":"${UUID.randomUUID()}","from":"2027-03-01T00:00:00Z","to":"2027-03-06T00:00:00Z","kind":"TRAINING"}"""

    val cases: List<ContractCase> = listOf(
        ContractCase("createAbsence",
            201, { val w = World(); ContractRequest("POST", "/v1/absences", absenceBody, env.headers(w.hrToken, extra = idem())) },
            403, { val w = World(); ContractRequest("POST", "/v1/absences", absenceBody, env.headers(w.plannerToken, extra = idem())) }, server = { env.server }),
        ContractCase("listAbsences",
            200, { val w = World(); w.absence(); ContractRequest("GET", "/v1/absences", null, env.headers(w.plannerToken)) },
            403, { val w = World(); ContractRequest("GET", "/v1/absences", null, env.headers(w.passengerToken)) }, server = { env.server }),
        ContractCase("getAbsence",
            200, { val w = World(); ContractRequest("GET", "/v1/absences/${w.absence()}", null, env.headers(w.hrToken)) },
            404, { val w = World(); ContractRequest("GET", "/v1/absences/${UUID.randomUUID()}", null, env.headers(w.hrToken)) }, server = { env.server }),
        ContractCase("withdrawAbsence",
            200, { val w = World(); ContractRequest("POST", "/v1/absences/${w.absence()}/withdraw", null, env.headers(w.hrToken, extra = idem() + ("If-Match" to "\"1\""))) },
            404, { val w = World(); ContractRequest("POST", "/v1/absences/${UUID.randomUUID()}/withdraw", null, env.headers(w.hrToken, extra = idem() + ("If-Match" to "\"1\""))) }, server = { env.server }),
    )
}

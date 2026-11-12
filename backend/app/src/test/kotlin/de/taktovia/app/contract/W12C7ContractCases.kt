package de.taktovia.app.contract

import de.taktovia.app.identity.IdentityEnv
import java.util.UUID

/** Vertragsfälle des Clusters wave12-c7 (WP-M5-19, `/v1/reports/jobs`): je Operation ein positiver Fall und ein Fehlerfall. */
internal object W12C7ContractCases {
    private val env = IdentityEnv
    private fun idem() = mapOf("Idempotency-Key" to "contract-" + UUID.randomUUID())
    private const val BODY = """{"kind":"TIME_PROOF","scope":{"from":"2026-10-01T00:00:00Z","to":"2026-10-02T00:00:00Z"}}"""

    private class World {
        val tenant = env.tenant("Vertrag-W12C7-" + UUID.randomUUID()); val hr = UUID.randomUUID(); val driver = UUID.randomUUID()
        init { env.grant(tenant, hr, "staff_planning"); env.grant(tenant, driver, "driver") }
        val hrToken = env.token(hr); val driverToken = env.token(driver)
        fun job(): String = env.post("/v1/reports/jobs", BODY, hrToken, extra = idem()).also { check(it.status == 202) { it.text } }.json["job_id"].asString()
    }

    val cases: List<ContractCase> = listOf(
        ContractCase("createReportJob",
            202, { val w = World(); ContractRequest("POST", "/v1/reports/jobs", BODY, env.headers(w.hrToken, extra = idem())) },
            403, { val w = World(); ContractRequest("POST", "/v1/reports/jobs", BODY, env.headers(w.driverToken, extra = idem())) }, server = { env.server }),
        ContractCase("getReportJob",
            200, { val w = World(); ContractRequest("GET", "/v1/reports/jobs/${w.job()}", null, env.headers(w.hrToken)) },
            404, { val w = World(); ContractRequest("GET", "/v1/reports/jobs/${UUID.randomUUID()}", null, env.headers(w.hrToken)) }, server = { env.server }),
    )
}

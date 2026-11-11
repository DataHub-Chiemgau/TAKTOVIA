package de.taktovia.app.contract

import de.taktovia.app.identity.IdentityEnv
import java.util.UUID

/** Vertragsfälle der Wirkungsanalyse (WP-M1-23): Analyse auslösen und Bericht lesen. */
internal object ImpactContractCases {
    private fun idem() = mapOf("Idempotency-Key" to "contract-" + UUID.randomUUID())

    private class World {
        val env = IdentityEnv
        val tenant = env.tenant("Vertrag-Wirkung-" + UUID.randomUUID())
        val admin = UUID.randomUUID(); val passenger = UUID.randomUUID()
        init { env.grant(tenant, admin, "tenant_admin"); env.grant(tenant, passenger, "passenger") }
        val adminToken = env.token(admin); val passengerToken = env.token(passenger)
        fun revision(): String = env.post("/v1/config/revisions",
            """{"scope_kind":"TENANT","scope_id":"$tenant","schema_version":"1","values":{"max_wait_min":20},"effective_from":"2026-12-01T00:00:00Z","reason":"Vertragsfall"}""", adminToken, extra = idem()).json["id"].asString()
        fun report(): String = env.server.send("POST", "/v1/config/revisions/${revision()}/impact", null, env.headers(adminToken, extra = idem() + ("If-Match" to "\"1\""))).json["id"].asString()
    }

    val cases: List<ContractCase> = listOf(
        ContractCase("requestConfigImpact",
            200, { val w = World(); ContractRequest("POST", "/v1/config/revisions/${w.revision()}/impact", null, w.env.headers(w.adminToken, extra = idem() + ("If-Match" to "\"1\""))) },
            412, { val w = World(); ContractRequest("POST", "/v1/config/revisions/${w.revision()}/impact", null, w.env.headers(w.adminToken, extra = idem() + ("If-Match" to "\"9\""))) }, server = { IdentityEnv.server }),
        ContractCase("getConfigImpactReport",
            200, { val w = World(); ContractRequest("GET", "/v1/config/impact-reports/${w.report()}", headers = w.env.headers(w.adminToken)) },
            404, { val w = World(); ContractRequest("GET", "/v1/config/impact-reports/${UUID.randomUUID()}", headers = w.env.headers(w.adminToken)) }, server = { IdentityEnv.server }),
    )
}

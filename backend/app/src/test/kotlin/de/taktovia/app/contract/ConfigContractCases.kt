package de.taktovia.app.contract

import de.taktovia.app.identity.IdentityEnv
import java.util.UUID

/** Vertragsfälle des Moduls `config` (WP-M1-19, WP-M1-20): Parameterkatalog, Revisionen anlegen, lesen, auflisten, Entwurf ändern. */
internal object ConfigContractCases {
    private fun idem() = mapOf("Idempotency-Key" to "contract-" + UUID.randomUUID())

    private class World {
        val env = IdentityEnv
        val tenant = env.tenant("Vertrag-Konfig-" + UUID.randomUUID())
        val admin = UUID.randomUUID(); val passenger = UUID.randomUUID()
        init { env.grant(tenant, admin, "tenant_admin"); env.grant(tenant, passenger, "passenger") }
        val adminToken = env.token(admin); val passengerToken = env.token(passenger)
        val body = """{"scope_kind":"TENANT","scope_id":"$tenant","schema_version":"1","values":{"max_wait_min":20},"effective_from":"2026-12-01T00:00:00Z","reason":"Vertragsfall"}"""
        fun create(): String = env.post("/v1/config/revisions", body, adminToken, extra = idem()).json["id"].asString()
    }

    val cases: List<ContractCase> = listOf(
        ContractCase("listConfigParameters",
            200, { val w = World(); ContractRequest("GET", "/v1/config/parameters", headers = w.env.headers(w.adminToken)) },
            403, { val w = World(); ContractRequest("GET", "/v1/config/parameters", headers = w.env.headers(w.passengerToken)) }, server = { IdentityEnv.server }),
        ContractCase("createConfigRevision",
            201, { val w = World(); ContractRequest("POST", "/v1/config/revisions", w.body, w.env.headers(w.adminToken, extra = idem())) },
            422, { val w = World(); ContractRequest("POST", "/v1/config/revisions", w.body.replace("20", "-5"), w.env.headers(w.adminToken, extra = idem())) }, server = { IdentityEnv.server }),
        ContractCase("listConfigRevisions",
            200, { val w = World(); w.create(); ContractRequest("GET", "/v1/config/revisions?scope_kind=TENANT&scope_id=${w.tenant}", headers = w.env.headers(w.adminToken)) },
            403, { val w = World(); ContractRequest("GET", "/v1/config/revisions", headers = w.env.headers(w.passengerToken)) }, server = { IdentityEnv.server }),
        ContractCase("getConfigRevision",
            200, { val w = World(); ContractRequest("GET", "/v1/config/revisions/${w.create()}", headers = w.env.headers(w.adminToken)) },
            404, { val w = World(); ContractRequest("GET", "/v1/config/revisions/${UUID.randomUUID()}", headers = w.env.headers(w.adminToken)) }, server = { IdentityEnv.server }),
        ContractCase("updateConfigRevision",
            200, { val w = World(); ContractRequest("PATCH", "/v1/config/revisions/${w.create()}", """{"reason":"Neuer Grund"}""", w.env.headers(w.adminToken, extra = idem() + ("If-Match" to "\"1\""))) },
            412, { val w = World(); ContractRequest("PATCH", "/v1/config/revisions/${w.create()}", """{"reason":"Neuer Grund"}""", w.env.headers(w.adminToken, extra = idem() + ("If-Match" to "\"9\""))) }, server = { IdentityEnv.server }),
    )
}

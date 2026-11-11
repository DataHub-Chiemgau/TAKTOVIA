package de.taktovia.app.contract

import de.taktovia.app.identity.IdentityEnv
import java.util.UUID

/** Vertragsfälle der Aufbewahrungsregeln und Löschläufe (WP-M1-33): Regeln anlegen, ändern, freigeben, auflisten; Lauf starten, Läufe und Löschprotokoll lesen. */
internal object RetentionContractCases {
    private fun idem() = mapOf("Idempotency-Key" to "contract-" + UUID.randomUUID())
    private const val RULE = """{"data_category":"LK_PII","retention_days":30,"action":"ANONYMIZE"}"""

    /** Mandant mit zwei Administratoren (Vier-Augen) und einem Disponenten (ohne Scope `retention:*`); je Aufruf neu. */
    private class World {
        val env = IdentityEnv; val tenant = env.tenant("Vertrag-Aufbewahrung-" + UUID.randomUUID())
        val adminA = UUID.randomUUID(); val adminB = UUID.randomUUID(); val dispatcher = UUID.randomUUID()
        init { env.grant(tenant, adminA, "tenant_admin"); env.grant(tenant, adminB, "tenant_admin"); env.grant(tenant, dispatcher, "dispatcher") }
        val tokenA = env.token(adminA); val tokenB = env.token(adminB); val dispatcherToken = env.token(dispatcher)
        /** Entwurf von Administrator A über die API; liefert Id. */
        fun draft(): String {
            val r = env.post("/v1/retention-rules", RULE, tokenA, null, idem())
            check(r.status == 201) { r.text }
            return r.json["id"].asString()
        }
        fun approved(): String = draft().also { id -> val r = env.post("/v1/retention-rules/$id/approvals", "{}", tokenB, null, idem() + ("If-Match" to "\"1\"")); check(r.status == 200) { r.text } }
    }

    val cases: List<ContractCase> = listOf(
        ContractCase("listRetentionRules",
            200, { val w = World(); w.draft(); ContractRequest("GET", "/v1/retention-rules?limit=10", headers = w.env.headers(w.tokenA)) },
            403, { val w = World(); ContractRequest("GET", "/v1/retention-rules", headers = w.env.headers(w.dispatcherToken)) }, server = { IdentityEnv.server }),
        ContractCase("createRetentionRule",
            201, { val w = World(); ContractRequest("POST", "/v1/retention-rules", RULE, w.env.headers(w.tokenA, extra = idem())) },
            422, { val w = World(); ContractRequest("POST", "/v1/retention-rules", """{"data_category":"LK_ERFUNDEN","retention_days":30,"action":"ANONYMIZE"}""", w.env.headers(w.tokenA, extra = idem())) }, server = { IdentityEnv.server }),
        ContractCase("updateRetentionRule",
            200, { val w = World(); ContractRequest("PUT", "/v1/retention-rules/${w.draft()}", """{"retention_days":60,"action":"DELETE"}""", w.env.headers(w.tokenA, extra = idem() + ("If-Match" to "\"1\""))) },
            412, { val w = World(); ContractRequest("PUT", "/v1/retention-rules/${w.draft()}", """{"retention_days":60,"action":"DELETE"}""", w.env.headers(w.tokenA, extra = idem() + ("If-Match" to "\"9\""))) }, server = { IdentityEnv.server }),
        ContractCase("approveRetentionRule",
            200, { val w = World(); ContractRequest("POST", "/v1/retention-rules/${w.draft()}/approvals", "{}", w.env.headers(w.tokenB, extra = idem() + ("If-Match" to "\"1\""))) },
            403, { val w = World(); ContractRequest("POST", "/v1/retention-rules/${w.draft()}/approvals", "{}", w.env.headers(w.tokenA, extra = idem() + ("If-Match" to "\"1\""))) }, server = { IdentityEnv.server }),
        ContractCase("startErasureJob",
            201, { val w = World(); ContractRequest("POST", "/v1/erasure-jobs", """{"rule_id":"${w.approved()}"}""", w.env.headers(w.tokenA, extra = idem())) },
            409, { val w = World(); ContractRequest("POST", "/v1/erasure-jobs", """{"rule_id":"${w.draft()}"}""", w.env.headers(w.tokenA, extra = idem())) }, server = { IdentityEnv.server }),
        ContractCase("listErasureJobs",
            200, { val w = World(); ContractRequest("GET", "/v1/erasure-jobs?limit=10", headers = w.env.headers(w.tokenA)) },
            403, { val w = World(); ContractRequest("GET", "/v1/erasure-jobs", headers = w.env.headers(w.dispatcherToken)) }, server = { IdentityEnv.server }),
        ContractCase("listErasureJournal",
            200, { val w = World(); ContractRequest("GET", "/v1/erasure-journal?job_id=${UUID.randomUUID()}", headers = w.env.headers(w.tokenA)) },
            403, { val w = World(); ContractRequest("GET", "/v1/erasure-journal", headers = w.env.headers(w.dispatcherToken)) }, server = { IdentityEnv.server }),
    )
}

package de.taktovia.app.contract

import de.taktovia.app.dispatch.ReplanHttpWorld
import java.util.UUID

/** Vertragsfälle des Clusters wave14-c6 (WP-M3-40: `POST` und `GET /v1/runs/{run_id}/revisions`): je Operation ein positiver Fall und ein Fehlerfall. Eigene Klasse je Cluster. */
internal object W14C6ContractCases {
    private val env = de.taktovia.app.identity.IdentityEnv

    val cases: List<ContractCase> = listOf(
        ContractCase("createRunRevision",
            201, { val w = ReplanHttpWorld(); ContractRequest("POST", "/v1/runs/${w.driverA.run}/revisions", w.body(w.swap()), env.headers(w.token, extra = mapOf("Idempotency-Key" to "c-" + UUID.randomUUID(), "If-Match" to "\"${w.revision(w.driverA.run)}\""))) },
            412, { val w = ReplanHttpWorld(); ContractRequest("POST", "/v1/runs/${w.driverA.run}/revisions", w.body(w.swap(), base = 1), env.headers(w.token, extra = mapOf("Idempotency-Key" to "c-" + UUID.randomUUID(), "If-Match" to "\"1\""))) },
            server = { env.server }),
        ContractCase("listRunRevisions",
            200, { val w = ReplanHttpWorld(); ContractRequest("GET", "/v1/runs/${w.driverA.run}/revisions", null, env.headers(w.token)) },
            404, { val w = ReplanHttpWorld(); ContractRequest("GET", "/v1/runs/${UUID.randomUUID()}/revisions", null, env.headers(w.token)) }, server = { env.server }),
    )
}

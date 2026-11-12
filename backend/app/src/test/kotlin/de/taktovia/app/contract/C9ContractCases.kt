package de.taktovia.app.contract

import de.taktovia.app.identity.IdentityEnv
import java.util.UUID

/** Vertragsfälle des Clusters wave9-c9 (WP-M4-29, WP-M4-33: Kostenzuordnung, Kostenereignis): je Operation ein positiver Fall und ein Fehlerfall. */
internal object C9ContractCases {
    private val env = IdentityEnv

    private class World {
        val tenant = env.tenant("Vertrag-C9-" + UUID.randomUUID()); val finance = UUID.randomUUID(); val le = UUID.randomUUID()
        init { env.tx.run(tenant) { j -> j.update("INSERT INTO taktovia.legal_entity(tenant_id,id,name,country) VALUES (?,?,'LE','DE')", tenant.value, le) }; env.grant(tenant, finance, "finance") }
        val token = env.token(finance)
    }

    val cases: List<ContractCase> = listOf(
        ContractCase("listCostObjects",
            200, { val w = World(); ContractRequest("GET", "/v1/cost-objects?kind=COST_CENTER&limit=10", null, env.headers(w.token)) },
            422, { val w = World(); ContractRequest("GET", "/v1/cost-objects?kind=PROJECT", null, env.headers(w.token)) }, server = { env.server }),
        ContractCase("listCostEvents",
            200, { val w = World(); ContractRequest("GET", "/v1/cost-events?legal_entity_id=${w.le}&limit=10", null, env.headers(w.token)) },
            422, { val w = World(); ContractRequest("GET", "/v1/cost-events?as_of=gestern", null, env.headers(w.token)) }, server = { env.server }),
    )
}

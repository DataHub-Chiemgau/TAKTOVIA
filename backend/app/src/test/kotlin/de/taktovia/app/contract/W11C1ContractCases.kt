package de.taktovia.app.contract

import de.taktovia.app.identity.IdentityEnv
import java.util.UUID

/**
 * Vertragsfälle des Clusters wave11-c1 (WP-M2-28: Änderungsstrom der Planrevisionen; WP-M3-26/-32: Fahrerprojektion): je Operation ein positiver Fall und ein Fehlerfall gegen die echte
 * Anwendung. Der Fehlerfall ist der fremde Kanal (Rolle ohne Zugriff, 403) bzw. eine ungültige Eingabe (422).
 */
internal object W11C1ContractCases {
    private val env = IdentityEnv

    private class World {
        val tenant = env.tenant("Vertrag-W11C1-" + UUID.randomUUID())
        private fun token(role: String): String { val s = UUID.randomUUID(); env.grant(tenant, s, role); return env.token(s) }
        val passenger = token("passenger"); val driver = token("driver")
    }

    private fun get(path: String, token: String) = ContractRequest("GET", path, null, env.headers(token))

    val cases: List<ContractCase> = listOf(
        ContractCase("listPlanChanges",
            200, { val w = World(); get("/v1/plan-changes?limit=10", w.passenger) },
            403, { val w = World(); get("/v1/plan-changes", w.driver) }, server = { env.server }),
        ContractCase("getPlanChangesSnapshot",
            200, { val w = World(); get("/v1/plan-changes/snapshot", w.passenger) },
            403, { val w = World(); get("/v1/plan-changes/snapshot", w.driver) }, server = { env.server }),
        ContractCase("listDriverPlanChanges",
            200, { val w = World(); get("/v1/driver/plan-changes", w.driver) },
            422, { val w = World(); get("/v1/driver/plan-changes?after=abc", w.driver) }, server = { env.server }),
        ContractCase("getDriverPlanChangesSnapshot",
            200, { val w = World(); get("/v1/driver/plan-changes/snapshot", w.driver) },
            403, { val w = World(); get("/v1/driver/plan-changes/snapshot", w.passenger) }, server = { env.server }),
    )
}

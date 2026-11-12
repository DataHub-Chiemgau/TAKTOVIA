package de.taktovia.app.contract

import de.taktovia.app.identity.IdentityEnv
import java.util.UUID

/**
 * Vertragsfälle des Clusters wave12-c5 (WP-M3-35 Live-Kanal, WP-M3-27 Planbestätigung, WP-M3-52 Alarme): je Operation ein positiver Fall und ein Fehlerfall gegen die echte Anwendung.
 */
internal object W12C5ContractCases {
    private val env = IdentityEnv

    private class World {
        val tenant = env.tenant("Vertrag-W12C5-" + UUID.randomUUID())
        private fun token(role: String): String { val s = UUID.randomUUID(); env.grant(tenant, s, role); return env.token(s) }
        val dispatcher = token("dispatcher"); val driver = token("driver"); val passenger = token("passenger")
    }

    private fun get(path: String, token: String) = ContractRequest("GET", path, null, env.headers(token))

    val cases: List<ContractCase> = listOf(
        ContractCase("streamOperatorLiveEvents",
            200, { val w = World(); get("/v1/operator/live/events?wait_seconds=0", w.dispatcher) },
            422, { val w = World(); get("/v1/operator/live/events?wait_seconds=0&after=abc", w.dispatcher) }, server = { env.server }),
        ContractCase("listOperatorLiveEvents",
            200, { val w = World(); get("/v1/operator/live/feed", w.dispatcher) },
            403, { val w = World(); get("/v1/operator/live/feed", w.driver) }, server = { env.server }),
        ContractCase("streamDriverLiveEvents",
            200, { val w = World(); get("/v1/driver/live/events?wait_seconds=0", w.driver) },
            403, { val w = World(); get("/v1/driver/live/events?wait_seconds=0", w.passenger) }, server = { env.server }),
    )
}

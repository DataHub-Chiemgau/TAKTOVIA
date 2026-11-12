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

    private fun alert(w: World): UUID = env.tx.run(w.tenant) { j ->
        env.server.ctx.getBean(de.taktovia.platform.alert.BusinessAlertPort::class.java).raise(j, de.taktovia.platform.alert.BusinessAlertRequest("OVERDUE", sourceEventId = UUID.randomUUID())).alertId
    }

    private fun postAck(id: UUID, token: String, revision: Long) = ContractRequest("POST", "/v1/operator/alerts/$id/acknowledgements", """{"cause":"HANDLED"}""",
        env.headers(token, extra = mapOf("Idempotency-Key" to "ack-" + UUID.randomUUID(), "If-Match" to "\"$revision\"")))

    private fun get(path: String, token: String) = ContractRequest("GET", path, null, env.headers(token))

    private fun ack(shift: UUID?, token: String, world: de.taktovia.app.driver.DriverRunHttpWorld.Driver, revision: Long) =
        ContractRequest("POST", "/v1/driver/shifts/${shift ?: world.shift}/run/acknowledgements", """{"plan_revision":$revision,"acknowledged_at":"2026-11-05T09:00:00Z"}""",
            env.headers(token, extra = mapOf("Idempotency-Key" to "ack-" + UUID.randomUUID(), "If-Match" to "\"$revision\"")))

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
        ContractCase("listOperatorAlerts",
            200, { val w = World(); alert(w); get("/v1/operator/alerts", w.dispatcher) },
            403, { val w = World(); get("/v1/operator/alerts", w.passenger) }, server = { env.server }),
        ContractCase("getOperatorAlert",
            200, { val w = World(); get("/v1/operator/alerts/${alert(w)}", w.dispatcher) },
            404, { val w = World(); get("/v1/operator/alerts/${UUID.randomUUID()}", w.dispatcher) }, server = { env.server }),
        ContractCase("acknowledgeOperatorAlert",
            200, { val w = World(); postAck(alert(w), w.dispatcher, 1) },
            404, { val w = World(); postAck(UUID.randomUUID(), w.dispatcher, 1) }, server = { env.server }),
        ContractCase("acknowledgeDriverRunRevision",
            200, { val w = de.taktovia.app.driver.DriverRunHttpWorld(); val d = w.driver(); val rev = w.plan(d.run, listOf(w.booking())); ack(null, d.token, d, rev) },
            404, { val w = de.taktovia.app.driver.DriverRunHttpWorld(); val d = w.driver(); val rev = w.plan(d.run, listOf(w.booking())); ack(UUID.randomUUID(), d.token, d, rev) }, server = { env.server }),
    )
}

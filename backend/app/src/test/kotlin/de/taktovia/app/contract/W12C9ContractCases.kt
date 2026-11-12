package de.taktovia.app.contract

import de.taktovia.app.identity.IdentityEnv
import java.util.UUID

/**
 * Vertragsfälle des Clusters wave12-c9 (WP-M4-41: Kilometerstandsregister): je Operation ein positiver Fall und ein Fehlerfall. Eigene Klasse je Cluster; die Welt baut auf
 * [W11C5ContractCases.World] auf (Mandant, Finanzrolle, Fahrzeug).
 */
internal object W12C9ContractCases {
    private val env = IdentityEnv
    private fun idem() = mapOf("Idempotency-Key" to "contract-" + UUID.randomUUID())

    fun reading(km: Long, ref: String, at: String) = """{"reading_km":$km,"source_ref":"$ref","measured_at":"$at"}"""

    /** Mandant mit Fahrzeug und Finanzrolle; `record` nimmt einen Werkstattstand über die echte Schnittstelle auf. */
    class World {
        val base = W11C5ContractCases.World()
        val token: String get() = base.finance
        val vehicle: UUID get() = base.base.vehicle
        fun record(km: Long, ref: String, at: String) {
            val r = env.server.send("POST", "/v1/vehicles/$vehicle/odometer", reading(km, ref, at), env.headers(token, extra = idem())); check(r.status == 201) { r.text }
        }

        /** Rückläufiger Stand: 5.000 km gemessen um 08:00, danach 4.000 km um 10:00 (ein offener Konflikt); liefert die Konfliktkennung. */
        fun conflict(): String {
            record(5_000, "wst-1", "2026-06-01T08:00:00Z"); record(4_000, "wst-2", "2026-06-01T10:00:00Z")
            val c = env.server.send("GET", "/v1/vehicles/$vehicle/odometer/conflicts?status=OPEN", null, env.headers(token)); check(c.status == 200) { c.text }
            return c.json["items"][0]["id"].asString()
        }
    }

    val cases: List<ContractCase> = listOf(
        ContractCase("createOdometerReading",
            201, { val w = World(); ContractRequest("POST", "/v1/vehicles/${w.vehicle}/odometer", reading(1_000, "wst-9", "2026-06-01T08:00:00Z"), env.headers(w.token, extra = idem())) },
            422, { val w = World(); ContractRequest("POST", "/v1/vehicles/${w.vehicle}/odometer", reading(-1, "wst-9", "2026-06-01T08:00:00Z"), env.headers(w.token, extra = idem())) }, server = { env.server }),
        ContractCase("listOdometerReadings",
            200, { val w = World(); w.record(1_000, "wst-1", "2026-06-01T08:00:00Z"); ContractRequest("GET", "/v1/vehicles/${w.vehicle}/odometer?limit=5", null, env.headers(w.token)) },
            404, { val w = World(); ContractRequest("GET", "/v1/vehicles/${UUID.randomUUID()}/odometer", null, env.headers(w.token)) }, server = { env.server }),
        ContractCase("listOdometerConflicts",
            200, { val w = World(); w.conflict(); ContractRequest("GET", "/v1/vehicles/${w.vehicle}/odometer/conflicts", null, env.headers(w.token)) },
            422, { val w = World(); ContractRequest("GET", "/v1/vehicles/${w.vehicle}/odometer/conflicts?status=GIBT_ES_NICHT", null, env.headers(w.token)) }, server = { env.server }),
        ContractCase("resolveOdometerConflict",
            200, { val w = World(); val c = w.conflict(); ContractRequest("POST", "/v1/vehicles/${w.vehicle}/odometer/conflicts/$c/resolution", """{"decision":"DISCARDED","reason":"Beide Stände nicht belegbar"}""", env.headers(w.token, extra = idem())) },
            422, { val w = World(); val c = w.conflict(); ContractRequest("POST", "/v1/vehicles/${w.vehicle}/odometer/conflicts/$c/resolution", """{"decision":"DISCARDED","reason":""}""", env.headers(w.token, extra = idem())) }, server = { env.server }),
    )
}

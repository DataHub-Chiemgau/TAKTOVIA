package de.taktovia.app.contract

import de.taktovia.app.identity.IdentityEnv
import java.util.UUID

/** Vertragsfälle des Fahrzeug- und Dienststatus (WP-M3-31, Welle 12 Cluster 4): Statusmeldung und Statusabruf des Fahrers, geprüft gegen `api/driver.yaml`. */
internal object W12C4ContractCases {
    private fun idem() = mapOf("Idempotency-Key" to "contract-" + UUID.randomUUID())
    private val env = IdentityEnv

    private class World {
        val tenant = env.tenant("Vertrag-Dienststatus-" + UUID.randomUUID())
        val driver = UUID.randomUUID(); val passenger = UUID.randomUUID()
        val vehicle = UUID.randomUUID(); val shift = UUID.randomUUID()
        init {
            env.grant(tenant, driver, "driver"); env.grant(tenant, passenger, "passenger")
            env.tx.run(tenant) { j ->
                j.update("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (?,?,'fixture','{}')", tenant.value, vehicle)
                j.update("INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state) VALUES (?,?,?,?,now(),now()+interval '8 hours','Europe/Berlin',0,'PLANNED')",
                    tenant.value, shift, driver, vehicle)
            }
        }
        val token = env.token(driver); val passengerToken = env.token(passenger)

        /** Gerät binden und die Schicht antreten (Voraussetzung jeder Statusmeldung). */
        fun started(): UUID = UUID.randomUUID().also { d ->
            env.post("/v1/driver/devices", """{"device_id":"$d","platform":"ANDROID","app_version":"1.0.0"}""", token, extra = idem())
            env.post("/v1/driver/shift-starts", """{"shift_id":"$shift","vehicle_id":"$vehicle","device_id":"$d"}""", token, extra = idem())
        }
        fun report(target: String, shiftId: UUID = shift) =
            """{"event_id":"${UUID.randomUUID()}","target_status":"$target","occurred_at":"2026-11-12T08:00:00Z","device_recorded_at":"2026-11-12T08:00:00Z","offline_recorded":false}"""
    }

    val cases: List<ContractCase> = listOf(
        ContractCase("reportDriverServiceStatus",
            200, { val w = World(); w.started(); ContractRequest("POST", "/v1/driver/shifts/${w.shift}/service-status", w.report("DISRUPTED"), env.headers(w.token, extra = idem())) },
            404, { val w = World(); w.started(); ContractRequest("POST", "/v1/driver/shifts/${UUID.randomUUID()}/service-status", w.report("DISRUPTED"), env.headers(w.token, extra = idem())) }, server = { IdentityEnv.server }),
        ContractCase("getDriverServiceStatus",
            200, { val w = World(); w.started(); ContractRequest("GET", "/v1/driver/shifts/${w.shift}/service-status", null, env.headers(w.token)) },
            404, { val w = World(); w.started(); ContractRequest("GET", "/v1/driver/shifts/${UUID.randomUUID()}/service-status", null, env.headers(w.token)) }, server = { IdentityEnv.server }),
    )
}

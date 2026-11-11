package de.taktovia.app.contract

import de.taktovia.app.identity.IdentityEnv
import java.util.UUID

/** Vertragsfälle der Fahrer-App-Schnittstellen (WP-M3-24): Gerätebindung und Ereigniseingang. */
internal object DriverContractCases {
    private fun idem() = mapOf("Idempotency-Key" to "contract-" + UUID.randomUUID())
    private val env = IdentityEnv

    private class World {
        val tenant = env.tenant("Vertrag-Fahrer-" + UUID.randomUUID())
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
        fun register(deviceId: UUID = UUID.randomUUID(), t: String = token) = env.post("/v1/driver/devices", """{"device_id":"$deviceId","platform":"ANDROID","app_version":"1.0.0"}""", t, extra = idem())
        fun event(device: UUID, shiftId: UUID = shift) = """{"event_id":"${UUID.randomUUID()}","device_id":"$device","shift_id":"$shiftId","vehicle_id":"$vehicle","plan_revision":1,"event_type":"ARRIVAL","occurred_at":"2026-11-12T08:00:00Z","device_recorded_at":"2026-11-12T08:00:00Z","offline_recorded":true}"""
    }

    val cases: List<ContractCase> = listOf(
        ContractCase("registerDriverDevice",
            201, { val w = World(); ContractRequest("POST", "/v1/driver/devices", """{"device_id":"${UUID.randomUUID()}","platform":"IOS","app_version":"1.0.0"}""", env.headers(w.token, extra = idem())) },
            403, { val w = World(); ContractRequest("POST", "/v1/driver/devices", """{"device_id":"${UUID.randomUUID()}","platform":"IOS","app_version":"1.0.0"}""", env.headers(w.passengerToken, extra = idem())) }, server = { IdentityEnv.server }),
        ContractCase("revokeDriverDevice",
            204, { val w = World(); val d = UUID.randomUUID(); w.register(d); ContractRequest("DELETE", "/v1/driver/devices/$d", null, env.headers(w.token, extra = idem() + ("If-Match" to "\"1\""))) },
            404, { val w = World(); ContractRequest("DELETE", "/v1/driver/devices/${UUID.randomUUID()}", null, env.headers(w.token, extra = idem() + ("If-Match" to "\"1\""))) }, server = { IdentityEnv.server }),
        ContractCase("recordDriverEvent",
            200, { val w = World(); val d = UUID.randomUUID(); w.register(d); ContractRequest("POST", "/v1/driver/events", w.event(d), env.headers(w.token, extra = idem())) },
            404, { val w = World(); val d = UUID.randomUUID(); w.register(d); ContractRequest("POST", "/v1/driver/events", w.event(d, shiftId = UUID.randomUUID()), env.headers(w.token, extra = idem())) }, server = { IdentityEnv.server }),
    )
}

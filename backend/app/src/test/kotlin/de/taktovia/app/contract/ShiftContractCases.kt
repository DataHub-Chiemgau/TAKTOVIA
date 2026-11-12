package de.taktovia.app.contract

import de.taktovia.app.identity.IdentityEnv
import java.util.UUID

/** Vertragsfälle der Schichtzuweisung (WP-M3-07): positiv und Fehlerfall je Operation, geprüft gegen `api/shifts.yaml`. */
internal object ShiftContractCases {
    private fun idem() = mapOf("Idempotency-Key" to "contract-" + UUID.randomUUID())
    private val env = IdentityEnv

    private class World {
        val tenant = env.tenant("Vertrag-Schicht-" + UUID.randomUUID())
        val dispatcher = UUID.randomUUID(); val driverAccount = UUID.randomUUID()
        init { env.grant(tenant, dispatcher, "dispatcher"); env.grant(tenant, driverAccount, "driver") }
        val token = env.token(dispatcher); val driverToken = env.token(driverAccount)
        val driver: UUID = UUID.randomUUID().also { env.exec("INSERT INTO taktovia.driver(tenant_id,id,display_name,contact_channel,contact_value) VALUES (?,?,'Fahrer','APP','x')", tenant.value, it) }
        val vehicle: UUID = UUID.randomUUID().also { env.exec("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config,vehicle_label) VALUES (?,?,'fixture','{}',?)", tenant.value, it, "V-" + it.toString().take(8)) }
        fun body(from: String = "2026-12-01T06:00:00Z", to: String = "2026-12-01T14:00:00Z") = """{"driver_id":"$driver","vehicle_id":"$vehicle","starts_at":"$from","ends_at":"$to","timezone":"Europe/Berlin"}"""
        fun create(): String = env.post("/v1/shifts", body(), token, extra = idem()).json["id"].asString()
    }

    val cases: List<ContractCase> = listOf(
        ContractCase("createStaffShift",
            201, { val w = World(); ContractRequest("POST", "/v1/shifts", w.body(), env.headers(w.token, extra = idem())) },
            409, { val w = World(); w.create(); ContractRequest("POST", "/v1/shifts", w.body("2026-12-01T10:00:00Z", "2026-12-01T12:00:00Z"), env.headers(w.token, extra = idem())) }, server = { IdentityEnv.server }),
        ContractCase("getStaffShift",
            200, { val w = World(); ContractRequest("GET", "/v1/shifts/${w.create()}", null, env.headers(w.token)) },
            404, { val w = World(); ContractRequest("GET", "/v1/shifts/${UUID.randomUUID()}", null, env.headers(w.token)) }, server = { IdentityEnv.server }),
        ContractCase("listStaffShifts",
            200, { val w = World(); w.create(); ContractRequest("GET", "/v1/shifts?driver_id=${w.driver}", null, env.headers(w.token)) },
            403, { val w = World(); ContractRequest("GET", "/v1/shifts", null, env.headers(w.driverToken)) }, server = { IdentityEnv.server }),
        ContractCase("updateStaffShift",
            200, { val w = World(); val id = w.create(); ContractRequest("PUT", "/v1/shifts/$id", w.body(to = "2026-12-01T15:00:00Z"), env.headers(w.token, extra = idem() + ("If-Match" to "\"1\""))) },
            412, { val w = World(); val id = w.create(); ContractRequest("PUT", "/v1/shifts/$id", w.body(to = "2026-12-01T15:00:00Z"), env.headers(w.token, extra = idem() + ("If-Match" to "\"9\""))) }, server = { IdentityEnv.server }),
    )
}

/** Vertragsfälle der Dienstanmeldung des Fahrers (WP-M3-25): positiv und Fehlerfall je Operation, geprüft gegen `api/driver.yaml`. */
internal object DriverShiftContractCases {
    private fun idem() = mapOf("Idempotency-Key" to "contract-" + UUID.randomUUID())
    private val env = IdentityEnv

    private class World {
        val tenant = env.tenant("Vertrag-Dienst-" + UUID.randomUUID())
        val account = UUID.randomUUID(); val dispatcher = UUID.randomUUID(); val passenger = UUID.randomUUID()
        val vehicle: UUID = UUID.randomUUID(); val driverId: UUID = UUID.randomUUID()
        init {
            val m = env.grant(tenant, account, "driver"); env.grant(tenant, dispatcher, "dispatcher"); env.grant(tenant, passenger, "passenger")
            env.exec("INSERT INTO taktovia.driver(tenant_id,id,membership_id,display_name,contact_channel,contact_value) VALUES (?,?,?,'Fahrer','APP','x')", tenant.value, driverId, m)
            env.exec("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config,vehicle_label) VALUES (?,?,'fixture','{}','V-1')", tenant.value, vehicle)
        }
        val token = env.token(account); val dispatcherToken = env.token(dispatcher); val passengerToken = env.token(passenger)
        fun shift(): String = env.post("/v1/shifts", """{"driver_id":"$driverId","vehicle_id":"$vehicle","starts_at":"${java.time.Instant.now().minusSeconds(300)}","ends_at":"${java.time.Instant.now().plusSeconds(14400)}","timezone":"Europe/Berlin"}""",
            dispatcherToken, extra = idem()).json["id"].asString()
        fun device(): String = UUID.randomUUID().toString().also { env.post("/v1/driver/devices", """{"device_id":"$it","platform":"ANDROID","app_version":"1.0.0"}""", token, extra = idem()) }
    }

    val cases: List<ContractCase> = listOf(
        ContractCase("listDriverShifts",
            200, { val w = World(); w.shift(); ContractRequest("GET", "/v1/driver/shifts", null, env.headers(w.token)) },
            403, { val w = World(); ContractRequest("GET", "/v1/driver/shifts", null, env.headers(w.passengerToken)) }, server = { IdentityEnv.server }),
        ContractCase("startDriverShift",
            200, { val w = World(); val s = w.shift(); val d = w.device(); ContractRequest("POST", "/v1/driver/shift-starts", """{"shift_id":"$s","vehicle_id":"${w.vehicle}","device_id":"$d"}""", env.headers(w.token, extra = idem())) },
            404, { val w = World(); val d = w.device(); ContractRequest("POST", "/v1/driver/shift-starts", """{"shift_id":"${UUID.randomUUID()}","vehicle_id":"${w.vehicle}","device_id":"$d"}""", env.headers(w.token, extra = idem())) }, server = { IdentityEnv.server }),
        ContractCase("endDriverShift",
            200, { val w = World(); val s = w.shift(); val d = w.device(); env.post("/v1/driver/shift-starts", """{"shift_id":"$s","vehicle_id":"${w.vehicle}","device_id":"$d"}""", w.token, extra = idem())
                ContractRequest("POST", "/v1/driver/shift-ends", """{"shift_id":"$s","device_id":"$d"}""", env.headers(w.token, extra = idem())) },
            409, { val w = World(); val s = w.shift(); val d = w.device(); ContractRequest("POST", "/v1/driver/shift-ends", """{"shift_id":"$s","device_id":"$d"}""", env.headers(w.token, extra = idem())) }, server = { IdentityEnv.server }),
        ContractCase("getDriverRun",
            200, { val w = World(); val s = w.shift(); ContractRequest("GET", "/v1/driver/shifts/$s/run", null, env.headers(w.token)) },
            404, { val w = World(); ContractRequest("GET", "/v1/driver/shifts/${UUID.randomUUID()}/run", null, env.headers(w.token)) }, server = { IdentityEnv.server }),
    )
}

/** Vertragsfälle der Positionserfassung (WP-M3-34): Positionsannahme der Fahrer-App und Leitstellenliste, geprüft gegen `api/driver.yaml` und `api/fleet-safety.yaml`. */
internal object PositionContractCases {
    private fun idem() = mapOf("Idempotency-Key" to "contract-" + UUID.randomUUID())
    private val env = IdentityEnv

    private class World {
        val tenant = env.tenant("Vertrag-Position-" + UUID.randomUUID())
        val account = UUID.randomUUID(); val dispatcher = UUID.randomUUID(); val driverId: UUID = UUID.randomUUID(); val vehicle: UUID = UUID.randomUUID()
        init {
            val m = env.grant(tenant, account, "driver"); env.grant(tenant, dispatcher, "dispatcher")
            env.exec("INSERT INTO taktovia.driver(tenant_id,id,membership_id,display_name,contact_channel,contact_value) VALUES (?,?,?,'Fahrer','APP','x')", tenant.value, driverId, m)
            env.exec("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config,vehicle_label) VALUES (?,?,'fixture','{}','V-P')", tenant.value, vehicle)
        }
        val token = env.token(account); val dispatcherToken = env.token(dispatcher)
        fun shift(): String = env.post("/v1/shifts", """{"driver_id":"$driverId","vehicle_id":"$vehicle","starts_at":"${java.time.Instant.now().minusSeconds(300)}","ends_at":"${java.time.Instant.now().plusSeconds(14400)}","timezone":"Europe/Berlin"}""",
            dispatcherToken, extra = idem()).json["id"].asString()
        fun device(): String = UUID.randomUUID().toString().also { env.post("/v1/driver/devices", """{"device_id":"$it","platform":"ANDROID","app_version":"1.0.0"}""", token, extra = idem()) }
        fun started(): Pair<String, String> { val s = shift(); val d = device(); env.post("/v1/driver/shift-starts", """{"shift_id":"$s","vehicle_id":"$vehicle","device_id":"$d"}""", token, extra = idem()); return s to d }
        fun body(s: String, d: String) = """{"shift_id":"$s","vehicle_id":"$vehicle","device_id":"$d","samples":[{"position":{"latitude":51.05,"longitude":13.74},"accuracy_meters":4.5,"measured_at":"${java.time.Instant.now().minusSeconds(2)}"}]}"""
    }

    val cases: List<ContractCase> = listOf(
        ContractCase("recordDriverPosition",
            200, { val w = World(); val (s, d) = w.started(); ContractRequest("POST", "/v1/driver/positions", w.body(s, d), env.headers(w.token, extra = idem())) },
            409, { val w = World(); val s = w.shift(); val d = w.device(); ContractRequest("POST", "/v1/driver/positions", w.body(s, d), env.headers(w.token, extra = idem())) }, server = { IdentityEnv.server }),
        ContractCase("listOperatorVehicles",
            200, { val w = World(); val (s, d) = w.started(); env.post("/v1/driver/positions", w.body(s, d), w.token, extra = idem()); ContractRequest("GET", "/v1/operator/vehicles", null, env.headers(w.dispatcherToken)) },
            403, { val w = World(); ContractRequest("GET", "/v1/operator/vehicles", null, env.headers(w.token)) }, server = { IdentityEnv.server }),
    )
}

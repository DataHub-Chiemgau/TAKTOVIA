package de.taktovia.app.contract

import de.taktovia.app.fleet.InspectionApiTest
import de.taktovia.app.identity.IdentityEnv
import java.util.UUID

/** Vertragsfälle des Clusters wave14-c2 (WP-M3-22, Fahrzeugwechsel in laufender Schicht): je Operation ein positiver Fall und ein Fehlerfall gegen die echte Anwendung. */
internal object W14C2ContractCases {
    private fun idem() = mapOf("Idempotency-Key" to "contract-" + UUID.randomUUID())

    /** Qualifizierter Fahrer mit angetretener Schicht auf einem Fahrzeug und einem aktiven Ersatzfahrzeug. */
    class Setup {
        val w = InspectionApiTest.World(IdentityEnv)
        val vehicle: UUID = w.vehicle("PKW")
        val replacement: UUID = w.vehicle("PKW")
        val shift: UUID
        init {
            w.env.exec("INSERT INTO taktovia.qualification_revision(tenant_id,id,driver_id,qualification_code,revision,valid_from,approval_status,driver_seq) VALUES (?,?,?,'PBEFG',1,now() - interval '30 days','APPROVED',1)", w.tenant.value, UUID.randomUUID(), w.driverId)
            w.env.exec("UPDATE taktovia.driver SET qualification_seq = 1 WHERE id = ?", w.driverId)
            shift = w.shift(vehicle = vehicle)
        }
        fun body(to: UUID = replacement, from: UUID = vehicle, reason: String = "BREAKDOWN") = """{"from_vehicle_id":"$from","new_vehicle_id":"$to","reason_code":"$reason"}"""
    }

    val cases: List<ContractCase> = listOf(
        ContractCase("createShiftVehicleSwitch",
            201, { val s = Setup(); ContractRequest("POST", "/v1/shifts/${s.shift}/vehicle-switches", s.body(), s.w.env.headers(s.w.token, extra = idem())) },
            422, { val s = Setup(); ContractRequest("POST", "/v1/shifts/${s.shift}/vehicle-switches", s.body(reason = "OTHER"), s.w.env.headers(s.w.token, extra = idem())) }, server = { IdentityEnv.server }),
        ContractCase("listShiftVehicleSwitches",
            200, { val s = Setup(); ContractRequest("GET", "/v1/shifts/${s.shift}/vehicle-switches", headers = s.w.env.headers(s.w.dispatcherToken)) },
            404, { val s = Setup(); ContractRequest("GET", "/v1/shifts/${UUID.randomUUID()}/vehicle-switches", headers = s.w.env.headers(s.w.dispatcherToken)) }, server = { IdentityEnv.server }),
    )
}

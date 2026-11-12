package de.taktovia.app.contract

import de.taktovia.app.fleet.InspectionApiTest
import de.taktovia.app.identity.IdentityEnv
import java.time.Instant
import java.util.UUID

/** Vertragsfälle des Clusters wave11-c4 (WP-M3-14, -15, -17): je Operation ein positiver Fall und ein Fehlerfall gegen die echte Anwendung. */
internal object W11C4ContractCases {
    private val env = IdentityEnv
    private fun idem() = mapOf("Idempotency-Key" to "contract-" + UUID.randomUUID())
    private fun im(rev: Long) = mapOf("If-Match" to "\"$rev\"")

    /** Gestarteter Prüflauf eines qualifizierten Fahrers mit freigegebener Vorlage (drei Pflichtschritte S1..S3). */
    class Started(val w: InspectionApiTest.World, val id: String, val shift: UUID, val vehicle: UUID) {
        fun answers(status: String = "OK") = (1..3).joinToString(",") { """{"step_code":"S$it","answer":"$status","note":"x","answered_at":"${Instant.now().minusSeconds(20)}"}""" }
        fun submitBody(a: String = answers()) = """{"answers":[$a],"client_submitted_at":"${Instant.now().minusSeconds(2)}"}"""
    }

    fun started(): Started {
        val w = InspectionApiTest.World(env)
        env.exec("INSERT INTO taktovia.qualification_revision(tenant_id,id,driver_id,qualification_code,revision,valid_from,approval_status,driver_seq) VALUES (?,?,?,'PBEFG',1,now() - interval '30 days','APPROVED',1)", w.tenant.value, UUID.randomUUID(), w.driverId)
        env.exec("UPDATE taktovia.driver SET qualification_seq = 1 WHERE id = ?", w.driverId)
        w.template("PKW")
        val vehicle = w.vehicle("PKW"); val shift = w.shift(vehicle = vehicle)
        val c = env.post("/v1/inspections", w.body(shift, vehicle), w.token, null, idem())
        check(c.status == 201) { c.text }
        return Started(w, c.json["id"].asString(), shift, vehicle)
    }

    val cases: List<ContractCase> = listOf(
        ContractCase("submitInspection",
            200, { val s = started(); ContractRequest("POST", "/v1/inspections/${s.id}/submit", s.submitBody(), s.w.env.headers(s.w.token, extra = idem() + im(1))) },
            404, { val s = started(); ContractRequest("POST", "/v1/inspections/${UUID.randomUUID()}/submit", s.submitBody(), s.w.env.headers(s.w.token, extra = idem() + im(1))) }, server = { IdentityEnv.server }),
    )
}

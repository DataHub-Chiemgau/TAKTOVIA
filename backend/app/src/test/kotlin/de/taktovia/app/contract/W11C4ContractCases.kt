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

    /** Lauf mit Startwert-Schritt ODO (Kilometerstand), letzter bestätigter Wert 10000; liefert Lauf-ID und die Welt. */
    private fun startValues(): Pair<String, Started> {
        val w = InspectionApiTest.World(env)
        val steps = """[{"step_code":"ODO","title":"Kilometerstand","input_kind":"ODOMETER_KM","mandatory":true,"safety_critical":false}]"""
        val created = env.post("/v1/operator/checklist-templates", """{"name":"Start","vehicle_class":"PKW","service_mode":"STANDARD","steps":$steps}""", env.token(w.author), null, idem())
        check(created.status == 201) { created.text }
        val p = env.server.send("POST", "/v1/operator/checklist-templates/${created.json["id"].asString()}/publish", null, env.headers(env.token(w.reviewer), extra = idem() + im(1)), null)
        check(p.status == 200) { p.text }
        val vehicle = w.vehicle("PKW"); env.exec("UPDATE taktovia.vehicle SET last_confirmed_odometer_km = 10000 WHERE id = ?", vehicle)
        val shift = w.shift(vehicle = vehicle)
        val c = env.post("/v1/inspections", w.body(shift, vehicle), w.token, null, idem())
        check(c.status == 201) { c.text }
        return c.json["id"].asString() to Started(w, c.json["id"].asString(), shift, vehicle)
    }

    private fun doneRun(): Started = started().also { s ->
        val r = env.server.send("POST", "/v1/inspections/${s.id}/submit", s.submitBody(), env.headers(s.w.token, extra = idem() + im(1)), "application/json")
        check(r.status == 200) { r.text }
    }

    private fun draftBody() = """{"answers":[{"step_code":"S1","answer":"OK","answered_at":"${Instant.now().minusSeconds(20)}"}]}"""

    val cases: List<ContractCase> = listOf(
        ContractCase("saveInspectionDraft",
            200, { val s = started(); ContractRequest("PUT", "/v1/inspections/${s.id}", draftBody(), s.w.env.headers(s.w.token, extra = idem() + im(1))) },
            404, { val s = started(); ContractRequest("PUT", "/v1/inspections/${UUID.randomUUID()}", """{"answers":[]}""", s.w.env.headers(s.w.token, extra = idem() + im(1))) }, server = { IdentityEnv.server }),
        ContractCase("justifyInspectionAnswer",
            200, { val s = startValues(); ContractRequest("POST", "/v1/inspections/${s.first}/answers/ODO/justification", """{"number_value":9000,"reason":"Tacho defekt"}""", env.headers(env.token(s.second.w.reviewer), extra = idem() + im(1))) },
            422, { val s = startValues(); ContractRequest("POST", "/v1/inspections/${s.first}/answers/ODO/justification", """{"number_value":9000,"reason":" "}""", env.headers(env.token(s.second.w.reviewer), extra = idem() + im(1))) }, server = { IdentityEnv.server }),
        ContractCase("createInspectionAmendment",
            201, { val s = doneRun(); ContractRequest("POST", "/v1/inspections/${s.id}/amendments", """{"step_code":"S2","kind":"CORRECTION","new_answer":"DEFECT","new_note":"Kratzer","reason":"Fehler"}""", s.w.env.headers(env.token(s.w.author), extra = idem())) },
            422, { val s = doneRun(); ContractRequest("POST", "/v1/inspections/${s.id}/amendments", """{"step_code":"S2","kind":"CORRECTION","new_answer":"OK"}""", s.w.env.headers(env.token(s.w.author), extra = idem())) }, server = { IdentityEnv.server }),
        ContractCase("submitInspection",
            200, { val s = started(); ContractRequest("POST", "/v1/inspections/${s.id}/submit", s.submitBody(), s.w.env.headers(s.w.token, extra = idem() + im(1))) },
            404, { val s = started(); ContractRequest("POST", "/v1/inspections/${UUID.randomUUID()}/submit", s.submitBody(), s.w.env.headers(s.w.token, extra = idem() + im(1))) }, server = { IdentityEnv.server }),
    )
}

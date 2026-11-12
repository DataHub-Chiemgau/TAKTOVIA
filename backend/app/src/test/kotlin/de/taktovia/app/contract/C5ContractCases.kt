package de.taktovia.app.contract

import de.taktovia.app.identity.IdentityEnv
import java.util.UUID

/** Vertragsfälle des Clusters wave7-c5 (WP-M4-06 Abwesenheit, WP-M4-08 Zeitereignisse, WP-M4-21 Belegupload): je Operation ein positiver Fall und ein Fehlerfall. */
internal object C5ContractCases {
    private val env = IdentityEnv
    private fun idem() = mapOf("Idempotency-Key" to "contract-" + UUID.randomUUID())

    private class World {
        val tenant = env.tenant("Vertrag-C5-" + UUID.randomUUID())
        val hr = UUID.randomUUID(); val planner = UUID.randomUUID(); val passenger = UUID.randomUUID(); val driver = UUID.randomUUID()
        val finance = UUID.randomUUID(); val le = UUID.randomUUID()
        init { env.tx.run(tenant) { j -> j.update("INSERT INTO taktovia.legal_entity(tenant_id,id,name,country) VALUES (?,?,'LE','DE')", tenant.value, le) }; env.grant(tenant, finance, "finance") }
        val financeToken = env.token(finance)
        init { env.grant(tenant, hr, "staff_planning"); env.grant(tenant, planner, "planner"); env.grant(tenant, passenger, "passenger"); env.grant(tenant, driver, "driver") }
        val hrToken = env.token(hr); val plannerToken = env.token(planner); val passengerToken = env.token(passenger); val driverToken = env.token(driver)
        fun absence(): String = env.post("/v1/absences", """{"person_ref":"${UUID.randomUUID()}","from":"2027-03-01T00:00:00Z","to":"2027-03-06T00:00:00Z","kind":"LEAVE"}""", hrToken, extra = idem()).also { check(it.status == 201) { it.text } }.json["id"].asString()
    }

    private val absenceBody get() = """{"person_ref":"${UUID.randomUUID()}","from":"2027-03-01T00:00:00Z","to":"2027-03-06T00:00:00Z","kind":"TRAINING"}"""

    private fun timeBody() = """{"event_id":"${UUID.randomUUID()}","kind":"WORK_START","occurred_at":"2026-12-01T06:00:00Z","device_recorded_at":"2026-12-01T06:00:00Z","channel":"APP","shift_id":null}"""
    private fun backfillBody(person: String) = """{"event_id":"${UUID.randomUUID()}","person_ref":"$person","kind":"WORK_END","occurred_at":"2026-12-01T14:00:00Z","shift_id":null,"reason":"Stempeln vergessen"}"""

    private val jpegBytes = ByteArray(1024) { (it * 7).toByte() }.also { it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte(); it[2] = 0xFF.toByte() }
    private val jpegHash: String get() = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(jpegBytes))
    private fun ticketBody(w: World) = """{"legal_entity_id":"${w.le}","purpose":"RECEIPT","mime":"image/jpeg","size_bytes":${jpegBytes.size},"sha256":"$jpegHash"}"""
    private fun World.issueTicket(upload: Boolean = true): String = env.post("/v1/expenses/receipts", ticketBody(this), financeToken, extra = idem()).also { check(it.status == 201) { it.text } }.json.let { j ->
        if (upload) env.server.ctx.getBean(de.taktovia.platform.storage.TenantObjectStore::class.java).put(de.taktovia.platform.storage.ObjectKey(tenant, j["upload_key"].asString()), jpegBytes, "image/jpeg")
        j["ticket_id"].asString()
    }

    val cases: List<ContractCase> = listOf(
        ContractCase("createAbsence",
            201, { val w = World(); ContractRequest("POST", "/v1/absences", absenceBody, env.headers(w.hrToken, extra = idem())) },
            403, { val w = World(); ContractRequest("POST", "/v1/absences", absenceBody, env.headers(w.plannerToken, extra = idem())) }, server = { env.server }),
        ContractCase("listAbsences",
            200, { val w = World(); w.absence(); ContractRequest("GET", "/v1/absences", null, env.headers(w.plannerToken)) },
            403, { val w = World(); ContractRequest("GET", "/v1/absences", null, env.headers(w.passengerToken)) }, server = { env.server }),
        ContractCase("getAbsence",
            200, { val w = World(); ContractRequest("GET", "/v1/absences/${w.absence()}", null, env.headers(w.hrToken)) },
            404, { val w = World(); ContractRequest("GET", "/v1/absences/${UUID.randomUUID()}", null, env.headers(w.hrToken)) }, server = { env.server }),
        ContractCase("withdrawAbsence",
            200, { val w = World(); ContractRequest("POST", "/v1/absences/${w.absence()}/withdraw", null, env.headers(w.hrToken, extra = idem() + ("If-Match" to "\"1\""))) },
            404, { val w = World(); ContractRequest("POST", "/v1/absences/${UUID.randomUUID()}/withdraw", null, env.headers(w.hrToken, extra = idem() + ("If-Match" to "\"1\""))) }, server = { env.server }),

        ContractCase("recordTimeEvent",
            201, { val w = World(); ContractRequest("POST", "/v1/time-events", timeBody(), env.headers(w.driverToken, extra = idem())) },
            403, { val w = World(); ContractRequest("POST", "/v1/time-events", timeBody(), env.headers(w.passengerToken, extra = idem())) }, server = { env.server }),
        ContractCase("listTimeEvents",
            200, { val w = World(); ContractRequest("GET", "/v1/time-events", null, env.headers(w.driverToken)) },
            404, { val w = World(); ContractRequest("GET", "/v1/time-events?person_ref=${UUID.randomUUID()}", null, env.headers(w.driverToken)) }, server = { env.server }),
        ContractCase("listTimeIntervals",
            200, { val w = World(); ContractRequest("GET", "/v1/time-events/intervals?from=2026-12-01T00:00:00Z&to=2026-12-02T00:00:00Z", null, env.headers(w.driverToken)) },
            404, { val w = World(); ContractRequest("GET", "/v1/time-events/intervals?person_ref=${UUID.randomUUID()}&from=2026-12-01T00:00:00Z&to=2026-12-02T00:00:00Z", null, env.headers(w.driverToken)) }, server = { env.server }),
        ContractCase("createTimeEventBackfill",
            201, { val w = World(); ContractRequest("POST", "/v1/time-events/backfills", backfillBody(w.driver.toString()), env.headers(w.hrToken, extra = idem())) },
            403, { val w = World(); ContractRequest("POST", "/v1/time-events/backfills", backfillBody(w.driver.toString()), env.headers(w.driverToken, extra = idem())) }, server = { env.server }),
        ContractCase("requestReceiptUpload",
            201, { val w = World(); ContractRequest("POST", "/v1/expenses/receipts", ticketBody(w), env.headers(w.financeToken, extra = idem())) },
            403, { val w = World(); ContractRequest("POST", "/v1/expenses/receipts", ticketBody(w), env.headers(w.passengerToken, extra = idem())) }, server = { env.server }),
        ContractCase("completeReceiptUpload",
            201, { val w = World(); ContractRequest("POST", "/v1/expenses/receipts/${w.issueTicket()}/complete", null, env.headers(w.financeToken, extra = idem() + ("If-Match" to "\"1\""))) },
            404, { val w = World(); ContractRequest("POST", "/v1/expenses/receipts/${UUID.randomUUID()}/complete", null, env.headers(w.financeToken, extra = idem() + ("If-Match" to "\"1\""))) }, server = { env.server }),
        ContractCase("getReceiptDocument",
            200, { val w = World(); val t = w.issueTicket(); val d = env.post("/v1/expenses/receipts/$t/complete", "", w.financeToken, extra = idem() + ("If-Match" to "\"1\"")).json["document_id"].asString(); ContractRequest("GET", "/v1/expenses/receipts/$d", null, env.headers(w.financeToken)) },
            404, { val w = World(); ContractRequest("GET", "/v1/expenses/receipts/${UUID.randomUUID()}", null, env.headers(w.financeToken)) }, server = { env.server }),
    )
}

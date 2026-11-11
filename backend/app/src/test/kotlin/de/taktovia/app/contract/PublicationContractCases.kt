package de.taktovia.app.contract

import de.taktovia.app.identity.IdentityEnv
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Vertragsfälle des Veröffentlichungswegs (WP-M1-22): validieren, freigeben, publizieren, zurücknehmen. */
internal object PublicationContractCases {
    private fun idem() = mapOf("Idempotency-Key" to "contract-" + UUID.randomUUID())

    private class World {
        val env = IdentityEnv
        val tenant = env.tenant("Vertrag-Publikation-" + UUID.randomUUID())
        val alice = UUID.randomUUID(); val bob = UUID.randomUUID(); val passenger = UUID.randomUUID()
        init { env.grant(tenant, alice, "tenant_admin"); env.grant(tenant, bob, "planner"); env.grant(tenant, passenger, "passenger") }
        val aliceToken = env.token(alice); val bobToken = env.token(bob); val passengerToken = env.token(passenger)
        val from: Instant = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)
        fun h(token: String, ifMatch: Long? = null) = env.headers(token, extra = idem() + (if (ifMatch != null) mapOf("If-Match" to "\"$ifMatch\"") else emptyMap()))
        fun draft(max: Int = 20): String = env.post("/v1/config/revisions",
            """{"scope_kind":"TENANT","scope_id":"$tenant","schema_version":"1","values":{"max_wait_min":$max},"effective_from":"$from","reason":"Vertragsfall"}""", aliceToken, extra = idem()).json["id"].asString()
        fun send(method: String, path: String, token: String, ifMatch: Long?, body: String? = null) = env.server.send(method, path, body, h(token, ifMatch))
        fun validated(): String { val id = draft(); check(send("POST", "/v1/config/revisions/$id/validate", aliceToken, 1).status == 200); return id }
        fun approved(): String { val id = validated(); check(send("POST", "/v1/config/revisions/$id/approve", bobToken, 2).status == 200); return id }
        fun report(id: String, rv: Long = 1): String = send("POST", "/v1/config/revisions/$id/impact", aliceToken, rv).json["id"].asString()
        fun publishBody(id: String, report: String) = """{"effective_from":"$from","reason":"Vertragsfall veröffentlichen","impact_report_id":"$report"}"""
        fun published(): String {
            val id = draft(); val rep = report(id)
            check(send("POST", "/v1/config/revisions/$id/validate", aliceToken, 1).status == 200); check(send("POST", "/v1/config/revisions/$id/approve", bobToken, 2).status == 200)
            val r = send("POST", "/v1/config/revisions/$id/publish", bobToken, 3, publishBody(id, rep)); check(r.status == 200) { r.text }
            return id
        }
    }

    val cases: List<ContractCase> = listOf(
        ContractCase("validateConfigRevision",
            200, { val w = World(); val id = w.draft(); ContractRequest("POST", "/v1/config/revisions/$id/validate", null, w.h(w.aliceToken, 1)) },
            412, { val w = World(); val id = w.draft(); ContractRequest("POST", "/v1/config/revisions/$id/validate", null, w.h(w.aliceToken, 9)) }, server = { IdentityEnv.server }),
        ContractCase("approveConfigRevision",
            200, { val w = World(); val id = w.validated(); ContractRequest("POST", "/v1/config/revisions/$id/approve", null, w.h(w.bobToken, 2)) },
            403, { val w = World(); val id = w.validated(); ContractRequest("POST", "/v1/config/revisions/$id/approve", null, w.h(w.aliceToken, 2)) }, server = { IdentityEnv.server }),
        ContractCase("publishConfig",
            200, { val w = World(); val id = w.approved(); ContractRequest("POST", "/v1/config/revisions/$id/publish", w.publishBody(id, w.report(id, 3)), w.h(w.bobToken, 3)) },
            412, { val w = World(); val id = w.approved(); ContractRequest("POST", "/v1/config/revisions/$id/publish", w.publishBody(id, w.report(id, 3)), w.h(w.bobToken, 2)) }, server = { IdentityEnv.server }),
        ContractCase("rollbackConfigRevision",
            201, { val w = World(); val p1 = w.published(); val d = w.draftAfter(); ContractRequest("POST", "/v1/config/revisions/$d/rollback", """{"effective_from":"${w.from.plusSeconds(3600)}","reason":"Rücknahme"}""", w.h(w.bobToken, 4)) },
            409, { val w = World(); val id = w.draft(); ContractRequest("POST", "/v1/config/revisions/$id/rollback", """{"effective_from":"${w.from}","reason":"Rücknahme"}""", w.h(w.bobToken, 1)) }, server = { IdentityEnv.server }),
    )

    /** Zweite publizierte Revision im selben Scope (die „fehlerhafte“), deren Rücknahme der Vertragsfall prüft; Zeilenversion der publizierten Zeile ist 4. */
    private fun World.draftAfter(): String {
        val id = env.post("/v1/config/revisions",
            """{"scope_kind":"TENANT","scope_id":"$tenant","schema_version":"1","values":{"max_wait_min":200},"effective_from":"${from.plusSeconds(1800)}","reason":"Fehlerhafter Wert"}""", aliceToken, extra = idem()).json["id"].asString()
        val rep = report(id)
        check(send("POST", "/v1/config/revisions/$id/validate", aliceToken, 1).status == 200); check(send("POST", "/v1/config/revisions/$id/approve", bobToken, 2).status == 200)
        val body = """{"effective_from":"${from.plusSeconds(1800)}","reason":"Veröffentlichen","impact_report_id":"$rep"}"""
        check(send("POST", "/v1/config/revisions/$id/publish", bobToken, 3, body).status == 200)
        return id
    }
}

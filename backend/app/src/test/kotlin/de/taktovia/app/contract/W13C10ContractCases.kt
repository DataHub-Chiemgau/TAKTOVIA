package de.taktovia.app.contract

import de.taktovia.app.identity.IdentityEnv
import de.taktovia.platform.storage.ObjectKey
import de.taktovia.platform.storage.TenantObjectStore
import java.util.UUID

/** Vertragsfälle des Clusters wave13-c10 (WP-M5-20, `/v1/expenses/{id}/view`): ein positiver Fall und ein Fehlerfall. */
internal object W13C10ContractCases {
    private val env = IdentityEnv
    private fun idem() = mapOf("Idempotency-Key" to "contract-" + UUID.randomUUID())
    private fun b64(b: ByteArray) = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(b))

    private class World {
        val tenant = env.tenant("Vertrag-W13C10-" + UUID.randomUUID()); val finance = UUID.randomUUID(); val le = UUID.randomUUID()
        init { env.tx.run(tenant) { j -> j.update("INSERT INTO taktovia.legal_entity(tenant_id,id,name,country) VALUES (?,?,'LE','DE')", tenant.value, le) }; env.grant(tenant, finance, "finance") }
        val token = env.token(finance)
        fun document(): String {
            val bytes = ("%PDF-1.4\n${UUID.randomUUID()}\n" + "x".repeat(600)).toByteArray(Charsets.ISO_8859_1)
            val t = env.post("/v1/expenses/receipts", """{"legal_entity_id":"$le","purpose":"RECEIPT","mime":"application/pdf","size_bytes":${bytes.size},"sha256":"${b64(bytes)}"}""", token, extra = idem())
            check(t.status == 201) { t.text }
            env.server.ctx.getBean(TenantObjectStore::class.java).put(ObjectKey(tenant, t.json["upload_key"].asString()), bytes, "application/pdf")
            val d = env.post("/v1/expenses/receipts/${t.json["ticket_id"].asString()}/complete", "", token, extra = idem() + mapOf("If-Match" to "\"1\""))
            check(d.status == 201 && d.json["state"].asString() == "STORED") { d.text }
            return d.json["document_id"].asString()
        }
    }

    val cases: List<ContractCase> = listOf(
        ContractCase("getExpenseDeliveryView",
            200, { val w = World(); ContractRequest("GET", "/v1/expenses/${w.document()}/view", null, env.headers(w.token)) },
            404, { val w = World(); ContractRequest("GET", "/v1/expenses/${UUID.randomUUID()}/view", null, env.headers(w.token)) }, server = { env.server }),
    )
}

package de.taktovia.app.contract

import de.taktovia.app.identity.IdentityEnv
import de.taktovia.platform.storage.ObjectKey
import de.taktovia.platform.storage.TenantObjectStore
import java.util.Base64
import java.util.UUID

/** Vertragsfälle des Clusters wave9-c7 (WP-M4-25 und -26, Belegerfassung per Kamera, Offline-Synchronisation): bearbeitete Ansicht, je Operation ein positiver und ein Fehlerfall. */
internal object C7ContractCases {
    private val env = IdentityEnv
    private fun idem() = mapOf("Idempotency-Key" to "contract-" + UUID.randomUUID())
    private val ifMatch1 = mapOf("If-Match" to "\"1\"")
    private fun jpeg(seed: Int) = ByteArray(1500) { ((it * 31 + seed) and 0xFF).toByte() }.also { it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte(); it[2] = 0xFF.toByte() }
    private fun b64(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(b))
    private fun editedBody(seed: Int) = """{"mime":"image/jpeg","content_base64":"${Base64.getEncoder().encodeToString(jpeg(seed))}"}"""

    private class World {
        val tenant = env.tenant("Vertrag-C7-" + UUID.randomUUID()); val finance = UUID.randomUUID(); val le = UUID.randomUUID()
        init { env.tx.run(tenant) { j -> j.update("INSERT INTO taktovia.legal_entity(tenant_id,id,name,country) VALUES (?,?,'LE','DE')", tenant.value, le) }; env.grant(tenant, finance, "finance") }
        val token = env.token(finance)

        /** Beleg im Zustand STORED über die HTTP-Schnittstelle (idempotente Anlage mit Client-UUID). */
        fun document(): String {
            val bytes = jpeg(UUID.randomUUID().hashCode())
            val t = env.post("/v1/expenses/receipts", """{"legal_entity_id":"$le","purpose":"RECEIPT","mime":"image/jpeg","size_bytes":${bytes.size},"sha256":"${b64(bytes)}","client_uuid":"${UUID.randomUUID()}","captured_at":"2026-01-01T10:00:00Z"}""", token, extra = idem())
            check(t.status == 201) { t.text }
            env.server.ctx.getBean(TenantObjectStore::class.java).put(ObjectKey(tenant, t.json["upload_key"].asString()), bytes, "image/jpeg")
            val d = env.post("/v1/expenses/receipts/${t.json["ticket_id"].asString()}/complete", "", token, extra = idem() + ifMatch1)
            check(d.status == 201 && d.json["state"].asString() == "STORED") { d.text }
            return d.json["document_id"].asString()
        }
    }

    val cases: List<ContractCase> = listOf(
        ContractCase("attachReceiptEditedView",
            200, { val w = World(); ContractRequest("PUT", "/v1/expenses/receipts/${w.document()}/edited-view", editedBody(1), env.headers(w.token, extra = idem() + ifMatch1)) },
            404, { val w = World(); ContractRequest("PUT", "/v1/expenses/receipts/${UUID.randomUUID()}/edited-view", editedBody(2), env.headers(w.token, extra = idem() + ifMatch1)) }, server = { env.server }),
        ContractCase("getReceiptEditedView",
            200, { val w = World(); val d = w.document(); check(env.put("/v1/expenses/receipts/$d/edited-view", editedBody(3), w.token, "\"1\"").status == 200); ContractRequest("GET", "/v1/expenses/receipts/$d/edited-view", null, env.headers(w.token)) },
            404, { val w = World(); ContractRequest("GET", "/v1/expenses/receipts/${w.document()}/edited-view", null, env.headers(w.token)) }, server = { env.server }),
    )
}

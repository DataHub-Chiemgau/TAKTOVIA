package de.taktovia.app.contract

import de.taktovia.app.identity.IdentityEnv
import de.taktovia.platform.storage.ObjectKey
import de.taktovia.platform.storage.TenantObjectStore
import java.util.UUID

/** Vertragsfälle des Clusters wave8-c10 (WP-M4-22 bis -27, Belege): je Operation ein positiver Fall und ein Fehlerfall. */
internal object C10ContractCases {
    private val env = IdentityEnv
    private fun idem() = mapOf("Idempotency-Key" to "contract-" + UUID.randomUUID())
    private val ifMatch1 = mapOf("If-Match" to "\"1\"")

    private fun pdfBytes(tag: String) = ("%PDF-1.4\n$tag\n" + "x".repeat(600)).toByteArray(Charsets.ISO_8859_1)
    private fun b64(b: ByteArray) = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(b))

    private class World {
        val tenant = env.tenant("Vertrag-C10-" + UUID.randomUUID()); val finance = UUID.randomUUID(); val le = UUID.randomUUID()
        init { env.tx.run(tenant) { j -> j.update("INSERT INTO taktovia.legal_entity(tenant_id,id,name,country) VALUES (?,?,'LE','DE')", tenant.value, le) }; env.grant(tenant, finance, "finance") }
        val token = env.token(finance)

        /** Ein geprüftes Beleg-Original (Ticket, Upload, Abschluss) über die HTTP-Schnittstelle. */
        fun document(): String {
            val bytes = pdfBytes(UUID.randomUUID().toString())
            val t = env.post("/v1/expenses/receipts", """{"legal_entity_id":"$le","purpose":"RECEIPT","mime":"application/pdf","size_bytes":${bytes.size},"sha256":"${b64(bytes)}"}""", token, extra = idem())
            check(t.status == 201) { t.text }
            env.server.ctx.getBean(TenantObjectStore::class.java).put(ObjectKey(tenant, t.json["upload_key"].asString()), bytes, "application/pdf")
            val d = env.post("/v1/expenses/receipts/${t.json["ticket_id"].asString()}/complete", "", token, extra = idem() + ifMatch1)
            check(d.status == 201 && d.json["state"].asString() == "STORED") { d.text }
            return d.json["document_id"].asString()
        }
    }

    private fun missing() = UUID.randomUUID()

    val cases: List<ContractCase> = listOf(
        ContractCase("getExpense",
            200, { val w = World(); ContractRequest("GET", "/v1/expenses/${w.document()}", null, env.headers(w.token)) },
            404, { val w = World(); ContractRequest("GET", "/v1/expenses/${missing()}", null, env.headers(w.token)) }, server = { env.server }),
        ContractCase("correctExpense",
            200, { val w = World(); ContractRequest("PATCH", "/v1/expenses/${w.document()}", """{"reason":"Betrag erfasst","fields":{"amount_minor":3900,"currency":"EUR"}}""", env.headers(w.token, extra = idem() + ifMatch1)) },
            404, { val w = World(); ContractRequest("PATCH", "/v1/expenses/${missing()}", """{"reason":"x","fields":{"amount_minor":1}}""", env.headers(w.token, extra = idem() + ifMatch1)) }, server = { env.server }),
        ContractCase("listExpenseRevisions",
            200, { val w = World(); ContractRequest("GET", "/v1/expenses/${w.document()}/revisions", null, env.headers(w.token)) },
            404, { val w = World(); ContractRequest("GET", "/v1/expenses/${missing()}/revisions", null, env.headers(w.token)) }, server = { env.server }),
        ContractCase("getExpenseOriginal",
            200, { val w = World(); ContractRequest("GET", "/v1/expenses/${w.document()}/original", null, env.headers(w.token)) },
            404, { val w = World(); ContractRequest("GET", "/v1/expenses/${missing()}/original", null, env.headers(w.token)) }, server = { env.server }),
        ContractCase("requestReceiptExtraction",
            202, { val w = World(); ContractRequest("POST", "/v1/expenses/receipts/${w.document()}/extract", null, env.headers(w.token, extra = idem() + ifMatch1)) },
            404, { val w = World(); ContractRequest("POST", "/v1/expenses/receipts/${missing()}/extract", null, env.headers(w.token, extra = idem() + ifMatch1)) }, server = { env.server }),
        ContractCase("getReceiptExtraction",
            200, { val w = World(); val d = w.document(); check(env.post("/v1/expenses/receipts/$d/extract", "", w.token, extra = idem() + ifMatch1).status == 202); ContractRequest("GET", "/v1/expenses/receipts/$d/extraction", null, env.headers(w.token)) },
            404, { val w = World(); ContractRequest("GET", "/v1/expenses/receipts/${missing()}/extraction", null, env.headers(w.token)) }, server = { env.server }),
    )
}

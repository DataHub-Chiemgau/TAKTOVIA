package de.taktovia.app.contract

import de.taktovia.app.identity.IdentityEnv
import de.taktovia.platform.storage.ObjectKey
import de.taktovia.platform.storage.TenantObjectStore
import java.util.UUID

/** Vertragsfälle des Clusters wave10-c5 (WP-M4-31, -32, -34: Tankplausibilität, Dubletten, Freigabe): je Operation ein positiver Fall und ein Fehlerfall. */
internal object W10C5ContractCases {
    private val env = IdentityEnv
    private fun idem() = mapOf("Idempotency-Key" to "contract-" + UUID.randomUUID())
    private fun im(rev: Long) = mapOf("If-Match" to "\"$rev\"")
    private fun b64(b: ByteArray) = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(b))

    /** Mandant mit Rechtsträger, Finanzrolle und einer Tankschwelle von 80 l. */
    class World {
        val tenant = env.tenant("Vertrag-W10C5-" + UUID.randomUUID()); val finance = UUID.randomUUID(); val le = UUID.randomUUID(); val vehicle = UUID.randomUUID()
        init {
            env.tx.run(tenant) { j ->
                j.update("INSERT INTO taktovia.legal_entity(tenant_id,id,name,country) VALUES (?,?,'LE','DE')", tenant.value, le)
                j.update("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (?,?,'org',CAST('{}' AS jsonb))", tenant.value, vehicle)
                j.update("INSERT INTO taktovia.finance_policy(tenant_id,updated_by,fuel_max_liters) VALUES (?,'contract',80)", tenant.value)
            }
            env.grant(tenant, finance, "finance")
        }
        val token = env.token(finance)

        /** Beleg im Zustand STORED (Art je nach `kind`); Revision 1. */
        fun fuelDocument(bytes: ByteArray = ("%PDF-1.4\n${UUID.randomUUID()}\n" + "x".repeat(600)).toByteArray(Charsets.ISO_8859_1), kind: String = "FUEL_RECEIPT"): String {
            val t = env.post("/v1/expenses/receipts", """{"legal_entity_id":"$le","purpose":"RECEIPT","mime":"application/pdf","size_bytes":${bytes.size},"sha256":"${b64(bytes)}","document_kind":"$kind"}""", token, extra = idem())
            check(t.status == 201) { t.text }
            env.server.ctx.getBean(TenantObjectStore::class.java).put(ObjectKey(tenant, t.json["upload_key"].asString()), bytes, "application/pdf")
            val d = env.post("/v1/expenses/receipts/${t.json["ticket_id"].asString()}/complete", "", token, extra = idem() + im(1))
            check(d.status == 201 && d.json["state"].asString() == "STORED") { d.text }
            return d.json["document_id"].asString()
        }

        /** Tankbeleg mit einem offenen Hinweis (Volumen 85 l über 80 l); liefert Beleg, Hinweis und Revision. */
        fun documentWithHint(): Triple<String, String, Long> {
            val doc = fuelDocument()
            val p = env.server.send("PATCH", "/v1/expenses/$doc", """{"fuel":{"vehicle_id":"$vehicle","fuel_type":"DIESEL","quantity":"85","unit_price":"1.7","odometer_km":1000}}""", env.headers(token, extra = idem() + im(1)))
            check(p.status == 200) { p.text }
            return Triple(doc, p.json["hints"][0]["id"].asString(), p.json["revision"].asLong())
        }
    }

    /** Zwei Belege mit gleichem Händler, Datum und gleicher Belegnummer, aber anderem Betrag: möglicher Treffer am neueren Beleg; liefert Beleg, Treffer und Revision. */
    fun World.documentWithDuplicate(): Triple<String, String, Long> {
        fun fields(doc: String, amount: Long): Long {
            val p = env.server.send("PATCH", "/v1/expenses/$doc", """{"reason":"erfasst","fields":{"amount_minor":$amount,"currency":"EUR","document_date":"2026-06-15","merchant":"Tankstelle Nord","document_number":"R-1001"}}""", env.headers(token, extra = idem() + im(1)))
            check(p.status == 200) { p.text }
            return p.json["revision"].asLong()
        }
        val first = fuelDocument(kind = "RECEIPT"); fields(first, 11900)
        val second = fuelDocument(kind = "RECEIPT"); val rev = fields(second, 11800)
        val e = env.get("/v1/expenses/$second", token).json
        check(e["duplicates"].size() == 1 && e["duplicates"][0]["state"].asString() == "OFFEN") { e.toString() }
        return Triple(second, e["duplicates"][0]["id"].asString(), rev)
    }

    val cases: List<ContractCase> = listOf(
        ContractCase("decideExpenseDuplicate",
            200, { val w = World(); val (doc, c, rev) = w.documentWithDuplicate(); ContractRequest("POST", "/v1/expenses/$doc/duplicates/$c/decision", """{"decision":"EIGENER_VORGANG","reason":"Zweite Tankung am selben Tag"}""", env.headers(w.token, extra = idem() + im(rev))) },
            422, { val w = World(); val (doc, c, rev) = w.documentWithDuplicate(); ContractRequest("POST", "/v1/expenses/$doc/duplicates/$c/decision", """{"decision":"EIGENER_VORGANG","reason":"ab"}""", env.headers(w.token, extra = idem() + im(rev))) }, server = { env.server }),
        ContractCase("acknowledgeExpenseHint",
            200, { val w = World(); val (doc, hint, rev) = w.documentWithHint(); ContractRequest("POST", "/v1/expenses/$doc/hints/$hint/acknowledgement", """{"reason":"Großtank, Sondertour"}""", env.headers(w.token, extra = idem() + im(rev))) },
            422, { val w = World(); val (doc, hint, rev) = w.documentWithHint(); ContractRequest("POST", "/v1/expenses/$doc/hints/$hint/acknowledgement", """{"reason":"ab"}""", env.headers(w.token, extra = idem() + im(rev))) }, server = { env.server }),
    )
}

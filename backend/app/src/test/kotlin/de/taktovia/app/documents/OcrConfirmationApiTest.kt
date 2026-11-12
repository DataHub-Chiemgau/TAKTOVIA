package de.taktovia.app.documents

import de.taktovia.app.identity.IdentityEnv
import de.taktovia.platform.storage.ObjectKey
import de.taktovia.platform.storage.TenantObjectStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/**
 * WP-M4-28 (DOC-003, E2E-066) über die echte Anwendung (HTTP, Runtime-Rolle, Test-Issuer, lokaler Textebenen-Adapter mit erzeugtem PDF): OCR-Vorschläge sind unbestätigt und
 * blockieren die Freigabefähigkeit (`approvable`, `unconfirmed_fields`), eine Summenabweichung wird am Vorschlag markiert (`flag_reason`), `PATCH` verlangt `If-Match` (428/412) und das
 * Schreibrecht (403), fremde Mandanten sehen nichts (404). Die Freigabe selbst (`approveExpense`, WP-M4-34) ist nicht gebaut; ihre Prüfung ist `ReceiptReview` (siehe `OcrConfirmationTest`).
 */
class OcrConfirmationApiTest {
    private val env = IdentityEnv
    private val store: TenantObjectStore get() = env.server.ctx.getBean(TenantObjectStore::class.java)
    private fun tools.jackson.databind.JsonNode.items() = (0 until size()).map { get(it) }
    private fun idem() = mapOf("Idempotency-Key" to "k-" + UUID.randomUUID())
    private fun patch(path: String, body: String, token: String, ifMatch: String?) = env.server.send("PATCH", path, body, env.headers(token, extra = idem() + (ifMatch?.let { mapOf("If-Match" to it) } ?: emptyMap())))

    private class World(val env: IdentityEnv) {
        val tenant = env.tenant("Beleg28-" + UUID.randomUUID()); val le = UUID.randomUUID(); val finance = UUID.randomUUID(); val nobody = UUID.randomUUID()
        init { env.grant(tenant, finance, "finance"); env.tx.run(tenant) { j -> j.update("INSERT INTO taktovia.legal_entity(tenant_id,id,name,country) VALUES (?,?,'LE','DE')", tenant.value, le) } }
        val financeToken = env.token(finance)
    }

    private fun pdf(vararg lines: String): ByteArray {
        val body = buildString {
            append("BT /F1 10 Tf 12 TL 50 780 Td\n")
            lines.forEachIndexed { i, l -> if (i > 0) append("T*\n"); append("(${l.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")}) Tj\n") }
            append("ET\n")
        }.toByteArray(Charsets.ISO_8859_1)
        return "%PDF-1.4\n1 0 obj\n<< /Length ${body.size} >>\nstream\n".toByteArray(Charsets.ISO_8859_1) + body + "\nendstream\nendobj\n%%EOF\n".toByteArray(Charsets.ISO_8859_1)
    }

    private fun upload(w: World, bytes: ByteArray): String {
        val sha = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))
        val t = env.post("/v1/expenses/receipts", """{"legal_entity_id":"${w.le}","purpose":"RECEIPT","mime":"application/pdf","size_bytes":${bytes.size},"sha256":"$sha"}""", w.financeToken, extra = idem())
        check(t.status == 201) { t.text }
        store.put(ObjectKey(w.tenant, t.json["upload_key"].asString()), bytes, "application/pdf")
        val d = env.post("/v1/expenses/receipts/${t.json["ticket_id"].asString()}/complete", "", w.financeToken, extra = idem() + mapOf("If-Match" to "\"1\""))
        check(d.status == 201 && d.json["state"].asString() == "STORED") { d.text }
        return d.json["document_id"].asString()
    }

    private fun extract(w: World, doc: String): tools.jackson.databind.JsonNode {
        assertEquals(202, env.post("/v1/expenses/receipts/$doc/extract", "", w.financeToken, extra = idem() + mapOf("If-Match" to "\"1\"")).status)
        repeat(100) {
            val r = env.get("/v1/expenses/receipts/$doc/extraction", w.financeToken)
            if (r.status == 200 && r.json["run"]["status"].asString() == "COMPLETED") return r.json
            Thread.sleep(100)
        }
        throw AssertionError("Extraktion nicht abgeschlossen")
    }

    @Test
    fun ocr_vorschlag_ist_unbestaetigt_summenabweichung_markiert_und_blockiert() {
        val w = World(env)
        val doc = upload(w, pdf("Werkstatt Sued", "Datum 01.02.2026", "Netto 70,00", "MwSt 19% 13,30", "Gesamtbetrag EUR 89,00"))
        val run = extract(w, doc)
        val byKind = run["proposals"].items().filter { !it["normalized_value"].isNull }.associateBy { it["field"].asString() }
        assertEquals("8900", byKind.getValue("AMOUNT")["normalized_value"].asString()); assertEquals("7000", byKind.getValue("NET_AMOUNT")["normalized_value"].asString())
        for (k in listOf("AMOUNT", "NET_AMOUNT", "VAT_AMOUNT")) assertEquals("SUM_MISMATCH", byKind.getValue(k)["flag_reason"].asString(), k)
        assertTrue(byKind.getValue("DOCUMENT_DATE")["flag_reason"].isNull)
        // unbestätigt: kein Belegstand, nicht freigabefähig
        val e = env.get("/v1/expenses/$doc", w.financeToken)
        assertEquals(0, e.json["fields"].size()); assertFalse(e.json["approvable"].asBoolean()); assertTrue("UNCONFIRMED_FIELDS" in e.json["blockers"].items().map { it.asString() })
        assertEquals(listOf("amount_minor", "currency", "document_date", "merchant", "vat_amounts", "net_amount_minor"), e.json["unconfirmed_fields"].items().map { it.asString() })
        // ohne If-Match 428, veraltet 412
        assertEquals(428, patch("/v1/expenses/$doc", """{"reason":"x","confirm":["merchant"]}""", w.financeToken, null).status)
        assertEquals(412, patch("/v1/expenses/$doc", """{"reason":"x","confirm":["merchant"]}""", w.financeToken, "\"7\"").status)
        // alles bestätigen: die Summe stimmt nicht, der Beleg bleibt blockiert; Korrektur auf 83,30 löst es
        val all = patch("/v1/expenses/$doc", """{"reason":"geprüft","confirm":["amount_minor","currency","document_date","merchant","vat_amounts","net_amount_minor"]}""", w.financeToken, "\"1\"")
        assertEquals(200, all.status, all.text); assertTrue(all.json["sum_mismatch"].asBoolean()); assertFalse(all.json["approvable"].asBoolean()); assertEquals(listOf("SUM_MISMATCH"), all.json["blockers"].items().map { it.asString() })
        val fixed = patch("/v1/expenses/$doc", """{"reason":"Brutto war 83,30","fields":{"amount_minor":8330}}""", w.financeToken, "\"2\"")
        assertEquals(200, fixed.status, fixed.text); assertFalse(fixed.json["sum_mismatch"].asBoolean()); assertTrue(fixed.json["approvable"].asBoolean()); assertEquals(0, fixed.json["unconfirmed_fields"].size())
        // der OCR-Vorschlag 89,00 bleibt abrufbar und unverändert markiert
        val after = env.get("/v1/expenses/receipts/$doc/extraction", w.financeToken).json
        assertEquals("8900", after["proposals"].items().single { it["field"].asString() == "AMOUNT" }["normalized_value"].asString())
        val cur = after["current"].items().single { it["field"].asString() == "AMOUNT" }
        assertEquals("CORRECTED", cur["status"].asString()); assertTrue(cur["flag_reason"].isNull, "die menschliche Entscheidung trägt keine Markierung")
    }

    @Test
    fun ohne_schreibrecht_403_fremder_mandant_404() {
        val w = World(env); val other = World(env)
        val doc = upload(w, pdf("Kiosk", "Summe 5,00 EUR"))
        val nobody = env.token(w.nobody)   // kein Mitglied: kein Belegrecht
        val denied = patch("/v1/expenses/$doc", """{"reason":"x","confirm":["merchant"]}""", nobody, "\"1\"")
        assertTrue(denied.status == 403 || denied.status == 401, "Status ${denied.status}")
        assertEquals(404, patch("/v1/expenses/$doc", """{"reason":"x","fields":{"amount_minor":2}}""", other.financeToken, "\"1\"").status)
        assertEquals(404, env.get("/v1/expenses/$doc", other.financeToken).status)
    }
}

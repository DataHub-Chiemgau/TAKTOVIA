package de.taktovia.app.documents

import de.taktovia.app.contract.W11C5ContractCases
import de.taktovia.app.identity.IdentityEnv
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.UUID

/**
 * WP-M4-37 über die echte Anwendung (E2E-068, Exportteil): `POST /v1/ledger/exports` als Job (202, Operation-Runner), Wiederholung (200, derselbe Batch), Idempotenz, Lesen, Journale, kurzlebiger Download mit
 * Signatur und Mandantenbindung, Bestätigungsprotokoll, 401/403/404/409/422 und der Exportstatus am Beleg.
 */
class LedgerExportApiTest {
    private val env = IdentityEnv
    private fun idem(key: String = "k-" + UUID.randomUUID()) = mapOf("Idempotency-Key" to key)
    private fun body(le: UUID, from: String = "2026-06-01", to: String = "2026-07-01") = """{"legal_entity_id":"$le","period_from":"$from","period_to":"$to"}"""
    private fun post(w: W11C5ContractCases.World, path: String, json: String, key: String? = null, token: String = w.allocatorToken) = env.server.send("POST", path, json, env.headers(token, extra = idem(key ?: "k-" + UUID.randomUUID())))
    private fun get(path: String, token: String) = env.server.send("GET", path, null, env.headers(token))
    private fun run(w: W11C5ContractCases.World) { with(W11C5ContractCases) { w.runJobs() } }
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Test
    fun auftrag_wiederholung_download_und_bestaetigung_mit_exportstatus_am_beleg() {
        val w = W11C5ContractCases.World(); val event = w.postedEvent(); val doc = env.tx.run(w.tenant) { j -> j.queryForObject("SELECT document_id::text FROM taktovia.economic_event WHERE id = CAST(? AS uuid)", String::class.java, event)!! }
        assertEquals("NOT_EXPORTED", get("/v1/expenses/$doc", w.finance).json["export_state"].asString())
        val key = "export-" + UUID.randomUUID()
        val first = post(w, "/v1/ledger/exports", body(w.base.le), key)
        assertEquals(202, first.status, first.text); assertEquals("REQUESTED", first.json["state"].asString()); assertTrue(first.header("Location")!!.startsWith("/v1/operations/")); assertTrue(first.json["batch_ref"].isNull)
        // gleicher Idempotency-Key: gespeicherte Antwort; anderer Schlüssel, gleicher Umfang: derselbe offene Auftrag (200)
        assertEquals(202, post(w, "/v1/ledger/exports", body(w.base.le), key).status)
        val same = post(w, "/v1/ledger/exports", body(w.base.le)); assertEquals(200, same.status); assertEquals(first.json["id"].asString(), same.json["id"].asString())
        val id = first.json["id"].asString()
        run(w)
        val done = get("/v1/ledger/exports/$id", w.allocatorToken).json
        assertEquals("PROVIDED", done["state"].asString()); assertEquals(1L, done["journal_count"].asLong()); assertEquals("taktovia-neutral-json", done["format"].asString()); assertTrue(done["batch_ref"].asString().startsWith("EXP-"))
        assertEquals("EXPORTED", get("/v1/expenses/$doc", w.finance).json["export_state"].asString(), "bereitgestellt")
        // Wiederholung: derselbe Batch (200) mit denselben Kennungen und demselben Hash
        val again = post(w, "/v1/ledger/exports", body(w.base.le)); assertEquals(200, again.status); assertEquals(done["id"].asString(), again.json["id"].asString()); assertEquals(done["content_sha256"].asString(), again.json["content_sha256"].asString()); assertEquals(done["batch_ref"].asString(), again.json["batch_ref"].asString())
        assertEquals(1, get("/v1/ledger/exports?limit=10", w.allocatorToken).json["items"].size())
        // Download: kurzlebige signierte Adresse, Inhalt mit Hash, manipulierte und fremde Adresse sind 404
        val link = get("/v1/ledger/exports/$id/download", w.allocatorToken); assertEquals(200, link.status, link.text)
        val content = env.server.send("GET", link.json["url"].asString(), null, env.headers(w.allocatorToken)); assertEquals(200, content.status)
        assertEquals(done["content_sha256"].asString(), content.header("X-Content-SHA256")); assertEquals(done["content_sha256"].asString(), sha(content.text.toByteArray(Charsets.UTF_8)))
        assertTrue(content.json["journals"][0]["lines"].size() >= 2); assertEquals(done["batch_ref"].asString(), content.json["batch_ref"].asString())
        val url = link.json["url"].asString()
        assertEquals(404, env.server.send("GET", url.dropLast(3) + "000", null, env.headers(w.allocatorToken)).status)
        val other = W11C5ContractCases.World(); assertEquals(404, env.server.send("GET", url, null, env.headers(other.allocatorToken)).status, "Adresse eines anderen Mandanten")
        assertEquals(404, get("/v1/ledger/exports/$id", other.allocatorToken).status)
        // Bestätigungsprotokoll
        val lines = get("/v1/ledger/exports/$id/lines", w.allocatorToken).json["items"]; assertEquals(1, lines.size()); val journal = lines[0]["journal_id"].asString()
        assertEquals(422, post(w, "/v1/ledger/exports/$id/confirmation", """{"entries":[]}""").status)
        assertEquals(422, post(w, "/v1/ledger/exports/$id/confirmation", """{"entries":[{"journal_id":"${UUID.randomUUID()}","import_ref":"ERP-1"}]}""").status)
        val ok = post(w, "/v1/ledger/exports/$id/confirmation", """{"entries":[{"journal_id":"$journal","import_ref":"ERP-4711"}]}"""); assertEquals(200, ok.status, ok.text); assertEquals("CONFIRMED", ok.json["state"].asString())
        assertEquals("ERP-4711", get("/v1/ledger/exports/$id/lines", w.allocatorToken).json["items"][0]["import_ref"].asString()); assertEquals("CONFIRMED", get("/v1/expenses/$doc", w.finance).json["export_state"].asString())
        assertEquals(409, post(w, "/v1/ledger/exports/$id/confirmation", """{"entries":[{"journal_id":"$journal","import_ref":"ERP-0815"}]}""").status)
        assertEquals(listOf("finance.ledger_export.confirmed", "finance.ledger_export.created", "finance.ledger_export.downloaded", "finance.ledger_export.provided"),
            env.tx.run(w.tenant) { j -> j.queryForObject("SELECT string_agg(DISTINCT action, ',' ORDER BY action) FROM taktovia.audit_event WHERE action LIKE 'finance.ledger_export.%'", String::class.java)!! }.split(','), "Audit: angefordert, bereitgestellt, heruntergeladen, bestätigt")
    }

    @Test
    fun recht_fremde_objekte_leerer_zeitraum_und_eingaben() {
        val w = W11C5ContractCases.World(); w.postedEvent()
        assertEquals(401, env.server.send("POST", "/v1/ledger/exports", body(w.base.le), idem()).status)
        val driver = UUID.randomUUID(); env.grant(w.tenant, driver, "driver")
        assertEquals(403, post(w, "/v1/ledger/exports", body(w.base.le), token = env.token(driver)).status); assertEquals(403, get("/v1/ledger/exports", env.token(driver)).status)
        assertEquals(0, env.tx.run(w.tenant) { j -> j.queryForObject("SELECT count(*) FROM taktovia.ledger_export", Int::class.java)!! }, "ohne Recht entsteht kein Auftrag")
        assertEquals(404, post(w, "/v1/ledger/exports", body(UUID.randomUUID())).status)
        val other = W11C5ContractCases.World(); assertEquals(404, post(w, "/v1/ledger/exports", body(other.base.le)).status, "Rechtsträger eines anderen Mandanten")
        val empty = post(w, "/v1/ledger/exports", body(w.base.le, "2027-05-01", "2027-06-01")); assertEquals(409, empty.status); assertEquals("CONFLICT", empty.json["code"].asString())
        assertEquals(422, post(w, "/v1/ledger/exports", body(w.base.le, "2026-07-01", "2026-06-01")).status)
        assertEquals(422, post(w, "/v1/ledger/exports", """{"legal_entity_id":"${w.base.le}","period_from":"2026-06-01","period_to":"2026-07-01","target_system":"DATEV"}""").status)
        assertEquals(422, post(w, "/v1/ledger/exports", """{"legal_entity_id":"${w.base.le}","period_from":"gestern","period_to":"2026-07-01"}""").status)
        assertEquals(404, get("/v1/ledger/exports/${UUID.randomUUID()}", w.allocatorToken).status)
        // noch nicht bereitgestellt: kein Download, keine Bestätigung
        val q = post(w, "/v1/ledger/exports", body(w.base.le)).json["id"].asString()
        assertEquals(409, get("/v1/ledger/exports/$q/download", w.allocatorToken).status)
        assertEquals(409, post(w, "/v1/ledger/exports/$q/confirmation", """{"entries":[{"journal_id":"${UUID.randomUUID()}","import_ref":"ERP-1"}]}""").status)
    }
}

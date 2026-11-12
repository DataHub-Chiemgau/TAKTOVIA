package de.taktovia.app.documents

import de.taktovia.app.identity.IdentityEnv
import de.taktovia.platform.storage.ObjectKey
import de.taktovia.platform.storage.TenantObjectStore
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.zip.Deflater

/**
 * WP-M4-22 bis -27 über die echte Anwendung (HTTP, Runtime-Rolle, Test-Issuer, lokaler Textebenen-Adapter `pdf-text` mit **erzeugten** PDF-Belegen): Beleg lesen und korrigieren mit
 * `If-Match` (428/412), Revisionen, Original byteweise, Integritätsvorfall bei Manipulation, Extraktion mit echter Datei, Bestätigung und Korrektur einzelner Felder (89,00 statt 39,00),
 * Mandantentrennung, Kanäle.
 */
class ExpenseApiTest {
    private val env = IdentityEnv
    private val store: TenantObjectStore get() = env.server.ctx.getBean(TenantObjectStore::class.java)
    private fun tools.jackson.databind.JsonNode.items(): List<tools.jackson.databind.JsonNode> = (0 until size()).map { get(it) }
    private fun idem() = mapOf("Idempotency-Key" to "k-" + UUID.randomUUID())
    private fun im(rev: Long) = mapOf("If-Match" to "\"$rev\"")

    private class World(val env: IdentityEnv) {
        val tenant = env.tenant("Beleg10-" + UUID.randomUUID()); val le = UUID.randomUUID()
        val driver = UUID.randomUUID(); val finance = UUID.randomUUID()
        init {
            env.grant(tenant, driver, "driver"); env.grant(tenant, finance, "finance")
            env.tx.run(tenant) { j -> j.update("INSERT INTO taktovia.legal_entity(tenant_id,id,name,country) VALUES (?,?,'LE','DE')", tenant.value, le) }
        }
        val driverToken = env.token(driver); val financeToken = env.token(finance)
    }

    private fun b64(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(b))

    /** Textbasiertes PDF (ein Textobjekt, eine Zeile je `T*`), optional Flate; Zeilen wie bei einem digitalen Kassenbon. */
    private fun receiptPdf(vararg lines: String, flate: Boolean = false): ByteArray {
        val body = buildString {
            append("BT /F1 10 Tf 12 TL 50 780 Td\n")
            lines.forEachIndexed { i, l -> if (i > 0) append("T*\n"); append("(${l.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")}) Tj\n") }
            append("ET\n")
        }.toByteArray(Charsets.ISO_8859_1)
        val data = if (flate) ByteArrayOutputStream().also { o -> val d = Deflater(); d.setInput(body); d.finish(); val b = ByteArray(4096); while (!d.finished()) o.write(b, 0, d.deflate(b)); d.end() }.toByteArray() else body
        return "%PDF-1.4\n1 0 obj\n<< /Length ${data.size}${if (flate) " /Filter /FlateDecode" else ""} >>\nstream\n".toByteArray(Charsets.ISO_8859_1) + data + "\nendstream\nendobj\n%%EOF\n".toByteArray(Charsets.ISO_8859_1)
    }

    private fun jpeg(seed: Int = 1) = ByteArray(2048) { ((it * 31 + seed) and 0xFF).toByte() }.also { it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte(); it[2] = 0xFF.toByte() }

    private fun upload(w: World, token: String, bytes: ByteArray, mime: String, extra: String = ""): String {
        val t = env.post("/v1/expenses/receipts", """{"legal_entity_id":"${w.le}","purpose":"RECEIPT","mime":"$mime","size_bytes":${bytes.size},"sha256":"${b64(bytes)}"$extra}""", token, extra = idem())
        check(t.status == 201) { t.text }
        store.put(ObjectKey(w.tenant, t.json["upload_key"].asString()), bytes, mime)
        val d = env.post("/v1/expenses/receipts/${t.json["ticket_id"].asString()}/complete", "", token, extra = idem() + im(1))
        check(d.status == 201 && d.json["state"].asString() == "STORED") { d.text }
        return d.json["document_id"].asString()
    }

    private fun patch(path: String, body: String, token: String, ifMatch: String?) = env.server.send("PATCH", path, body, env.headers(token, extra = idem() + (ifMatch?.let { mapOf("If-Match" to it) } ?: emptyMap())))

    private fun awaitRun(w: World, doc: String, token: String): tools.jackson.databind.JsonNode {
        repeat(100) {
            val r = env.get("/v1/expenses/receipts/$doc/extraction", token)
            if (r.status == 200 && r.json["run"]["status"].asString().let { it == "COMPLETED" || it == "FAILED" }) return r.json
            Thread.sleep(100)
        }
        throw AssertionError("Extraktion nicht abgeschlossen")
    }

    @Test
    fun beleg_lesen_korrigieren_mit_if_match_revisionen_und_original() {
        val w = World(env); val bytes = receiptPdf("Tankstelle Nord", "Summe EUR 89,00")
        val doc = upload(w, w.driverToken, bytes, "application/pdf", ""","source_channel":"DRIVER_APP","document_kind":"FUEL_RECEIPT"""")
        val e = env.get("/v1/expenses/$doc", w.driverToken)
        assertEquals(200, e.status, e.text); assertEquals("\"1\"", e.header("ETag"))
        assertEquals("DRIVER_APP", e.json["source_channel"].asString()); assertEquals("FUEL_RECEIPT", e.json["document_kind"].asString()); assertEquals("STORED", e.json["state"].asString())
        assertFalse(e.json["approvable"].asBoolean(), "ohne bestätigte Pflichtfelder nicht freigabefähig (WP-M4-28)"); assertEquals(listOf("UNCONFIRMED_FIELDS"), e.json["blockers"].items().map { it.asString() })
        assertEquals(listOf("amount_minor", "currency", "document_date", "merchant"), e.json["unconfirmed_fields"].items().map { it.asString() }); assertEquals(0, e.json["fields"].size()); assertTrue(e.json["case_ref"].isNull); assertEquals("UNCHECKED", e.json["integrity"].asString())
        // ohne If-Match 428, veralteter ETag 412, unbekanntes Feld 422
        assertEquals(428, patch("/v1/expenses/$doc", """{"reason":"x","fields":{"amount_minor":1}}""", w.financeToken, null).status)
        val first = patch("/v1/expenses/$doc", """{"reason":"Betrag erfasst","fields":{"amount_minor":8900,"currency":"EUR"}}""", w.financeToken, "\"1\"")
        assertEquals(200, first.status, first.text); assertEquals("\"2\"", first.header("ETag")); assertEquals(8900, first.json["fields"]["amount_minor"].asInt())
        assertEquals(412, patch("/v1/expenses/$doc", """{"reason":"alt","fields":{"amount_minor":1}}""", w.financeToken, "\"1\"").status)
        assertEquals(422, patch("/v1/expenses/$doc", """{"reason":"x","fields":{"state":"APPROVED"}}""", w.financeToken, "\"2\"").status, "unbekanntes Feld (Zustand lässt sich nicht setzen)")
        assertEquals(422, patch("/v1/expenses/$doc", """{"reason":"x","fields":{"amount_minor":8900}}""", w.financeToken, "\"2\"").status, "Wert entspricht dem aktuellen Stand")
        assertEquals(422, patch("/v1/expenses/$doc", """{"reason":"x","fields":{"amount_minor":-1}}""", w.financeToken, "\"2\"").status)
        val second = patch("/v1/expenses/$doc", """{"reason":"Lesefehler 89,00 statt 39,00","fields":{"amount_minor":3900}}""", w.financeToken, "\"2\"")
        assertEquals(200, second.status, second.text); assertEquals(3900, second.json["fields"]["amount_minor"].asInt())
        // alle Versionen mit Bearbeiter, Zeitpunkt, Alt- und Neuwert
        val revs = env.get("/v1/expenses/$doc/revisions", w.financeToken).json["items"]
        assertEquals(3, revs.size()); assertEquals(listOf(1, 2, 3), revs.items().map { it["revision"].asInt() })
        assertEquals(8900, revs[2]["fields"]["amount_minor"]["old"].asInt()); assertEquals(3900, revs[2]["fields"]["amount_minor"]["new"].asInt()); assertEquals(w.finance.toString(), revs[2]["edited_by"].asString())
        // Original byteweise unverändert, auch nach den Korrekturen
        val o = env.get("/v1/expenses/$doc/original", w.financeToken)
        assertEquals(200, o.status); assertTrue(o.headers["Content-Type"]!!.first().startsWith("application/pdf")); assertArrayEquals(bytes, o.text.toByteArray(Charsets.ISO_8859_1))
        // der Fahrer als Erfasser darf ebenfalls lesen, ein anderer Fahrer und ein anderer Mandant nicht
        assertEquals(200, env.get("/v1/expenses/$doc", w.driverToken).status)
        val other = World(env)
        assertEquals(404, env.get("/v1/expenses/$doc", other.financeToken).status); assertEquals(404, env.get("/v1/expenses/$doc/original", other.financeToken).status)
        assertEquals(404, env.get("/v1/expenses/$doc/revisions", other.financeToken).status); assertEquals(404, patch("/v1/expenses/$doc", """{"reason":"x","fields":{"amount_minor":2}}""", other.financeToken, "\"3\"").status)
    }

    @Test
    fun extraktion_mit_echtem_pdf_bestaetigen_und_korrigieren() {
        val w = World(env)
        val bytes = receiptPdf("REWE Markt GmbH", "Datum: 14.03.2026", "Bon-Nr. 4711/0032", "Brot 2,49", "Milch 1,29", "SUMME EUR 89,00", "MwSt 7% 5,82", flate = true)
        val doc = upload(w, w.financeToken, bytes, "application/pdf")
        assertEquals(404, env.get("/v1/expenses/receipts/$doc/extraction", w.financeToken).status, "noch kein Lauf")
        val start = env.post("/v1/expenses/receipts/$doc/extract", "", w.financeToken, extra = idem() + im(1))
        assertEquals(202, start.status, start.text); assertEquals("/v1/expenses/receipts/$doc/extraction", start.header("Location")); assertEquals("pdf-text-layer", start.json["engine"].asString())
        assertEquals(412, env.post("/v1/expenses/receipts/$doc/extract", "", w.financeToken, extra = idem() + im(9)).status)
        assertEquals(428, env.post("/v1/expenses/receipts/$doc/extract", "", w.financeToken, extra = idem()).status)
        val result = awaitRun(w, doc, w.financeToken)
        assertEquals("COMPLETED", result["run"]["status"].asString())
        val byKind = result["proposals"].items().associateBy { it["field"].asString() + it["occurrence"].asInt() }
        assertEquals("8900", byKind.getValue("AMOUNT0")["normalized_value"].asString()); assertEquals("2026-03-14", byKind.getValue("DOCUMENT_DATE0")["normalized_value"].asString())
        assertEquals("REWE Markt GmbH", byKind.getValue("MERCHANT0")["normalized_value"].asString()); assertEquals("4711/0032", byKind.getValue("DOCUMENT_NUMBER0")["normalized_value"].asString())
        assertEquals("EUR", byKind.getValue("CURRENCY0")["normalized_value"].asString()); assertEquals("7", byKind.getValue("VAT_AMOUNT0")["qualifier"].asString()); assertEquals("582", byKind.getValue("VAT_AMOUNT0")["normalized_value"].asString())
        for (p in result["proposals"].items().filter { !it["normalized_value"].isNull }) { assertTrue(p["confidence"].asDouble() > 0.8); assertEquals(1, p["source"]["page"].asInt()); assertEquals("PROPOSED", p["status"].asString()) }
        // unbestätigt: nichts ist Belegstand
        assertEquals(0, env.get("/v1/expenses/$doc", w.financeToken).json["fields"].size())
        // einzeln bestätigen und korrigieren: 89,00 statt 39,00
        val ok = patch("/v1/expenses/$doc", """{"reason":"geprüft","confirm":["merchant","currency","document_date"],"fields":{"amount_minor":3900}}""", w.financeToken, "\"1\"")
        assertEquals(200, ok.status, ok.text); assertEquals(3900, ok.json["fields"]["amount_minor"].asInt()); assertEquals("REWE Markt GmbH", ok.json["fields"]["merchant"].asString()); assertEquals("2026-03-14", ok.json["fields"]["document_date"].asString())
        val after = env.get("/v1/expenses/receipts/$doc/extraction", w.financeToken).json
        val amount = after["current"].items().single { it["field"].asString() == "AMOUNT" }
        assertEquals("CORRECTED", amount["status"].asString()); assertEquals("3900", amount["normalized_value"].asString()); assertEquals(byKind.getValue("AMOUNT0")["id"].asString(), amount["corrects_field_id"].asString())
        assertEquals("8900", after["proposals"].items().single { it["field"].asString() == "AMOUNT" }["normalized_value"].asString(), "der OCR-Vorschlag 89,00 bleibt erhalten")
        assertEquals("CONFIRMED", after["current"].items().single { it["field"].asString() == "MERCHANT" }["status"].asString()); assertEquals("PROPOSED", after["current"].items().single { it["field"].asString() == "DOCUMENT_NUMBER" }["status"].asString())
        val rev = env.get("/v1/expenses/$doc/revisions", w.financeToken).json["items"][1]["fields"]
        assertEquals(8900, rev["amount_minor"]["ocr_proposal"].asInt()); assertTrue(rev["merchant"]["confirmed"].asBoolean())
        // ein zweiter Anstoß liefert denselben Lauf (kein Doppellauf)
        assertEquals(start.json["run_id"].asString(), env.post("/v1/expenses/receipts/$doc/extract", "", w.financeToken, extra = idem() + im(2)).json["run_id"].asString())
    }

    @Test
    fun rasterbild_ist_nicht_lesbar_felder_bleiben_leer_und_quarantaene_wird_nicht_extrahiert() {
        val w = World(env); val doc = upload(w, w.financeToken, jpeg(5), "image/jpeg")
        assertEquals(202, env.post("/v1/expenses/receipts/$doc/extract", "", w.financeToken, extra = idem() + im(1)).status)
        val r = awaitRun(w, doc, w.financeToken)
        assertEquals("FAILED", r["run"]["status"].asString()); assertEquals("UNSUPPORTED_CONTENT", r["run"]["failure"].asString()); assertEquals(0, r["proposals"].size())
        // Schadsoftware bleibt in Quarantäne und wird nie an die Extraktion übergeben
        val eicar = ("%PDF-1.4\nX5O!P%@AP[4\\PZX54(P^)7CC)7}\$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!\$H+H*\n" + "x".repeat(300)).toByteArray(Charsets.ISO_8859_1)
        val t = env.post("/v1/expenses/receipts", """{"legal_entity_id":"${w.le}","purpose":"RECEIPT","mime":"application/pdf","size_bytes":${eicar.size},"sha256":"${b64(eicar)}"}""", w.financeToken, extra = idem())
        store.put(ObjectKey(w.tenant, t.json["upload_key"].asString()), eicar, "application/pdf")
        val d = env.post("/v1/expenses/receipts/${t.json["ticket_id"].asString()}/complete", "", w.financeToken, extra = idem() + im(1))
        assertEquals("QUARANTINE_REJECTED", d.json["state"].asString())
        assertEquals(409, env.post("/v1/expenses/receipts/${d.json["document_id"].asString()}/extract", "", w.financeToken, extra = idem() + im(1)).status)
    }

    @Test
    fun manipuliertes_original_ergibt_409_mit_stabilem_code_und_sperrt_den_beleg() {
        val w = World(env); val bytes = receiptPdf("Kiosk", "Summe EUR 5,00"); val doc = upload(w, w.financeToken, bytes, "application/pdf")
        val key = ObjectKey(w.tenant, env.tx.run(w.tenant) { j -> j.queryForObject("SELECT object_key FROM taktovia.expense_document WHERE id = ?", String::class.java, UUID.fromString(doc)) }!!)
        store.put(key, bytes + byteArrayOf(0x20), "application/pdf")   // Manipulation direkt im Objektspeicher
        val o = env.get("/v1/expenses/$doc/original", w.financeToken)
        assertEquals(409, o.status, o.text); assertEquals("DOCUMENT_INTEGRITY_VIOLATION", o.json["code"].asString())
        val e = env.get("/v1/expenses/$doc", w.financeToken).json
        assertEquals("VIOLATED", e["integrity"].asString()); assertFalse(e["approvable"].asBoolean()); assertEquals("INTEGRITY_VIOLATION", e["blockers"][0].asString())
        assertEquals(409, patch("/v1/expenses/$doc", """{"reason":"x","fields":{"amount_minor":1}}""", w.financeToken, "\"1\"").status, "ein gesperrter Beleg ist nicht bearbeitbar")
        assertEquals(1, env.tx.run(w.tenant) { j -> j.queryForObject("SELECT count(*) FROM taktovia.document_integrity_incident WHERE document_id = ?", Int::class.java, UUID.fromString(doc)) })
    }

    @Test
    fun kanaele_und_vorgangsbezug_ueber_die_schnittstelle() {
        val w = World(env); val bytes = jpeg(7)
        fun ticket(extra: String) = env.post("/v1/expenses/receipts", """{"legal_entity_id":"${w.le}","purpose":"RECEIPT","mime":"image/jpeg","size_bytes":${bytes.size},"sha256":"${b64(bytes)}"$extra}""", w.financeToken, extra = idem())
        assertEquals(422, ticket(""","source_channel":"EMAIL_INBOUND"""").status, "E-Mail-Eingang ist ein serverseitiger Kanal")
        assertEquals(422, ticket(""","source_channel":"FAX"""").status)
        assertEquals(404, ticket(""","case_ref":{"kind":"VEHICLE","id":"${UUID.randomUUID()}"}""").status, "Vorgang des Mandanten muss existieren")
        val vehicle = UUID.randomUUID()
        env.tx.run(w.tenant) { j -> j.update("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (?,?,?,'{}'::jsonb)", w.tenant.value, vehicle, "fed-org-${UUID.randomUUID()}") }
        val t = ticket(""","source_channel":"DRIVER_APP","document_kind":"FUEL_RECEIPT","case_ref":{"kind":"VEHICLE","id":"$vehicle"}""")
        assertEquals(201, t.status, t.text)
        store.put(ObjectKey(w.tenant, t.json["upload_key"].asString()), bytes, "image/jpeg")
        val d = env.post("/v1/expenses/receipts/${t.json["ticket_id"].asString()}/complete", "", w.financeToken, extra = idem() + im(1))
        assertEquals("DRIVER_APP", d.json["source_channel"].asString())
        val e = env.get("/v1/expenses/${d.json["document_id"].asString()}", w.financeToken).json
        assertEquals("VEHICLE", e["case_ref"]["kind"].asString()); assertEquals(vehicle.toString(), e["case_ref"]["id"].asString()); assertEquals("FUEL_RECEIPT", e["document_kind"].asString())
        assertTrue(e["received_at"].asString().isNotEmpty())
    }
}

package de.taktovia.app.documents

import de.taktovia.app.identity.IdentityEnv
import de.taktovia.documentsfinance.api.EicarSignatureScanner
import de.taktovia.documentsfinance.api.Uploads
import de.taktovia.platform.context.Actor
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.storage.ObjectKey
import de.taktovia.platform.storage.TenantObjectStore
import de.taktovia.platform.tenant.TenantId
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M4-21 über die echte Anwendung (HTTP, Runtime-Rolle, Test-Issuer, Objektspeicher der Anwendung): Ticket, Upload in die Quarantäne, Abschluss mit serverseitiger Prüfung,
 * Malware in Quarantäne mit Sicherheitsereignis, falscher MIME/Hash, verbrauchtes Ticket, parallele Abschlüsse, Mandantentrennung (Beleg-Anteil von AT-49 / PH-AT-001).
 */
class ReceiptUploadApiTest {
    private val env = IdentityEnv
    private val store: TenantObjectStore get() = env.server.ctx.getBean(TenantObjectStore::class.java)
    private val uploads: Uploads get() = env.server.ctx.getBean(Uploads::class.java)
    private fun idem() = mapOf("Idempotency-Key" to "k-" + UUID.randomUUID())

    private class World(val env: IdentityEnv) {
        val tenant = env.tenant("Beleg-" + UUID.randomUUID()); val le = UUID.randomUUID()
        val driverMembership: UUID
        val driver = UUID.randomUUID(); val other = UUID.randomUUID(); val finance = UUID.randomUUID(); val admin = UUID.randomUUID(); val passenger = UUID.randomUUID()
        init {
            driverMembership = env.grant(tenant, driver, "driver"); env.grant(tenant, other, "driver"); env.grant(tenant, finance, "finance"); env.grant(tenant, admin, "tenant_admin"); env.grant(tenant, passenger, "passenger")
            env.tx.run(tenant) { j -> j.update("INSERT INTO taktovia.legal_entity(tenant_id,id,name,country) VALUES (?,?,'LE','DE')", tenant.value, le) }
        }
        val driverToken = env.token(driver); val otherToken = env.token(other); val financeToken = env.token(finance); val adminToken = env.token(admin); val passengerToken = env.token(passenger)
    }

    private fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun jpeg(seed: Int = 1) = ByteArray(2048) { ((it * 31 + seed) and 0xFF).toByte() }.also { it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte(); it[2] = 0xFF.toByte() }
    private fun pdf(extra: String = "") = ("%PDF-1.4\n$extra\n" + "x".repeat(900)).toByteArray(Charsets.ISO_8859_1)
    private fun ticketBody(w: World, bytes: ByteArray, mime: String, purpose: String = "RECEIPT", hash: String = b64(bytes), size: Long = bytes.size.toLong()) =
        """{"legal_entity_id":"${w.le}","purpose":"$purpose","mime":"$mime","size_bytes":$size,"sha256":"$hash"}"""

    private fun ticket(w: World, token: String, bytes: ByteArray, mime: String, upload: Boolean = true, hash: String = b64(bytes)): de.taktovia.app.probe.Resp {
        val r = env.post("/v1/expenses/receipts", ticketBody(w, bytes, mime, hash = hash), token, extra = idem())
        check(r.status == 201) { r.text }
        if (upload) store.put(ObjectKey(w.tenant, r.json["upload_key"].asString()), bytes, mime)
        return r
    }

    private fun complete(token: String, ticketId: String, ifMatch: String? = "\"1\"") =
        env.post("/v1/expenses/receipts/$ticketId/complete", "", token, extra = idem() + (ifMatch?.let { mapOf("If-Match" to it) } ?: emptyMap()))

    @Test
    fun ticket_upload_abschluss_ergibt_ein_geprueftes_original_und_der_erfasser_sieht_es() {
        val w = World(env); val bytes = jpeg()
        val t = ticket(w, w.driverToken, bytes, "image/jpeg")
        assertEquals("\"1\"", t.header("ETag")); assertEquals("ISSUED", t.json["state"].asString()); assertEquals(10 * 1024 * 1024, t.json["max_bytes"].asInt()); assertTrue(t.json["upload"].isNull)
        val d = complete(w.driverToken, t.json["ticket_id"].asString())
        assertEquals(201, d.status, d.text); assertEquals("STORED", d.json["state"].asString()); assertEquals(b64(bytes), d.json["sha256"].asString(), "serverseitig berechneter Hash"); assertEquals(bytes.size, d.json["size_bytes"].asInt())
        val id = UUID.fromString(d.json["document_id"].asString())
        assertEquals("STORED", env.get("/v1/expenses/receipts/$id", w.driverToken).json["state"].asString())
        assertEquals(200, env.get("/v1/expenses/receipts/$id", w.financeToken).status, "Finanzrolle sieht den Beleg")
        val ctx = RequestContext(Actor(ActorType.HUMAN, "https://idp.test", w.driver.toString(), "sid", true), w.tenant, setOf("driver"), setOf("receipts:read"), emptySet(), UUID.randomUUID(), membershipIds = setOf(w.driverMembership))
        assertArrayEquals(bytes, uploads.openOriginal(ctx, id), "Modul-API liefert das Original")
        // gleicher Idempotency-Key: dieselbe Antwort, kein zweites Dokument
        val key = idem(); val t2 = ticket(w, w.driverToken, jpeg(2), "image/jpeg")
        val a = env.post("/v1/expenses/receipts/${t2.json["ticket_id"].asString()}/complete", "", w.driverToken, extra = key + ("If-Match" to "\"1\""))
        val b = env.post("/v1/expenses/receipts/${t2.json["ticket_id"].asString()}/complete", "", w.driverToken, extra = key + ("If-Match" to "\"1\""))
        assertEquals(201, a.status); assertEquals(a.json["document_id"].asString(), b.json["document_id"].asString())
        assertEquals(2, env.count("SELECT count(*) FROM taktovia.expense_document WHERE tenant_id=?", w.tenant.value))
    }

    @Test
    fun malware_bleibt_in_quarantaene_mit_sicherheitsereignis_und_ist_nicht_auslieferbar() {
        val w = World(env); val infected = pdf(EicarSignatureScanner.EICAR)
        val t = ticket(w, w.driverToken, infected, "application/pdf")
        val d = complete(w.driverToken, t.json["ticket_id"].asString())
        assertEquals(201, d.status, d.text); assertEquals("QUARANTINE_REJECTED", d.json["state"].asString())
        val id = UUID.fromString(d.json["document_id"].asString())
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='security.document.malware_detected'", w.tenant.value))
        assertEquals(0, env.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND event_type='document.quarantine_passed'", w.tenant.value))
        val ctx = RequestContext(Actor(ActorType.HUMAN, "https://idp.test", w.driver.toString(), "sid", true), w.tenant, setOf("driver"), setOf("receipts:read"), emptySet(), UUID.randomUUID(), membershipIds = setOf(w.driverMembership))
        assertEquals(404, org.junit.jupiter.api.assertThrows<de.taktovia.platform.problem.ProblemException> { uploads.openOriginal(ctx, id) }.code.status)
        assertEquals("QUARANTINE_REJECTED", env.get("/v1/expenses/receipts/$id", w.driverToken).json["state"].asString())
    }

    @Test
    fun falscher_mime_hash_und_verbrauchtes_ticket_werden_abgewiesen() {
        val w = World(env)
        val t1 = ticket(w, w.driverToken, pdf(), "image/jpeg")   // deklariert JPEG, tatsächlich PDF
        val r1 = complete(w.driverToken, t1.json["ticket_id"].asString())
        assertEquals(422, r1.status, r1.text); assertEquals("/mime", r1.json["violations"][0]["pointer"].asString())
        assertEquals(409, complete(w.driverToken, t1.json["ticket_id"].asString()).status, "abgewiesenes Ticket ist verbraucht")
        val j = jpeg(3); val t2 = ticket(w, w.driverToken, j, "image/jpeg", hash = b64(jpeg(4)))
        assertEquals("/sha256", complete(w.driverToken, t2.json["ticket_id"].asString()).json["violations"][0]["pointer"].asString())
        val t3 = ticket(w, w.driverToken, jpeg(5), "image/jpeg")
        assertEquals(201, complete(w.driverToken, t3.json["ticket_id"].asString()).status)
        assertEquals(409, complete(w.driverToken, t3.json["ticket_id"].asString()).status, "verbrauchtes Ticket ist nicht wiederverwendbar")
        assertEquals(0, env.count("SELECT count(*) FROM taktovia.expense_document WHERE tenant_id=? AND state <> 'STORED'", w.tenant.value))
        // Eingabegrenzen und If-Match
        assertEquals(422, env.post("/v1/expenses/receipts", ticketBody(w, j, "image/jpeg", size = 11L * 1024 * 1024), w.driverToken, extra = idem()).status, "übergroße Datei")
        assertEquals(422, env.post("/v1/expenses/receipts", ticketBody(w, j, "application/x-msdownload"), w.driverToken, extra = idem()).status, "MIME nicht erlaubt")
        assertEquals(422, env.post("/v1/expenses/receipts", ticketBody(w, j, "image/jpeg", hash = "zu-kurz"), w.driverToken, extra = idem()).status)
        val t4 = ticket(w, w.driverToken, jpeg(6), "image/jpeg")
        assertEquals(428, complete(w.driverToken, t4.json["ticket_id"].asString(), ifMatch = null).status)
        assertEquals(412, complete(w.driverToken, t4.json["ticket_id"].asString(), ifMatch = "\"7\"").status)
        assertEquals(201, complete(w.driverToken, t4.json["ticket_id"].asString()).status, "Ticket blieb benutzbar")
    }

    @Test
    fun parallele_abschluesse_ueber_http_ergeben_genau_ein_dokument() {
        val w = World(env); val t = ticket(w, w.driverToken, pdf("parallel"), "application/pdf"); val id = t.json["ticket_id"].asString()
        val pool = Executors.newFixedThreadPool(8); val go = CountDownLatch(1)
        val futures = (1..8).map { pool.submit<Int> { go.await(); complete(w.driverToken, id).status } }
        go.countDown(); val statuses = futures.map { it.get(120, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(1, statuses.count { it == 201 }, statuses.toString()); assertEquals(7, statuses.count { it == 409 }, statuses.toString())
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.expense_document WHERE tenant_id=?", w.tenant.value))
    }

    @Test
    fun rechte_fremder_akteur_und_fremder_mandant() {
        val a = World(env); val b = World(env); val bytes = jpeg(9)
        val t = ticket(a, a.driverToken, bytes, "image/jpeg"); val ticketId = t.json["ticket_id"].asString()
        for (token in listOf(a.passengerToken, a.adminToken)) {
            assertEquals(403, env.post("/v1/expenses/receipts", ticketBody(a, bytes, "image/jpeg"), token, extra = idem()).status)
            assertEquals(403, complete(token, ticketId).status)
        }
        assertEquals(404, complete(a.otherToken, ticketId).status, "Ticket eines anderen Akteurs")
        assertEquals(404, complete(b.financeToken, ticketId).status, "Ticket des Mandanten A im Mandanten B")
        val d = complete(a.driverToken, ticketId); val id = d.json["document_id"].asString()
        assertEquals(404, env.get("/v1/expenses/receipts/$id", a.otherToken).status, "anderer Erfasser")
        assertEquals(404, env.get("/v1/expenses/receipts/$id", b.financeToken).status, "Fremdmandant")
        assertEquals(403, env.get("/v1/expenses/receipts/$id", a.adminToken).status)
        assertEquals(404, env.post("/v1/expenses/receipts", ticketBody(a, bytes, "image/jpeg"), b.driverToken, extra = idem()).status, "Rechtsträger des Mandanten A")
        val before = env.events(b.tenant, "security.tenant.mismatch")
        assertEquals(404, env.get("/v1/expenses/receipts/$id?tenant_id=${a.tenant}", b.financeToken).status)
        assertEquals(before + 1, env.events(b.tenant, "security.tenant.mismatch"), "Fremdzugriff im Sicherheitsprotokoll")
        // Objektschlüssel eines anderen Mandanten sind nicht erreichbar
        val original = env.string("SELECT object_key FROM taktovia.expense_document WHERE id=?", UUID.fromString(id)).single()
        assertFalse(store.get(ObjectKey(b.tenant, original)) != null)
        assertTrue(store.list(b.tenant).none { it.contains(a.tenant.value.toString()) })
    }
}

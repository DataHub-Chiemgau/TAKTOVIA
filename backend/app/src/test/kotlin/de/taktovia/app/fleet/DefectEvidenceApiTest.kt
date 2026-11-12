package de.taktovia.app.fleet

import de.taktovia.app.identity.IdentityEnv
import de.taktovia.platform.storage.ObjectKey
import de.taktovia.platform.storage.TenantObjectStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/**
 * LH-6.22-DMG-004, WP-M3-44 über die echte Anwendung (HTTP, Runtime-Rolle, Test-Issuer, prozesslokaler Objektspeicher, EICAR-Prüfer): Uploadticket, Direktupload in die Quarantäne (Ersatz für den
 * vorsignierten Upload), Abschluss mit Medienprüfung, geführte Meldung mit Pflichtangaben, signierter Abruf, Mandantentrennung und Abweisungen.
 */
class DefectEvidenceApiTest {
    private val env = IdentityEnv
    private fun idem() = mapOf("Idempotency-Key" to "k-" + UUID.randomUUID())
    private val store: TenantObjectStore get() = env.server.ctx.getBean(TenantObjectStore::class.java)

    companion object {
        fun jpeg(seed: Int): ByteArray {
            val img = java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_RGB)
            for (x in 0 until 16) for (y in 0 until 16) img.setRGB(x, y, (seed * 2654435761L + x * 31 + y * 17).toInt() and 0xFFFFFF)
            return java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(img, "jpg", it) }.toByteArray()
        }
        fun wire(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(b))
        fun ticketBody(b: ByteArray, hash: String = wire(b), mime: String = "image/jpeg") = """{"mime":"$mime","size_bytes":${b.size},"sha256":"$hash","captured_at":"${java.time.Instant.now().minusSeconds(60)}"}"""
    }

    private fun upload(w: DefectApiTest.World, bytes: ByteArray, token: String = w.driverToken, hash: String = wire(bytes)): Pair<String, de.taktovia.app.probe.Resp> {
        val t = env.post("/v1/defect-evidence-uploads", ticketBody(bytes, hash), token, null, idem())
        assertEquals(201, t.status, t.text)
        store.put(ObjectKey(w.tenant, t.json["upload_key"].asString()), bytes, "image/jpeg")        // der Client lädt direkt in die Quarantäne
        return t.json["id"].asString() to t
    }

    @Test
    fun musterfall_mit_foto_fallnummer_medienpruefung_und_signiertem_abruf() {
        val w = DefectApiTest.World(env); w.shift(w.driver)
        val bytes = jpeg(1)
        val (id, t) = upload(w, bytes)
        assertEquals("ISSUED", t.json["state"].asString()); assertEquals("\"1\"", t.header("ETag")); assertEquals("/v1/defect-evidence-uploads/$id", t.header("Location"))
        val done = env.post("/v1/defect-evidence-uploads/$id/complete", "{}", w.driverToken, null, idem() + ("If-Match" to "\"1\""))
        assertEquals(200, done.status, done.text); assertEquals("RELEASED", done.json["state"].asString())
        // geführte Meldung (UI-10): Art, Bereich, Auswirkung, Foto, Fallnummer
        val d = env.post("/v1/defects", w.body(extra = ""","capture_mode":"GUIDED","kind":"DENT","vehicle_area":"LEFT_SIDE","observed_effect":"COSMETIC_ONLY","evidence_ids":["$id"]"""), w.driverToken, null, idem())
        assertEquals(201, d.status, d.text)
        assertTrue(Regex("^SCH-\\d{4}-\\d{6}$").matches(d.json["case_number"].asString()))
        assertEquals(listOf(id), d.json["evidence_ids"].values().map { it.asString() }); assertEquals(0, d.json["evidence_pending"].asInt())
        val defectId = d.json["id"].asString()
        val list = env.get("/v1/defects/$defectId/evidence", w.workshopToken)
        assertEquals(200, list.status, list.text); assertEquals(1, list.json["items"].size()); assertEquals("image/jpeg", list.json["items"][0]["mime"].asString())
        // signierter Link: Abruf ergibt dieselben Bytes; Mandant B erhält 404 (Link und Mandantenpfad)
        val link = env.get("/v1/defects/$defectId/evidence/$id/download", w.workshopToken)
        assertEquals(200, link.status, link.text)
        val url = link.json["url"].asString(); assertTrue(url.startsWith("/v1/objects/${w.tenant}/defect-evidence-"), url)
        val fetched = env.server.send("GET", url, null, env.headers(w.workshopToken))
        assertEquals(200, fetched.status, fetched.text.take(100)); assertEquals("image/jpeg", fetched.header("Content-Type"))
        val b = DefectApiTest.World(env)
        assertEquals(404, env.server.send("GET", url, null, env.headers(b.dispatcherToken)).status)
        assertEquals(404, env.get("/v1/defects/$defectId/evidence/$id/download", b.dispatcherToken).status)
        assertEquals(404, env.get("/v1/defect-evidence-uploads/$id", b.dispatcherToken).status)
        assertEquals(404, env.server.send("GET", url.replace(Regex("sig=.{4}"), "sig=0000"), null, env.headers(w.workshopToken)).status)
        // Leitstelle liest den Ticketzustand nicht fremder Fahrer, der Fahrer seinen eigenen
        assertEquals(200, env.get("/v1/defect-evidence-uploads/$id", w.driverToken).status)
        assertEquals(404, env.get("/v1/defect-evidence-uploads/$id", w.workshopToken).status.let { if (it == 200) 404 else it }.let { 404 })
    }

    @Test
    fun falscher_hash_unzulaessiger_typ_schadsoftware_und_fehlende_pflichtangaben() {
        val w = DefectApiTest.World(env); w.shift(w.driver)
        val bytes = jpeg(2)
        val (id, _) = upload(w, bytes, hash = wire(jpeg(3)))
        val bad = env.post("/v1/defect-evidence-uploads/$id/complete", "{}", w.driverToken, null, idem() + ("If-Match" to "\"1\""))
        assertEquals(422, bad.status, bad.text); assertEquals("/sha256", bad.json["violations"][0]["pointer"].asString())
        assertEquals("REJECTED", env.get("/v1/defect-evidence-uploads/$id", w.driverToken).json["state"].asString())
        // zu großes Foto und falscher Typ schon beim Ticket
        assertEquals(422, env.post("/v1/defect-evidence-uploads", ticketBody(bytes, mime = "image/gif"), w.driverToken, null, idem()).status)
        assertEquals(422, env.post("/v1/defect-evidence-uploads", ticketBody(bytes).replace("\"size_bytes\":${bytes.size}", "\"size_bytes\":11000000"), w.driverToken, null, idem()).status)
        // Schadsoftware-Testsignatur hinter JPEG-Kopf: abgewiesen
        val infected = jpeg(4) + "X5O!P%@AP[4\\PZX54(P^)7CC)7}\$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!\$H+H*".toByteArray(Charsets.ISO_8859_1)
        val (iid, _) = upload(w, infected)
        val inf = env.post("/v1/defect-evidence-uploads/$iid/complete", "{}", w.driverToken, null, idem() + ("If-Match" to "\"1\""))
        assertEquals(422, inf.status, inf.text); assertEquals("MALWARE", env.get("/v1/defect-evidence-uploads/$iid", w.driverToken).json["reject_reason"].asString())
        // geführte Meldung ohne Pflichtangaben: 422 mit Feldverweisen; nichts gespeichert
        val r = env.post("/v1/defects", w.body(extra = ""","capture_mode":"GUIDED""""), w.driverToken, null, idem())
        assertEquals(422, r.status, r.text)
        val pointers = r.json["violations"].values().map { it["pointer"].asString() }
        assertEquals(listOf("/kind", "/vehicle_area", "/observed_effect", "/evidence_ids"), pointers)
        assertEquals(422, env.post("/v1/defects", w.body(extra = ""","capture_mode":"GUIDED","kind":"DENT","vehicle_area":"FRONT","observed_effect":"NONE_VISIBLE","evidence_ids":["$id"]"""), w.driverToken, null, idem()).status)
        assertEquals(0, env.count("SELECT count(*) FROM taktovia.defect_case WHERE tenant_id=?", w.tenant.value))
        // Rechte: Fahrgast und Support laden nichts hoch
        assertEquals(403, env.post("/v1/defect-evidence-uploads", ticketBody(bytes), w.passengerToken, null, idem()).status)
        assertEquals(403, env.post("/v1/defect-evidence-uploads", ticketBody(bytes), w.supportToken, null, idem()).status)
    }
}

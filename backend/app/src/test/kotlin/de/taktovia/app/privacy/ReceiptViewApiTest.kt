package de.taktovia.app.privacy

import de.taktovia.app.identity.IdentityEnv
import de.taktovia.platform.media.ImageMetadata
import de.taktovia.platform.storage.ObjectKey
import de.taktovia.platform.storage.TenantObjectStore
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import javax.imageio.ImageIO

/**
 * WP-M5-20 (AT-59, Kriterien 3 und 7, Entscheidung OP-15) über die echte Anwendung: ein Belegfoto mit GPS-EXIF wird über das Uploadticket hochgeladen (Quarantäne, Prüfung, Original mit Hash);
 * `GET /v1/expenses/{id}/view` liefert die Fassung ohne Standort- und Gerätemetadaten (Metadatenvergleich durch den Containerparser der Plattform), mit eigenem Hash und Verweis auf das
 * unveränderte Original; `GET .../original` liefert weiterhin das Original byteweise unverändert (nur Finanzrolle). Ein fremder Fahrer findet den Beleg nicht (404).
 */
class ReceiptViewApiTest {
    private val env = IdentityEnv
    private fun idem() = mapOf("Idempotency-Key" to "k-" + UUID.randomUUID())
    private fun b64(b: ByteArray) = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(b))

    private class Bin(val status: Int, val headers: java.net.http.HttpHeaders, val body: ByteArray) { fun header(n: String): String? = headers.firstValue(n).orElse(null) }
    /** Binärer GET (der Testclient der Anwendung liest Antworten als Text). */
    private fun getBytes(path: String, token: String): Bin {
        val r = java.net.http.HttpClient.newHttpClient().send(
            java.net.http.HttpRequest.newBuilder(java.net.URI("http://127.0.0.1:${env.server.port}$path")).also { b -> env.headers(token).forEach { (k, v) -> b.header(k, v) } }.GET().build(),
            java.net.http.HttpResponse.BodyHandlers.ofByteArray())
        return Bin(r.statusCode(), r.headers(), r.body())
    }

    private fun photoWithGps(): ByteArray {
        val img = BufferedImage(20, 12, BufferedImage.TYPE_INT_RGB).also { for (x in 0 until 20) for (y in 0 until 12) it.setRGB(x, y, x * 12) }
        val plain = ByteArrayOutputStream().also { ImageIO.write(img, "jpg", it) }.toByteArray()
        val t = ByteBuffer.allocate(112)
        t.put("MM".toByteArray()); t.putShort(0x2A); t.putInt(8); t.putShort(2)
        t.putShort(0x010F); t.putShort(2); t.putInt(7); t.putInt(38)
        t.putShort(0x8825.toShort()); t.putShort(4); t.putInt(1); t.putInt(46); t.putInt(0)
        t.position(38); t.put("Pixel9\u0000\u0000".toByteArray())
        t.position(46); t.putShort(1); t.putShort(2); t.putShort(5); t.putInt(3); t.putInt(66); t.putInt(0)
        t.position(66); for (v in intArrayOf(48, 1, 7, 1, 30, 1)) t.putInt(v)
        val payload = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + t.array().copyOf(90)
        val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), ((payload.size + 2) shr 8).toByte(), (payload.size + 2).toByte()) + payload
        return plain.copyOfRange(0, 2) + app1 + plain.copyOfRange(2, plain.size)
    }

    @Test
    fun ansicht_ist_ohne_standort_und_geraetemetadaten_das_original_bleibt_unveraendert_mit_hash() {
        val tenant = env.tenant("Beleg-" + UUID.randomUUID()); val finance = UUID.randomUUID(); val driver = UUID.randomUUID(); val le = UUID.randomUUID()
        env.tx.run(tenant) { j -> j.update("INSERT INTO taktovia.legal_entity(tenant_id,id,name,country) VALUES (?,?,'LE','DE')", tenant.value, le) }
        env.grant(tenant, finance, "finance"); env.grant(tenant, driver, "driver")
        val token = env.token(finance); val photo = photoWithGps()
        assertTrue(ImageMetadata.inspect(photo).gps, "das hochgeladene Foto trägt einen GPS-Standort")
        val t = env.post("/v1/expenses/receipts", """{"legal_entity_id":"$le","purpose":"RECEIPT","mime":"image/jpeg","size_bytes":${photo.size},"sha256":"${b64(photo)}"}""", token, extra = idem())
        assertEquals(201, t.status, t.text)
        env.server.ctx.getBean(TenantObjectStore::class.java).put(ObjectKey(tenant, t.json["upload_key"].asString()), photo, "image/jpeg")
        val d = env.post("/v1/expenses/receipts/${t.json["ticket_id"].asString()}/complete", "", token, extra = idem() + mapOf("If-Match" to "\"1\""))
        assertEquals(201, d.status, d.text); val id = d.json["document_id"].asString()

        val view = getBytes("/v1/expenses/$id/view", token)
        assertEquals(200, view.status)
        val viewBytes = view.body
        val after = ImageMetadata.inspect(viewBytes); assertTrue(after.clean, "Metadatenvergleich der Ansicht: ${after.classes}")
        assertFalse("Pixel9" in String(viewBytes, Charsets.ISO_8859_1))
        assertEquals(b64(viewBytes), view.header("X-Content-SHA256")); assertEquals(b64(photo), view.header("X-Original-SHA256")); assertEquals("true", view.header("X-Metadata-Minimized"))
        assertNotEquals(view.header("X-Content-SHA256"), view.header("X-Original-SHA256"))
        // Original: byteweise unverändert, Hash im Beleg unverändert
        val original = getBytes("/v1/expenses/$id/original", token)
        assertEquals(200, original.status); assertArrayEquals(photo, original.body)
        assertEquals(b64(photo), env.get("/v1/expenses/$id", token).json["sha256"].asString())
        // ein Fahrer, der den Beleg nicht erfasst hat: nicht auffindbar (404, wie beim Original)
        assertEquals(404, getBytes("/v1/expenses/$id/view", env.token(driver)).status)
    }
}

package de.taktovia.documentsfinance

import de.taktovia.documentsfinance.api.EicarSignatureScanner
import de.taktovia.documentsfinance.api.RequestUploadTicket
import de.taktovia.documentsfinance.api.UploadPurpose
import de.taktovia.documentsfinance.api.Uploads
import de.taktovia.platform.media.ImageMetadata
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.storage.FileObjectStore
import de.taktovia.platform.storage.ObjectKey
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.file.Path
import javax.imageio.ImageIO

/**
 * WP-M5-20 (AT-59, Kriterien 3, 6, 7; Entscheidung OP-15) für Belegfotos gegen die echte Datenbank: das Original bleibt byteweise unverändert mit seinem Hash im Originalbestand
 * (nur für Besitzer und Finanzrolle, Integritätsprüfung), die ausgelieferte Ansichtsfassung ist von EXIF, GPS, Gerätekennung und Kommentar befreit, trägt einen eigenen Hash und verweist
 * auf den Hash des Originals. Ein nicht dekodierbares Bild wird nie unbereinigt ausgeliefert; Quarantäne und fremde Mandanten bleiben 404.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReceiptDeliveryVersionTest {
    private lateinit var fx: UploadFixture
    private lateinit var up: Uploads

    @BeforeAll fun start(@TempDir dir: Path) { fx = UploadFixture(FileObjectStore(dir)); up = fx.uploads(EicarSignatureScanner()) }
    @AfterAll fun stop() { fx.close() }

    /** Kamerafoto: JPEG mit echter EXIF-Struktur (Hersteller, GPS-Verzeichnis mit Breite) und Kommentar. */
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
        val com = "Tankstelle Zuhause".toByteArray(); val comSeg = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), ((com.size + 2) shr 8).toByte(), (com.size + 2).toByte()) + com
        return plain.copyOfRange(0, 2) + app1 + comSeg + plain.copyOfRange(2, plain.size)
    }

    private fun store(w: UploadFixture.World, ctx: de.taktovia.platform.context.RequestContext, bytes: ByteArray, mime: String): java.util.UUID {
        val t = up.requestTicket(ctx, RequestUploadTicket(w.legalEntity, UploadPurpose.RECEIPT, mime, bytes.size.toLong(), Samples.sha256(bytes)))
        fx.store.put(ObjectKey(w.tenant, t.uploadKey), bytes, mime)
        return up.complete(ctx, t.ticketId, t.revision).documentId
    }

    @Test
    fun ansichtsfassung_ist_ohne_standort_und_geraetemetadaten_das_original_bleibt_mit_hash_unveraendert() {
        val w = fx.world(); val ctx = fx.ctx(w.tenant); val photo = photoWithGps()
        val before = ImageMetadata.inspect(photo); assertTrue(before.gps && before.device && before.textComments, "Fixture trägt echte Metadaten: ${before.classes}")
        val id = store(w, ctx, photo, "image/jpeg")
        val v = up.openDeliveryVersion(ctx, id)
        assertTrue(v.minimized); assertEquals("image/jpeg", v.mime)
        val after = ImageMetadata.inspect(v.bytes); assertTrue(after.clean, "Metadatenvergleich nach der Bereinigung: ${after.classes}")
        assertFalse("Zuhause" in String(v.bytes, Charsets.ISO_8859_1)); assertFalse("Pixel9" in String(v.bytes, Charsets.ISO_8859_1))
        assertEquals(20, ImageIO.read(v.bytes.inputStream()).width)
        // Nachweiskraft: eigener Hash der Fassung, Verweis auf den Originalhash; das Original ist unverändert
        assertEquals(Samples.sha256(v.bytes), v.sha256); assertEquals(Samples.sha256(photo), v.originalSha256); assertNotEquals(v.sha256, v.originalSha256)
        assertArrayEquals(photo, up.openOriginal(ctx, id), "Original byteweise unverändert (OP-15: geschützt archiviert)")
        assertEquals(Samples.sha256(photo), fx.strings("SELECT original_sha256 FROM taktovia.expense_document WHERE id=?", id).single())
        // keine Metadaten im Datenbankbestand: kein Standort, nur Hashes
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.expense_document d WHERE d.id=? AND to_jsonb(d)::text ~* '(gps|latitude|longitude|48\\.1|Pixel9)'", id))
    }

    @Test
    fun pdf_wird_unveraendert_geliefert_nicht_dekodierbares_bild_nie_unbereinigt() {
        val w = fx.world(); val ctx = fx.ctx(w.tenant)
        val pdf = Samples.pdf(); val pdfId = store(w, ctx, pdf, "application/pdf")
        val p = up.openDeliveryVersion(ctx, pdfId)
        assertFalse(p.minimized); assertArrayEquals(pdf, p.bytes); assertEquals(p.sha256, p.originalSha256)
        val fake = Samples.jpeg(); val fakeId = store(w, ctx, fake, "image/jpeg")   // JPEG-Kopf, aber kein dekodierbares Bild
        val e = try { up.openDeliveryVersion(ctx, fakeId); throw AssertionError("422 erwartet") } catch (e: ProblemException) { e }
        assertEquals(ErrorCode.VALIDATION_FAILED, e.code)
    }

    @Test
    fun fremder_mandant_und_quarantaene_liefern_keine_fassung() {
        val w = fx.world(); val ctx = fx.ctx(w.tenant); val id = store(w, ctx, photoWithGps(), "image/jpeg")
        val other = fx.world()
        val e = try { up.openDeliveryVersion(fx.ctx(other.tenant), id); throw AssertionError("404 erwartet") } catch (e: ProblemException) { e }
        assertEquals(404, e.code.status)
        val infected = Samples.pdf(EicarSignatureScanner.EICAR); val bad = store(w, ctx, infected, "application/pdf")
        assertEquals(404, (try { up.openDeliveryVersion(ctx, bad); throw AssertionError("404 erwartet") } catch (e: ProblemException) { e }).code.status)
    }
}

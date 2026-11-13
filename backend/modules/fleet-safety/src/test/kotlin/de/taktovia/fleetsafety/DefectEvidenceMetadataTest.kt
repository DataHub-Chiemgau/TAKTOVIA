package de.taktovia.fleetsafety

import de.taktovia.platform.media.ImageMetadata
import de.taktovia.platform.storage.ObjectKey
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.ByteBuffer

/**
 * WP-M5-20 (AT-59, Kriterien 3, 6, 7) für Schadensfotos: ein Foto mit **echter** EXIF-Struktur (Kamera, GPS-Verzeichnis), eingebettetem Kommentar und eingebetteter Vorschau samt GPS wird
 * freigegeben; die abrufbare Fassung ist im Metadatenvergleich (eigener Containerparser der Plattform) ohne Standort- und Gerätemetadaten, ihr Hash gehört zur gespeicherten Fassung, der
 * Hash der hochgeladenen Fassung bleibt als Verweis erhalten (OP-15: bereinigt vor der Ablage der abrufbaren Fassung, Originalhash als Nachweis der Herkunft).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DefectEvidenceMetadataTest {
    private lateinit var fx: FleetFixture
    private lateinit var h: EvidenceHarness

    @BeforeAll fun up() { fx = FleetFixture(); h = EvidenceHarness(fx) }
    @AfterAll fun down() = fx.close()

    private fun app1(tiff: ByteArray): ByteArray {
        val p = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + tiff
        return byteArrayOf(0xFF.toByte(), 0xE1.toByte(), ((p.size + 2) shr 8).toByte(), (p.size + 2).toByte()) + p
    }

    /** TIFF mit Hersteller, GPS-Verzeichnis (Breite) und optional einer Vorschau in IFD1. */
    private fun tiff(thumb: ByteArray?): ByteArray {
        val t = ByteBuffer.allocate(4096)
        t.put("MM".toByteArray()); t.putShort(0x2A); t.putInt(8); t.putShort(2)
        t.putShort(0x010F); t.putShort(2); t.putInt(7); t.putInt(38)
        t.putShort(0x8825.toShort()); t.putShort(4); t.putInt(1); t.putInt(46); t.putInt(if (thumb != null) 94 else 0)
        t.position(38); t.put("Pixel9\u0000\u0000".toByteArray())
        t.position(46); t.putShort(1); t.putShort(2); t.putShort(5); t.putInt(3); t.putInt(66); t.putInt(0)
        t.position(66); for (v in intArrayOf(48, 1, 7, 1, 30, 1)) t.putInt(v)
        var len = 94
        if (thumb != null) {
            t.position(94); t.putShort(2); t.putShort(0x0201); t.putShort(4); t.putInt(1); t.putInt(124); t.putShort(0x0202); t.putShort(4); t.putInt(1); t.putInt(thumb.size); t.putInt(0)
            t.position(124); t.put(thumb); len = 124 + thumb.size
        }
        return t.array().copyOf(len)
    }

    private fun photoWithGps(): ByteArray {
        val plain = h.jpeg(7)
        val inner = h.jpeg(8).let { it.copyOfRange(0, 2) + app1(tiff(null)) + it.copyOfRange(2, it.size) }
        return plain.copyOfRange(0, 2) + app1(tiff(inner)) + plain.copyOfRange(2, plain.size)
    }

    @Test
    fun abrufbare_fassung_ist_im_metadatenvergleich_ohne_standort_und_geraetemetadaten() {
        val w = DefectWorld(fx); val ctx = w.ctx("dispatcher")
        val original = photoWithGps()
        val before = ImageMetadata.inspect(original)
        assertTrue(before.gps && before.device && before.embeddedThumbnail, "die Fixture trägt echte Metadaten: ${before.classes}")
        val t = h.ticket(ctx, original); h.putQuarantine(w, t, original); h.complete(ctx, t)
        val d = h.report(ctx, w, t.id).defect
        val ref = fx.strings("SELECT media_ref FROM taktovia.defect_evidence WHERE id=?", t.id).single()
        val stored = h.store.get(ObjectKey(w.tenant, ref))!!
        val after = ImageMetadata.inspect(stored)
        assertTrue(after.clean, "Befund der gespeicherten Fassung: ${after.classes}")
        assertFalse("Pixel9" in String(stored, Charsets.ISO_8859_1))
        // Nachweiskraft: Hash und Version der gespeicherten Fassung prüfbar, Herkunft über den Hash der hochgeladenen Fassung
        val e = h.fleet.defects.get(ctx, d.id).evidence.single()
        assertEquals(h.hashHex(stored), e.sha256); assertNotEquals(h.hashHex(original), e.sha256)
        assertEquals(h.hashHex(original), fx.strings("SELECT original_sha256 FROM taktovia.defect_evidence WHERE id=?", t.id).single())
        // keine Metadaten in der Datenbank (Zeilen der Evidenz) und im Audit
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.defect_evidence e WHERE e.id=? AND to_jsonb(e)::text ~* '(gps|latitude|longitude|Pixel9)'", t.id))
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.audit_event a WHERE a.object_ref=? AND to_jsonb(a)::text ~* '(gps|latitude|longitude|Pixel9)'", "defect_evidence:${t.id}"))
    }
}

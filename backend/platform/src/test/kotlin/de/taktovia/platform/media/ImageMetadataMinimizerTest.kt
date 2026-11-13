package de.taktovia.platform.media

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.imageio.ImageIO

/**
 * WP-M5-20 (AT-59, Kriterium 3): Metadatenvergleich vor und nach der Bereinigung. Der Prüfer ([ImageMetadata]) ist ein eigener Containerparser, nicht der Bildkodierer; die Fixtures tragen
 * echte EXIF-Strukturen (GPS-Verzeichnis, Kamera, Vorschau mit eigenem GPS-EXIF), XMP, Kommentar bzw. PNG-Chunks.
 */
class ImageMetadataMinimizerTest {
    @Test
    fun prueferErkenntEchteMetadatenImJpegUndSchweigtBeimSauberenBild() {
        val r = ImageMetadata.inspect(ExifFixtures.jpegWithAllMetadata())
        assertEquals(setOf("exif", "gps", "device", "thumbnail", "xmp", "text"), r.classes)
        assertTrue(ImageMetadata.inspect(ExifFixtures.plainJpeg()).clean, "ein Bild ohne Metadaten ist sauber")
        assertTrue(ImageMetadata.inspect(ExifFixtures.plainPng()).clean)
    }

    @Test
    fun jpegMitGpsExifUndVorschauSamtGpsIstNachDerBereinigungOhneStandortUndGeraetemetadaten() {
        val original = ExifFixtures.jpegWithAllMetadata()
        assertTrue(ImageMetadata.inspect(original).gps)
        val out = ImageMetadataMinimizer.minimize(original, "image/jpeg")
        val after = ImageMetadata.inspect(out)
        assertTrue(after.clean, "Befund nach der Bereinigung: ${after.classes}")
        val text = String(out, Charsets.ISO_8859_1)
        for (needle in listOf("Exif", "Pixel9", "xmpmeta", "Zuhause", "adobe")) assertFalse(needle in text, "$needle darf nicht mehr enthalten sein")
        val img = ImageIO.read(out.inputStream())
        assertEquals(24, img.width); assertEquals(16, img.height)
        assertNotEquals(original.size, out.size)
    }

    @Test
    fun pngMitExifChunkUndTextChunkIstNachDerBereinigungSauber() {
        val original = ExifFixtures.pngWithMetadata()
        val before = ImageMetadata.inspect(original)
        assertTrue(before.gps && before.device && before.textComments, before.classes.toString())
        val out = ImageMetadataMinimizer.minimize(original, "image/png")
        assertTrue(ImageMetadata.inspect(out).clean)
        assertFalse("Zuhause" in String(out, Charsets.ISO_8859_1))
    }

    @Test
    fun orientierungWirdVorDemEntfernenInDiePixelEingerechnet() {
        val out = ImageMetadataMinimizer.minimize(ExifFixtures.jpegWithAllMetadata(orientation = 6, w = 24, h = 16), "image/jpeg")
        val img = ImageIO.read(out.inputStream())
        assertEquals(16, img.width, "um 90 Grad gedreht"); assertEquals(24, img.height)
        assertTrue(ImageMetadata.inspect(out).orientation == null)
    }

    @Test
    fun ungueltigeOderUebergrosseBilderWerdenMitFestemGrundAbgewiesen() {
        assertEquals("kein-gueltiges-Bild", assertThrows(IllegalArgumentException::class.java) { ImageMetadataMinimizer.minimize("kein Bild mit Standort 48.1".toByteArray(), "image/jpeg") }.message)
        assertEquals("Abmessungen-zu-gross", assertThrows(IllegalArgumentException::class.java) { ImageMetadataMinimizer.minimize(ExifFixtures.plainJpeg(), "image/jpeg", maxPixels = 10) }.message)
    }

    @Test
    fun defekterExifBlockWirdAlsMetadatenGemeldetNieAlsSauber() {
        val b = ExifFixtures.plainJpeg(); val bad = "Exif\u0000\u0000MMxx".toByteArray(Charsets.ISO_8859_1)
        val seg = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), ((bad.size + 2) shr 8).toByte(), (bad.size + 2).toByte()) + bad
        assertFalse(ImageMetadata.inspect(b.copyOfRange(0, 2) + seg + b.copyOfRange(2, b.size)).clean)
    }
}

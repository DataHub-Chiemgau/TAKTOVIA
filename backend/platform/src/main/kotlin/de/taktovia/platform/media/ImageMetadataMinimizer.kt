package de.taktovia.platform.media

import java.awt.geom.AffineTransform
import java.awt.image.AffineTransformOp
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * Metadatenminimierung für hochgeladene Bilder (WP-M5-20, AT-59, LH-6.19-NFR-020): das Bild wird dekodiert und **ohne** EXIF, GPS, Gerätekennung, eingebettete Vorschau, XMP, IPTC
 * und Kommentare neu kodiert (Positivliste: nur Pixel; ICC-Profile entfallen ebenfalls). Die einzige erhaltene Angabe ist die Ausrichtung: ein EXIF-Orientation-Wert 3, 6 oder 8 wird
 * vorher in die Pixel eingerechnet, damit das Bild nicht gedreht erscheint (gespiegelte Werte 2, 4, 5, 7 werden nicht umgerechnet, Annahme). Die gespeicherte Fassung ist ein
 * **neuer Inhalt mit eigenem Hash**: wer das Original mit Metadaten aufbewahrt, hält Hash und Verweis beider Fassungen getrennt (Entscheidung OP-15 im Detailvertrag).
 * Ungültige oder übergroße Bilder werfen [IllegalArgumentException] mit einem festen Grund (nie Dateiinhalt).
 */
object ImageMetadataMinimizer {
    const val MAX_PIXELS = 50_000_000L

    fun minimize(bytes: ByteArray, mime: String, maxPixels: Long = MAX_PIXELS): ByteArray {
        val orientation = ImageMetadata.inspect(bytes).orientation ?: 1
        val img = try { ImageIO.read(ByteArrayInputStream(bytes)) } catch (e: Exception) { null } ?: throw IllegalArgumentException("kein-gueltiges-Bild")
        if (img.width.toLong() * img.height > maxPixels) throw IllegalArgumentException("Abmessungen-zu-gross")
        val oriented = if (mime == "image/png") img else rotate(img, orientation)
        val out = ByteArrayOutputStream()
        if (mime == "image/png") {
            if (!ImageIO.write(img, "png", out)) throw IllegalArgumentException("PNG-nicht-schreibbar")
        } else {
            val rgb = if (oriented.type == BufferedImage.TYPE_3BYTE_BGR || oriented.type == BufferedImage.TYPE_INT_RGB) oriented
            else BufferedImage(oriented.width, oriented.height, BufferedImage.TYPE_INT_RGB).also { it.createGraphics().apply { drawImage(oriented, 0, 0, null); dispose() } }
            val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
            val param = writer.defaultWriteParam.also { it.compressionMode = ImageWriteParam.MODE_EXPLICIT; it.compressionQuality = 0.92f }
            ImageIO.createImageOutputStream(out).use { ios -> writer.output = ios; writer.write(null, IIOImage(rgb, null, null), param); writer.dispose() }
        }
        return out.toByteArray()
    }

    private fun rotate(img: BufferedImage, orientation: Int): BufferedImage {
        val w = img.width; val h = img.height
        val (t, nw, nh) = when (orientation) {
            3 -> Triple(AffineTransform(-1.0, 0.0, 0.0, -1.0, w.toDouble(), h.toDouble()), w, h)
            6 -> Triple(AffineTransform(0.0, 1.0, -1.0, 0.0, h.toDouble(), 0.0), h, w)
            8 -> Triple(AffineTransform(0.0, -1.0, 1.0, 0.0, 0.0, w.toDouble()), h, w)
            else -> return img
        }
        val dest = BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB)
        AffineTransformOp(t, AffineTransformOp.TYPE_NEAREST_NEIGHBOR).filter(img.let { s -> if (s.type == BufferedImage.TYPE_INT_RGB) s else BufferedImage(w, h, BufferedImage.TYPE_INT_RGB).also { c -> c.createGraphics().apply { drawImage(s, 0, 0, null); dispose() } } }, dest)
        return dest
    }
}

package de.taktovia.fleetsafety.api

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * Medienprüfung der Schadenfotos. [basic] ist **kein Virenscanner**: sie erkennt die EICAR-Testsignatur und ausführbare Dateien, dekodiert das Bild (kein Bild, übergroße
 * Abmessungen: ungültig) und kodiert es neu, wodurch EXIF- und andere Metadaten samt Standort entfallen (OP-31). Ein echter Scanner (ClamAV, WP-M4-21) wird über [extraScan]
 * davor geschaltet; liefert er `null`, gilt die Datei als vom Scanner unbeanstandet, sonst entscheidet sein Urteil (Infected oder Unavailable bleiben maßgeblich).
 */
object MediaInspectors {
    const val EICAR = "X5O!P%@AP[4\\PZX54(P^)7CC)7}\$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!\$H+H*"
    const val MAX_PIXELS = 50_000_000L

    fun basic(extraScan: ((ByteArray) -> MediaVerdict?)? = null, engine: String = "basic-media"): MediaInspector = object : MediaInspector {
        override val engine = engine
        override fun inspect(bytes: ByteArray, mime: String): MediaVerdict {
            extraScan?.invoke(bytes)?.let { return it }
            if (String(bytes, Charsets.ISO_8859_1).contains(EICAR)) return MediaVerdict.Infected("Eicar-Test-Signature")
            if (isExecutable(bytes)) return MediaVerdict.Infected("ausfuehrbare-Datei")
            return try { MediaVerdict.Clean(reencode(bytes, mime)) } catch (e: IllegalArgumentException) { MediaVerdict.Invalid(e.message ?: "kein-gueltiges-Bild") }
        }
    }

    /** Tatsächlicher Typ an den Anfangsbytes: JPEG oder PNG, sonst `null`. */
    fun sniff(b: ByteArray): String? = when {
        b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() -> "image/jpeg"
        b.size >= 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte() &&
            b[4] == 0x0D.toByte() && b[5] == 0x0A.toByte() && b[6] == 0x1A.toByte() && b[7] == 0x0A.toByte() -> "image/png"
        else -> null
    }

    private fun isExecutable(b: ByteArray) =
        (b.size >= 2 && b[0] == 'M'.code.toByte() && b[1] == 'Z'.code.toByte()) || (b.size >= 4 && b[0] == 0x7F.toByte() && b[1] == 'E'.code.toByte() && b[2] == 'L'.code.toByte() && b[3] == 'F'.code.toByte()) ||
            (b.size >= 2 && b[0] == '#'.code.toByte() && b[1] == '!'.code.toByte())

    private fun reencode(bytes: ByteArray, mime: String): ByteArray {
        val img = try { ImageIO.read(ByteArrayInputStream(bytes)) } catch (e: Exception) { null } ?: throw IllegalArgumentException("kein-gueltiges-Bild")
        if (img.width.toLong() * img.height > MAX_PIXELS) throw IllegalArgumentException("Abmessungen-zu-gross")
        val out = ByteArrayOutputStream()
        if (mime == "image/png") {
            if (!ImageIO.write(img, "png", out)) throw IllegalArgumentException("PNG-nicht-schreibbar")
        } else {
            val rgb = if (img.type == BufferedImage.TYPE_3BYTE_BGR || img.type == BufferedImage.TYPE_INT_RGB) img else BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_RGB).also { it.createGraphics().apply { drawImage(img, 0, 0, null); dispose() } }
            val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
            val param = writer.defaultWriteParam.also { it.compressionMode = ImageWriteParam.MODE_EXPLICIT; it.compressionQuality = 0.92f }
            ImageIO.createImageOutputStream(out).use { ios -> writer.output = ios; writer.write(null, IIOImage(rgb, null, null), param); writer.dispose() }
        }
        return out.toByteArray()
    }
}

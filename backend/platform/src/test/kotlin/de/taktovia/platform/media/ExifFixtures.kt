package de.taktovia.platform.media

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import javax.imageio.ImageIO

/** Testbilder mit echter EXIF-Struktur (TIFF-Verzeichnisse: Kamera, GPS-Verzeichnis, Vorschau mit eigenem EXIF samt GPS), nicht nur einem Textplatzhalter. */
object ExifFixtures {
    fun pixels(w: Int = 24, h: Int = 16): BufferedImage = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB).also { for (x in 0 until w) for (y in 0 until h) it.setRGB(x, y, if (x < w / 2) 0xCC2222 else 0x2222CC) }
    fun plainJpeg(w: Int = 24, h: Int = 16): ByteArray = ByteArrayOutputStream().also { ImageIO.write(pixels(w, h), "jpg", it) }.toByteArray()
    fun plainPng(): ByteArray = ByteArrayOutputStream().also { ImageIO.write(pixels(), "png", it) }.toByteArray()

    /** TIFF-Block (big endian): IFD0 mit Hersteller, Orientierung, GPS-Zeiger; GPS-IFD mit Breite; optional IFD1 mit JPEG-Vorschau [thumb]. */
    fun tiff(orientation: Int = 1, thumb: ByteArray? = null): ByteArray {
        val b = ByteBuffer.allocate(4096)
        b.put("MM".toByteArray()); b.putShort(0x2A); b.putInt(8)
        b.putShort(3)
        b.putShort(0x010F); b.putShort(2); b.putInt(7); b.putInt(50)                  // Make "Pixel9"
        b.putShort(0x0112); b.putShort(3); b.putInt(1); b.putShort(orientation.toShort()); b.putShort(0)
        b.putShort(0x8825.toShort()); b.putShort(4); b.putInt(1); b.putInt(58)        // GPS-IFD
        b.putInt(if (thumb != null) 112 else 0)
        b.position(50); b.put("Pixel9\u0000\u0000".toByteArray())
        b.position(58); b.putShort(2)
        b.putShort(1); b.putShort(2); b.putInt(2); b.put('N'.code.toByte()); b.put(0); b.putShort(0)   // GPSLatitudeRef
        b.putShort(2); b.putShort(5); b.putInt(3); b.putInt(88)                         // GPSLatitude
        b.putInt(0)
        b.position(88); for (v in intArrayOf(48, 1, 7, 1, 30, 1)) b.putInt(v)
        if (thumb != null) {
            b.position(112); b.putShort(3)
            b.putShort(0x0103); b.putShort(3); b.putInt(1); b.putShort(6); b.putShort(0)
            b.putShort(0x0201); b.putShort(4); b.putInt(1); b.putInt(154)
            b.putShort(0x0202); b.putShort(4); b.putInt(1); b.putInt(thumb.size)
            b.putInt(0)
            b.position(154); b.put(thumb)
        }
        val len = if (thumb != null) 154 + thumb.size else 112
        return b.array().copyOf(len)
    }

    private fun app1(tiff: ByteArray): ByteArray {
        val payload = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + tiff
        val len = payload.size + 2
        return byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (len shr 8).toByte(), len.toByte()) + payload
    }

    /** JPEG mit EXIF (Kamera, GPS), Vorschau mit eigenem GPS-EXIF, einem Kommentar und einem XMP-Paket. */
    fun jpegWithAllMetadata(orientation: Int = 1, w: Int = 24, h: Int = 16): ByteArray {
        val inner = plainJpeg(8, 6).let { it.copyOfRange(0, 2) + app1(tiff()) + it.copyOfRange(2, it.size) }
        val base = plainJpeg(w, h)
        val xmp = "http://ns.adobe.com/xap/1.0/\u0000<x:xmpmeta/>".toByteArray(Charsets.ISO_8859_1)
        val xmpSeg = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), ((xmp.size + 2) shr 8).toByte(), (xmp.size + 2).toByte()) + xmp
        val com = "Aufnahme zu Hause".toByteArray(); val comSeg = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), ((com.size + 2) shr 8).toByte(), (com.size + 2).toByte()) + com
        return base.copyOfRange(0, 2) + app1(tiff(orientation, inner)) + xmpSeg + comSeg + base.copyOfRange(2, base.size)
    }

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val crc = CRC32().also { it.update(type.toByteArray()); it.update(data) }.value
        return ByteBuffer.allocate(12 + data.size).putInt(data.size).put(type.toByteArray()).put(data).putInt(crc.toInt()).array()
    }

    /** PNG mit eXIf (Kamera, GPS) und einem tEXt-Chunk (Kommentar) hinter dem IHDR. */
    fun pngWithMetadata(): ByteArray {
        val base = plainPng(); val ihdrEnd = 8 + 12 + 13
        return base.copyOfRange(0, ihdrEnd) + chunk("eXIf", tiff()) + chunk("tEXt", "Comment\u0000Ort Zuhause".toByteArray()) + base.copyOfRange(ihdrEnd, base.size)
    }
}

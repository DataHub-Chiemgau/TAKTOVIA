package de.taktovia.platform.media

/**
 * Befund über Metadaten in einem JPEG oder PNG (WP-M5-20, AT-59): welche Metadatenklassen die Datei trägt. Eigener, kleiner Parser der Containerstruktur (JPEG-Segmente, PNG-Chunks,
 * TIFF-Verzeichnisse der EXIF-Daten), **unabhängig vom Bildkodierer**, damit der Nachweis „bereinigt“ nicht vom Bereinigungscode selbst stammt. Nur Struktur, keine Werte: der Befund
 * enthält nie Koordinaten oder Gerätekennungen.
 */
data class ImageMetadataReport(
    /** EXIF-Block vorhanden (JPEG APP1 „Exif“, PNG `eXIf`). */
    val exif: Boolean = false,
    /** EXIF enthält ein GPS-Verzeichnis (Tag 0x8825): Standort der Aufnahme. */
    val gps: Boolean = false,
    /** EXIF enthält Hersteller, Modell oder Software der Kamera (Tags 0x010F, 0x0110, 0x0131) oder Seriennummern (0xA431, 0xA433 ... über IFD0/Exif-IFD). */
    val device: Boolean = false,
    /** Eingebettete Vorschau (zweites Verzeichnis IFD1 mit JPEG-Daten), die ihrerseits Metadaten tragen kann. */
    val embeddedThumbnail: Boolean = false,
    /** XMP-Paket (JPEG APP1 mit XMP-Kennung, PNG iTXt `XML:com.adobe.xmp`). */
    val xmp: Boolean = false,
    /** IPTC/Photoshop-Ressourcen (JPEG APP13). */
    val iptc: Boolean = false,
    /** Kommentare und Textchunks (JPEG COM, PNG tEXt/zTXt/iTXt, PNG tIME). */
    val textComments: Boolean = false,
    /** Orientierung laut EXIF (1 bis 8), `null` ohne Angabe. */
    val orientation: Int? = null,
) {
    /** Keine der betrachteten Metadatenklassen vorhanden. */
    val clean: Boolean get() = !exif && !gps && !device && !embeddedThumbnail && !xmp && !iptc && !textComments
    val classes: Set<String> get() = buildSet {
        if (exif) add("exif"); if (gps) add("gps"); if (device) add("device"); if (embeddedThumbnail) add("thumbnail"); if (xmp) add("xmp"); if (iptc) add("iptc"); if (textComments) add("text")
    }
}

object ImageMetadata {
    fun inspect(b: ByteArray): ImageMetadataReport = when {
        b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> jpeg(b)
        b.size >= 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() -> png(b)
        else -> ImageMetadataReport()
    }

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun be16(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)
    private fun be32(b: ByteArray, i: Int) = (be16(b, i).toLong() shl 16) or be16(b, i + 2).toLong()
    private fun starts(b: ByteArray, at: Int, s: String) = at + s.length <= b.size && s.indices.all { b[at + it] == s[it].code.toByte() }

    private fun jpeg(b: ByteArray): ImageMetadataReport {
        var r = ImageMetadataReport(); var i = 2
        while (i + 4 <= b.size) {
            if (u8(b, i) != 0xFF) { i++; continue }
            val marker = u8(b, i + 1)
            if (marker == 0xFF) { i++; continue }
            if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) { i += 2; continue }
            if (marker == 0xDA || marker == 0xD9) break
            val len = be16(b, i + 2); val body = i + 4; val end = minOf(b.size, i + 2 + len)
            when (marker) {
                0xE1 -> if (starts(b, body, "Exif\u0000\u0000")) r = tiff(b, body + 6, end, r) else if (starts(b, body, "http://ns.adobe.com/xap/1.0/")) r = r.copy(xmp = true)
                0xED -> r = r.copy(iptc = true)
                0xFE -> r = r.copy(textComments = true)
            }
            i = i + 2 + len
        }
        return r
    }

    /** TIFF-Kopf des EXIF-Blocks ab [start]: IFD0 (GPS-Zeiger, Gerät, Orientierung) und IFD1 (Vorschau). Defekte Strukturen werden als „EXIF vorhanden“ gemeldet, nie als sauber. */
    private fun tiff(b: ByteArray, start: Int, end: Int, base: ImageMetadataReport): ImageMetadataReport {
        var r = base.copy(exif = true)
        try {
            val little = starts(b, start, "II"); if (!little && !starts(b, start, "MM")) return r
            fun r16(o: Int) = if (little) u8(b, o) or (u8(b, o + 1) shl 8) else be16(b, o)
            fun r32(o: Int): Long = if (little) r16(o).toLong() or (r16(o + 2).toLong() shl 16) else be32(b, o)
            fun scan(ifdOff: Long, first: Boolean): Long {
                val o = start + ifdOff.toInt(); if (o + 2 > end) return 0
                val n = r16(o)
                for (k in 0 until n) {
                    val e = o + 2 + k * 12; if (e + 12 > end) break
                    when (r16(e)) {
                        0x8825 -> if (first) r = r.copy(gps = true)
                        0x010F, 0x0110, 0x0131, 0xA431, 0xA433, 0xA434, 0xC62F, 0xA430, 0xA435 -> if (first) r = r.copy(device = true)
                        0x0112 -> if (first && r16(e + 2) == 3) r = r.copy(orientation = r16(e + 8).takeIf { it in 1..8 })
                        0x8769 -> if (first) { val sub = r32(e + 8); val so = start + sub.toInt(); if (so + 2 <= end) {
                            val sn = r16(so); for (q in 0 until sn) { val se = so + 2 + q * 12; if (se + 12 > end) break
                                if (r16(se) in setOf(0xA431, 0xA433, 0xA434, 0xA430, 0xA435)) r = r.copy(device = true) } } }
                    }
                }
                val next = o + 2 + n * 12; return if (next + 4 <= end) r32(next) else 0
            }
            val ifd1 = scan(r32(start + 4), true)
            if (ifd1 > 0) { r = r.copy(embeddedThumbnail = true); scan(ifd1, false) }
        } catch (_: RuntimeException) { /* defekter Block bleibt als „EXIF vorhanden“ gemeldet */ }
        return r
    }

    private fun png(b: ByteArray): ImageMetadataReport {
        var r = ImageMetadataReport(); var i = 8
        while (i + 12 <= b.size) {
            val len = be32(b, i).toInt(); val type = String(b, i + 4, 4, Charsets.ISO_8859_1)
            when (type) {
                "eXIf" -> r = tiff(b, i + 8, minOf(b.size, i + 8 + len), r)
                "tEXt", "zTXt" -> r = r.copy(textComments = true)
                "iTXt" -> r = if (starts(b, i + 8, "XML:com.adobe.xmp")) r.copy(xmp = true) else r.copy(textComments = true)
                "tIME" -> r = r.copy(textComments = true)
                "IEND" -> return r
            }
            if (len < 0) break
            i += 12 + len
        }
        return r
    }
}

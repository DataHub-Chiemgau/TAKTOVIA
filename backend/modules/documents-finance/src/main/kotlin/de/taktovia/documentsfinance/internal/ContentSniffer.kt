package de.taktovia.documentsfinance.internal

/** Erkennt den tatsächlichen Dateityp an den Anfangsbytes (nicht am deklarierten MIME-Typ): JPEG, PNG, PDF; sonst `null`. */
internal object ContentSniffer {
    fun detect(b: ByteArray): String? {
        val head = String(b, 0, minOf(b.size, 1024), Charsets.ISO_8859_1)
        return when {
        b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() -> "image/jpeg"
        b.size >= 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte() &&
            b[4] == 0x0D.toByte() && b[5] == 0x0A.toByte() && b[6] == 0x1A.toByte() && b[7] == 0x0A.toByte() -> "image/png"
        head.contains("%PDF-") -> "application/pdf"
        else -> null
        }
    }
}

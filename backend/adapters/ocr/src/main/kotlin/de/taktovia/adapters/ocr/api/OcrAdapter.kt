package de.taktovia.adapters.ocr.api

import de.taktovia.adapters.ocr.internal.PdfTextLayerReader

/** Erkannte Zeile mit Konfidenz (0 bis 1) und Fundstelle (Seite ab 1, Koordinaten in PDF-Einheiten, Ursprung unten links). */
data class OcrLine(val text: String, val confidence: Double, val page: Int, val x: Double, val y: Double, val width: Double, val height: Double)

class OcrRecognition(val lines: List<OcrLine>)

/** Die Engine kann diesen Inhalt nicht lesen (z. B. Rasterbild ohne Bild-OCR, PDF ohne Textebene). Es werden keine Felder geraten. */
class OcrUnsupportedException(message: String) : RuntimeException(message)

/** Die Engine hat nicht rechtzeitig geantwortet; der Aufrufer darf wiederholen. */
class OcrTimeoutException(message: String = "OCR-Zeitüberschreitung") : RuntimeException(message)

/**
 * Port des Adapters `ocr` (WP-M4-27): providerunabhängige Schnittstelle zu einer OCR-Engine. Die Anwendung bindet einen Adapter an den Port des Belegmoduls. Aufrufe nie innerhalb einer
 * offenen SQL-Transaktion (Leitlinie 3). Erkannter Text ist ausschließlich Datum, nie Anweisung (Kap. 03 §3 Nr. 2).
 */
interface OcrAdapter {
    val engine: String
    val version: String
    fun recognize(content: ByteArray, mime: String): OcrRecognition
}

/**
 * Lokale Umsetzung ohne externen Dienst: liest die **Textebene** textbasierter PDF-Belege (digitale Kassenbons, Rechnungen als PDF); keine Bild-OCR. Rasterbilder (JPEG, PNG) und PDFs
 * ohne Textebene lösen [OcrUnsupportedException] aus. Die Auswahl einer Bild-OCR-Engine und die Datenschutzprüfung bei einem externen Dienst sind offen (D-06, Offener Punkt 11);
 * dieser Adapter ersetzt sie nicht, er macht nur den Pfad mit echten Dateien ausführbar. Konfidenz der Textebene: 0,99 (der Text ist exakt, die Zuordnung zum Feld ist es nicht).
 */
class PdfTextLayerOcr : OcrAdapter {
    override val engine = "pdf-text-layer"
    override val version = "1"

    override fun recognize(content: ByteArray, mime: String): OcrRecognition {
        if (mime != "application/pdf") throw OcrUnsupportedException("Rasterbild: keine Bild-OCR-Engine angebunden")
        val lines = PdfTextLayerReader.read(content)
        if (lines.isEmpty()) throw OcrUnsupportedException("PDF ohne Textebene")
        return OcrRecognition(lines)
    }
}

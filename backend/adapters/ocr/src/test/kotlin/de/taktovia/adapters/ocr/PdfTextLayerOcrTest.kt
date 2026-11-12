package de.taktovia.adapters.ocr

import de.taktovia.adapters.ocr.api.OcrUnsupportedException
import de.taktovia.adapters.ocr.api.PdfTextLayerOcr
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

/** WP-M4-27: der lokale Textebenen-Adapter liest echte (hier erzeugte) PDF-Dateien, unkomprimiert und Flate, und weist Rasterbilder und PDFs ohne Text ab. */
class PdfTextLayerOcrTest {
    private val ocr = PdfTextLayerOcr()

    /** Minimales PDF mit einem Textobjekt je Zeile (`Td` absolut), optional Flate-komprimiert. */
    private fun pdf(vararg lines: String, flate: Boolean = false, tj: Boolean = false): ByteArray {
        val body = buildString {
            append("BT /F1 10 Tf 12 TL 50 780 Td\n")
            lines.forEachIndexed { i, l ->
                val esc = l.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
                if (i > 0) append("T*\n")
                append(if (tj) "[($esc)] TJ\n" else "($esc) Tj\n")
            }
            append("ET\n")
        }.toByteArray(Charsets.ISO_8859_1)
        val data = if (flate) ByteArrayOutputStream().also { o -> val d = Deflater(); d.setInput(body); d.finish(); val b = ByteArray(4096); while (!d.finished()) o.write(b, 0, d.deflate(b)); d.end() }.toByteArray() else body
        val head = "%PDF-1.4\n1 0 obj\n<< /Length ${data.size}${if (flate) " /Filter /FlateDecode" else ""} >>\nstream\n".toByteArray(Charsets.ISO_8859_1)
        return head + data + "\nendstream\nendobj\ntrailer\n<< >>\n%%EOF\n".toByteArray(Charsets.ISO_8859_1)
    }

    @Test
    fun liest_zeilen_in_leserichtung_aus_unkomprimierten_und_komprimierten_pdfs() {
        for (flate in listOf(false, true)) for (tj in listOf(false, true)) {
            val r = ocr.recognize(pdf("Tankstelle Mustermann", "Datum: 14.03.2026", "SUMME EUR 39,00 (brutto)", flate = flate, tj = tj), "application/pdf")
            assertEquals(listOf("Tankstelle Mustermann", "Datum: 14.03.2026", "SUMME EUR 39,00 (brutto)"), r.lines.map { it.text }, "flate=$flate tj=$tj")
            assertTrue(r.lines.all { it.confidence in 0.9..1.0 && it.page == 1 && it.height == 10.0 })
            assertTrue(r.lines[0].y > r.lines[1].y && r.lines[1].y > r.lines[2].y, "von oben nach unten")
            assertEquals(50.0, r.lines[0].x)
        }
    }

    @Test
    fun umlaute_euro_und_hexketten_werden_in_windows_1252_gelesen() {
        val content = "BT /F1 10 Tf 40 700 Td <4B E4 73 65 20 80> Tj ET".toByteArray(Charsets.ISO_8859_1)
        val bytes = "%PDF-1.4\n1 0 obj\n<< /Length ${content.size} >>\nstream\n".toByteArray(Charsets.ISO_8859_1) + content + "\nendstream\nendobj\n".toByteArray(Charsets.ISO_8859_1)
        assertEquals("Käse €", ocr.recognize(bytes, "application/pdf").lines.single().text)
    }

    @Test
    fun rasterbild_und_pdf_ohne_textebene_sind_nicht_lesbar_und_es_wird_nichts_geraten() {
        assertThrows(OcrUnsupportedException::class.java) { ocr.recognize(ByteArray(2000) { 3 }, "image/jpeg") }
        assertThrows(OcrUnsupportedException::class.java) { ocr.recognize("%PDF-1.4\n1 0 obj\n<< /Length 0 >>\nstream\nendstream\nendobj\n".toByteArray(), "application/pdf") }
        assertThrows(OcrUnsupportedException::class.java) { ocr.recognize("kein pdf".toByteArray(), "application/pdf") }
    }

    @Test
    fun defekter_flate_strom_fuehrt_nicht_zum_absturz() {
        val bytes = "%PDF-1.4\n1 0 obj\n<< /Length 5 /Filter /FlateDecode >>\nstream\nxxxxx\nendstream\nendobj\n".toByteArray()
        assertThrows(OcrUnsupportedException::class.java) { ocr.recognize(bytes, "application/pdf") }
    }
}

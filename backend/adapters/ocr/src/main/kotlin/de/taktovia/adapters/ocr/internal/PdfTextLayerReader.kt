package de.taktovia.adapters.ocr.internal

import de.taktovia.adapters.ocr.api.OcrLine
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Liest die Textebene einfacher PDF-Dateien: Inhaltsströme (unkomprimiert oder FlateDecode), Textobjekte `BT … ET` mit `Td`, `TD`, `Tm`, `T*`, `TL`, `Tj`, `TJ`, `'`. Zeichenketten in
 * Windows-1252 (Literale und Hex). Texte derselben Grundlinie werden zu einer Zeile verbunden. Nicht unterstützt: eingebettete Schriften mit eigener Zeichenkodierung (CMap), verschlüsselte PDFs.
 */
internal object PdfTextLayerReader {
    private val CP1252 = Charset.forName("windows-1252")
    private const val MAX_STREAM = 8 * 1024 * 1024

    private class Run(val page: Int, val x: Double, val y: Double, val size: Double, val text: String)

    fun read(pdf: ByteArray): List<OcrLine> {
        val text = String(pdf, Charsets.ISO_8859_1)
        val runs = ArrayList<Run>()
        var page = 0
        var pos = 0
        while (true) {
            val s = text.indexOf("stream", pos).takeIf { it >= 0 } ?: break
            if (s >= 3 && text.startsWith("end", s - 3)) { pos = s + 6; continue }   // „endstream“
            var start = s + 6
            if (text.startsWith("\r\n", start)) start += 2 else if (text.startsWith("\n", start) || text.startsWith("\r", start)) start += 1
            val end = text.indexOf("endstream", start).takeIf { it >= 0 } ?: break
            val dictStart = text.lastIndexOf("obj", s).takeIf { it >= 0 } ?: 0
            val dict = text.substring(dictStart, s)
            var data = pdf.copyOfRange(start, end)
            if (dict.contains("/FlateDecode")) data = inflate(data) ?: ByteArray(0)
            val content = String(data, Charsets.ISO_8859_1)
            if (content.contains("BT") && content.contains("ET")) {
                val found = parseContent(content, page + 1)
                if (found.isNotEmpty()) { runs += found; page++ }
            }
            pos = end + 9
        }
        return merge(runs)
    }

    private fun inflate(data: ByteArray): ByteArray? {
        val inf = Inflater(); inf.setInput(data)
        val out = ByteArrayOutputStream(); val buf = ByteArray(8192)
        try {
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                out.write(buf, 0, n)
                if (out.size() > MAX_STREAM) return null   // Schutz vor Dekompressionsbomben
            }
        } catch (e: DataFormatException) { return null } finally { inf.end() }
        return out.toByteArray()
    }

    private fun merge(runs: List<Run>): List<OcrLine> {
        val out = ArrayList<OcrLine>()
        for ((page, onPage) in runs.groupBy { it.page }.toSortedMap()) {
            val rows = ArrayList<MutableList<Run>>()
            for (r in onPage.sortedWith(compareByDescending<Run> { it.y }.thenBy { it.x })) {
                val row = rows.lastOrNull()
                if (row != null && Math.abs(row.first().y - r.y) < 1.0) row += r else rows += mutableListOf(r)
            }
            for (row in rows) {
                val ordered = row.sortedBy { it.x }
                val t = ordered.joinToString(" ") { it.text.trim() }.trim()
                if (t.isEmpty()) continue
                val x0 = ordered.first().x; val size = ordered.maxOf { it.size }
                val width = ordered.last().x + ordered.last().text.length * size * 0.5 - x0
                out += OcrLine(t, 0.99, page, x0, ordered.first().y, width, size)
            }
        }
        return out
    }

    private sealed interface Tok
    private class Num(val v: Double) : Tok
    private class Str(val v: String) : Tok
    private class Op(val v: String) : Tok
    private class Arr(val items: List<Tok>) : Tok

    private fun parseContent(c: String, page: Int): List<Run> {
        val toks = tokenize(c)
        val out = ArrayList<Run>()
        var inText = false
        var x = 0.0; var y = 0.0; var lx = 0.0; var ly = 0.0; var leading = 0.0; var size = 10.0
        val stack = ArrayList<Tok>()
        fun show(t: String) { if (inText && t.isNotEmpty()) out += Run(page, x, y, size, t) }
        fun nextLine() { ly -= leading; lx = lx; x = lx; y = ly }
        for (t in toks) {
            if (t !is Op) { stack += t; continue }
            val nums = stack.filterIsInstance<Num>().map { it.v }
            when (t.v) {
                "BT" -> { inText = true; x = 0.0; y = 0.0; lx = 0.0; ly = 0.0 }
                "ET" -> inText = false
                "Tf" -> nums.lastOrNull()?.let { if (it > 0) size = it }
                "TL" -> nums.lastOrNull()?.let { leading = it }
                "Td" -> if (nums.size >= 2) { lx += nums[nums.size - 2]; ly += nums.last(); x = lx; y = ly }
                "TD" -> if (nums.size >= 2) { leading = -nums.last(); lx += nums[nums.size - 2]; ly += nums.last(); x = lx; y = ly }
                "Tm" -> if (nums.size >= 6) { lx = nums[nums.size - 2]; ly = nums.last(); x = lx; y = ly }
                "T*" -> nextLine()
                "Tj" -> (stack.lastOrNull() as? Str)?.let { show(it.v) }
                "'" -> { nextLine(); (stack.lastOrNull() as? Str)?.let { show(it.v) } }
                "TJ" -> (stack.lastOrNull() as? Arr)?.let { a -> show(a.items.filterIsInstance<Str>().joinToString("") { it.v }) }
            }
            stack.clear()
        }
        return out
    }

    private fun tokenize(c: String): List<Tok> = Lexer(c).all()

    /** Zeichenweiser Zerleger des Inhaltsstroms: Zahlen, Literale `( )` mit Escapes und Verschachtelung, Hexketten `< >`, Felder `[ ]`, Namen und Operatoren. */
    private class Lexer(private val c: String) {
        private var i = 0
        private val n = c.length

        fun all(): List<Tok> { val out = ArrayList<Tok>(); while (true) out += (next() ?: break); return out }

        private fun skipSpace() { while (i < n && c[i].isWhitespace()) i++ }

        fun next(): Tok? {
            skipSpace()
            if (i >= n) return null
            val ch = c[i]
            return when {
                ch == '(' -> Str(String(literal().toByteArray(Charsets.ISO_8859_1), CP1252))
                ch == '<' && i + 1 < n && c[i + 1] != '<' -> Str(hex())
                ch == '<' || ch == '>' -> { i += if (i + 1 < n && c[i + 1] == ch) 2 else 1; Op("dict") }
                ch == '[' -> { i++; val items = ArrayList<Tok>(); while (true) { skipSpace(); if (i >= n) break; if (c[i] == ']') { i++; break }; items += (next() ?: break) }; Arr(items) }
                ch == ']' -> { i++; Op("]") }
                ch == '/' -> { i++; while (i < n && !c[i].isWhitespace() && c[i] !in "/[]()<>%") i++; Op("/name") }
                ch == '%' -> { while (i < n && c[i] != '\n' && c[i] != '\r') i++; next() }
                else -> {
                    val s = i
                    while (i < n && !c[i].isWhitespace() && c[i] !in "/[]()<>%") i++
                    if (i == s) { i++; return Op("?") }
                    val w = c.substring(s, i)
                    w.toDoubleOrNull()?.let { Num(it) } ?: Op(w)
                }
            }
        }

        private fun literal(): String {
            i++   // '('
            val sb = StringBuilder(); var depth = 1
            while (i < n && depth > 0) {
                val ch = c[i++]
                when {
                    ch == '\\' && i < n -> {
                        val e = c[i++]
                        when (e) {
                            'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t'); 'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                            '(', ')', '\\' -> sb.append(e)
                            '\r' -> if (i < n && c[i] == '\n') i++
                            '\n' -> {}
                            in '0'..'7' -> { var v = e - '0'; var k = 0; while (k < 2 && i < n && c[i] in '0'..'7') { v = v * 8 + (c[i++] - '0'); k++ }; sb.append((v and 0xFF).toChar()) }
                            else -> sb.append(e)
                        }
                    }
                    ch == '(' -> { depth++; sb.append(ch) }
                    ch == ')' -> { depth--; if (depth > 0) sb.append(ch) }
                    else -> sb.append(ch)
                }
            }
            return sb.toString()
        }

        private fun hex(): String {
            i++   // '<'
            val sb = StringBuilder()
            while (i < n && c[i] != '>') { if (!c[i].isWhitespace()) sb.append(c[i]); i++ }
            i++
            if (sb.length % 2 == 1) sb.append('0')
            val bytes = ByteArray(sb.length / 2) { k -> sb.substring(2 * k, 2 * k + 2).toIntOrNull(16)?.toByte() ?: 0 }
            return if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE) else String(bytes, CP1252)
        }
    }
}

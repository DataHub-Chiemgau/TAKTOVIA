package de.taktovia.documentsfinance.internal

import de.taktovia.documentsfinance.api.ExtractedFieldKind
import de.taktovia.documentsfinance.api.RecognitionResult
import de.taktovia.documentsfinance.api.RecognizedLine
import de.taktovia.documentsfinance.api.SourceLocation
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** Ein erkannter Feldvorschlag. `normalized == null` = nicht sichtbar, nichts geraten. */
internal class ParsedField(
    val kind: ExtractedFieldKind, val occurrence: Int, val raw: String?, val normalized: String?, val qualifier: String?, val confidence: Double?, val source: SourceLocation?, val suspicious: Boolean,
)

/**
 * Feldextraktion aus erkanntem Beleg-Text (WP-M4-27): Belegdatum, Betrag, Währung, Händler, Belegnummer, Mehrwertsteuerbeträge. Regelbasiert und deterministisch. Ein Wert wird nur
 * übernommen, wenn eine eindeutige Fundstelle vorliegt (Schlüsselwort bzw. Format); sonst bleibt das Feld leer. Die Konfidenz ist die der Zeile, bei schwächerer Fundstelle gemindert.
 * Der Text wird nie interpretiert oder ausgeführt, nur gelesen.
 */
internal object ReceiptFieldParser {
    private val AMOUNT = Regex("(?<![0-9.,])([0-9]{1,3}(?:\\.[0-9]{3})*|[0-9]+)[,.]([0-9]{2})(?![0-9]|[.,][0-9])")
    private val TOTAL_KEY = Regex("(?i)\\b(gesamt(betrag|summe)?|summe|total|zu\\s+zahlen|zahlbetrag|endbetrag|rechnungsbetrag|brutto(betrag)?|betrag)\\b")
    private val VAT_KEY = Regex("(?i)(mwst|mehrwertsteuer|ust|umsatzsteuer|vat)")
    private val RATE = Regex("(?<![0-9])([0-9]{1,2})(?:[,.]([0-9]{1,2}))?\\s*%")
    private val DATE_DE = Regex("(?<![0-9])([0-3]?[0-9])\\.([01]?[0-9])\\.((?:19|20)?[0-9]{2})(?![0-9])")
    private val DATE_ISO = Regex("(?<![0-9])((?:19|20)[0-9]{2})-([01][0-9])-([0-3][0-9])(?![0-9])")
    private val NUMBER = Regex("(?i)\\b(?:(?:beleg|bon|rechnung|rechnungs|quittung|quittungs|transaktions)[\\s-]*(?:nr|nummer)|receipt\\s*(?:no|number|nr)|invoice\\s*(?:no|number|nr))\\b\\.?\\s*[:#]?\\s*([A-Za-z0-9][A-Za-z0-9\\-/]{2,30})")
    private val CURRENCY = Regex("(?i)(?<![A-Za-z])(EUR|CHF|USD|GBP)(?![A-Za-z])|€")
    private val HAS_LETTERS = Regex("\\p{L}{3,}")
    private val NOT_MERCHANT = Regex("(?i)(^\\s*[0-9.,:/ -]+\\s*$|\\b(datum|uhrzeit|beleg|bon|rechnung|kasse|tel|telefon|fax|www|http|ust-?id|steuernr|str\\.|straße|strasse|plz)\\b)")

    fun parse(r: RecognitionResult): List<ParsedField> {
        val lines = r.lines.filter { it.text.isNotBlank() }
        val out = ArrayList<ParsedField>()
        out += merchant(lines); out += date(lines); out += number(lines)
        val total = amount(lines)
        out += total.first; out += currency(lines, total.second); out += vat(lines)
        return out
    }

    private fun loc(l: RecognizedLine) = SourceLocation(l.page, l.x, l.y, l.width, l.height)
    private fun conf(l: RecognizedLine, factor: Double = 1.0) = Math.round(l.confidence * factor * 1000.0) / 1000.0
    private fun empty(k: ExtractedFieldKind) = ParsedField(k, 0, null, null, null, null, null, false)
    private fun found(k: ExtractedFieldKind, occ: Int, l: RecognizedLine, raw: String, norm: String, q: String? = null, factor: Double = 1.0) =
        ParsedField(k, occ, raw.take(500), norm, q, conf(l, factor), loc(l), Neutralizer.looksActive(l.text))

    private fun merchant(lines: List<RecognizedLine>): ParsedField {
        val l = lines.take(6).firstOrNull { HAS_LETTERS.containsMatchIn(it.text) && !NOT_MERCHANT.containsMatchIn(it.text) && it.text.trim().length <= 80 } ?: return empty(ExtractedFieldKind.MERCHANT)
        return found(ExtractedFieldKind.MERCHANT, 0, l, l.text.trim(), l.text.trim(), factor = 0.9)   // erste Kopfzeile: Annahme, daher gemindert
    }

    private fun date(lines: List<RecognizedLine>): ParsedField {
        val candidates = lines.flatMap { l -> (DATE_DE.findAll(l.text).map { l to it } + DATE_ISO.findAll(l.text).map { l to it }).toList() }
        for ((l, m) in candidates.sortedBy { (l, _) -> if (Regex("(?i)datum|date").containsMatchIn(l.text)) 0 else 1 }) {
            val d = try {
                if (m.groupValues.size == 4 && m.value.contains('-')) LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
                else { val y = m.groupValues[3].let { if (it.length == 2) 2000 + it.toInt() else it.toInt() }; LocalDate.of(y, m.groupValues[2].toInt(), m.groupValues[1].toInt()) }
            } catch (e: DateTimeParseException) { null } catch (e: java.time.DateTimeException) { null }
            if (d != null) return found(ExtractedFieldKind.DOCUMENT_DATE, 0, l, m.value, d.toString(), factor = if (Regex("(?i)datum|date").containsMatchIn(l.text)) 1.0 else 0.95)
        }
        return empty(ExtractedFieldKind.DOCUMENT_DATE)
    }

    private fun number(lines: List<RecognizedLine>): ParsedField {
        for (l in lines) { val m = NUMBER.find(l.text) ?: continue; return found(ExtractedFieldKind.DOCUMENT_NUMBER, 0, l, m.groupValues.last(), m.groupValues.last()) }
        return empty(ExtractedFieldKind.DOCUMENT_NUMBER)
    }

    private fun minor(m: MatchResult): Long = (m.groupValues[1].replace(".", "") + m.groupValues[2]).toLong()

    /** Betrag aus einer Summenzeile (Schlüsselwort Pflicht; Mehrwertsteuerzeilen zählen nicht). Liefert das Feld und die Fundzeile für die Währung. */
    private fun amount(lines: List<RecognizedLine>): Pair<ParsedField, RecognizedLine?> {
        val total = lines.filter { TOTAL_KEY.containsMatchIn(it.text) && !VAT_KEY.containsMatchIn(it.text) && AMOUNT.containsMatchIn(it.text) }
        // Priorität: Gesamt/Summe/Total/zu zahlen vor dem allgemeinen „Betrag“; bei mehreren gleichwertigen die letzte (Schlusssumme)
        val strong = total.filter { Regex("(?i)gesamt|summe|total|zu\\s+zahlen|zahlbetrag|endbetrag|rechnungsbetrag").containsMatchIn(it.text) }
        val l = (strong.ifEmpty { total }).lastOrNull() ?: return empty(ExtractedFieldKind.AMOUNT) to null
        val m = AMOUNT.findAll(l.text).last()
        return found(ExtractedFieldKind.AMOUNT, 0, l, m.value, minor(m).toString()) to l
    }

    private fun currency(lines: List<RecognizedLine>, totalLine: RecognizedLine?): ParsedField {
        val l = totalLine?.takeIf { CURRENCY.containsMatchIn(it.text) } ?: lines.firstOrNull { CURRENCY.containsMatchIn(it.text) } ?: return empty(ExtractedFieldKind.CURRENCY)
        val m = CURRENCY.find(l.text)!!
        return found(ExtractedFieldKind.CURRENCY, 0, l, m.value, if (m.value == "€") "EUR" else m.value.uppercase())
    }

    private fun vat(lines: List<RecognizedLine>): List<ParsedField> {
        val seen = LinkedHashSet<Pair<String, Long>>(); val out = ArrayList<ParsedField>()
        for (l in lines) {
            if (!VAT_KEY.containsMatchIn(l.text)) continue
            val rate = RATE.find(l.text) ?: continue
            val amounts = AMOUNT.findAll(l.text).filter { it.range.first > rate.range.last }.toList()   // der Betrag steht hinter dem Satz
            val m = (amounts.firstOrNull() ?: continue)
            val rateText = rate.groupValues[1] + (rate.groupValues[2].takeIf { it.isNotEmpty() }?.let { ".$it" } ?: "")
            if (seen.add(rateText to minor(m))) out += found(ExtractedFieldKind.VAT_AMOUNT, out.size, l, l.text.trim(), minor(m).toString(), rateText)
        }
        return out.ifEmpty { listOf(empty(ExtractedFieldKind.VAT_AMOUNT)) }
    }
}

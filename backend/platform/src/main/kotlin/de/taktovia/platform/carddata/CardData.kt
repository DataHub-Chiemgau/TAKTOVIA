package de.taktovia.platform.carddata

/**
 * Erkennung und Maskierung kartennummernähnlicher Muster (WP-M1-37, LH-6.18-SEC-008). Das System nimmt keine rohen Kartendaten an:
 * Eingaben mit einem Treffer werden abgewiesen, Logs/Audit/Ereignisse maskiert (siehe `docs/umsetzungsplan/vertraege/psp-trennung.md`).
 *
 * Erkennungsregel (Vorschlag, im Pflichtenheft nicht festgelegt): eine Ziffernfolge von 13 bis 19 Ziffern, die durch einzelne Leerzeichen,
 * Bindestriche oder Punkte gegliedert sein darf, **und** Luhn-Prüfsumme **und** Länge/Präfix eines gängigen Kartennetzwerks (Visa, Mastercard, Amex,
 * Discover, Diners, JCB, UnionPay, Maestro). Die Netzwerkprüfung hält Zeitstempel in Millisekunden, Bestellnummern und UUID-Teile weitgehend
 * heraus. UUIDs werden vorher ausgeblendet. Von einer längeren Ziffernfolge zählt jedes passende Teilstück (Verschleierung durch Anhängen
 * wirkt nicht). Nicht erkannt werden bewusst: Ablaufdatum und CVC allein (ohne Nummer kein Treffer; beides ist schemaseitig verboten).
 */
object CardData {
    const val MASK = "[card-data-removed]"

    private val UUID_RE = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val RUN = Regex("\\d(?:[ .\\-]?\\d){12,}")

    /** Enthält [text] eine kartennummernähnliche Folge? */
    fun contains(text: String?): Boolean = text != null && text.length >= 13 && ranges(text).isNotEmpty()

    /** Ersetzt jede erkannte Folge durch [MASK]; kein Ziffernrest bleibt zurück. */
    fun mask(text: String): String {
        if (text.length < 13) return text
        val rs = ranges(text)
        if (rs.isEmpty()) return text
        val sb = StringBuilder(); var pos = 0
        for (r in rs) { sb.append(text, pos, r.first); sb.append(MASK); pos = r.last + 1 }
        return sb.append(text, pos, text.length).toString()
    }

    /** Zeichenbereiche der Treffer (verschmolzen, aufsteigend). */
    fun ranges(text: String): List<IntRange> {
        val scan = UUID_RE.replace(text) { "x".repeat(it.value.length) }
        val found = ArrayList<IntRange>()
        for (m in RUN.findAll(scan)) {
            val idx = ArrayList<Int>(); val digits = StringBuilder()
            for (i in m.range) if (scan[i].isDigit()) { idx += i; digits.append(scan[i]) }
            val d = digits.toString()
            for (len in 13..minOf(19, d.length)) for (start in 0..d.length - len) {
                if (isCard(d, start, len)) found += idx[start]..idx[start + len - 1]
            }
        }
        if (found.isEmpty()) return emptyList()
        found.sortBy { it.first }
        val merged = ArrayList<IntRange>()
        for (r in found) {
            val last = merged.lastOrNull()
            if (last != null && r.first <= last.last + 1) merged[merged.size - 1] = last.first..maxOf(last.last, r.last) else merged += r
        }
        return merged
    }

    private fun isCard(all: String, start: Int, len: Int): Boolean {
        val d = all.substring(start, start + len)
        return networkMatches(d) && luhn(d)
    }

    fun luhn(d: String): Boolean {
        var sum = 0; var alt = false
        for (c in d.reversed()) { var n = c - '0'; if (alt) { n *= 2; if (n > 9) n -= 9 }; sum += n; alt = !alt }
        return sum % 10 == 0
    }

    private fun p(d: String, n: Int) = d.substring(0, n).toInt()

    private fun networkMatches(d: String): Boolean {
        val len = d.length
        return when {
            d[0] == '4' -> len == 13 || len == 16 || len == 19                                                    // Visa
            len == 16 && (p(d, 2) in 51..55 || p(d, 4) in 2221..2720) -> true                                      // Mastercard
            len == 15 && (p(d, 2) == 34 || p(d, 2) == 37) -> true                                                  // American Express
            len in 16..19 && (p(d, 4) == 6011 || p(d, 2) == 65 || p(d, 3) in 644..649 || p(d, 6) in 622126..622925) -> true   // Discover
            len in 16..19 && p(d, 2) == 62 -> true                                                                 // UnionPay
            len in 14..19 && (p(d, 3) in 300..305 || p(d, 2) == 36 || p(d, 2) == 38 || p(d, 2) == 39) -> true      // Diners
            len in 16..19 && p(d, 4) in 3528..3589 -> true                                                         // JCB
            len in 12..19 && (p(d, 4) in setOf(5018, 5020, 5038, 5893, 6304, 6759, 6761, 6762, 6763)) -> true     // Maestro
            else -> false
        }
    }
}

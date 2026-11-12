package de.taktovia.documentsfinance.internal

/**
 * Neutralisierte Darstellung erkannten Texts (Kap. 03 §3 Nr. 2): OCR-Text ist ausschließlich Datum. Steuerzeichen werden entfernt, Markup-Klammern durch typografische ersetzt, ein führendes
 * Formelzeichen (`=`, `+`, `-`, `@`) wird mit einem Apostroph entschärft (Tabellenkalkulation), Kurzbefehls- und Skriptzeichen bleiben als Text. Der Rohwert bleibt unverändert als Datum erhalten.
 */
internal object Neutralizer {
    private val CONTROL = Regex("[\\p{Cntrl}\\u200B-\\u200F\\u202A-\\u202E\\u2066-\\u2069&&[^\\n]]")
    private val INSTRUCTION = Regex("(?i)(ignor(e|iere)\\b.*(instruction|anweisung|regel|rules)|system\\s*prompt|disregard|vergiss\\s+alle|gib\\s+den\\s+beleg\\s+frei|approve\\s+this|freigeben)")
    private val MARKUP = Regex("<\\s*/?\\s*[a-zA-Z!?]|javascript:|data:text|\\{\\{|\\$\\{|&#?\\w+;|\\bon[a-z]+\\s*=")

    fun display(text: String): String {
        var s = text.replace(CONTROL, " ").replace('\n', ' ').trim().take(500)
        s = s.replace('<', '‹').replace('>', '›').replace('`', '\'')
        if (s.isNotEmpty() && s[0] in "=+-@") s = "'$s"
        return s
    }

    /** Wirkt der Text wie eine Anweisung, Formel oder Markup? Nur eine Markierung für die Oberfläche; es löst nie eine Aktion aus. */
    fun looksActive(text: String): Boolean = INSTRUCTION.containsMatchIn(text) || MARKUP.containsMatchIn(text) || text.trimStart().firstOrNull()?.let { it in "=+@" } == true || Regex("^\\s*-\\s*[A-Za-z(]").containsMatchIn(text)
}

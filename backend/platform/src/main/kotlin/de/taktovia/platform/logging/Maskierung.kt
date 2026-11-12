package de.taktovia.platform.logging

import de.taktovia.platform.carddata.CardData

/**
 * Zentrale Maskierungsschicht für Diagnosedaten (WP-M5-06, LH-6.19-NFR-015): Logausgabe, Trace-Attribute, `problem+json`-`detail`,
 * Alarmtexte und Ausnahmebeschreibungen laufen durch dieselben Regeln. Regeln und Feldklassen: docs/betrieb/maskierung.md.
 *
 * Feldklassen (Ersatzdarstellung `[maskiert:<klasse>]`): Karte (Luhn, über [CardData]), IBAN, E-Mail, Telefon, Adresse, Token/Geheimnis,
 * Boarding-Code. Zulässig bleiben technische Kennungen: UUID, W3C-Trace-/Span-ID (hexadezimal mit Buchstaben), Zeitpunkte, Zähler.
 * Arbeitsannahme zu OP-09: Zahlungsdetails und Kontaktdaten werden immer maskiert, Fahrgast-UUIDs sind zulässig.
 */
object Maskierung {
    enum class Klasse(val ersatz: String) {
        KARTE(CardData.MASK), IBAN("[maskiert:iban]"), EMAIL("[maskiert:email]"), TELEFON("[maskiert:telefon]"),
        ADRESSE("[maskiert:adresse]"), TOKEN("[maskiert:token]"), BOARDING_CODE("[maskiert:boarding-code]"),
    }

    private val UUID_RE = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val TRACE_ID = Regex("(?<![0-9A-Za-z])(?=[0-9a-f]*[a-f])(?:[0-9a-f]{32}|[0-9a-f]{16})(?![0-9A-Za-z])")

    // Reihenfolge: zuerst Geheimnisse mit Schlüssel, dann Formen mit eindeutigem Muster, zuletzt die unschärferen (Telefon, Adresse).
    private val SECRET_KV = Regex("(?i)\\b(pass(?:word|wd)?|passwort|secret|token|access[_-]?token|refresh[_-]?token|authorization|api[_-]?key|client[_-]?secret|cookie|session[_-]?id|otp|pin|cvc|cvv)\\b(\\s*[=:]\\s*|\"\\s*:\\s*\")(?!\\[maskiert)(?:Bearer\\s+|Basic\\s+)?[^\\s,;&\"'}\\]]+")
    private val BOARDING_KV = Regex("(?i)\\b(boarding[_ -]?code)\\b(\\s*(?:[=:]|\"\\s*:)\\s*\"?|\\s+)(?!\\[maskiert)[A-Za-z0-9-]{4,}")
    private val BOARDING_PREFIX = Regex("\\bBC-[A-Z0-9]{4,}\\b")
    private val JWT = Regex("eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{4,}")
    private val BEARER = Regex("(?i)\\b(?:bearer|basic)\\s+[A-Za-z0-9._~+/=-]{8,}")
    private val EMAIL = Regex("[A-Za-z0-9._%+'-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}")
    private val IBAN = Regex("\\b[A-Z]{2}\\d{2}(?:\\s?[A-Z0-9]{4}){2,7}(?:\\s?[A-Z0-9]{1,3})?\\b")
    private val PHONE_INTL = Regex("(?<![\\w.-])(?:\\+|00)\\d{1,3}[\\s/().-]*\\d(?:[\\s/().-]?\\d){5,13}(?![\\w-])")
    private val PHONE_NAT = Regex("(?<![\\w.+-])0\\d{2,5}[\\s/-]?\\d(?:[\\s/-]?\\d){4,9}(?![\\w-])")
    private val STREET = Regex("\\b[A-ZÄÖÜ][\\wäöüß.-]*(?:stra(?:ß|ss)e|str\\.|weg|allee|platz|gasse|ring|damm|ufer|chaussee)\\s+\\d{1,4}\\s?[a-zA-Z]?\\b(?:,?\\s*\\d{5}\\s+[A-ZÄÖÜ][\\wäöüß-]+)?")
    private val POSTAL_CITY = Regex("(?<![\\d.-])\\d{5}\\s+[A-ZÄÖÜ][a-zäöüß]{2,}(?:-[A-ZÄÖÜ][a-zäöüß]+)?\\b")

    // IBAN und E-Mail vor der Kartenerkennung (die Ziffern einer IBAN können eine Luhn-Prüfsumme erfüllen), Telefon und Adresse danach.
    private val PRE = listOf(Klasse.TOKEN to JWT, Klasse.TOKEN to BEARER, Klasse.EMAIL to EMAIL, Klasse.IBAN to IBAN, Klasse.BOARDING_CODE to BOARDING_PREFIX)
    private val POST = listOf(Klasse.TELEFON to PHONE_INTL, Klasse.TELEFON to PHONE_NAT, Klasse.ADRESSE to STREET, Klasse.ADRESSE to POSTAL_CITY)
    private val RULES = PRE + POST

    /** Ersetzt Klartext der Feldklassen; UUIDs und Trace-IDs bleiben lesbar. */
    fun maskText(text: String?): String {
        if (text.isNullOrEmpty()) return text ?: ""
        var s = SECRET_KV.replace(text) { it.groupValues[1] + it.groupValues[2] + Klasse.TOKEN.ersatz }
        s = BOARDING_KV.replace(s) { it.groupValues[1] + it.groupValues[2] + Klasse.BOARDING_CODE.ersatz }
        s = phase(s, PRE)
        s = CardData.mask(s)
        return phase(s, POST)
    }

    private fun phase(s: String, rules: List<Pair<Klasse, Regex>>): String {
        val out = StringBuilder(s.length)
        var pos = 0
        for (m in protectedRanges(s)) { out.append(maskSegment(s.substring(pos, m.first), rules)).append(s, m.first, m.last + 1); pos = m.last + 1 }
        out.append(maskSegment(s.substring(pos), rules))
        return out.toString()
    }

    /** Welche Feldklassen stehen im Klartext in [text]? (Prüfhilfe; maskierte Ersatzdarstellungen zählen nicht.) */
    fun klartextKlassen(text: String): Set<Klasse> {
        val found = linkedSetOf<Klasse>()
        if (CardData.contains(text)) found += Klasse.KARTE
        if (SECRET_KV.containsMatchIn(text)) found += Klasse.TOKEN
        if (BOARDING_KV.containsMatchIn(text)) found += Klasse.BOARDING_CODE
        val rest = text
        var pos = 0
        val segments = ArrayList<String>()
        for (m in protectedRanges(rest)) { segments += rest.substring(pos, m.first); pos = m.last + 1 }
        segments += rest.substring(pos)
        for (seg in segments) for ((k, re) in RULES) if (re.containsMatchIn(seg)) found += k
        return found
    }

    private fun protectedRanges(s: String): List<IntRange> =
        (UUID_RE.findAll(s).map { it.range } + TRACE_ID.findAll(s).map { it.range }).sortedBy { it.first }.fold(ArrayList<IntRange>()) { acc, r ->
            if (acc.isNotEmpty() && r.first <= acc.last().last) { if (r.last > acc.last().last) acc[acc.size - 1] = acc.last().first..r.last } else acc += r
            acc
        }

    private fun maskSegment(seg: String, rules: List<Pair<Klasse, Regex>>): String {
        var s = seg
        for ((k, re) in rules) s = re.replace(s, k.ersatz)
        return s
    }

    /**
     * Ausnahmebeschreibung für Logs: Klassenname und maskierte Nachricht, Ursachenkette bis Tiefe 4; keine Objektinhalte, kein Stapel.
     * Die Nachricht wird auf 300 Zeichen gekürzt (Objekt-`toString()` mit Fachdaten wird so nicht ausgegeben).
     */
    fun beschreibe(t: Throwable): String {
        val parts = ArrayList<String>()
        var c: Throwable? = t
        var depth = 0
        while (c != null && depth < 4) {
            parts += c.javaClass.name + (c.message?.let { ": " + maskText(it).take(300) } ?: "")
            c = c.cause?.takeIf { it !== c }; depth++
        }
        return parts.joinToString(" <- ")
    }

    /** Trace-Attribute: nur Werte, die Klartext der Feldklassen enthalten, werden ersetzt; Schlüssel auf der Sperrliste werden vollständig maskiert. */
    fun traceAttribute(key: String, value: Any?): String {
        if (value == null) return ""
        if (Positivliste.istSperrschluessel(key)) return Klasse.TOKEN.ersatz
        return maskText(value.toString()).take(256)
    }

    fun traceAttribute(attributes: Map<String, Any?>): Map<String, String> = attributes.mapValues { (k, v) -> traceAttribute(k, v) }

    /** `detail` von `application/problem+json`: maskiert, ohne Stapel- oder Klassenangaben, höchstens 2000 Zeichen. */
    fun problemDetail(detail: String?): String? = detail?.let { maskText(it).take(2000) }

    /** Alarmtexte (Betreiberwarnung, Zustellmitteilung). */
    fun alertText(text: String): String = maskText(text).take(500)
}

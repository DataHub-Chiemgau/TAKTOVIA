package de.taktovia.platform.alert

/**
 * Zulassungsregel für Betreiberwarnungen (WP-M1-47 AK 3): Attribute nur aus der Positivliste, Werte nur technische Zeichen;
 * als personenbezogen bekannte Feldnamen und personenbezogene Wertmuster (Mail, Telefon, IBAN, Kartennummer) werden abgewiesen.
 * Neue technische Attribute werden bewusst hier ergänzt (Review), nicht ad hoc von Modulen.
 */
object OperatorWarningPolicy {
    val ALLOWED_ATTRIBUTES = setOf("component", "queue", "event_type", "aggregate_type", "attempts", "reason_code", "consumer", "endpoint_ref")
    private val PERSONAL_KEYS = Regex("(?i)(e-?mail|phone|tel|mobil|name|vorname|nachname|address|adresse|iban|card|pan|passenger|fahrgast|user|person|birth|content|body|message|text)")
    private val SAFE_VALUE = Regex("[A-Za-z0-9_.:/-]{1,80}")
    private val EMAIL = Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")
    private val SLUG = Regex("[a-z][a-z0-9_.-]{1,47}")

    fun validate(w: OperatorWarning) {
        require(SLUG.matches(w.source)) { "ungültige Quelle" }
        require(SLUG.matches(w.kind)) { "ungültige Art" }
        for ((k, v) in w.attributes) {
            if (k !in ALLOWED_ATTRIBUTES) {
                if (PERSONAL_KEYS.containsMatchIn(k)) throw PersonalDataInWarningException("Attribut '$k' ist als personenbezogen/inhaltlich gekennzeichnet")
                throw IllegalArgumentException("Attribut '$k' nicht zugelassen")
            }
            if (!SAFE_VALUE.matches(v) || EMAIL.containsMatchIn(v) || digitsLookPersonal(v))
                throw PersonalDataInWarningException("Wert von '$k' enthält unzulässige oder personenbezogene Zeichen")
        }
    }

    /** Telefonnummern-, IBAN- und Kartennummernmuster: lange Ziffernfolgen. */
    private fun digitsLookPersonal(v: String): Boolean {
        val digits = v.filter { it.isDigit() }
        return digits.length >= 9 || Regex("[A-Z]{2}\\d{2}[A-Z0-9]{10,}").containsMatchIn(v)
    }
}

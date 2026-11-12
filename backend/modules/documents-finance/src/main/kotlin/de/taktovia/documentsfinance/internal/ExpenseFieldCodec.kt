package de.taktovia.documentsfinance.internal

import de.taktovia.documentsfinance.api.ExpenseFields
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Prüfung und Normalisierung der Belegfelder (WP-M4-23): Betrag als Ganzzahl in kleinster Einheit, Währung ISO-4217 (Großbuchstaben), Datum ISO-8601, Texte begrenzt,
 * Mehrwertsteuerbeträge als Liste aus Satz (Dezimalstring) und Betrag. Dieselbe Normalform gilt für gespeicherte und eingegebene Werte (Vergleich auf Gleichheit).
 */
internal object ExpenseFieldCodec {
    private val MAPPER = JsonMapper.builder().build()
    private val RATE = Regex("[0-9]{1,2}(\\.[0-9]{1,2})?")
    private const val MAX_SAFE = 9_007_199_254_740_991L

    fun normalize(key: String, value: Any?, pointer: String = "/fields/$key"): Any {
        fun bad(code: ViolationCode = ViolationCode.INVALID_VALUE): Nothing = throw ProblemException.invalid(Violation(pointer, code))
        if (value == null) bad(ViolationCode.REQUIRED)
        return when (key) {
            ExpenseFields.AMOUNT_MINOR, ExpenseFields.NET_AMOUNT_MINOR -> minor(value, pointer)
            ExpenseFields.CURRENCY -> (value as? String)?.takeIf { Regex("[A-Z]{3}").matches(it) } ?: bad(ViolationCode.PATTERN_MISMATCH)
            ExpenseFields.DOCUMENT_DATE -> try { LocalDate.parse(value as? String ?: bad(ViolationCode.TYPE_MISMATCH)).toString() } catch (e: DateTimeParseException) { bad(ViolationCode.INVALID_FORMAT) }
            ExpenseFields.MERCHANT -> text(value, 200, pointer)
            ExpenseFields.DOCUMENT_NUMBER -> text(value, 100, pointer)
            ExpenseFields.VAT_AMOUNTS -> {
                val list = value as? List<*> ?: bad(ViolationCode.TYPE_MISMATCH)
                if (list.size > 6) bad(ViolationCode.TOO_MANY_ITEMS)
                list.map { e ->
                    val m = e as? Map<*, *> ?: bad(ViolationCode.TYPE_MISMATCH)
                    if (m.keys.any { it != "vat_rate_pct" && it != "amount_minor" }) bad(ViolationCode.UNKNOWN_FIELD)
                    val rate = (m["vat_rate_pct"] as? String)?.takeIf { RATE.matches(it) } ?: bad(ViolationCode.PATTERN_MISMATCH)
                    linkedMapOf<String, Any>("vat_rate_pct" to rate, "amount_minor" to minor(m["amount_minor"], pointer))
                }
            }
            else -> throw ProblemException.invalid(Violation(pointer, ViolationCode.UNKNOWN_FIELD))
        }
    }

    private fun minor(v: Any?, pointer: String): Long {
        val n = when (v) { is Int -> v.toLong(); is Long -> v; is java.math.BigInteger -> if (v.bitLength() < 63) v.toLong() else Long.MAX_VALUE; else -> throw ProblemException.invalid(Violation(pointer, ViolationCode.MONEY_NOT_INTEGER)) }
        if (n < 0) throw ProblemException.invalid(Violation(pointer, ViolationCode.MONEY_NEGATIVE))
        if (n > MAX_SAFE) throw ProblemException.invalid(Violation(pointer, ViolationCode.MONEY_OUT_OF_RANGE))
        return n
    }

    private fun text(v: Any?, max: Int, pointer: String): String {
        val s = (v as? String)?.trim() ?: throw ProblemException.invalid(Violation(pointer, ViolationCode.TYPE_MISMATCH))
        if (s.isEmpty()) throw ProblemException.invalid(Violation(pointer, ViolationCode.TOO_SHORT))
        if (s.length > max) throw ProblemException.invalid(Violation(pointer, ViolationCode.TOO_LONG))
        return s
    }

    fun toJson(v: Any?): String = MAPPER.writeValueAsString(v)

    /** Gespeicherter Feldstand (jsonb) in Normalform. */
    fun parseStored(json: String): Map<String, Any> {
        @Suppress("UNCHECKED_CAST") val raw = MAPPER.readValue(json, Map::class.java) as Map<String, Any?>
        return raw.entries.associate { (k, v) -> k to normalize(k, v) }
    }

    /** Revisionsfeld `{"amount_minor": {"old": …, "new": …}}` aus der Datenbank. */
    fun parseTree(json: String): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST") return MAPPER.readValue(json, Map::class.java) as Map<String, Any?>
    }
}

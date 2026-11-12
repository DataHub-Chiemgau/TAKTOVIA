package de.taktovia.farepayment.internal

import de.taktovia.farepayment.api.PersonMode
import de.taktovia.farepayment.api.TariffRuleInput
import de.taktovia.farepayment.api.TariffRuleType
import de.taktovia.farepayment.api.TaxMode
import de.taktovia.platform.money.MoneyLimits
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import java.math.BigDecimal
import java.math.BigInteger
import java.util.UUID

/** Geprüfte Regel (Spiegel der Tabelle `tariff_rule`). */
internal data class ParsedRule(
    val type: TariffRuleType, val personMode: PersonMode, val amountMinor: Long, val zoneFrom: UUID?, val zoneTo: UUID?, val ratePerKm: BigDecimal?,
    val bookingFeeMinor: Long, val taxRate: BigDecimal, val taxMode: TaxMode, val taxRuleRevision: String,
)

/**
 * Strukturprüfung der Regeleingabe (WP-M2-07): Typen, Wertebereiche und die Form je Regeltyp mit Feldverweisen `/rules/<n>/<feld>`; danach die Konsistenz der Revision
 * (genau ein Regeltyp; Festpreis und Grundpreis/Strecke genau eine Regel; Zonenpaare eindeutig). Beträge müssen ganze Zahlen sein, Sätze Dezimalzahlen im Textformat
 * mit höchstens acht Nachkommastellen (nie Gleitkomma).
 */
internal object TariffRuleParser {
    const val MAX_RULES = 500
    private val RATE = Regex("^[0-9]{1,12}(\\.[0-9]{1,8})?$")

    fun parse(inputs: List<TariffRuleInput>?, v: MutableList<Violation>): List<ParsedRule> {
        if (inputs == null) { v += Violation("/rules", ViolationCode.REQUIRED); return emptyList() }
        if (inputs.isEmpty()) { v += Violation("/rules", ViolationCode.TOO_FEW_ITEMS); return emptyList() }
        if (inputs.size > MAX_RULES) { v += Violation("/rules", ViolationCode.TOO_MANY_ITEMS); return emptyList() }
        val out = inputs.mapIndexedNotNull { i, r -> rule(i, r, v) }
        if (out.size != inputs.size) return emptyList()
        val types = out.map { it.type }.toSet()
        if (types.size > 1) v += Violation("/rules", ViolationCode.INVALID_VALUE, "Eine Revision darf nur einen Regeltyp enthalten")
        else when (types.single()) {
            TariffRuleType.FESTPREIS, TariffRuleType.GRUNDPREIS_STRECKE -> if (out.size != 1) v += Violation("/rules", ViolationCode.TOO_MANY_ITEMS, "Dieser Regeltyp erlaubt genau eine Regel")
            TariffRuleType.ZONENPREIS -> {
                val seen = HashSet<Pair<UUID?, UUID?>>()
                out.forEachIndexed { i, r -> if (!seen.add(r.zoneFrom to r.zoneTo)) v += Violation("/rules/$i/zone_to", ViolationCode.NOT_UNIQUE, "Zonenpaar mehrfach bepreist") }
            }
        }
        return out
    }

    private fun rule(i: Int, r: TariffRuleInput, v: MutableList<Violation>): ParsedRule? {
        val before = v.size
        val p = "/rules/$i"
        val type = enumOf<TariffRuleType>(r.ruleType, "$p/rule_type", true, v)
        val personMode = enumOf<PersonMode>(r.personMode, "$p/person_mode", false, v) ?: PersonMode.PER_PERSON
        val taxMode = enumOf<TaxMode>(r.taxMode, "$p/tax_mode", false, v) ?: TaxMode.GROSS
        val amount = minor(r.amountMinor, "$p/amount_minor", true, v)
        val fee = minor(r.bookingFeeMinor, "$p/booking_fee_minor", false, v) ?: 0L
        val zoneFrom = uuid(r.zoneFrom, "$p/zone_from", v); val zoneTo = uuid(r.zoneTo, "$p/zone_to", v)
        val rate = decimal(r.ratePerKm, "$p/rate_per_km", v, max = null)
        val tax = decimal(r.taxRate, "$p/tax_rate", v, max = BigDecimal.ONE) ?: BigDecimal.ZERO
        val taxRev = when (val t = r.taxRuleRevision) {
            null -> { v += Violation("$p/tax_rule_revision", ViolationCode.REQUIRED); null }
            !is String -> { v += Violation("$p/tax_rule_revision", ViolationCode.TYPE_MISMATCH); null }
            else -> if (t.isBlank() || t.length > 40) { v += Violation("$p/tax_rule_revision", if (t.isBlank()) ViolationCode.TOO_SHORT else ViolationCode.TOO_LONG); null } else t
        }
        if (type != null) when (type) {
            TariffRuleType.FESTPREIS -> { forbid(r.zoneFrom, "$p/zone_from", v); forbid(r.zoneTo, "$p/zone_to", v); forbid(r.ratePerKm, "$p/rate_per_km", v) }
            TariffRuleType.ZONENPREIS -> { require(r.zoneFrom, "$p/zone_from", v); require(r.zoneTo, "$p/zone_to", v); forbid(r.ratePerKm, "$p/rate_per_km", v) }
            TariffRuleType.GRUNDPREIS_STRECKE -> { forbid(r.zoneFrom, "$p/zone_from", v); forbid(r.zoneTo, "$p/zone_to", v); require(r.ratePerKm, "$p/rate_per_km", v) }
        }
        if (v.size != before || type == null || amount == null || taxRev == null) return null
        return ParsedRule(type, personMode, amount, zoneFrom, zoneTo, rate, fee, tax, taxMode, taxRev)
    }

    private fun require(value: Any?, pointer: String, v: MutableList<Violation>) { if (value == null) v += Violation(pointer, ViolationCode.REQUIRED) }
    private fun forbid(value: Any?, pointer: String, v: MutableList<Violation>) { if (value != null) v += Violation(pointer, ViolationCode.UNKNOWN_FIELD, "Für diesen Regeltyp nicht zulässig") }

    private inline fun <reified E : Enum<E>> enumOf(value: Any?, pointer: String, required: Boolean, v: MutableList<Violation>): E? = when (value) {
        null -> { if (required) v += Violation(pointer, ViolationCode.REQUIRED); null }
        is String -> enumValues<E>().firstOrNull { it.name == value } ?: run { v += Violation(pointer, ViolationCode.NOT_IN_ENUM); null }
        else -> { v += Violation(pointer, ViolationCode.TYPE_MISMATCH); null }
    }

    /** Ganzzahl in der kleinsten Einheit, 0 bis 2^53 − 1; Zahlen mit Nachkommaanteil sind kein Betrag. */
    private fun minor(value: Any?, pointer: String, required: Boolean, v: MutableList<Violation>): Long? {
        val n: BigInteger = when (value) {
            null -> { if (required) v += Violation(pointer, ViolationCode.REQUIRED); return null }
            is Long -> BigInteger.valueOf(value); is Int -> BigInteger.valueOf(value.toLong()); is Short -> BigInteger.valueOf(value.toLong()); is BigInteger -> value
            is Number -> { v += Violation(pointer, ViolationCode.MONEY_NOT_INTEGER); return null }
            else -> { v += Violation(pointer, ViolationCode.TYPE_MISMATCH); return null }
        }
        if (n.signum() < 0) { v += Violation(pointer, ViolationCode.MONEY_NEGATIVE); return null }
        if (n > BigInteger.valueOf(MoneyLimits.MAX_SAFE)) { v += Violation(pointer, ViolationCode.MONEY_OUT_OF_RANGE); return null }
        return n.toLong()
    }

    private fun uuid(value: Any?, pointer: String, v: MutableList<Violation>): UUID? = when (value) {
        null -> null
        is String -> runCatching { UUID.fromString(value) }.getOrNull() ?: run { v += Violation(pointer, ViolationCode.INVALID_FORMAT); null }
        else -> { v += Violation(pointer, ViolationCode.TYPE_MISMATCH); null }
    }

    /** Dezimalsatz als Text (`0.19`, `12.5`); Zahlen im JSON (Gleitkomma) werden nicht angenommen. [max] ist eine echte obere Schranke (ausschließlich). */
    private fun decimal(value: Any?, pointer: String, v: MutableList<Violation>, max: BigDecimal?): BigDecimal? = when (value) {
        null -> null
        is String -> if (!RATE.matches(value)) { v += Violation(pointer, ViolationCode.PATTERN_MISMATCH); null }
                     else BigDecimal(value).also { if (max != null && it >= max) { v += Violation(pointer, ViolationCode.ABOVE_MAXIMUM); } }.takeIf { max == null || it < max }
        else -> { v += Violation(pointer, ViolationCode.TYPE_MISMATCH); null }
    }
}

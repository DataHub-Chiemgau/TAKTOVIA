package de.taktovia.platform.money

import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import java.math.BigInteger
import javax.sql.DataSource

/**
 * Geldtypen der Plattform (Kap. 05 §5.1, Kap. 04 §4.1, WP-M1-02): `amount_minor` ganzzahlig in der kleinsten Einheit,
 * `currency` (ISO 4217), `exponent` aus dem Währungskatalog (Tabelle `currency`). Kein Gleitkomma, nie.
 * [Money] ist nichtnegativ; negative Beträge (Rabatt, Gegenereignis) gibt es nur als [SignedMoney].
 */
object MoneyLimits {
    /** Größter sicher in JSON darstellbarer Integer (2^53 − 1). */
    const val MAX_SAFE: Long = 9_007_199_254_740_991L
}

data class Money(val amountMinor: Long, val currency: String, val exponent: Int) {
    init { require(amountMinor in 0..MoneyLimits.MAX_SAFE) { "Money ist nichtnegativ und höchstens ${MoneyLimits.MAX_SAFE}" } }
    fun toMap(): Map<String, Any> = mapOf("amount_minor" to amountMinor, "currency" to currency, "exponent" to exponent)
}

data class SignedMoney(val amountMinor: Long, val currency: String, val exponent: Int) {
    init { require(amountMinor in -MoneyLimits.MAX_SAFE..MoneyLimits.MAX_SAFE) { "SignedMoney liegt außerhalb ±${MoneyLimits.MAX_SAFE}" } }
    fun toMap(): Map<String, Any> = mapOf("amount_minor" to amountMinor, "currency" to currency, "exponent" to exponent)
}

/** Währungskatalog (Code → Exponent). */
fun interface CurrencyCatalog { fun exponent(code: String): Int? }

class StaticCurrencyCatalog(private val exponents: Map<String, Int>) : CurrencyCatalog {
    override fun exponent(code: String) = exponents[code]
}

/** Liest die globale Tabelle `currency` (Runtime hat SELECT, Pflege nur per Migration); Ergebnis wird gecacht. */
class JdbcCurrencyCatalog(private val dataSource: DataSource) : CurrencyCatalog {
    @Volatile private var cache: Map<String, Int>? = null

    override fun exponent(code: String): Int? = (cache ?: load().also { cache = it })[code]

    private fun load(): Map<String, Int> = dataSource.connection.use { c ->
        c.createStatement().use { st ->
            st.executeQuery("SELECT code::text, exponent FROM taktovia.currency").use { rs ->
                buildMap { while (rs.next()) put(rs.getString(1).trim(), rs.getInt(2)) }
            }
        }
    }
}

/**
 * Serverseitige Prüfung eines Geldobjekts aus bereits geparstem JSON. Zahlen kommen als Kotlin-Typen: ganze Zahlen als
 * Long/Int/BigInteger, alles mit Nachkommaanteil oder Exponent als Double/BigDecimal und wird abgewiesen.
 */
class MoneyValidator(private val catalog: CurrencyCatalog) {
    private val known = setOf("amount_minor", "currency", "exponent")

    fun validate(value: Any?, pointer: String, signed: Boolean): List<Violation> {
        if (value !is Map<*, *>) return listOf(Violation(pointer, ViolationCode.TYPE_MISMATCH))
        val out = mutableListOf<Violation>()
        value.keys.filter { it !in known }.forEach { out += Violation("$pointer/${escape(it.toString())}", ViolationCode.UNKNOWN_FIELD) }
        val amount = integral(value["amount_minor"])
        when {
            !value.containsKey("amount_minor") -> out += Violation("$pointer/amount_minor", ViolationCode.REQUIRED)
            amount == null -> out += Violation("$pointer/amount_minor", if (value["amount_minor"] is Number) ViolationCode.MONEY_NOT_INTEGER else ViolationCode.TYPE_MISMATCH)
            amount.abs() > BigInteger.valueOf(MoneyLimits.MAX_SAFE) -> out += Violation("$pointer/amount_minor", ViolationCode.MONEY_OUT_OF_RANGE)
            amount.signum() < 0 && !signed -> out += Violation("$pointer/amount_minor", ViolationCode.MONEY_NEGATIVE)
        }
        var catalogExponent: Int? = null
        val currency = value["currency"]
        when {
            !value.containsKey("currency") -> out += Violation("$pointer/currency", ViolationCode.REQUIRED)
            currency !is String -> out += Violation("$pointer/currency", ViolationCode.TYPE_MISMATCH)
            else -> { catalogExponent = catalog.exponent(currency); if (catalogExponent == null) out += Violation("$pointer/currency", ViolationCode.CURRENCY_UNKNOWN) }
        }
        val exponent = integral(value["exponent"])
        when {
            !value.containsKey("exponent") -> out += Violation("$pointer/exponent", ViolationCode.REQUIRED)
            exponent == null -> out += Violation("$pointer/exponent", ViolationCode.TYPE_MISMATCH)
            catalogExponent != null && exponent != BigInteger.valueOf(catalogExponent.toLong()) -> out += Violation("$pointer/exponent", ViolationCode.EXPONENT_MISMATCH)
        }
        return out
    }

    /** Wie [validate], liefert aber den Wert oder wirft 422. */
    fun parseMoney(value: Any?, pointer: String = ""): Money {
        throwIfInvalid(validate(value, pointer, signed = false))
        val m = value as Map<*, *>
        return Money(integral(m["amount_minor"])!!.toLong(), m["currency"] as String, integral(m["exponent"])!!.toInt())
    }

    fun parseSignedMoney(value: Any?, pointer: String = ""): SignedMoney {
        throwIfInvalid(validate(value, pointer, signed = true))
        val m = value as Map<*, *>
        return SignedMoney(integral(m["amount_minor"])!!.toLong(), m["currency"] as String, integral(m["exponent"])!!.toInt())
    }

    private fun throwIfInvalid(v: List<Violation>) { if (v.isNotEmpty()) throw ProblemException.invalid(*v.toTypedArray()) }

    private fun integral(v: Any?): BigInteger? = when (v) {
        is Long -> BigInteger.valueOf(v); is Int -> BigInteger.valueOf(v.toLong()); is Short -> BigInteger.valueOf(v.toLong())
        is Byte -> BigInteger.valueOf(v.toLong()); is BigInteger -> v
        else -> null // Double, Float, BigDecimal, String, null: kein ganzzahliger Betrag
    }

    private fun escape(s: String) = s.replace("~", "~0").replace("/", "~1")
}

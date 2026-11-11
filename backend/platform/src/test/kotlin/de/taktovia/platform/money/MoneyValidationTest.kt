package de.taktovia.platform.money

import de.taktovia.platform.TestDatabase
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.ViolationCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.math.BigInteger

/** WP-M1-02 AK 7: Money/SignedMoney, Exponent gegen den Währungskatalog, kein Gleitkomma (LH-6.17-INT-003). */
class MoneyValidationTest {
    private val validator = MoneyValidator(StaticCurrencyCatalog(mapOf("EUR" to 2, "JPY" to 0, "BHD" to 3)))

    private fun money(amount: Any?, currency: Any? = "EUR", exponent: Any? = 2) =
        mapOf("amount_minor" to amount, "currency" to currency, "exponent" to exponent)

    private fun codes(value: Any?, signed: Boolean = false) = validator.validate(value, "/price", signed).map { it.pointer to it.code }

    @Test
    fun gueltige_betraege_werden_angenommen() {
        assertEquals(emptyList<Any>(), codes(money(1800L)))
        assertEquals(emptyList<Any>(), codes(money(0)))
        assertEquals(emptyList<Any>(), codes(money(1000L, "JPY", 0)))
        assertEquals(emptyList<Any>(), codes(money(MoneyLimits.MAX_SAFE)))
        assertEquals(emptyList<Any>(), codes(money(-MoneyLimits.MAX_SAFE), signed = true))
        assertEquals(Money(1800, "EUR", 2), validator.parseMoney(money(1800L)))
    }

    @Test
    fun gleitkomma_exponent_waehrung_negativ_und_integerbereich_werden_abgewiesen() {
        // Gleitkomma, auch wenn der Wert ganzzahlig aussieht
        assertEquals(listOf("/price/amount_minor" to ViolationCode.MONEY_NOT_INTEGER), codes(money(18.0)))
        assertEquals(listOf("/price/amount_minor" to ViolationCode.MONEY_NOT_INTEGER), codes(money(18.5f)))
        assertEquals(listOf("/price/amount_minor" to ViolationCode.MONEY_NOT_INTEGER), codes(money(BigDecimal("18.50"))))
        assertEquals(listOf("/price/amount_minor" to ViolationCode.TYPE_MISMATCH), codes(money("1800")))
        // falscher Exponent gegen den Währungskatalog (EUR = 2, JPY = 0, BHD = 3)
        assertEquals(listOf("/price/exponent" to ViolationCode.EXPONENT_MISMATCH), codes(money(1800L, "EUR", 3)))
        assertEquals(listOf("/price/exponent" to ViolationCode.EXPONENT_MISMATCH), codes(money(1800L, "JPY", 2)))
        assertEquals(listOf("/price/exponent" to ViolationCode.TYPE_MISMATCH), codes(money(1800L, "EUR", 2.0)))
        // unbekannte Währung, Kleinschreibung, falscher Typ
        assertEquals(listOf("/price/currency" to ViolationCode.CURRENCY_UNKNOWN), codes(money(1800L, "XXX", 2)))
        assertEquals(listOf("/price/currency" to ViolationCode.CURRENCY_UNKNOWN), codes(money(1800L, "eur", 2)))
        assertEquals(listOf("/price/currency" to ViolationCode.TYPE_MISMATCH), codes(money(1800L, 978, 2)))
        // negativ nur im SignedMoney
        assertEquals(listOf("/price/amount_minor" to ViolationCode.MONEY_NEGATIVE), codes(money(-1L)))
        assertEquals(emptyList<Any>(), codes(money(-1L), signed = true))
        // Betrag außerhalb des sicher darstellbaren Integerbereichs (±9.007.199.254.740.991)
        assertEquals(listOf("/price/amount_minor" to ViolationCode.MONEY_OUT_OF_RANGE), codes(money(MoneyLimits.MAX_SAFE + 1)))
        assertEquals(listOf("/price/amount_minor" to ViolationCode.MONEY_OUT_OF_RANGE), codes(money(-MoneyLimits.MAX_SAFE - 1), signed = true))
        assertEquals(listOf("/price/amount_minor" to ViolationCode.MONEY_OUT_OF_RANGE), codes(money(BigInteger.TEN.pow(30))))
        // fehlende und unbekannte Felder, falscher Gesamttyp
        assertEquals(listOf("/price/amount_minor" to ViolationCode.REQUIRED), codes(mapOf("currency" to "EUR", "exponent" to 2)))
        assertEquals(listOf("/price/gross" to ViolationCode.UNKNOWN_FIELD), codes(money(1L) + ("gross" to 1)))
        assertEquals(listOf("/price" to ViolationCode.TYPE_MISMATCH), codes("1800 EUR"))
        // parse* wirft 422 mit Feldverletzung statt eines ungeprüften Werts
        val e = assertThrows<ProblemException> { validator.parseMoney(money(1800L, "EUR", 3), "/price") }
        assertEquals(ErrorCode.VALIDATION_FAILED, e.code)
        assertEquals(ViolationCode.EXPONENT_MISMATCH, e.violations.single().code)
    }

    @Test
    fun geldtypen_lassen_keine_unzulaessigen_werte_konstruieren() {
        assertThrows<IllegalArgumentException> { Money(-1, "EUR", 2) }
        assertThrows<IllegalArgumentException> { Money(MoneyLimits.MAX_SAFE + 1, "EUR", 2) }
        assertThrows<IllegalArgumentException> { SignedMoney(-MoneyLimits.MAX_SAFE - 1, "EUR", 2) }
        assertEquals(-5L, SignedMoney(-5, "EUR", 2).amountMinor)
    }

    @Test
    fun waehrungskatalog_aus_der_datenbank_stimmt_mit_der_migration_ueberein() {
        TestDatabase.require()
        TestDatabase.runtime().use { ds ->
            val catalog = JdbcCurrencyCatalog(ds)
            assertEquals(2, catalog.exponent("EUR"))
            assertEquals(0, catalog.exponent("JPY"))
            assertEquals(null, catalog.exponent("ZZZ"))
            assertTrue(MoneyValidator(catalog).validate(mapOf("amount_minor" to 1L, "currency" to "EUR", "exponent" to 3), "", false).isNotEmpty())
        }
    }
}

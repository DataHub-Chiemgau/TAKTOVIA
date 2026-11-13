package de.taktovia.platform.money

import de.taktovia.platform.time.ReportingPeriods
import java.math.BigDecimal
import java.math.BigInteger
import java.time.LocalDate
import java.time.ZoneId

/** Fester Referenzlauf: erzeugt ein Textergebnis aus festen Eingaben. Wird in-process und in frischen JVMs (Neustart, andere Systemzone/-sprache) ausgeführt. */
object ReferenzLauf {
    private val ids = listOf("A", "B", "C")

    fun lauf(): String = buildString {
        for (total in listOf(10000L, 10001L, 10002L, -10001L, 1L, 0L)) appendLine("lr3 $total ${LargestRemainder.distributeEqually(total, ids)}")
        appendLine("lr-gewichtet ${LargestRemainder.distribute(99, listOf("a" to BigDecimal("70"), "b" to BigDecimal("20"), "c" to BigDecimal("10")))}")
        appendLine("lr-mischung ${LargestRemainder.distribute(100003, listOf("z" to BigDecimal("0.33333333"), "m" to BigDecimal("0.33333333"), "a" to BigDecimal("0.33333334")))}")
        appendLine("steuer ${MoneyRounding.tax(BigInteger.valueOf(1050), BigDecimal("0.19"))} ${MoneyRounding.tax(BigInteger.valueOf(1052), BigDecimal("0.19"))} ${MoneyRounding.netOfGross(BigInteger.valueOf(1190), BigDecimal("0.19"))}")
        appendLine("tag ${ReportingPeriods.operatingDay(LocalDate.parse("2026-10-25"), ZoneId.of("Europe/Berlin"))}")
    }

    @JvmStatic fun main(args: Array<String>) { print(lauf()) }
}

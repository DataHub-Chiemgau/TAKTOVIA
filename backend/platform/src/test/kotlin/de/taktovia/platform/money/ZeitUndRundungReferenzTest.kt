package de.taktovia.platform.money

import de.taktovia.platform.TestDatabase
import de.taktovia.platform.problem.ViolationCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.math.BigInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Referenztestkatalog Rundung (LH-6.19-NFR-012, WP-M5-07, Kap. 03 §2.1, PH-AT-013): feste Eingaben, feste Erwartungen.
 * Katalog der Zeit: `de.taktovia.platform.time.ZeitUndRundungReferenzTest`.
 */
class ZeitUndRundungReferenzTest {
    private val ids = listOf("A", "B", "C")

    @Test
    fun largest_remainder_100_00_drei_anteile() {
        val parts = LargestRemainder.distributeEqually(10000, ids)
        assertEquals(listOf(3334L, 3333L, 3333L), parts)   // 33,34 / 33,33 / 33,33 EUR, IDs A < B < C
        assertEquals(10000L, parts.sum())
        // Gleichstand ist stabil nach Kennung, nicht nach Eingabereihenfolge
        assertEquals(listOf(3333L, 3334L, 3333L), LargestRemainder.distributeEqually(10000, listOf("C", "A", "B")))
        // gewichtet: 99 im Verhältnis 70/20/10 (Reste 0,3 / 0,8 / 0,9: den Rest bekommt der größte Restanteil)
        assertEquals(listOf(69L, 20L, 10L), LargestRemainder.distribute(99, listOf("a" to BigDecimal("70"), "b" to BigDecimal("20"), "c" to BigDecimal("10"))))
        // Steuerrundung HALF_UP je Steuergruppe: 10,50 EUR netto zu 19 % = 1,995 -> 2,00 (nicht 1,99)
        assertEquals(BigInteger.valueOf(200), MoneyRounding.tax(BigInteger.valueOf(1050), BigDecimal("0.19")))
        assertEquals(BigInteger.valueOf(1000), MoneyRounding.netOfGross(BigInteger.valueOf(1190), BigDecimal("0.19")))
        assertEquals(BigInteger.valueOf(3), MoneyRounding.halfUp(BigDecimal("2.5"))); assertEquals(BigInteger.valueOf(-3), MoneyRounding.halfUp(BigDecimal("-2.5")))
        assertEquals(BigInteger.valueOf(2), MoneyRounding.halfUp(BigDecimal("2.4999999999")))
    }

    @Test
    fun largest_remainder_100_01_drei_anteile_und_ruecknahme() {
        val parts = LargestRemainder.distributeEqually(10001, ids)
        assertEquals(listOf(3334L, 3334L, 3333L), parts)   // 33,34 / 33,34 / 33,33 EUR
        assertEquals(10001L, parts.sum())
        // Rücknahme spiegelt die gespeicherten Teilbeträge (vollständig)
        assertEquals(listOf(-3334L, -3334L, -3333L), LargestRemainder.reverse(parts))
        assertEquals(listOf(-3334L, -3333L, -3333L), LargestRemainder.reverse(LargestRemainder.distributeEqually(10000, ids)))
        assertEquals(0L, parts.sum() + LargestRemainder.reverse(parts).sum())
        // Teilrücknahme: nach den gespeicherten Teilbeträgen, Summe exakt, nie mehr als gespeichert je Empfänger
        val teil = LargestRemainder.reversePartial(ids, parts, 5001)
        assertEquals(-5001L, teil.sum()); assertTrue(teil.indices.all { -teil[it] <= parts[it] }, teil.toString())
        assertEquals(LargestRemainder.reverse(parts), LargestRemainder.reversePartial(ids, parts, 10001))
        assertThrows<IllegalArgumentException> { LargestRemainder.reversePartial(ids, parts, 10002) }
        // negative Korrektur: zuerst der absolute Betrag, dann das Vorzeichen
        assertEquals(listOf(-3334L, -3334L, -3333L), LargestRemainder.distributeEqually(-10001, ids))
        // jede Summe bleibt exakt, auch bei Gleichstand über viele Empfänger
        for (n in 1..13) for (total in listOf(0L, 1L, 99L, 100L, 10001L, 9_007_199_254_740_991L)) {
            val p = LargestRemainder.distributeEqually(total, (1..n).map { "id%02d".format(it) })
            assertEquals(total, p.sum(), "n=$n total=$total"); assertTrue(p.max() - p.min() <= 1)
        }
        assertEquals(listOf(9_007_199_254_740_991L), LargestRemainder.distributeEqually(MoneyLimits.MAX_SAFE, listOf("x")))
    }

    @Test
    fun betrag_ausserhalb_bereich_und_falscher_waehrungsexponent_abgelehnt() {
        val v = MoneyValidator(StaticCurrencyCatalog(mapOf("EUR" to 2, "JPY" to 0)))
        fun m(a: Any?, c: String = "EUR", e: Int = 2) = mapOf("amount_minor" to a, "currency" to c, "exponent" to e)
        fun codes(x: Map<String, Any?>, signed: Boolean = false) = v.validate(x, "/p", signed).map { it.pointer to it.code }
        assertEquals(listOf("/p/amount_minor" to ViolationCode.MONEY_OUT_OF_RANGE), codes(m(MoneyLimits.MAX_SAFE + 1)))
        assertEquals(listOf("/p/amount_minor" to ViolationCode.MONEY_OUT_OF_RANGE), codes(m(-MoneyLimits.MAX_SAFE - 1), signed = true))
        assertEquals(listOf("/p/amount_minor" to ViolationCode.MONEY_OUT_OF_RANGE), codes(m(BigInteger.valueOf(MoneyLimits.MAX_SAFE).add(BigInteger.ONE))))
        assertEquals(emptyList<Any>(), codes(m(MoneyLimits.MAX_SAFE)))
        assertEquals(listOf("/p/exponent" to ViolationCode.EXPONENT_MISMATCH), codes(m(100, "EUR", 3)))
        assertEquals(listOf("/p/exponent" to ViolationCode.EXPONENT_MISMATCH), codes(m(100, "JPY", 2)))
        assertEquals(listOf("/p/currency" to ViolationCode.CURRENCY_UNKNOWN), codes(m(100, "XXX", 2)))
        assertEquals(listOf("/p/amount_minor" to ViolationCode.MONEY_NOT_INTEGER), codes(m(1.5)))
        assertThrows<IllegalArgumentException> { Money(MoneyLimits.MAX_SAFE + 1, "EUR", 2) }
        assertThrows<IllegalArgumentException> { SignedMoney(-MoneyLimits.MAX_SAFE - 1, "EUR", 2) }
        assertThrows<IllegalArgumentException> { LargestRemainder.distribute(100, listOf("a" to BigDecimal("-1"), "b" to BigDecimal("2"))) }
        assertThrows<ArithmeticException> { LargestRemainder.distribute(100, listOf("a" to BigDecimal("0.123456789"), "b" to BigDecimal.ONE)) }   // mehr als 8 Nachkommastellen
    }

    @Test
    fun zwei_laeufe_zwei_instanzen_neustart_identisch() {
        val lauf1 = ReferenzLauf.lauf(); val lauf2 = ReferenzLauf.lauf()
        assertEquals(lauf1, lauf2)
        assertTrue(lauf1.contains("lr3 10000 [3334, 3333, 3333]") && lauf1.contains("lr3 10001 [3334, 3334, 3333]"), lauf1)
        // zwei frische JVMs (Neustart, zwei Instanzen) mit fremder Systemzone und Sprache liefern byte-identische Ergebnisse
        val java = ProcessHandle.current().info().command().orElse("java")
        fun fresh(vararg opts: String): String {
            val p = ProcessBuilder(listOf(java, *opts, "-cp", System.getProperty("java.class.path"), "de.taktovia.platform.money.ReferenzLauf")).redirectErrorStream(true).start()
            val out = p.inputStream.readBytes().toString(Charsets.UTF_8).lines().filterNot { it.startsWith("Picked up") }.joinToString("\n")
            assertTrue(p.waitFor(60, TimeUnit.SECONDS)); assertEquals(0, p.exitValue(), out)
            return out
        }
        assertEquals(lauf1, fresh("-Duser.timezone=Asia/Tokyo", "-Duser.language=tr", "-Duser.country=TR"))
        assertEquals(lauf1, fresh("-Duser.timezone=America/New_York", "-Duser.language=de", "-Duser.country=DE"))
    }

    @Test
    fun parallele_umlage_genau_eine_wirksame() {
        // Echte parallele Transaktionen gegen die Datenbank: acht Arbeiter verteilen dasselbe Kostenereignis; ein partieller Unique-Index
        // erlaubt genau eine wirksame Umlage, alle Arbeiter berechnen byte-identische Zeilen. (Der Dienst selbst: AllocationServiceTest
        // `parallele_allokationsrevisionen_genau_eine_aktiv`, Modul documents-finance.)
        TestDatabase.require()
        val ds = TestDatabase.admin()
        val table = "zz_umlage_" + System.nanoTime()
        ds.connection.use { c -> c.createStatement().use {
            it.execute("CREATE TABLE $table (event_id int NOT NULL, recipient text NOT NULL, amount_minor bigint NOT NULL, PRIMARY KEY (event_id, recipient))")   // sql-lint: ok Testtabelle mit eindeutigem Namen
            it.execute("CREATE TABLE ${table}_set (event_id int PRIMARY KEY, worker int NOT NULL)")   // sql-lint: ok Testtabelle mit eindeutigem Namen
        } }
        try {
            val workers = 8; val pool = Executors.newFixedThreadPool(workers); val go = CountDownLatch(1)
            val computed = (1..workers).map { w -> pool.submit<Pair<Boolean, List<Long>>> {
                val lines = LargestRemainder.distributeEqually(10001, ids)
                go.await()
                ds.connection.use { c ->
                    c.autoCommit = false
                    val won = c.prepareStatement("INSERT INTO ${table}_set(event_id, worker) VALUES (1, ?) ON CONFLICT DO NOTHING").use { ps -> ps.setInt(1, w); ps.executeUpdate() } == 1   // sql-lint: ok Testtabelle mit eindeutigem Namen
                    if (won) ids.forEachIndexed { i, id -> c.prepareStatement("INSERT INTO $table VALUES (1, ?, ?)").use { ps -> ps.setString(1, id); ps.setLong(2, lines[i]); ps.executeUpdate() } }   // sql-lint: ok Testtabelle mit eindeutigem Namen
                    c.commit(); won to lines
                }
            } }
            go.countDown(); val out = computed.map { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
            assertEquals(1, out.count { it.first }, "genau eine wirksame Umlage")
            assertEquals(1, out.map { it.second }.toSet().size, "alle Arbeiter berechnen identische Zeilen")
            ds.connection.use { c -> c.createStatement().use { st -> st.executeQuery("SELECT recipient, amount_minor FROM $table ORDER BY recipient").use { rs ->   // sql-lint: ok Testtabelle mit eindeutigem Namen
                val rows = buildList { while (rs.next()) add(rs.getString(1) to rs.getLong(2)) }
                assertEquals(listOf("A" to 3334L, "B" to 3334L, "C" to 3333L), rows)
            } } }
        } finally {
            ds.connection.use { c -> c.createStatement().use { it.execute("DROP TABLE IF EXISTS $table, ${table}_set") } }   // sql-lint: ok Testtabelle mit eindeutigem Namen
        }
    }
}

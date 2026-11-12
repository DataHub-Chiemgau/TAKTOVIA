package de.taktovia.farepayment

import de.taktovia.farepayment.api.FareBreakdown
import de.taktovia.farepayment.api.FareComponentCode
import de.taktovia.farepayment.api.FareEngine
import de.taktovia.farepayment.api.FareRejection
import de.taktovia.farepayment.api.FareRequest
import de.taktovia.farepayment.api.FareResult
import de.taktovia.farepayment.api.TariffRuleView
import de.taktovia.platform.money.MoneyLimits
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.file.Files
import java.nio.file.Path
import java.util.Random
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Tarifengine (WP-M2-08, LH-6.10-PAY-002), rein und ohne Datenbank. Die erwarteten Preise der Testrelationen stammen aus `tests/fixtures/tarif_testrelationen.csv` und sind
 * **von Hand und unabhängig von der Engine gerechnet** (Testannahmen bis zur Bestätigung des Pilottarifs durch den Auftraggeber, D-05/OP-12).
 */
class FareEngineTest {
    private val repo: Path = Path.of(System.getProperty("taktovia.repoRoot"))
    private fun zone(name: String) = UUID.nameUUIDFromBytes(name.toByteArray())
    private val ruleId = UUID.fromString("00000000-0000-4000-8000-0000000000aa")

    private fun rule(type: String = "FESTPREIS", amount: Long = 450, personMode: String = "PER_PERSON", zoneFrom: UUID? = null, zoneTo: UUID? = null, rate: String? = null,
                     fee: Long = 0, tax: String = "0.07", taxMode: String = "GROSS", no: Int = 1) =
        TariffRuleView(UUID.nameUUIDFromBytes("r$no$zoneFrom$zoneTo".toByteArray()), no, type, personMode, amount, zoneFrom, zoneTo, rate, fee, tax, taxMode, "DE-2026-1")

    private class Relation(val id: String, val rule: TariffRuleView, val request: FareRequest, val gross: Long, val net: Long, val tax: Long, val transport: Long, val fee: Long)

    private fun relations(): List<Relation> {
        val lines = Files.readAllLines(repo.resolve("tests/fixtures/tarif_testrelationen.csv"))
        val header = lines.first().split(';'); fun col(r: List<String>, n: String) = r[header.indexOf(n)]
        return lines.drop(1).filter { it.isNotBlank() }.mapIndexed { i, l ->
            val c = l.split(';', limit = header.size).let { it + List(header.size - it.size) { "" } }
            fun zoneOf(n: String) = col(c, n).ifBlank { null }?.let(::zone)
            Relation(col(c, "relation"), rule(col(c, "regeltyp"), col(c, "betrag_minor").toLong(), col(c, "person_modus"), zoneOf("zone_regel_von"), zoneOf("zone_regel_nach"), col(c, "satz_je_km").ifBlank { null },
                col(c, "gebuehr_minor").toLong(), col(c, "steuersatz"), col(c, "steuermodus"), i + 1),
                FareRequest(col(c, "personen").toInt(), zoneOf("anfrage_zone_von"), zoneOf("anfrage_zone_nach"), col(c, "strecke_m").ifBlank { null }?.toLong()),
                col(c, "erwartet_brutto").toLong(), col(c, "erwartet_netto").toLong(), col(c, "erwartet_steuer").toLong(), col(c, "erwartet_transport").toLong(), col(c, "erwartet_gebuehr").toLong())
        }
    }

    private fun priced(r: FareResult): FareBreakdown = (r as? FareResult.Priced ?: error("nicht bepreist: $r")).breakdown
    private fun rejected(r: FareResult): FareRejection = (r as? FareResult.Rejected ?: error("unerwartet bepreist: $r")).reason

    @Test
    fun testrelationen_liefern_erwartete_preise() {
        val all = relations()
        assertTrue(all.size >= 12, "Fixture vollständig gelesen")
        for (t in listOf("FESTPREIS", "ZONENPREIS", "GRUNDPREIS_STRECKE")) assertTrue(all.any { it.rule.ruleType == t }, "Regeltyp $t in den Testrelationen")
        for (r in all) {
            val b = priced(FareEngine.price("EUR", listOf(r.rule), r.request))
            assertEquals(r.gross, b.grossMinor, "${r.id}: brutto")
            assertEquals(r.net, b.netMinor, "${r.id}: netto")
            assertEquals(r.tax, b.taxMinor, "${r.id}: Steuer")
            assertEquals(r.transport, b.components.filter { it.code == FareComponentCode.TRANSPORT }.sumOf { it.amountMinor }, "${r.id}: Transport")
            assertEquals(r.fee, b.components.filter { it.code == FareComponentCode.SURCHARGE }.sumOf { it.amountMinor }, "${r.id}: Gebühr")
            assertEquals("EUR", b.currency)
            assertTrue(b.components.all { it.ruleId == r.rule.id }, "${r.id}: Komponenten verweisen auf die Regel")
        }
    }

    @Test
    fun komponentensumme_gleich_brutto_property() {
        val rnd = Random(20261112L)
        repeat(5000) { n ->
            val type = listOf("FESTPREIS", "ZONENPREIS", "GRUNDPREIS_STRECKE")[rnd.nextInt(3)]
            val amount = rnd.nextInt(100_000).toLong(); val fee = if (rnd.nextBoolean()) 0L else rnd.nextInt(5_000).toLong()
            val taxRate = listOf("0", "0.07", "0.19", "0.0525", "0.12345678")[rnd.nextInt(5)]
            val mode = if (rnd.nextBoolean()) "GROSS" else "NET"; val mdl = if (rnd.nextBoolean()) "PER_PERSON" else "PER_BOOKING"
            val persons = 1 + rnd.nextInt(FareEngine.MAX_PERSONS); val meters = 1L + rnd.nextInt(200_000)
            val rate = "${rnd.nextInt(500)}.${rnd.nextInt(100_000_000).toString().padStart(8, '0')}"
            val z = zone("z$n")
            val rule = rule(type, amount, mdl, if (type == "ZONENPREIS") z else null, if (type == "ZONENPREIS") z else null, if (type == "GRUNDPREIS_STRECKE") rate else null, fee, taxRate, mode)
            val b = priced(FareEngine.price("EUR", listOf(rule), FareRequest(persons, z, z, meters)))
            // Summe der Komponenten = Brutto; Netto + Steuer = Brutto; Steuer höchstens einmal; keine negativen Komponenten
            assertEquals(b.components.sumOf { it.amountMinor }, b.grossMinor)
            assertEquals(b.grossMinor, b.netMinor + b.taxMinor)
            assertTrue(b.components.count { it.code == FareComponentCode.TAX } <= 1)
            assertTrue(b.components.all { it.amountMinor >= 0 }, b.toString())
            // unabhängiges Orakel (BigDecimal) für den Bruttobetrag
            val units = if (mdl == "PER_PERSON") persons.toLong() else 1L
            val unit = if (type == "GRUNDPREIS_STRECKE") BigDecimal(amount) + BigDecimal(rate).multiply(BigDecimal(meters)).divide(BigDecimal(1000), 0, RoundingMode.HALF_UP) else BigDecimal(amount)
            val base = unit.multiply(BigDecimal(units)) + BigDecimal(fee)
            val expectedGross = if (mode == "GROSS") base else base + base.multiply(BigDecimal(taxRate)).setScale(0, RoundingMode.HALF_UP)
            assertEquals(expectedGross.toLong(), b.grossMinor, "n=$n $rule")
            if (taxRate == "0") assertEquals(0, b.taxMinor)
        }
    }

    @Test
    fun ueberlauf_wird_abgewiesen() {
        val max = MoneyLimits.MAX_SAFE
        val one = FareRequest(1, distanceMeters = 1000)
        // genau an der Grenze: zulässig, eins darüber: abgewiesen
        assertEquals(max, priced(FareEngine.price("EUR", listOf(rule(amount = max, tax = "0")), one)).grossMinor)
        assertEquals(FareRejection.OVERFLOW, rejected(FareEngine.price("EUR", listOf(rule(amount = max, fee = 1, tax = "0")), one)))
        // Multiplikation mit der Personenzahl
        assertEquals(FareRejection.OVERFLOW, rejected(FareEngine.price("EUR", listOf(rule(amount = max / 2 + 1, tax = "0")), FareRequest(2))))
        assertEquals(max / 2 * 2, priced(FareEngine.price("EUR", listOf(rule(amount = max / 2, tax = "0")), FareRequest(2))).grossMinor)
        assertEquals(max / 2 + 1, priced(FareEngine.price("EUR", listOf(rule(amount = max / 2 + 1, personMode = "PER_BOOKING", tax = "0")), FareRequest(2))).grossMinor, "je Buchung: keine Multiplikation")
        // Streckenanteil: Satz * Meter überschreitet den Bereich
        val gs = rule("GRUNDPREIS_STRECKE", 0, rate = "999999999999.99999999", tax = "0")
        assertEquals(FareRejection.OVERFLOW, rejected(FareEngine.price("EUR", listOf(gs), FareRequest(1, distanceMeters = Long.MAX_VALUE))))
        assertEquals(FareRejection.OVERFLOW, rejected(FareEngine.price("EUR", listOf(rule("GRUNDPREIS_STRECKE", max, rate = "1.0", tax = "0")), FareRequest(1, distanceMeters = 1000))))
        // Steuer obendrauf überschreitet den Bruttobereich
        assertEquals(FareRejection.OVERFLOW, rejected(FareEngine.price("EUR", listOf(rule(amount = max, taxMode = "NET", tax = "0.5")), FareRequest(1))))
    }

    @Test
    fun gleiche_eingaben_gleiches_ergebnis_auch_parallel() {
        val rule = rule("GRUNDPREIS_STRECKE", 300, rate = "35.123", fee = 70, tax = "0.19", taxMode = "NET"); val req = FareRequest(3, distanceMeters = 7777)
        val first = FareEngine.price("EUR", listOf(rule), req)
        assertEquals(first, FareEngine.price("EUR", listOf(rule), req)); assertEquals(first.hashCode(), FareEngine.price("EUR", listOf(rule), req).hashCode())
        val pool = Executors.newFixedThreadPool(16)
        try {
            val results = (1..2000).map { pool.submit(Callable { FareEngine.price("EUR", listOf(rule), req) }) }.map { it.get(30, TimeUnit.SECONDS) }
            assertTrue(results.all { it == first }, "bitgleiche Ergebnisse bei paralleler Berechnung")
        } finally { pool.shutdownNow() }
    }

    @Test
    fun mehrpersonenbuchung_nach_regel() {
        val perPerson = rule(amount = 450, tax = "0")
        for (n in listOf(1, 2, 5, 100)) assertEquals(450L * n, priced(FareEngine.price("EUR", listOf(perPerson), FareRequest(n))).grossMinor, "je Person: $n")
        val perBooking = rule(amount = 450, personMode = "PER_BOOKING", tax = "0")
        for (n in listOf(1, 2, 5, 100)) assertEquals(450L, priced(FareEngine.price("EUR", listOf(perBooking), FareRequest(n))).grossMinor, "je Buchung: $n")
        // Grundpreis plus Strecke: Rundung je Person, dann mal Personenzahl (35.5 * 1234 m / 1000 = 43.807 -> 44 je Person)
        val gs = rule("GRUNDPREIS_STRECKE", 250, rate = "35.5", tax = "0")
        assertEquals(3 * 294L, priced(FareEngine.price("EUR", listOf(gs), FareRequest(3, distanceMeters = 1234))).grossMinor)
        // die Gebühr fällt einmal je Buchung an, nicht je Person
        val withFee = rule(amount = 100, fee = 30, tax = "0")
        assertEquals(3 * 100L + 30, priced(FareEngine.price("EUR", listOf(withFee), FareRequest(3))).grossMinor)
        // Personenzahl außerhalb des Bereichs
        for (bad in listOf(0, -1, FareEngine.MAX_PERSONS + 1)) assertEquals(FareRejection.INVALID_PERSONS, rejected(FareEngine.price("EUR", listOf(perPerson), FareRequest(bad))))
    }

    @Test
    fun fehlende_zone_strecke_null_unbekannter_regeltyp_abgewiesen() {
        val a = zone("A"); val b = zone("B"); val c = zone("C")
        val zones = listOf(rule("ZONENPREIS", 500, zoneFrom = a, zoneTo = b, no = 1), rule("ZONENPREIS", 900, zoneFrom = b, zoneTo = a, no = 2))
        assertEquals(500L, priced(FareEngine.price("EUR", zones, FareRequest(1, a, b))).grossMinor)
        assertEquals(900L, priced(FareEngine.price("EUR", zones, FareRequest(1, b, a))).grossMinor, "Rückrichtung ist ein eigenes Paar")
        assertEquals(FareRejection.MISSING_ZONE, rejected(FareEngine.price("EUR", zones, FareRequest(1, a, null))))
        assertEquals(FareRejection.MISSING_ZONE, rejected(FareEngine.price("EUR", zones, FareRequest(1))))
        assertEquals(FareRejection.ZONE_NOT_PRICED, rejected(FareEngine.price("EUR", zones, FareRequest(1, a, c))))
        assertEquals(FareRejection.ZONE_NOT_PRICED, rejected(FareEngine.price("EUR", zones, FareRequest(1, a, a))))
        val gs = rule("GRUNDPREIS_STRECKE", 300, rate = "1.5")
        for (m in listOf(0L, -5L)) assertEquals(FareRejection.DISTANCE_REQUIRED, rejected(FareEngine.price("EUR", listOf(gs), FareRequest(1, distanceMeters = m))))
        assertEquals(FareRejection.DISTANCE_REQUIRED, rejected(FareEngine.price("EUR", listOf(gs), FareRequest(1))))
        assertEquals(FareRejection.UNKNOWN_RULE_TYPE, rejected(FareEngine.price("EUR", listOf(rule("PAUSCHALE")), FareRequest(1))))
        assertEquals(FareRejection.NO_RULES, rejected(FareEngine.price("EUR", emptyList(), FareRequest(1))))
        assertEquals(FareRejection.INCONSISTENT_RULES, rejected(FareEngine.price("EUR", listOf(rule(no = 1), gs.copy(ruleNo = 2)), FareRequest(1, distanceMeters = 5))))
        assertEquals(FareRejection.INCONSISTENT_RULES, rejected(FareEngine.price("EUR", listOf(rule(no = 1), rule(no = 2)), FareRequest(1))), "zwei Festpreise")
        assertEquals(FareRejection.INVALID_RATE, rejected(FareEngine.price("EUR", listOf(rule(tax = "1.5")), FareRequest(1))))
        assertEquals(FareRejection.INVALID_RATE, rejected(FareEngine.price("EUR", listOf(rule(tax = "0,07")), FareRequest(1))))
        assertEquals(FareRejection.INVALID_RATE, rejected(FareEngine.price("EUR", listOf(gs.copy(ratePerKm = null)), FareRequest(1, distanceMeters = 5))))
    }

    @Test
    fun steuer_nicht_doppelt_und_keine_gleitkommaarithmetik() {
        // Brutto-Regel: die Steuer steckt im Betrag und wird nicht noch einmal aufgeschlagen; genau eine TAX-Komponente
        val b = priced(FareEngine.price("EUR", listOf(rule(amount = 1070, fee = 107, tax = "0.07")), FareRequest(1)))
        assertEquals(1177L, b.grossMinor, "Brutto = Betrag + Gebühr, die Steuer ist enthalten")
        assertEquals(1, b.components.count { it.code == FareComponentCode.TAX })
        assertEquals(77L, b.taxMinor)
        // Netto-Regel: die Steuer kommt einmal auf die Summe obendrauf
        val n = priced(FareEngine.price("EUR", listOf(rule(amount = 1000, fee = 100, tax = "0.10", taxMode = "NET")), FareRequest(1)))
        assertEquals(110L, n.taxMinor); assertEquals(1210L, n.grossMinor); assertEquals(1, n.components.count { it.code == FareComponentCode.TAX })
        // ohne Steuersatz keine TAX-Komponente
        assertFalse(priced(FareEngine.price("EUR", listOf(rule(tax = "0")), FareRequest(1))).components.any { it.code == FareComponentCode.TAX })
        // statisch: im Codepfad der Berechnung und der Regelprüfung kommt keine Gleitkommaarithmetik vor
        val base = repo.resolve("backend/modules/fare-payment/src/main/kotlin/de/taktovia/farepayment")
        val forbidden = Regex("\\b(Double|Float)\\b|\\.toDouble\\(|\\.toFloat\\(|\\bMath\\.(pow|sqrt|floor|ceil|round)\\b|[0-9]\\.[0-9]+[fF]\\b|[0-9]\\.[0-9]+[eE][0-9+-]")
        val files = listOf("api/FareEngine.kt", "api/Tariffs.kt", "internal/JdbcTariffs.kt", "internal/TariffRuleParser.kt").map { base.resolve(it) }
        for (f in files) {
            val code = Files.readAllLines(f).filterNot { it.trim().startsWith("*") || it.trim().startsWith("/**") || it.trim().startsWith("//") }.joinToString("\n")
            assertEquals(emptyList<String>(), forbidden.findAll(code).map { it.value }.toList(), "${f.fileName}: Gleitkomma im Codepfad")
        }
    }
}

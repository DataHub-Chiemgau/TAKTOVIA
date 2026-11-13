package de.taktovia.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * WP-M5-07 (LH-6.19-NFR-012): Fachmodule und Adapter runden nicht selbst, rechnen Geld nie in Gleitkomma und halten keine Ortszeit ohne Zone.
 * Positiv gegen den echten Code, negativ gegen die absichtlich fehlerhafte Probeberechnung `archfixtures.bad.config.ZeitGeldVerstoss`.
 */
class ZeitGeldRegelnTest {
    private val real = ClassFileImporter().withImportOption(ImportOption.DoNotIncludeTests()).importPackages("de.taktovia")
    private val bad = "de.taktovia.archfixtures.bad"
    private val fixtures = ClassFileImporter().importPackages(bad)

    @Test
    fun keine_eigene_rundung_kein_gleitkomma_kein_localdatetime_in_fachmodulen() {
        ArchitectureRules().timeAndMoneyRules().checkAll(real)
        // die Regeln erfassen wirklich Fachcode (kein leerer Treffer) und die Allowlists enthalten nur existierende Klassen
        assertTrue(real.any { it.name.startsWith("de.taktovia.farepayment.") })
        (ArchitectureRules.ROUNDING_ALLOWLIST + ArchitectureRules.LOCAL_DATE_TIME_ALLOWLIST).forEach { n ->
            assertTrue(real.any { it.name == n || it.name.startsWith("$n$") }, "Allowlist-Eintrag ohne Klasse: $n")
        }
        // Probeberechnung mit Gleitkomma und modulinterner Rundung: jede der drei Regeln schlägt an
        val probe = ArchitectureRules(root = bad, modules = listOf("identity", "config"), adapters = listOf("psp"))
        probe.timeAndMoneyRules().forEach { (name, rule) ->
            val e = assertThrows(AssertionError::class.java, { rule.check(fixtures) }, "Regel schlägt nicht an: $name")
            assertTrue(e.message!!.contains("ZeitGeldVerstoss"), "$name: ${e.message}")
        }
        assertEquals(3, probe.timeAndMoneyRules().size)
    }

    @Test
    fun regeluebersicht_ist_versioniert_und_nennt_jede_regel_mit_fundstelle() {
        val root = generateSequence(java.nio.file.Path.of("").toAbsolutePath()) { it.parent }.first { java.nio.file.Files.exists(it.resolve("docs/regeln/zeit-und-rundung.md")) }
        val doc = java.nio.file.Files.readString(root.resolve("docs/regeln/zeit-und-rundung.md"))
        assertTrue(Regex("\\*\\*Version:\\*\\* \\d+\\.\\d+\\.\\d+").containsMatchIn(doc), "Version fehlt")
        val rows = doc.lines().filter { Regex("^\\| [ZG]-\\d\\d \\|").containsMatchIn(it) }
        assertTrue(rows.size >= 18, "erwartet Z-01..Z-09 und G-01..G-09, gefunden ${rows.size}")
        rows.forEach { r -> assertTrue(r.split("|").map { it.trim() }.filter { it.isNotEmpty() }.size == 5, "Spalten (ID, Regel, Fundstelle, Code, Module): $r") }
        listOf("Tarif", "Zahlung", "Storno", "Schichtdauer", "Kostenumlage", "KPI-Periode").forEach { assertTrue(doc.contains(it), "nutzendes Modul fehlt: $it") }
    }
}

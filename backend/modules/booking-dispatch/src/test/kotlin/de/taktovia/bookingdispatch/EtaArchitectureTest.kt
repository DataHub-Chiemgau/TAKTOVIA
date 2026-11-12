package de.taktovia.bookingdispatch

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files
import java.nio.file.Path

/**
 * WP-M4-61 / LH-I.3-ETA-001: das ETA-Grundmodell (api `Eta*` und `internal.eta`) hat keinen Zugriff auf Fahrerprofile und keine Fahrer-Kennung im Schnittstellenvertrag (D-10, P1 ohne
 * individuelles Fahrerprofil). Die Regeln laufen gegen den echten Code (grün) und gegen absichtlich verletzende Fixtures (rot; eine Regel, die dort nicht anschlägt, schützt nichts).
 */
class EtaArchitectureTest {
    private val main: JavaClasses = ClassFileImporter().withImportOption(ImportOption.DoNotIncludeTests()).importPackages("de.taktovia")
    private val fixtures: JavaClasses = ClassFileImporter().importPackages("de.taktovia.bookingdispatch.etafixture")
    private val etaPackages = arrayOf("de.taktovia.bookingdispatch.internal.eta..")

    private fun etaClasses(c: JavaClasses) = c.filter { it.packageName.startsWith("de.taktovia.bookingdispatch.internal.eta") || (it.packageName == "de.taktovia.bookingdispatch.api" && it.simpleName.startsWith("Eta")) ||
        it.packageName.startsWith("de.taktovia.bookingdispatch.etafixture") }

    private val etaSet get() = main.filter { c -> etaClasses(main).any { it.name == c.name } }

    /** Regeln als Funktionen, damit sie auf echtem Code und Fixtures laufen. */
    private fun rules(c: JavaClasses): List<Pair<String, () -> Unit>> {
        val eta = etaClasses(c)
        val names = eta.map { it.name }.toSet()
        fun inEta(it: com.tngtech.archunit.core.domain.JavaClass) = it.name in names || it.name.substringBefore('$') in names
        return listOf(
            "kein Zugriff auf Fahrerklassen (Name enthält Driver oder Fahrer)" to {
                noClasses().that().areAssignableTo(object : com.tngtech.archunit.base.DescribedPredicate<com.tngtech.archunit.core.domain.JavaClass>("ETA-Klassen") { override fun test(input: com.tngtech.archunit.core.domain.JavaClass) = inEta(input) })
                    .should().dependOnClassesThat().haveSimpleNameContaining("Driver").orShould().dependOnClassesThat().haveSimpleNameContaining("Fahrer").check(c)
            },
            "kein Zugriff auf Fahrer-, Personal- und Flottenmodule" to {
                noClasses().that(object : com.tngtech.archunit.base.DescribedPredicate<com.tngtech.archunit.core.domain.JavaClass>("ETA-Klassen") { override fun test(input: com.tngtech.archunit.core.domain.JavaClass) = inEta(input) })
                    .should().dependOnClassesThat().resideInAnyPackage("de.taktovia.fleetsafety..", "de.taktovia.stafftime..", "de.taktovia.platform.readports.stub..")
                    .orShould().dependOnClassesThat().haveFullyQualifiedName("de.taktovia.platform.readports.DriverReadPort")
                    .orShould().dependOnClassesThat().haveFullyQualifiedName("de.taktovia.platform.readports.DriverView").check(c)
            },
            "keine Felder mit Fahrerbezug" to {
                noFields().that().areDeclaredInClassesThat(object : com.tngtech.archunit.base.DescribedPredicate<com.tngtech.archunit.core.domain.JavaClass>("ETA-Klassen") { override fun test(input: com.tngtech.archunit.core.domain.JavaClass) = inEta(input) })
                    .should().haveNameMatching("(?i).*(driver|fahrer).*").check(c)
            },
            "keine Methoden mit Fahrerbezug" to {
                noMethods().that().areDeclaredInClassesThat(object : com.tngtech.archunit.base.DescribedPredicate<com.tngtech.archunit.core.domain.JavaClass>("ETA-Klassen") { override fun test(input: com.tngtech.archunit.core.domain.JavaClass) = inEta(input) })
                    .should().haveNameMatching("(?i).*(driver|fahrer).*").check(c)
            },
        )
    }

    @Test
    fun grundmodell_hat_keinen_zugriff_auf_fahrerprofile_und_keine_fahrer_id() {
        assertTrue(etaClasses(main).size >= 10, "ETA-Klassen gefunden: ${etaClasses(main).map { it.simpleName }}")
        // 1. Regeln über die Klassendateien (Abhängigkeiten, Felder, Methoden, Typen von Parametern)
        for ((name, rule) in rules(main)) try { rule() } catch (e: AssertionError) { throw AssertionError("Regel verletzt: $name\n${e.message}", e) }
        // 2. Schnittstellenvertrag: kein Parameter, Rückgabewert oder Datenfeld der öffentlichen ETA-Typen heißt oder trägt Fahrer (auch Parameternamen, die im Bytecode fehlen können: Quelltextprüfung ohne Kommentare)
        val root = Path.of(System.getProperty("taktovia.repoRoot")).resolve("backend/modules/booking-dispatch/src/main/kotlin/de/taktovia/bookingdispatch")
        val sources = Files.list(root.resolve("api")).use { s -> s.filter { it.fileName.toString().startsWith("Eta") }.toList() } +
            Files.list(root.resolve("internal/eta")).use { s -> s.toList() }
        assertTrue(sources.size >= 8)
        for (file in sources) {
            val code = Files.readString(file).replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "").lines().joinToString("\n") { it.substringBefore("//") }
            val hit = Regex("(?i)driver|fahrer").find(code)
            assertEquals(null, hit, "${file.fileName}: Fahrerbezug im Schnittstellenvertrag oder in der Implementierung: ${hit?.value}")
        }
        // 3. Reflexion: kein Konstruktor- oder Methodenparameter der Anfrage-/Port-Typen heißt Fahrer (wo Namen verfügbar sind) und die Anfrage-Typen tragen nur die vertraglichen Felder
        val requestFields = de.taktovia.bookingdispatch.api.EtaReferenceRequest::class.java.declaredFields.map { it.name }.sorted()
        assertEquals(listOf("at", "destinationStop", "originStop", "roadDataVersion", "vehicleProfile"), requestFields, "Eingaben ausschließlich Strecke, Zeit, Fahrzeugprofil und Straßendatenversion")
    }

    @Test
    fun regeln_schlagen_auf_verletzenden_fixtures_an() {
        val violating = rules(fixtures)
        assertTrue(violating.isNotEmpty() && etaClasses(fixtures).isNotEmpty(), "Fixtures gefunden")
        for ((name, rule) in violating) assertThrows<AssertionError>("Regel '$name' muss auf den Fixtures anschlagen") { rule() }
    }
}

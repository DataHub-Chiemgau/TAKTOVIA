package de.taktovia.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** LH-6.1-SYS-011 / WP-M1-26 AK 2: ein bestätigender Befehl ohne PolicyCheck-Aufruf lässt den Architekturtest scheitern (grün auf dem Code, rot auf Fixtures). */
class PolicyCheckArchitectureTest {
    @Test
    fun bestaetigender_befehl_ohne_policycheck_scheitert() {
        val bad = ClassFileImporter().importPackages("de.taktovia.archfixtures.bad.policy")
        assertTrue(bad.any { it.simpleName == "UncheckedConfirmation" })
        PolicyCheckRules(root = "de.taktovia.archfixtures").all().forEach { (name, rule) -> assertThrows(AssertionError::class.java, { rule.check(bad) }, "Regel schlägt nicht an: $name") }
        // ein Aufruf über eine Hilfsmethode derselben Klasse genügt, ebenso evaluate
        val good = ClassFileImporter().importPackages("de.taktovia.archfixtures.good.policy")
        assertTrue(good.any { it.simpleName == "CheckedConfirmation" } && good.any { it.simpleName == "IndirectlyCheckedConfirmation" })
        PolicyCheckRules(root = "de.taktovia.archfixtures").all().forEach { (_, rule) -> rule.check(good) }
    }

    @Test
    fun der_echte_code_besteht_die_regel() {
        val classes = ClassFileImporter().withImportOption(ImportOption.DoNotIncludeTests()).importPackages("de.taktovia")
        PolicyCheckRules().all().forEach { (name, rule) -> try { rule.check(classes) } catch (e: AssertionError) { throw AssertionError("Regel verletzt: $name\n${e.message}", e) } }
    }
}

package de.taktovia.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** LH-I.4-MUL-006 / WP-M1-06 AK 2: Architekturtest „tenant_id nicht aus Request-DTOs“ (grün auf dem Code, rot auf Fixtures). */
class RequestContextRulesTest {
    @Test
    fun tenant_id_nicht_aus_request_dtos() {
        val classes = ClassFileImporter().withImportOption(ImportOption.DoNotIncludeTests()).importPackages("de.taktovia")
        assertTrue(classes.any { it.simpleName == "MembershipCreateRequest" } && classes.any { it.simpleName == "TenantSwitchRequest" }, "die DTOs der Identität sind importiert")
        RequestContextRules().all().checkWithDtos(classes)
    }

    @Test
    fun rote_selbsttests_gegen_fixtures() {
        val bad = ClassFileImporter().importPackages("de.taktovia.archfixtures.bad.requestcontext")
        val rules = RequestContextRules(root = "de.taktovia.archfixtures", filterClass = "de.taktovia.archfixtures.nicht.vorhanden.Filter")
        JavaClassesHolder.current = bad
        rules.all().forEach { (name, rule) -> assertThrows(AssertionError::class.java, { rule.check(bad) }, "Regel schlägt nicht an: $name") }
        // gute Fixtures bestehen: markiertes Auswahl-DTO und DTO ohne Mandant
        val good = ClassFileImporter().importPackages("de.taktovia.archfixtures.good.requestcontext")
        JavaClassesHolder.current = good
        RequestContextRules(root = "de.taktovia.archfixtures").all().filterKeys { it != "nur der Anfragefilter erzeugt den RequestContext" }.forEach { (_, r) -> r.check(good) }
        assertTrue(good.any { it.simpleName == "GoodSwitchRequest" })
    }
}

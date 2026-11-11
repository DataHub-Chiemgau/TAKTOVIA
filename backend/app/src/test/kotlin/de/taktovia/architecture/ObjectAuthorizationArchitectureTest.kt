package de.taktovia.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** LH-6.2-IAM-005 / WP-M1-08: Repository mit Dienstbezug rufen den Port der Objektberechtigung auf (grün auf dem Code, rot auf Fixtures). */
class ObjectAuthorizationArchitectureTest {
    @Test
    fun repository_mit_dienstbezug_ruft_port_auf() {
        val classes = ClassFileImporter().withImportOption(ImportOption.DoNotIncludeTests()).importPackages("de.taktovia")
        // die echten Repositories mit Dienstbezug sind markiert und importiert (sonst prüfte die Regel nichts)
        val marked = classes.filter { c -> c.isAnnotatedWith(ServiceScopeRules.SERVICE_SCOPED) }.map { it.simpleName }
        assertTrue("JdbcOrganizationStructure" in marked, "markierte Klassen: $marked")
        ServiceScopeRules().all().forEach { (name, rule) -> try { rule.check(classes) } catch (e: AssertionError) { throw AssertionError("Regel verletzt: $name\n${e.message}", e) } }
    }

    @Test
    fun rote_selbsttests_gegen_fixtures() {
        val bad = ClassFileImporter().importPackages("de.taktovia.archfixtures.bad.servicescope")
        assertTrue(bad.any { it.simpleName == "UncheckedAreaRepository" })
        ServiceScopeRules(root = "de.taktovia.archfixtures").all().forEach { (name, rule) -> assertThrows(AssertionError::class.java, { rule.check(bad) }, "Regel schlägt nicht an: $name") }
        val good = ClassFileImporter().importPackages("de.taktovia.archfixtures.good.servicescope")
        assertTrue(good.any { it.simpleName == "CheckedAreaRepository" })
        ServiceScopeRules(root = "de.taktovia.archfixtures").all().forEach { (_, rule) -> rule.check(good) }
    }
}

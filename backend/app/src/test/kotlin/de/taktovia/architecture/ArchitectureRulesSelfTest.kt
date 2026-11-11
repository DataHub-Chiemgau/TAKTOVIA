package de.taktovia.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.ArchRule
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Rote Selbsttests (WP-M0-03 AK 2/4): jede Regel muss auf absichtlich fehlerhaften Fixtures
 * (`de.taktovia.archfixtures.bad`) fehlschlagen. Sonst wäre sie wirkungslos.
 */
class ArchitectureRulesSelfTest {
    private val bad = "de.taktovia.archfixtures.bad"
    private val classes = ClassFileImporter().importPackages(bad)
    private val rules = ArchitectureRules(root = bad, modules = listOf("identity", "config"), adapters = listOf("psp"))

    private fun mustFail(name: String, rule: ArchRule) {
        assertThrows(AssertionError::class.java, { rule.check(classes) }, "Regel schlägt nicht an: $name")
    }

    @Test fun `Import auf fremdes internal-Paket bricht`() =
        rules.internalIsolation().filterKeys { it.contains("identity") }.forEach { (n, r) -> mustFail(n, r) }

    @Test fun `Schichtverletzungen brechen`() {
        val l = rules.layering()
        mustFail("platform", l.getValue("platform kennt weder Module noch Adapter noch app"))
        mustFail("Module", l.getValue("Module kennen weder Adapter noch app"))
    }

    @Test fun `Redis ausserhalb des Cache-Pakets bricht`() = mustFail("redis", rules.redisOnlyInCachePackage())

    @Test fun `Geld als Double bricht`() = rules.noFloatingPointMoney().forEach { (n, r) -> mustFail(n, r) }

    @Test fun `Adapteraufruf in Transaktion bricht`() = rules.noAdapterCallInTransaction().forEach { (n, r) -> mustFail(n, r) }
}

package de.taktovia.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Positivtest: der aktuelle Stand verletzt keine Regel. */
class ArchitectureTest {
    private val classes = ClassFileImporter().withImportOption(ImportOption.DoNotIncludeTests()).importPackages("de.taktovia")

    @Test
    fun `alle 11 Module, 7 Adapter und platform sind importiert`() {
        val packages = classes.map { it.packageName }.toSet()
        ArchitectureRules.MODULES.forEach { m -> assertTrue(packages.any { it.startsWith("de.taktovia.$m.internal") }, "Modul fehlt: $m") }
        ArchitectureRules.ADAPTERS.forEach { a -> assertTrue(packages.any { it.startsWith("de.taktovia.adapters.$a.internal") }, "Adapter fehlt: $a") }
        assertTrue(packages.any { it.startsWith("de.taktovia.platform.cache") })
    }

    @Test
    fun `aktueller Stand verletzt keine Architekturregel`() = ArchitectureRules().all().checkAll(classes)
}

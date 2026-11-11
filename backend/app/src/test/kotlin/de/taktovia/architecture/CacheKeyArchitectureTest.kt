package de.taktovia.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.ArchRule
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** Positivtest und rote Selbsttests der Isolationsregeln (WP-M1-11): „kein Cache-/Storage-Zugriff ohne Tenant im Schlüssel“. */
class CacheKeyArchitectureTest {
    private val prod = ClassFileImporter().withImportOption(ImportOption.DoNotIncludeTests()).importPackages("de.taktovia")
    private val bad = ClassFileImporter().importPackages("de.taktovia.archfixtures.bad.isolation")
    private fun mustFail(name: String, rule: ArchRule) { assertThrows(AssertionError::class.java, { rule.check(bad) }, "Regel schlägt nicht an: $name") }

    @Test
    fun kein_cache_zugriff_ohne_tenant_im_schluessel() {
        IsolationRules.all().forEach { (name, rule) -> try { rule.check(prod) } catch (e: AssertionError) { throw AssertionError("Regel verletzt: $name\n${e.message}", e) } }
        mustFail("Cache", IsolationRules.cacheAccessOnlyWithTenantKey())
    }

    @Test
    fun kein_storage_zugriff_ohne_mandantenpraefix_im_schluessel() = mustFail("Storage", IsolationRules.storageAccessOnlyWithTenantKey())

    @Test
    fun objektspeicher_sdk_ausserhalb_des_storage_pakets_bricht() =
        mustFail("SDK", IsolationRules.storageSdkOnlyInStoragePackage(root = "de.taktovia.archfixtures.bad.none", sdkPackages = listOf("java.util.concurrent..")))

    @Test
    fun listen_baustein_mit_oeffentlichem_konstruktor_bricht() =
        mustFail("Listing", IsolationRules.listingHasNoPublicConstructor("de.taktovia.archfixtures.bad.isolation.BadListing"))
}

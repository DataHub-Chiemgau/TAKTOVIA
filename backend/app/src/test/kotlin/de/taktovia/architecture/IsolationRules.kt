package de.taktovia.architecture

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaMethod
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.constructors
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.core.domain.JavaModifier

/**
 * Architekturregeln der Isolation außerhalb der Datenbank (WP-M1-11, LH-6.1-SYS-001): **kein Cache- oder Storage-Zugriff ohne Mandant im Schlüssel** und kein Listen-/Export-Baustein
 * ohne erzwungenen Tenantfilter. Jede Regel hat rote Selbsttests an Fixtures (`IsolationRulesSelfTest`).
 *
 * - Cache und Objektspeicher kennen nur typisierte Schlüssel ([CACHE_KEY], [OBJECT_KEY]): jede schlüsselnehmende Methode einer Umsetzung von `TenantCache` bzw. `TenantObjectStore`
 *   hat als ersten Parameter den Schlüsseltyp; eine Überladung mit Zeichenkettenschlüssel ist verboten.
 * - Redis nur im Cache-Paket (bestehende Regel), Objektspeicher-SDKs (MinIO, S3) nur im Paket `platform.storage`.
 * - `TenantListing` hat keinen öffentlichen Konstruktor: Instanzen entstehen nur über `TenantListing.of(ListSource)`, deren SQL stets den Mandantenfilter trägt.
 */
object IsolationRules {
    const val TENANT_CACHE = "de.taktovia.platform.cache.TenantCache"
    const val CACHE_KEY = "de.taktovia.platform.cache.CacheKey"
    const val TENANT_OBJECT_STORE = "de.taktovia.platform.storage.TenantObjectStore"
    const val OBJECT_KEY = "de.taktovia.platform.storage.ObjectKey"
    const val TENANT_LISTING = "de.taktovia.platform.isolation.TenantListing"
    private val KEYED = setOf("get", "put", "evict", "delete", "exists", "contains", "remove", "set", "getOrPut", "computeIfAbsent")

    private fun implementing(type: String) = object : DescribedPredicate<JavaClass>("implementiert $type") {
        override fun test(c: JavaClass) = c.allRawInterfaces.any { it.name == type }
    }

    /** Schlüsselnehmende Methoden (get, put, evict, delete …) einer Umsetzung von [type] haben als ersten Parameter [keyType]. */
    fun keyedMethodsTakeTypedKey(type: String, keyType: String): ArchRule = methods().that().areDeclaredInClassesThat(implementing(type))
        .and().arePublic().and().haveNameMatching(KEYED.joinToString("|", "(", ")"))
        .should(object : ArchCondition<JavaMethod>("als ersten Parameter $keyType nehmen (Mandant im Schlüssel)") {
            override fun check(m: JavaMethod, events: ConditionEvents) {
                if (m.modifiers.contains(JavaModifier.SYNTHETIC) || m.modifiers.contains(JavaModifier.BRIDGE)) return
                val first = m.rawParameterTypes.firstOrNull()?.name
                if (first != keyType) events.add(SimpleConditionEvent.violated(m, "${m.fullName}: Schlüsselzugriff ohne $keyType (erster Parameter: ${first ?: "keiner"})"))
            }
        }).allowEmptyShould(true)

    fun cacheAccessOnlyWithTenantKey(): ArchRule = keyedMethodsTakeTypedKey(TENANT_CACHE, CACHE_KEY)
    fun storageAccessOnlyWithTenantKey(): ArchRule = keyedMethodsTakeTypedKey(TENANT_OBJECT_STORE, OBJECT_KEY)

    /** Objektspeicher-SDKs nur im Paket `platform.storage`. [sdkPackages] ist für den Selbsttest austauschbar. */
    fun storageSdkOnlyInStoragePackage(root: String = "de.taktovia", sdkPackages: List<String> = listOf("io.minio..", "software.amazon.awssdk..", "com.amazonaws..")): ArchRule =
        noClasses().that().resideOutsideOfPackage("$root.platform.storage..").should().dependOnClassesThat().resideInAnyPackage(*sdkPackages.toTypedArray()).allowEmptyShould(true)

    /** Der Listen-/Export-Baustein hat keinen öffentlichen Konstruktor (Instanzen nur über die Fabrik mit Positivliste und Mandantenfilter). */
    fun listingHasNoPublicConstructor(className: String = TENANT_LISTING): ArchRule =
        constructors().that().areDeclaredIn(className).should(object : ArchCondition<com.tngtech.archunit.core.domain.JavaConstructor>("nicht öffentlich sein (Kotlin-Marker-Konstruktor ausgenommen)") {
            override fun check(c: com.tngtech.archunit.core.domain.JavaConstructor, events: ConditionEvents) {
                // `private constructor` erzeugt in Kotlin einen synthetischen öffentlichen Marker-Konstruktor (DefaultConstructorMarker); er ist von außen nicht aufrufbar
                val marker = c.rawParameterTypes.any { it.name == "kotlin.jvm.internal.DefaultConstructorMarker" }
                if (c.modifiers.contains(JavaModifier.PUBLIC) && !marker) events.add(SimpleConditionEvent.violated(c, "${c.fullName} ist öffentlich"))
            }
        }).allowEmptyShould(false)

    fun all(): Map<String, ArchRule> = mapOf(
        "Cache-Zugriff nur mit CacheKey (Mandant im Schlüssel)" to cacheAccessOnlyWithTenantKey(),
        "Storage-Zugriff nur mit ObjectKey (Mandantenpräfix)" to storageAccessOnlyWithTenantKey(),
        "Objektspeicher-SDKs nur in platform.storage" to storageSdkOnlyInStoragePackage(),
        "TenantListing ohne öffentlichen Konstruktor" to listingHasNoPublicConstructor(),
    )

}

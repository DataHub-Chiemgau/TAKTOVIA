package de.taktovia.architecture

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.domain.JavaMethod
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices

/**
 * Architekturregeln aus Umsetzungsplan §2 (WP-M0-03). Alle Regeln sind über das Wurzelpaket parametrisiert,
 * damit dieselben Regeln einmal gegen den echten Code (grün) und einmal gegen absichtlich fehlerhafte Fixtures
 * (rot) laufen. Eine Regel, die auf den Fixtures nicht fehlschlägt, schützt nichts.
 */
class ArchitectureRules(
    private val root: String = "de.taktovia",
    private val modules: List<String> = MODULES,
    private val adapters: List<String> = ADAPTERS,
) {
    companion object {
        val MODULES = listOf(
            "identity", "config", "servicegeo", "bookingdispatch", "journeyfederation", "farepayment",
            "fleetsafety", "stafftime", "documentsfinance", "communicationsupport", "analytics",
        )
        val ADAPTERS = listOf("psp", "partnertomp", "partnerosdm", "timetablegtfs", "erpexport", "ocr", "notification")
        const val TRANSACTIONAL = "org.springframework.transaction.annotation.Transactional"
        const val AUDIT_REQUIRED = "de.taktovia.platform.audit.AuditRequired"
        const val AUDIT_PORT = "de.taktovia.platform.audit.AuditPort"
    }

    private val modulePackages get() = modules.map { "$root.$it.." }.toTypedArray()
    private val adapterRoot get() = "$root.adapters.."

    /** Leitlinie 1: ein Modul greift nie auf das `internal`-Paket eines anderen Moduls oder Adapters zu. */
    fun internalIsolation(): Map<String, ArchRule> =
        (modules.map { it to "$root.$it" } + adapters.map { it to "$root.adapters.$it" }).associate { (name, pkg) ->
            "internal von $name nur innerhalb von $name" to noClasses().that().resideOutsideOfPackage("$pkg..")
                .should().dependOnClassesThat().resideInAPackage("$pkg.internal..")
        }

    /** Leitlinie 1: Abhängigkeitsrichtungen Modul/Adapter -> platform, app -> alle, niemals umgekehrt. */
    fun layering(): Map<String, ArchRule> = buildMap {
        put("platform kennt weder Module noch Adapter noch app", noClasses().that().resideInAPackage("$root.platform..")
            .should().dependOnClassesThat().resideInAnyPackage(*modulePackages, adapterRoot, "$root.app.."))
        put("Module kennen weder Adapter noch app", noClasses().that().resideInAnyPackage(*modulePackages)
            .should().dependOnClassesThat().resideInAnyPackage(adapterRoot, "$root.app.."))
        put("nur app darf app kennen", noClasses().that().resideOutsideOfPackage("$root.app..")
            .should().dependOnClassesThat().resideInAPackage("$root.app.."))
        adapters.forEach { a ->
            val others = adapters.filter { it != a }.map { "$root.adapters.$it.." }.toTypedArray()
            put("Adapter $a kennt keine anderen Adapter", noClasses().that().resideInAPackage("$root.adapters.$a..")
                .should().dependOnClassesThat().resideInAnyPackage(*others))
        }
        put("Module sind frei von Zyklen", slices().matching("$root.(${modules.joinToString("|")})..")
            .should().beFreeOfCycles())
    }

    /** Leitlinie 4: Redis nur über das Cache-Paket der platform; dort nie führender Zustand (Reviewregel). */
    fun redisOnlyInCachePackage(): ArchRule = noClasses().that().resideOutsideOfPackage("$root.platform.cache..")
        .should().dependOnClassesThat().resideInAnyPackage("io.lettuce..", "org.springframework.data.redis..", "redis.clients..")

    /** Leitlinie 5: keine Gleitkommatypen für Geld (`*Minor`, Paket `money`). */
    fun noFloatingPointMoney(): Map<String, ArchRule> = mapOf(
        "Felder *Minor sind nicht Float/Double" to noFields().that().haveNameMatching(".*[Mm]inor")
            .should().haveRawType(java.lang.Double.TYPE).orShould().haveRawType(java.lang.Float.TYPE)
            .orShould().haveRawType(java.lang.Double::class.java).orShould().haveRawType(java.lang.Float::class.java).allowEmptyShould(true),
        "Methoden *Minor liefern nicht Float/Double" to noMethods().that().haveNameMatching("(get)?[a-zA-Z]*[Mm]inor")
            .should().haveRawReturnType(java.lang.Double.TYPE).orShould().haveRawReturnType(java.lang.Float.TYPE).allowEmptyShould(true),
        "Felder im Paket money sind nicht Float/Double" to noFields().that().areDeclaredInClassesThat().resideInAPackage("$root..money..")
            .should().haveRawType(java.lang.Double.TYPE).orShould().haveRawType(java.lang.Float.TYPE).allowEmptyShould(true),
    )

    /**
     * Leitlinie 3 (Näherung): Code mit `@Transactional` ruft keine Adapter. Die vollständige Regel
     * „kein PSP-/Partneraufruf in offener SQL-Transaktion“ wird mit dem Transaktionsmanagement in M1 geschärft.
     */
    fun noAdapterCallInTransaction(): Map<String, ArchRule> = mapOf(
        "@Transactional-Klassen rufen keine Adapter" to noClasses().that().areAnnotatedWith(TRANSACTIONAL)
            .should().dependOnClassesThat().resideInAPackage(adapterRoot).allowEmptyShould(true),
        "@Transactional-Methoden rufen keine Adapter" to noMethods().that().areAnnotatedWith(TRANSACTIONAL)
            .should(callsAdapter()).allowEmptyShould(true),
    )

    private fun callsAdapter() = object : ArchCondition<JavaMethod>("einen Adapter aufrufen") {
        override fun check(method: JavaMethod, events: ConditionEvents) {
            method.callsFromSelf.filter { it.targetOwner.packageName.startsWith("$root.adapters") }
                .forEach { events.add(SimpleConditionEvent.satisfied(method, "${method.fullName} ruft ${it.target.fullName}")) }
        }
    }

    /**
     * WP-M1-46: ein als auditpflichtig markierter Befehl (`@AuditRequired`) ruft `AuditPort.record` auf, direkt oder über Methoden derselben
     * Klasse. Sonst scheitert der Build. Bezug auf die platform-Typen ist fest (nicht über `root` parametrisiert).
     */
    fun auditRequiredCallsAuditPort(): ArchRule = methods().that().areAnnotatedWith(AUDIT_REQUIRED)
        .should(callAuditRecord()).allowEmptyShould(true)

    private fun callAuditRecord() = object : ArchCondition<JavaMethod>("AuditPort.record aufrufen (direkt oder über Methoden derselben Klasse)") {
        private fun callsRecord(m: JavaMethod, seen: MutableSet<JavaMethod>): Boolean {
            if (!seen.add(m)) return false
            return m.methodCallsFromSelf.any { c ->
                (c.target.name == "record" && c.targetOwner.isAssignableTo(AUDIT_PORT)) ||
                    (c.targetOwner == m.owner && c.target.resolveMember().orElse(null)?.let { callsRecord(it, seen) } == true)
            }
        }
        override fun check(method: JavaMethod, events: ConditionEvents) {
            if (!callsRecord(method, mutableSetOf())) events.add(SimpleConditionEvent.violated(method, "${method.fullName} ist @AuditRequired, ruft aber AuditPort.record nicht auf"))
        }
    }

    fun all(): Map<String, ArchRule> = internalIsolation() + layering() + ("Redis nur in platform.cache" to redisOnlyInCachePackage()) +
        noFloatingPointMoney() + noAdapterCallInTransaction() + ("@AuditRequired ruft AuditPort.record" to auditRequiredCallsAuditPort())
}

fun Map<String, ArchRule>.checkAll(classes: JavaClasses) = forEach { (name, rule) ->
    try { rule.check(classes) } catch (e: AssertionError) { throw AssertionError("Regel verletzt: $name\n${e.message}", e) }
}

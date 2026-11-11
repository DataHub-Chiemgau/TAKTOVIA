package de.taktovia.architecture

import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.domain.JavaMethod
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes

/**
 * Architekturregeln zum Anfragekontext (WP-M1-06, LH-I.4-MUL-006 AK 2): Der Mandant kommt nie aus dem Request.
 *  1. Anfrage-DTOs (Klassen `*Request`, Typ eines `@RequestBody`-Parameters) tragen kein Feld `tenant_id`/`tenantId`,
 *     außer dem ausdrücklich markierten DTO der Mandantenauswahl (`@TenantSelectionRequest`; dort ist es nur der Wunsch).
 *  2. Handler lesen den Mandanten nicht aus `@RequestParam`, `@RequestHeader` oder `@PathVariable` (Name enthält `tenant`).
 *  3. Nur der Anfragefilter erzeugt einen `RequestContext`; kein anderer Code kann sich einen Kontext ausstellen.
 * Parametrisiert über das Wurzelpaket, damit dieselben Regeln gegen Fixtures (rot) und den echten Code (grün) laufen.
 */
class RequestContextRules(private val root: String = "de.taktovia", private val filterClass: String = "$root.app.identity.RequestContextFilter") {
    companion object {
        const val REQUEST_BODY = "org.springframework.web.bind.annotation.RequestBody"
        const val SELECTION = "de.taktovia.platform.context.TenantSelectionRequest"
        const val REQUEST_CONTEXT = "de.taktovia.platform.context.RequestContext"
        private val TENANT_FIELD = Regex("(?i)^_*tenant_?id.*")
        private val TENANT_NAME = Regex("(?i).*tenant.*")
        private val SOURCES = listOf("org.springframework.web.bind.annotation.RequestParam", "org.springframework.web.bind.annotation.RequestHeader", "org.springframework.web.bind.annotation.PathVariable")
    }

    /** Handler = Methode mit einer Spring-Mapping-Annotation (`@GetMapping`, `@PostMapping`, …). */
    private fun isHandler(m: JavaMethod) = m.annotations.any { it.rawType.packageName == "org.springframework.web.bind.annotation" && it.rawType.simpleName.endsWith("Mapping") }

    private fun tenantField(c: JavaClass) = c.fields.firstOrNull { TENANT_FIELD.matches(it.name) }

    /** Alle als Anfrage-DTO geltenden Klassen von [classes]: `*Request`-Klassen und Typen von `@RequestBody`-Parametern. */
    private fun requestDtos(all: JavaClasses): Set<JavaClass> {
        val byName = all.filter { it.simpleName.endsWith("Request") && !it.isInterface && !it.isAnnotation }.toSet()
        val bodies = all.flatMap { it.methods }.filter(::isHandler)
            .flatMap { m -> m.parameters.filter { p -> p.isAnnotatedWith(REQUEST_BODY) }.map { it.rawType } }
            .filter { it.packageName.startsWith(root) }.toSet()
        return byName + bodies
    }

    fun noTenantIdInRequestDtos(): ArchRule = classes().should(object : ArchCondition<JavaClass>("kein tenant_id-Feld in Anfrage-DTOs ausser der Mandantenauswahl") {
        private var dtos: Set<JavaClass>? = null
        override fun check(item: JavaClass, events: ConditionEvents) {
            val all = dtos ?: requestDtos(JavaClassesHolder.current).also { dtos = it }
            if (item in all && !item.isAnnotatedWith(SELECTION)) tenantField(item)?.let { events.add(SimpleConditionEvent.violated(item, "${item.name} liest ${it.name} aus dem Request; der Mandant kommt nur aus dem RequestContext")) }
        }
    }).allowEmptyShould(true)

    fun noTenantFromRequestParameters(): ArchRule = com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods().that(object : com.tngtech.archunit.base.DescribedPredicate<JavaMethod>("Handler mit Mapping-Annotation") {
            override fun test(input: JavaMethod) = isHandler(input)
        }).should(object : ArchCondition<JavaMethod>("keinen Mandanten aus @RequestParam/@RequestHeader/@PathVariable lesen") {
            override fun check(item: JavaMethod, events: ConditionEvents) {
                item.parameters.forEach { p ->
                    SOURCES.filter { p.isAnnotatedWith(it) }.forEach { a ->
                        val ann = p.getAnnotationOfType(a)
                        val names = listOfNotNull(ann.get("value").orElse(null), ann.get("name").orElse(null)).flatMap { v -> if (v is Array<*>) v.map { it.toString() } else listOf(v.toString()) }
                        if (names.any { TENANT_NAME.matches(it) }) events.add(SimpleConditionEvent.violated(item, "${item.fullName} nimmt den Mandanten aus dem Request ($names)"))
                    }
                }
            }
        }).allowEmptyShould(true)

    fun onlyTheFilterCreatesRequestContext(): ArchRule = classes().that().doNotHaveFullyQualifiedName(filterClass).and().doNotHaveFullyQualifiedName(REQUEST_CONTEXT)
        .and().resideInAPackage("$root..").should(object : ArchCondition<JavaClass>("keinen RequestContext erzeugen (nur der Anfragefilter darf das)") {
            override fun check(item: JavaClass, events: ConditionEvents) {
                item.constructorCallsFromSelf.filter { it.targetOwner.name == REQUEST_CONTEXT }.forEach {
                    events.add(SimpleConditionEvent.violated(item, "${it.origin.fullName} erzeugt einen RequestContext; Kontexte entstehen nur im Anfragefilter"))
                }
            }
        }).allowEmptyShould(true)

    /** Die DTO-Regel braucht alle Klassen (Typen der `@RequestBody`-Parameter); [check] stellt sie bereit. */
    fun all(): Map<String, ArchRule> = mapOf(
        "kein tenant_id in Anfrage-DTOs" to noTenantIdInRequestDtos(),
        "kein Mandant aus RequestParam/RequestHeader/PathVariable" to noTenantFromRequestParameters(),
        "nur der Anfragefilter erzeugt den RequestContext" to onlyTheFilterCreatesRequestContext(),
    )
}

/** Hält die aktuell geprüften Klassen für die DTO-Regel (ArchUnit reicht einer Bedingung nur das Einzelobjekt). */
object JavaClassesHolder {
    @Volatile var current: JavaClasses = com.tngtech.archunit.core.importer.ClassFileImporter().importClasses()
}

fun Map<String, ArchRule>.checkWithDtos(classes: JavaClasses) {
    JavaClassesHolder.current = classes
    checkAll(classes)
}

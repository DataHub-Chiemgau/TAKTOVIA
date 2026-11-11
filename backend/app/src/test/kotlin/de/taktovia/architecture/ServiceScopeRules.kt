package de.taktovia.architecture

import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes

/**
 * Architekturregel zur Objekt- und Dienstberechtigung (WP-M1-08, LH-6.2-IAM-005): Jede als `@ServiceScoped` markierte Klasse (Repository oder Dienst, der Objekte
 * **mit Dienstbezug** liest oder ändert) ruft den `ObjectAuthorizer` auf, direkt oder über eine Methode derselben Klasse. Ein Repository mit Dienstbezug ohne Aufruf lässt
 * den Build scheitern. Parametrisiert über das Wurzelpaket, damit dieselbe Regel gegen Fixtures (rot) und den echten Code (grün) läuft.
 */
class ServiceScopeRules(private val root: String = "de.taktovia") {
    companion object {
        const val SERVICE_SCOPED = "de.taktovia.platform.authz.ServiceScoped"
        const val AUTHORIZER = "de.taktovia.platform.authz.ObjectAuthorizer"
    }

    fun serviceScopedClassesCallTheAuthorizer(): ArchRule = classes().that().areAnnotatedWith(SERVICE_SCOPED).and().resideInAPackage("$root..")
        .should(object : ArchCondition<JavaClass>("den ObjectAuthorizer aufrufen (Port der Dienst- und Objektberechtigung)") {
            override fun check(item: JavaClass, events: ConditionEvents) {
                val calls = item.methods.any { m -> m.methodCallsFromSelf.any { it.targetOwner.isAssignableTo(AUTHORIZER) } }
                if (!calls) events.add(SimpleConditionEvent.violated(item, "${item.name} hat Dienstbezug (@ServiceScoped), ruft den ObjectAuthorizer aber nicht auf"))
            }
        }).allowEmptyShould(true)

    fun all(): Map<String, ArchRule> = mapOf("@ServiceScoped ruft ObjectAuthorizer auf" to serviceScopedClassesCallTheAuthorizer())
}

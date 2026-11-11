package de.taktovia.architecture

import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaMethod
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods

/**
 * Architekturregel zur serverseitigen Regelprüfung (WP-M1-26, LH-6.1-SYS-011): Jede als `@ConfirmingCommand` markierte Methode (bestätigender Befehl: Buchungsbestätigung,
 * Tarifprüfung …) ruft `PolicyCheck.check` oder `PolicyCheck.evaluate` auf, direkt oder über Methoden derselben Klasse. Ein bestätigender Befehl ohne Aufruf lässt den Build
 * scheitern. Parametrisiert über das Wurzelpaket, damit dieselbe Regel gegen Fixtures (rot) und den echten Code (grün) läuft.
 */
class PolicyCheckRules(private val root: String = "de.taktovia") {
    companion object {
        const val CONFIRMING = "de.taktovia.platform.policy.ConfirmingCommand"
        const val POLICY_CHECK = "de.taktovia.platform.policy.PolicyCheck"
    }

    fun confirmingCommandsCallPolicyCheck(): ArchRule = methods().that().areAnnotatedWith(CONFIRMING).and().areDeclaredInClassesThat().resideInAPackage("$root..")
        .should(object : ArchCondition<JavaMethod>("PolicyCheck.check bzw. evaluate aufrufen (direkt oder über Methoden derselben Klasse)") {
            private fun calls(m: JavaMethod, seen: MutableSet<JavaMethod>): Boolean {
                if (!seen.add(m)) return false
                return m.methodCallsFromSelf.any { c ->
                    (c.target.name == "check" || c.target.name == "evaluate") && c.targetOwner.isAssignableTo(POLICY_CHECK) ||
                        (c.targetOwner == m.owner && c.target.resolveMember().orElse(null)?.let { calls(it, seen) } == true)
                }
            }
            override fun check(item: JavaMethod, events: ConditionEvents) {
                if (!calls(item, mutableSetOf())) events.add(SimpleConditionEvent.violated(item, "${item.fullName} ist @ConfirmingCommand, ruft PolicyCheck aber nicht auf"))
            }
        }).allowEmptyShould(true)

    fun all(): Map<String, ArchRule> = mapOf("@ConfirmingCommand ruft PolicyCheck auf" to confirmingCommandsCallPolicyCheck())
}

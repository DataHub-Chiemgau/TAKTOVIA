package de.taktovia.platform.policy

import de.taktovia.platform.audit.ActorRef
import de.taktovia.platform.audit.AuditActions
import de.taktovia.platform.audit.AuditEntry
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.audit.AuditRequired
import de.taktovia.platform.audit.ObjectRef
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import org.springframework.jdbc.core.JdbcOperations
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Serverseitige Regelprüfung (WP-M1-26, LH-6.1-SYS-011): Angebots- und Betriebsregeln werden vor der Bestätigung aus der **wirksamen Konfiguration** aufgelöst, nie aus
 * Werten des Clients. Der Befehl trägt nur **Fakten**, die die Fachlogik des Servers aus ihren eigenen Daten bildet (z. B. angebotene Wartezeit, Vorlauf), nie Regel-,
 * Preis- oder Revisionswerte aus der Anfrage; die Regelrevision stammt ausschließlich aus der serverseitigen Auflösung zum Zeitpunkt der Prüfung (Serverzeit).
 */
data class PolicyScope(val regionId: UUID? = null, val serviceId: UUID? = null, val vehicleId: UUID? = null)

/** Eine aufgelöste Regel: Wert und Herkunft (Ebene, Revision); `revisionId` `null` bei globaler Vorgabe oder ohne Wert. */
data class ResolvedRule(val parameter: String, val value: Any?, val level: String?, val revisionId: UUID?, val revision: Long?)

data class ResolvedRules(val asOf: Instant, val schemaVersion: String, val rules: Map<String, ResolvedRule>) {
    /** Die beteiligten Regelrevisionen (ohne Duplikate, stabil sortiert). */
    val revisionIds: List<UUID> get() = rules.values.mapNotNull { it.revisionId }.distinct().sortedBy { it.toString() }
}

/** Quelle der wirksamen Regeln; die Anwendung verdrahtet sie mit der Präzedenzauflösung des Moduls `config` (die Plattform kennt kein Fachmodul). */
fun interface PolicyRuleSource {
    /** Löst [parameters] im Gebiet [scope] zum Zeitpunkt [at] in der laufenden Transaktion [jdbc] auf. */
    fun resolve(jdbc: JdbcOperations, ctx: RequestContext, scope: PolicyScope, parameters: Set<String>, at: Instant): ResolvedRules
}

/**
 * Ein zu prüfender Befehl: [name], Gebiet und **Fakten** (Schlüssel `a-z0-9_`). [quotedRevisions] sind die Regelrevisionen, auf denen ein früheres Angebot beruhte
 * (aus dem **gespeicherten** Angebot, nie aus der Anfrage); weichen die jetzt wirksamen ab, ist eine neue Preis- und Regelauskunft nötig (409 `REQUOTE_REQUIRED`).
 */
class PolicyCommand(val name: String, val scope: PolicyScope, val facts: Map<String, Any?>, val quotedRevisions: Set<UUID>? = null) {
    init {
        require(Regex("[a-z][a-z0-9_]{1,62}").matches(name)) { "ungültiger Befehlsname" }
        require(facts.keys.all { Regex("[a-z][a-z0-9_]{0,62}").matches(it) }) { "ungültiger Faktenname" }
    }
    override fun toString() = "PolicyCommand($name)"   // keine Fakten in Logs
}

/** Eine Regel eines Befehls: prüft die Fakten gegen die aufgelösten Regelwerte und liefert Verletzungen mit Pointer `/facts/<name>`. */
fun interface PolicyRule { fun evaluate(rules: ResolvedRules, facts: Map<String, Any?>): List<Violation> }

/** Vorgefertigte Regelbausteine: Zahlenfakt gegen Parameter. Ein fehlender Fakt oder Wert ist eine Verletzung (fail-closed), ein fehlender Regelwert ist keine Beschränkung. */
object PolicyRules {
    fun atMost(parameter: String, fact: String) = PolicyRule { rules, facts -> compare(rules, facts, parameter, fact) { f, limit -> f <= limit }.let { violation(it, fact, ViolationCode.ABOVE_MAXIMUM) } }
    fun atLeast(parameter: String, fact: String) = PolicyRule { rules, facts -> compare(rules, facts, parameter, fact) { f, limit -> f >= limit }.let { violation(it, fact, ViolationCode.BELOW_MINIMUM) } }
    /** Boolescher Parameter muss `true` sein, wenn der Fakt `true` ist (z. B. Pooling nur, wenn erlaubt). */
    fun allowedWhen(parameter: String, fact: String) = PolicyRule { rules, facts ->
        val wanted = facts[fact]; val allowed = rules.rules[parameter]?.value
        if (wanted == true && allowed != true) listOf(Violation("/facts/$fact", ViolationCode.INVALID_VALUE)) else emptyList()
    }

    private fun compare(rules: ResolvedRules, facts: Map<String, Any?>, parameter: String, fact: String, ok: (Double, Double) -> Boolean): Boolean? {
        val limit = (rules.rules[parameter]?.value as? Number)?.toDouble() ?: return null   // keine Regel: keine Beschränkung
        val value = (facts[fact] as? Number)?.toDouble() ?: return false                      // Fakt fehlt oder ist keine Zahl: verweigert
        return ok(value, limit)
    }
    private fun violation(ok: Boolean?, fact: String, code: ViolationCode) = if (ok == null || ok) emptyList() else listOf(Violation("/facts/$fact", code))
}

/** Regelsatz eines Befehls: die dafür aufzulösenden [parameters] und die [rules]. */
class PolicyDefinition(val command: String, val parameters: Set<String>, val rules: List<PolicyRule>)

/** Ergebnis einer bestandenen Prüfung: die serverseitig aufgelöste Regelrevision für Audit und Snapshot des bestätigten Objekts (z. B. `fare_quote.config_revision_id`). */
data class PolicyResult(val command: String, val asOf: Instant, val schemaVersion: String, val ruleRevisions: List<UUID>, val rules: Map<String, ResolvedRule>)

sealed interface PolicyDecision {
    data class Allowed(val result: PolicyResult) : PolicyDecision
    /** Eine Regel ist verletzt (422); [ruleRevisions] ist der Stand, gegen den entschieden wurde. */
    data class Rejected(val violations: List<Violation>, val ruleRevisions: List<UUID>) : PolicyDecision
    /** Die Regelrevisionen haben sich seit dem Angebot geändert (409 `REQUOTE_REQUIRED`); es wird nie stillschweigend mit der alten Revision bestätigt. */
    data class RequoteRequired(val ruleRevisions: List<UUID>) : PolicyDecision
}

/**
 * Port „prüfe Befehl X im Kontext Y gegen die wirksamen Regeln zum Zeitpunkt T“. Pflichtaufruf vor dem Commit für jeden als [ConfirmingCommand] markierten
 * Befehl (Architekturregel `PolicyCheckRules`).
 */
interface PolicyCheck {
    /** Entscheidung ohne Ausnahme; schreibt bei [PolicyDecision.Allowed] ein Auditereignis `policy.command.checked` mit den Regelrevisionen in die Transaktion [jdbc]. */
    fun evaluate(ctx: RequestContext, jdbc: JdbcOperations, command: PolicyCommand): PolicyDecision

    /** Wie [evaluate], aber [PolicyDecision.Rejected] ergibt 422 `VALIDATION_FAILED` mit Pointern `/facts/<name>`, [PolicyDecision.RequoteRequired] 409 `REQUOTE_REQUIRED`. */
    fun check(ctx: RequestContext, jdbc: JdbcOperations, command: PolicyCommand): PolicyResult = when (val d = evaluate(ctx, jdbc, command)) {
        is PolicyDecision.Allowed -> d.result
        is PolicyDecision.Rejected -> throw ProblemException.invalid(*d.violations.toTypedArray())
        is PolicyDecision.RequoteRequired -> throw ProblemException(ErrorCode.REQUOTE_REQUIRED, "Die geltenden Regeln haben sich geändert; eine neue Preis- und Regelauskunft ist nötig.")
    }
}

/**
 * Kennzeichnet einen **bestätigenden** Befehl (Buchungsbestätigung, Tarifprüfung …): die Methode ruft [PolicyCheck.check] bzw. [PolicyCheck.evaluate] auf, bevor sie
 * festschreibt. Architekturregel `PolicyCheckRules`: ohne Aufruf scheitert der Build.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class ConfirmingCommand

/**
 * Standardumsetzung: löst die Regeln der [definitions] über [source] zur **Serverzeit** auf (die Uhr [clock], nie ein Zeitpunkt des Clients), prüft die Regelrevisionen
 * gegen das Angebot, wertet die Regeln aus und protokolliert die verwendeten Regelrevisionen.
 */
class ServerPolicyCheck(
    private val source: PolicyRuleSource, definitions: Collection<PolicyDefinition>, private val audit: AuditPort, private val clock: Clock = Clock.systemUTC(),
) : PolicyCheck {
    private val byCommand = definitions.associateBy { it.command }.also { require(it.size == definitions.size) { "Befehl mehrfach definiert" } }

    @AuditRequired
    override fun evaluate(ctx: RequestContext, jdbc: JdbcOperations, command: PolicyCommand): PolicyDecision {
        // Ein Befehl ohne Regelsatz wird nie stillschweigend durchgewinkt.
        val definition = byCommand[command.name] ?: throw IllegalStateException("kein Regelsatz für Befehl ${command.name}")
        val rules = source.resolve(jdbc, ctx, command.scope, definition.parameters, clock.instant())
        val revisions = rules.revisionIds
        if (command.quotedRevisions != null && command.quotedRevisions != revisions.toSet()) return PolicyDecision.RequoteRequired(revisions)
        val violations = definition.rules.flatMap { it.evaluate(rules, command.facts) }
        if (violations.isNotEmpty()) return PolicyDecision.Rejected(violations, revisions)
        val result = PolicyResult(command.name, rules.asOf, rules.schemaVersion, revisions, rules.rules)
        audit.record(jdbc, AuditEntry(
            ActorRef((if (ctx.actor.type == ActorType.SERVICE) "service:" else "user:") + ctx.actor.subject), AuditActions.POLICY_CHECKED, ObjectRef("command", ctx.correlationId), ctx.correlationId,
            mapOf("command" to command.name, "rule_revisions" to revisions.map { it.toString() }, "schema_version" to rules.schemaVersion, "as_of" to rules.asOf.toString(),
                "rule_levels" to rules.rules.values.associate { it.parameter to (it.level ?: "NONE") }),
        ))
        return PolicyDecision.Allowed(result)
    }
}

package de.taktovia.platform.fieldprotection

import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.RequestContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/** Schutzklassen des Feldschutzbausteins (WP-M1-31, Kap. 01 §1.7); die Zuordnung der Token zu den fünf Klassen des Kapitels ist offen (SEC-008). */
enum class FieldClass { PII, HR, FINANCE, SUPPORT, PAYMENT_TOKEN }

/** Kennung eines geschützten Feldes (`<objekt>.<feld>`), wie in `feldrechte.json`. */
@JvmInline
value class FieldCode(val value: String) {
    init { require(Regex("[a-z][a-z0-9_]*\\.[a-z][a-z0-9_]*").matches(value)) { "ungültiger Feldcode" } }
    override fun toString() = value
}

object ProtectedFields {
    val SUPPORT_NEEDS = FieldCode("passenger.support_needs")
    val CONTACT = FieldCode("passenger.contact")
    val PROVIDER_REF = FieldCode("payment.provider_ref")
}

/** Ein Recht der Rollen×Feld-Matrix: wer ([role] für Menschen, null für Dienstkonten) darf mit welchen Zwecken lesen. */
data class FieldGrant(val actor: ActorType, val role: String?, val purposes: Set<String>)

data class FieldRule(val field: FieldCode, val fieldClass: FieldClass, val scope: String, val grants: List<FieldGrant>)

sealed interface FieldDecision {
    data object Allowed : FieldDecision
    /** [reason] ist nur für Audit und Log bestimmt, nie für die Antwort an den Aufrufer. */
    data class Denied(val reason: String) : FieldDecision
    data object PurposeRequired : FieldDecision
}

/**
 * Rollen×Feld-Matrix (`fieldprotection/feldrechte.json`, Schema `docs/umsetzungsplan/vertraege/schemas/feldrechte.v1.schema.json`).
 * Ein Feld ist nur lesbar, wenn **alle** vier Bedingungen gelten: das Feld steht in der Matrix, der Funktionsscope des Feldes (Feldscope)
 * steht im [RequestContext], mindestens eine Rolle des Kontexts (bei Dienstkonten: der Akteurtyp) hat ein Recht und der angegebene Zweck gehört
 * zu diesem Recht. Alles andere ist verweigert (fail-closed, OP-24): kein Feld ohne ausdrückliches Recht, kein Recht für den Administrator
 * ohne gesonderte Zuweisung (zusätzliche Mitgliedschaft in einer Fachrolle, Rollen vereinigen sich je Mandant).
 */
class FieldRights private constructor(val version: Int, val purposes: Set<String>, private val rules: Map<FieldCode, FieldRule>) {
    val fields: Set<FieldCode> get() = rules.keys

    fun rule(field: FieldCode): FieldRule? = rules[field]

    /** Reihenfolge: Berechtigung vor Zweck, damit Unberechtigte nie erfahren, ob ein Zweck gefehlt hätte. */
    fun decide(ctx: RequestContext, field: FieldCode, purpose: String?): FieldDecision {
        val rule = rules[field] ?: return FieldDecision.Denied("unknown_field")
        if (!ctx.hasScope(rule.scope)) return FieldDecision.Denied("scope_missing")
        val matching = rule.grants.filter { g ->
            g.actor == ctx.actor.type && (g.actor == ActorType.SERVICE || g.role in ctx.roles)
        }
        if (matching.isEmpty()) return FieldDecision.Denied("role_not_granted")
        if (purpose.isNullOrBlank()) return FieldDecision.PurposeRequired
        if (purpose !in purposes) return FieldDecision.Denied("purpose_unknown")
        return if (matching.any { purpose in it.purposes }) FieldDecision.Allowed else FieldDecision.Denied("purpose_not_granted")
    }

    fun mayRead(ctx: RequestContext, field: FieldCode, purpose: String?) = decide(ctx, field, purpose) == FieldDecision.Allowed

    /**
     * Projektion für DTO-Zuordner: liefert [value] nur, wenn das Feld lesbar ist, sonst `null` (Feld erscheint nicht in der Antwort).
     * Standardantworten rufen dies nie mit einem Zweck auf und erhalten geschützte Felder deshalb nie.
     */
    fun <T> project(ctx: RequestContext, field: FieldCode, purpose: String?, value: T): T? = if (mayRead(ctx, field, purpose)) value else null

    companion object {
        const val RESOURCE = "/fieldprotection/feldrechte.json"
        private val mapper: JsonMapper = JsonMapper.builder().build()

        fun load(): FieldRights = parse(FieldRights::class.java.getResourceAsStream(RESOURCE)?.use { String(it.readAllBytes(), Charsets.UTF_8) }
            ?: error("Feldrechte $RESOURCE fehlen im Klassenpfad"))

        fun parse(json: String): FieldRights {
            val root = mapper.readTree(json)
            val version = root.get("version")?.asInt() ?: error("version fehlt")
            val purposes = root.get("purposes").propertyNames().toSet<String>()
            require(purposes.isNotEmpty()) { "keine Zwecke definiert" }
            val rules = LinkedHashMap<FieldCode, FieldRule>()
            for (f in root.get("fields").values()) {
                val code = FieldCode(f.get("field").asString())
                require(code !in rules) { "Feld $code doppelt" }
                val grants = f.get("grants").values().map { g -> grant(g, purposes) }
                rules[code] = FieldRule(code, FieldClass.valueOf(f.get("class").asString()), f.get("scope").asString(), grants)
            }
            return FieldRights(version, purposes, rules)
        }

        private fun grant(g: JsonNode, purposes: Set<String>): FieldGrant {
            val actor = ActorType.valueOf(g.get("actor").asString())
            val role = g.get("role")?.asString()
            require((actor == ActorType.HUMAN) == (role != null)) { "Recht: Rolle genau bei HUMAN" }
            val ps = g.get("purposes").values().map { it.asString() }.toSet()
            require(ps.isNotEmpty() && purposes.containsAll(ps)) { "Recht mit unbekanntem oder fehlendem Zweck" }
            return FieldGrant(actor, role, ps)
        }
    }
}

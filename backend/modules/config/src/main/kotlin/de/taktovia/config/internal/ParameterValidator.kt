package de.taktovia.config.internal

import com.networknt.schema.Error
import de.taktovia.config.api.ScopeKind
import de.taktovia.config.api.ValidationOutcome
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.ZoneId

/**
 * Prüft Konfigurationswerte gegen einen Katalog (WP-M1-19, LH-I.7-CFG-002/-004). Reine Logik, ohne Datenbankzugriff: unbekannter Name oder
 * unzulässige Ebene, Einheit, Datentyp, Bereich (zugleich Systemgrenze), Sicherheitsinvariante, Format. Die Datenbank prüft dieselben Regeln noch
 * einmal beim Schreiben (`config_value_violations`); ein Test hält beide Seiten zusammen. Es werden alle Verletzungen gesammelt; die Texte der
 * Antwort sind die festen Katalogtexte, nie Eingabewerte.
 */
internal class ParameterValidator(private val catalog: LoadedCatalog, private val mapper: JsonMapper = JsonMapper.builder().build()) {

    fun validate(scope: ScopeKind, values: Map<String, Any?>): ValidationOutcome {
        val violations = mutableListOf<Violation>()
        val normalized = LinkedHashMap<String, Any?>()
        for ((name, raw) in values) {
            val pointer = "/" + name.replace("~", "~0").replace("/", "~1")
            val p = catalog.parameters[name]
            if (p == null || scope !in p.definition.allowedLevels) { violations += Violation(pointer, ViolationCode.UNKNOWN_FIELD); continue }
            val unwrapped = unwrap(p, raw)
            if (unwrapped is Unit) { violations += Violation(pointer, ViolationCode.UNIT_MISMATCH); continue }
            val node: JsonNode = mapper.valueToTree(unwrapped)
            val found = check(p, node, pointer)
            if (found.isEmpty()) normalized[name] = unwrapped else violations += found
        }
        return if (violations.isEmpty()) ValidationOutcome.Valid(catalog.version, normalized) else ValidationOutcome.Invalid(catalog.version, violations)
    }

    /** Hülle `{"value": …, "unit": …}` bei skalaren Parametern: gleiche Einheit → Wert, andere Einheit → [Unit] (Einheitenfehler). */
    private fun unwrap(p: LoadedParameter, raw: Any?): Any? {
        if (p.definition.type == "object" || raw !is Map<*, *> || raw.keys != setOf("value", "unit")) return raw
        return if (raw["unit"] == p.definition.unit) raw["value"] else Unit
    }

    private fun check(p: LoadedParameter, node: JsonNode, pointer: String): List<Violation> {
        val errors: List<Error> = p.schema.validate(node)
        val out = LinkedHashMap<String, Violation>()
        for (e in errors) {
            val at = pointer + e.instanceLocation.toString().let { if (it == "/" || it.isEmpty()) "" else it }
            val v = when (e.keyword) {
                "required" -> Violation(e.property?.let { "$at/${escape(it)}" } ?: at, ViolationCode.REQUIRED)
                "additionalProperties", "unevaluatedProperties" -> Violation(e.property?.takeIf { SAFE_NAME.matches(it) }?.let { "$at/${escape(it)}" } ?: at, ViolationCode.UNKNOWN_FIELD)
                // Die feste Obergrenze der Sicherheitsklasse ist eine Invariante, kein gewöhnlicher Aufzählungsfehler.
                "const" -> Violation(at, if (p.definition.securityClass == "SAFETY_INVARIANT") ViolationCode.SAFETY_INVARIANT_VIOLATED else ViolationCode.NOT_IN_ENUM)
                else -> Violation(at, code(e.keyword))
            }
            out.putIfAbsent(v.pointer, v)
        }
        // Systemgrenze oberhalb des Schema-Minimums (`policy_system_floor()`): gilt zusätzlich, wie in config_value_violations.
        val floor = p.systemFloor
        if (out.isEmpty() && floor != null && node.isNumber && node.decimalValue() < floor) out[pointer] = Violation(pointer, ViolationCode.BELOW_MINIMUM)
        if (out.isEmpty() && p.definition.schema["format"] == "iana-timezone" && !(node.isString && node.asString() in ZoneId.getAvailableZoneIds()))
            out[pointer] = Violation(pointer, ViolationCode.ZONE_UNKNOWN)
        return out.values.toList()
    }

    private fun code(keyword: String?) = when (keyword) {
        "type" -> ViolationCode.TYPE_MISMATCH
        "minimum", "exclusiveMinimum" -> ViolationCode.BELOW_MINIMUM
        "maximum", "exclusiveMaximum" -> ViolationCode.ABOVE_MAXIMUM
        "minLength" -> ViolationCode.TOO_SHORT
        "maxLength" -> ViolationCode.TOO_LONG
        "pattern" -> ViolationCode.PATTERN_MISMATCH
        "format" -> ViolationCode.INVALID_FORMAT
        "enum" -> ViolationCode.NOT_IN_ENUM
        "minItems" -> ViolationCode.TOO_FEW_ITEMS
        "maxItems" -> ViolationCode.TOO_MANY_ITEMS
        "uniqueItems" -> ViolationCode.NOT_UNIQUE
        else -> ViolationCode.INVALID_VALUE
    }

    private fun escape(s: String) = s.replace("~", "~0").replace("/", "~1")

    private companion object { val SAFE_NAME = Regex("^[A-Za-z0-9_.-]{1,64}$") }
}

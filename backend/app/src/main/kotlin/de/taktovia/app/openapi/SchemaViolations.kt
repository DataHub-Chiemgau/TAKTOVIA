package de.taktovia.app.openapi

import com.networknt.schema.Error
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode

/**
 * Bildet Validatorfehler auf den Katalog der Feldverletzungscodes ab. Der Text des Validators (`message`) enthält
 * Eingabewerte und wird nie an Clients gegeben; `message` der Antwort ist der feste Katalogtext des Codes.
 */
object SchemaViolations {
    fun from(errors: Collection<Error>): List<Violation> {
        val byPointer = LinkedHashMap<String, Pair<Violation, Boolean>>()
        for (e in errors) {
            val semantic = e.keyword?.startsWith("x-") == true
            val v = if (semantic) Violation(e.details["pointer"].toString().ifEmpty { "/" }, ViolationCode.valueOf(e.details["code"].toString()))
                    else Violation(pointerOf(e), codeOf(e))
            val old = byPointer[v.pointer]
            // Je Feld die genaueste Verletzung: ein semantischer Code (z. B. MONEY_NEGATIVE) ersetzt den allgemeinen (BELOW_MINIMUM).
            if (old == null || (semantic && !old.second)) byPointer[v.pointer] = v to semantic
        }
        return byPointer.values.map { it.first }
    }

    private fun pointerOf(e: Error): String {
        val base = e.instanceLocation.toString()
        val prop = e.property
        val p = when (e.keyword) {
            "required" -> if (prop != null) "$base/${escape(prop)}" else base
            // Namen unbekannter Felder stammen vom Client: nur unauffällige Namen werden in die Antwort übernommen (kein Reflektieren von Angriffsdaten).
            "additionalProperties", "unevaluatedProperties" -> if (prop != null && SAFE_NAME.matches(prop)) "$base/${escape(prop)}" else base
            else -> base
        }
        return p.ifEmpty { "/" }
    }

    private fun codeOf(e: Error): ViolationCode = when (e.keyword) {
        "required" -> ViolationCode.REQUIRED
        "additionalProperties", "unevaluatedProperties" -> ViolationCode.UNKNOWN_FIELD
        "type" -> ViolationCode.TYPE_MISMATCH
        "minLength" -> ViolationCode.TOO_SHORT
        "maxLength" -> ViolationCode.TOO_LONG
        "minimum", "exclusiveMinimum" -> ViolationCode.BELOW_MINIMUM
        "maximum", "exclusiveMaximum" -> ViolationCode.ABOVE_MAXIMUM
        "pattern" -> ViolationCode.PATTERN_MISMATCH
        "format" -> ViolationCode.INVALID_FORMAT
        "enum", "const" -> ViolationCode.NOT_IN_ENUM
        "minItems" -> ViolationCode.TOO_FEW_ITEMS
        "maxItems" -> ViolationCode.TOO_MANY_ITEMS
        "uniqueItems" -> ViolationCode.NOT_UNIQUE
        else -> ViolationCode.INVALID_VALUE
    }

    private val SAFE_NAME = Regex("^[A-Za-z0-9_.-]{1,64}$")

    private fun escape(s: String) = s.replace("~", "~0").replace("/", "~1")
}

package de.taktovia.app.openapi

import com.networknt.schema.Error
import com.networknt.schema.ExecutionContext
import com.networknt.schema.Schema
import com.networknt.schema.SchemaContext
import com.networknt.schema.SchemaLocation
import com.networknt.schema.keyword.AbstractKeyword
import com.networknt.schema.keyword.AbstractKeywordValidator
import com.networknt.schema.keyword.KeywordValidator
import com.networknt.schema.path.NodePath
import de.taktovia.platform.problem.Violation
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import java.math.BigInteger

/**
 * Semantische Schema-Erweiterungen (`x-money`, `x-time`): prüfen, was JSON Schema nicht ausdrücken kann (Exponent gegen den
 * Währungskatalog, Ortszeit gegen die Zeitzone). Die Prüfer sind Plattformbausteine; hier nur die Anbindung an den Validator.
 * Das Ergebnis trägt den Verletzungscode in `details["code"]` und den absoluten JSON-Pointer in `details["pointer"]`.
 */
class SemanticKeyword(
    name: String,
    private val check: (value: JsonNode, argument: String, pointer: String) -> List<Violation>,
) : AbstractKeyword(name) {
    override fun newValidator(schemaLocation: SchemaLocation, schemaNode: JsonNode, parentSchema: Schema, schemaContext: SchemaContext): KeywordValidator {
        val keyword = this
        val argument = schemaNode.asString()
        return object : AbstractKeywordValidator(keyword, schemaNode, schemaLocation) {
            override fun validate(executionContext: ExecutionContext, node: JsonNode, rootNode: JsonNode, instanceLocation: NodePath) {
                check(node, argument, instanceLocation.toString()).forEach { v ->
                    executionContext.addError(
                        Error.builder().keyword(value).instanceNode(node).instanceLocation(instanceLocation)
                            .schemaLocation(schemaLocation).schemaNode(schemaNode)
                            .details(mapOf("code" to v.code.name, "pointer" to v.pointer)).message(v.code.name).build()
                    )
                }
            }
        }
    }
}

/** Wandelt einen JSON-Baum in einfache Kotlin-Typen: ganze Zahlen als Long/BigInteger, alle anderen Zahlen als Double. */
object JsonPlain {
    fun of(n: JsonNode?): Any? = when (n) {
        null -> null
        is ObjectNode -> LinkedHashMap<String, Any?>().also { m -> n.properties().forEach { (k, v) -> m[k] = of(v) } }
        is ArrayNode -> n.map { of(it) }
        else -> when {
            n.isNull -> null
            n.isBoolean -> n.asBoolean()
            n.isIntegralNumber -> n.bigIntegerValue().let { if (it.bitLength() < 64) it.toLong() else it as BigInteger }
            n.isNumber -> n.doubleValue()
            else -> n.asString()
        }
    }
}

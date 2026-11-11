package de.taktovia.app.policy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.dataformat.yaml.YAMLMapper
import java.nio.file.Files
import java.nio.file.Path

/**
 * LH-6.1-SYS-011 / WP-M1-26: Lint über alle Request-Schemas unter `api/<modul>.yaml` (Konvention `api/request-schema-policy.json`, dieselbe Datei wie `api/tools/lint.py`):
 * additionalProperties:false an jedem Objekt mit Eigenschaften, keine clientseitig setzbaren Regel-, Preis-, Revisions- oder Statusfelder; unbekannte Felder ergeben zur Laufzeit 422.
 */
class RequestSchemaLintTest {
    private val repo = Path.of(System.getProperty("taktovia.repoRoot") ?: error("taktovia.repoRoot fehlt"))
    private val yaml = YAMLMapper.builder().build(); private val json = JsonMapper.builder().build()

    private class Policy(val patterns: List<Regex>, val params: Set<String>, val allow: Set<Pair<String, String>>)

    private fun policy(): Policy {
        val p = json.readTree(Files.readString(repo.resolve("api/request-schema-policy.json")))
        val params = Files.readAllLines(repo.resolve("docs/pflichtenheft/kataloge.csv")).drop(1).map { it.split(';') }.filter { it[0] == "Parameter" }.map { it[3] }.toSet()
        return Policy(p["verbotene_feldnamen"].values().map { Regex(it.asString()) }, params, p["ausnahmen"].values().map { it["schema"].asString() to it["feld"].asString() }.toSet())
    }

    private fun specs(): Map<String, JsonNode> =
        Files.list(repo.resolve("api")).use { s -> s.filter { it.toString().endsWith(".yaml") }.toList() }.associate { it.fileName.toString() to yaml.readTree(Files.readString(it)) }

    /** Alle Verstöge der Request-Schemas der Operationen aus [docs]; Verweise der Form `datei.yaml#/components/schemas/X` bzw. `#/components/schemas/X` werden aufgelöst. */
    private fun violations(docs: Map<String, JsonNode>, policy: Policy): List<String> {
        val out = mutableListOf<String>()
        fun resolve(ref: String, file: String): Pair<JsonNode, String>? {
            val f = ref.substringBefore('#').ifEmpty { file }; val name = ref.substringAfterLast('/')
            return docs[f]?.path("components")?.path("schemas")?.get(name)?.let { it to f }
        }
        fun walk(node: JsonNode?, where: String, named: String?, file: String, seen: Set<String>) {
            if (node == null || !node.isObject) return
            node.get("\$ref")?.let { r ->
                val (target, f) = resolve(r.asString(), file) ?: return
                val key = "$f#" + r.asString().substringAfterLast('/')
                if (key in seen) return
                return walk(target, where, r.asString().substringAfterLast('/'), f, seen + key)
            }
            val props = node.get("properties")
            if (props != null && node.get("additionalProperties")?.let { it.isBoolean && !it.asBoolean() } != true) out += "$where: Request-Schema $named ohne additionalProperties:false"
            props?.propertyNames()?.forEach { field ->
                if ((named to field) !in policy.allow && (policy.patterns.any { it.matches(field) } || field in policy.params))
                    out += "$where: Request-Schema $named enthält das vom Client setzbare Regel-, Preis- oder Statusfeld \"$field\""
                walk(props.get(field), where, named, file, seen)
            }
            walk(node.get("items"), where, named, file, seen)
            for (k in listOf("allOf", "oneOf", "anyOf")) node.get(k)?.values()?.forEach { walk(it, where, named, file, seen) }
        }
        for ((file, doc) in docs) doc.path("paths").properties().forEach { (path, item) ->
            item.properties().forEach { (method, op) ->
                if (method in setOf("post", "put", "patch", "delete")) op.path("requestBody").path("content").path("application/json").get("schema")?.let { walk(it, "${method.uppercase()} $path (${op.path("operationId").asString()})", null, file, emptySet()) }
            }
        }
        return out
    }

    @Test
    fun alle_request_schemas_haben_additional_properties_false_und_keine_regelfelder() {
        val docs = specs(); val pol = policy()
        val ops = docs.values.sumOf { d -> d.path("paths").properties().sumOf { (_, item) -> item.properties().count { (m, op) -> m in setOf("post", "put", "patch", "delete") && op.has("requestBody") } } }
        assertTrue(ops >= 30, "geprüfte Operationen mit Request-Body: $ops")
        assertEquals(emptyList<String>(), violations(docs, pol))
        // die Ausnahmen sind begründet und stehen tatsächlich in den Verträgen (keine toten Ausnahmen)
        val names = docs.values.flatMap { it.path("components").path("schemas").propertyNames().toList() }.toSet()
        assertTrue(pol.allow.all { it.first in names }, "Ausnahme für nicht vorhandenes Schema")
    }

    @Test
    fun rote_selbsttests_verstoesse_werden_gefunden() {
        val pol = policy()
        fun doc(schema: String) = mapOf("t.yaml" to yaml.readTree("""
            paths:
              /v1/x:
                post:
                  operationId: x
                  requestBody: { content: { application/json: { schema: { ${'$'}ref: '#/components/schemas/XRequest' } } } }
            components:
              schemas:
                XRequest: $schema
        """.trimIndent()))
        assertEquals(emptyList<String>(), violations(doc("{ type: object, additionalProperties: false, properties: { name: { type: string } } }"), pol))
        for (field in listOf("price_minor", "max_wait_min", "config_revision_id", "status", "fare", "policy_revision", "rule_revision")) {
            val v = violations(doc("{ type: object, additionalProperties: false, properties: { $field: { type: string } } }"), pol)
            assertTrue(v.any { it.contains(field) }, "$field: $v")
        }
        assertTrue(violations(doc("{ type: object, properties: { name: { type: string } } }"), pol).any { it.contains("additionalProperties") })
        assertTrue(violations(doc("{ type: object, additionalProperties: true, properties: { name: { type: string } } }"), pol).isNotEmpty())
        // verschachtelt: ein Unterobjekt ohne additionalProperties:false wird ebenfalls gefunden
        assertTrue(violations(doc("{ type: object, additionalProperties: false, properties: { sub: { type: object, properties: { a: { type: string } } } } }"), pol).any { it.contains("additionalProperties") })
    }
}

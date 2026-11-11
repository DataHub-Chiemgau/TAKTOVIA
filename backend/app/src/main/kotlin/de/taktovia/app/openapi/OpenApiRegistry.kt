package de.taktovia.app.openapi

import com.networknt.schema.Schema
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SchemaRegistryConfig
import com.networknt.schema.dialect.Dialect
import com.networknt.schema.dialect.Dialects
import com.networknt.schema.path.PathType
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.dataformat.yaml.YAMLMapper

/** Eine Operation der OpenAPI-Verträge (Kap. 05, `api/<modul>.yaml`). */
data class ApiOperation(
    val file: String,
    val method: String,
    val path: String,
    val operationId: String,
    /** Scopes der ersten Sicherheitsanforderung (z. B. `config:publish`). */
    val scopes: List<String>,
    /** Parameter als Komponentenname (bei `$ref`) bzw. Name, z. B. `IfMatch`, `IdempotencyKey`, `limit`. */
    val parameters: Set<String>,
    val requiredParameters: Set<String>,
    val requestSchemaLocation: String?,
    val requestBodyRequired: Boolean,
    val responseStatuses: Set<String>,
    val summary: String,
) {
    private val regex = Regex("^" + path.split('/').joinToString("/") { seg ->
        if (seg.startsWith("{") && seg.endsWith("}")) "[^/]+" else Regex.escape(seg)
    } + "$")
    val variableCount = path.count { it == '{' }
    fun matches(requestPath: String) = regex.matches(requestPath)
}

/**
 * Lädt die OpenAPI-Dateien (je Modul eine, gemeinsame Komponenten in `common.yaml`) und stellt Operationen sowie die
 * geprüften Schemas bereit. Alle Dateien liegen unter derselben Basis-IRI, so lösen sich Verweise der Form
 * `common.yaml#/components/schemas/Money` wie im Quellverzeichnis `api/` auf.
 */
class OpenApiRegistry(documents: Map<String, String>, dialectKeywords: List<SemanticKeyword>) {
    private val yaml = YAMLMapper.builder().build()
    private val json = JsonMapper.builder().build()
    private val docs: Map<String, JsonNode> = documents.mapValues { (_, text) -> yaml.readTree(text) }
    val registry: SchemaRegistry

    val operations: List<ApiOperation>

    init {
        val dialect: Dialect = Dialect.builder(Dialects.getDraft202012()).keywords(dialectKeywords).build()
        val texts = docs.mapKeys { (name, _) -> iri(name) }.mapValues { (_, node) -> json.writeValueAsString(node) }
        registry = SchemaRegistry.withDefaultDialect(dialect) { b ->
            b.schemas(texts).schemaRegistryConfig(SchemaRegistryConfig.builder().formatAssertionsEnabled(true).pathType(PathType.JSON_POINTER).build())
        }
        operations = docs.flatMap { (file, doc) -> parseOperations(file, doc) }
        val dup = operations.groupBy { it.operationId }.filterValues { it.size > 1 }.keys
        check(dup.isEmpty()) { "operationId doppelt: $dup" }
    }

    /** Schema `components/schemas/<name>` der Datei [file] (Standard `common.yaml`). */
    fun schema(name: String, file: String = "common.yaml"): Schema =
        registry.getSchema(SchemaLocation.of("${iri(file)}#/components/schemas/$name"))

    fun requestSchema(op: ApiOperation): Schema? = op.requestSchemaLocation?.let { registry.getSchema(SchemaLocation.of(it)) }

    /** Spezifischste passende Operation (weniger Pfadvariablen gewinnen, z. B. `/v1/x/new` vor `/v1/x/{id}`). */
    fun match(method: String, path: String): ApiOperation? =
        operations.filter { it.method.equals(method, true) && it.matches(path) }.minByOrNull { it.variableCount }

    fun pathExists(path: String) = operations.any { it.matches(path) }
    fun allowedMethods(path: String) = operations.filter { it.matches(path) }.map { it.method.uppercase() }.distinct()

    private fun parseOperations(file: String, doc: JsonNode): List<ApiOperation> {
        val out = mutableListOf<ApiOperation>()
        doc.path("paths").properties().forEach { (path, item) ->
            item.properties().forEach { (method, op) ->
                if (method !in METHODS) return@forEach
                val params = (item.path("parameters").values().toList() + op.path("parameters").values().toList())
                fun name(p: JsonNode) = p.path("\$ref").asString().takeIf { it.isNotEmpty() }?.substringAfterLast('/') ?: p.path("name").asString()
                fun required(p: JsonNode): Boolean {
                    val ref = p.path("\$ref").asString()
                    if (ref.isEmpty()) return p.path("required").asBoolean(false)
                    val target = docs[ref.substringBefore('#').ifEmpty { file }]?.path("components")?.path("parameters")?.path(ref.substringAfterLast('/'))
                    return target?.path("required")?.asBoolean(false) ?: false
                }
                val body = op.path("requestBody")
                val hasSchema = !body.path("content").path("application/json").path("schema").isMissingNode
                val ptr = "${iri(file)}#/paths/${path.replace("~", "~0").replace("/", "~1")}/$method/requestBody/content/application~1json/schema"
                out += ApiOperation(
                    file = file, method = method.uppercase(), path = path, operationId = op.path("operationId").asString(),
                    scopes = op.path("security").path(0).path("oidc").values().map { it.asString() },
                    parameters = params.map(::name).toSet(), requiredParameters = params.filter(::required).map(::name).toSet(),
                    requestSchemaLocation = if (hasSchema) ptr else null, requestBodyRequired = body.path("required").asBoolean(false),
                    responseStatuses = op.path("responses").properties().map { it.key }.toSet(), summary = op.path("summary").asString(),
                )
            }
        }
        return out
    }

    companion object {
        const val BASE = "https://api.taktovia.example/openapi/"
        private val METHODS = setOf("get", "post", "put", "patch", "delete")
        fun iri(file: String) = BASE + file
    }
}

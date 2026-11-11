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

/** Ein Parameter einer Operation (`in`: query, path, header) mit dem Ort seines Schemas. */
data class ParamSpec(val name: String, val location: String, val required: Boolean, val schemaLocation: String?, val type: String?)

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
    val paramSpecs: List<ParamSpec>,
    val requestSchemaLocation: String?,
    val requestBodyRequired: Boolean,
    /** Antwortstatus → Ort des Antwortschemas (`null` ohne Body). */
    val responseSchemas: Map<String, String?>,
    val summary: String,
) {
    val responseStatuses: Set<String> get() = responseSchemas.keys
    private val regex = Regex("^" + path.split('/').joinToString("/") { seg ->
        if (seg.startsWith("{") && seg.endsWith("}")) "[^/]+" else Regex.escape(seg)
    } + "$")
    val variableCount = path.count { it == '{' }
    private val variableNames = Regex("\\{([^/}]+)}").findAll(path).map { it.groupValues[1] }.toList()
    private val capture = Regex("^" + path.split('/').joinToString("/") { seg -> if (seg.startsWith("{") && seg.endsWith("}")) "([^/]+)" else Regex.escape(seg) } + "$")
    fun matches(requestPath: String) = regex.matches(requestPath)

    /** Werte der Pfadvariablen (roh, noch nicht dekodiert). */
    fun pathValues(requestPath: String): Map<String, String> =
        capture.matchEntire(requestPath)?.groupValues?.drop(1)?.let { variableNames.zip(it).toMap() } ?: emptyMap()
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
                val specs = (item.path("parameters").values().toList() + op.path("parameters").values().toList()).mapIndexed { i, p -> paramSpec(file, path, method, i, p) }
                out += ApiOperation(
                    file = file, method = method.uppercase(), path = path, operationId = op.path("operationId").asString(),
                    scopes = op.path("security").path(0).path("oidc").values().map { it.asString() },
                    parameters = params.map(::name).toSet(), requiredParameters = params.filter(::required).map(::name).toSet(), paramSpecs = specs,
                    requestSchemaLocation = if (hasSchema) ptr else null, requestBodyRequired = body.path("required").asBoolean(false),
                    responseSchemas = op.path("responses").properties().associate { (code, r) -> code to responseSchemaLocation(file, path, method, code, r) },
                    summary = op.path("summary").asString(),
                )
            }
        }
        return out
    }

    private fun paramSpec(file: String, path: String, method: String, index: Int, p: JsonNode): ParamSpec {
        val ref = p.path("\$ref").asString()
        val (docName, node, base) = if (ref.isNotEmpty()) {
            val target = ref.substringBefore('#').ifEmpty { file }
            Triple(target, docs[target]?.at(ref.substringAfter('#')) ?: p, "#${ref.substringAfter('#')}")
        } else Triple(file, p, "#/paths/${path.replace("~", "~0").replace("/", "~1")}/$method/parameters/$index")
        val schema = node.path("schema")
        val type = resolveType(docName, schema)
        return ParamSpec(node.path("name").asString(), node.path("in").asString(), node.path("required").asBoolean(false),
            if (schema.isMissingNode) null else "${iri(docName)}$base/schema", type)
    }

    private fun resolveType(file: String, schema: JsonNode, depth: Int = 0): String? {
        if (depth > 5) return null
        val t = schema.path("type")
        if (t.isString) return t.asString()
        val ref = schema.path("\$ref").asString()
        if (ref.isEmpty()) return null
        val target = ref.substringBefore('#').ifEmpty { file }
        return docs[target]?.at(ref.substringAfter('#'))?.let { resolveType(target, it, depth + 1) }
    }

    fun paramSchema(spec: ParamSpec): Schema? = spec.schemaLocation?.let { registry.getSchema(SchemaLocation.of(it)) }

    private fun responseSchemaLocation(file: String, path: String, method: String, code: String, r: JsonNode): String? {
        val ref = r.path("\$ref").asString()
        val (docName, pointer, node) = if (ref.isNotEmpty()) {
            val target = ref.substringBefore('#').ifEmpty { file }
            val ptr = ref.substringAfter('#')
            Triple(target, ptr, docs[target]?.at(ptr) ?: return null)
        } else Triple(file, "/paths/${path.replace("~", "~0").replace("/", "~1")}/$method/responses/$code", r)
        val mediaType = node.path("content").properties().firstOrNull { !it.value.path("schema").isMissingNode }?.key ?: return null
        return "${iri(docName)}#$pointer/content/${mediaType.replace("~", "~0").replace("/", "~1")}/schema"
    }

    /** Verletzungen von [json] gegen [schema] als `<Pointer> <Schlüsselwort>`; ohne Eingabewerte. */
    fun schemaErrors(schema: Schema, json: JsonNode): List<String> =
        schema.validate(json).map { e -> "${e.instanceLocation}${e.property?.let { p -> "/$p" } ?: ""} ${e.keyword}" }

    fun responseSchema(op: ApiOperation, status: String): Schema? = op.responseSchemas[status]?.let { registry.getSchema(SchemaLocation.of(it)) }

    /** Beispiele (`examples`) des Anfrageschemas, soweit vorhanden; Grundlage für aus dem Vertrag abgeleitete Positivfälle. */
    fun requestExample(op: ApiOperation): JsonNode? {
        val loc = op.requestSchemaLocation ?: return null
        val node = docs[loc.substringAfter(BASE).substringBefore('#')]?.at(loc.substringAfter('#')) ?: return null
        val target = node.path("\$ref").asString().takeIf { it.isNotEmpty() }?.let { r -> docs[r.substringBefore('#').ifEmpty { op.file }]?.at(r.substringAfter('#')) } ?: node
        return target.path("examples").path(0).takeUnless { it.isMissingNode }
    }

    companion object {
        const val BASE = "https://api.taktovia.example/openapi/"
        private val METHODS = setOf("get", "post", "put", "patch", "delete")
        fun iri(file: String) = BASE + file
    }
}

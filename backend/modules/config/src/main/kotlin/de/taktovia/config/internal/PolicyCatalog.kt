package de.taktovia.config.internal

import com.networknt.schema.Schema
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SchemaRegistryConfig
import com.networknt.schema.path.PathType
import com.networknt.schema.dialect.Dialects
import de.taktovia.config.api.ParameterCatalog
import de.taktovia.config.api.ParameterDefinition
import de.taktovia.config.api.ScopeKind
import org.springframework.jdbc.core.JdbcOperations
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Ein geladener Parameter: Definition für die API plus das kompilierte JSON-Schema für die Prüfung. */
internal class LoadedParameter(val definition: ParameterDefinition, val schema: Schema, val constValue: JsonNode?)

/** Katalog einer Schema-Version, geladen aus `policy_schema`. Freigegebene Versionen sind unveränderlich (Datenbank), daher cachebar. */
internal class LoadedCatalog(val version: String, val validFrom: Instant, val parameters: Map<String, LoadedParameter>) {
    fun toApi() = ParameterCatalog(version, validFrom, parameters.values.map { it.definition }.sortedBy { it.name })
}

/** Lädt Schema-Versionen aus der Datenbank (Tabellen `policy_schema_version`, `policy_schema`; für die Runtime-Rolle nur lesbar). */
internal class PolicyCatalogRepository {
    private val mapper = JsonMapper.builder().build()
    private val registry = SchemaRegistry.withDefaultDialect(Dialects.getDraft202012()) { b ->
        b.schemaRegistryConfig(SchemaRegistryConfig.builder().formatAssertionsEnabled(true).pathType(PathType.JSON_POINTER).build())
    }
    private val cache = ConcurrentHashMap<String, LoadedCatalog>()

    /** Die zum Zeitpunkt [at] gültige Version oder `null`, wenn noch keine gilt. */
    fun currentVersion(jdbc: JdbcOperations, at: Instant): String? =
        jdbc.query("SELECT taktovia.current_policy_version(?)", { rs, _ -> rs.getString(1) }, java.sql.Timestamp.from(at)).firstOrNull()

    fun load(jdbc: JdbcOperations, version: String): LoadedCatalog? {
        cache[version]?.let { return it }
        val validFrom = jdbc.query("SELECT valid_from FROM taktovia.policy_schema_version WHERE schema_version = ?", { rs, _ -> rs.getTimestamp(1).toInstant() }, version).firstOrNull()
            ?: return null
        val rows = jdbc.query(
            "SELECT parameter, json_schema::text FROM taktovia.policy_schema WHERE schema_version = ? ORDER BY parameter",
            { rs, _ -> rs.getString(1) to rs.getString(2) }, version,
        )
        val loaded = LoadedCatalog(version, validFrom, rows.associate { (name, text) -> name to parse(name, text) })
        cache[version] = loaded
        return loaded
    }

    private fun parse(name: String, text: String): LoadedParameter {
        val node = mapper.readTree(text)
        val levels = node.path("x-levels").values().map { ScopeKind.valueOf(it.asString()) }.toSet()
        val def = ParameterDefinition(
            name = name, type = node.path("type").asString(), unit = node.path("x-unit").asString(),
            minimum = node.get("minimum")?.let { BigDecimal(it.asString()) }, maximum = node.get("maximum")?.let { BigDecimal(it.asString()) },
            default = node.get("default")?.let { mapper.treeToValue(it, Any::class.java) }, allowedLevels = levels,
            securityClass = node.path("x-security-class").asString(), limitSource = node.path("x-limit-source").asString(),
            description = node.path("description").asString(), schema = mapper.readValue(text, Map::class.java).toStringKeyed(),
            impactRequired = node.path("x-impact-required").asBoolean(ImpactRequiredParameters.hint(name) != null),
            impactHint = node.get("x-impact-hint")?.asString() ?: ImpactRequiredParameters.hint(name),
        )
        return LoadedParameter(def, registry.getSchema(text), node.get("const"))
    }
}

@Suppress("UNCHECKED_CAST")
internal fun Map<*, *>.toStringKeyed(): Map<String, Any?> = this as Map<String, Any?>

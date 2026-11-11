package de.taktovia.config.internal

import de.taktovia.config.api.ConfigLevel
import de.taktovia.config.api.EffectiveConfig
import de.taktovia.config.api.EffectiveConfigView
import de.taktovia.config.api.EffectiveParameter
import de.taktovia.config.api.EffectiveRequest
import de.taktovia.config.api.EffectiveStatus
import de.taktovia.config.api.LevelOutcome
import de.taktovia.config.api.PrecedenceEntry
import de.taktovia.config.api.PrecedenceRule
import de.taktovia.config.api.Proposal
import de.taktovia.config.api.RevisionState
import de.taktovia.config.api.ScopeKind
import de.taktovia.config.api.ValidationOutcome
import de.taktovia.platform.authz.ObjectAuthorizer
import de.taktovia.platform.authz.ScopedObject
import de.taktovia.platform.authz.ServiceScoped
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import org.springframework.jdbc.core.JdbcOperations
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Auflösung der Präzedenzkette (WP-M1-21): ein Lesestand der wirksamen Revisionen aller Scopes des Kontexts (**eine** Anweisung, also konsistent zu genau einem
 * Stand der Publikation), danach je Parameter die Ebenen von der spezifischsten zur globalen. Reine Auswertung ohne Schreibzugriff.
 *
 * Der Zwischenspeicher bewahrt die ausgewertete Antwort je Anfrage auf. Sein Schlüssel enthält die **beteiligten Revisionen** (Kennungen der wirksamen und der
 * vorgeschlagenen Revisionen) und die Schema-Version: nach einer Publikation, die eine wirksame Revision ablöst, ändert sich der Schlüssel, ein abgelöster Wert wird nie geliefert.
 */
@ServiceScoped
internal class JdbcEffectiveConfig(
    private val ctxTx: ContextTransactions, private val catalogs: PolicyCatalogRepository, private val authorizer: ObjectAuthorizer, private val clock: Clock,
) : EffectiveConfig {
    private val mapper = JsonMapper.builder().build()
    private val cache = object : LinkedHashMap<String, EffectiveConfigView>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, EffectiveConfigView>) = size > MAX_CACHE
    }
    internal val cacheHits = AtomicLong()
    internal val cacheMisses = AtomicLong()

    override fun resolve(ctx: RequestContext, request: EffectiveRequest): EffectiveConfigView = ctxTx.run(ctx, readOnly = true) { j ->
        val context = checkContext(j, ctx, request)
        // Dienstbezug: lesende Rollen sehen nur Dienste ihres Dienstscopes (404 sonst); verwaltende Rollen (config:write) mandantenweit.
        context.serviceId?.let { sid -> if (!ctx.hasScope("config:write")) authorizer.requireRead(j, ctx, ScopedObject("service", sid, ctx.tenant, sid)) }
        evaluate(j, ctx, request, context)
    }

    override fun resolveInTransaction(jdbc: JdbcOperations, ctx: RequestContext, request: EffectiveRequest): EffectiveConfigView =
        evaluate(jdbc, ctx, request, checkContext(jdbc, ctx, request))

    // ---- Kontext ----------------------------------------------------------------------------------------------------------------------

    private class Context(val regionId: UUID?, val serviceId: UUID?, val vehicleId: UUID?)

    /** Region, Dienst und Fahrzeug müssen zum Mandanten gehören (RLS: fremde sind „nicht vorhanden“, 404); Dienst und Region müssen zusammenpassen (422). */
    private fun checkContext(j: JdbcOperations, ctx: RequestContext, req: EffectiveRequest): Context {
        req.regionId?.let { if (j.queryForObject("SELECT count(*) FROM taktovia.region WHERE id = ?", Int::class.java, it) != 1) throw ProblemException.notFound() }
        req.vehicleId?.let { if (j.queryForObject("SELECT count(*) FROM taktovia.vehicle WHERE id = ?", Int::class.java, it) != 1) throw ProblemException.notFound() }
        var region = req.regionId
        if (req.serviceId != null) {
            val rows = j.query("SELECT region_id FROM taktovia.service WHERE id = ?", { rs, _ -> rs.getObject(1, UUID::class.java) }, req.serviceId)
            if (rows.isEmpty()) throw ProblemException.notFound()
            val serviceRegion = rows.single()
            if (req.regionId != null && serviceRegion != req.regionId)
                throw ProblemException.invalid(Violation("/\$query/region_id", ViolationCode.INVALID_VALUE))   // der Dienst gehört nicht zu dieser Region
            region = region ?: serviceRegion
        }
        return Context(region, req.serviceId, req.vehicleId)
    }

    // ---- Auswertung -------------------------------------------------------------------------------------------------------------------

    private class Row(val id: UUID, val scopeKind: ScopeKind, val scopeId: UUID, val revision: Long, val state: RevisionState, val values: Map<String, Any?>, val from: Instant, val to: Instant?)

    private fun evaluate(j: JdbcOperations, ctx: RequestContext, req: EffectiveRequest, context: Context): EffectiveConfigView {
        val now = clock.instant(); val at = req.at ?: now
        val version = catalogs.currentVersion(j, at) ?: catalogs.currentVersion(j, now) ?: throw ProblemException(ErrorCode.SERVICE_UNAVAILABLE, "Kein Parameterkatalog gültig.")
        val catalog = catalogs.load(j, version) ?: throw ProblemException(ErrorCode.SERVICE_UNAVAILABLE, "Parameterkatalog nicht ladbar.")
        val names = req.parameter?.let { listOf(it) } ?: req.parameters?.sorted() ?: catalog.parameters.keys.sorted()
        names.forEach { if (it !in catalog.parameters) throw ProblemException.notFound() }

        // Lesestand: je Scope des Kontexts die zu `at` wirksame veröffentlichte Revision (spätester Beginn, dann höchste Nummer) und der jüngste unveröffentlichte Stand
        val scopes = listOfNotNull(ScopeKind.TENANT to ctx.tenant.value, context.regionId?.let { ScopeKind.REGION to it }, context.serviceId?.let { ScopeKind.SERVICE to it }, context.vehicleId?.let { ScopeKind.VEHICLE to it })
        val effective = effectiveRows(j, scopes, at); val proposals = proposalRows(j, scopes)
        val key = cacheKey(ctx, req, context, at, version, effective, proposals)
        synchronized(cache) { cache[key] }?.let { cacheHits.incrementAndGet(); return it.copy(asOf = at) }   // die Antwort hängt von `at` nur über die beteiligten Revisionen und die Schema-Version ab (im Schlüssel)
        cacheMisses.incrementAndGet()

        val validator = ParameterValidator(catalog)
        val items = names.map { name -> parameterOf(name, catalog.parameters.getValue(name), validator, context, ctx, effective, proposals, version) }
        val view = EffectiveConfigView(at, version, items)
        synchronized(cache) { cache[key] = view }
        return view
    }

    private fun effectiveRows(j: JdbcOperations, scopes: List<Pair<ScopeKind, UUID>>, at: Instant): Map<Pair<ScopeKind, UUID>, Row> {
        val kinds = scopes.map { it.first.name }.toTypedArray(); val ids = scopes.map { it.second }.toTypedArray()
        val ts = Timestamp.from(at)
        // Ein Lesestand in einer Anweisung: die Kandidaten sind die Zeilen der (Art, Kennung)-Paare des Kontexts; je Scope gewinnt der späteste Beginn, dann die höchste Nummer.
        val rows = j.query(
            "SELECT DISTINCT ON (r.scope_kind, r.scope_id) r.id, r.scope_kind, r.scope_id, r.revision, r.state, r.values_json::text AS v, r.effective_from, r.effective_to " +
                "FROM taktovia.config_revision r JOIN unnest(CAST(? AS text[]), CAST(? AS uuid[])) AS s(kind, id) ON s.kind = r.scope_kind AND s.id = r.scope_id " +
                "WHERE r.state = 'PUBLISHED' AND r.effective_from <= ? AND (r.effective_to IS NULL OR r.effective_to > ?) " +
                "ORDER BY r.scope_kind, r.scope_id, r.effective_from DESC, r.revision DESC",
            { rs, _ -> mapRow(rs) }, textArray(kinds.toList()), uuidArray(ids.toList()), ts, ts)
        return rows.associateBy { it.scopeKind to it.scopeId }
    }

    private fun proposalRows(j: JdbcOperations, scopes: List<Pair<ScopeKind, UUID>>): Map<Pair<ScopeKind, UUID>, Row> {
        val rows = j.query(
            "SELECT DISTINCT ON (r.scope_kind, r.scope_id) r.id, r.scope_kind, r.scope_id, r.revision, r.state, r.values_json::text AS v, r.effective_from, r.effective_to " +
                "FROM taktovia.config_revision r JOIN unnest(CAST(? AS text[]), CAST(? AS uuid[])) AS s(kind, id) ON s.kind = r.scope_kind AND s.id = r.scope_id " +
                "WHERE r.state IN ('DRAFT', 'VALIDATED', 'APPROVED') ORDER BY r.scope_kind, r.scope_id, r.created_at DESC, r.revision DESC",
            { rs, _ -> mapRow(rs) }, textArray(scopes.map { it.first.name }), uuidArray(scopes.map { it.second }))
        return rows.associateBy { it.scopeKind to it.scopeId }
    }

    private fun mapRow(rs: java.sql.ResultSet) = Row(
        rs.getObject("id", UUID::class.java), ScopeKind.valueOf(rs.getString("scope_kind")), rs.getObject("scope_id", UUID::class.java), rs.getLong("revision"),
        RevisionState.valueOf(rs.getString("state")), mapper.readValue(rs.getString("v"), Map::class.java).toStringKeyed(), rs.getTimestamp("effective_from").toInstant(),
        rs.getTimestamp("effective_to")?.toInstant(),
    )

    private fun parameterOf(
        name: String, p: LoadedParameter, validator: ParameterValidator, context: Context, ctx: RequestContext,
        effective: Map<Pair<ScopeKind, UUID>, Row>, proposals: Map<Pair<ScopeKind, UUID>, Row>, version: String,
    ): EffectiveParameter {
        val chain = listOf(
            Triple(ConfigLevel.VEHICLE, ScopeKind.VEHICLE, context.vehicleId), Triple(ConfigLevel.SERVICE, ScopeKind.SERVICE, context.serviceId),
            Triple(ConfigLevel.REGION, ScopeKind.REGION, context.regionId), Triple(ConfigLevel.TENANT, ScopeKind.TENANT, ctx.tenant.value),
        )
        val entries = mutableListOf<PrecedenceEntry>()
        var winner: PrecedenceEntry? = null; var winnerRow: Row? = null
        for ((level, kind, scopeId) in chain) {
            if (scopeId == null) { entries += PrecedenceEntry(level, LevelOutcome.NOT_IN_CONTEXT, null, null, null, null, null, null); continue }
            val proposal = proposals[kind to scopeId]?.takeIf { name in it.values }?.let { Proposal(it.id, it.state, it.values[name]) }
            val row = effective[kind to scopeId]
            if (row == null || name !in row.values) { entries += PrecedenceEntry(level, LevelOutcome.NOT_SET, scopeId, null, row?.id, row?.revision, null, proposal); continue }
            val value = row.values[name]
            // Zulässigkeit: Ebene für den Parameter erlaubt (Fahrzeug nur bei Freigabe) und Wert innerhalb der globalen Sicherheitsgrenze der zu T gültigen Schema-Version.
            val outcome = validator.validate(kind, mapOf(name to value))
            if (outcome is ValidationOutcome.Invalid) {
                val code = outcome.violations.first().code
                entries += PrecedenceEntry(level, if (code == ViolationCode.UNKNOWN_FIELD) LevelOutcome.SKIPPED_NOT_ALLOWED else LevelOutcome.SKIPPED_LIMIT, scopeId, value, row.id, row.revision, code.name, proposal)
            } else if (winner == null) {
                val applied = PrecedenceEntry(level, LevelOutcome.APPLIED, scopeId, value, row.id, row.revision, null, proposal); entries += applied; winner = applied; winnerRow = row
            } else entries += PrecedenceEntry(level, LevelOutcome.OVERRIDDEN, scopeId, value, row.id, row.revision, null, proposal)
        }
        // Globale Ebene: feste Vorgabe (Sicherheitsinvariante `const`) oder Katalogvorgabe `default`; sonst keine Vorgabe.
        val globalValue = p.constValue?.let { mapper.treeToValue(it, Any::class.java) } ?: p.definition.default
        val global = if (globalValue != null) PrecedenceEntry(ConfigLevel.GLOBAL, if (winner == null) LevelOutcome.APPLIED else LevelOutcome.OVERRIDDEN, null, globalValue, null, null, null, null)
        else PrecedenceEntry(ConfigLevel.GLOBAL, LevelOutcome.NOT_SET, null, null, null, null, null, null)
        entries += global
        val won = winner ?: global.takeIf { it.outcome == LevelOutcome.APPLIED }
        val rule = when {
            winner != null && entries.any { it.outcome == LevelOutcome.SKIPPED_LIMIT } -> RULE_LIMIT_FALLBACK
            winner != null && entries.count { it.outcome == LevelOutcome.OVERRIDDEN || it.outcome == LevelOutcome.APPLIED } > 1 -> RULE_MOST_SPECIFIC
            winner != null -> RULE_ONLY_LEVEL
            won != null -> RULE_GLOBAL
            else -> RULE_NO_VALUE
        }
        return EffectiveParameter(
            name, p.definition.unit, if (won != null) EffectiveStatus.EFFECTIVE else EffectiveStatus.NO_VALUE, won?.value, won?.level, won?.revisionId, won?.revision, won?.scopeId,
            winnerRow?.from, winnerRow?.to, version, rule, entries,
        )
    }

    private fun cacheKey(ctx: RequestContext, req: EffectiveRequest, context: Context, at: Instant, version: String, effective: Map<Pair<ScopeKind, UUID>, Row>, proposals: Map<Pair<ScopeKind, UUID>, Row>): String {
        val parts = listOf(ctx.tenant.toString(), req.parameter ?: req.parameters?.sorted()?.joinToString(",") ?: "*", context.regionId, context.serviceId, context.vehicleId, version,
            effective.values.map { it.id.toString() }.sorted().joinToString(","), proposals.values.map { it.id.toString() + ":" + it.state }.sorted().joinToString(","),
            // Aufgelöst wird gegen die zu `at` gültige Revisionsmenge; ohne wirksame Revision hängt die Antwort nur von `at` selbst nicht ab, die Katalogauswahl schon (Version im Schlüssel).
        )
        return MessageDigest.getInstance("SHA-256").digest(parts.joinToString("|").toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun textArray(items: List<String>) = items.joinToString(",", "{", "}") { "\"$it\"" }   // nur feste Katalogwerte
    private fun uuidArray(items: List<UUID>) = items.joinToString(",", "{", "}")                  // nur UUIDs, nie Fremdtext

    private companion object {
        const val MAX_CACHE = 2_000
        val RULE_MOST_SPECIFIC = PrecedenceRule("MOST_SPECIFIC_LEVEL_WINS",
            "Die spezifischste Ebene mit einem veröffentlichten, zum Zeitpunkt wirksamen und zulässigen Wert gilt (Fahrzeug vor Dienst vor Region vor Mandant vor globaler Vorgabe); die globale Sicherheitsgrenze gilt immer.")
        val RULE_ONLY_LEVEL = PrecedenceRule("SINGLE_LEVEL", "Nur diese Ebene setzt den Parameter; kein Konflikt mit anderen Ebenen.")
        val RULE_LIMIT_FALLBACK = PrecedenceRule("SAFETY_LIMIT_FALLBACK",
            "Ein spezifischerer Wert verletzt die globale Sicherheitsgrenze und wird nicht geliefert; es gilt die nächste zulässige Ebene.")
        val RULE_GLOBAL = PrecedenceRule("GLOBAL_DEFAULT", "Keine Ebene setzt den Parameter; es gilt die globale Vorgabe des Parameterkatalogs.")
        val RULE_NO_VALUE = PrecedenceRule("NO_VALUE", "Zum Zeitpunkt gibt es keine wirksame Revision und keine globale Vorgabe: der Parameter hat keinen Wert.")
    }
}

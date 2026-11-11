package de.taktovia.servicegeo.internal

import de.taktovia.config.api.ConfigApi
import de.taktovia.platform.audit.ActorRef
import de.taktovia.platform.audit.AuditActions
import de.taktovia.platform.audit.AuditEntry
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.audit.AuditRequired
import de.taktovia.platform.audit.ObjectRef
import de.taktovia.platform.authz.ObjectAuthorizer
import de.taktovia.platform.authz.ServiceScoped
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.platform.time.IanaZones
import de.taktovia.servicegeo.api.CreateService
import de.taktovia.servicegeo.api.DepotView
import de.taktovia.servicegeo.api.OrganizationStructure
import de.taktovia.servicegeo.api.RegionView
import de.taktovia.servicegeo.api.ServiceView
import de.taktovia.servicegeo.api.UpdateService
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Region, Betriebshof und Dienstkopf (WP-M1-09, LH-I.4-MUL-002): Fachzeile und Audit in **einer** Transaktion im Mandanten des Kontexts. Mandantentrennung
 * durch RLS und zusammengesetzte Schlüssel; ein Verweis auf ein Objekt eines anderen Mandanten verhält sich wie „nicht vorhanden“ (404).
 */
@ServiceScoped
internal class JdbcOrganizationStructure(
    private val ctxTx: ContextTransactions, private val audit: AuditPort, private val config: ConfigApi, private val clock: Clock, private val authorizer: ObjectAuthorizer,
) : OrganizationStructure {

    // ---- Region -----------------------------------------------------------------------------------------------------------------------

    override fun createRegion(ctx: RequestContext, name: String): RegionView {
        throwIf(listOfNotNull(name(name)))
        return ctxTx.run(ctx) { j -> doCreateRegion(j, ctx, name) }
    }

    @AuditRequired
    private fun doCreateRegion(j: JdbcOperations, ctx: RequestContext, name: String): RegionView {
        val v = j.query("INSERT INTO taktovia.region AS r (tenant_id, id, name, created_by) VALUES (taktovia.current_tenant(), ?, ?, ?) RETURNING $REGION_COLUMNS",
            REGION, UUID.randomUUID(), name, actor(ctx).value).single()
        record(j, ctx, AuditActions.REGION_CHANGED, "region", v.id, v.revision, "created", mapOf("name" to v.name))
        return v
    }

    override fun updateRegion(ctx: RequestContext, id: UUID, expectedRevision: Long, name: String): RegionView {
        throwIf(listOfNotNull(name(name)))
        return ctxTx.run(ctx) { j -> doUpdateRegion(j, ctx, id, expectedRevision, name) }
    }

    @AuditRequired
    private fun doUpdateRegion(j: JdbcOperations, ctx: RequestContext, id: UUID, expected: Long, name: String): RegionView {
        val v = j.query("UPDATE taktovia.region r SET name = ? WHERE r.id = ? AND r.revision = ? RETURNING $REGION_COLUMNS", REGION, name, id, expected).singleOrNull()
            ?: throw missing(j, "region", id)
        if (v.revision != expected) record(j, ctx, AuditActions.REGION_CHANGED, "region", v.id, v.revision, "updated", mapOf("name" to v.name))
        return v
    }

    override fun listRegions(ctx: RequestContext, limit: Int, after: Pair<Instant, UUID>?): List<RegionView> = ctxTx.run(ctx, readOnly = true) { j ->
        if (after == null) j.query("SELECT $REGION_COLUMNS FROM taktovia.region r ORDER BY r.created_at, r.id LIMIT ?", REGION, limit + 1)
        else j.query("SELECT $REGION_COLUMNS FROM taktovia.region r WHERE (r.created_at, r.id) > (?, ?) ORDER BY r.created_at, r.id LIMIT ?", REGION, Timestamp.from(after.first), after.second, limit + 1)
    }

    // ---- Betriebshof ------------------------------------------------------------------------------------------------------------------

    override fun createDepot(ctx: RequestContext, regionId: UUID, name: String): DepotView {
        throwIf(listOfNotNull(name(name)))
        return ctxTx.run(ctx) { j -> doCreateDepot(j, ctx, regionId, name) }
    }

    @AuditRequired
    private fun doCreateDepot(j: JdbcOperations, ctx: RequestContext, regionId: UUID, name: String): DepotView {
        require(j, "region", regionId)
        val v = j.query("INSERT INTO taktovia.depot AS d (tenant_id, id, region_id, name, created_by) VALUES (taktovia.current_tenant(), ?, ?, ?, ?) RETURNING $DEPOT_COLUMNS",
            DEPOT, UUID.randomUUID(), regionId, name, actor(ctx).value).single()
        record(j, ctx, AuditActions.DEPOT_CHANGED, "depot", v.id, v.revision, "created", mapOf("name" to v.name, "region_id" to v.regionId.toString()))
        return v
    }

    override fun updateDepot(ctx: RequestContext, id: UUID, expectedRevision: Long, regionId: UUID, name: String): DepotView {
        throwIf(listOfNotNull(name(name)))
        return ctxTx.run(ctx) { j -> doUpdateDepot(j, ctx, id, expectedRevision, regionId, name) }
    }

    @AuditRequired
    private fun doUpdateDepot(j: JdbcOperations, ctx: RequestContext, id: UUID, expected: Long, regionId: UUID, name: String): DepotView {
        require(j, "region", regionId)
        val v = j.query("UPDATE taktovia.depot d SET region_id = ?, name = ? WHERE d.id = ? AND d.revision = ? RETURNING $DEPOT_COLUMNS", DEPOT, regionId, name, id, expected).singleOrNull()
            ?: throw missing(j, "depot", id)
        if (v.revision != expected) record(j, ctx, AuditActions.DEPOT_CHANGED, "depot", v.id, v.revision, "updated", mapOf("name" to v.name, "region_id" to v.regionId.toString()))
        return v
    }

    override fun listDepots(ctx: RequestContext, limit: Int, after: Pair<Instant, UUID>?): List<DepotView> = ctxTx.run(ctx, readOnly = true) { j ->
        if (after == null) j.query("SELECT $DEPOT_COLUMNS FROM taktovia.depot d ORDER BY d.created_at, d.id LIMIT ?", DEPOT, limit + 1)
        else j.query("SELECT $DEPOT_COLUMNS FROM taktovia.depot d WHERE (d.created_at, d.id) > (?, ?) ORDER BY d.created_at, d.id LIMIT ?", DEPOT, Timestamp.from(after.first), after.second, limit + 1)
    }

    // ---- Dienstkopf -------------------------------------------------------------------------------------------------------------------

    override fun createService(ctx: RequestContext, cmd: CreateService): ServiceView {
        throwIf(listOfNotNull(name(cmd.name), zone(cmd.timezone)))
        return ctxTx.run(ctx) { j -> doCreateService(j, ctx, cmd) }
    }

    @AuditRequired
    private fun doCreateService(j: JdbcOperations, ctx: RequestContext, cmd: CreateService): ServiceView {
        require(j, "organization", cmd.operatorId)
        cmd.regionId?.let { require(j, "region", it) }
        val id = UUID.randomUUID()
        // OP-35: service.config_revision_id ist Pflicht; die erste Konfigurationsrevision (Entwurf) entsteht in derselben Transaktion.
        val revisionId = config.revisions.seedServiceHead(j, actor(ctx), ctx.correlationId, id, clock.instant())
        val v = j.query("INSERT INTO taktovia.service AS s (tenant_id, id, operator_id, name, timezone, config_revision_id, region_id, created_by) " +
            "VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?, ?) RETURNING $SERVICE_COLUMNS",
            SERVICE, id, cmd.operatorId, cmd.name, cmd.timezone, revisionId, cmd.regionId, actor(ctx).value).single()
        record(j, ctx, AuditActions.SERVICE_CHANGED, "service", v.id, v.revision, "created", serviceChanges(v) + mapOf("config_revision_id" to revisionId.toString()))
        return v
    }

    override fun updateService(ctx: RequestContext, id: UUID, expectedRevision: Long, cmd: UpdateService): ServiceView {
        throwIf(listOfNotNull(name(cmd.name), zone(cmd.timezone)))
        return ctxTx.run(ctx) { j -> doUpdateService(j, ctx, id, expectedRevision, cmd) }
    }

    @AuditRequired
    private fun doUpdateService(j: JdbcOperations, ctx: RequestContext, id: UUID, expected: Long, cmd: UpdateService): ServiceView {
        cmd.regionId?.let { require(j, "region", it) }
        val v = j.query("UPDATE taktovia.service s SET name = ?, timezone = ?, region_id = ? WHERE s.id = ? AND s.revision = ? RETURNING $SERVICE_COLUMNS",
            SERVICE, cmd.name, cmd.timezone, cmd.regionId, id, expected).singleOrNull() ?: throw missing(j, "service", id)
        if (v.revision != expected) record(j, ctx, AuditActions.SERVICE_CHANGED, "service", v.id, v.revision, "updated", serviceChanges(v))
        return v
    }

    override fun listServices(ctx: RequestContext, limit: Int, after: Pair<Instant, UUID>?, requested: Set<UUID>?): List<ServiceView> = ctxTx.run(ctx, readOnly = true) { j ->
        // Dienstbezug: der Port bestimmt die Dienste, auf die die Liste zugreifen darf (Filter schränken nur ein); verwaltende Rollen sehen alle Dienste des Mandanten.
        val services = authorizer.effectiveServices(ctx, requested, tenantWide = ctx.hasScope("services:write"))
        if (services != null && services.isEmpty()) return@run emptyList()
        val scope = services?.joinToString(",", "{", "}")   // nur UUIDs, nie Fremdtext
        val sql = StringBuilder("SELECT $SERVICE_COLUMNS FROM taktovia.service s WHERE TRUE")
        val args = mutableListOf<Any?>()
        if (scope != null) { sql.append(" AND s.id = ANY(CAST(? AS uuid[]))"); args += scope }
        if (after != null) { sql.append(" AND (s.created_at, s.id) > (?, ?)"); args += Timestamp.from(after.first); args += after.second }
        sql.append(" ORDER BY s.created_at, s.id LIMIT ?")
        j.query(sql.toString(), SERVICE, *args.toTypedArray(), limit + 1) // sql-lint: ok feste Bedingungstexte, Werte gebunden
    }

    // ---- gemeinsam --------------------------------------------------------------------------------------------------------------------

    private fun record(j: JdbcOperations, ctx: RequestContext, action: String, type: String, id: UUID, revision: Long, change: String, changes: Map<String, Any?>) {
        audit.record(j, AuditEntry(actor(ctx), action, ObjectRef(type, id), ctx.correlationId, changes + mapOf("change" to change, "revision" to revision)))
    }

    /** Warum keine Zeile: unbekannt oder fremder Mandant (RLS) 404, sonst veraltete Revision 412. */
    private fun missing(j: JdbcOperations, table: String, id: UUID): ProblemException =
        if (exists(j, table, id)) ProblemException(ErrorCode.REVISION_MISMATCH, "Die Ressource wurde zwischenzeitlich geändert; aktuelle Revision lesen.") else ProblemException.notFound()

    /** Ein Objekt eines anderen Mandanten ist als Verweis nicht verwendbar: für den Aufrufer wie „nicht vorhanden“ (404). */
    private fun require(j: JdbcOperations, table: String, id: UUID) { if (!exists(j, table, id)) throw ProblemException.notFound() }

    private fun exists(j: JdbcOperations, table: String, id: UUID): Boolean = when (table) {
        "region" -> j.queryForObject("SELECT count(*) FROM taktovia.region WHERE id = ?", Int::class.java, id)
        "depot" -> j.queryForObject("SELECT count(*) FROM taktovia.depot WHERE id = ?", Int::class.java, id)
        "service" -> j.queryForObject("SELECT count(*) FROM taktovia.service WHERE id = ?", Int::class.java, id)
        "organization" -> j.queryForObject("SELECT count(*) FROM taktovia.organization WHERE id = ?", Int::class.java, id)
        else -> error("unbekannte Tabelle")
    } == 1

    private fun name(s: String): Violation? = when {
        s.isEmpty() -> Violation("/name", ViolationCode.TOO_SHORT)
        s.length > 200 -> Violation("/name", ViolationCode.TOO_LONG)
        else -> null
    }

    private fun zone(tz: String): Violation? = if (IanaZones.parse(tz) == null) Violation("/timezone", ViolationCode.ZONE_UNKNOWN) else null

    private fun throwIf(v: List<Violation>) { if (v.isNotEmpty()) throw ProblemException.invalid(*v.toTypedArray()) }

    private fun actor(ctx: RequestContext) = ActorRef((if (ctx.actor.type == ActorType.SERVICE) "service:" else "user:") + ctx.actor.subject)

    private fun serviceChanges(v: ServiceView) = mapOf("name" to v.name, "timezone" to v.timezone, "region_id" to v.regionId?.toString(), "operator_id" to v.operatorId.toString())

    private companion object {
        const val REGION_COLUMNS = "r.id, r.name, r.revision, r.created_at"
        val REGION = RowMapper { rs, _ -> RegionView(rs.getObject("id", UUID::class.java), rs.getString("name"), rs.getLong("revision"), rs.getTimestamp("created_at").toInstant()) }
        const val DEPOT_COLUMNS = "d.id, d.region_id, d.name, d.revision, d.created_at"
        val DEPOT = RowMapper { rs, _ -> DepotView(rs.getObject("id", UUID::class.java), rs.getObject("region_id", UUID::class.java), rs.getString("name"), rs.getLong("revision"), rs.getTimestamp("created_at").toInstant()) }
        const val SERVICE_COLUMNS = "s.id, s.operator_id, s.region_id, s.name, s.timezone, s.config_revision_id, s.revision, s.created_at"
        val SERVICE = RowMapper { rs, _ ->
            ServiceView(rs.getObject("id", UUID::class.java), rs.getObject("operator_id", UUID::class.java), rs.getObject("region_id", UUID::class.java), rs.getString("name"),
                rs.getString("timezone"), rs.getObject("config_revision_id", UUID::class.java), rs.getLong("revision"), rs.getTimestamp("created_at").toInstant())
        }
    }
}

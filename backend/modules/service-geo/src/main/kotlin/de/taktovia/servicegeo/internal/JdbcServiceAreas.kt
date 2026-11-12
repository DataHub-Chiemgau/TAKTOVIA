package de.taktovia.servicegeo.internal

import de.taktovia.platform.audit.AuditActions
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.audit.AuditRequired
import de.taktovia.platform.authz.ObjectAuthorizer
import de.taktovia.platform.authz.ServiceScoped
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.outbox.OutboxEvent
import de.taktovia.platform.problem.DbErrorMapper
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.servicegeo.api.CreateServiceArea
import de.taktovia.servicegeo.api.GeoPoint
import de.taktovia.servicegeo.api.ReviseServiceArea
import de.taktovia.servicegeo.api.ServiceAreaView
import de.taktovia.servicegeo.api.ServiceAreas
import de.taktovia.servicegeo.api.ZoneRole
import org.springframework.dao.DataAccessException
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID

private const val COLS = "COALESCE(a.area_key, a.id) AS area_key, a.id, a.service_id, a.revision, ST_AsGeoJSON(a.area)::text AS area_json, a.valid_from, a.valid_to, a.created_by, a.created_at, a.zone_role"

/**
 * Bediengebiete (WP-M2-04). Schreibende Operationen sperren die Dienstzeile (`FOR UPDATE`, Scope-Sperre wie bei den Dienstversionen), prüfen Geometrie und erwartete
 * Revision, schließen eine offene Vorgängerrevision und legen die neue Zeile an; Überlappung, Revisionsnummer und Geometrie sichert zusätzlich die Datenbank
 * (Exclusion Constraint `service_area_no_overlap`, `UNIQUE (area_key, revision)`, `CHECK (ST_IsValid(area))`).
 */
@ServiceScoped
internal class JdbcServiceAreas(
    private val ctxTx: ContextTransactions, private val audit: AuditPort, private val outbox: Outbox, private val clock: Clock, private val authorizer: ObjectAuthorizer,
) : ServiceAreas {
    private val json = JsonMapper.builder().build()

    override fun create(ctx: RequestContext, cmd: CreateServiceArea): ServiceAreaView = ctxTx.run(ctx) { j -> doCreate(j, ctx, cmd) }

    @AuditRequired
    private fun doCreate(j: JdbcOperations, ctx: RequestContext, cmd: CreateServiceArea): ServiceAreaView {
        lockService(j, ctx, cmd.serviceId)
        val areaJson = validated(j, cmd.area, cmd.validFrom, cmd.validTo)
        val id = UUID.randomUUID()
        insert(j, ctx, id, id, cmd.serviceId, 1, areaJson, cmd.validFrom, cmd.validTo, cmd.zoneRole)
        val v = read(j, id, 1)
        audit.record(j, GeoSupport.entry(ctx, AuditActions.GEO_SERVICE_AREA_CHANGED, "service_area", v.id, 1, "created", changes(v)))
        emit(j, ctx, v)
        return v
    }

    override fun revise(ctx: RequestContext, areaKey: UUID, expectedRevision: Long, cmd: ReviseServiceArea): ServiceAreaView = ctxTx.run(ctx) { j -> doRevise(j, ctx, areaKey, expectedRevision, cmd) }

    @AuditRequired
    private fun doRevise(j: JdbcOperations, ctx: RequestContext, areaKey: UUID, expected: Long, cmd: ReviseServiceArea): ServiceAreaView {
        val serviceId = j.query("SELECT a.service_id FROM taktovia.service_area a WHERE COALESCE(a.area_key, a.id) = ? LIMIT 1", { rs, _ -> rs.getObject(1, UUID::class.java) }, areaKey).singleOrNull()
            ?: throw ProblemException.notFound()
        lockService(j, ctx, serviceId)
        val head = head(j, areaKey) ?: throw ProblemException.notFound()
        if (head.revision != expected) throw GeoSupport.revisionMismatch()
        val areaJson = validated(j, cmd.area, cmd.validFrom, cmd.validTo)
        if (head.validTo == null && head.validFrom.isBefore(cmd.validFrom))
            j.update("UPDATE taktovia.service_area SET valid_to = ? WHERE id = ? AND valid_to IS NULL", Timestamp.from(cmd.validFrom), head.revisionId)
        val revision = expected + 1
        val id = UUID.randomUUID()
        insert(j, ctx, id, areaKey, serviceId, revision, areaJson, cmd.validFrom, cmd.validTo, cmd.zoneRole ?: head.zoneRole)
        val v = read(j, id, revision)
        audit.record(j, GeoSupport.entry(ctx, AuditActions.GEO_SERVICE_AREA_CHANGED, "service_area", areaKey, revision, "revised", changes(v)))
        emit(j, ctx, v)
        return v
    }

    override fun get(ctx: RequestContext, areaKey: UUID, revision: Long?): ServiceAreaView = ctxTx.run(ctx, readOnly = true) { j ->
        if (revision == null) head(j, areaKey) else j.query("SELECT $COLS FROM taktovia.service_area a WHERE COALESCE(a.area_key, a.id) = ? AND a.revision = ?", MAPPER, areaKey, revision).singleOrNull()
    } ?: throw ProblemException.notFound()

    override fun list(ctx: RequestContext, serviceId: UUID?, limit: Int, after: Pair<Instant, UUID>?): List<ServiceAreaView> = ctxTx.run(ctx, readOnly = true) { j ->
        j.query("SELECT $COLS FROM taktovia.service_area a WHERE a.revision = (SELECT max(b.revision) FROM taktovia.service_area b WHERE b.tenant_id = a.tenant_id AND COALESCE(b.area_key, b.id) = COALESCE(a.area_key, a.id)) " +
            "AND (CAST(? AS uuid) IS NULL OR a.service_id = CAST(? AS uuid)) AND (a.created_at, a.id) > (?, ?) ORDER BY a.created_at, a.id LIMIT ?",
            MAPPER, serviceId, serviceId, Timestamp.from(after?.first ?: Instant.EPOCH), after?.second ?: UUID(0, 0), limit + 1)
    }

    override fun covering(ctx: RequestContext, serviceId: UUID, point: GeoPoint, at: Instant, role: ZoneRole?): ServiceAreaView? = ctxTx.run(ctx, readOnly = true) { j ->
        j.query("SELECT $COLS FROM taktovia.service_area a WHERE a.service_id = ? AND a.valid_from <= ? AND (a.valid_to IS NULL OR ? < a.valid_to) " +
            "AND (CAST(? AS text) IS NULL OR a.zone_role IN (CAST(? AS text), 'BOTH')) AND ST_Covers(a.area, ST_SetSRID(ST_MakePoint(?, ?), 4326)) ORDER BY a.revision DESC LIMIT 1",
            MAPPER, serviceId, Timestamp.from(at), Timestamp.from(at), role?.name, role?.name, point.longitude, point.latitude).singleOrNull()
    }

    // ---- intern ------------------------------------------------------------------------------------------------------------------------

    /** Sperrt den Dienst (404 bei fremdem/unbekanntem Dienst) und prüft die Dienstberechtigung (wie [JdbcServiceRevisions]). */
    private fun lockService(j: JdbcOperations, ctx: RequestContext, serviceId: UUID) {
        GeoSupport.loadService(j, serviceId, lock = true)
        if (!ctx.hasScope("geo:write")) authorizer.requireWrite(j, ctx, GeoSupport.scoped(ctx, serviceId))
    }

    /** Strukturprüfung, Zeitraum und PostGIS-Gültigkeit; liefert den GeoJSON-Text zum Speichern. Verletzungen: 422 mit Pointer, nichts wird gespeichert. */
    private fun validated(j: JdbcOperations, area: Any?, from: Instant, to: Instant?): String {
        val v = mutableListOf<Violation>()
        if (to != null && !to.isAfter(from)) v += Violation("/valid_to", ViolationCode.INTERVAL_INVALID)
        v += GeoJsonArea.check(area)
        GeoSupport.throwIf(v)
        val text = json.writeValueAsString(area)
        val check = try {
            j.query("SELECT ST_IsValid(g), ST_IsValidReason(g), ST_IsEmpty(g) FROM (SELECT ST_SetSRID(ST_GeomFromGeoJSON(CAST(? AS text)), 4326) AS g) x",
                { rs, _ -> Triple(rs.getBoolean(1), rs.getString(2), rs.getBoolean(3)) }, text).single()
        } catch (_: DataAccessException) { throw ProblemException.invalid(Violation("/area", ViolationCode.INVALID_FORMAT)) }
        if (!check.first || check.third) throw ProblemException.invalid(Violation("/area", ViolationCode.INVALID_VALUE, GeoJsonArea.reason(check.second)))
        return text
    }

    private fun insert(j: JdbcOperations, ctx: RequestContext, id: UUID, areaKey: UUID, serviceId: UUID, revision: Long, areaJson: String, from: Instant, to: Instant?, role: ZoneRole) {
        try {
            j.update("INSERT INTO taktovia.service_area (tenant_id, id, service_id, revision, area, valid_from, valid_to, area_key, created_by, zone_role) " +
                "VALUES (taktovia.current_tenant(), ?, ?, ?, ST_SetSRID(ST_GeomFromGeoJSON(CAST(? AS text)), 4326), ?, ?, ?, ?, ?)",
                id, serviceId, revision, areaJson, Timestamp.from(from), to?.let { Timestamp.from(it) }, areaKey, GeoSupport.actor(ctx).value, role.name)
        } catch (_: DuplicateKeyException) { throw GeoSupport.revisionMismatch() }
        catch (e: DataAccessException) { throw DbErrorMapper.map(e) }   // Exclusion Constraint: überlappende Wirksamkeit je Dienst, 409
    }

    private fun head(j: JdbcOperations, areaKey: UUID): ServiceAreaView? =
        j.query("SELECT $COLS FROM taktovia.service_area a WHERE COALESCE(a.area_key, a.id) = ? ORDER BY a.revision DESC LIMIT 1", MAPPER, areaKey).singleOrNull()

    private fun read(j: JdbcOperations, id: UUID, revision: Long): ServiceAreaView =
        j.query("SELECT $COLS FROM taktovia.service_area a WHERE a.id = ? AND a.revision = ?", MAPPER, id, revision).single()

    private fun changes(v: ServiceAreaView) = linkedMapOf<String, Any?>("service_id" to v.serviceId.toString(), "revision_id" to v.revisionId.toString(),
        "valid_from" to v.validFrom.toString(), "valid_to" to v.validTo?.toString(), "zone_role" to v.zoneRole.name)

    private fun emit(j: JdbcOperations, ctx: RequestContext, v: ServiceAreaView) {
        outbox.append(j, OutboxEvent(v.id, v.revision, "geo.service_area_revision_effective", linkedMapOf(
            "area_id" to v.id.toString(), "revision_id" to v.revisionId.toString(), "service_id" to v.serviceId.toString(), "revision" to v.revision,
            "valid_from" to v.validFrom.toString(), "valid_to" to v.validTo?.toString(), "zone_role" to v.zoneRole.name), clock.instant(), correlationId = ctx.correlationId))
    }

    private companion object {
        private val mapper = JsonMapper.builder().build()
        @Suppress("UNCHECKED_CAST")
        val MAPPER = RowMapper { rs, _ ->
            ServiceAreaView(rs.getObject("area_key", UUID::class.java), rs.getObject("id", UUID::class.java), rs.getObject("service_id", UUID::class.java), rs.getLong("revision"),
                mapper.readValue(rs.getString("area_json"), Map::class.java) as Map<String, Any?>, rs.getTimestamp("valid_from").toInstant(), rs.getTimestamp("valid_to")?.toInstant(),
                rs.getString("created_by"), rs.getTimestamp("created_at").toInstant(), ZoneRole.valueOf(rs.getString("zone_role")))
        }
    }
}

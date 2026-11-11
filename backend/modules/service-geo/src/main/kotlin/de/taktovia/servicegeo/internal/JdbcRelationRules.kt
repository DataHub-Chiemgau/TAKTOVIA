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
import de.taktovia.servicegeo.api.GeoPoint
import de.taktovia.servicegeo.api.PublishRelationRule
import de.taktovia.servicegeo.api.RelationDecision
import de.taktovia.servicegeo.api.RelationEnd
import de.taktovia.servicegeo.api.RelationRejection
import de.taktovia.servicegeo.api.RelationRuleView
import de.taktovia.servicegeo.api.RelationRules
import org.springframework.dao.DataAccessException
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID

private const val COLS = "r.id, r.service_id, r.revision, r.destination_stop_ids, r.valid_from, r.valid_to, r.reason, r.created_by, r.created_at"

/**
 * Relationsregeln (WP-M2-06): Mengenversionen der erlaubten Zielpunkte je Dienst (wie die Dienstversionen: Dienstzeile sperren, erwartete Revision prüfen, offene Vorgängerversion
 * schließen, neue Zeile anlegen) und die Prüfung „Relation zulässig?“ über die Zonen der Bediengebiete (`service_area.zone_role`).
 */
@ServiceScoped
internal class JdbcRelationRules(
    private val ctxTx: ContextTransactions, private val audit: AuditPort, private val outbox: Outbox, private val clock: Clock, private val authorizer: ObjectAuthorizer,
) : RelationRules {

    override fun head(ctx: RequestContext, serviceId: UUID): RelationRuleView? = ctxTx.run(ctx, readOnly = true) { j ->
        GeoSupport.loadService(j, serviceId, lock = false)   // Lesen ist mandantenweit (Fahrgäste haben `geo:read`); RLS trennt die Mandanten
        headOf(j, serviceId)
    }

    override fun publish(ctx: RequestContext, serviceId: UUID, expectedRevision: Long, cmd: PublishRelationRule): RelationRuleView =
        ctxTx.run(ctx) { j -> doPublish(j, ctx, serviceId, expectedRevision, cmd) }

    @AuditRequired
    private fun doPublish(j: JdbcOperations, ctx: RequestContext, serviceId: UUID, expected: Long, cmd: PublishRelationRule): RelationRuleView {
        GeoSupport.loadService(j, serviceId, lock = true)                                   // 404 vor 422; sperrt den Dienst
        if (!ctx.hasScope("geo:write")) authorizer.requireWrite(j, ctx, GeoSupport.scoped(ctx, serviceId))
        val head = headOf(j, serviceId)
        if ((head?.revision ?: 0L) != expected) throw GeoSupport.revisionMismatch()
        val v = mutableListOf<Violation>()
        if (cmd.reason.isBlank()) v += Violation("/reason", ViolationCode.REQUIRED) else if (cmd.reason.length > 500) v += Violation("/reason", ViolationCode.TOO_LONG)
        if (cmd.validTo != null && !cmd.validTo.isAfter(cmd.validFrom)) v += Violation("/valid_to", ViolationCode.INTERVAL_INVALID)
        val ids = parseIds(cmd.destinationStopIds, v)
        if (ids != null) {
            val known = j.query("SELECT DISTINCT s.stop_id FROM taktovia.stop_revision s WHERE s.stop_id = ANY(CAST(? AS uuid[]))", { rs, _ -> rs.getObject(1, UUID::class.java) }, ids.joinToString(",", "{", "}")).toSet()
            ids.forEachIndexed { i, id -> if (id !in known) v += Violation("/destination_stop_ids/$i", ViolationCode.INVALID_VALUE, "Zielpunkt ist kein Haltepunkt dieses Mandanten") }
        }
        GeoSupport.throwIf(v)
        if (head != null && head.validTo == null && head.validFrom.isBefore(cmd.validFrom))
            j.update("UPDATE taktovia.relation_rule SET valid_to = ? WHERE id = ? AND valid_to IS NULL", Timestamp.from(cmd.validFrom), head.id)
        val revision = expected + 1; val id = UUID.randomUUID()
        try {
            j.update("INSERT INTO taktovia.relation_rule (tenant_id, id, service_id, revision, destination_stop_ids, valid_from, valid_to, reason, created_by) " +
                "VALUES (taktovia.current_tenant(), ?, ?, ?, CAST(? AS uuid[]), ?, ?, ?, ?)", id, serviceId, revision, ids!!.joinToString(",", "{", "}"),   // nur geprüfte UUIDs
                Timestamp.from(cmd.validFrom), cmd.validTo?.let { Timestamp.from(it) }, cmd.reason, GeoSupport.actor(ctx).value)
        } catch (_: DuplicateKeyException) { throw GeoSupport.revisionMismatch() }
        catch (e: DataAccessException) { throw DbErrorMapper.map(e) }   // Exclusion Constraint: überlappende Wirksamkeit, 409
        val created = j.query("SELECT $COLS FROM taktovia.relation_rule r WHERE r.id = ?", MAPPER, id).single()
        audit.record(j, GeoSupport.entry(ctx, AuditActions.GEO_RELATION_RULE_PUBLISHED, "service", serviceId, revision, "published", linkedMapOf(
            "destination_count" to created.destinationStopIds.size, "valid_from" to created.validFrom.toString(), "valid_to" to created.validTo?.toString(), "reason" to created.reason)))
        outbox.append(j, OutboxEvent(serviceId, revision, "geo.relation_rule_effective", linkedMapOf(
            "service_id" to serviceId.toString(), "revision" to revision, "destination_count" to created.destinationStopIds.size,
            "valid_from" to created.validFrom.toString(), "valid_to" to created.validTo?.toString()), clock.instant(), correlationId = ctx.correlationId))
        return created
    }

    private fun parseIds(raw: Any?, v: MutableList<Violation>): List<UUID>? {
        val list = raw as? List<*>
        if (list == null) { v += Violation("/destination_stop_ids", if (raw == null) ViolationCode.REQUIRED else ViolationCode.TYPE_MISMATCH); return null }
        if (list.size > 1000) { v += Violation("/destination_stop_ids", ViolationCode.TOO_MANY_ITEMS); return null }
        val out = list.mapIndexedNotNull { i, x ->
            (x as? String)?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: run { v += Violation("/destination_stop_ids/$i", ViolationCode.INVALID_FORMAT); null }
        }
        if (out.toSet().size != out.size) v += Violation("/destination_stop_ids", ViolationCode.NOT_UNIQUE)
        return if (v.any { it.pointer.startsWith("/destination_stop_ids") }) null else out
    }

    override fun check(ctx: RequestContext, serviceId: UUID, origin: GeoPoint, destination: RelationEnd, at: Instant): RelationDecision = ctxTx.run(ctx, readOnly = true) { j ->
        val t = Timestamp.from(at)
        // Alle Fakten in einer Anweisung (ein Snapshot): vollständig die alte oder die neue wirksame Regelversion
        val f = j.query("WITH ds AS (SELECT s.point, s.active FROM taktovia.stop_revision s WHERE s.stop_id = CAST(? AS uuid) AND s.valid_from <= ? AND (s.valid_to IS NULL OR ? < s.valid_to) ORDER BY s.revision DESC LIMIT 1), " +
            "pts AS (SELECT ST_SetSRID(ST_MakePoint(?, ?), 4326) AS o, COALESCE((SELECT point FROM ds), ST_SetSRID(ST_MakePoint(CAST(? AS float8), CAST(? AS float8)), 4326)) AS d), " +
            "za AS (SELECT a.zone_role, a.area FROM taktovia.service_area a WHERE a.service_id = ? AND a.valid_from <= ? AND (a.valid_to IS NULL OR ? < a.valid_to)) " +
            "SELECT (SELECT count(*) FROM taktovia.service s WHERE s.id = ?) AS service_found, " +
            "(SELECT count(*) FROM za WHERE za.zone_role IN ('ORIGIN', 'BOTH')) AS origin_zones, " +
            "(SELECT count(*) FROM za, pts WHERE za.zone_role IN ('ORIGIN', 'BOTH') AND ST_Covers(za.area, pts.o)) AS origin_hits, " +
            "(SELECT count(*) FROM za WHERE za.zone_role IN ('DESTINATION', 'BOTH')) AS dest_zones, " +
            "(SELECT count(*) FROM za, pts WHERE za.zone_role IN ('DESTINATION', 'BOTH') AND ST_Covers(za.area, pts.d)) AS dest_hits, " +
            "(SELECT count(*) FROM ds) AS stop_effective, (SELECT ds.active FROM ds) AS stop_active, " +
            "(SELECT r.destination_stop_ids FROM taktovia.relation_rule r WHERE r.service_id = ? AND r.valid_from <= ? AND (r.valid_to IS NULL OR ? < r.valid_to) ORDER BY r.revision DESC LIMIT 1) AS dest_set",
            RowMapper { rs, _ -> Facts(rs.getInt("service_found"), rs.getInt("origin_zones"), rs.getInt("origin_hits"), rs.getInt("dest_zones"), rs.getInt("dest_hits"),
                rs.getInt("stop_effective"), rs.getObject("stop_active") as Boolean?, rs.getArray("dest_set")?.let { a -> (a.array as Array<*>).map { it as UUID } }) },
            destination.stopId, t, t, origin.longitude, origin.latitude, destination.point?.longitude, destination.point?.latitude, serviceId, t, t, serviceId, serviceId, t, t).single()
        if (f.serviceFound == 0) throw ProblemException.notFound()
        decide(f, destination)
    }

    private class Facts(val serviceFound: Int, val originZones: Int, val originHits: Int, val destZones: Int, val destHits: Int, val stopEffective: Int, val stopActive: Boolean?, val destSet: List<UUID>?)

    private fun decide(f: Facts, destination: RelationEnd): RelationDecision {
        fun no(r: RelationRejection, detail: String) = RelationDecision(false, r, detail)
        if (f.originZones == 0) return no(RelationRejection.OUTSIDE_AREA, "Der Dienst hat zum Zeitpunkt keine Startzone")
        if (f.originHits == 0) return no(RelationRejection.OUTSIDE_AREA, "Der Start liegt außerhalb der Startzone")
        if (destination.stopId != null && (f.stopEffective == 0 || f.stopActive != true)) return no(RelationRejection.RULE_FORBIDDEN, "Der Zielpunkt ist inaktiv oder nicht wirksam")
        if (f.destZones == 0) return no(RelationRejection.OUTSIDE_AREA, "Der Dienst hat zum Zeitpunkt keine Zielzone")
        if (f.destHits == 0) return no(RelationRejection.OUTSIDE_AREA, "Das Ziel liegt außerhalb der Zielzone")
        val set = f.destSet
        if (!set.isNullOrEmpty() && (destination.stopId == null || destination.stopId !in set)) return no(RelationRejection.RULE_FORBIDDEN, "Das Ziel ist kein erlaubter Zielpunkt")
        return RelationDecision(true, null, null)
    }

    private fun headOf(j: JdbcOperations, serviceId: UUID): RelationRuleView? =
        j.query("SELECT $COLS FROM taktovia.relation_rule r WHERE r.service_id = ? ORDER BY r.revision DESC LIMIT 1", MAPPER, serviceId).singleOrNull()

    private companion object {
        val MAPPER = RowMapper { rs, _ ->
            RelationRuleView(rs.getObject("id", UUID::class.java), rs.getObject("service_id", UUID::class.java), rs.getLong("revision"),
                (rs.getArray("destination_stop_ids").array as Array<*>).map { it as UUID }, rs.getTimestamp("valid_from").toInstant(), rs.getTimestamp("valid_to")?.toInstant(),
                rs.getString("reason"), rs.getString("created_by"), rs.getTimestamp("created_at").toInstant())
        }
    }
}

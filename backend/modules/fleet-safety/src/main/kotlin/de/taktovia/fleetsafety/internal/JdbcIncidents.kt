package de.taktovia.fleetsafety.internal

import de.taktovia.fleetsafety.api.IncidentStatus
import de.taktovia.fleetsafety.api.IncidentView
import de.taktovia.fleetsafety.api.Incidents
import de.taktovia.fleetsafety.api.WorkflowLogEntry
import de.taktovia.platform.authz.ScopedObject
import de.taktovia.platform.authz.ServiceScopeAuthorizer
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.Purpose
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import org.springframework.jdbc.core.JdbcOperations
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * Lesesicht der Incidents (WP-M3-53/-55). Die Rechtematrix im Anfragefilter ist die erste Schicht, Rolle und Scope hier die zweite. Sichtbar sind nur Incidents der berechtigten Dienste
 * (ohne Dienstbezug mandantenweit); fremder Dienst, fremder Mandant (RLS) und unbekannte Kennung sind gleichermaßen 404.
 */
internal class JdbcIncidents(private val ctxTx: ContextTransactions) : Incidents {
    private class Row(val id: UUID, val kind: String, val vehicleId: UUID?, val status: IncidentStatus, val dedupeKey: String, val openedAt: Instant, val updatedAt: Instant, val ruleId: UUID?,
        val operationId: UUID?, val serviceId: UUID?, val revision: Long, val closedAt: Instant?)

    override fun list(ctx: RequestContext, status: IncidentStatus?, vehicleId: UUID?, limit: Int, after: Pair<Instant, UUID>?): List<IncidentView> {
        guard(ctx)
        val services = ServiceScopeAuthorizer.effectiveServices(ctx, null, tenantWide = false).orEmpty().joinToString(",", "{", "}")   // nur UUIDs
        val ts = after?.first?.let(Timestamp::from)
        return ctxTx.run(ctx, readOnly = true) { j ->
            val rows = j.query(
                "SELECT $COLUMNS FROM taktovia.incident i WHERE (i.service_id IS NULL OR i.service_id = ANY(CAST(? AS uuid[]))) AND (CAST(? AS text) IS NULL OR i.status = CAST(? AS text)) " +
                    "AND (CAST(? AS uuid) IS NULL OR i.vehicle_id = CAST(? AS uuid)) AND (CAST(? AS timestamptz) IS NULL OR (i.opened_at, i.id) > (CAST(? AS timestamptz), CAST(? AS uuid))) " +
                    "ORDER BY i.opened_at, i.id LIMIT ?", MAPPER, services, status?.name, status?.name, vehicleId, vehicleId, ts, ts, after?.second, limit + 1)
            enrich(j, rows)
        }
    }

    override fun get(ctx: RequestContext, id: UUID): IncidentView {
        guard(ctx)
        return ctxTx.run(ctx, readOnly = true) { j ->
            val row = j.query("SELECT $COLUMNS FROM taktovia.incident i WHERE i.id = ?", MAPPER, id).firstOrNull() ?: throw ProblemException.notFound()
            if (!ServiceScopeAuthorizer.canRead(j, ctx, ScopedObject("incident", id, ctx.tenant, row.serviceId), Purpose.OPERATIONS)) throw ProblemException.notFound()
            enrich(j, listOf(row)).single().copy(workflowLog = log(j, id))
        }
    }

    /** Ablaufprotokoll des Incidents in der Reihenfolge der Ordnungsnummer (Zeilen aus der Zeit vor `V30550` ohne Nummer zuerst, nach Zeit); nur Codes und Kennungen. */
    private fun log(j: JdbcOperations, incident: UUID): List<WorkflowLogEntry> = j.query(
        "SELECT l.seq, l.created_at, l.event_id, l.event_type, l.rule_revision_id, r.revision_no, l.action, l.outcome, l.decision, l.created_by, l.correlation_id, l.result_ref, l.reason_code " +
            "FROM taktovia.workflow_run_log l LEFT JOIN taktovia.workflow_rule_revision r ON r.tenant_id = l.tenant_id AND r.id = l.rule_revision_id WHERE l.incident_id = ? ORDER BY l.seq NULLS FIRST, l.created_at, l.id",
        { rs, _ -> WorkflowLogEntry(rs.getLong(1), rs.getTimestamp(2).toInstant(), rs.getObject(3, UUID::class.java), rs.getString(4), rs.getObject(5, UUID::class.java), rs.getObject(6) as Int?, rs.getString(7),
            rs.getString(8), rs.getString(9), rs.getString(10), rs.getObject(11, UUID::class.java), rs.getString(12), rs.getString(13)) }, incident)

    private fun enrich(j: JdbcOperations, rows: List<Row>): List<IncidentView> {
        if (rows.isEmpty()) return emptyList()
        val ids = rows.joinToString(",", "{", "}") { it.id.toString() }
        val links = j.query("SELECT incident_id, link_kind, object_id FROM taktovia.incident_link WHERE incident_id = ANY(CAST(? AS uuid[])) ORDER BY created_at, id",
            { rs, _ -> Triple(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getObject(3, UUID::class.java)) }, ids).groupBy { it.first }
        return rows.map { r ->
            val l = links[r.id].orEmpty()
            IncidentView(r.id, r.kind, r.vehicleId, r.status, r.dedupeKey, r.openedAt, r.updatedAt, r.ruleId, l.filter { it.second == "VEHICLE_RUN" }.map { it.third }, l.filter { it.second == "DEFECT" }.map { it.third },
                r.operationId, r.serviceId, r.revision, r.closedAt)
        }
    }

    private fun guard(ctx: RequestContext) {
        if (ctx.actor.type != ActorType.HUMAN || ctx.actor.sessionId == null || !ctx.hasScope("operator:read") || ctx.roles.none { it in READERS }) throw ProblemException(ErrorCode.FORBIDDEN)
    }

    private companion object {
        val READERS = setOf("callcenter", "support", "dispatcher", "planner", "workshop")
        const val COLUMNS = "i.id, i.kind, i.vehicle_id, i.status, i.dedupe_key, i.opened_at, i.updated_at, i.rule_revision_id, i.replacement_operation_id, i.service_id, i.revision, i.closed_at"
        val MAPPER = org.springframework.jdbc.core.RowMapper { rs, _ ->
            Row(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getObject(3, UUID::class.java), IncidentStatus.valueOf(rs.getString(4)), rs.getString(5), rs.getTimestamp(6).toInstant(),
                rs.getTimestamp(7).toInstant(), rs.getObject(8, UUID::class.java), rs.getObject(9, UUID::class.java), rs.getObject(10, UUID::class.java), rs.getLong(11), rs.getTimestamp(12)?.toInstant())
        }
    }
}

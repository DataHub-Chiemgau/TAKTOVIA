package de.taktovia.fleetsafety.internal

import de.taktovia.fleetsafety.api.BlockKind
import de.taktovia.fleetsafety.api.BlockReason
import de.taktovia.fleetsafety.api.BlockSource
import de.taktovia.fleetsafety.api.OperatorBlockView
import de.taktovia.fleetsafety.api.OperatorVehicleFacts
import de.taktovia.fleetsafety.api.OperatorVehicles
import de.taktovia.platform.authz.ServiceScopeAuthorizer
import de.taktovia.platform.authz.ServiceScoped
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ProblemException
import org.springframework.jdbc.core.JdbcOperations
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Leitstellenprojektion der Fahrzeuge (WP-M3-36, siehe [OperatorVehicles]). Rein lesend, eine Transaktion je Aufruf. Der Dienstscope wird in dieser Transaktion frisch gelesen
 * ([ServiceScopeAuthorizer.currentServices]): ein Entzug, der vorher committet, wirkt sofort; Fahrzeuge anderer Mandanten sind durch RLS nie vorhanden.
 */
@ServiceScoped
internal class JdbcOperatorVehicles(private val ctxTx: ContextTransactions, private val readiness: JdbcReadiness, private val clock: Clock) : OperatorVehicles {

    private class Row(val id: UUID, val label: String, val active: Boolean, val blocked: Boolean, val safetyRevision: Long, val revision: Long, val createdAt: Instant)

    override fun list(ctx: RequestContext, serviceId: UUID?, openAlerts: Boolean?, limit: Int, after: Pair<Instant, UUID>?): List<OperatorVehicleFacts> = ctxTx.run(ctx, readOnly = true) { j ->
        val scope = ServiceScopeAuthorizer.currentServices(j, ctx)
        // ein Filter schränkt nur ein: Wunschdienst außerhalb des Scopes ergibt die leere Menge (kein Hinweis auf die Existenz)
        val wanted: Set<UUID>? = serviceId?.let { s -> if (s in scope) setOf(s) else return@run emptyList() }
        val now = clock.instant()
        val allowed = (wanted ?: scope).joinToString(",", "{", "}")   // nur UUIDs
        val ts = Timestamp.from(now)
        val cursor = after?.first?.let(Timestamp::from)
        val rows = j.query(
            "SELECT v.id, v.vehicle_label, v.active, v.active_block, v.safety_revision, v.master_revision, v.created_at FROM taktovia.vehicle v WHERE v.active " +
                "AND (EXISTS (SELECT 1 FROM taktovia.vehicle_pool_assignment a WHERE a.vehicle_id = v.id AND a.valid_from <= ? AND (a.valid_to IS NULL OR a.valid_to > ?) AND a.service_id = ANY(CAST(? AS uuid[]))) " +
                "OR (CAST(? AS boolean) AND NOT EXISTS (SELECT 1 FROM taktovia.vehicle_pool_assignment a WHERE a.vehicle_id = v.id AND a.valid_from <= ? AND (a.valid_to IS NULL OR a.valid_to > ?)))) " +
                "AND (CAST(? AS boolean) IS NULL OR CAST(? AS boolean) = EXISTS (SELECT 1 FROM taktovia.business_alert al WHERE al.vehicle_id = v.id AND al.state <> 'RESOLVED')) " +
                "AND (CAST(? AS timestamptz) IS NULL OR (v.created_at, v.id) > (CAST(? AS timestamptz), CAST(? AS uuid))) ORDER BY v.created_at, v.id LIMIT ?",
            { rs, _ -> Row(rs.getObject(1, UUID::class.java), rs.getString(2) ?: rs.getObject(1, UUID::class.java).toString().take(8), rs.getBoolean(3), rs.getBoolean(4), rs.getLong(5), rs.getLong(6), rs.getTimestamp(7).toInstant()) },
            ts, ts, allowed, wanted == null, ts, ts, openAlerts, openAlerts, cursor, cursor, after?.second, limit + 1)
        facts(j, rows, now)
    }

    override fun get(ctx: RequestContext, vehicleId: UUID): OperatorVehicleFacts = ctxTx.run(ctx, readOnly = true) { j ->
        val scope = ServiceScopeAuthorizer.currentServices(j, ctx)
        val now = clock.instant(); val ts = Timestamp.from(now)
        val row = j.query(
            "SELECT v.id, v.vehicle_label, v.active, v.active_block, v.safety_revision, v.master_revision, v.created_at FROM taktovia.vehicle v WHERE v.id = ? AND v.active " +
                "AND (EXISTS (SELECT 1 FROM taktovia.vehicle_pool_assignment a WHERE a.vehicle_id = v.id AND a.valid_from <= ? AND (a.valid_to IS NULL OR a.valid_to > ?) AND a.service_id = ANY(CAST(? AS uuid[]))) " +
                "OR NOT EXISTS (SELECT 1 FROM taktovia.vehicle_pool_assignment a WHERE a.vehicle_id = v.id AND a.valid_from <= ? AND (a.valid_to IS NULL OR a.valid_to > ?)))",
            { rs, _ -> Row(rs.getObject(1, UUID::class.java), rs.getString(2) ?: rs.getObject(1, UUID::class.java).toString().take(8), rs.getBoolean(3), rs.getBoolean(4), rs.getLong(5), rs.getLong(6), rs.getTimestamp(7).toInstant()) },
            vehicleId, ts, ts, scope.joinToString(",", "{", "}"), ts, ts).firstOrNull() ?: throw ProblemException.notFound()   // nicht sichtbar = nicht vorhanden
        facts(j, listOf(row), now).single()
    }

    private fun facts(j: JdbcOperations, rows: List<Row>, now: Instant): List<OperatorVehicleFacts> {
        if (rows.isEmpty()) return emptyList()
        val ids = rows.joinToString(",", "{", "}") { it.id.toString() }   // nur UUIDs
        val ts = Timestamp.from(now)
        val services = j.query("SELECT vehicle_id, service_id FROM taktovia.vehicle_pool_assignment WHERE vehicle_id = ANY(CAST(? AS uuid[])) AND valid_from <= ? AND (valid_to IS NULL OR valid_to > ?) ORDER BY service_id",
            { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getObject(2, UUID::class.java) }, ids, ts, ts).groupBy({ it.first }, { it.second })
        val vehicleBlocks = j.query(
            "SELECT b.id, b.vehicle_id, b.source, b.reason_code, b.case_number, b.set_by, b.set_at, b.revision FROM taktovia.vehicle_block b WHERE b.vehicle_id = ANY(CAST(? AS uuid[])) AND b.status = 'ACTIVE' ORDER BY b.set_at, b.id",
            { rs, _ -> OperatorBlockView(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), BlockKind.VEHICLE, BlockSource.valueOf(rs.getString(3)), BlockReason.valueOf(rs.getString(4)), rs.getString(5),
                rs.getString(6), rs.getTimestamp(7).toInstant(), null, rs.getLong(8)) }, ids)
        val shifts = j.query(
            "SELECT DISTINCT ON (s.vehicle_id) s.vehicle_id, s.id FROM taktovia.staff_shift s WHERE s.vehicle_id = ANY(CAST(? AS uuid[])) AND s.state IN ('STARTED', 'PUBLISHED') AND s.starts_at <= ? AND s.ends_at > ? " +
                "ORDER BY s.vehicle_id, (s.state = 'STARTED') DESC, s.starts_at DESC, s.id", { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getObject(2, UUID::class.java) }, ids, ts, ts).toMap()
        val shiftBlocks = j.query(
            "SELECT b.id, b.vehicle_id, b.case_number, b.set_by, b.set_at, b.shift_id, b.revision FROM taktovia.shift_block b WHERE b.vehicle_id = ANY(CAST(? AS uuid[])) AND b.status = 'ACTIVE' ORDER BY b.set_at, b.id",
            { rs, _ -> OperatorBlockView(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), BlockKind.SERVICE, BlockSource.SAFETY, BlockReason.INSPECTION_FAILED, rs.getString(3), rs.getString(4),
                rs.getTimestamp(5).toInstant(), rs.getObject(6, UUID::class.java), rs.getLong(7)) }, ids)
        val defects = j.query("SELECT vehicle_id, count(*) FROM taktovia.defect_case WHERE vehicle_id = ANY(CAST(? AS uuid[])) AND status IN ('NEW', 'REVIEWED', 'IN_PROGRESS', 'REPAIR') GROUP BY vehicle_id",
            { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getInt(2) }, ids).toMap()
        val alerts = j.query("SELECT vehicle_id, count(*) FROM taktovia.business_alert WHERE vehicle_id = ANY(CAST(? AS uuid[])) AND state <> 'RESOLVED' GROUP BY vehicle_id",
            { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getInt(2) }, ids).toMap()
        return rows.map { r ->
            val shiftId = shifts[r.id]
            val req = shiftId?.let { readiness.requirementsIn(j, r.id, it, now) }
            // eine Dienstsperre gilt nur für ihre Schicht; sie steht in der Ansicht der Schicht, nicht an anderen Schichten des Fahrzeugs
            val blocks = vehicleBlocks.filter { it.vehicleId == r.id } + shiftBlocks.filter { it.vehicleId == r.id && it.shiftId == shiftId }
            OperatorVehicleFacts(r.id, r.label, r.active, r.blocked, r.safetyRevision, r.revision, r.createdAt, services[r.id].orEmpty(), shiftId, blocks, req?.gate, req?.validUntil, req?.missing.orEmpty(),
                req?.unavailableDimensions.orEmpty(), defects[r.id] ?: 0, alerts[r.id] ?: 0)
        }
    }
}

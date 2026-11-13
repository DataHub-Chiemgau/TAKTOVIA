package de.taktovia.bookingdispatch.internal

import de.taktovia.bookingdispatch.api.OperatorOrderView
import de.taktovia.bookingdispatch.api.OperatorOrders
import de.taktovia.bookingdispatch.api.OperatorRunSummary
import de.taktovia.bookingdispatch.api.OrderPhase
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import java.util.UUID

/** Aufträge je Fahrzeug aus `vehicle_run`, `route_stop` und `booking` (eigene Tabellen dieses Moduls), nur lesend. */
internal class JdbcOperatorOrders(private val ctxTx: ContextTransactions) : OperatorOrders {

    override fun forVehicles(ctx: RequestContext, vehicleIds: Collection<UUID>): Map<UUID, OperatorRunSummary> {
        if (vehicleIds.isEmpty()) return emptyMap()
        val ids = vehicleIds.joinToString(",", "{", "}")   // nur UUIDs
        return ctxTx.run(ctx, readOnly = true) { j ->
            class Head(val runId: UUID, val vehicleId: UUID, val state: String, val revision: Long)
            val heads = j.query(
                "SELECT DISTINCT ON (r.vehicle_id) r.id, r.vehicle_id, r.state, r.revision FROM taktovia.vehicle_run r JOIN taktovia.staff_shift s ON s.tenant_id = r.tenant_id AND s.id = r.shift_id " +
                    "WHERE r.vehicle_id = ANY(CAST(? AS uuid[])) AND r.state IN ('PLANNED', 'ACTIVE', 'LOCKED', 'IN_PROGRESS') ORDER BY r.vehicle_id, (r.state = 'IN_PROGRESS') DESC, s.starts_at DESC, r.id",
                { rs, _ -> Head(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getString(3), rs.getLong(4)) }, ids)
            if (heads.isEmpty()) return@run emptyMap()
            val runs = heads.joinToString(",", "{", "}") { it.runId.toString() }
            class Order(val runId: UUID, val view: OperatorOrderView)
            val orders = j.query(
                "SELECT rs.run_id, bk.id, bk.booking_no, bk.state, rs.planned_at, (r.state = 'IN_PROGRESS' AND rs.planned_at <= clock_timestamp()) FROM taktovia.route_stop rs " +
                    "JOIN taktovia.vehicle_run r ON r.tenant_id = rs.tenant_id AND r.id = rs.run_id AND r.revision = rs.run_revision " +
                    "CROSS JOIN LATERAL jsonb_array_elements(rs.boardings) AS b(item) " +
                    "JOIN taktovia.booking bk ON bk.tenant_id = rs.tenant_id AND bk.id = CAST(b.item ->> 'booking_id' AS uuid) " +
                    "WHERE rs.run_id = ANY(CAST(? AS uuid[])) AND bk.state NOT IN ('CANCELED', 'COMPLETED', 'FAILED', 'CANCEL_PENDING', 'COMPENSATING') ORDER BY rs.planned_at, rs.seq, bk.id",
                { rs, _ -> Order(rs.getObject(1, UUID::class.java), OperatorOrderView(rs.getObject(2, UUID::class.java), rs.getString(3), rs.getString(4), rs.getTimestamp(5).toInstant(),
                    if (rs.getBoolean(6)) OrderPhase.RUNNING else OrderPhase.NEXT)) }, runs)
            heads.associate { h -> h.vehicleId to OperatorRunSummary(h.runId, h.state, h.revision, orders.filter { it.runId == h.runId }.map { it.view }.take(OperatorOrders.MAX_ORDERS)) }
        }
    }
}

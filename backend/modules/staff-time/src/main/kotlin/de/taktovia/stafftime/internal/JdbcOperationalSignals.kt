package de.taktovia.stafftime.internal

import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.tenant.TenantId
import de.taktovia.stafftime.api.OpenReview
import de.taktovia.stafftime.api.OperationalSignals
import de.taktovia.stafftime.api.StaleVehicle
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.util.UUID

/** Lesende Betriebsaufträge aus `staff_shift`, `position_sample` und `driver_event` (eigene Tabellen dieses Moduls), im Mandanten des Auftrags (RLS). */
internal class JdbcOperationalSignals(private val ctxTx: ContextTransactions, private val clock: Clock) : OperationalSignals {

    override fun staleVehicles(tenant: TenantId, olderThan: Duration): List<StaleVehicle> {
        val cutoff = Timestamp.from(clock.instant().minus(olderThan))
        return ctxTx.runSystem(tenant, readOnly = true) { j ->
            j.query(
                "SELECT s.vehicle_id, s.id, coalesce(max(p.measured_at), s.started_at) FROM taktovia.staff_shift s " +
                    "LEFT JOIN taktovia.position_sample p ON p.tenant_id = s.tenant_id AND p.vehicle_id = s.vehicle_id AND p.shift_id = s.id AND p.quality <> 'UNUSABLE' " +
                    "WHERE s.state = 'STARTED' AND s.started_at IS NOT NULL GROUP BY s.vehicle_id, s.id, s.started_at HAVING coalesce(max(p.measured_at), s.started_at) < ? ORDER BY 3, s.id",
                { rs, _ -> StaleVehicle(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getTimestamp(3).toInstant()) }, cutoff,
            )
        }
    }

    override fun openReviews(tenant: TenantId, olderThan: Duration): List<OpenReview> {
        val cutoff = Timestamp.from(clock.instant().minus(olderThan))
        return ctxTx.runSystem(tenant, readOnly = true) { j ->
            j.query(
                "SELECT DISTINCT ON (e.shift_id) e.vehicle_id, e.shift_id, e.reason_code, e.received_at FROM taktovia.driver_event e " +
                    "JOIN taktovia.staff_shift s ON s.tenant_id = e.tenant_id AND s.id = e.shift_id AND s.state = 'STARTED' " +
                    "WHERE e.outcome = 'REVIEW_REQUIRED' AND e.received_at < ? " +
                    "AND NOT EXISTS (SELECT 1 FROM taktovia.driver_event a WHERE a.tenant_id = e.tenant_id AND a.shift_id = e.shift_id AND a.outcome = 'APPLIED' AND a.received_at > e.received_at) " +
                    "ORDER BY e.shift_id, e.received_at",
                { rs, _ -> OpenReview(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getString(3), rs.getTimestamp(4).toInstant()) }, cutoff,
            )
        }
    }
}

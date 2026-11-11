package de.taktovia.bookingdispatch.internal

import de.taktovia.bookingdispatch.api.AvailabilitySnapshot
import de.taktovia.bookingdispatch.api.ResourceAvailability
import de.taktovia.bookingdispatch.api.TimeWindow
import de.taktovia.bookingdispatch.api.Unusable
import de.taktovia.bookingdispatch.api.VehicleAvailability
import de.taktovia.bookingdispatch.api.VehicleResource
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID

/**
 * Leseport auf die Fixture-Daten (WP-M2-02): liest `vehicle`, `staff_shift`, `resource_release` und `vehicle_pool_assignment` in einem Statement, nur lesend
 * (read-only-Transaktion). Bis die führenden Module liefern (Fahrzeugstamm WP-M3-03, Pool WP-M3-06, Freigabe WP-M3-08, Schichten WP-M4-03/-65), stammen die Zeilen aus dem
 * Seed `tests/acceptance/fixtures/m2_referenzregion/seed.sql`; die Schnittstelle bleibt unverändert.
 */
internal class JdbcResourceAvailability(private val ctxTx: ContextTransactions, private val clock: Clock) : ResourceAvailability {

    private class Row(
        val vehicleId: UUID, val inPool: Boolean, val capacity: String, val activeBlock: Boolean, val safetyRevision: Long,
        val shiftId: UUID?, val shiftStart: java.time.Instant?, val shiftEnd: java.time.Instant?, val releaseId: UUID?, val releaseUntil: java.time.Instant?,
    ) {
        fun verdict(): VehicleAvailability = when {
            !inPool -> VehicleAvailability.NotUsable(vehicleId, Unusable.NOT_IN_POOL)
            activeBlock -> VehicleAvailability.NotUsable(vehicleId, Unusable.VEHICLE_BLOCKED)
            shiftId == null -> VehicleAvailability.NotUsable(vehicleId, Unusable.NO_SHIFT)
            releaseId == null -> VehicleAvailability.NotUsable(vehicleId, Unusable.NO_VALID_RELEASE)
            else -> VehicleAvailability.Usable(VehicleResource(vehicleId, capacity, safetyRevision, shiftId, shiftStart!!, shiftEnd!!, releaseId, releaseUntil!!))
        }
    }

    private val mapper = RowMapper { rs, _ ->
        Row(
            rs.getObject("id", UUID::class.java), rs.getBoolean("in_pool"), rs.getString("capacity_config"), rs.getBoolean("active_block"), rs.getLong("safety_revision"),
            rs.getObject("shift_id", UUID::class.java), rs.getTimestamp("starts_at")?.toInstant(), rs.getTimestamp("ends_at")?.toInstant(),
            rs.getObject("release_id", UUID::class.java), rs.getTimestamp("valid_until")?.toInstant(),
        )
    }

    /**
     * Ein Statement. Pool: gültig zum Fensterbeginn. Schicht: veröffentlicht und deckt das ganze Fenster. Freigabe: gehört zu dieser Schicht, nicht widerrufen, gilt bis
     * mindestens zum Fensterende und wurde gegen die aktuelle `safety_revision` des Fahrzeugs ausgestellt. Alle Werte sind gebunden.
     */
    private fun query(j: JdbcOperations, serviceId: UUID, vehicleId: UUID?, w: TimeWindow): List<Row> {
        val from = Timestamp.from(w.from); val to = Timestamp.from(w.to)
        return j.query(
            "SELECT v.id, (p.id IS NOT NULL) AS in_pool, v.capacity_config::text AS capacity_config, v.active_block, v.safety_revision, " +
                "s.id AS shift_id, s.starts_at, s.ends_at, r.id AS release_id, r.valid_until " +
                "FROM taktovia.vehicle v " +
                "LEFT JOIN taktovia.vehicle_pool_assignment p ON p.tenant_id = v.tenant_id AND p.vehicle_id = v.id AND p.service_id = ? " +
                "AND p.valid_from <= ? AND (p.valid_to IS NULL OR p.valid_to > ?) " +
                "LEFT JOIN LATERAL (SELECT sh.id, sh.starts_at, sh.ends_at FROM taktovia.staff_shift sh WHERE sh.tenant_id = v.tenant_id AND sh.vehicle_id = v.id " +
                "AND sh.state = '$SHIFT_USABLE' AND sh.starts_at <= ? AND sh.ends_at >= ? ORDER BY sh.starts_at, sh.id LIMIT 1) s ON true " +
                "LEFT JOIN LATERAL (SELECT rr.id, rr.valid_until FROM taktovia.resource_release rr WHERE rr.tenant_id = v.tenant_id AND rr.vehicle_id = v.id " +
                "AND rr.shift_id = s.id AND rr.revoked_at IS NULL AND rr.valid_until >= ? AND rr.safety_revision = v.safety_revision " +
                "ORDER BY rr.valid_until DESC, rr.id LIMIT 1) r ON true " +
                "WHERE (CAST(? AS uuid) IS NULL AND p.id IS NOT NULL) OR v.id = CAST(? AS uuid) ORDER BY v.id",
            mapper, serviceId, from, from, from, to, to, vehicleId, vehicleId,
        )
    }

    override fun usableVehicles(ctx: RequestContext, serviceId: UUID, window: TimeWindow): AvailabilitySnapshot {
        val rows = ctxTx.run(ctx, readOnly = true) { j -> query(j, serviceId, null, window) }
        val verdicts = rows.map { it.verdict() }
        return AvailabilitySnapshot(
            clock.instant(), SOURCE, serviceId, window,
            verdicts.filterIsInstance<VehicleAvailability.Usable>().map { it.resource }, verdicts.filterIsInstance<VehicleAvailability.NotUsable>(),
        )
    }

    override fun check(ctx: RequestContext, vehicleId: UUID, serviceId: UUID, window: TimeWindow): VehicleAvailability =
        ctxTx.run(ctx, readOnly = true) { j -> check(j, vehicleId, serviceId, window) }

    override fun check(jdbc: JdbcOperations, vehicleId: UUID, serviceId: UUID, window: TimeWindow): VehicleAvailability =
        query(jdbc, serviceId, vehicleId, window).firstOrNull()?.verdict() ?: VehicleAvailability.NotUsable(vehicleId, Unusable.UNKNOWN_VEHICLE)

    companion object {
        const val SOURCE = "FIXTURE"
        /** Zustand einer einsetzbaren Schicht: Annahme dieses Plans (M4 legt die Werte fest, `staff_shift.state` ist freier Text). */
        const val SHIFT_USABLE = "PUBLISHED"
    }
}

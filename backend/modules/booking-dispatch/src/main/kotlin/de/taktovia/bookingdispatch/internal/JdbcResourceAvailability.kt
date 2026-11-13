package de.taktovia.bookingdispatch.internal

import de.taktovia.bookingdispatch.api.AvailabilitySnapshot
import de.taktovia.bookingdispatch.api.ResourceAvailability
import de.taktovia.bookingdispatch.api.TimeWindow
import de.taktovia.bookingdispatch.api.Unusable
import de.taktovia.bookingdispatch.api.VehicleAvailability
import de.taktovia.bookingdispatch.api.VehicleResource
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.geo.GeoPoint
import de.taktovia.platform.readports.ShiftStaffingReadPort
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
internal class JdbcResourceAvailability(
    private val ctxTx: ContextTransactions, private val clock: Clock,
    /** Leseport „Schichtbesetzung“ (WP-M4-17); gesetzt löst er die Schicht-Fixture ab (WP-M4-65): Besetzung kommt dann aus `staff-time`, nicht aus einem Join auf `staff_shift`. */
    private val staffing: ShiftStaffingReadPort? = null,
) : ResourceAvailability {

    private class Row(
        val vehicleId: UUID, val inPool: Boolean, val areaOk: Boolean, val capacity: String, val activeBlock: Boolean, val safetyRevision: Long, val active: Boolean,
        val shiftId: UUID?, val shiftStart: java.time.Instant?, val shiftEnd: java.time.Instant?, val releaseId: UUID?, val releaseUntil: java.time.Instant?, val serviceStatus: String?,
    ) {
        fun verdict(): VehicleAvailability = when {
            !inPool -> VehicleAvailability.NotUsable(vehicleId, Unusable.NOT_IN_POOL)
            !areaOk -> VehicleAvailability.NotUsable(vehicleId, Unusable.OUTSIDE_SERVICE_AREA)
            !active -> VehicleAvailability.NotUsable(vehicleId, Unusable.VEHICLE_INACTIVE)
            activeBlock -> VehicleAvailability.NotUsable(vehicleId, Unusable.VEHICLE_BLOCKED)
            shiftId == null -> VehicleAvailability.NotUsable(vehicleId, Unusable.NO_SHIFT)
            releaseId == null -> VehicleAvailability.NotUsable(vehicleId, Unusable.NO_VALID_RELEASE)
            serviceStatus != null && serviceStatus !in DISPATCHABLE_STATUS -> VehicleAvailability.NotUsable(vehicleId, Unusable.SERVICE_STATUS_NOT_DISPATCHABLE)
            else -> VehicleAvailability.Usable(VehicleResource(vehicleId, capacity, safetyRevision, shiftId, shiftStart!!, shiftEnd!!, releaseId, releaseUntil!!))
        }
    }

    private val mapper = RowMapper { rs, _ ->
        Row(
            rs.getObject("id", UUID::class.java), rs.getBoolean("in_pool"), rs.getBoolean("area_ok"), rs.getString("capacity_config"), rs.getBoolean("active_block"), rs.getLong("safety_revision"), rs.getBoolean("active"),
            rs.getObject("shift_id", UUID::class.java), rs.getTimestamp("starts_at")?.toInstant(), rs.getTimestamp("ends_at")?.toInstant(),
            rs.getObject("release_id", UUID::class.java), rs.getTimestamp("valid_until")?.toInstant(), rs.getString("service_status"),
        )
    }

    /**
     * Ein Statement. Pool: gültig zum Fensterbeginn. Schicht: veröffentlicht und deckt das ganze Fenster. Freigabe: gehört zu dieser Schicht, nicht widerrufen, gilt bis
     * mindestens zum Fensterende und wurde gegen die aktuelle `safety_revision` des Fahrzeugs ausgestellt. Gebiet (WP-M3-06): hat die gültige Zuordnung einen Gebietsbezug, muss die zum
     * Fensterbeginn gültige Revision der Gebietskette alle [locations] enthalten (ohne Ort: nicht prüfbar, also nicht zulässig). Alle Werte sind gebunden.
     */
    private fun query(j: JdbcOperations, serviceId: UUID, vehicleId: UUID?, w: TimeWindow, locations: List<GeoPoint>, lock: Boolean = false): List<Row> {
        if (staffing != null) return viaPort(j, staffing, serviceId, vehicleId, w, locations, lock)
        val from = Timestamp.from(w.from); val to = Timestamp.from(w.to)
        return j.query(
            "SELECT v.id, (p.id IS NOT NULL) AS in_pool, ($AREA_OK) AS area_ok, v.capacity_config::text AS capacity_config, v.active_block, v.safety_revision, v.active, " +
                "s.id AS shift_id, s.starts_at, s.ends_at, r.id AS release_id, r.valid_until, st.status AS service_status " +
                "FROM taktovia.vehicle v " +
                "LEFT JOIN taktovia.vehicle_pool_assignment p ON p.tenant_id = v.tenant_id AND p.vehicle_id = v.id AND p.service_id = ? " +
                "AND p.valid_from <= ? AND (p.valid_to IS NULL OR p.valid_to > ?) " +
                "LEFT JOIN LATERAL (SELECT sh.id, sh.starts_at, sh.ends_at FROM taktovia.staff_shift sh WHERE sh.tenant_id = v.tenant_id AND sh.vehicle_id = v.id " +
                "AND sh.state IN ($SHIFT_USABLE) AND sh.starts_at <= ? AND sh.ends_at >= ? ORDER BY sh.starts_at, sh.id LIMIT 1) s ON true " +
                "LEFT JOIN LATERAL (SELECT rr.id, rr.valid_until FROM taktovia.resource_release rr WHERE rr.tenant_id = v.tenant_id AND rr.vehicle_id = v.id " +
                "AND rr.shift_id = s.id AND rr.revoked_at IS NULL AND rr.valid_until >= ? AND rr.safety_revision = v.safety_revision " +
                "ORDER BY rr.valid_until DESC, rr.id LIMIT 1) r ON true " +
                "LEFT JOIN taktovia.vehicle_service_status st ON st.tenant_id = v.tenant_id AND st.shift_id = s.id " +
                "WHERE (CAST(? AS uuid) IS NULL AND p.id IS NOT NULL) OR v.id = CAST(? AS uuid) ORDER BY v.id",
            mapper, GeoPoint.toJsonArray(locations), from, from, serviceId, from, from, from, to, to, vehicleId, vehicleId,
        )
    }

    /**
     * Besetzung aus dem Leseport „Schichtbesetzung“ (WP-M4-65): Fahrzeug-, Pool- und Gebietsstand in einem Statement ohne Schichtbezug, die Besetzung je Fahrzeug aus dem Port (in der Transaktion des Aufrufers,
     * bei [lock] mit Sperre der deckenden Schicht), die Freigabe der gelieferten Schicht in einem weiteren Statement. Nur veröffentlichte, besetzte Schichten machen ein Fahrzeug einsetzbar.
     */
    private fun viaPort(j: JdbcOperations, port: ShiftStaffingReadPort, serviceId: UUID, vehicleId: UUID?, w: TimeWindow, locations: List<GeoPoint>, lock: Boolean): List<Row> {
        val from = Timestamp.from(w.from)
        val base = j.query(
            "SELECT v.id, (p.id IS NOT NULL) AS in_pool, ($AREA_OK) AS area_ok, v.capacity_config::text AS capacity_config, v.active_block, v.safety_revision, v.active " +
                "FROM taktovia.vehicle v " +
                "LEFT JOIN taktovia.vehicle_pool_assignment p ON p.tenant_id = v.tenant_id AND p.vehicle_id = v.id AND p.service_id = ? " +
                "AND p.valid_from <= ? AND (p.valid_to IS NULL OR p.valid_to > ?) " +
                "WHERE (CAST(? AS uuid) IS NULL AND p.id IS NOT NULL) OR v.id = CAST(? AS uuid) ORDER BY v.id",
            { rs, _ -> Row(rs.getObject("id", UUID::class.java), rs.getBoolean("in_pool"), rs.getBoolean("area_ok"), rs.getString("capacity_config"), rs.getBoolean("active_block"), rs.getLong("safety_revision"), rs.getBoolean("active"), null, null, null, null, null, null) },
            GeoPoint.toJsonArray(locations), from, from, serviceId, from, from, vehicleId, vehicleId,
        )
        if (base.isEmpty()) return base
        // nur Fahrzeuge, die bis hierher einsetzbar sind, brauchen Besetzung und Freigabe
        val candidates = base.filter { it.inPool && it.areaOk && it.active && !it.activeBlock }
        val staffed = port.staffing(j, candidates.map { it.vehicleId }, w.from, w.to, lock).filterValues { it.staffed }
        val releases = if (staffed.isEmpty()) emptyMap() else j.query(
            "SELECT DISTINCT ON (rr.shift_id) rr.shift_id, rr.id, rr.valid_until FROM taktovia.resource_release rr JOIN taktovia.vehicle v ON v.tenant_id = rr.tenant_id AND v.id = rr.vehicle_id " +
                "WHERE rr.shift_id = ANY (CAST(? AS uuid[])) AND rr.revoked_at IS NULL AND rr.valid_until >= ? AND rr.safety_revision = v.safety_revision AND rr.vehicle_id = ANY (CAST(? AS uuid[])) " +
                "ORDER BY rr.shift_id, rr.valid_until DESC, rr.id",
            { rs, _ -> rs.getObject(1, UUID::class.java) to (rs.getObject(2, UUID::class.java) to rs.getTimestamp(3).toInstant()) },
            "{" + staffed.values.joinToString(",") { it.shiftId.toString() } + "}", Timestamp.from(w.to), "{" + staffed.keys.joinToString(",") + "}",
        ).toMap()
        // Dienststatus der besetzten Schichten (WP-M3-31); ohne Statuszeile gilt der Status als unbekannt und blockiert nicht (wie im direkten Pfad)
        val statuses = if (staffed.isEmpty()) emptyMap() else j.query(
            "SELECT st.shift_id, st.status FROM taktovia.vehicle_service_status st WHERE st.shift_id = ANY (CAST(? AS uuid[]))",
            { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getString(2) }, "{" + staffed.values.joinToString(",") { it.shiftId.toString() } + "}",
        ).toMap()
        return base.map { r ->
            val s = staffed[r.vehicleId] ?: return@map r
            val rel = releases[s.shiftId]
            Row(r.vehicleId, r.inPool, r.areaOk, r.capacity, r.activeBlock, r.safetyRevision, r.active, s.shiftId, s.startsAt, s.endsAt, rel?.first, rel?.second, statuses[s.shiftId])
        }
    }

    override fun usableVehicles(ctx: RequestContext, serviceId: UUID, window: TimeWindow, locations: List<GeoPoint>): AvailabilitySnapshot {
        val verdicts = ctxTx.run(ctx, readOnly = true) { j ->
            val base = query(j, serviceId, null, window, locations).map { it.verdict() }
            // „Schichtende“ (WP-M4-49): Fahrzeuge ohne deckende Schicht, deren Fahrer-Schicht vor dem Fenster begann und im Fenster endet
            val noShift = base.filterIsInstance<VehicleAvailability.NotUsable>().filter { it.reason == Unusable.NO_SHIFT }.map { it.vehicleId }
            val ended = if (noShift.isEmpty()) emptySet() else shiftEnded(j, noShift, window)
            base.map { v -> if (v is VehicleAvailability.NotUsable && v.vehicleId in ended) VehicleAvailability.NotUsable(v.vehicleId, Unusable.SHIFT_ENDED_IN_WINDOW) else v }
        }
        return AvailabilitySnapshot(
            clock.instant(), if (staffing != null) SOURCE_PORT else SOURCE, serviceId, window,
            verdicts.filterIsInstance<VehicleAvailability.Usable>().map { it.resource }, verdicts.filterIsInstance<VehicleAvailability.NotUsable>(),
        )
    }

    private fun shiftEnded(j: JdbcOperations, vehicleIds: List<UUID>, w: TimeWindow): Set<UUID> = j.query(
        "SELECT DISTINCT sh.vehicle_id FROM taktovia.staff_shift sh WHERE sh.vehicle_id = ANY (CAST(? AS uuid[])) AND sh.driver_ref IS NOT NULL AND sh.state IN ('PUBLISHED', 'STARTED') " +
            "AND sh.starts_at <= ? AND sh.ends_at > ? AND sh.ends_at < ?",
        { rs, _ -> rs.getObject(1, UUID::class.java) }, "{" + vehicleIds.joinToString(",") + "}", Timestamp.from(w.from), Timestamp.from(w.from), Timestamp.from(w.to),
    ).toSet()

    override fun check(ctx: RequestContext, vehicleId: UUID, serviceId: UUID, window: TimeWindow, locations: List<GeoPoint>): VehicleAvailability =
        ctxTx.run(ctx, readOnly = true) { j -> verdict(j, vehicleId, serviceId, window, locations, lock = false) }

    /** In der Transaktion des Aufrufers (Hold): die deckende Schicht wird gesperrt, damit ein paralleler Wegfall der Besetzung bis zum Ende dieser Transaktion wartet (nur mit Leseport, WP-M4-65). */
    override fun check(jdbc: JdbcOperations, vehicleId: UUID, serviceId: UUID, window: TimeWindow, locations: List<GeoPoint>): VehicleAvailability =
        verdict(jdbc, vehicleId, serviceId, window, locations, lock = true)

    private fun verdict(j: JdbcOperations, vehicleId: UUID, serviceId: UUID, window: TimeWindow, locations: List<GeoPoint>, lock: Boolean): VehicleAvailability =
        query(j, serviceId, vehicleId, window, locations, lock).firstOrNull()?.verdict() ?: VehicleAvailability.NotUsable(vehicleId, Unusable.UNKNOWN_VEHICLE)

    override fun isAssigned(jdbc: JdbcOperations, vehicleId: UUID, serviceId: UUID, at: java.time.Instant): Boolean {
        val ts = Timestamp.from(at)
        return jdbc.queryForObject(
            "SELECT count(*) FROM taktovia.vehicle_pool_assignment WHERE vehicle_id = ? AND service_id = ? AND valid_from <= ? AND (valid_to IS NULL OR valid_to > ?)",
            Int::class.java, vehicleId, serviceId, ts, ts,
        )!! > 0
    }

    override fun isStatusDispatchable(jdbc: JdbcOperations, vehicleId: UUID, at: java.time.Instant): Boolean {
        val ts = Timestamp.from(at)
        return jdbc.queryForObject(
            "SELECT count(*) FROM taktovia.vehicle_service_status st JOIN taktovia.staff_shift sh ON sh.tenant_id = st.tenant_id AND sh.id = st.shift_id " +
                "WHERE st.vehicle_id = ? AND sh.starts_at <= ? AND sh.ends_at >= ? AND st.status NOT IN ($DISPATCHABLE_STATUS_SQL)",
            Int::class.java, vehicleId, ts, ts,
        )!! == 0
    }

    companion object {
        const val SOURCE = "FIXTURE"
        /** Herkunft der Besetzung aus dem Leseport „Schichtbesetzung“ (WP-M4-65). */
        const val SOURCE_PORT = "STAFFING_PORT"

        /**
         * Gebietsprüfung der Dienstzuordnung `p` (WP-M3-06, Planungsannahme PA-02/OP-25), dieselbe Regel wie im Pool-Port von `fleet-safety`: die zum Zeitpunkt t gültige Revision der Gebietskette
         * (`COALESCE(area_key, id)`) der referenzierten Zeile muss alle übergebenen Punkte enthalten (`ST_Covers`). Ohne Gebietsbezug: wahr. Bindungen: Punkte (JSON), t, t.
         */
        const val AREA_OK = "p.service_area_id IS NULL OR (SELECT count(*) > 0 AND bool_and(ST_Covers(g.area, ST_SetSRID(ST_MakePoint((pt.value->>0)::float8, (pt.value->>1)::float8), 4326))) " +
            "FROM taktovia.service_area a0 JOIN taktovia.service_area g ON g.tenant_id = a0.tenant_id AND g.service_id = a0.service_id AND COALESCE(g.area_key, g.id) = COALESCE(a0.area_key, a0.id) " +
            "CROSS JOIN LATERAL jsonb_array_elements(CAST(? AS jsonb)) AS pt(value) " +
            "WHERE a0.tenant_id = p.tenant_id AND a0.id = p.service_area_id AND g.valid_from <= ? AND (g.valid_to IS NULL OR g.valid_to > ?))"
        /** Zustand einer einsetzbaren Schicht: Annahme dieses Plans (M4 legt die Werte fest, `staff_shift.state` ist freier Text). */
        const val SHIFT_USABLE = "'PUBLISHED', 'STARTED'"

        /** Für neue Zuweisungen zulässige Dienststatus (WP-M3-31, `zustaende/fahrzeug_dienststatus.md`): Verfügbarkeit, Anfahrt, Wartezeit. */
        val DISPATCHABLE_STATUS = setOf("AVAILABLE", "EN_ROUTE", "WAITING")
        const val DISPATCHABLE_STATUS_SQL = "'AVAILABLE', 'EN_ROUTE', 'WAITING'"
    }
}

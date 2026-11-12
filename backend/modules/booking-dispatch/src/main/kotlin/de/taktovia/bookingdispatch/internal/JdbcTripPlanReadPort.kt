package de.taktovia.bookingdispatch.internal

import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.readports.TripPlanReadPort
import de.taktovia.platform.readports.TripPlanView
import de.taktovia.platform.readports.TripStopView
import org.springframework.jdbc.core.JdbcOperations
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * Leseport „Fahrtenplan“ für M4 (WP-M4-02): aktuelle Planrevision eines Umlaufs aus `vehicle_run` und `route_stop` (eigene Tabellen dieses Moduls, WP-M2-16). Nur lesend, im Mandanten des
 * Kontexts (RLS); ein fremder Umlauf ist nicht vorhanden (`null`).
 */
internal class JdbcTripPlanReadPort(private val ctxTx: ContextTransactions) : TripPlanReadPort {
    private val json = JsonMapper.builder().build()

    override fun currentPlan(ctx: RequestContext, runId: UUID): TripPlanView? = ctxTx.run(ctx, readOnly = true) { j -> plan(j, "r.id = ?", runId) }

    override fun currentPlanOfShift(ctx: RequestContext, shiftId: UUID): TripPlanView? = ctxTx.run(ctx, readOnly = true) { j -> plan(j, "r.shift_id = ?", shiftId) }

    private fun plan(j: JdbcOperations, where: String, arg: UUID): TripPlanView? {
        data class Head(val id: UUID, val vehicle: UUID, val shift: UUID, val revision: Long, val state: String)
        val head = j.query("SELECT r.id, r.vehicle_id, r.shift_id, r.revision, r.state FROM taktovia.vehicle_run r WHERE $where",   // sql-lint: ok feste Bedingung aus zwei Konstanten dieser Klasse
            { rs, _ -> Head(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getObject(3, UUID::class.java), rs.getLong(4), rs.getString(5)) }, arg).firstOrNull() ?: return null
        val stops = j.query("SELECT s.seq, s.stop_revision_id, s.planned_at, s.boardings::text, s.alightings::text, s.leg_duration_sec, s.leg_distance_m FROM taktovia.route_stop s " +
            "WHERE s.run_id = ? AND s.run_revision = ? ORDER BY s.seq", { rs, _ ->
            TripStopView(rs.getInt(1), rs.getObject(2, UUID::class.java), rs.getTimestamp(3).toInstant(), bookings(rs.getString(4)), bookings(rs.getString(5)),
                rs.getObject(6) as Int?, rs.getObject(7) as Int?)
        }, head.id, head.revision)
        return TripPlanView(head.id, head.vehicle, head.shift, head.revision, head.state, stops)
    }

    private fun bookings(text: String): List<UUID> = json.readTree(text).values().map { UUID.fromString(it.get("booking_id").asString()) }
}

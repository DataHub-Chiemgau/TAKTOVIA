package de.taktovia.bookingdispatch.internal

import de.taktovia.bookingdispatch.api.DriverPlanStop
import de.taktovia.bookingdispatch.api.DriverPlanView
import de.taktovia.bookingdispatch.api.DriverRunPlans
import de.taktovia.bookingdispatch.api.DriverStopKind
import de.taktovia.bookingdispatch.api.ResourceVector
import de.taktovia.bookingdispatch.api.RunState
import de.taktovia.bookingdispatch.api.StopAssistance
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import org.springframework.jdbc.core.JdbcOperations
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * Fahrerprojektion auf PostgreSQL (WP-M3-26/-32). Eine schreibgeschützte Transaktion: der Umlauf der Schicht liefert die Planrevision, die Stopps genau dieser Revision
 * (append-only, unveränderlich) folgen in `route_stop`-Reihenfolge; Revision und Inhalt passen damit immer zusammen, auch während ein Plan-Commit läuft.
 */
internal class JdbcDriverRunPlans(private val ctxTx: ContextTransactions) : DriverRunPlans {
    private val json = JsonMapper.builder().build()

    private class Move(val bookingId: UUID, val vector: ResourceVector)

    override fun currentPlan(ctx: RequestContext, shiftId: UUID, vehicleId: UUID): DriverPlanView? {
        if ("driver" !in ctx.roles) throw ProblemException(ErrorCode.FORBIDDEN)
        return ctxTx.run(ctx, readOnly = true) { j ->
            val run = j.query("SELECT id, revision, state FROM taktovia.vehicle_run WHERE shift_id = ? AND vehicle_id = ?",
                { rs, _ -> Triple(rs.getObject(1, UUID::class.java), rs.getLong(2), RunState.valueOf(rs.getString(3))) }, shiftId, vehicleId).firstOrNull() ?: return@run null
            DriverPlanView(run.first, shiftId, vehicleId, run.second, run.third, stops(j, run.first, run.second))
        }
    }

    private class Raw(
        val id: UUID, val seq: Int, val stopRevisionId: UUID, val plannedAt: java.time.Instant, val boardings: List<Move>, val alightings: List<Move>, val legDurationSec: Int?,
        val name: String, val lon: Double, val lat: Double,
    )

    private fun stops(j: JdbcOperations, runId: UUID, revision: Long): List<DriverPlanStop> {
        val raw = j.query(
            "SELECT rs.id, rs.seq, rs.stop_revision_id, rs.planned_at, rs.boardings::text, rs.alightings::text, rs.leg_duration_sec, sr.name, ST_X(sr.point), ST_Y(sr.point) " +
                "FROM taktovia.route_stop rs JOIN taktovia.stop_revision sr ON sr.tenant_id = rs.tenant_id AND sr.id = rs.stop_revision_id WHERE rs.run_id = ? AND rs.run_revision = ? ORDER BY rs.seq",
            { rs, _ -> Raw(rs.getObject(1, UUID::class.java), rs.getInt(2), rs.getObject(3, UUID::class.java), rs.getTimestamp(4).toInstant(), moves(rs.getString(5)), moves(rs.getString(6)), rs.getObject(7) as Int?,
                rs.getString(8), rs.getDouble(9), rs.getDouble(10)) }, runId, revision)
        val bookingIds = raw.flatMap { r -> (r.boardings + r.alightings).map { it.bookingId } }.distinct()
        val trips = if (bookingIds.isEmpty()) emptyMap() else tripsOf(j, bookingIds)
        return raw.mapIndexed { i, r ->
            val kind = when { r.boardings.isNotEmpty() && r.alightings.isNotEmpty() -> DriverStopKind.PICKUP_DROPOFF; r.alightings.isNotEmpty() -> DriverStopKind.DROPOFF; else -> DriverStopKind.PICKUP }
            // Prognose bis zum ETA-Grundmodell (OP-20): Planzeit aus dem Routing, wenn die Fahrzeit zu diesem Stopp aus dem Routing stammt (erster Stopp: Abfahrtszeit); sonst „keine Prognose“
            val routed = i == 0 || raw[i - 1].legDurationSec != null
            val need = r.boardings.takeIf { it.isNotEmpty() }?.let { m -> ResourceVector(m.sumOf { it.vector.seats }, m.sumOf { it.vector.wheelchair }, m.flatMap { it.vector.equipment }.toSet()) }
            val boardingTrips = r.boardings.flatMap { trips[it.bookingId].orEmpty() }; val alightingTrips = r.alightings.flatMap { trips[it.bookingId].orEmpty() }
            val assistance = StopAssistance(
                wheelchairRequired = (boardingTrips + alightingTrips).any { it.wheelchair }, boardingAssist = boardingTrips.any { it.help }, alightingAssist = alightingTrips.any { it.help },
            ).takeIf { it.any }
            DriverPlanStop(r.id, r.seq, r.stopRevisionId, kind, r.plannedAt, if (routed) r.plannedAt else null, r.name, r.lon, r.lat, r.boardings.map { it.bookingId }, r.alightings.map { it.bookingId },
                r.boardings.sumOf { it.vector.seats + it.vector.wheelchair }, r.alightings.sumOf { it.vector.seats + it.vector.wheelchair }, need, assistance)
        }
    }

    private class Trip(val wheelchair: Boolean, val help: Boolean)

    /** Merkmale je Buchung aus `passenger_trip`: Rollstuhlplatz (Platzart oder Code) und Ein-/Ausstiegshilfe (Code `BOARDING_HELP`); nur diese Katalogmerkmale, keine Freitexte. */
    private fun tripsOf(j: JdbcOperations, bookingIds: List<UUID>): Map<UUID, List<Trip>> {
        val out = HashMap<UUID, MutableList<Trip>>()
        j.query("SELECT booking_id, place_kind, array_to_string(assistance_codes, ',') FROM taktovia.passenger_trip WHERE booking_id = ANY(CAST(? AS uuid[]))", { rs, _ ->
            val codes = rs.getString(3).orEmpty().split(',')
            out.getOrPut(rs.getObject(1, UUID::class.java)) { mutableListOf() }.add(Trip(rs.getString(2) == "WHEELCHAIR" || "WHEELCHAIR_SPACE" in codes, "BOARDING_HELP" in codes))
        }, bookingIds.joinToString(",", "{", "}"))   // nur UUIDs
        return out
    }

    private fun moves(text: String): List<Move> = json.readTree(text).let { arr -> (0 until arr.size()).map { i -> arr.get(i).let { Move(UUID.fromString(it.get("booking_id").asString()), ResourceVector.parse(it.get("vector"))) } } }
}

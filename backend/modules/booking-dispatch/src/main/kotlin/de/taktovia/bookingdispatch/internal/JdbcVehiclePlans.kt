package de.taktovia.bookingdispatch.internal

import de.taktovia.bookingdispatch.api.DimensionCounts
import de.taktovia.bookingdispatch.api.DriverStopKind
import de.taktovia.bookingdispatch.api.EtaBasis
import de.taktovia.bookingdispatch.api.EtaStopEtaPort
import de.taktovia.bookingdispatch.api.PlanForecast
import de.taktovia.bookingdispatch.api.PlanForecastInfo
import de.taktovia.bookingdispatch.api.PlanPromiseWindow
import de.taktovia.bookingdispatch.api.PlanReviewCase
import de.taktovia.bookingdispatch.api.PromiseWindowKind
import de.taktovia.bookingdispatch.api.ResourceVector
import de.taktovia.bookingdispatch.api.RunState
import de.taktovia.bookingdispatch.api.VehiclePlan
import de.taktovia.bookingdispatch.api.VehiclePlanStop
import de.taktovia.bookingdispatch.api.VehiclePlans
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ProblemException
import org.springframework.jdbc.core.JdbcOperations
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Fahrzeugplan auf PostgreSQL (WP-M3-38). Eine schreibgeschützte Transaktion: Umlauf (Revision), die Stopps genau dieser Revision (unveränderlich), die Zusagefenster der bestätigten Holds des
 * Umlaufs und die **bestätigten** Fahrerereignisse der Schicht (`driver_event`, nur Lesen; Eigentümer `staff-time`). Besetzung je Dimension = Summe der Vektoren der Einstiege (`CHECK_IN`,
 * `APPLIED`) minus Summe der Vektoren der Ausstiege (`CHECK_OUT`, `APPLIED`), je Buchung höchstens einmal je Art; `REVIEW_REQUIRED` zählt nie mit, wird aber ausgewiesen.
 */
internal class JdbcVehiclePlans(private val ctxTx: ContextTransactions, private val eta: EtaStopEtaPort?, private val profile: String, private val clock: Clock) : VehiclePlans {
    private val json = JsonMapper.builder().build()

    private class Head(val runId: UUID, val shiftId: UUID, val state: RunState, val revision: Long)
    private class Window(val pickupEarliest: Instant, val pickupLatest: Instant, val arrivalEarliest: Instant, val arrivalLatest: Instant)
    private class Move(val bookingId: UUID, val vector: ResourceVector)
    private class RawStop(val id: UUID, val seq: Int, val stopRevisionId: UUID, val plannedAt: Instant, val boardings: List<Move>, val alightings: List<Move>, val legDurationSec: Int?, val name: String)

    override fun plan(ctx: RequestContext, vehicleId: UUID): VehiclePlan {
        val now = clock.instant()
        val read = ctxTx.run(ctx, readOnly = true) { j ->
            val capacityJson = j.query("SELECT capacity_config::text FROM taktovia.vehicle WHERE id = ?", { rs, _ -> rs.getString(1) }, vehicleId).firstOrNull() ?: throw ProblemException.notFound()
            val capacity = ResourceVector.parse(capacityJson, "/vehicle/capacity_config").let { DimensionCounts(it.seats, it.wheelchair) }
            val run = j.query(
                "SELECT r.id, r.shift_id, r.state, r.revision FROM taktovia.vehicle_run r JOIN taktovia.staff_shift s ON s.tenant_id = r.tenant_id AND s.id = r.shift_id " +
                    "WHERE r.vehicle_id = ? AND r.state IN ('PLANNED', 'ACTIVE', 'LOCKED', 'IN_PROGRESS') ORDER BY (r.state = 'IN_PROGRESS') DESC, s.starts_at DESC, r.id LIMIT 1",
                { rs, _ -> Head(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), RunState.valueOf(rs.getString(3)), rs.getLong(4)) }, vehicleId,
            ).firstOrNull() ?: return@run Snapshot(null, capacity, emptyList(), emptyMap(), DimensionCounts(0, 0), emptyList())
            val stops = stops(j, run.runId, run.revision)
            val windows = windows(j, stops)
            val (occupancy, review) = occupancy(j, run, vehicleId)
            Snapshot(run, capacity, stops, windows, occupancy, review)
        }
        val run = read.head ?: return VehiclePlan(vehicleId, null, null, null, now, emptyList(), read.capacity, DimensionCounts(0, 0), emptyList(), PlanForecastInfo("NONE", null, null, null, null))

        // Prognose: Leseport „ETA je Stopp“ (WP-M4-66), sonst Planzeit aus dem Routing (OP-20); je Stopp `null` = keine Prognose
        val report = eta?.let { port ->
            try { port.etaPerStop(ctx, run.runId, profile, null, 0) } catch (e: ProblemException) { throw e } catch (e: RuntimeException) { null }
        }?.takeIf { it.planRevision == run.revision }
        val byStop = report?.stops?.associateBy { it.stopRevisionId to it.seq }
        val stops = read.stops.mapIndexed { i, s ->
            val routed = i == 0 || read.stops[i - 1].legDurationSec != null
            // Eine Prognose des Grundmodells (Live-Position oder Referenzprognose) ersetzt die Planzeit; im Rückfall auf die Planzeit gilt unverändert die Regel „nur wo die Fahrzeit aus dem Routing stammt“,
            // sonst „keine Prognose“ (nie eine erfundene Zeit)
            val modelled = byStop?.get(s.stopRevisionId to s.seq)?.takeIf { it.basis != EtaBasis.PLAN_TIME }
            val forecast: PlanForecast? = when {
                modelled != null -> PlanForecast(modelled.forecastEarliest, modelled.forecastLatest, modelled.basis)
                routed -> PlanForecast(s.plannedAt, s.plannedAt, EtaBasis.PLAN_TIME)
                else -> null
            }
            val kind = when { s.boardings.isNotEmpty() && s.alightings.isNotEmpty() -> DriverStopKind.PICKUP_DROPOFF; s.alightings.isNotEmpty() -> DriverStopKind.DROPOFF; else -> DriverStopKind.PICKUP }
            val windows = s.boardings.mapNotNull { m -> read.windows[m.bookingId]?.let { PlanPromiseWindow(m.bookingId, PromiseWindowKind.PICKUP, it.pickupEarliest, it.pickupLatest) } } +
                s.alightings.mapNotNull { m -> read.windows[m.bookingId]?.let { PlanPromiseWindow(m.bookingId, PromiseWindowKind.DROPOFF, it.arrivalEarliest, it.arrivalLatest) } }
            VehiclePlanStop(s.id, s.seq + 1, s.stopRevisionId, s.name, kind, s.plannedAt, forecast, windows, s.boardings.map { it.bookingId }, s.alightings.map { it.bookingId })
        }
        val info = if (report != null) PlanForecastInfo("ETA_MODEL", report.dataStand, report.fallbackReason?.name, report.modelVersion, report.configVersion)
        else PlanForecastInfo("PLAN_TIME", null, null, null, null)
        return VehiclePlan(vehicleId, run.runId, run.state, run.revision, now, stops, read.capacity, read.occupancy, read.review, info)
    }

    private class Snapshot(val head: Head?, val capacity: DimensionCounts, val stops: List<RawStop>, val windows: Map<UUID, Window>, val occupancy: DimensionCounts, val review: List<PlanReviewCase>)

    private fun stops(j: JdbcOperations, runId: UUID, revision: Long): List<RawStop> = j.query(
        "SELECT rs.id, rs.seq, rs.stop_revision_id, rs.planned_at, rs.boardings::text, rs.alightings::text, rs.leg_duration_sec, sr.name " +
            "FROM taktovia.route_stop rs JOIN taktovia.stop_revision sr ON sr.tenant_id = rs.tenant_id AND sr.id = rs.stop_revision_id WHERE rs.run_id = ? AND rs.run_revision = ? ORDER BY rs.seq",
        { rs, _ -> RawStop(rs.getObject(1, UUID::class.java), rs.getInt(2), rs.getObject(3, UUID::class.java), rs.getTimestamp(4).toInstant(), moves(rs.getString(5)), moves(rs.getString(6)), rs.getObject(7) as Int?, rs.getString(8)) },
        runId, revision,
    )

    /** Zusagefenster der Buchungen des Plans aus der unveränderlichen Zusage (`booking.promise_snapshot`); stornierte und gescheiterte Buchungen haben keine geltende Zusage. */
    private fun windows(j: JdbcOperations, stops: List<RawStop>): Map<UUID, Window> {
        val ids = stops.flatMap { s -> (s.boardings + s.alightings).map { it.bookingId } }.distinct()
        if (ids.isEmpty()) return emptyMap()
        val out = HashMap<UUID, Window>()
        j.query(
            "SELECT id, promise_snapshot::text FROM taktovia.booking WHERE id = ANY(CAST(? AS uuid[])) AND promise_snapshot IS NOT NULL AND state NOT IN ('CANCELED', 'CANCEL_PENDING', 'COMPENSATING', 'FAILED')",
            { rs, _ ->
                val n = json.readTree(rs.getString(2))
                fun at(k: String) = java.time.OffsetDateTime.parse(n.get(k).asString()).toInstant()
                out[rs.getObject(1, UUID::class.java)] = Window(at("pickup_earliest"), at("pickup_latest"), at("arrival_earliest"), at("arrival_latest"))
            }, ids.joinToString(",", "{", "}"),   // nur UUIDs
        )
        return out
    }

    private fun occupancy(j: JdbcOperations, run: Head, vehicleId: UUID): Pair<DimensionCounts, List<PlanReviewCase>> {
        // Vektor je Buchung: aus der jüngsten Revision des Umlaufs, in der sie einsteigt (Buchungen können nach dem Einsteigen aus dem Plan genommen werden)
        val vectors = HashMap<UUID, ResourceVector>()
        j.query("SELECT boardings::text FROM taktovia.route_stop WHERE run_id = ? ORDER BY run_revision DESC, seq", { rs, _ -> moves(rs.getString(1)) }, run.runId)
            .flatten().forEach { vectors.putIfAbsent(it.bookingId, it.vector) }
        val boarded = LinkedHashSet<UUID>(); val alighted = LinkedHashSet<UUID>()
        j.query(
            "SELECT event_type, booking_id FROM taktovia.driver_event WHERE shift_id = ? AND vehicle_id = ? AND outcome = 'APPLIED' AND event_type IN ('CHECK_IN', 'CHECK_OUT') AND booking_id IS NOT NULL " +
                "ORDER BY received_at, event_id",
            { rs, _ -> if (rs.getString(1) == "CHECK_IN") boarded += rs.getObject(2, UUID::class.java) else alighted += rs.getObject(2, UUID::class.java) }, run.shiftId, vehicleId,
        )
        var seats = 0; var wheelchair = 0
        boarded.forEach { b -> vectors[b]?.let { seats += it.seats; wheelchair += it.wheelchair } }
        alighted.forEach { b -> vectors[b]?.let { seats -= it.seats; wheelchair -= it.wheelchair } }
        val review = j.query(
            "SELECT event_id, event_type, booking_id, reason_code, occurred_at FROM taktovia.driver_event WHERE shift_id = ? AND vehicle_id = ? AND outcome = 'REVIEW_REQUIRED' ORDER BY occurred_at, event_id LIMIT 50",
            { rs, _ -> PlanReviewCase(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getObject(3, UUID::class.java), rs.getString(4), rs.getTimestamp(5).toInstant()) }, run.shiftId, vehicleId,
        )
        return DimensionCounts(maxOf(0, seats), maxOf(0, wheelchair)) to review
    }

    private fun moves(text: String): List<Move> = json.readTree(text).let { arr -> (0 until arr.size()).map { i -> arr.get(i).let { Move(UUID.fromString(it.get("booking_id").asString()), ResourceVector.parse(it.get("vector"))) } } }
}

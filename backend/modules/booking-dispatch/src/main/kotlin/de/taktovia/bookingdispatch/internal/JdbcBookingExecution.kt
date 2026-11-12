package de.taktovia.bookingdispatch.internal

import de.taktovia.bookingdispatch.api.BookingExecution
import de.taktovia.bookingdispatch.api.BookingReason
import de.taktovia.bookingdispatch.api.ExecutionKind
import de.taktovia.bookingdispatch.api.ExecutionRequest
import de.taktovia.bookingdispatch.api.ExecutionResult
import de.taktovia.journeyfederation.api.JourneyShells
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.statemachine.BookingState
import org.springframework.jdbc.core.JdbcOperations
import java.time.Clock
import java.util.UUID

/**
 * Wirkung der Fahrerereignisse auf Buchung und Fahrt (WP-M3-29). Reihenfolge: Umlauf der Schicht (Lesen), Stopp der aktuellen Planrevision, Zuordnung der Buchung zum Stopp, dann die
 * Buchungszeile `FOR UPDATE` (dieselbe Sperrreihenfolge wie der Storno endet bei der Buchung: Umlauf, Hold, Buchung; hier wird nur die Buchung gesperrt, es gibt keinen Zyklus) und erst unter
 * der Sperre der Zustandsvergleich und der Übergang.
 */
internal class JdbcBookingExecution(outbox: Outbox, clock: Clock, private val journeys: JourneyShells) : BookingExecution {
    private val transitions = BookingTransitions(outbox, clock, journeys)

    private class Stop(val runRevision: Long, val boardings: Set<UUID>, val alightings: Set<UUID>)

    override fun apply(j: JdbcOperations, r: ExecutionRequest): ExecutionResult {
        val run = j.query("SELECT id, revision FROM taktovia.vehicle_run WHERE shift_id = ? AND vehicle_id = ?", { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getLong(2) }, r.shiftId, r.vehicleId)
            .firstOrNull() ?: return ExecutionResult.NotFound
        val stop = j.query("SELECT run_revision, boardings::text, alightings::text FROM taktovia.route_stop WHERE id = ? AND run_id = ?",
            { rs, _ -> Stop(rs.getLong(1), ids(rs.getString(2)), ids(rs.getString(3))) }, r.routeStopId, run.first).firstOrNull() ?: return ExecutionResult.NotFound
        if (stop.runRevision != run.second) return ExecutionResult.Review("STALE_PLAN")     // der Stopp gehört zu einer ersetzten Planrevision
        if (r.kind == ExecutionKind.ARRIVAL) return ExecutionResult.Applied(null, null)
        val bookingId = requireNotNull(r.bookingId) { "Buchung fehlt (Prüfung im Aufrufer)" }
        // Buchung muss im aktuellen Plan des Umlaufs stehen (sonst nicht auffindbar), aber am gemeldeten Stopp ein-/aussteigen (sonst falscher Stopp)
        val inPlan = j.queryForObject(
            "SELECT count(*) FROM taktovia.route_stop WHERE run_id = ? AND run_revision = ? AND (boardings @> CAST(? AS jsonb) OR alightings @> CAST(? AS jsonb))",
            Long::class.javaObjectType, run.first, run.second, ref(bookingId), ref(bookingId))!!
        if (inPlan == 0L) return ExecutionResult.NotFound
        // Zeilensperre der Buchung: ab hier sieht diese Transaktion einen parallel committeten Storno
        val b = j.query("SELECT state, journey_id FROM taktovia.booking WHERE id = ? FOR UPDATE", { rs, _ -> rs.getString(1) to rs.getObject(2, UUID::class.java) }, bookingId).firstOrNull()
            ?: return ExecutionResult.NotFound
        val atStop = if (r.kind == ExecutionKind.ALIGHTED) bookingId in stop.alightings else bookingId in stop.boardings
        if (!atStop) return ExecutionResult.Review("OUTSIDE_STOP")
        val leg = journeys.executionState(j, b.second, forUpdate = true) ?: return ExecutionResult.NotFound
        val state = BookingState.valueOf(b.first)
        // Widerspruch zu einem serverseitigen Storno: Klärfall, kein Zustandsrücksprung (das Segment bleibt, wie es ist)
        if (state in CANCELED && leg == "PLANNED") return ExecutionResult.Review("BOOKING_CANCELED")
        if (state != BookingState.CONFIRMED && !(state == BookingState.COMPLETED || state in CANCELED)) return ExecutionResult.Review("INVALID_ORDER")   // noch nicht bestätigt
        val caller = Caller("SYSTEM", "service:driver-events", r.eventId)
        return when (r.kind) {
            ExecutionKind.BOARDED -> {
                if (leg != "PLANNED" || state != BookingState.CONFIRMED) return ExecutionResult.Review("INVALID_ORDER")
                journeys.setExecutionState(j, b.second, "BOARDED"); ExecutionResult.Applied(state.name, "BOARDED")
            }
            ExecutionKind.ALIGHTED -> {
                if (leg != "BOARDED" || state != BookingState.CONFIRMED) return ExecutionResult.Review("INVALID_ORDER")   // Ausstieg ohne vorherigen Einstieg
                journeys.setExecutionState(j, b.second, "ALIGHTED")
                transitions.apply(j, caller, bookingId, BookingState.COMPLETED, BookingReason.TRIP_COMPLETED, null)
                ExecutionResult.Applied(BookingState.COMPLETED.name, "ALIGHTED")
            }
            ExecutionKind.NO_SHOW -> {
                if (leg != "PLANNED" || state != BookingState.CONFIRMED) return ExecutionResult.Review("INVALID_ORDER")
                journeys.setExecutionState(j, b.second, "NO_SHOW")
                transitions.apply(j, caller, bookingId, BookingState.CANCEL_PENDING, BookingReason.NO_SHOW, null)
                transitions.apply(j, caller, bookingId, BookingState.CANCELED, BookingReason.CANCEL_COMPLETED, null)
                ExecutionResult.Applied(BookingState.CANCELED.name, "NO_SHOW")
            }
            ExecutionKind.ARRIVAL -> ExecutionResult.Applied(null, null)
        }
    }

    override fun inRun(j: JdbcOperations, shiftId: UUID, vehicleId: UUID, bookingId: UUID): Boolean {
        val run = j.query("SELECT id, revision FROM taktovia.vehicle_run WHERE shift_id = ? AND vehicle_id = ?", { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getLong(2) }, shiftId, vehicleId).firstOrNull() ?: return false
        return j.queryForObject("SELECT count(*) FROM taktovia.route_stop WHERE run_id = ? AND run_revision = ? AND (boardings @> CAST(? AS jsonb) OR alightings @> CAST(? AS jsonb))",
            Long::class.javaObjectType, run.first, run.second, ref(bookingId), ref(bookingId))!! > 0
    }

    override fun serviceOf(j: JdbcOperations, bookingId: UUID): UUID? =
        j.query("SELECT service_id FROM taktovia.booking WHERE id = ?", { rs, _ -> rs.getObject(1, UUID::class.java) }, bookingId).firstOrNull()

    private fun ref(bookingId: UUID) = """[{"booking_id": "$bookingId"}]"""

    private fun ids(json: String): Set<UUID> = Regex("\"booking_id\"\\s*:\\s*\"([0-9a-fA-F-]{36})\"").findAll(json).map { UUID.fromString(it.groupValues[1]) }.toSet()

    private companion object { val CANCELED = setOf(BookingState.CANCEL_PENDING, BookingState.CANCELED, BookingState.COMPENSATING, BookingState.FAILED) }
}

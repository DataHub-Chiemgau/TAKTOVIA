package de.taktovia.bookingdispatch.internal

import de.taktovia.bookingdispatch.api.BookingPlanChange
import de.taktovia.bookingdispatch.api.PlanBookingChangeKind
import de.taktovia.bookingdispatch.api.PlanStop
import java.time.Instant
import java.util.UUID

/**
 * Vergleich zweier Planrevisionen je Buchung (WP-M2-28): Abholzeit = geplante Zeit am Einstiegsstopp, Ausstiegszeit = am Ausstiegsstopp. Rein, ohne Datenbank.
 * Unveränderte Buchungen erzeugen keine Änderung, außer der Aufrufer benennt sie ausdrücklich ([touched], z. B. Auslöser der Planänderung): dann `TOUCHED`.
 */
internal object PlanDiff {
    private class Position(val pickupAt: Instant, val pickupStop: UUID, val dropoffAt: Instant?, val dropoffStop: UUID?)

    private fun positions(stops: List<PlanStop>): Map<UUID, Position> {
        val ordered = stops.sortedBy { it.seq }
        val out = LinkedHashMap<UUID, Position>()
        ordered.forEach { s ->
            s.boardings.forEach { m ->
                val alight = ordered.firstOrNull { o -> o.alightings.any { it.bookingId == m.bookingId } }
                out[m.bookingId] = Position(s.plannedAt, s.stopRevisionId, alight?.plannedAt, alight?.stopRevisionId)
            }
        }
        return out
    }

    fun changes(before: List<PlanStop>, after: List<PlanStop>, touched: Set<UUID>): List<BookingPlanChange> {
        val b = positions(before); val a = positions(after)
        val ids = (touched.filter { it in b || it in a } + b.keys + a.keys).distinct()
        return ids.mapNotNull { id ->
            val x = b[id]; val y = a[id]
            val kind = when {
                x == null && y != null -> PlanBookingChangeKind.ADDED
                x != null && y == null -> PlanBookingChangeKind.REMOVED
                x != null && y != null && (x.pickupAt != y.pickupAt || x.dropoffAt != y.dropoffAt) -> PlanBookingChangeKind.TIME_CHANGED
                x != null && y != null && (x.pickupStop != y.pickupStop || x.dropoffStop != y.dropoffStop) -> PlanBookingChangeKind.STOP_CHANGED
                id in touched -> PlanBookingChangeKind.TOUCHED
                else -> null
            }
            kind?.let { BookingPlanChange(id, it, 0, x?.pickupAt, y?.pickupAt, x?.dropoffAt, y?.dropoffAt) }
        }
    }
}

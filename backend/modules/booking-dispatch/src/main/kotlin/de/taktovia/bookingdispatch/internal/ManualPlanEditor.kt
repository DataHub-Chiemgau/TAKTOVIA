package de.taktovia.bookingdispatch.internal

import de.taktovia.bookingdispatch.api.PlanStop
import de.taktovia.bookingdispatch.api.StopChange
import de.taktovia.bookingdispatch.api.StopChangeKind
import de.taktovia.bookingdispatch.api.StopDiff
import de.taktovia.bookingdispatch.api.StopDiffKind
import de.taktovia.bookingdispatch.api.StopMovement
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import java.time.Instant
import java.util.UUID

/** Stopp mit Datenbankkennung (für den Bezug der Änderungen); [routeStopId] ist `null` bei einem neu eingefügten Stopp. */
internal class EditStop(val routeStopId: UUID?, val stop: PlanStop)

/** Ergebnis der Änderungen: Zielplan, geänderte Quellpläne, übernommene Buchungen mit ihrem Quellumlauf. */
internal class EditOutcome(val target: List<PlanStop>, val sources: Map<UUID, List<PlanStop>>, val movedFrom: Map<UUID, UUID>)

/**
 * Reine Anwendung der Stoppänderungen einer manuellen Umplanung auf Pläne (WP-M3-40), ohne Datenbank. Die Änderungen gelten **der Reihe nach** (`new_sequence` ist die Position in der Liste zum Zeitpunkt der
 * Änderung). Danach behalten die Stopps das **Zeitraster** ihres Plans: die Planzeiten aller Stopps werden aufsteigend sortiert und der Reihe nach vergeben (die Reihenfolge ändert, wann gehalten wird, nicht
 * die Zeitpunkte). Abschnittsdaten (Fahrzeit, Strecke) bleiben nur erhalten, wenn der Folgestopp derselbe geblieben ist; sonst fehlen sie (dann gibt es dafür keine Prognose, nie einen geschätzten Wert).
 */
internal object ManualPlanEditor {
    private class Work(val routeStopId: UUID?, val stop: PlanStop, val boardings: MutableList<StopMovement>, val alightings: MutableList<StopMovement>, var plannedAt: Instant, val originalNext: UUID?) {
        val empty: Boolean get() = boardings.isEmpty() && alightings.isEmpty()
        fun has(booking: UUID) = boardings.any { it.bookingId == booking } || alightings.any { it.bookingId == booking }
    }

    private fun work(list: List<EditStop>): MutableList<Work> {
        val ordered = list.sortedBy { it.stop.seq }
        return ordered.mapIndexed { i, e -> Work(e.routeStopId, e.stop, e.stop.boardings.toMutableList(), e.stop.alightings.toMutableList(), e.stop.plannedAt, ordered.getOrNull(i + 1)?.routeStopId) }.toMutableList()
    }

    private fun invalid(i: Int, field: String, code: ViolationCode = ViolationCode.INVALID_VALUE): Nothing = throw ProblemException.invalid(Violation("/changes/$i/$field", code))

    fun apply(target: List<EditStop>, sources: Map<UUID, List<EditStop>>, changes: List<StopChange>): EditOutcome {
        val work = work(target)
        val src = sources.mapValues { work(it.value) }
        val movedFrom = LinkedHashMap<UUID, UUID>()
        val touchedSources = LinkedHashSet<UUID>()
        changes.forEachIndexed { i, c ->
            when (c.kind) {
                StopChangeKind.MOVE -> {
                    val idx = work.indexOfFirst { it.routeStopId == c.routeStopId }
                    if (idx < 0) invalid(i, "route_stop_id")
                    val to = c.newSequence ?: invalid(i, "new_sequence", ViolationCode.REQUIRED)
                    if (to !in 1..work.size) invalid(i, "new_sequence", if (to < 1) ViolationCode.BELOW_MINIMUM else ViolationCode.ABOVE_MAXIMUM)
                    val w = work.removeAt(idx); work.add(to - 1, w)
                }
                StopChangeKind.REMOVE -> {
                    val booking = c.bookingId ?: invalid(i, "booking_id", ViolationCode.REQUIRED)
                    val at = work.firstOrNull { it.routeStopId == c.routeStopId } ?: invalid(i, "route_stop_id")
                    if (!at.has(booking)) invalid(i, "booking_id")
                    work.forEach { w -> w.boardings.removeAll { it.bookingId == booking }; w.alightings.removeAll { it.bookingId == booking } }
                    work.removeAll { it.empty }
                }
                StopChangeKind.INSERT -> {
                    val booking = c.bookingId ?: invalid(i, "booking_id", ViolationCode.REQUIRED)
                    val to = c.newSequence ?: invalid(i, "new_sequence", ViolationCode.REQUIRED)
                    if (to !in 1..work.size + 1) invalid(i, "new_sequence", if (to < 1) ViolationCode.BELOW_MINIMUM else ViolationCode.ABOVE_MAXIMUM)
                    val owner = src.entries.firstOrNull { (_, list) -> list.any { it.routeStopId == c.routeStopId } } ?: invalid(i, "route_stop_id")
                    val from = owner.value.first { it.routeStopId == c.routeStopId }
                    if (!from.has(booking)) invalid(i, "booking_id")
                    val board = from.boardings.firstOrNull { it.bookingId == booking }; val alight = from.alightings.firstOrNull { it.bookingId == booking }
                    from.boardings.removeAll { it.bookingId == booking }; from.alightings.removeAll { it.bookingId == booking }
                    owner.value.removeAll { it.empty }
                    touchedSources += owner.key; movedFrom[booking] = owner.key
                    work.add(to - 1, Work(null, from.stop, mutableListOf<StopMovement>().also { l -> board?.let { l += it } }, mutableListOf<StopMovement>().also { l -> alight?.let { l += it } }, from.plannedAt, null))
                }
            }
        }
        return EditOutcome(finish(work, regrid = true), touchedSources.associateWith { finish(src.getValue(it), regrid = false) }, movedFrom)
    }

    private fun finish(list: List<Work>, regrid: Boolean): List<PlanStop> {
        val times = if (regrid) list.map { it.plannedAt }.sorted() else list.map { it.plannedAt }
        return list.mapIndexed { i, w ->
            val keep = w.routeStopId != null && w.originalNext != null && list.getOrNull(i + 1)?.routeStopId == w.originalNext
            PlanStop(i, w.stop.stopRevisionId, times[i], w.boardings.toList(), w.alightings.toList(), if (keep) w.stop.legDurationSec else null, if (keep) w.stop.legDistanceM else null)
        }
    }

    /**
     * Planvergleich alt gegen neu: Stopps werden über (Haltepunktrevision, Ein- und Ausstiege) einander zugeordnet. `ADDED`/`REMOVED` ohne Gegenstück, `MOVED` bei geänderter Position,
     * `TIME_CHANGED` bei gleicher Position und geänderter Planzeit. [idOfNew] liefert die Stopp-ID der neuen Stopps (nach Position), [idOfOld] die der alten.
     */
    fun diff(old: List<PlanStop>, new: List<PlanStop>, idOfOld: (Int) -> UUID, idOfNew: (Int) -> UUID): List<StopDiff> {
        fun key(s: PlanStop) = Triple(s.stopRevisionId, s.boardings.map { it.bookingId }.sorted(), s.alightings.map { it.bookingId }.sorted())
        val oldOrdered = old.sortedBy { it.seq }; val newOrdered = new.sortedBy { it.seq }
        val taken = BooleanArray(oldOrdered.size)
        val out = ArrayList<StopDiff>()
        newOrdered.forEachIndexed { ni, n ->
            val oi = oldOrdered.indices.firstOrNull { !taken[it] && key(oldOrdered[it]) == key(n) }
            if (oi == null) { out += StopDiff(idOfNew(ni), StopDiffKind.ADDED, null, ni + 1); return@forEachIndexed }
            taken[oi] = true
            when {
                oi != ni -> out += StopDiff(idOfNew(ni), StopDiffKind.MOVED, oi + 1, ni + 1)
                oldOrdered[oi].plannedAt != n.plannedAt -> out += StopDiff(idOfNew(ni), StopDiffKind.TIME_CHANGED, oi + 1, ni + 1)
            }
        }
        oldOrdered.indices.filter { !taken[it] }.forEach { oi -> out += StopDiff(idOfOld(oi), StopDiffKind.REMOVED, oi + 1, null) }
        return out
    }
}

package de.taktovia.bookingdispatch.api

import de.taktovia.platform.context.RequestContext
import java.time.Instant
import java.util.UUID

/**
 * Zustände des Umlaufs (`vehicle_run.state`; Vorschlag dieses Plans, OP-4/OP-48, Datei `vertraege/zustaende/fahrtenplan.md`). Die Übergangstabelle ist dieselbe wie in
 * `state_transition_rule` (Maschine `vehicle_run`, Migration V20160); ein Test vergleicht beide Seiten.
 */
enum class RunState {
    PLANNED, ACTIVE, LOCKED, IN_PROGRESS, COMPLETED, CANCELED;

    fun canGoTo(to: RunState) = to in TRANSITIONS.getValue(this)

    companion object {
        val TRANSITIONS: Map<RunState, Set<RunState>> = mapOf(
            PLANNED to setOf(ACTIVE, CANCELED), ACTIVE to setOf(LOCKED, IN_PROGRESS, CANCELED), LOCKED to setOf(ACTIVE, IN_PROGRESS, CANCELED),
            IN_PROGRESS to setOf(COMPLETED), COMPLETED to emptySet(), CANCELED to emptySet(),
        )
        /** Neue Pläne und Holds sind nur in diesen Zuständen zulässig (Holds nur in `ACTIVE`, Planänderung auch in `PLANNED`). */
        val PLAN_CHANGEABLE: Set<RunState> = setOf(PLANNED, ACTIVE)
    }
}

/** Ein Einstieg oder Ausstieg einer Buchung an einem Stopp, mit dem Ressourcenvektor, den sie belegt (Schema `resource-vector.v1`). */
data class StopMovement(val bookingId: UUID, val vector: ResourceVector)

/**
 * Ein Stopp der Planrevision. [seq] ist die 0-basierte Position; der Abschnitt `i` liegt zwischen Stopp `i` und `i+1` (Abbildung auf `capacity_hold.interval_from/interval_to`,
 * halboffen). [legDurationSec]/[legDistanceM] gelten für den Abschnitt zum Folgestopp und fehlen beim letzten Stopp.
 */
data class PlanStop(
    val seq: Int, val stopRevisionId: UUID, val plannedAt: Instant,
    val boardings: List<StopMovement> = emptyList(), val alightings: List<StopMovement> = emptyList(),
    val legDurationSec: Int? = null, val legDistanceM: Int? = null,
)

/**
 * Fahrtenplan einer Revision (Aggregat RunPlanRevision): Stoppliste nach [PlanStop.seq]. Der Abschnittsindex gilt nur innerhalb einer Revision; ein Hold trägt seine `run_revision`,
 * eine neue Revision macht ältere Holds über den Revisionsvergleich ungültig (Annahme laut Spike (a), siehe `m2-detailvertraege.md` §9).
 */
data class RunPlan(val runId: UUID, val revision: Long, val stops: List<PlanStop>) {
    val segmentCount: Int get() = maxOf(0, stops.size - 1)

    /** Halboffenes Abschnittsintervall `[von, bis)` der Mitfahrt einer Buchung (Einstiegsstopp bis Ausstiegsstopp), `null` wenn die Buchung nicht im Plan steht. */
    fun intervalOf(bookingId: UUID): Pair<Int, Int>? {
        val from = stops.indexOfFirst { s -> s.boardings.any { it.bookingId == bookingId } }
        val to = stops.indexOfFirst { s -> s.alightings.any { it.bookingId == bookingId } }
        return if (from >= 0 && to > from) from to to else null
    }
}

enum class PlanViolationCode {
    /** Stoppreihenfolge nicht eindeutig: dieselbe `seq` mehrfach. */
    DUPLICATE_SEQUENCE,
    /** `seq` nicht lückenlos 0..n-1 (der Abschnittsindex wäre nicht stabil). */
    SEQUENCE_GAP,
    /** Ausstieg liegt vor dem oder am selben Stopp wie der Einstieg derselben Buchung. */
    ALIGHT_BEFORE_BOARD,
    ALIGHT_WITHOUT_BOARD,
    BOARD_WITHOUT_ALIGHT,
    DUPLICATE_MOVEMENT,
    /** Einstieg und Ausstieg derselben Buchung tragen verschiedene Ressourcenvektoren. */
    VECTOR_MISMATCH,
    NEGATIVE_OCCUPANCY,
    OVER_CAPACITY,
    /** Eine mitfahrende Buchung braucht ein Ausstattungsmerkmal, das das Fahrzeug nicht hat. */
    EQUIPMENT_MISSING,
}

data class PlanViolation(val code: PlanViolationCode, val seq: Int? = null, val bookingId: UUID? = null, val dimension: Dimension? = null)

/**
 * Constraint Validator der Routeninvarianten (LH-6.5-OPT-004), rein und ohne Datenbank. Gemeinsam benutzt von der Solver-Ergebnisprüfung (jede Planrevision läuft durch
 * `RunPlans.appendRevision`), vom Hold und von der Bestätigung (Kapazitätsprüfung je Abschnitt über [checkSegmentOccupancy]).
 *
 * Invarianten: je Buchung Einstieg vor Ausstieg; Belegung je Abschnitt in jeder Dimension (Sitz, Rollstuhlplatz, Ausstattung) zwischen 0 und Fahrzeugkapazität; Stoppreihenfolge
 * eindeutig und lückenlos. An einem Stopp steigen erst die Aussteiger aus, dann die Einsteiger ein.
 */
object RunPlanValidator {

    fun validate(capacity: ResourceVector, stops: List<PlanStop>): List<PlanViolation> {
        val v = mutableListOf<PlanViolation>()
        // Stoppreihenfolge
        val dup = stops.groupBy { it.seq }.filterValues { it.size > 1 }.keys.sorted()
        dup.forEach { v += PlanViolation(PlanViolationCode.DUPLICATE_SEQUENCE, seq = it) }
        val ordered = stops.sortedBy { it.seq }
        if (dup.isEmpty() && ordered.withIndex().any { (i, s) -> s.seq != i }) v += PlanViolation(PlanViolationCode.SEQUENCE_GAP)
        if (dup.isNotEmpty() || v.isNotEmpty()) return v   // ohne eindeutige Reihenfolge ist keine weitere Aussage über die Strecke möglich

        // Einstieg vor Ausstieg je Buchung
        val boardAt = mutableMapOf<UUID, Int>(); val alightAt = mutableMapOf<UUID, Int>()
        val boardVec = mutableMapOf<UUID, ResourceVector>(); val alightVec = mutableMapOf<UUID, ResourceVector>()
        ordered.forEachIndexed { i, s ->
            s.boardings.forEach { m -> if (boardAt.putIfAbsent(m.bookingId, i) != null) v += PlanViolation(PlanViolationCode.DUPLICATE_MOVEMENT, i, m.bookingId) else boardVec[m.bookingId] = m.vector }
            s.alightings.forEach { m -> if (alightAt.putIfAbsent(m.bookingId, i) != null) v += PlanViolation(PlanViolationCode.DUPLICATE_MOVEMENT, i, m.bookingId) else alightVec[m.bookingId] = m.vector }
        }
        for ((id, a) in alightAt) {
            val b = boardAt[id]
            when {
                b == null -> v += PlanViolation(PlanViolationCode.ALIGHT_WITHOUT_BOARD, a, id)
                a <= b -> v += PlanViolation(PlanViolationCode.ALIGHT_BEFORE_BOARD, a, id)
                boardVec[id] != alightVec[id] -> v += PlanViolation(PlanViolationCode.VECTOR_MISMATCH, a, id)
            }
        }
        for ((id, b) in boardAt) if (id !in alightAt) v += PlanViolation(PlanViolationCode.BOARD_WITHOUT_ALIGHT, b, id)
        boardVec.forEach { (id, vec) ->
            if (!capacity.equipment.containsAll(vec.equipment)) v += PlanViolation(PlanViolationCode.EQUIPMENT_MISSING, boardAt[id], id, Dimension.EQUIPMENT)
        }

        // Belegung: erst aussteigen, dann einsteigen; Zwischenstand nach dem Aussteigen darf nie negativ sein, nach dem Einsteigen nie über der Kapazität
        var seats = 0L; var wheelchair = 0L
        ordered.forEachIndexed { i, s ->
            s.alightings.forEach { seats -= it.vector.seats; wheelchair -= it.vector.wheelchair }
            if (seats < 0) v += PlanViolation(PlanViolationCode.NEGATIVE_OCCUPANCY, i, dimension = Dimension.SEATS)
            if (wheelchair < 0) v += PlanViolation(PlanViolationCode.NEGATIVE_OCCUPANCY, i, dimension = Dimension.WHEELCHAIR)
            s.boardings.forEach { seats += it.vector.seats; wheelchair += it.vector.wheelchair }
            if (seats > capacity.seats) v += PlanViolation(PlanViolationCode.OVER_CAPACITY, i, dimension = Dimension.SEATS)
            if (wheelchair > capacity.wheelchair) v += PlanViolation(PlanViolationCode.OVER_CAPACITY, i, dimension = Dimension.WHEELCHAIR)
        }
        return v
    }

    /** Belegung je Abschnitt (Index `i` = Abschnitt zwischen Stopp `i` und `i+1`) aus den Bewegungen; für Tests und Anzeige, ohne Prüfung. */
    fun occupancy(stops: List<PlanStop>): List<Pair<Int, Int>> {
        var seats = 0; var wheelchair = 0
        return stops.sortedBy { it.seq }.dropLast(1).map { s ->
            s.alightings.forEach { seats -= it.vector.seats; wheelchair -= it.vector.wheelchair }
            s.boardings.forEach { seats += it.vector.seats; wheelchair += it.vector.wheelchair }
            seats to wheelchair
        }
    }

    /**
     * Kapazitätsprüfung je Abschnitt für Hold und Confirm: [segments] ist die Belegung je Abschnitt in Zählern der Dimensionen Sitz und Rollstuhlplatz (einschließlich einer
     * geschützten Reserve und des neuen Bedarfs); Werte über der Kapazität oder unter null sind Verletzungen. [firstSegment] ist der Abschnittsindex des ersten Eintrags.
     */
    fun checkSegmentOccupancy(capacity: ResourceVector, segments: List<Pair<Int, Int>>, firstSegment: Int = 0): List<PlanViolation> = buildList {
        segments.forEachIndexed { k, (s, w) ->
            val seg = firstSegment + k
            if (s < 0) add(PlanViolation(PlanViolationCode.NEGATIVE_OCCUPANCY, seg, dimension = Dimension.SEATS))
            if (w < 0) add(PlanViolation(PlanViolationCode.NEGATIVE_OCCUPANCY, seg, dimension = Dimension.WHEELCHAIR))
            if (s > capacity.seats) add(PlanViolation(PlanViolationCode.OVER_CAPACITY, seg, dimension = Dimension.SEATS))
            if (w > capacity.wheelchair) add(PlanViolation(PlanViolationCode.OVER_CAPACITY, seg, dimension = Dimension.WHEELCHAIR))
        }
    }
}

/** Ein vom Validator abgewiesener Plan (Solver-Ergebnis oder Planänderung); nichts wurde gespeichert, kein Ereignis geschrieben. */
class RunPlanRejectedException(val violations: List<PlanViolation>) : RuntimeException("Planrevision verletzt Routeninvarianten: " + violations.joinToString { "${it.code}@${it.seq}" })

enum class RevisionCause { BOOKING_INSERTED, BOOKING_REMOVED, MANUAL_CHANGE, REPLAN }

data class RunView(val id: UUID, val vehicleId: UUID, val shiftId: UUID, val revision: Long, val state: RunState)

/**
 * Fahrtenplan-Port (Aggregat RunPlanRevision, WP-M2-16); zugleich die lesende Schnittstelle für M3. Jede Planänderung läuft in einer Transaktion unter dem Zeilenlock des
 * Umlaufs (`vehicle_run`, wie Hold und Confirm): Revision vergleichen (CAS), Validator, Revision +1, Stopps der neuen Revision anhängen, Outbox-Ereignis `dispatch.run_revised`.
 * Frühere Revisionen bleiben unverändert lesbar.
 */
interface RunPlans {
    /**
     * Legt den Umlauf der Schicht an (Zustand `PLANNED`, Revision 1, ohne Stopps) oder liefert den vorhandenen; auch bei parallelen Aufrufen genau ein Umlauf je Schicht.
     * Zeitpunkt und Auslöser (OP-48, Annahme: Schichtfreigabe durch M3/M4, bis dahin Fixture) bestimmt der Aufrufer. Unbekannte oder fremde Schicht: 404.
     */
    fun ensureRun(ctx: RequestContext, shiftId: UUID): RunView

    fun run(ctx: RequestContext, runId: UUID): RunView

    /** Zustandswechsel des Umlaufs nach der Übergangstabelle ([RunState.TRANSITIONS]); die Planrevision bleibt unverändert (409 `INVALID_STATE_TRANSITION` bei unzulässigem Übergang). */
    fun transition(ctx: RequestContext, runId: UUID, to: RunState): RunView

    /** Plan der aktuellen Revision. */
    fun currentPlan(ctx: RequestContext, runId: UUID): RunPlan

    /** Plan einer früheren oder der aktuellen Revision; Revision 1 ist leer, eine Revision über der aktuellen ist 404. */
    fun plan(ctx: RequestContext, runId: UUID, revision: Long): RunPlan

    /**
     * Speichert [stops] als neue Revision `expectedRevision + 1`. Abweichende Revision: 412 `REVISION_MISMATCH`; Plan nicht änderbar (Zustand): 409; Verletzung einer Invariante:
     * [RunPlanRejectedException], nichts gespeichert. Gilt gleichermaßen für Ergebnisse des Solvers (Einfügeheuristik, WP-M2-17) und manuelle Änderungen.
     */
    fun appendRevision(ctx: RequestContext, runId: UUID, expectedRevision: Long, stops: List<PlanStop>, cause: RevisionCause, affectedBookingIds: List<UUID> = emptyList()): RunPlan
}

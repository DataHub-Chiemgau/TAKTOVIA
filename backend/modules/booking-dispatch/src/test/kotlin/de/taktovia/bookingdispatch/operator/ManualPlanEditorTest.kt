package de.taktovia.bookingdispatch.operator

import de.taktovia.bookingdispatch.api.PlanStop
import de.taktovia.bookingdispatch.api.ResourceVector
import de.taktovia.bookingdispatch.api.StopChange
import de.taktovia.bookingdispatch.api.StopChangeKind
import de.taktovia.bookingdispatch.api.StopDiffKind
import de.taktovia.bookingdispatch.api.StopMovement
import de.taktovia.bookingdispatch.internal.EditStop
import de.taktovia.bookingdispatch.internal.ManualPlanEditor
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID

/** WP-M3-40: reine Anwendung der Stoppänderungen (ohne Datenbank): Reihenfolge, Zeitraster, Abschnittsdaten, Umbuchung, Planvergleich und fehlerhafte Bezüge. */
class ManualPlanEditorTest {
    private val t0 = Instant.parse("2026-11-02T09:00:00Z")
    private val rev = UUID.randomUUID()
    private val one = ResourceVector(1, 0)
    private val a = UUID.randomUUID(); private val b = UUID.randomUUID()
    private fun stop(seq: Int, min: Long, board: UUID? = null, alight: UUID? = null, leg: Boolean = true) =
        PlanStop(seq, rev, t0.plusSeconds(60 * min), listOfNotNull(board?.let { StopMovement(it, one) }), listOfNotNull(alight?.let { StopMovement(it, one) }), if (leg) 60 else null, if (leg) 500 else null)
    private val ids = List(8) { UUID.randomUUID() }
    private val plan = listOf(stop(0, 0, board = a), stop(1, 5, board = b), stop(2, 15, alight = a), stop(3, 20, alight = b, leg = false)).mapIndexed { i, s -> EditStop(ids[i], s) }

    @Test
    fun verschieben_behaelt_das_zeitraster_und_entfernt_abschnittsdaten_nur_wo_der_folgestopp_wechselt() {
        val out = ManualPlanEditor.apply(plan, emptyMap(), listOf(StopChange(StopChangeKind.MOVE, ids[1], 1)))
        assertEquals(listOf(b, a), out.target.take(2).map { s -> (s.boardings).single().bookingId })
        assertEquals(plan.map { it.stop.plannedAt }, out.target.map { it.plannedAt }, "Zeitraster bleibt")
        assertEquals(listOf(0, 1, 2, 3), out.target.map { it.seq })
        // der verschobene Stopp hat einen neuen Folgestopp: Abschnittsdaten entfallen; der Stopp davor (neuer Vorgänger) ebenso
        assertNull(out.target[0].legDurationSec); assertEquals(60, out.target[2].legDurationSec)
    }

    @Test
    fun entfernen_einer_buchung_laesst_leere_stopps_entfallen() {
        val out = ManualPlanEditor.apply(plan, emptyMap(), listOf(StopChange(StopChangeKind.REMOVE, ids[0], bookingId = a)))
        assertEquals(2, out.target.size); assertTrue(out.target.none { s -> (s.boardings + s.alightings).any { it.bookingId == a } })
        assertEquals(listOf(0, 1), out.target.map { it.seq })
    }

    @Test
    fun einfuegen_uebernimmt_die_bewegung_aus_dem_quellumlauf_und_nimmt_sie_dort_heraus() {
        val src = UUID.randomUUID(); val c = UUID.randomUUID()
        val source = listOf(EditStop(ids[4], stop(0, 2, board = c)), EditStop(ids[5], stop(1, 12, alight = c, leg = false)))
        val out = ManualPlanEditor.apply(plan, mapOf(src to source), listOf(StopChange(StopChangeKind.INSERT, ids[4], 2, c), StopChange(StopChangeKind.INSERT, ids[5], 4, c)))
        assertEquals(6, out.target.size); assertEquals(mapOf(c to src), out.movedFrom)
        assertTrue(out.sources.getValue(src).isEmpty(), "der Quellumlauf verliert beide Stopps")
        assertEquals(out.target.map { it.plannedAt }.sorted(), out.target.map { it.plannedAt }, "Zeiten aufsteigend")
        assertEquals(c, out.target[1].boardings.single().bookingId); assertEquals(c, out.target[3].alightings.single().bookingId)
    }

    @Test
    fun planvergleich_unterscheidet_neu_entfernt_verschoben_und_zeitaenderung() {
        val out = ManualPlanEditor.apply(plan, emptyMap(), listOf(StopChange(StopChangeKind.MOVE, ids[1], 1), StopChange(StopChangeKind.REMOVE, ids[3], bookingId = b)))
        val diff = ManualPlanEditor.diff(plan.map { it.stop }, out.target, { ids[it] }, { UUID(0, it.toLong()) })
        val kinds = diff.groupBy { it.kind }
        assertEquals(2, kinds.getValue(StopDiffKind.REMOVED).size)   // Einstieg und Ausstieg von b
        assertTrue(StopDiffKind.ADDED !in kinds)
        assertTrue(kinds.getValue(StopDiffKind.MOVED).isNotEmpty() || kinds.containsKey(StopDiffKind.TIME_CHANGED))
        assertTrue(ManualPlanEditor.diff(plan.map { it.stop }, plan.map { it.stop }, { ids[it] }, { ids[it] }).isEmpty(), "unverändert = kein Unterschied")
    }

    @Test
    fun fehlerhafte_bezuege_ergeben_422_mit_zeiger() {
        fun code(c: StopChange) = assertThrows<ProblemException> { ManualPlanEditor.apply(plan, emptyMap(), listOf(c)) }.also { assertEquals(ErrorCode.VALIDATION_FAILED, it.code) }.violations.single().pointer
        assertEquals("/changes/0/route_stop_id", code(StopChange(StopChangeKind.MOVE, UUID.randomUUID(), 1)))
        assertEquals("/changes/0/new_sequence", code(StopChange(StopChangeKind.MOVE, ids[0], 9)))
        assertEquals("/changes/0/new_sequence", code(StopChange(StopChangeKind.MOVE, ids[0], 0)))
        assertEquals("/changes/0/booking_id", code(StopChange(StopChangeKind.REMOVE, ids[0], bookingId = b)), "die Buchung steht nicht an diesem Stopp")
        assertEquals("/changes/0/route_stop_id", code(StopChange(StopChangeKind.INSERT, ids[0], 1, a)), "Einfügen nur aus einem anderen Umlauf")
    }
}

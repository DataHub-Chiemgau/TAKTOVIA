package de.taktovia.bookingdispatch

import de.taktovia.bookingdispatch.api.BookingReason
import de.taktovia.bookingdispatch.api.Dimension
import de.taktovia.bookingdispatch.api.HaversineTravelTime
import de.taktovia.bookingdispatch.api.PlanStop
import de.taktovia.bookingdispatch.api.PlanViolationCode
import de.taktovia.bookingdispatch.api.ReserveRule
import de.taktovia.bookingdispatch.api.ResourceVector
import de.taktovia.bookingdispatch.api.RideTimeWindow
import de.taktovia.bookingdispatch.api.RunPlan
import de.taktovia.bookingdispatch.api.RunPlanValidator
import de.taktovia.bookingdispatch.api.RunState
import de.taktovia.bookingdispatch.api.StopMovement
import de.taktovia.servicegeo.api.GeoPoint
import de.taktovia.platform.statemachine.BookingState
import de.taktovia.platform.statemachine.StateMachines
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID

/**
 * WP-M5-22 / LH-6.20-QAT-002: Unit-Stufe des Release-Gates für Kernbuchung und Disposition. Reine Prüfungen ohne Datenbank, ohne HTTP und ohne Uhr: Sie üben die
 * Entscheidungsregeln der Fachfunktionen (Angebot, Hold, Confirm, Ablauf, Storno, Planrevision, Constraint-Validator) isoliert aus; das Zusammenspiel mit der
 * Datenbank prüfen die Integrationstests, die Außenverträge die Contract-Fälle (Zuordnung: `tests/acceptance/release/funktionsabdeckung.csv`).
 */
class KernbuchungUnitTest {
    private val t0 = Instant.parse("2026-11-02T07:00:00Z")
    private fun stop(seq: Int, board: List<StopMovement> = emptyList(), alight: List<StopMovement> = emptyList()) =
        PlanStop(seq, UUID.randomUUID(), t0.plusSeconds(seq * 300L), board, alight)

    @Test
    fun angebot_zeitfenster_verlangt_geordnete_raender_und_fahrzeit_hat_untergrenze() {
        assertThrows<IllegalArgumentException> { RideTimeWindow(t0.plusSeconds(60), t0) }
        RideTimeWindow(t0, t0)   // gleicher Rand ist zulässig
        val near = HaversineTravelTime().seconds(GeoPoint(9.0, 48.0), GeoPoint(9.0, 48.0))
        assertEquals(120, near, "Mindestfahrzeit gilt auch bei Entfernung null")
        val far = HaversineTravelTime().seconds(GeoPoint(9.0, 48.0), GeoPoint(9.1, 48.1))
        assertTrue(far > near)
    }

    @Test
    fun hold_deckt_nur_wenn_jede_dimension_reicht_und_reserve_zaehlt_mit() {
        val cap = ResourceVector(seats = 4, wheelchair = 1)
        assertTrue(cap.covers(ResourceVector(seats = 3, wheelchair = 1)))
        assertEquals(setOf(Dimension.SEATS), cap.missing(ResourceVector(seats = 5)))
        assertEquals(setOf(Dimension.WHEELCHAIR), cap.missing(ResourceVector(wheelchair = 2)))
        // letzter Platz: nach Belegung von 4 Sitzen bleibt nichts, mit Reserve nie negativ
        assertEquals(0, cap.freeAfter(ResourceVector(seats = 4)).seats)
        assertEquals(0, cap.freeAfter(ResourceVector(seats = 3), ReserveRule(seats = 2)).seats)
        assertEquals(1, cap.freeAfter(ResourceVector(seats = 3)).seats)
        assertThrows<IllegalArgumentException> { ResourceVector(seats = -1) }
    }

    @Test
    fun confirm_geht_nur_ueber_confirming_und_nie_direkt_von_held_nach_confirmed() {
        val m = StateMachines.BOOKING
        assertTrue(m.allowed(BookingState.HELD, BookingState.CONFIRMING))
        assertTrue(m.allowed(BookingState.CONFIRMING, BookingState.CONFIRMED))
        assertFalse(m.allowed(BookingState.HELD, BookingState.CONFIRMED))
        assertFalse(m.allowed(BookingState.FAILED, BookingState.CONFIRMED))
        // ungewisse Zahlung bleibt unbestimmt: Abgleich führt weiter, springt aber nie zurück in die Bestätigung
        assertTrue(m.allowed(BookingState.CONFIRMING, BookingState.RECONCILING))
        assertFalse(m.allowed(BookingState.RECONCILING, BookingState.CONFIRMING))
        assertEquals(setOf(BookingState.CONFIRMING), BookingReason.HOLD_CONFIRMED.targets)
    }

    @Test
    fun ablauf_fuehrt_nie_zu_bestaetigt() {
        assertTrue(BookingReason.HOLD_EXPIRED.targets.none { it == BookingState.CONFIRMED || it == BookingState.CONFIRMING })
        assertEquals(setOf(BookingState.COMPENSATING, BookingState.FAILED), BookingReason.HOLD_EXPIRED.targets)
        // Ablauf eines nicht gehaltenen Umlaufs: Pläne und Holds nur in planbaren Zuständen
        assertFalse(RunState.COMPLETED in RunState.PLAN_CHANGEABLE)
        assertFalse(RunState.CANCELED in RunState.PLAN_CHANGEABLE)
    }

    @Test
    fun storno_ist_ein_zweistufiger_weg_und_endzustaende_sind_endgueltig() {
        val m = StateMachines.BOOKING
        assertTrue(m.allowed(BookingState.CONFIRMED, BookingState.CANCEL_PENDING))
        assertTrue(m.allowed(BookingState.CANCEL_PENDING, BookingState.CANCELED))
        assertFalse(m.allowed(BookingState.CONFIRMED, BookingState.CANCELED), "kein Storno ohne Zwischenzustand")
        assertTrue(m.transitions.getValue(BookingState.CANCELED).isEmpty())
        assertTrue(m.allowed(BookingState.CANCEL_PENDING, BookingState.RECONCILING), "unbekanntes Stornoergebnis bleibt unbestimmt")
        assertTrue(BookingState.CANCEL_PENDING in BookingReason.PASSENGER_REQUEST.targets)
        assertEquals(setOf(BookingState.CANCELED), BookingReason.CANCEL_COMPLETED.targets)
    }

    @Test
    fun planrevision_intervall_einer_buchung_und_umlaufzustaende() {
        val b = UUID.randomUUID(); val v = ResourceVector(seats = 1)
        val plan = RunPlan(UUID.randomUUID(), 3, listOf(stop(0, board = listOf(StopMovement(b, v))), stop(1), stop(2, alight = listOf(StopMovement(b, v)))))
        assertEquals(0 to 2, plan.intervalOf(b))
        assertNull(plan.intervalOf(UUID.randomUUID()))
        assertEquals(2, plan.segmentCount)
        assertTrue(RunState.ACTIVE.canGoTo(RunState.LOCKED))
        assertFalse(RunState.COMPLETED.canGoTo(RunState.ACTIVE))
    }

    @Test
    fun constraint_validator_erkennt_reihenfolge_kapazitaet_und_vektorfehler() {
        val cap = ResourceVector(seats = 2, wheelchair = 0)
        val a = UUID.randomUUID(); val b = UUID.randomUUID(); val c = UUID.randomUUID()
        val one = ResourceVector(seats = 1)
        val ok = listOf(stop(0, board = listOf(StopMovement(a, one), StopMovement(b, one))), stop(1, alight = listOf(StopMovement(a, one))), stop(2, alight = listOf(StopMovement(b, one))))
        assertEquals(emptyList<Any>(), RunPlanValidator.validate(cap, ok))
        assertEquals(listOf(2, 1), RunPlanValidator.occupancy(ok).map { it.first })
        // über Kapazität
        val over = listOf(stop(0, board = listOf(StopMovement(a, one), StopMovement(b, one), StopMovement(c, one))), stop(1, alight = listOf(StopMovement(a, one), StopMovement(b, one), StopMovement(c, one))))
        assertTrue(RunPlanValidator.validate(cap, over).any { it.code == PlanViolationCode.OVER_CAPACITY && it.dimension == Dimension.SEATS })
        // Ausstieg vor Einstieg
        val reversed = listOf(stop(0, alight = listOf(StopMovement(a, one))), stop(1, board = listOf(StopMovement(a, one))))
        assertTrue(RunPlanValidator.validate(cap, reversed).any { it.code == PlanViolationCode.ALIGHT_BEFORE_BOARD })
        // Lücke in der Reihenfolge
        assertEquals(listOf(PlanViolationCode.SEQUENCE_GAP), RunPlanValidator.validate(cap, listOf(stop(0), stop(2))).map { it.code })
        // Abschnittsbelegung unter null und über Kapazität
        val seg = RunPlanValidator.checkSegmentOccupancy(cap, listOf(1 to 0, 3 to 0, -1 to 0))
        assertEquals(listOf(PlanViolationCode.OVER_CAPACITY to 1, PlanViolationCode.NEGATIVE_OCCUPANCY to 2), seg.map { it.code to it.seq })
    }
}

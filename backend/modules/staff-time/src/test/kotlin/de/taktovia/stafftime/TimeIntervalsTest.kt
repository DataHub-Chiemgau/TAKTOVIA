package de.taktovia.stafftime

import de.taktovia.stafftime.api.AnomalyCode
import de.taktovia.stafftime.api.IntervalFlag
import de.taktovia.stafftime.api.TimeEventKind
import de.taktovia.stafftime.api.TimeEventSource
import de.taktovia.stafftime.api.TimeEventView
import de.taktovia.stafftime.api.TimeIntervals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/** WP-M4-08: Regel zur Ableitung von Arbeitszeitintervallen aus Ereignisfolgen (reine Funktion, ohne Datenbank). */
class TimeIntervalsTest {
    private fun at(h: Int, m: Int = 0) = Instant.parse("2026-12-01T00:00:00Z").plusSeconds(h * 3600L + m * 60L)
    private fun ev(kind: TimeEventKind, t: Instant, received: Instant = t) =
        TimeEventView(UUID.randomUUID(), "p", kind, t, t, received, TimeEventSource.APP, "p", null, null)

    @Test
    fun mehrere_pausen_werden_abgezogen_und_reihenfolge_der_eingabe_ist_egal() {
        val events = listOf(ev(TimeEventKind.WORK_END, at(16)), ev(TimeEventKind.BREAK_START, at(12)), ev(TimeEventKind.WORK_START, at(8)), ev(TimeEventKind.BREAK_END, at(12, 45)),
            ev(TimeEventKind.BREAK_START, at(15)), ev(TimeEventKind.BREAK_END, at(15, 15)))
        val d = TimeIntervals.derive(events)
        assertEquals(1, d.intervals.size); assertEquals(8 * 3600L - 45 * 60 - 15 * 60, d.intervals.single().netSeconds); assertEquals(2, d.intervals.single().breaks.size)
    }

    @Test
    fun zweiter_beginn_ohne_ende_laesst_das_erste_intervall_offen() {
        val d = TimeIntervals.derive(listOf(ev(TimeEventKind.WORK_START, at(6)), ev(TimeEventKind.WORK_START, at(13)), ev(TimeEventKind.WORK_END, at(14))))
        assertEquals(2, d.intervals.size)
        assertEquals(setOf(IntervalFlag.MISSING_END), d.intervals[0].flags); assertEquals(null, d.intervals[0].end); assertEquals(null, d.intervals[0].netSeconds)
        assertEquals(3600L, d.intervals[1].netSeconds)
    }

    @Test
    fun pause_ohne_ende_vor_arbeitsende_wird_gekennzeichnet_und_bis_zum_arbeitsende_gezaehlt() {
        val d = TimeIntervals.derive(listOf(ev(TimeEventKind.WORK_START, at(8)), ev(TimeEventKind.BREAK_START, at(12)), ev(TimeEventKind.WORK_END, at(13))))
        val i = d.intervals.single()
        assertEquals(setOf(IntervalFlag.BREAK_NOT_ENDED), i.flags); assertEquals(4 * 3600L, i.netSeconds, "Pause zählt bis zum Arbeitsende"); assertEquals(null, i.breaks.single().end)
    }

    @Test
    fun pausen_ausserhalb_der_arbeit_sind_anomalien_und_gehen_nicht_verloren() {
        val a = ev(TimeEventKind.BREAK_START, at(5)); val b = ev(TimeEventKind.BREAK_END, at(5, 10))
        val d = TimeIntervals.derive(listOf(a, b))
        assertEquals(emptyList<Any>(), d.intervals); assertEquals(listOf(a.eventId to AnomalyCode.BREAK_OUTSIDE_WORK, b.eventId to AnomalyCode.BREAK_OUTSIDE_WORK), d.anomalies.map { it.eventId to it.code })
        val s = ev(TimeEventKind.WORK_START, at(6)); val bs1 = ev(TimeEventKind.BREAK_START, at(7)); val bs2 = ev(TimeEventKind.BREAK_START, at(7, 5)); val be0 = ev(TimeEventKind.BREAK_END, at(7, 10)); val be = ev(TimeEventKind.BREAK_END, at(7, 20))
        val d2 = TimeIntervals.derive(listOf(s, bs1, bs2, be0, be, ev(TimeEventKind.WORK_END, at(9))))
        assertEquals(listOf(bs2.eventId to AnomalyCode.BREAK_ALREADY_STARTED, be.eventId to AnomalyCode.BREAK_END_WITHOUT_START), d2.anomalies.map { it.eventId to it.code })
        assertEquals(3 * 3600L - 10 * 60, d2.intervals.single().netSeconds)
    }

    @Test
    fun gleiche_ereigniszeit_wird_nach_empfangszeit_geordnet() {
        val start = ev(TimeEventKind.WORK_START, at(6), received = at(6, 1)); val end = ev(TimeEventKind.WORK_END, at(6), received = at(6, 2))
        val d = TimeIntervals.derive(listOf(end, start))
        assertEquals(0L, d.intervals.single().netSeconds); assertEquals(emptySet<IntervalFlag>(), d.intervals.single().flags)
    }
}

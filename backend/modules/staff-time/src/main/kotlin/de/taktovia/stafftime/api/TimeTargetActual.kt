package de.taktovia.stafftime.api

import de.taktovia.platform.context.RequestContext
import java.time.LocalDate
import java.time.ZoneId

/** Geplante Schicht als Soll eines Tages (Quelle `staff_shift`); [plannedSeconds] ist die absolute Dauer aus den Zeitpunkten (DST-fest). */
data class PlannedShift(val shiftId: java.util.UUID, val startsAt: java.time.Instant, val endsAt: java.time.Instant, val timezone: ZoneId, val plannedSeconds: Long, val state: String, val breakMinutes: Long = 0)

/** Kennzeichen eines Tages der Soll/Ist-Sicht. [NO_ACTUAL_BOOKING]: Soll vorhanden, keine Ist-Buchung (der Planwert ersetzt nie den Istwert). */
enum class TargetActualFlag { NO_ACTUAL_BOOKING, ACTUAL_WITHOUT_PLAN, ACTUAL_INCOMPLETE }

/** Ein Tag je Person: Soll aus `staff_shift`, Ist aus `time_event`. [actualNetSeconds] ist `null`, wenn keine vollständige Ist-Buchung vorliegt (nie mit [plannedSeconds] gefüllt). */
data class TargetActualDay(
    val day: LocalDate, val planned: List<PlannedShift>, val plannedSeconds: Long, val actual: List<WorkInterval>, val actualNetSeconds: Long?, val flags: Set<TargetActualFlag>,
    /** Regelhinweise (WP-M4-13) für den Tag aus Plan und Ist; nur in der Eigensicht der Person (fremde Personen: leer). Berechnet, ändert weder Soll noch Ist. */
    val hints: List<RuleHint> = emptyList(),
)

/**
 * Soll/Ist je Person und Tag (WP-M4-09, LH-6.24-TIM-004): reine Leseprojektion; Soll (`staff_shift`) und Ist (`time_event`) bleiben getrennt gespeichert, die Sicht schreibt nichts.
 * Zuordnung zum Tag: Soll nach dem Betriebstag der Schicht, Ist nach dem lokalen Tag (Zone [zone]) des Intervallbeginns (ohne Beginn: des Endes). Rechte wie `listTimeEvents`
 * (`time-events:read`; fremde Person nur mit `time-events:manage`, sonst 404). Höchstens 31 Tage je Abruf (sonst 422).
 */
interface TimeTargetActual {
    fun days(ctx: RequestContext, personRef: String?, from: LocalDate, to: LocalDate, zone: ZoneId): List<TargetActualDay>
}

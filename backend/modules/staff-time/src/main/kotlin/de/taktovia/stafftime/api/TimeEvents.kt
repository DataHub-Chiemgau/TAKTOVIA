package de.taktovia.stafftime.api

import de.taktovia.platform.context.RequestContext
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

enum class TimeEventKind { WORK_START, WORK_END, BREAK_START, BREAK_END }

/** Erfassungsquelle (Enum des Detailvertrags): App, Web oder Nacherfassung durch eine berechtigte Rolle. */
enum class TimeEventSource { APP, WEB, BACKFILL }

/** Selbsterfassung der eigenen Arbeitszeit; die Person ist der Akteur, nie Teil der Eingabe. [channel] ist `APP` oder `WEB`. */
data class RecordTimeEvent(
    val eventId: UUID, val kind: TimeEventKind, val occurredAt: Instant, val deviceRecordedAt: Instant, val channel: TimeEventSource, val shiftId: UUID?,
    val timeZone: ZoneId = WorkTime.DEFAULT_ZONE,
)

/** Nacherfassung durch die Personalrolle für [personRef]; Begründung ist Pflicht. */
data class BackfillTimeEvent(val eventId: UUID, val personRef: String, val kind: TimeEventKind, val occurredAt: Instant, val shiftId: UUID?, val reason: String, val timeZone: ZoneId = WorkTime.DEFAULT_ZONE)

data class TimeEventView(
    val eventId: UUID, val personRef: String, val kind: TimeEventKind, val occurredAt: Instant, val deviceRecordedAt: Instant?, val receivedAt: Instant,
    val source: TimeEventSource, val recordedBy: String, val shiftId: UUID?, val backfillReason: String?,
    val timeZone: ZoneId = WorkTime.DEFAULT_ZONE,
) {
    /** Lokale Darstellung der Ereigniszeit in der Zone des Ereignisses (WP-M4-10). */
    val occurredLocal: LocalPresentation get() = WorkTime.present(occurredAt, timeZone)
}

/** [created] ist falsch bei einer Wiederholung mit gleichem Inhalt (das gespeicherte Ereignis, keine zweite Zeile). */
data class TimeEventResult(val event: TimeEventView, val created: Boolean)

enum class IntervalFlag { MISSING_END, END_WITHOUT_START, BREAK_NOT_ENDED }
enum class AnomalyCode { BREAK_OUTSIDE_WORK, BREAK_END_WITHOUT_START, BREAK_ALREADY_STARTED }

data class WorkBreak(val start: Instant, val end: Instant?)

/** Abgeleitetes Arbeitszeitintervall; wird nie gespeichert. [start] oder [end] fehlen, wenn das Ereignis fehlt (gekennzeichnet, nie verworfen oder automatisch ergänzt). */
data class WorkInterval(val start: Instant?, val end: Instant?, val breaks: List<WorkBreak>, val netSeconds: Long?, val flags: Set<IntervalFlag>, val eventIds: List<UUID>, val zone: ZoneId = WorkTime.DEFAULT_ZONE)

data class TimeAnomaly(val eventId: UUID, val code: AnomalyCode)

data class DerivedTime(val intervals: List<WorkInterval>, val anomalies: List<TimeAnomaly>)

/**
 * Regel zur Ableitung von Intervallen aus Ereignisfolgen (Detailvertrag LH-6.24-TIM-001, WP-M4-08): Ereignisse je Person nach `(Ereigniszeit, Empfangszeit, ID)` ordnen;
 * `WORK_START` öffnet ein Intervall, `WORK_END` schließt es, Pausen innerhalb abziehen. Netto = Ende − Beginn − Pausen.
 * - Zweiter Beginn ohne Ende: das vorige Intervall bleibt **offen** (`MISSING_END`, kein Nettowert), kein Auto-Abschluss (Offener Punkt: Regel für fehlendes Ende, Annahme).
 * - Ende ohne Beginn: Intervall ohne Beginn (`END_WITHOUT_START`), kein Nettowert.
 * - Pause ohne Ende bei Arbeitsende: `BREAK_NOT_ENDED`, die Pause zählt bis zum Arbeitsende; Pause außerhalb der Arbeit oder doppelt begonnen/beendet: Anomalie.
 */
object TimeIntervals {
    private class Open(val start: Instant, val ids: MutableList<UUID>, val zone: ZoneId) {
        val breaks = mutableListOf<WorkBreak>()
        var breakStart: Instant? = null
    }

    fun derive(events: List<TimeEventView>): DerivedTime {
        val intervals = mutableListOf<WorkInterval>(); val anomalies = mutableListOf<TimeAnomaly>()
        var open: Open? = null

        fun closeOpen(end: Instant?, flags: MutableSet<IntervalFlag>) {
            val o = open ?: return
            val bs = o.breakStart
            val breaks = o.breaks.toMutableList()
            if (bs != null) { breaks += WorkBreak(bs, null); flags += IntervalFlag.BREAK_NOT_ENDED }
            val net = if (end == null) null else maxOf(0L, end.epochSecond - o.start.epochSecond - breaks.sumOf { b -> ((b.end ?: end).epochSecond - b.start.epochSecond).coerceAtLeast(0L) })
            intervals += WorkInterval(o.start, end, breaks, net, flags, o.ids, o.zone)
            open = null
        }

        for (e in events.sortedWith(compareBy<TimeEventView>({ it.occurredAt }, { it.receivedAt }, { it.eventId }))) {
            when (e.kind) {
                TimeEventKind.WORK_START -> { if (open != null) closeOpen(null, mutableSetOf(IntervalFlag.MISSING_END)); open = Open(e.occurredAt, mutableListOf(e.eventId), e.timeZone) }
                TimeEventKind.WORK_END -> {
                    val o = open
                    if (o == null) intervals += WorkInterval(null, e.occurredAt, emptyList(), null, setOf(IntervalFlag.END_WITHOUT_START), listOf(e.eventId), e.timeZone)
                    else { o.ids += e.eventId; closeOpen(e.occurredAt, mutableSetOf()) }
                }
                TimeEventKind.BREAK_START -> {
                    val o = open
                    if (o == null) anomalies += TimeAnomaly(e.eventId, AnomalyCode.BREAK_OUTSIDE_WORK)
                    else if (o.breakStart != null) anomalies += TimeAnomaly(e.eventId, AnomalyCode.BREAK_ALREADY_STARTED)
                    else { o.breakStart = e.occurredAt; o.ids += e.eventId }
                }
                TimeEventKind.BREAK_END -> {
                    val o = open
                    if (o == null) anomalies += TimeAnomaly(e.eventId, AnomalyCode.BREAK_OUTSIDE_WORK)
                    else if (o.breakStart == null) anomalies += TimeAnomaly(e.eventId, AnomalyCode.BREAK_END_WITHOUT_START)
                    else { o.breaks += WorkBreak(o.breakStart!!, e.occurredAt); o.breakStart = null; o.ids += e.eventId }
                }
            }
        }
        if (open != null) closeOpen(null, mutableSetOf(IntervalFlag.MISSING_END))
        return DerivedTime(intervals, anomalies)
    }
}

/**
 * Ist-Arbeitszeit als unveränderliche Zeitereignisse (WP-M4-08, LH-6.24-TIM-001), unabhängig von Schichten. Die Ereignis-ID dedupliziert dauerhaft je Mandant
 * (Wiederholung mit gleichem Inhalt: dasselbe Ereignis, keine zweite Zeile; anderer Inhalt: 409 `IDEMPOTENCY_CONFLICT`). Selbsterfassung braucht `time-events:write`,
 * Lesen `time-events:read`, fremde Personen und Nacherfassung `time-events:manage` (Personalrolle); ohne dieses Recht ist eine fremde Person nicht auffindbar (404).
 */
interface TimeEvents {
    fun record(ctx: RequestContext, cmd: RecordTimeEvent): TimeEventResult

    fun backfill(ctx: RequestContext, cmd: BackfillTimeEvent): TimeEventResult

    /** Ereignisse der Person (ohne Angabe die eigene), Ereigniszeit im Fenster `[from, to)`, nach `(Empfangszeit, ID)` sortiert, mit `limit + 1` Zeilen für die Folgeseite. */
    fun list(ctx: RequestContext, personRef: String?, from: Instant?, to: Instant?, shiftId: UUID?, limit: Int, after: Pair<Instant, UUID>?): List<TimeEventView>

    /** Abgeleitete Intervalle im Fenster `[from, to)` (höchstens 31 Tage, höchstens 5000 Ereignisse; sonst 422). */
    fun intervals(ctx: RequestContext, personRef: String?, from: Instant, to: Instant): DerivedTime
}

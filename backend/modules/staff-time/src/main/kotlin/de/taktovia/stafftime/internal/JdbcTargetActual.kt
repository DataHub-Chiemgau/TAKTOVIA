package de.taktovia.stafftime.internal

import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.stafftime.api.IntervalFlag
import de.taktovia.stafftime.api.PlannedShift
import de.taktovia.stafftime.api.TargetActualDay
import de.taktovia.stafftime.api.TargetActualFlag
import de.taktovia.stafftime.api.TimeEventKind
import de.taktovia.stafftime.api.TimeEventSource
import de.taktovia.stafftime.api.TimeEventView
import de.taktovia.stafftime.api.TimeIntervals
import de.taktovia.stafftime.api.TimeTargetActual
import de.taktovia.stafftime.api.WorkInterval
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Soll/Ist-Projektion (WP-M4-09). Nur lesend (`readOnly`-Transaktion): `staff_shift` liefert das Soll, `time_event` das Ist; keiner der beiden Pfade wird verändert.
 * Die Intervallableitung ist [TimeIntervals] (WP-M4-08); das Ist-Fenster reicht einen Tag über beide Ränder, damit Schichten über Mitternacht vollständig abgeleitet werden.
 */
internal class JdbcTargetActual(private val ctxTx: ContextTransactions) : TimeTargetActual {

    override fun days(ctx: RequestContext, personRef: String?, from: LocalDate, to: LocalDate, zone: ZoneId): List<TargetActualDay> {
        if (!ctx.hasScope(SCOPE_READ)) throw ProblemException(ErrorCode.FORBIDDEN)
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) throw ProblemException.invalid(Violation("/\$query/to", ViolationCode.INVALID_VALUE))
        val self = ctx.actor.subject
        val person = personRef ?: self
        if (person != self && !ctx.hasScope(SCOPE_MANAGE)) throw ProblemException.notFound()

        val winFrom = from.minusDays(1).atStartOfDay(zone).toInstant(); val winTo = to.plusDays(2).atStartOfDay(zone).toInstant()
        val (shifts, events) = ctxTx.run(ctx, readOnly = true) { j ->
            val s = j.query(
                SHIFTS_SQL,
                { rs, _ -> Planned(PlannedShift(rs.getObject("id", UUID::class.java), rs.getTimestamp("starts_at").toInstant(), rs.getTimestamp("ends_at").toInstant(), ZoneId.of(rs.getString("timezone")),
                    Duration.between(rs.getTimestamp("starts_at").toInstant(), rs.getTimestamp("ends_at").toInstant()).seconds, rs.getString("state")), rs.getObject("operating_day", LocalDate::class.java)) },
                *ShiftOwnership.args(ctx.actor.issuer, person), from, to,
            )
            val e = j.query(
                "SELECT e.id, e.person_ref, e.kind, e.occurred_at, e.device_recorded_at, e.received_at, e.source, e.recorded_by, e.shift_id, e.backfill_reason FROM taktovia.time_event e " +
                    "WHERE e.person_ref = ? AND e.occurred_at >= ? AND e.occurred_at < ? ORDER BY e.occurred_at, e.received_at, e.id LIMIT ?",
                { rs, _ -> TimeEventView(rs.getObject("id", UUID::class.java), rs.getString("person_ref"), TimeEventKind.valueOf(rs.getString("kind")), rs.getTimestamp("occurred_at").toInstant(),
                    rs.getTimestamp("device_recorded_at")?.toInstant(), rs.getTimestamp("received_at").toInstant(), TimeEventSource.valueOf(rs.getString("source")), rs.getString("recorded_by"),
                    rs.getObject("shift_id", UUID::class.java), rs.getString("backfill_reason")) },
                person, Timestamp.from(winFrom), Timestamp.from(winTo), MAX_EVENTS + 1,
            )
            s to e
        }
        if (events.size > MAX_EVENTS) throw ProblemException.invalid(Violation("/\$query/to", ViolationCode.TOO_MANY_ITEMS))

        val intervals = TimeIntervals.derive(events).intervals
        fun dayOf(i: WorkInterval): LocalDate = ((i.start ?: i.end)!!).atZone(zone).toLocalDate()
        val actualByDay = intervals.groupBy(::dayOf)
        val plannedByDay = shifts.groupBy { it.day }
        return generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.map { d ->
            val planned = plannedByDay[d].orEmpty().map { it.shift }
            val actual = actualByDay[d].orEmpty()
            val flags = linkedSetOf<TargetActualFlag>()
            if (planned.isNotEmpty() && actual.isEmpty()) flags += TargetActualFlag.NO_ACTUAL_BOOKING
            if (planned.isEmpty() && actual.isNotEmpty()) flags += TargetActualFlag.ACTUAL_WITHOUT_PLAN
            if (actual.any { IntervalFlag.MISSING_END in it.flags || IntervalFlag.END_WITHOUT_START in it.flags }) flags += TargetActualFlag.ACTUAL_INCOMPLETE
            val complete = actual.isNotEmpty() && actual.none { it.netSeconds == null }
            TargetActualDay(d, planned, planned.sumOf { it.plannedSeconds }, actual, if (complete) actual.sumOf { it.netSeconds!! } else null, flags)
        }.toList()
    }

    private class Planned(val shift: PlannedShift, val day: LocalDate)

    private companion object {
        const val SHIFTS_SQL = "SELECT s.id, s.starts_at, s.ends_at, s.timezone, s.state, s.operating_day FROM taktovia.staff_shift s WHERE " + ShiftOwnership.PREDICATE +
            " AND s.operating_day BETWEEN ? AND ? AND s.state <> 'DRAFT' ORDER BY s.starts_at, s.id"
        const val SCOPE_READ = "time-events:read"
        const val SCOPE_MANAGE = "time-events:manage"
        const val MAX_DAYS = 31L
        const val MAX_EVENTS = 5000
    }
}

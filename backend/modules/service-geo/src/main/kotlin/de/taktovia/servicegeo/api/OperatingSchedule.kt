package de.taktovia.servicegeo.api

import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** Ein Zeitfenster eines Tages in Minuten ab Mitternacht Ortszeit, halboffen `[fromMin, toMin)`; `toMin == 1440` ist „24:00“ (nur als Ende). */
data class HoursWindow(val fromMin: Int, val toMin: Int) {
    init { require(fromMin in 0..1439 && toMin in 1..1440 && fromMin < toMin) { "ungültiges Zeitfenster" } }
    operator fun contains(minuteOfDay: Int) = minuteOfDay in fromMin until toMin
    fun format(): Pair<String, String> = hhmm(fromMin) to hhmm(toMin)
    companion object {
        fun hhmm(min: Int) = "%02d:%02d".format(min / 60, min % 60)
        private val CLOCK = Regex("^(([01][0-9]|2[0-3]):[0-5][0-9]|24:00)$")
        /** `HH:MM`, `24:00` nur als Ende; `null` bei ungültiger Form. */
        fun minutes(text: String?, asEnd: Boolean): Int? {
            if (text == null || !CLOCK.matches(text)) return null
            val m = text.substring(0, 2).toInt() * 60 + text.substring(3).toInt()
            return if (m == 1440 && !asEnd) null else m
        }
    }
}

/** Wochenregel: Fenster, die an den genannten Wochentagen gelten. */
data class WeeklyRule(val weekdays: Set<DayOfWeek>, val window: HoursWindow)

/** Ausnahmetag: ersetzt das Wochenschema des Datums vollständig; keine Fenster = ganztägig geschlossen. */
data class ExceptionDay(val date: LocalDate, val windows: List<HoursWindow>, val note: String? = null)

/** Ergebnis von [OperatingSchedule.nextOpening]. */
data class Opening(val instant: Instant, val local: ZonedDateTime)

/**
 * Betriebszeiten eines Dienstes als **Ortszeit** der Dienstzone (WP-M2-03, LH-6.1-SYS-006): Wochenschema plus Ausnahmetage.
 *
 * **Regel für Zeitumstellungen (Detailvertrag WP-M2-03):** Ein Zeitpunkt wird in die Ortszeit der Zone umgerechnet; maßgeblich sind lokales Datum und
 * lokale Uhrzeit, nicht die verstrichene Zeit. Folgen: Bei der Umstellung im Herbst (25.10.2026) kommt 02:00 bis 03:00 zweimal vor; liegt die Ortszeit
 * im Fenster, sind **beide** Vorkommen „in Betrieb“, liegt sie außerhalb, beide „außer Betrieb“ (jeder der beiden Offsets ergibt eine eindeutige Antwort).
 * Im Frühjahr existiert die übersprungene Stunde nie als Zeitpunkt; ein Fenster, das sie überdeckt, gilt für die vorhandenen Zeitpunkte unverändert.
 * Ein Ausnahmetag ersetzt das Wochenschema seines Ortsdatums vollständig. Ohne Fenster an einem Tag ist der Dienst dort außer Betrieb.
 * Fenster enden spätestens um 24:00 des Tages; Nachtbetrieb über Mitternacht wird als zwei Fenster an aufeinanderfolgenden Tagen erfasst.
 */
class OperatingSchedule(val zone: ZoneId, val weekly: List<WeeklyRule>, val exceptions: List<ExceptionDay> = emptyList()) {
    private val exceptionByDate = exceptions.associateBy { it.date }

    /** Fenster des Ortsdatums [date]: Ausnahmetag oder Wochenschema, nach Beginn sortiert. */
    fun windowsOn(date: LocalDate): List<HoursWindow> =
        (exceptionByDate[date]?.windows ?: weekly.filter { date.dayOfWeek in it.weekdays }.map { it.window }).sortedBy { it.fromMin }

    fun isOperating(at: Instant): Boolean {
        val local = at.atZone(zone)
        val minute = local.hour * 60 + local.minute
        return windowsOn(local.toLocalDate()).any { minute in it }
    }

    /** Beginn des nächsten Fensters ab [from] (einschließlich), höchstens [horizonDays] Tage voraus; `null`, wenn keines. Läuft gerade ein Fenster, ist es [from] selbst. */
    fun nextOpening(from: Instant, horizonDays: Int = 366): Opening? {
        if (isOperating(from)) return Opening(from, from.atZone(zone))
        val start = from.atZone(zone)
        var date = start.toLocalDate()
        for (i in 0..horizonDays) {
            for (w in windowsOn(date)) {
                val candidate = firstInstantAtOrAfter(date, w.fromMin, zone)
                if (!candidate.isBefore(from)) return Opening(candidate, candidate.atZone(zone))
            }
            date = date.plusDays(1)
        }
        return null
    }

    companion object {
        /** Erster Zeitpunkt, dessen Ortszeit am Datum [date] bei oder nach [minuteOfDay] liegt (liegt die Minute in einer Frühjahrslücke, der erste Zeitpunkt danach). */
        private fun firstInstantAtOrAfter(date: LocalDate, minuteOfDay: Int, zone: ZoneId): Instant =
            ZonedDateTime.of(date, java.time.LocalTime.of(minuteOfDay / 60, minuteOfDay % 60), zone).toInstant()

        private val DAYS = mapOf("MON" to DayOfWeek.MONDAY, "TUE" to DayOfWeek.TUESDAY, "WED" to DayOfWeek.WEDNESDAY, "THU" to DayOfWeek.THURSDAY,
            "FRI" to DayOfWeek.FRIDAY, "SAT" to DayOfWeek.SATURDAY, "SUN" to DayOfWeek.SUNDAY)
        fun dayCode(d: DayOfWeek) = DAYS.entries.first { it.value == d }.key
        const val MAX_WEEKLY = 50
        const val MAX_EXCEPTIONS = 366

        /**
         * Prüft und liest die JSON-Form (Listen von Maps): `weekly` = `[{weekdays:[…], from, to}]`, `exceptions` = `[{date, windows:[{from,to}], note?}]`. Alle Verletzungen
         * werden mit JSON-Pointer gemeldet (`/weekly_hours/0/from`, …); Fenster desselben Tages dürfen sich nicht überlappen.
         */
        fun parse(zone: ZoneId, weekly: Any?, exceptions: Any?, weeklyPointer: String = "/weekly_hours", exceptionsPointer: String = "/exceptions"): Pair<OperatingSchedule?, List<Violation>> {
            val v = mutableListOf<Violation>()
            val rules = mutableListOf<WeeklyRule>()
            val list = weekly as? List<*>
            if (list == null) v += Violation(weeklyPointer, ViolationCode.TYPE_MISMATCH)
            else {
                if (list.size > MAX_WEEKLY) v += Violation(weeklyPointer, ViolationCode.TOO_MANY_ITEMS)
                list.forEachIndexed { i, raw ->
                    val p = "$weeklyPointer/$i"; val m = raw as? Map<*, *>
                    if (m == null) { v += Violation(p, ViolationCode.TYPE_MISMATCH); return@forEachIndexed }
                    val days = (m["weekdays"] as? List<*>)?.map { DAYS[it] }
                    when {
                        days == null || days.isEmpty() -> v += Violation("$p/weekdays", ViolationCode.REQUIRED)
                        days.any { it == null } -> v += Violation("$p/weekdays", ViolationCode.NOT_IN_ENUM)
                        days.toSet().size != days.size -> v += Violation("$p/weekdays", ViolationCode.NOT_UNIQUE)
                    }
                    val window = window(m, p, v)
                    if (days != null && days.isNotEmpty() && days.none { it == null } && window != null) rules += WeeklyRule(days.filterNotNull().toSet(), window)
                }
            }
            val exs = mutableListOf<ExceptionDay>()
            val exList = (exceptions ?: emptyList<Any>()) as? List<*>
            if (exList == null) v += Violation(exceptionsPointer, ViolationCode.TYPE_MISMATCH)
            else {
                if (exList.size > MAX_EXCEPTIONS) v += Violation(exceptionsPointer, ViolationCode.TOO_MANY_ITEMS)
                exList.forEachIndexed { i, raw ->
                    val p = "$exceptionsPointer/$i"; val m = raw as? Map<*, *>
                    if (m == null) { v += Violation(p, ViolationCode.TYPE_MISMATCH); return@forEachIndexed }
                    val date = (m["date"] as? String)?.let { try { LocalDate.parse(it) } catch (_: Exception) { null } }
                    if (date == null) v += Violation("$p/date", if (m["date"] == null) ViolationCode.REQUIRED else ViolationCode.INVALID_FORMAT)
                    val ws = (m["windows"] as? List<*>) ?: emptyList<Any>().also { if (m["windows"] != null) v += Violation("$p/windows", ViolationCode.TYPE_MISMATCH) }
                    val windows = ws.mapIndexedNotNull { j, w ->
                        val wm = w as? Map<*, *>
                        if (wm == null) { v += Violation("$p/windows/$j", ViolationCode.TYPE_MISMATCH); null } else window(wm, "$p/windows/$j", v)
                    }
                    val note = m["note"] as? String
                    if (note != null && note.length > 200) v += Violation("$p/note", ViolationCode.TOO_LONG)
                    if (date != null) exs += ExceptionDay(date, windows, note)
                    if (windows.sortedBy { it.fromMin }.zipWithNext().any { (a, b) -> a.toMin > b.fromMin }) v += Violation("$p/windows", ViolationCode.INVALID_VALUE)
                }
                exs.groupBy { it.date }.filterValues { it.size > 1 }.keys.firstOrNull()?.let { dup ->
                    v += Violation(exceptionsPointer + "/" + exs.indexOfLast { it.date == dup }, ViolationCode.NOT_UNIQUE)
                }
            }
            for (d in DayOfWeek.entries) {
                val ws = rules.filter { d in it.weekdays }.map { it.window }.sortedBy { it.fromMin }
                if (ws.zipWithNext().any { (a, b) -> a.toMin > b.fromMin }) v += Violation(weeklyPointer, ViolationCode.INVALID_VALUE, "Zeitfenster am ${dayCode(d)} überlappen sich")
            }
            return if (v.isEmpty()) OperatingSchedule(zone, rules, exs) to v else null to v
        }

        private fun window(m: Map<*, *>, p: String, v: MutableList<Violation>): HoursWindow? {
            val from = HoursWindow.minutes(m["from"] as? String, asEnd = false); val to = HoursWindow.minutes(m["to"] as? String, asEnd = true)
            if (from == null) v += Violation("$p/from", if (m["from"] == null) ViolationCode.REQUIRED else ViolationCode.PATTERN_MISMATCH)
            if (to == null) v += Violation("$p/to", if (m["to"] == null) ViolationCode.REQUIRED else ViolationCode.PATTERN_MISMATCH)
            if (from == null || to == null) return null
            if (from >= to) { v += Violation("$p/to", ViolationCode.INTERVAL_INVALID); return null }
            return HoursWindow(from, to)
        }
    }
}

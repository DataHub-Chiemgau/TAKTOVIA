package de.taktovia.stafftime.api

import de.taktovia.platform.context.RequestContext
import org.springframework.jdbc.core.JdbcOperations
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Regeln der Arbeitszeit-Hinweise (WP-M4-13, LH-6.24-TIM-006). Die Hinweise sind Warnungen mit Begründung, nie eine Rechtsentscheidung. */
enum class RuleCode {
    /** Ruhezeit zwischen zwei Schichten oder Arbeitsintervallen kürzer als `min_rest_hours`. */
    REST_PERIOD_SHORT,

    /** Arbeitszeit eines Tages (ohne Pausen) länger als `max_daily_work_minutes`. */
    DAILY_WORK_EXCEEDED,

    /** Schicht oder Arbeitsintervall länger als die Pausenschwelle, aber ohne ausreichende Pause. */
    BREAK_MISSING,
}

/** Woraus der Hinweis berechnet wurde: [PLAN] aus Schichten (`staff_shift`), [ACTUAL] aus Ist-Zeiten (`time_event`). */
enum class HintBasis { PLAN, ACTUAL }

/**
 * Warnhinweis mit Regel, Sollwert, Istwert und Bezug. [expected] ist der Grenzwert der Regel, [actual] der gemessene Wert, beide in [unit] (`MINUTES`);
 * [shiftIds] bzw. [eventIds] nennen die betroffenen Schichten bzw. Zeitereignisse, [from]/[to] den betroffenen Zeitraum (absolute Zeitpunkte), [day] den lokalen Bezugstag.
 * Der Hinweis wird berechnet und nie gespeichert; er ändert weder Plan- noch Ist-Daten.
 */
data class RuleHint(
    val rule: RuleCode, val basis: HintBasis, val personRef: String, val expected: Long, val actual: Long, val unit: String, val day: LocalDate, val from: Instant, val to: Instant,
    val shiftIds: List<UUID>, val eventIds: List<UUID>, val message: String,
)

/** Eine Pausenschwelle: dauert die Arbeit **länger als** [afterMinutes], sind insgesamt mindestens [requiredBreakMinutes] Pause erforderlich. */
data class BreakThreshold(val afterMinutes: Long, val requiredBreakMinutes: Long)

/**
 * Wirksame Regelparameter. [minRestMinutes] (`min_rest_hours`, Standard und gesetzliche Untergrenze 11 h) und [maxDailyWorkMinutes] (`max_daily_work_minutes`, Standard und gesetzliche Obergrenze 600 min)
 * stammen aus der Konfiguration. Die Pausenschwellen sind **kein Katalogparameter**; der Standard (mehr als 6 h: 30 min, mehr als 9 h: 45 min) ist eine Planungsannahme bis zur Freigabe der Regelmatrix (Offener Punkt 3).
 */
data class WorkRules(
    val minRestMinutes: Long = 11 * 60L, val maxDailyWorkMinutes: Long = 600L,
    val breaks: List<BreakThreshold> = listOf(BreakThreshold(6 * 60L, 30L), BreakThreshold(9 * 60L, 45L)),
)

/** Quelle der wirksamen Regelparameter (Konfiguration je Mandant); wird in der laufenden Fachtransaktion gelesen. */
fun interface WorkRuleSource {
    fun rules(jdbc: JdbcOperations, ctx: RequestContext, at: Instant): WorkRules

    companion object { val DEFAULT = WorkRuleSource { _, _, _ -> WorkRules() } }
}

/** Ein Arbeitsabschnitt einer Person: eine geplante Schicht oder ein abgeleitetes Ist-Intervall. [breakMinutes] = geplante bzw. erfasste Pausen insgesamt. */
data class WorkSpan(
    val personRef: String, val start: Instant, val end: Instant, val zone: ZoneId, val breakMinutes: Long, val shiftId: UUID? = null, val eventIds: List<UUID> = emptyList(),
)

/**
 * Reine Regelberechnung (WP-M4-13): Dauern ausschließlich aus absoluten Zeitpunkten (DST-fest, Kap. 05 §5.1), Grenzwerte exakt (genau auf der Grenze: kein Hinweis). Überlappende Abschnitte derselben
 * Person sind Sache der Konfliktprüfung (Doppelbelegung, WP-M4-07) und erzeugen hier keinen Ruhezeit-Hinweis. [relevant] schränkt die Meldung auf Abschnitte ein, die zum Prüfgegenstand gehören
 * (z. B. der Entwurf); Nachbarabschnitte fließen nur als Bezug ein. Die Ausgabe ist deterministisch sortiert.
 */
object WorkRuleEngine {
    fun evaluate(spans: List<WorkSpan>, rules: WorkRules, basis: HintBasis, relevant: (WorkSpan) -> Boolean = { true }): List<RuleHint> {
        val out = mutableListOf<RuleHint>()
        for ((person, own) in spans.groupBy { it.personRef }) {
            val sorted = own.sortedWith(compareBy({ it.start }, { it.end }))
            rest(person, sorted, rules, basis, relevant, out)
            daily(person, sorted, rules, basis, relevant, out)
            for (s in sorted) if (relevant(s)) pause(person, s, rules, basis, out)
        }
        return out.sortedWith(compareBy({ it.personRef }, { it.from }, { it.rule.ordinal }))
    }

    private fun rest(person: String, sorted: List<WorkSpan>, rules: WorkRules, basis: HintBasis, relevant: (WorkSpan) -> Boolean, out: MutableList<RuleHint>) {
        var last: WorkSpan? = null
        for (s in sorted) {
            val prev = last
            if (prev != null && !s.start.isBefore(prev.end) && (relevant(prev) || relevant(s))) {
                val gap = Duration.between(prev.end, s.start)
                if (gap < Duration.ofMinutes(rules.minRestMinutes)) {
                    out += RuleHint(RuleCode.REST_PERIOD_SHORT, basis, person, rules.minRestMinutes, gap.toMinutes(), UNIT, WorkTime.present(s.start, s.zone).operatingDay, prev.end, s.start,
                        listOfNotNull(prev.shiftId, s.shiftId), prev.eventIds + s.eventIds,
                        "Ruhezeit unterschritten: ${gap.toMinutes()} min zwischen Arbeitsende und nächstem Beginn, mindestens ${rules.minRestMinutes} min vorgesehen. Hinweis zur Prüfung, keine Rechtsbewertung.")
                }
            }
            if (prev == null || s.end.isAfter(prev.end)) last = s
        }
    }

    private fun daily(person: String, sorted: List<WorkSpan>, rules: WorkRules, basis: HintBasis, relevant: (WorkSpan) -> Boolean, out: MutableList<RuleHint>) {
        for ((day, group) in sorted.groupBy { WorkTime.present(it.start, it.zone).operatingDay }.toSortedMap()) {
            if (group.none(relevant)) continue
            val net = group.sumOf { (Duration.between(it.start, it.end).seconds - it.breakMinutes * 60L).coerceAtLeast(0L) }
            if (net > rules.maxDailyWorkMinutes * 60L) {
                out += RuleHint(RuleCode.DAILY_WORK_EXCEEDED, basis, person, rules.maxDailyWorkMinutes, net / 60L, UNIT, day, group.minOf { it.start }, group.maxOf { it.end },
                    group.mapNotNull { it.shiftId }, group.flatMap { it.eventIds },
                    "Tägliche Arbeitszeit überschritten: ${net / 60L} min am $day, höchstens ${rules.maxDailyWorkMinutes} min vorgesehen. Hinweis zur Prüfung, keine Rechtsbewertung.")
            }
        }
    }

    private fun pause(person: String, s: WorkSpan, rules: WorkRules, basis: HintBasis, out: MutableList<RuleHint>) {
        val length = Duration.between(s.start, s.end)
        val required = rules.breaks.filter { length > Duration.ofMinutes(it.afterMinutes) }.maxOfOrNull { it.requiredBreakMinutes } ?: return
        if (s.breakMinutes < required) {
            out += RuleHint(RuleCode.BREAK_MISSING, basis, person, required, s.breakMinutes, UNIT, WorkTime.present(s.start, s.zone).operatingDay, s.start, s.end,
                listOfNotNull(s.shiftId), s.eventIds,
                "Pflichtpause fehlt: ${s.breakMinutes} min Pause bei ${length.toMinutes()} min Arbeitszeit, mindestens $required min vorgesehen (Planungsannahme, Regelmatrix nicht freigegeben). Hinweis zur Prüfung, keine Rechtsbewertung.")
        }
    }

    const val UNIT = "MINUTES"
}

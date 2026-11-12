package de.taktovia.stafftime.api

import de.taktovia.platform.time.LocalResolution
import de.taktovia.platform.time.OperatingDay
import de.taktovia.platform.time.Rfc3339
import de.taktovia.platform.time.ZonedMoments
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Lokale Darstellung eines Zeitpunkts (WP-M4-10, LH-6.24-TIM-005): absoluter Zeitpunkt [instant], IANA-Zone, in dieser Zone gültiger [offset], Ortszeit und Bezugstag.
 * [ambiguous] ist wahr, wenn die Ortszeit wegen der Herbstumstellung zweimal vorkommt; dann ist der Offset Teil der Anzeige und wird nie weggelassen.
 */
data class LocalPresentation(val instant: Instant, val zone: ZoneId, val offset: ZoneOffset, val local: LocalDateTime, val operatingDay: LocalDate, val ambiguous: Boolean) {
    /** RFC 3339 mit dem Offset der Zone (`2026-10-25T02:30:00+01:00`); unterscheidet die beiden Herbst-02:30. */
    fun localWithOffset(): String = Rfc3339.format(instant, offset)
}

/** Auswertung einer Schicht oder eines Arbeitsintervalls aus absoluten Zeitpunkten. [referenceDay] ist das lokale Datum des Beginns (Festlegung der Testtabelle, Fachbereich bestätigt OP-08). */
data class SpanEvaluation(val start: LocalPresentation, val end: LocalPresentation, val duration: Duration, val referenceDay: LocalDate, val crossesMidnight: Boolean)

/**
 * Zeitrechenbaustein des Moduls (WP-M4-10) auf Basis des Plattform-Zeittyps. Regeln: Dauern ausschließlich aus absoluten Zeitpunkten (nie aus Ortszeit-Differenzen),
 * Ortszeit in der Frühjahrslücke wird abgelehnt, eine doppelte Ortszeit verlangt den Offset, Bezugstag einer Schicht über Mitternacht ist der lokale Tag des Beginns.
 * Zeitzonenwechsel: Beginn und Ende werden je in der Zone des Datensatzes gelesen; der Auswertungstag für Berichte ([reportingDay]) steht daneben in `reporting_timezone`.
 */
object WorkTime {
    /** Zone, solange `reporting_timezone` je Mandant noch nicht angebunden ist (Pilotbetrieb in Deutschland). */
    val DEFAULT_ZONE: ZoneId = ZoneId.of("Europe/Berlin")

    fun duration(start: Instant, end: Instant): Duration {
        require(!end.isBefore(start)) { "Ende liegt vor dem Beginn" }
        return Duration.between(start, end)
    }

    fun present(instant: Instant, zone: ZoneId): LocalPresentation {
        val local = LocalDateTime.ofInstant(instant, zone)
        return LocalPresentation(instant, zone, zone.rules.getOffset(instant), local, OperatingDay.of(instant, zone), zone.rules.getValidOffsets(local).size > 1)
    }

    /** Lokaler Auswertungstag für Berichte in der `reporting_timezone`, unabhängig von der Zone des Datensatzes. */
    fun reportingDay(instant: Instant, reportingZone: ZoneId): LocalDate = OperatingDay.of(instant, reportingZone)

    fun evaluate(start: Instant, end: Instant, startZone: ZoneId, endZone: ZoneId = startZone): SpanEvaluation {
        val s = present(start, startZone); val e = present(end, endZone)
        return SpanEvaluation(s, e, duration(start, end), s.operatingDay, e.local.toLocalDate() != s.local.toLocalDate())
    }

    /** Löst eine Ortszeit auf; Lücke -> [LocalTimeRejected.Gap], mehrdeutig ohne Offset -> [LocalTimeRejected.Ambiguous], Offset passt nicht -> [LocalTimeRejected.OffsetMismatch]. Nie stille Auswahl oder Verschiebung. */
    fun resolveLocal(local: LocalDateTime, zone: ZoneId, offset: ZoneOffset? = null): Instant {
        val valid = zone.rules.getValidOffsets(local)
        if (valid.isEmpty()) throw LocalTimeRejected.Gap(local, zone)
        if (offset != null) {
            if (offset !in valid) throw LocalTimeRejected.OffsetMismatch(local, zone, offset)
            return local.toInstant(offset)
        }
        return when (val r = ZonedMoments.resolve(local, zone)) {
            is LocalResolution.Unique -> r.moment.instant
            is LocalResolution.Ambiguous -> throw LocalTimeRejected.Ambiguous(local, zone)
            LocalResolution.Gap -> throw LocalTimeRejected.Gap(local, zone)
        }
    }
}

/** Abgelehnte Ortszeit (Programmierfehlerklasse für den Baustein; die API übersetzt sie in 422). */
sealed class LocalTimeRejected(message: String) : IllegalArgumentException(message) {
    class Gap(val local: LocalDateTime, val zone: ZoneId) : LocalTimeRejected("Ortszeit $local existiert in $zone nicht (Zeitumstellung)")
    class Ambiguous(val local: LocalDateTime, val zone: ZoneId) : LocalTimeRejected("Ortszeit $local kommt in $zone zweimal vor, Offset fehlt")
    class OffsetMismatch(val local: LocalDateTime, val zone: ZoneId, val offset: ZoneOffset) : LocalTimeRejected("Offset $offset passt nicht zur Ortszeit $local in $zone")
}

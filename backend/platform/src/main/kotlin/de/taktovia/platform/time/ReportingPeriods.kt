package de.taktovia.platform.time

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId

/**
 * Betriebstag- und Periodenbasis in der `reporting_timezone` (LH-6.19-NFR-012, WP-M5-07). Grenzen sind absolute Zeitpunkte `[from, to)`;
 * ein Tag mit Zeitumstellung ist 23 oder 25 Stunden lang, ein Monat summiert die tatsächlichen Tage. Wer Zeiträume berechnet, nutzt
 * diese Bausteine, nie `plusHours(24)` oder Ortszeitdifferenzen.
 */
object ReportingPeriods {
    /** Betriebstag `day` mit Beginn `dayStart` (Planungsdefault Mitternacht) in `zone`. Beginn in der Frühjahrslücke rückt auf die erste gültige Zeit danach. */
    fun operatingDay(day: LocalDate, zone: ZoneId, dayStart: LocalTime = LocalTime.MIDNIGHT): ValidityInterval =
        ValidityInterval(startOf(day, zone, dayStart), startOf(day.plusDays(1), zone, dayStart))

    /** Kalendermonat in `zone` als `[Monatsbeginn, nächster Monatsbeginn)`. */
    fun month(month: YearMonth, zone: ZoneId): ValidityInterval =
        ValidityInterval(startOf(month.atDay(1), zone, LocalTime.MIDNIGHT), startOf(month.plusMonths(1).atDay(1), zone, LocalTime.MIDNIGHT))

    /** Betriebstag, zu dem `instant` gehört, zusammen mit seinen Grenzen. */
    fun dayOf(instant: Instant, zone: ZoneId, dayStart: LocalTime = LocalTime.MIDNIGHT): ValidityInterval =
        operatingDay(OperatingDay.of(instant, zone, dayStart), zone, dayStart)

    private fun startOf(day: LocalDate, zone: ZoneId, dayStart: LocalTime): Instant = day.atTime(dayStart).atZone(zone).toInstant()   // java.time schiebt Lücken vorwärts
}

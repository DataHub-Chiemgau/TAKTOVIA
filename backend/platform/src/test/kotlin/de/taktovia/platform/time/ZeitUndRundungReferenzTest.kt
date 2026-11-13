package de.taktovia.platform.time

import de.taktovia.platform.problem.ViolationCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId

/**
 * Referenztestkatalog Zeit (LH-6.19-NFR-012, WP-M5-07, Kap. 05 §5.1): feste Eingaben, feste Erwartungen, kein Systemzonen- und Uhrbezug.
 * Katalog der Rundung: `de.taktovia.platform.money.ZeitUndRundungReferenzTest`.
 */
class ZeitUndRundungReferenzTest {
    private val berlin = ZoneId.of("Europe/Berlin")

    @Test
    fun dst_umstellung_25_10_2026_zwei_zeitpunkte() {
        val sommer = ZonedMoments.parse("2026-10-25T02:30:00+02:00", "Europe/Berlin").orThrow()
        val winter = ZonedMoments.parse("2026-10-25T02:30:00+01:00", "Europe/Berlin").orThrow()
        assertEquals(Instant.parse("2026-10-25T00:30:00Z"), sommer.instant)
        assertEquals(Instant.parse("2026-10-25T01:30:00Z"), winter.instant)
        assertEquals(Duration.ofHours(1), Durations.between(sommer.instant, winter.instant))
        assertEquals("2026-10-25T02:30:00+02:00", sommer.toRfc3339()); assertEquals("2026-10-25T02:30:00+01:00", winter.toRfc3339())
        // der Betriebstag der Umstellung ist 25 Stunden lang, der der Frühjahrsumstellung 23 Stunden; Monat und Periodenbasis folgen den absoluten Grenzen
        val herbst = ReportingPeriods.operatingDay(LocalDate.parse("2026-10-25"), berlin)
        assertEquals(Instant.parse("2026-10-24T22:00:00Z"), herbst.from); assertEquals(Instant.parse("2026-10-25T23:00:00Z"), herbst.to); assertEquals(Duration.ofHours(25), herbst.duration)
        assertEquals(Duration.ofHours(23), ReportingPeriods.operatingDay(LocalDate.parse("2026-03-29"), berlin).duration)
        assertEquals(Duration.ofHours(24), ReportingPeriods.operatingDay(LocalDate.parse("2026-07-01"), berlin).duration)
        assertEquals(Duration.ofHours(31 * 24 + 1), ReportingPeriods.month(YearMonth.parse("2026-10"), berlin).duration)   // Oktober 2026: 31 Tage + 1 h
        assertEquals(Duration.ofHours(31 * 24 - 1), ReportingPeriods.month(YearMonth.parse("2026-03"), berlin).duration)   // März 2026: 31 Tage - 1 h
        // beide Herbst-02:30 gehören demselben Betriebstag; Grenzen sind links geschlossen, rechts offen
        assertEquals(herbst, ReportingPeriods.dayOf(sommer.instant, berlin)); assertEquals(herbst, ReportingPeriods.dayOf(winter.instant, berlin))
        assertTrue(herbst.from in herbst && herbst.to!! !in herbst)
        assertEquals(LocalDate.parse("2026-10-26"), OperatingDay.of(herbst.to!!, berlin))
        // die Statistikzone bestimmt den Tag: derselbe Zeitpunkt, anderer Betriebstag
        assertEquals(LocalDate.parse("2026-10-25"), OperatingDay.of(Instant.parse("2026-10-24T23:30:00Z"), berlin))
        assertEquals(LocalDate.parse("2026-10-24"), OperatingDay.of(Instant.parse("2026-10-24T23:30:00Z"), ZoneId.of("UTC")))
    }

    @Test
    fun fruehjahrsluecke_und_zeit_ohne_offset_abgelehnt() {
        for (offset in listOf("+01:00", "+02:00"))
            assertEquals(listOf("/desired_at" to ViolationCode.LOCAL_TIME_NONEXISTENT), ZonedMoments.parse("2026-03-29T02:30:00$offset", "Europe/Berlin").violations.map { it.pointer to it.code })
        assertEquals(LocalResolution.Gap, ZonedMoments.resolve(LocalDateTime.parse("2026-03-29T02:30:00"), berlin))
        // Zeit ohne Offset ist kein Zeitpunkt, auch wenn die Ortszeit eindeutig wäre
        for (t in listOf("2026-10-25T02:30:00", "2026-07-01T12:00:00"))
            assertEquals(listOf("/at" to ViolationCode.OFFSET_REQUIRED), Rfc3339.parse(t, "/at").violations.map { it.pointer to it.code }, t)
        // doppelte Ortszeit ohne Offset: nicht geraten, sondern abgewiesen
        assertEquals(listOf("/at" to ViolationCode.LOCAL_TIME_AMBIGUOUS), ZonedMoments.fromLocal(LocalDateTime.parse("2026-10-25T02:30:00"), berlin, "/at").violations.map { it.pointer to it.code })
    }

    @Test
    fun schichtdauer_ueber_umstellung_aus_absoluten_zeitpunkten() {
        // Nachtschicht 24.10. 22:00 bis 25.10. 06:00 Ortszeit (Herbst): 9 Stunden, nicht 8
        val von = ZonedMoments.parse("2026-10-24T22:00:00+02:00", "Europe/Berlin").orThrow()
        val bis = ZonedMoments.parse("2026-10-25T06:00:00+01:00", "Europe/Berlin").orThrow()
        assertEquals(Duration.ofHours(9), Durations.between(von.instant, bis.instant))
        assertEquals(Duration.ofHours(8), Duration.between(von.local, bis.local), "die Ortszeit-Differenz wäre falsch")
        // Frühjahr: 28.03. 22:00 bis 29.03. 06:00 Ortszeit = 7 Stunden
        val v2 = ZonedMoments.parse("2026-03-28T22:00:00+01:00", "Europe/Berlin").orThrow()
        val b2 = ZonedMoments.parse("2026-03-29T06:00:00+02:00", "Europe/Berlin").orThrow()
        assertEquals(Duration.ofHours(7), Durations.between(v2.instant, b2.instant))
        // dieselbe Dauer in jeder Zone, weil sie aus Zeitpunkten entsteht
        assertEquals(Duration.ofHours(9), Durations.between(von.instant, bis.instant))
        assertEquals(Duration.ofHours(9), Duration.between(von.instant.atZone(ZoneId.of("Asia/Tokyo")), bis.instant.atZone(ZoneId.of("Asia/Tokyo"))))
    }
}

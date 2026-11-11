package de.taktovia.platform.time

import de.taktovia.platform.TestDatabase
import de.taktovia.platform.problem.ViolationCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** LH-6.1-SYS-006: Zeiten mit Zeitzonenbezug, DST-Lücke abgelehnt, doppelte Ortszeit verlangt eindeutigen Offset. */
class DstTimeTest {
    private val berlin = ZoneId.of("Europe/Berlin")

    private fun parse(desired: String?, zone: String?) = ZonedMoments.parse(desired, zone)
    private fun codes(c: Checked<*>) = c.violations.map { it.pointer to it.code }

    @Test
    fun doppelte_ortszeit_zwei_eindeutige_zeitpunkte() {
        // 25.10.2026 02:30 Europe/Berlin kommt zweimal vor: +02:00 (Sommerzeit) und +01:00 (Winterzeit)
        val summer = parse("2026-10-25T02:30:00+02:00", "Europe/Berlin").orThrow()
        val winter = parse("2026-10-25T02:30:00+01:00", "Europe/Berlin").orThrow()
        assertEquals(Instant.parse("2026-10-25T00:30:00Z"), summer.instant)
        assertEquals(Instant.parse("2026-10-25T01:30:00Z"), winter.instant)
        assertNotEquals(summer.instant, winter.instant)
        assertEquals(Duration.ofHours(1), Durations.between(summer.instant, winter.instant))
        // unterscheidbar zurückgegeben: gleiche Ortszeit, verschiedener Offset im Antworttext
        assertEquals("2026-10-25T02:30:00+02:00", summer.toRfc3339())
        assertEquals("2026-10-25T02:30:00+01:00", winter.toRfc3339())
        assertEquals(summer.local, winter.local)
        // Auflösung einer lokalen Eingabe ohne Offset: mehrdeutig, beide Kandidaten werden angeboten (Offsetwahl in der Oberfläche)
        val r = ZonedMoments.resolve(LocalDateTime.parse("2026-10-25T02:30:00"), berlin) as LocalResolution.Ambiguous
        assertEquals(summer, r.earlier); assertEquals(winter, r.later)
        assertEquals(listOf("/at" to ViolationCode.LOCAL_TIME_AMBIGUOUS), codes(ZonedMoments.fromLocal(LocalDateTime.parse("2026-10-25T02:30:00"), berlin, "/at")))
        assertEquals(winter, ZonedMoments.fromLocal(LocalDateTime.parse("2026-10-25T02:30:00"), berlin, ZoneOffset.ofHours(1), "/at").orThrow())
        // gespeichert als timestamptz in der echten Datenbank: zwei verschiedene Zeilen, unterscheidbar gelesen
        TestDatabase.require()
        TestDatabase.admin().connection.use { c ->
            c.createStatement().use { it.execute("CREATE TEMP TABLE zeit_probe(id int PRIMARY KEY, at timestamptz NOT NULL, zone text NOT NULL)") }
            c.prepareStatement("INSERT INTO zeit_probe VALUES (?, ?, ?)").use { ps ->
                for ((id, m) in listOf(1 to summer, 2 to winter)) { ps.setInt(1, id); ps.setObject(2, m.instant.atOffset(ZoneOffset.UTC)); ps.setString(3, m.zone.id); ps.executeUpdate() }
            }
            c.createStatement().use { st ->
                st.executeQuery("SELECT id, at, zone, (at AT TIME ZONE zone) AS local FROM zeit_probe ORDER BY id").use { rs ->
                    val back = buildList { while (rs.next()) add(Triple(rs.getInt(1), rs.getObject(2, OffsetDateTime::class.java).toInstant(), rs.getString(4))) }
                    assertEquals(listOf(summer.instant, winter.instant), back.map { it.second }, "zwei verschiedene Zeitpunkte gespeichert")
                    assertEquals(back[0].third, back[1].third, "die Ortszeit ist gleich; nur der gespeicherte Zeitpunkt unterscheidet")
                }
            }
        }
    }

    @Test
    fun dst_luecke_und_fehlender_offset_werden_abgewiesen() {
        // Frühjahrsumstellung: 29.03.2026 02:30 existiert in Europe/Berlin nicht (02:00 springt auf 03:00)
        for (offset in listOf("+01:00", "+02:00")) {
            assertEquals(listOf("/desired_at" to ViolationCode.LOCAL_TIME_NONEXISTENT), codes(parse("2026-03-29T02:30:00$offset", "Europe/Berlin")), offset)
        }
        assertEquals(LocalResolution.Gap, ZonedMoments.resolve(LocalDateTime.parse("2026-03-29T02:30:00"), berlin))
        assertEquals(listOf("/x" to ViolationCode.LOCAL_TIME_NONEXISTENT), codes(ZonedMoments.fromLocal(LocalDateTime.parse("2026-03-29T02:30:00"), berlin, "/x")))
        assertTrue(parse("2026-03-29T01:59:59+01:00", "Europe/Berlin").ok)
        assertTrue(parse("2026-03-29T03:00:00+02:00", "Europe/Berlin").ok)
        // ohne Offset: Feldfehler OFFSET_REQUIRED, auch bei mehrdeutiger und bei eindeutiger Ortszeit
        for (t in listOf("2026-10-25T02:30:00", "2026-07-01T12:00:00", "2026-10-25 02:30:00", "2026-10-25T02:30:00.123")) {
            assertEquals(listOf("/desired_at" to ViolationCode.OFFSET_REQUIRED), codes(parse(t, "Europe/Berlin")), t)
        }
        // formal ungültig
        for (t in listOf("", "morgen", "2026-13-01T00:00:00Z", "2026-10-25T25:00:00Z", "2026-10-25T02:30Z", "2026-10-25T02:30:00+0200", "2026-02-30T00:00:00Z", "2026-12-31T23:59:60Z"))
            assertEquals(listOf("/desired_at" to ViolationCode.INVALID_FORMAT), codes(parse(t, "Europe/Berlin")), t)
        assertEquals(listOf("/desired_at" to ViolationCode.REQUIRED), codes(parse(null, "Europe/Berlin")))
        assertTrue(parse("2026-10-25T02:30:00Z", "UTC").ok)
    }

    @Test
    fun dauer_ueber_zeitumstellung_aus_absoluten_zeitpunkten() {
        // 01:00 bis 04:00 Ortszeit am 25.10.2026 (Herbstumstellung) = 4 Stunden, nicht 3
        val from = ZonedMoments.fromLocal(LocalDateTime.parse("2026-10-25T01:00:00"), berlin, "/a").orThrow()
        val to = ZonedMoments.fromLocal(LocalDateTime.parse("2026-10-25T04:00:00"), berlin, "/b").orThrow()
        assertEquals(Duration.ofHours(4), Durations.between(from.instant, to.instant))
        assertEquals(Duration.ofHours(3), Duration.between(from.local, to.local), "die Ortszeit-Differenz wäre falsch")
        // Frühjahr: 01:00 bis 04:00 Ortszeit am 29.03.2026 = 2 Stunden
        val f2 = ZonedMoments.fromLocal(LocalDateTime.parse("2026-03-29T01:00:00"), berlin, "/a").orThrow()
        val t2 = ZonedMoments.fromLocal(LocalDateTime.parse("2026-03-29T04:00:00"), berlin, "/b").orThrow()
        assertEquals(Duration.ofHours(2), Durations.between(f2.instant, t2.instant))
        // Gültigkeitsintervall links geschlossen, rechts offen
        val i = ValidityInterval(from.instant, to.instant)
        assertEquals(Duration.ofHours(4), i.duration)
        assertTrue(from.instant in i); assertFalse(to.instant in i)
        val next = ValidityInterval(to.instant, null)
        assertFalse(i.overlaps(next), "angrenzende Intervalle überlappen nicht"); assertFalse(next.overlaps(i))
        assertTrue(next.overlaps(ValidityInterval(to.instant.minusSeconds(1), to.instant.plusSeconds(1))))
        assertTrue(to.instant in next && to.instant.plusSeconds(10_000_000) in next, "offenes Ende")
        // Intervall mit Ende vor oder gleich Beginn wird abgewiesen
        assertEquals(listOf("/valid_to" to ViolationCode.INTERVAL_INVALID), codes(ValidityInterval.check(to.instant, to.instant)))
        assertEquals(listOf("/valid_to" to ViolationCode.INTERVAL_INVALID), codes(ValidityInterval.check(to.instant, from.instant)))
        assertEquals(listOf("/valid_to" to ViolationCode.INTERVAL_INVALID), codes(ValidityInterval.parse("2026-12-01T10:00:00+01:00", "2026-12-01T09:00:00Z")))
        assertEquals(listOf("/valid_from" to ViolationCode.OFFSET_REQUIRED), codes(ValidityInterval.parse("2026-12-01T10:00:00", null)))
        assertTrue(ValidityInterval.parse("2026-12-01T10:00:00+01:00", null).ok)
    }

    @Test
    fun zone_widerspricht_offset_und_unbekannte_zone_werden_abgewiesen() {
        // Offset passt nicht zur Zone: im Sommer hat Berlin +02:00, nicht +05:00 und nicht +01:00
        assertEquals(listOf("/time_zone" to ViolationCode.ZONE_OFFSET_MISMATCH), codes(parse("2026-07-01T12:00:00+05:00", "Europe/Berlin")))
        assertEquals(listOf("/time_zone" to ViolationCode.ZONE_OFFSET_MISMATCH), codes(parse("2026-07-01T12:00:00+01:00", "Europe/Berlin")))
        assertEquals(listOf("/time_zone" to ViolationCode.ZONE_OFFSET_MISMATCH), codes(parse("2026-07-01T12:00:00Z", "Europe/Berlin")))
        assertTrue(parse("2026-07-01T12:00:00+02:00", "Europe/Berlin").ok)
        assertTrue(parse("2026-12-01T12:00:00+01:00", "Europe/Berlin").ok)
        // Zonenwechsel: derselbe Zeitpunkt, andere lokale Darstellung
        val ny = parse("2026-07-01T06:00:00-04:00", "America/New_York").orThrow()
        val de = parse("2026-07-01T12:00:00+02:00", "Europe/Berlin").orThrow()
        assertEquals(ny.instant, de.instant); assertNotEquals(ny.local, de.local)
        // unbekannte, leere und unzulässig geformte Zonen
        for (z in listOf("Mars/Olympus", "Europe/Berlino", "CET", "EST", "+01:00", "UTC+1", "Etc/GMT-1", "europe/berlin", "Europe/Berlin ", "Europe/../etc"))
            assertEquals(listOf("/time_zone" to ViolationCode.ZONE_UNKNOWN), codes(parse("2026-07-01T12:00:00+02:00", z)), z)
        assertEquals(listOf("/time_zone" to ViolationCode.REQUIRED), codes(parse("2026-07-01T12:00:00+02:00", null)))
        assertEquals(listOf("/time_zone" to ViolationCode.REQUIRED), codes(parse("2026-07-01T12:00:00+02:00", " ")))
        // mehrere Verletzungen werden gemeinsam gemeldet
        assertEquals(setOf("/desired_at" to ViolationCode.OFFSET_REQUIRED, "/time_zone" to ViolationCode.ZONE_UNKNOWN), codes(parse("2026-07-01T12:00:00", "Mars/Olympus")).toSet())
        // lokale Eingabe mit falschem Offset
        assertEquals(listOf("/o" to ViolationCode.ZONE_OFFSET_MISMATCH), codes(ZonedMoments.fromLocal(LocalDateTime.parse("2026-07-01T12:00:00"), berlin, ZoneOffset.ofHours(1), "/o")))
    }

    @Test
    fun lokaler_betriebstag_in_dienstzone() {
        // 23:30 UTC am 24.10. ist 01:30 Ortszeit am 25.10. in Berlin: der Betriebstag ist der 25.10., nicht der 24.10.
        val t = Instant.parse("2026-10-24T23:30:00Z")
        assertEquals(LocalDate.parse("2026-10-25"), OperatingDay.of(t, berlin))
        assertEquals(LocalDate.parse("2026-10-24"), OperatingDay.of(t, ZoneId.of("UTC")))
        assertEquals(LocalDate.parse("2026-10-24"), OperatingDay.of(t, ZoneId.of("America/New_York")))
        // Betriebstagbeginn 03:00: 01:30 Ortszeit gehört noch zum Vortag
        assertEquals(LocalDate.parse("2026-10-24"), OperatingDay.of(t, berlin, LocalTime.of(3, 0)))
        // am Umstellungstag selbst
        assertEquals(LocalDate.parse("2026-10-25"), parse("2026-10-25T02:30:00+01:00", "Europe/Berlin").orThrow().operatingDay())
        assertEquals(LocalDate.parse("2026-10-25"), OperatingDay.of(Instant.parse("2026-10-25T22:59:59Z"), berlin))
        assertEquals(LocalDate.parse("2026-10-26"), OperatingDay.of(Instant.parse("2026-10-25T23:00:00Z"), berlin))
        // injizierbare Serveruhr: feste Uhr liefert deterministische Ergebnisse
        val clock = FixedTimeSource(Instant.parse("2026-03-28T23:30:00Z"))
        assertEquals(LocalDate.parse("2026-03-29"), OperatingDay.today(clock, berlin))
        clock.advance(Duration.ofHours(1))
        assertEquals(LocalDate.parse("2026-03-29"), OperatingDay.today(clock, berlin))
        assertEquals(Instant.parse("2026-03-29T00:30:00Z"), clock.now())
        assertTrue(SystemTimeSource.now().isAfter(Instant.parse("2026-01-01T00:00:00Z")))
    }
}

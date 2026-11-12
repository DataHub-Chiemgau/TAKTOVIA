package de.taktovia.stafftime

import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.platform.time.ZonedMoments
import de.taktovia.stafftime.api.LocalTimeRejected
import de.taktovia.stafftime.api.RecordTimeEvent
import de.taktovia.stafftime.api.TimeEventKind
import de.taktovia.stafftime.api.TimeEventSource
import de.taktovia.stafftime.api.WorkTime
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M4-10 / LH-6.24-TIM-005 (Zeitrechnung über Mitternacht, Zeitzone, DST). Sollwerttabelle (Europe/Berlin):
 *
 * | Fall                                   | lokal                         | absolute Dauer | Bezugstag  |
 * |----------------------------------------|-------------------------------|----------------|------------|
 * | Nachtschicht über Herbstumstellung     | 24.10. 22:00 – 25.10. 06:00   | 9 h            | 24.10.2026 |
 * | Nachtschicht über Frühjahrsumstellung  | 28.03. 22:00 – 29.03. 06:00   | 7 h            | 28.03.2026 |
 * | Schicht über Mitternacht (ohne DST)    | 10.06. 22:00 – 11.06. 06:00   | 8 h            | 10.06.2026 |
 * | Zeitzonenwechsel (Berlin -> Lissabon)  | Beginn 08:00 Berlin, Ende 08:00 Lissabon | 1 h länger als lokale Differenz | Berlin-Tag |
 *
 * Bezugstag = lokales Datum des Beginns (Vorschlag des Vertrags, OP-08 vom Fachbereich zu bestätigen). Ortszeit in der Lücke (29.03.2026 02:30) wird abgelehnt;
 * 25.10.2026 02:30 kommt zweimal vor (+02:00 und +01:00) und wird mit Offset angezeigt. Das Parallelstück läuft gegen die echte Datenbank.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ZeitrechnungDstTest {
    private val berlin = ZoneId.of("Europe/Berlin")
    private lateinit var fx: StaffFixture

    @BeforeAll fun up() { fx = StaffFixture() }
    @AfterAll fun down() { fx.close() }

    private fun ldt(s: String) = LocalDateTime.parse(s)

    @Test
    fun nachtschicht_herbstumstellung_9_stunden_absolut() {
        val start = WorkTime.resolveLocal(ldt("2026-10-24T22:00"), berlin)
        val end = WorkTime.resolveLocal(ldt("2026-10-25T06:00"), berlin)
        val e = WorkTime.evaluate(start, end, berlin)
        assertEquals(Duration.ofHours(9), e.duration, "9 h absolut, obwohl die Ortszeit-Differenz 8 h ist")
        assertEquals(ldt("2026-10-24T22:00"), e.start.local); assertEquals(ldt("2026-10-25T06:00"), e.end.local, "lokale Darstellung bleibt 22:00-06:00")
        assertEquals(LocalDate.of(2026, 10, 24), e.referenceDay); assertTrue(e.crossesMidnight)
        assertEquals("2026-10-24T22:00:00+02:00", e.start.localWithOffset()); assertEquals("2026-10-25T06:00:00+01:00", e.end.localWithOffset())
    }

    @Test
    fun nachtschicht_fruehjahrsumstellung_7_stunden_absolut() {
        val start = WorkTime.resolveLocal(ldt("2026-03-28T22:00"), berlin)
        val end = WorkTime.resolveLocal(ldt("2026-03-29T06:00"), berlin)
        val e = WorkTime.evaluate(start, end, berlin)
        assertEquals(Duration.ofHours(7), e.duration)
        assertEquals(ldt("2026-03-28T22:00"), e.start.local); assertEquals(ldt("2026-03-29T06:00"), e.end.local)
        assertEquals(LocalDate.of(2026, 3, 28), e.referenceDay)
    }

    @Test
    fun schicht_ueber_mitternacht_dauer_und_bezugstag() {
        val e = WorkTime.evaluate(WorkTime.resolveLocal(ldt("2026-06-10T22:00"), berlin), WorkTime.resolveLocal(ldt("2026-06-11T06:00"), berlin), berlin)
        assertEquals(Duration.ofHours(8), e.duration)
        assertEquals(LocalDate.of(2026, 6, 10), e.referenceDay, "Bezugstag = Tag des Beginns")
        assertTrue(e.crossesMidnight)
        assertFalse(WorkTime.evaluate(WorkTime.resolveLocal(ldt("2026-06-10T08:00"), berlin), WorkTime.resolveLocal(ldt("2026-06-10T16:00"), berlin), berlin).crossesMidnight)
        // Zeitzonenwechsel: Beginn 08:00 Berlin (06:00Z), Ende 08:00 Lissabon (07:00Z im Sommer) -> absolut 1 h, Ortszeit-Differenz wäre 0 h
        val lisbon = ZoneId.of("Europe/Lisbon")
        val z = WorkTime.evaluate(WorkTime.resolveLocal(ldt("2026-06-10T08:00"), berlin), WorkTime.resolveLocal(ldt("2026-06-10T08:00"), lisbon), berlin, lisbon)
        assertEquals(Duration.ofHours(1), z.duration)
        assertEquals(LocalDate.of(2026, 6, 10), z.referenceDay)
        // Berichtstag in anderer reporting_timezone: 23:30 Berlin am 10.06. ist 22:30 Lissabon, 21:30Z -> in Lissabon noch der 10.06.; 00:30 Berlin am 11.06. ist 23:30 Lissabon am 10.06.
        val i = WorkTime.resolveLocal(ldt("2026-06-11T00:30"), berlin)
        assertEquals(LocalDate.of(2026, 6, 11), WorkTime.present(i, berlin).operatingDay); assertEquals(LocalDate.of(2026, 6, 10), WorkTime.reportingDay(i, lisbon))
        assertThrows(IllegalArgumentException::class.java) { WorkTime.duration(Instant.parse("2026-01-01T10:00:00Z"), Instant.parse("2026-01-01T09:00:00Z")) }
    }

    @Test
    fun lokale_zeit_in_dst_luecke_wird_abgelehnt() {
        assertThrows(LocalTimeRejected.Gap::class.java) { WorkTime.resolveLocal(ldt("2026-03-29T02:30"), berlin) }
        assertThrows(LocalTimeRejected.Gap::class.java) { WorkTime.resolveLocal(ldt("2026-03-29T02:30"), berlin, ZoneOffset.ofHours(1)) }
        assertThrows(LocalTimeRejected.Ambiguous::class.java) { WorkTime.resolveLocal(ldt("2026-10-25T02:30"), berlin) }
        assertThrows(LocalTimeRejected.OffsetMismatch::class.java) { WorkTime.resolveLocal(ldt("2026-06-10T12:00"), berlin, ZoneOffset.ofHours(1)) }
        // API-Weg: Zeitpunkt mit Offset, dessen Ortszeit in der Lücke liegt, ist ein Feldfehler (422), keine Verschiebung
        val gap = ZonedMoments.parse("2026-03-29T02:30:00+01:00", "Europe/Berlin", "/occurred_at", "/time_zone")
        assertFalse(gap.ok); assertEquals(ViolationCode.LOCAL_TIME_NONEXISTENT, gap.violations.single().code)
        val ex = assertThrows(ProblemException::class.java) { gap.orThrow() }
        assertEquals(de.taktovia.platform.problem.ErrorCode.VALIDATION_FAILED, ex.code); assertEquals("/occurred_at", ex.violations.single().pointer)
        assertTrue(ZonedMoments.parse("2026-03-29T03:30:00+02:00", "Europe/Berlin").ok)
    }

    @Test
    fun doppelte_ortszeit_wird_mit_offset_dargestellt() {
        val first = WorkTime.resolveLocal(ldt("2026-10-25T02:30"), berlin, ZoneOffset.ofHours(2))
        val second = WorkTime.resolveLocal(ldt("2026-10-25T02:30"), berlin, ZoneOffset.ofHours(1))
        assertEquals(Duration.ofHours(1), Duration.between(first, second))
        val a = WorkTime.present(first, berlin); val b = WorkTime.present(second, berlin)
        assertTrue(a.ambiguous && b.ambiguous)
        assertEquals("2026-10-25T02:30:00+02:00", a.localWithOffset()); assertEquals("2026-10-25T02:30:00+01:00", b.localWithOffset())
        assertNotEquals(a.localWithOffset(), b.localWithOffset())
        assertFalse(WorkTime.present(WorkTime.resolveLocal(ldt("2026-10-25T03:30"), berlin), berlin).ambiguous)
    }

    @Test
    fun zwei_stempel_0230_utc_plus_2_und_plus_1_parallel_beide_gespeichert() {
        val w = fx.world(); val ctx = StaffM4Support.employee(w.tenant, w.subject)
        val t1 = OffsetDateTime.parse("2026-10-25T02:30:00+02:00").toInstant(); val t2 = OffsetDateTime.parse("2026-10-25T02:30:00+01:00").toInstant()
        assertNotEquals(t1, t2)
        val cmds = listOf(
            RecordTimeEvent(UUID.randomUUID(), TimeEventKind.WORK_START, t1, t1, TimeEventSource.APP, null, berlin),
            RecordTimeEvent(UUID.randomUUID(), TimeEventKind.WORK_END, t2, t2, TimeEventSource.APP, null, berlin),
        )
        val pool = Executors.newFixedThreadPool(2); val gate = CountDownLatch(1)
        try {
            val futures = cmds.map { c -> pool.submit<Boolean> { gate.await(); fx.staff.timeEvents.record(ctx, c).created } }
            gate.countDown()
            assertTrue(futures.all { it.get(30, TimeUnit.SECONDS) }, "beide Stempel werden neu gespeichert, keiner überschreibt den anderen")
        } finally { pool.shutdownNow() }
        val rows = fx.staff.timeEvents.list(ctx, null, null, null, null, 50, null)
        assertEquals(setOf(t1, t2), rows.map { it.occurredAt }.toSet(), "zwei eindeutige Zeitpunkte, keine stille Auswahl oder Verschiebung")
        assertEquals(setOf("2026-10-25T02:30:00+02:00", "2026-10-25T02:30:00+01:00"), rows.map { it.occurredLocal.localWithOffset() }.toSet())
        assertTrue(rows.all { it.timeZone == berlin && it.occurredLocal.ambiguous })
        // Intervall: 1 h absolut, lokal 02:30 - 02:30 (Offset unterscheidet)
        val d = fx.staff.timeEvents.intervals(ctx, null, t1.minusSeconds(60), t2.plusSeconds(60))
        assertEquals(3600L, d.intervals.single().netSeconds); assertEquals(berlin, d.intervals.single().zone)
    }
}

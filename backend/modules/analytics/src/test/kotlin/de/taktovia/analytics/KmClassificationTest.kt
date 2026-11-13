package de.taktovia.analytics

import de.taktovia.analytics.api.Completeness
import de.taktovia.analytics.api.KmCategory
import de.taktovia.analytics.api.KmClassification
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Klassifikation der Fahrzeugkilometer (WP-M4-42, FCO-005) gegen die echte Datenbank. Die dokumentierte Musterfahrt (Vertrag LH-I.2-FCO-005) liegt am Betriebstag 2026-06-01:
 * Fahrdienst 06:00-14:00 mit Fahrtenplan (4 Abschnitte leer, 6 mit Fahrgast, 4 leer, dann eine Lücke von sechs Minuten), Überführungsschicht 15:00-16:00 (3 Abschnitte),
 * Werkstattschicht 18:00-19:00 (3 Abschnitte), danach 2 Abschnitte ohne Schicht. Die erwarteten Meter berechnet die Fixture unabhängig von der Klassifikation.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KmClassificationTest {
    private val fx = KmFixture()
    private val day = LocalDate.of(2026, 6, 1)
    private val read = setOf(KmClassification.SCOPE_READ)

    @AfterAll fun close() = fx.close()

    private class Muster(val w: KmFixture.World, val service: UUID, val expected: Map<KmCategory, Long>, val total: Long)

    /** Legt die Musterfahrt an; Schritt = fortlaufender Index der Position (Breitengrad), Zeiten in Minuten. */
    private fun musterfahrt(): Muster {
        val w = fx.world()
        val s1 = fx.shift(w, "2026-06-01T06:00:00Z", "2026-06-01T14:00:00Z")
        val s2 = fx.shift(w, "2026-06-01T15:00:00Z", "2026-06-01T16:00:00Z", function = "Überführung", plan = false)
        val s3 = fx.shift(w, "2026-06-01T18:00:00Z", "2026-06-01T19:00:00Z", function = "Werkstattfahrt", plan = false)
        val booking = UUID.randomUUID()
        fx.driverEvent(w, s1, booking, "CHECK_IN", "2026-06-01T08:04:00Z"); fx.driverEvent(w, s1, booking, "CHECK_OUT", "2026-06-01T08:10:00Z")
        val times = (0..14).map { "2026-06-01T08:%02d:00Z".format(it) } + "2026-06-01T08:20:00Z" +
            (0..3).map { "2026-06-01T15:%02d:00Z".format(it) } + (0..3).map { "2026-06-01T18:%02d:00Z".format(it) } + (0..2).map { "2026-06-01T20:%02d:00Z".format(it) }
        val shiftOf = times.map { t -> when { t.startsWith("2026-06-01T15") -> s2; t.startsWith("2026-06-01T18") || t.startsWith("2026-06-01T20") -> s3; else -> s1 } }
        times.forEachIndexed { i, t -> fx.position(w, shiftOf[i], t, i) }
        // Abschnitt i liegt zwischen Position i und i+1: Kategorie nach Vertrag
        val cats = mutableListOf<KmCategory>()
        repeat(4) { cats += KmCategory.EMPTY }; repeat(6) { cats += KmCategory.PASSENGER }; repeat(4) { cats += KmCategory.EMPTY }
        cats += KmCategory.UNASSIGNED            // Lücke 08:14 bis 08:20
        cats += KmCategory.UNASSIGNED            // 08:20 bis 15:00: Lücke (größer als fünf Minuten)
        repeat(3) { cats += KmCategory.TRANSFER }
        cats += KmCategory.UNASSIGNED            // 15:03 bis 18:00: Lücke
        repeat(3) { cats += KmCategory.WORKSHOP }
        cats += KmCategory.UNASSIGNED            // 18:03 bis 20:00: Lücke
        repeat(2) { cats += KmCategory.UNASSIGNED }   // 20:00 bis 20:02 ohne Schicht
        assertEquals(times.size - 1, cats.size)
        val expected = KmCategory.entries.associateWith { c -> cats.indices.filter { cats[it] == c }.sumOf { fx.meters(it, it + 1) } }
        return Muster(w, s1, expected, cats.indices.sumOf { fx.meters(it, it + 1) })
    }

    @Test fun musterfahrt_summe_der_kategorien_gleich_gesamtkilometer() {
        val m = musterfahrt()
        val run = fx.km.classify(m.w.tenant, m.w.vehicle, day)
        assertTrue(run.created)
        KmCategory.entries.forEach { assertEquals(m.expected.getValue(it), run.byCategory.getValue(it), "Kategorie $it") }
        assertEquals(m.total, run.totalKmThousandths)
        assertEquals(run.totalKmThousandths, run.byCategory.values.sum(), "Gesamtkilometer = vier Kategorien + nicht zuordenbar (exakt, ganzzahlig)")
        assertTrue(run.byCategory.getValue(KmCategory.PASSENGER) in 6 * 100L..6 * 120L, "sechs Abschnitte zu je etwa 111 m")
        assertTrue(run.eventsComplete)
        // Auswertung über die API-Schicht: alle fünf Kategorien, UNASSIGNED separat
        val s = fx.km.summary(fx.ctx(m.w.tenant, read), m.w.vehicle, day, day)
        assertEquals(m.expected, s.byCategory); assertEquals(m.total, s.totalKmThousandths); assertEquals(Completeness.COMPLETE, s.completeness)
        assertEquals(1, s.days.size); assertEquals(KmClassification.RULE_VERSION, s.definitionVersion)
        // Wiederholung mit demselben Eingangszustand: kein zweiter Lauf
        val again = fx.km.classify(m.w.tenant, m.w.vehicle, day)
        assertFalse(again.created); assertEquals(run.runId, again.runId)
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.vehicle_km_run WHERE vehicle_id = ?", m.w.vehicle))
        // Tagesjob: findet das Fahrzeug über seine Positionen, verändert den unveränderten Zustand nicht
        val job = fx.km.classifyDay(m.w.tenant, day)
        assertEquals(listOf(run.runId), job.map { it.runId }); assertFalse(job.single().created)
        // fehlendes Leserecht: 403
        assertEquals(ErrorCode.FORBIDDEN, assertThrows<ProblemException> { fx.km.summary(fx.ctx(m.w.tenant, emptySet()), m.w.vehicle, day, day) }.code)
    }

    @Test fun luecke_in_positionsdaten_erhoeht_nicht_zuordenbar() {
        // zwei gleiche Fahrten, in der zweiten fehlt die Position um 08:03: der Abschnitt über die Lücke wird UNASSIGNED, die Kategorien werden nicht aufgefüllt
        fun fahrt(withGap: Boolean): de.taktovia.analytics.api.KmRunView {
            val w = fx.world(); val s = fx.shift(w, "2026-06-01T06:00:00Z", "2026-06-01T14:00:00Z")
            (0..8).filter { !(withGap && it in 3..8) || it == 8 }.forEach { fx.position(w, s, "2026-06-01T08:%02d:00Z".format(it), it) }
            return fx.km.classify(w.tenant, w.vehicle, day)
        }
        val full = fahrt(false); val gap = fahrt(true)
        assertEquals(0L, full.byCategory.getValue(KmCategory.UNASSIGNED))
        assertEquals(full.totalKmThousandths, full.byCategory.getValue(KmCategory.EMPTY))
        assertEquals(fx.meters(2, 8), gap.byCategory.getValue(KmCategory.UNASSIGNED), "die Strecke über die Lücke (08:02 bis 08:08) bleibt nicht zuordenbar")
        assertEquals(fx.meters(0, 2), gap.byCategory.getValue(KmCategory.EMPTY))
        assertEquals(gap.totalKmThousandths, gap.byCategory.values.sum())
    }

    @Test fun fahrzeug_ohne_fahrtenplan_alles_nicht_zuordenbar() {
        val w = fx.world(); val s = fx.shift(w, "2026-06-01T06:00:00Z", "2026-06-01T14:00:00Z", plan = false)
        (0..5).forEach { fx.position(w, s, "2026-06-01T09:%02d:00Z".format(it), it) }
        val r = fx.km.classify(w.tenant, w.vehicle, day)
        assertTrue(r.totalKmThousandths > 0)
        assertEquals(r.totalKmThousandths, r.byCategory.getValue(KmCategory.UNASSIGNED), "ohne Fahrtenplan wird keine Kategorie erfunden")
        KmCategory.entries.filter { it != KmCategory.UNASSIGNED }.forEach { assertEquals(0L, r.byCategory.getValue(it)) }
        // Fahrzeug ganz ohne Positionen und Schichten: leerer Lauf (null Kilometer), Auswertung ohne Zeile
        val empty = fx.world()
        val e = fx.km.classify(empty.tenant, empty.vehicle, day)
        assertEquals(0L, e.totalKmThousandths); assertEquals(0, e.positionCount)
    }

    @Test fun nachtraegliche_daten_aendern_nur_ueber_neuen_datenstand() {
        val w = fx.world(); val s = fx.shift(w, "2026-06-01T06:00:00Z", "2026-06-01T14:00:00Z")
        (0..3).forEach { fx.position(w, s, "2026-06-01T08:%02d:00Z".format(it), it) }
        val first = fx.km.classify(w.tenant, w.vehicle, day)
        // nachträglich eingetroffene Position (Messung 08:04, Eingang erst später): neuer Lauf mit neuem Datenstand, der erste Lauf bleibt unverändert lesbar
        fx.position(w, s, "2026-06-01T08:04:00Z", 4, receivedAt = java.time.Instant.now().plusSeconds(3600).toString())   // nach dem Eingang des Fahrtenplans
        val second = fx.km.classify(w.tenant, w.vehicle, day)
        assertTrue(second.created); assertNotEquals(first.runId, second.runId)
        assertTrue(second.dataAsOf.isAfter(first.dataAsOf)); assertTrue(second.totalKmThousandths > first.totalKmThousandths)
        assertEquals(first.totalKmThousandths, fx.strings("SELECT total_km_thousandths FROM taktovia.vehicle_km_run WHERE id = ?", first.runId).single().toLong())
        assertEquals(second.totalKmThousandths, fx.km.summary(fx.ctx(w.tenant, read), w.vehicle, day, day).totalKmThousandths, "aktuell ist der Lauf mit dem jüngsten Datenstand")
        // konkurrierend: zehn gleichzeitige Läufe für denselben Zustand erzeugen genau einen Lauf, alle liefern dasselbe Ergebnis
        val w2 = fx.world(); val s2 = fx.shift(w2, "2026-06-01T06:00:00Z", "2026-06-01T14:00:00Z")
        (0..5).forEach { fx.position(w2, s2, "2026-06-01T08:%02d:00Z".format(it), it) }
        val pool = Executors.newFixedThreadPool(10)
        try {
            val runs = pool.invokeAll(List(10) { Callable { fx.km.classify(w2.tenant, w2.vehicle, day) } }).map { it.get() }
            assertEquals(1, runs.map { it.runId }.toSet().size)
            assertEquals(1, runs.count { it.created })
            assertEquals(1, fx.count("SELECT count(*) FROM taktovia.vehicle_km_run WHERE vehicle_id = ?", w2.vehicle))
        } finally { pool.shutdown() }
    }

    @Test fun fremdes_fahrzeug_ergibt_404_und_ungueltiger_zeitraum_422() {
        val a = fx.world(); val b = fx.world()
        val sb = fx.shift(b, "2026-06-01T06:00:00Z", "2026-06-01T14:00:00Z"); (0..3).forEach { fx.position(b, sb, "2026-06-01T08:%02d:00Z".format(it), it) }
        fx.km.classify(b.tenant, b.vehicle, day)
        // Mandant A sieht das Fahrzeug und die Läufe von B nicht (RLS): Klassifikation und Auswertung ergeben 404, die Gesamtauswertung von A bleibt leer
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { fx.km.classify(a.tenant, b.vehicle, day) }.code)
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { fx.km.summary(fx.ctx(a.tenant, read), b.vehicle, day, day) }.code)
        val all = fx.km.summary(fx.ctx(a.tenant, read), null, day, day)
        assertEquals(0L, all.totalKmThousandths); assertTrue(all.days.isEmpty()); assertEquals(null, all.asOf)
        assertEquals(1, fx.km.summary(fx.ctx(b.tenant, read), null, day, day).days.size)
        // ungültiger Zeitraum: Ende vor Beginn, mehr als 366 Tage
        assertEquals(ErrorCode.VALIDATION_FAILED, assertThrows<ProblemException> { fx.km.summary(fx.ctx(a.tenant, read), null, day, day.minusDays(1)) }.code)
        assertEquals(ErrorCode.VALIDATION_FAILED, assertThrows<ProblemException> { fx.km.summary(fx.ctx(a.tenant, read), null, day, day.plusDays(366)) }.code)
    }
}

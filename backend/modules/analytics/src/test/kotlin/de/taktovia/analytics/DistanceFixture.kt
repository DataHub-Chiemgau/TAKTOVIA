package de.taktovia.analytics

import de.taktovia.analytics.api.AnalyticsApi
import de.taktovia.analytics.api.KmCategory
import de.taktovia.analytics.api.MetricFilter
import de.taktovia.analytics.api.MetricQuery
import de.taktovia.analytics.api.MetricValue
import de.taktovia.analytics.api.Metrics
import de.taktovia.analytics.api.ReportingPeriod
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Testhilfe des Clusters wave15-c3 (WP-M4-56/-57): Kilometer- und Auslastungskennzahlen durch die echte Pipeline (Positionen, Schichten, Fahrerereignisse -> Kilometerklassifikation -> Kennzahl) auf der
 * Datenbank unter der Runtime-Rolle (RLS). Betriebstag 2026-06-01 (UTC und Europe/Berlin fallen für die Testzeiten 06:00 bis 19:00 UTC zusammen). Erwartete Meter berechnet [km] unabhängig von der Klassifikation.
 */
class DistanceFixture : AutoCloseable {
    val km = KmFixture()
    val metrics: Metrics = AnalyticsApi.createMetrics(km.tx, smallGroupThreshold = 3)
    val day: LocalDate = LocalDate.of(2026, 6, 1)
    val period: ReportingPeriod = ReportingPeriod.ofDays(ZoneId.of("Europe/Berlin"), day, day)

    /** Welt mit Fahrzeug der Kapazität [seats] Sitzplätze und [wheelchair] Rollstuhlplätze. */
    fun world(seats: Int? = 4, wheelchair: Int = 1): KmFixture.World = km.world().also { setCapacity(it, seats, wheelchair) }

    fun setCapacity(w: KmFixture.World, seats: Int?, wheelchair: Int = 0) =
        km.exec("UPDATE taktovia.vehicle SET capacity_config = ?::jsonb WHERE tenant_id = ? AND id = ?", if (seats == null) "{}" else """{"seats":$seats,"wheelchair":$wheelchair}""", w.tenant.value, w.vehicle)

    fun time(hh: Int, mm: Int) = "2026-06-01T%02d:%02d:00Z".format(hh, mm)

    /** Positionen entlang des Breitengrads: [steps] sind die Schritte (je 111 m), [times] die Messzeitpunkte. */
    fun positions(w: KmFixture.World, shift: UUID, times: List<String>, steps: List<Int>) = times.indices.forEach { km.position(w, shift, times[it], steps[it]) }

    /** Fahrgast an Bord von [from] bis [to] (Einstieg und Ausstieg als Fahrerereignisse einer Buchung). */
    fun passenger(w: KmFixture.World, shift: UUID, from: String, to: String) { val b = UUID.randomUUID(); km.driverEvent(w, shift, b, "CHECK_IN", from); km.driverEvent(w, shift, b, "CHECK_OUT", to) }

    fun classify(w: KmFixture.World, d: LocalDate = day) = km.km.classify(w.tenant, w.vehicle, d)

    fun value(w: KmFixture.World, id: String, filter: MetricFilter = MetricFilter()): MetricValue = metrics.evaluate(w.tenant, MetricQuery(period, metricId = id, filter = filter)).single()

    /** Meter zwischen den Schritten [a] und [b] (unabhängig berechnet). */
    fun m(a: Int, b: Int): Long = km.meters(a, b)
    fun sumM(from: Int, toExclusive: Int): Long = (from until toExclusive).sumOf { m(it, it + 1) }
    fun km3(meters: Long): BigDecimal = BigDecimal(meters).movePointLeft(3)
    fun ratio(n: Long, d: Long): BigDecimal = BigDecimal(n).divide(BigDecimal(d), 6, RoundingMode.HALF_UP)

    /**
     * Musterfahrt (Vertrag LH-6.14-ANA-002): Fahrdienst 06:00-14:00 mit Fahrtenplan, Positionen 08:00 bis 08:10 im Minutentakt (Schritt 0 bis 10, zehn Abschnitte zu je einer Minute).
     * Fahrgast A an Bord 08:02-08:08 (Abschnitte 2 bis 7), Fahrgast B 08:04-08:06 (Abschnitte 4 und 5, gemeinsame Teilstrecke). Abschnitte 0, 1, 8, 9 sind Leerfahrt.
     * Erwartung: Fahrzeugkilometer = Summe aller zehn Abschnitte, produktive Kilometer = Abschnitte 2 bis 7, Fahrgastkilometer = A (2 bis 7) + B (4 und 5) > produktive Kilometer.
     */
    fun musterfahrt(w: KmFixture.World = world()): Muster {
        val s = km.shift(w, time(6, 0), time(14, 0))
        positions(w, s, (0..10).map { time(8, it) }, (0..10).toList())
        passenger(w, s, time(8, 2), time(8, 8)); passenger(w, s, time(8, 4), time(8, 6))
        classify(w)
        return Muster(w, s, vehicle = sumM(0, 10), productive = sumM(2, 8), empty = sumM(0, 2) + sumM(8, 10), passenger = sumM(2, 8) + sumM(4, 6))
    }

    class Muster(val w: KmFixture.World, val shift: UUID, val vehicle: Long, val productive: Long, val empty: Long, val passenger: Long)

    val categories get() = KmCategory.entries
    /**
     * Testumlauf mit vier Fahrgästen (Vertrag LH-I.6-KPI-012) am Betriebstag [d] ([t] bildet Stunde und Minute auf den Zeitpunkt ab): Positionen im Minutentakt, zehn Abschnitte zu je einer Minute (Schritt 0 bis 10).
     * Fahrgast 1 an Bord in den Abschnitten 0 bis 3, Fahrgast 2 in 2 bis 6, Fahrgast 3 in 3 und 4, Fahrgast 4 in 6 bis 8. Fahrgäste je Abschnitt: 1, 1, 2, 3, 2, 1, 2, 1, 1, 0 (Summe 14; Abschnitt 9 ist leer).
     */
    fun testumlauf(w: KmFixture.World, d: LocalDate = day, t: (Int, Int) -> String = ::time): UmlaufSoll {
        val s = km.shift(w, t(6, 0), t(14, 0))
        positions(w, s, (0..10).map { t(8, it) }, (0..10).toList())
        passenger(w, s, t(8, 0), t(8, 4)); passenger(w, s, t(8, 2), t(8, 7)); passenger(w, s, t(8, 3), t(8, 5)); passenger(w, s, t(8, 6), t(8, 9))
        classify(w, d)
        val perSection = listOf(1, 1, 2, 3, 2, 1, 2, 1, 1, 0)
        return UmlaufSoll(s, occupiedMeters = perSection.indices.sumOf { perSection[it] * m(it, it + 1) }, offeredMetersPerSeat = sumM(0, 10), occupiedMinutes = perSection.sum(), operatingMinutes = 10)
    }

    class UmlaufSoll(val shift: UUID, val occupiedMeters: Long, val offeredMetersPerSeat: Long, val occupiedMinutes: Int, val operatingMinutes: Int)

    override fun close() = km.close()
}

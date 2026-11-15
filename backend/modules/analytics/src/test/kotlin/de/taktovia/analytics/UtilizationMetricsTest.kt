package de.taktovia.analytics

import de.taktovia.analytics.api.MetricQuery
import de.taktovia.analytics.api.MetricReason
import de.taktovia.analytics.api.MetricState
import de.taktovia.analytics.api.ReportingPeriod
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M4-57 / LH-I.6-KPI-012: Auslastung als Kapazitätszeit (`seat_time_utilization`) und als Fahrgastkilometerkennzahl (`capacity_utilization_distance_weighted`) auf der echten Datenbank.
 * Testumlauf (siehe [DistanceFixture.testumlauf]): vier Fahrgäste mit überlappenden Teilstrecken, vier Sitzplätze, zehn Minuten Betriebsdienst. Sollwerte von Hand: belegte Sitzplatzminuten 14,
 * verfügbare 4 x 10 = 40, also 0,35; belegte Platz-km = Summe (Fahrgäste je Abschnitt x Länge), angebotene = 4 x Gesamtlänge.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UtilizationMetricsTest {
    private val fx = DistanceFixture()
    @AfterAll fun close() = fx.close()
    private val seat = "seat_time_utilization"
    private val dist = "capacity_utilization_distance_weighted"

    @Test
    fun testumlauf_vier_fahrgaeste_beide_auslastungen() {
        val w = fx.world(seats = 4, wheelchair = 1); val u = fx.testumlauf(w)
        val time = fx.value(w, seat); val km = fx.value(w, dist)
        assertEquals(MetricState.OK, time.state); assertEquals(MetricState.OK, km.state)
        assertEquals(BigDecimal("0.350000"), time.value); assertEquals(BigDecimal("14.0000"), time.numerator); assertEquals(BigDecimal("40.0000"), time.denominator)
        assertEquals(fx.ratio(u.occupiedMeters, 4 * u.offeredMetersPerSeat), km.value)
        assertEquals(fx.km3(u.occupiedMeters), km.numerator); assertEquals(fx.km3(4 * u.offeredMetersPerSeat), km.denominator)
        assertEquals(fx.km3(u.occupiedMeters), fx.value(w, "passenger_km").value, "der Zähler der Distanzauslastung sind die Fahrgastkilometer")
        // Formeln über die Definition abrufbar (Vertrag: Formel und Version je Kennzahl)
        for (id in listOf(seat, dist)) {
            val d = fx.metrics.definition(w.tenant, id)
            assertTrue(d.executable && d.version == 2 && d.numerator.contains("Fahrgäste") && d.denominator.contains("Sitzplätze") && d.denominator.contains("capacity_config"), id)
        }
    }

    @Test
    fun nullnenner_ist_nicht_berechenbar() {
        val none = fx.world(); for (id in listOf(seat, dist)) { val v = fx.value(none, id); assertEquals(MetricState.NOT_COMPUTABLE, v.state, id); assertEquals(MetricReason.NO_DATA, v.reason, id); assertNull(v.value, id) }
        for (seats in listOf(null, 0)) {   // Kapazität unbekannt oder 0: keine verfügbare Kapazität im Zeitraum
            val w = fx.world(seats = seats); fx.testumlauf(w)
            for (id in listOf(seat, dist)) {
                val v = fx.value(w, id); assertEquals(MetricState.NOT_COMPUTABLE, v.state, "$id seats=$seats"); assertEquals(MetricReason.ZERO_DENOMINATOR, v.reason, "$id seats=$seats"); assertNull(v.value, "$id seats=$seats")
            }
        }
    }

    @Test
    fun rollstuhlplaetze_werden_nicht_den_sitzplaetzen_zugeschlagen() {
        val a = fx.world(seats = 4, wheelchair = 0); val b = fx.world(seats = 4, wheelchair = 3)
        fx.testumlauf(a); fx.testumlauf(b)
        assertEquals(fx.value(a, seat).value, fx.value(b, seat).value); assertEquals(fx.value(a, seat).denominator, fx.value(b, seat).denominator)
        assertEquals(BigDecimal("0.350000"), fx.value(b, seat).value)
    }

    @Test
    fun kapazitaetswechsel_im_zeitraum_wird_je_betriebstag_gewichtet() {
        val w = fx.world(seats = 4); fx.testumlauf(w)
        fx.setCapacity(w, 2, 0)   // Umrüstung nach dem ersten Betriebstag
        val d2 = LocalDate.of(2026, 6, 2); val t2 = { h: Int, m: Int -> "2026-06-02T%02d:%02d:00Z".format(h, m) }
        val s = fx.km.shift(w, t2(6, 0), t2(14, 0)); fx.positions(w, s, (0..10).map { t2(8, it) }, (0..10).toList()); fx.passenger(w, s, t2(8, 0), t2(8, 5)); fx.classify(w, d2)
        val period = ReportingPeriod.ofDays(ZoneId.of("Europe/Berlin"), fx.day, d2)
        val v = fx.metrics.evaluate(w.tenant, MetricQuery(period, metricId = seat)).single()
        // Tag 1: belegt 14 von 4 x 10 = 40; Tag 2: belegt 5 von 2 x 10 = 20 -> 19 / 60
        assertEquals(BigDecimal("19.0000"), v.numerator); assertEquals(BigDecimal("60.0000"), v.denominator); assertEquals(BigDecimal("0.316667"), v.value)
        assertEquals(BigDecimal("0.350000"), fx.value(w, seat).value, "der erste Betriebstag behält seine Kapazität")
    }

    @Test
    fun umruestung_aendert_veroeffentlichten_snapshot_nicht_rueckwirkend_auch_nicht_parallel() {
        val w = fx.world(seats = 4); fx.testumlauf(w)
        val before = fx.value(w, seat)
        assertEquals(BigDecimal("0.350000"), before.value)
        val pool = Executors.newFixedThreadPool(6)
        try {
            val readers = (0 until 40).map { Callable { fx.value(w, seat) } }.map { pool.submit(it) }
            fx.setCapacity(w, 2, 0); fx.classify(w)   // Umrüstung und neuer Klassifikationslauf während der Auswertungen
            val results = readers.map { it.get(30, TimeUnit.SECONDS) }
            assertTrue(results.all { it.value == BigDecimal("0.350000") || it.value == BigDecimal("0.700000") }, "jede Auswertung sieht genau einen Stand, nie eine Mischung: ${results.map { it.value }.toSet()}")
        } finally { pool.shutdownNow() }
        val after = fx.value(w, seat)
        assertEquals(BigDecimal("0.700000"), after.value); assertNotEquals(before.snapshotId, after.snapshotId)
        assertEquals(BigDecimal("0.350000"), fx.km.strings("SELECT value::text FROM taktovia.aggregate_snapshot WHERE id = ?", before.snapshotId).single().toBigDecimal(), "der veröffentlichte Snapshot bleibt unverändert")
    }

    @Test
    fun fremdes_fahrzeug_fliesst_nicht_ein() {
        val a = fx.world(); val b = fx.world(seats = 1); fx.testumlauf(a); fx.testumlauf(b)
        assertEquals(BigDecimal("0.350000"), fx.value(a, seat).value); assertEquals(BigDecimal("1.400000"), fx.value(b, seat).value, "Besetzung über der Sitzplatzzahl bleibt sichtbar (keine Kappung)")
        assertEquals(BigDecimal("40.0000"), fx.value(a, seat).denominator)
    }
}

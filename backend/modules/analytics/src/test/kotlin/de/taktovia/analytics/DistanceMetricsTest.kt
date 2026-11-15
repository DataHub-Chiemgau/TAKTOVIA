package de.taktovia.analytics

import de.taktovia.analytics.api.KmCategory
import de.taktovia.analytics.api.MetricQuery
import de.taktovia.analytics.api.MetricReason
import de.taktovia.analytics.api.MetricState
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M4-56 / LH-6.14-ANA-002: Fahrzeug-, Besetzt- (produktive) und Fahrgastkilometer, Leerkilometerquote und Betriebsstunden aus der Kilometerklassifikation (WP-M4-42), auf der echten Datenbank.
 * Die Sollwerte der Musterfahrt (siehe [DistanceFixture.musterfahrt]) werden aus den Positionen unabhängig von der Klassifikation berechnet.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DistanceMetricsTest {
    private val fx = DistanceFixture()
    @AfterAll fun close() = fx.close()

    @Test
    fun musterfahrt_ergibt_erwartete_kilometer() {
        val m = fx.musterfahrt()
        val vehicle = fx.value(m.w, "vehicle_km"); val productive = fx.value(m.w, "productive_km"); val passenger = fx.value(m.w, "passenger_km"); val empty = fx.value(m.w, "empty_km_rate")
        assertEquals(MetricState.OK, vehicle.state)
        assertEquals(fx.km3(m.vehicle), vehicle.value)
        assertEquals(fx.km3(m.productive), productive.value)
        assertEquals(fx.km3(m.passenger), passenger.value)
        assertTrue(passenger.value!! > productive.value!!, "Fahrgastkilometer (Summe individueller Strecken) sind bei gemeinsamer Teilstrecke größer als die produktiven Kilometer")
        assertEquals(fx.km3(m.empty), empty.numerator); assertEquals(fx.ratio(m.empty, m.vehicle), empty.value)
        assertEquals(BigDecimal.ONE.setScale(4), empty.coverage)
        val hours = fx.value(m.w, "operating_hours")   // zehn Minuten Betriebszeit
        assertEquals(BigDecimal("0.1667"), hours.value)
        assertNotNull(vehicle.snapshotId); assertNotNull(vehicle.asOf)
    }

    @Test
    fun ueberfuehrung_zaehlt_nicht_als_produktiv() {
        val w = fx.world(); val s = fx.km.shift(w, fx.time(15, 0), fx.time(16, 0), function = "Überführung", plan = false)
        fx.positions(w, s, (0..4).map { fx.time(15, it) }, (0..4).toList())
        fx.passenger(w, s, fx.time(15, 1), fx.time(15, 3))   // Fahrgast an Bord, trotzdem Überführung
        val run = fx.classify(w)
        assertEquals(fx.sumM(0, 4), run.byCategory.getValue(KmCategory.TRANSFER))
        val productive = fx.value(w, "productive_km"); val passenger = fx.value(w, "passenger_km")
        assertEquals(fx.km3(0), productive.value); assertEquals(fx.km3(0), passenger.value)
        assertEquals(fx.km3(fx.sumM(0, 4)), fx.value(w, "vehicle_km").value)
        assertEquals(fx.km3(fx.sumM(0, 4)), fx.value(w, "vehicle_km").components.getValue("TRANSFER"))
        assertEquals(MetricReason.ZERO_DENOMINATOR, fx.value(w, "empty_km_rate").reason)   // Überführung steht nicht im Nenner
    }

    @Test
    fun werkstattfahrt_zaehlt_nicht_als_produktiv() {
        val w = fx.world(); val s = fx.km.shift(w, fx.time(18, 0), fx.time(19, 0), function = "Werkstattfahrt", plan = false)
        fx.positions(w, s, (0..3).map { fx.time(18, it) }, (0..3).toList())
        fx.passenger(w, s, fx.time(18, 0), fx.time(18, 2))
        fx.classify(w)
        assertEquals(fx.km3(0), fx.value(w, "productive_km").value); assertEquals(fx.km3(0), fx.value(w, "passenger_km").value)
        assertEquals(fx.km3(fx.sumM(0, 3)), fx.value(w, "vehicle_km").components.getValue("WORKSHOP"))
        assertEquals(BigDecimal.ZERO.setScale(4), fx.value(w, "operating_hours").value, "Werkstattfahrt ist keine Betriebszeit")
    }

    @Test
    fun kategorien_summieren_zu_fahrzeugkilometern() {
        val w = fx.world()
        val s1 = fx.km.shift(w, fx.time(6, 0), fx.time(14, 0)); val s2 = fx.km.shift(w, fx.time(15, 0), fx.time(16, 0), function = "Überführung", plan = false)
        val s3 = fx.km.shift(w, fx.time(18, 0), fx.time(19, 0), function = "Werkstattfahrt", plan = false)
        fx.positions(w, s1, listOf(fx.time(8, 0), fx.time(8, 1), fx.time(8, 2), fx.time(8, 3), fx.time(8, 20)), listOf(0, 1, 2, 3, 4))   // letzter Abschnitt über eine Lücke
        fx.passenger(w, s1, fx.time(8, 1), fx.time(8, 2))
        fx.positions(w, s2, listOf(fx.time(15, 0), fx.time(15, 1)), listOf(5, 6)); fx.positions(w, s3, listOf(fx.time(18, 0), fx.time(18, 1)), listOf(7, 8))
        val run = fx.classify(w)
        val c = fx.value(w, "vehicle_km").components
        KmCategory.entries.forEach { assertEquals(fx.km3(run.byCategory.getValue(it)), c.getValue(it.name), "Kategorie $it") }
        assertEquals(fx.km3(run.totalKmThousandths), fx.value(w, "vehicle_km").value)
        // Invariante: Leer + produktiv + nicht zuordenbar = Fahrzeugkilometer im Betriebsdienst (Nenner der Leerkilometerquote)
        val service = c.getValue("EMPTY") + c.getValue("PASSENGER") + c.getValue("UNASSIGNED")
        assertEquals(service, fx.value(w, "empty_km_rate").denominator); assertEquals(service, fx.value(w, "productive_km").denominator)
        assertTrue(c.getValue("UNASSIGNED") > BigDecimal.ZERO && c.getValue("TRANSFER") > BigDecimal.ZERO && c.getValue("WORKSHOP") > BigDecimal.ZERO)
    }

    @Test
    fun definition_je_kennzahl_abrufbar() {
        val w = fx.world()
        for (id in listOf("vehicle_km", "operating_hours", "empty_km_rate", "productive_km", "passenger_km")) {
            val d = fx.metrics.definition(w.tenant, id)
            assertTrue(d.executable, id); assertTrue(d.numerator.isNotBlank() && d.denominator.isNotBlank() && d.exclusions.isNotBlank(), id)
            assertTrue(d.timeBasis.contains("POSITION"), "Distanzquelle in der Definition: $id")
            assertTrue(d.exclusions.contains("Erfassungsgrenze"), "Erfassungsgrenze in der Definition: $id")
        }
        assertTrue(fx.metrics.definition(w.tenant, "passenger_km").numerator.contains("individuellen Fahrtstrecken"))
        assertTrue(fx.metrics.definition(w.tenant, "productive_km").exclusions.contains("Überführung und Werkstattfahrt zählen nie"))
        assertTrue(fx.metrics.definition(w.tenant, "operating_hours").exclusions.contains("Beginn und Ende des Betriebsdienstes"))
        assertEquals(2, fx.metrics.definition(w.tenant, "empty_km_rate").version)   // Version 1 (nicht ausführbar) bleibt unverändert, Version 2 ist die ausführbare
        assertTrue(!fx.metrics.definition(w.tenant, "empty_km_rate", 1).executable)
    }

    @Test
    fun gps_luecke_weist_vollstaendigkeit_aus() {
        val w = fx.world(); val s = fx.km.shift(w, fx.time(6, 0), fx.time(14, 0))
        fx.positions(w, s, listOf(fx.time(8, 0), fx.time(8, 1), fx.time(8, 2), fx.time(8, 3), fx.time(8, 13), fx.time(8, 14)), listOf(0, 1, 2, 3, 4, 5))   // Lücke 08:03 bis 08:13
        val run = fx.classify(w)
        val gap = run.byCategory.getValue(KmCategory.UNASSIGNED); assertTrue(gap > 0)
        val rate = fx.value(w, "empty_km_rate"); val expected = BigDecimal(run.totalKmThousandths - gap).divide(BigDecimal(run.totalKmThousandths), 4, RoundingMode.DOWN)
        assertEquals(MetricState.PROVISIONAL, rate.state); assertEquals(expected, rate.coverage)
        assertTrue(rate.coverage!! < BigDecimal.ONE, "kein scheingenauer Wert: Vollständigkeit unter 100 %")
        assertEquals(MetricState.PROVISIONAL, fx.value(w, "vehicle_km").state)
        assertEquals(fx.km3(gap), rate.components.getValue("UNASSIGNED"), "nicht zuordenbare Kilometer werden ausgewiesen und nicht verteilt")
    }

    @Test
    fun nullnenner_ergibt_nicht_berechenbar() {
        val w = fx.world(); val s = fx.km.shift(w, fx.time(18, 0), fx.time(19, 0), function = "Werkstattfahrt", plan = false)
        fx.positions(w, s, (0..2).map { fx.time(18, it) }, (0..2).toList()); fx.classify(w)
        val rate = fx.value(w, "empty_km_rate")
        assertEquals(MetricState.NOT_COMPUTABLE, rate.state); assertEquals(MetricReason.ZERO_DENOMINATOR, rate.reason); assertNull(rate.value)
        val none = fx.world()   // keine Klassifikation: kein Wert, nie 0
        for (id in listOf("vehicle_km", "productive_km", "passenger_km", "empty_km_rate", "operating_hours")) {
            val v = fx.value(none, id); assertEquals(MetricState.NOT_COMPUTABLE, v.state, id); assertEquals(MetricReason.NO_DATA, v.reason, id); assertNull(v.value, id)
        }
    }

    @Test
    fun nachtraegliche_daten_aendern_snapshot_nur_ueber_neuen_datenstand() {
        val m = fx.musterfahrt()
        val first = fx.value(m.w, "vehicle_km")
        assertEquals(first.snapshotId, fx.value(m.w, "vehicle_km").snapshotId, "derselbe Datenstand ergibt denselben Snapshot")
        fx.km.position(m.w, m.shift, fx.time(8, 11), 11)   // nachträglich eintreffende Position
        fx.classify(m.w)
        val second = fx.value(m.w, "vehicle_km")
        assertNotEquals(first.snapshotId, second.snapshotId); assertTrue(second.asOf!! > first.asOf!!, "neuer Datenstand")
        assertTrue(second.value!! > first.value!!)
        assertEquals(fx.km3(m.vehicle), fx.km.strings("SELECT value::text FROM taktovia.aggregate_snapshot WHERE id = ?", first.snapshotId).single().toBigDecimal(), "der frühere Snapshot bleibt unverändert")
        assertEquals(2, fx.km.count("SELECT count(*) FROM taktovia.aggregate_snapshot WHERE tenant_id = ? AND metric_id = 'vehicle_km'", m.w.tenant.value))
    }

    @Test
    fun parallele_auswertung_und_mandantentrennung() {
        val a = fx.musterfahrt(); val b = fx.world().also { w ->
            val s = fx.km.shift(w, fx.time(6, 0), fx.time(14, 0)); fx.positions(w, s, (0..3).map { fx.time(9, it) }, (30..33).toList()); fx.classify(w)
        }
        val pool = Executors.newFixedThreadPool(8)
        try {
            val calls = (0 until 16).map { i -> Callable { val w = if (i % 2 == 0) a.w else b; w to fx.metrics.evaluate(w.tenant, MetricQuery(fx.period, metricId = "vehicle_km")).single() } }
            val results = pool.invokeAll(calls).map { it.get(30, TimeUnit.SECONDS) }
            assertEquals(1, results.filter { it.first === a.w }.map { it.second.snapshotId }.toSet().size, "parallele Abfragen schreiben genau einen Snapshot")
            assertTrue(results.filter { it.first === a.w }.all { it.second.value == fx.km3(a.vehicle) }, "Mandant A sieht nur seine Kilometer")
            assertTrue(results.filter { it.first !== a.w }.all { it.second.value == fx.km3(fx.sumM(30, 33)) }, "Mandant B sieht nur seine Kilometer")
        } finally { pool.shutdownNow() }
        assertEquals(0, fx.km.count("SELECT count(*) FROM taktovia.aggregate_snapshot s WHERE s.tenant_id = ? AND s.id IN (SELECT id FROM taktovia.aggregate_snapshot WHERE tenant_id = ?)", a.w.tenant.value, b.tenant.value))
    }
}

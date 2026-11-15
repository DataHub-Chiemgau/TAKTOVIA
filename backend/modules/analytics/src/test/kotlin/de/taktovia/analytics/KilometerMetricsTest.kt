package de.taktovia.analytics

import de.taktovia.analytics.api.MetricState
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal

/**
 * WP-M4-56 / LH-I.6-KPI-013: Erfassungsgrenzen von Leerkilometern, produktiven Kilometern, Betriebsstunden und Fahrgastkilometern. Originalabnahme: eine Überführungsfahrt wird nicht als produktive
 * Fahrgaststrecke gezählt. Die ausführlichen Fälle (Lücke, Werkstatt, Nullnenner, Datenstand) stehen in [DistanceMetricsTest].
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KilometerMetricsTest {
    private val fx = DistanceFixture()
    @AfterAll fun close() = fx.close()

    @Test
    fun ueberfuehrung_zaehlt_nicht_als_produktive_fahrgaststrecke() {
        // Fahrdienst mit einem Fahrgast (zwei produktive Abschnitte) und eine Überführungsschicht mit Fahrgast an Bord am selben Tag
        val w = fx.world()
        val s1 = fx.km.shift(w, fx.time(6, 0), fx.time(14, 0)); val s2 = fx.km.shift(w, fx.time(15, 0), fx.time(16, 0), function = "Überführung", plan = false)
        fx.positions(w, s1, (0..3).map { fx.time(8, it) }, (0..3).toList()); fx.passenger(w, s1, fx.time(8, 1), fx.time(8, 3))
        fx.positions(w, s2, (0..3).map { fx.time(15, it) }, (10..13).toList()); fx.passenger(w, s2, fx.time(15, 0), fx.time(15, 3))
        fx.classify(w)
        assertEquals(fx.km3(fx.sumM(1, 3)), fx.value(w, "productive_km").value, "nur die Strecke im Betriebsdienst ist produktiv")
        assertEquals(fx.km3(fx.sumM(1, 3)), fx.value(w, "passenger_km").value)
        assertEquals(fx.km3(fx.sumM(10, 13)), fx.value(w, "vehicle_km").components.getValue("TRANSFER"))
    }

    @Test
    fun musterfahrt_ergibt_erwartete_kilometer() {
        val m = fx.musterfahrt()
        val rate = fx.value(m.w, "empty_km_rate")
        assertEquals(MetricState.OK, rate.state)
        assertEquals(fx.km3(m.empty), rate.numerator); assertEquals(fx.km3(m.vehicle), rate.denominator)   // Leer + produktiv = Fahrzeugkilometer im Betriebsdienst
        assertEquals(fx.km3(m.empty + m.productive), rate.denominator)
        assertEquals(fx.km3(m.productive), fx.value(m.w, "productive_km").numerator)
        assertEquals(BigDecimal("0.1667"), fx.value(m.w, "operating_hours").value)   // Betriebsstunden: zehn Minuten im Betriebsdienst
        assertTrue(fx.value(m.w, "passenger_km").value!! > fx.value(m.w, "productive_km").value!!)
    }
}

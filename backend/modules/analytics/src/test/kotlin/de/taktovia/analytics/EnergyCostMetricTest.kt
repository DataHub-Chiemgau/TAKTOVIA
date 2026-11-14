package de.taktovia.analytics

import de.taktovia.analytics.api.EnergyKind
import de.taktovia.analytics.api.ExclusionReason
import de.taktovia.analytics.api.MetricApproximation
import de.taktovia.analytics.api.MetricFilter
import de.taktovia.analytics.api.MetricQuery
import de.taktovia.analytics.api.MetricReason
import de.taktovia.analytics.api.MetricState
import de.taktovia.analytics.api.MetricValue
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M4-43 / LH-I.2-FCO-004: Energiekosten je 100 km und je Betriebsstunde aus freigegebenen, gebuchten Tankvorgängen, auf der echten Datenbank (Runtime-Rolle, RLS).
 *
 * Referenzberechnung (von Hand, unabhängig vom Code): Fahrzeug 1 (Diesel) hat zwei gebuchte Tankbelege, Kostenbasis 11 900 - 1 900 = 10 000 und 5 950 - 950 = 5 000 Minor Units = 15 000. Es fuhr 185,000 km
 * (120 Fahrgast, 40 leer, 20 Überführung, 5 Werkstatt) und war 2 Stunden im Betrieb (1 h + 0,5 h + 0,5 h; Werkstatt zählt nicht). Je 100 km: 15 000 x 100 / 185 = 8 108,108 -> 8 108 (HALF_UP auf ganze
 * Minor Units). Je Betriebsstunde: 15 000 / 2 = 7 500. Fahrzeug 2 (Strom) hat einen Beleg mit Kostenbasis 2 380 - 380 = 2 000 und fuhr 50,000 km in einer Stunde: 2 000 x 100 / 50 = 4 000 je 100 km,
 * 2 000 je Stunde. Energiearten werden nie gemischt, Währungen nie addiert.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EnergyCostMetricTest {
    private lateinit var fx: EnergyFixture
    @BeforeAll fun up() { fx = EnergyFixture() }
    @AfterAll fun down() { fx.close() }

    private fun eval(w: EnergyFixture.World, id: String, filter: MetricFilter = MetricFilter()): List<MetricValue> = fx.metrics.evaluate(w.tenant, MetricQuery(fx.day, metricId = id, filter = filter))
    private fun one(w: EnergyFixture.World, id: String, energy: EnergyKind, currency: String? = null) = eval(w, id, MetricFilter(energy = energy, currency = currency)).single()
    private val km = "energy_cost_per_100km"
    private val hour = "energy_cost_per_operating_hour"

    /** Welt mit dem Referenzdatensatz (siehe Klassenbeschreibung). */
    private fun reference(): Triple<EnergyFixture.World, UUID, UUID> {
        val w = fx.world(); val diesel = fx.vehicle(w); val electric = fx.vehicle(w)
        fx.fuel(w, diesel); fx.fuel(w, diesel, gross = 5950, tax = 950, recoverable = 950); fx.fuel(w, electric, fuelType = "ELECTRIC", gross = 2380, tax = 380, recoverable = 380, quantity = "30.0")
        fx.referenceKm(w, diesel); fx.electricKm(w, electric)
        return Triple(w, diesel, electric)
    }

    @Test
    fun referenzdatensatz_ergibt_referenzkennzahl() {
        val (w, _, _) = reference()
        val perKm = one(w, km, EnergyKind.FUEL)
        assertEquals(MetricState.OK, perKm.state); assertNull(perKm.reason)
        assertEquals(BigDecimal(8108), perKm.value); assertEquals(BigDecimal(15000), perKm.numerator); assertEquals(BigDecimal("185.000"), perKm.denominator)
        assertEquals(BigDecimal("1.0000"), perKm.coverage); assertEquals(1, perKm.definition.version); assertEquals("EUR", perKm.filter.currency); assertEquals(EnergyKind.FUEL, perKm.filter.energy)
        assertNotNull(perKm.asOf); assertNotNull(perKm.snapshotId); assertEquals(MetricApproximation.ACQUISITION_BASIS_P1, perKm.approximation, "P1 ist eine gekennzeichnete Erwerbsnäherung"); assertTrue(perKm.exclusions.isEmpty())
        val perHour = one(w, hour, EnergyKind.FUEL)
        assertEquals(MetricState.OK, perHour.state); assertEquals(BigDecimal(7500), perHour.value); assertEquals(BigDecimal(15000), perHour.numerator); assertEquals(0, BigDecimal(2).compareTo(perHour.denominator))
        val electricKm = one(w, km, EnergyKind.ELECTRIC); val electricHour = one(w, hour, EnergyKind.ELECTRIC)
        assertEquals(BigDecimal(4000), electricKm.value); assertEquals(BigDecimal(2000), electricHour.value); assertEquals(BigDecimal(2000), electricKm.numerator)
        // ohne Energieart: je Energieart ein getrennter Wert, nie eine Summe
        val both = eval(w, km); assertEquals(listOf(EnergyKind.FUEL, EnergyKind.ELECTRIC), both.map { it.filter.energy }); assertEquals(listOf(BigDecimal(8108), BigDecimal(4000)), both.map { it.value })
        // dieselbe Abfrage auf demselben Datenstand: identisch, derselbe Snapshot
        val again = one(w, km, EnergyKind.FUEL); assertEquals(perKm, again); assertEquals(perKm.snapshotId, again.snapshotId)
        // Fahrzeugfilter
        val vehicleFilter = fx.base.strings("SELECT id::text FROM taktovia.vehicle WHERE tenant_id = ? ORDER BY id", w.tenant.value).map(UUID::fromString)
        assertEquals(2, vehicleFilter.size)
    }

    @Test
    fun rundung_erfolgt_kaufmaennisch_auf_ganze_minor_units() {
        val w = fx.world(); val v = fx.vehicle(w)
        fx.fuel(w, v, gross = 1, tax = 0, recoverable = 0)   // Kostenbasis 1
        fx.km(w, v, listOf(EnergyFixture.Seg("PASSENGER", "2026-12-01T06:00:00Z", "2026-12-01T07:00:00Z", 200_000)))
        assertEquals(BigDecimal(1), one(w, km, EnergyKind.FUEL).value, "1 x 100 / 200 km = 0,5 -> 1 (HALF_UP)")
        assertEquals(BigDecimal(1), one(w, hour, EnergyKind.FUEL).value, "1 / 1 h")
    }

    @Test
    fun null_kilometer_ergibt_zero_denominator() {
        val w = fx.world(); val v = fx.vehicle(w)
        fx.fuel(w, v)   // gebucht, aber keine Kilometer und keine Betriebsstunden
        for (id in listOf(km, hour)) {
            val r = one(w, id, EnergyKind.FUEL)
            assertEquals(MetricState.NOT_COMPUTABLE, r.state, id); assertEquals(MetricReason.ZERO_DENOMINATOR, r.reason, id); assertNull(r.value, "nie 0 oder ein Scheinwert"); assertEquals(BigDecimal(10000), r.numerator)
        }
        // Kilometer vorhanden, aber nur Werkstattzeit: je 100 km berechenbar, je Betriebsstunde Nenner 0
        fx.km(w, v, listOf(EnergyFixture.Seg("WORKSHOP", "2026-12-01T06:00:00Z", "2026-12-01T07:00:00Z", 10_000)))
        assertEquals(BigDecimal(100000), one(w, km, EnergyKind.FUEL).value, "10 000 x 100 / 10 km")
        assertEquals(MetricReason.ZERO_DENOMINATOR, one(w, hour, EnergyKind.FUEL).reason)
    }

    @Test
    fun liter_und_kwh_werden_nicht_gemischt() {
        val (w, diesel, electric) = reference()
        val hybrid = fx.vehicle(w); fx.fuel(w, hybrid, fuelType = "PETROL"); fx.fuel(w, hybrid, fuelType = "ELECTRIC", gross = 2380, tax = 380, recoverable = 380); fx.referenceKm(w, hybrid)
        val fuel = one(w, km, EnergyKind.FUEL); val power = one(w, km, EnergyKind.ELECTRIC)
        assertEquals(BigDecimal(15000), fuel.numerator, "nur Kraftstoff, kein Strom"); assertEquals(BigDecimal(2000), power.numerator, "nur Strom, kein Kraftstoff")
        assertEquals(BigDecimal("185.000"), fuel.denominator, "Kilometer des Plug-in-Hybrids lassen sich nicht auf die Energiearten verteilen")
        for (r in listOf(fuel, power)) assertEquals(1, r.exclusions.count { it.reason == ExclusionReason.MIXED_ENERGY_VEHICLE && it.vehicleId == hybrid })
        assertEquals(MetricState.PROVISIONAL, fuel.state, "ausgeschlossene Vorgänge fehlen: Abdeckungsgrad unter 1")
        assertTrue(diesel != electric)
    }

    @Test
    fun unfreigegebener_beleg_wird_ausgeschlossen_und_benannt() {
        val w = fx.world(); val v = fx.vehicle(w)
        val posted = fx.fuel(w, v); fx.referenceKm(w, v)
        val inReview = fx.fuel(w, v, state = "IN_REVIEW"); val reviewed = fx.fuel(w, v, state = "WAITING_SECOND_APPROVAL")
        val hint = fx.fuel(w, v, openHint = true)
        val reversed = fx.fuel(w, v); fx.reverse(w, reversed)
        val r = one(w, km, EnergyKind.FUEL)
        assertEquals(BigDecimal(10000), r.numerator, "nur der freigegebene, gebuchte, nicht stornierte Beleg geht ein (Beleg $posted)")
        val byReason = r.exclusions.groupBy({ it.reason }, { it.documentId })
        assertEquals(setOf(inReview, reviewed), byReason.getValue(ExclusionReason.NOT_RELEASED).toSet()); assertEquals(listOf(hint), byReason[ExclusionReason.OPEN_HINT]); assertEquals(listOf(reversed), byReason[ExclusionReason.REVERSED])
        assertFalse(r.exclusions.any { it.documentId == posted })
        // Abdeckung: 1 eingegangen, 3 fehlen (stornierte zählen nicht)
        assertEquals(MetricState.PROVISIONAL, r.state); assertEquals(BigDecimal("0.2500"), r.coverage)
        val snap = fx.base.strings("SELECT jsonb_array_length(exclusions)::text FROM taktovia.aggregate_snapshot WHERE tenant_id = ? AND id = ?", w.tenant.value, r.snapshotId).single()
        assertEquals("4", snap, "die Ausschlussliste steht im Snapshot")
    }

    @Test
    fun fehlende_energiedaten_ergeben_provisional_nicht_null_kosten() {
        // ein Fahrzeug mit gebuchtem und mit noch nicht freigegebenem Beleg: vorläufig mit Abdeckungsgrad
        val w = fx.world(); val v = fx.vehicle(w); fx.fuel(w, v); fx.fuel(w, v, state = "IN_REVIEW"); fx.referenceKm(w, v)
        val partial = one(w, km, EnergyKind.FUEL)
        assertEquals(MetricState.PROVISIONAL, partial.state); assertEquals(BigDecimal("0.5000"), partial.coverage); assertEquals(BigDecimal(5405), partial.value, "10 000 x 100 / 185 = 5 405,4")
        // ohne einen einzigen eingegangenen Vorgang: kein Wert, nie 0 Kosten
        val w2 = fx.world(); val v2 = fx.vehicle(w2); fx.fuel(w2, v2, state = "IN_REVIEW"); fx.referenceKm(w2, v2)
        val none = one(w2, km, EnergyKind.FUEL)
        assertEquals(MetricState.NOT_COMPUTABLE, none.state); assertEquals(MetricReason.NO_DATA, none.reason); assertNull(none.value); assertEquals(1, none.exclusions.size)
        // Mandant ganz ohne Tankvorgänge
        val empty = one(fx.world(), km, EnergyKind.ELECTRIC); assertEquals(MetricReason.NO_DATA, empty.reason); assertNull(empty.value)
        // fehlende Pflichtwerte: ohne Fahrzeug, Kraftstoffart oder Menge wird der Vorgang benannt statt als 0 gerechnet
        val w3 = fx.world(); val v3 = fx.vehicle(w3); fx.fuel(w3, v3); fx.referenceKm(w3, v3)
        val noVehicle = fx.fuel(w3, null); val noType = fx.fuel(w3, v3, fuelType = null); val noQty = fx.fuel(w3, v3, quantity = null)
        val r3 = one(w3, km, EnergyKind.FUEL)
        val reasons = r3.exclusions.associate { it.documentId to it.reason }
        assertEquals(ExclusionReason.MISSING_VEHICLE, reasons[noVehicle]); assertEquals(ExclusionReason.MISSING_ENERGY_TYPE, reasons[noType]); assertEquals(ExclusionReason.MISSING_QUANTITY, reasons[noQty])
        assertEquals(MetricState.PROVISIONAL, r3.state)
        // Kilometer unvollständig (Fahrerereignisse lückenhaft): vorläufig, nicht vollständig
        val w4 = fx.world(); val v4 = fx.vehicle(w4); fx.fuel(w4, v4); fx.referenceKm(w4, v4, eventsComplete = false)
        val r4 = one(w4, km, EnergyKind.FUEL); assertEquals(MetricState.PROVISIONAL, r4.state); assertEquals(BigDecimal("0.0000"), r4.coverage)
        // nicht zugeordnete Zeit: Betriebsstunden zu niedrig, je Stunde vorläufig
        val w5 = fx.world(); val v5 = fx.vehicle(w5); fx.fuel(w5, v5)
        fx.km(w5, v5, listOf(EnergyFixture.Seg("PASSENGER", "2026-12-01T06:00:00Z", "2026-12-01T07:00:00Z", 10_000), EnergyFixture.Seg("UNASSIGNED", "2026-12-01T07:00:00Z", "2026-12-01T08:00:00Z", 5_000)))
        assertEquals(MetricState.OK, one(w5, km, EnergyKind.FUEL).state); assertEquals(MetricState.PROVISIONAL, one(w5, hour, EnergyKind.FUEL).state)
    }

    @Test
    fun unterschiedliche_waehrungen_werden_nicht_addiert() {
        val w = fx.world(); val v = fx.vehicle(w)
        val eur = fx.fuel(w, v); val chf = fx.fuel(w, v, gross = 5000, tax = 0, recoverable = 0, currency = "CHF"); fx.referenceKm(w, v)
        val all = eval(w, km, MetricFilter(energy = EnergyKind.FUEL))
        assertEquals(listOf("CHF", "EUR"), all.map { it.filter.currency }, "je Währung ein eigener Wert"); assertEquals(listOf(BigDecimal(5000), BigDecimal(10000)), all.map { it.numerator })
        val onlyEur = one(w, km, EnergyKind.FUEL, "EUR")
        assertEquals(BigDecimal(10000), onlyEur.numerator); assertEquals(listOf(chf to ExclusionReason.OTHER_CURRENCY), onlyEur.exclusions.map { it.documentId to it.reason }); assertFalse(onlyEur.exclusions.any { it.documentId == eur })
        assertEquals(BigDecimal("1.0000"), onlyEur.coverage, "ein Vorgang anderer Währung ist kein fehlender Vorgang")
    }

    @Test
    fun stornierter_tankvorgang_zaehlt_nicht_und_fremdes_fahrzeug_ist_404() {
        val w = fx.world(); val v = fx.vehicle(w); val a = fx.fuel(w, v); val b = fx.fuel(w, v, gross = 5950, tax = 950, recoverable = 950); fx.referenceKm(w, v)
        assertEquals(BigDecimal(15000), one(w, km, EnergyKind.FUEL).numerator)
        fx.reverse(w, a)
        val r = one(w, km, EnergyKind.FUEL); assertEquals(BigDecimal(5000), r.numerator, "Stornierung hebt den ursprünglichen Vorgang auf, nichts wird doppelt gezählt"); assertEquals(listOf(a to ExclusionReason.REVERSED), r.exclusions.map { it.documentId to it.reason })
        assertTrue(b != a)
        val other = fx.world(); val foreign = fx.vehicle(other)
        val e = assertThrows(ProblemException::class.java) { eval(w, km, MetricFilter(vehicleId = foreign, energy = EnergyKind.FUEL)) }; assertEquals(ErrorCode.NOT_FOUND, e.code)
        assertEquals(MetricReason.NO_DATA, one(other, km, EnergyKind.FUEL).reason, "der andere Mandant sieht nichts von diesem Mandanten")
        assertEquals(BigDecimal(5000), eval(w, km, MetricFilter(vehicleId = v, energy = EnergyKind.FUEL)).single().numerator)
    }

    @Test
    fun freigabe_parallel_zur_berechnung_bleibt_reproduzierbar() {
        val w = fx.world(); val v = fx.vehicle(w); fx.fuel(w, v); fx.referenceKm(w, v)
        val pending = fx.fuel(w, v, state = "IN_REVIEW", gross = 5950, tax = 950, recoverable = 950)
        val other = fx.world(); val ov = fx.vehicle(other); fx.fuel(other, ov, gross = 2380, tax = 380, recoverable = 380); fx.electricKm(other, ov)
        val pool = Executors.newFixedThreadPool(6)
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            val readers = (1..4).map { i ->
                pool.submit(Callable { buildList { while (!stop.get() || size < 5) { add(one(if (i % 2 == 0) other else w, km, if (i % 2 == 0) EnergyKind.FUEL else EnergyKind.FUEL)); Thread.sleep(5); if (size > 400) break } } })
            }
            Thread.sleep(150)
            val approver = pool.submit { fx.approve(w, pending, gross = 5950, tax = 950, recoverable = 950) }
            approver.get(30, TimeUnit.SECONDS); Thread.sleep(150); stop.set(true)
            val booked = fx.base.strings("SELECT booked_at::text FROM taktovia.economic_event WHERE tenant_id = ? AND document_id = ?", w.tenant.value, pending).single()
            val bookedAt = fx.base.strings("SELECT to_char(booked_at AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"') FROM taktovia.economic_event WHERE tenant_id = ? AND document_id = ?", w.tenant.value, pending).single().let(Instant::parse)
            assertTrue(booked.isNotBlank())
            val own = readers.filterIndexed { i, _ -> (i + 1) % 2 == 1 }.flatMap { it.get(30, TimeUnit.SECONDS) }
            assertTrue(own.isNotEmpty())
            // jedes Ergebnis gehört genau einem Datenstand: vor der Freigabe 10 000, danach 15 000 mit dem Stand der Freigabe, nie ein Mischwert
            for (r in own) {
                val expected = if (r.asOf != null && !r.asOf!!.isBefore(bookedAt)) BigDecimal(15000) else BigDecimal(10000)
                assertEquals(expected, r.numerator, "Datenstand ${r.asOf}, Quellzeilen ${r.sourceEvents}")
            }
            assertTrue(own.any { it.numerator == BigDecimal(10000) } && own.any { it.numerator == BigDecimal(15000) }, "beide Stände wurden beobachtet")
            // Wiederholung auf demselben Datenstand ist identisch (auch der Snapshot)
            val a = one(w, km, EnergyKind.FUEL); val b = one(w, km, EnergyKind.FUEL)
            assertEquals(a, b); assertEquals(a.snapshotId, b.snapshotId); assertEquals(BigDecimal(15000), a.numerator)
            val sameStand = own.filter { it.asOf == a.asOf && it.sourceEvents == a.sourceEvents }
            assertTrue(sameStand.all { it.snapshotId == a.snapshotId && it.value == a.value }, "derselbe Stand ist genau ein Snapshot")
            // Mandant B ohne Vermischung
            val foreign = readers.filterIndexed { i, _ -> (i + 1) % 2 == 0 }.flatMap { it.get(30, TimeUnit.SECONDS) }
            assertTrue(foreign.isNotEmpty() && foreign.all { it.numerator == BigDecimal(2000) && it.value == BigDecimal(4000) }, "Mandant B sieht nur eigene Werte")
        } finally { stop.set(true); pool.shutdownNow() }
    }
}

package de.taktovia.analytics

import de.taktovia.analytics.api.ArrivalTolerance
import de.taktovia.analytics.api.ArrivalToleranceSource
import de.taktovia.analytics.api.MetricFilter
import de.taktovia.analytics.api.MetricReason
import de.taktovia.analytics.api.MetricState
import de.taktovia.analytics.api.NotAssessable
import de.taktovia.analytics.api.PickupTolerance
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Ankunftspünktlichkeit getrennt von der Abholung (WP-M4-54, LH-I.6-KPI-008) auf der echten Datenbank unter der Runtime-Rolle. Zugesagte späteste Ankunft 08:45 UTC; Verspätung = Ist-Ankunft minus 08:45 der höchsten
 * Zusageversion, pünktlich = höchstens wirksame Toleranz (größerer Wert aus `on_time_arrival_tolerance_min` und `arrival_grace_min`). Arbeitsannahmen: Vergleichsstichtag = Zusagezeitpunkt (OP 29),
 * Bezugsgröße bei gewähltem Servicefenster = dessen Ende (`arrival_latest` der Zusage).
 */
class ArrivalPunctualityTest {
    private fun bd(s: String) = BigDecimal(s)
    private val latest = "2026-12-01T08:45:00Z"

    private fun ArrivalFixture.arrivesAfter(t: de.taktovia.platform.tenant.TenantId, service: UUID, minutes: Long, promisedAt: String = "2026-12-01T06:00:00Z", pickupActual: String? = "2026-12-01T08:12:00Z") =
        booking(t, service, latest, Instant.parse(latest).plusSeconds(minutes * 60).toString(), pickupActual, promisedAt)

    @Test
    fun verspaetete_ankunft_trotz_puenktlicher_abholung_eigene_kennzahl() {
        // E2E-083: Abholung im Zusagefenster (08:12 bei Fenster 08:10 bis 08:15), Ankunft 17 Minuten nach der zugesagten Ankunft
        ArrivalFixture(arrivals = ArrivalFixture.fixed(3), pickups = ArrivalFixture.fixedPickup(0)).use { fx ->
            val t = fx.tenant(); val s = UUID.randomUUID()
            val b = fx.arrivesAfter(t, s, 17)
            val mean = fx.eval(t, "mean_arrival_delay"); val ok = fx.eval(t, "arrival_on_time")
            assertEquals(MetricState.OK, mean.state); assertEquals(0, bd("17").compareTo(mean.value), "17 Minuten Ankunftsverspätung"); assertEquals(0, bd("17").compareTo(mean.numerator)); assertEquals(0, bd("1").compareTo(mean.denominator))
            assertEquals("Minuten", mean.definition.unit); assertEquals(2, mean.definition.version); assertTrue(mean.definition.executable)
            assertEquals(0, bd("0").compareTo(ok.value), "17 Minuten bei Toleranz 3: Ankunft verspätet"); assertEquals(0, bd("1").compareTo(ok.denominator))
            // die Abholkennzahlen bleiben „pünktlich“ und 0 Minuten verspätet: eigene Kennzahl, kein gemeinsames Flag
            assertEquals(0, bd("1").compareTo(fx.eval(t, "pickup_on_time").value), "Abholung pünktlich"); assertEquals(0, bd("0").compareTo(fx.eval(t, "mean_pickup_delay").value))
            val trip = fx.tf.forBooking(t, b)
            assertEquals(1020L, trip.arrival.delay.seconds); assertTrue(trip.pickup.delay.seconds!! <= 0, "Abholung nicht verspätet")
        }
    }

    @Test
    fun toleranzgrenzfaelle_genau_zugesagt_plus_toleranz_ist_puenktlich_eine_sekunde_spaeter_nicht() {
        for ((limit, expectedOnTime) in listOf(3 to 2, 2 to 1)) {
            ArrivalFixture(arrivals = ArrivalFixture.fixed(limit)).use { fx ->
                val t = fx.tenant(); val s = UUID.randomUUID()
                listOf(2L, 3L, 4L).forEach { fx.arrivesAfter(t, s, it) }
                val v = fx.eval(t, "arrival_on_time")
                assertEquals(0, BigDecimal(expectedOnTime).compareTo(v.numerator), "Toleranz $limit: Zähler"); assertEquals(0, bd("3").compareTo(v.denominator))
            }
        }
        ArrivalFixture(arrivals = ArrivalFixture.fixed(3)).use { fx ->
            val t = fx.tenant(); val s = UUID.randomUUID()
            fx.booking(t, s, latest, "2026-12-01T08:48:00Z"); fx.booking(t, s, latest, "2026-12-01T08:48:01Z")
            assertEquals(0, bd("1").compareTo(fx.eval(t, "arrival_on_time").numerator), "08:48:00 pünktlich, 08:48:01 verspätet")
        }
    }

    @Test
    fun karenz_der_zusage_und_kennzahlgrenzwert_wirken_als_groesserer_wert() {
        // Arbeitsannahme zum Verhältnis der Parameter: wirksam ist der größere Wert; beide stehen mit Revision in der Bindung
        for ((tol, grace, expected) in listOf(Triple(2, 5, 1), Triple(5, 2, 1), Triple(2, 2, 0))) {
            ArrivalFixture(arrivals = ArrivalFixture.fixed(tol, grace)).use { fx ->
                val t = fx.tenant(); val s = UUID.randomUUID()
                fx.arrivesAfter(t, s, 4)
                assertEquals(0, BigDecimal(expected).compareTo(fx.eval(t, "arrival_on_time").numerator), "Toleranz $tol, Karenz $grace, Verspätung 4")
                assertEquals(0, bd("4").compareTo(fx.eval(t, "mean_arrival_delay").value), "die mittlere Verspätung rechnet ohne Toleranz")
                assertEquals(listOf(maxOf(tol, grace).toString()), fx.base.strings("SELECT applied_min::text FROM taktovia.arrival_tolerance_binding WHERE tenant_id = ?", t.value))
            }
        }
    }

    @Test
    fun fruehe_ankunft_geht_mit_null_in_die_mittlere_verspaetung_und_ist_puenktlich() {
        ArrivalFixture(arrivals = ArrivalFixture.fixed(0)).use { fx ->
            val t = fx.tenant(); val s = UUID.randomUUID()
            fx.arrivesAfter(t, s, -4); fx.arrivesAfter(t, s, 6)
            val mean = fx.eval(t, "mean_arrival_delay")
            assertEquals(0, bd("3").compareTo(mean.value), "(0 + 6) / 2, nie -4 verrechnet"); assertEquals(0, bd("6").compareTo(mean.numerator)); assertEquals(0, bd("2").compareTo(mean.denominator))
            assertEquals(0, bd("0.5").compareTo(fx.eval(t, "arrival_on_time").value), "früh ist pünktlich, 6 Minuten bei Toleranz 0 verspätet")
        }
    }

    @Test
    fun bezugsgroesse_bei_gewaehltem_servicefenster_ist_dessen_ende() {
        // Die zugesagte späteste Ankunft einer Buchung mit gewähltem Servicefenster ist das Ende des Fensters (08:30): eine Ankunft um 08:30 ist pünktlich, 08:31 verspätet
        ArrivalFixture(arrivals = ArrivalFixture.fixed(0)).use { fx ->
            val t = fx.tenant(); val s = UUID.randomUUID()
            val windowEnd = "2026-12-01T08:30:00Z"
            fx.booking(t, s, windowEnd, "2026-12-01T08:30:00Z"); fx.booking(t, s, windowEnd, "2026-12-01T08:31:00Z")
            val ok = fx.eval(t, "arrival_on_time"); assertEquals(0, bd("1").compareTo(ok.numerator)); assertEquals(0, bd("2").compareTo(ok.denominator))
            assertEquals(0, bd("0.5").compareTo(fx.eval(t, "mean_arrival_delay").value), "(0 + 1) / 2 Minuten")
        }
    }

    @Test
    fun fahrt_ohne_ankunftsereignis_ist_nicht_im_nenner() {
        ArrivalFixture(arrivals = ArrivalFixture.fixed(3)).use { fx ->
            val t = fx.tenant(); val s = UUID.randomUUID()
            val open = fx.booking(t, s, latest, null)   // abgeholt, aber nie angekommen
            assertEquals(NotAssessable.NO_ACTUAL, fx.tf.forBooking(t, open).arrival.delay.notAssessable)
            val alone = fx.eval(t, "arrival_on_time")
            assertEquals(MetricState.NOT_COMPUTABLE, alone.state); assertEquals(MetricReason.ZERO_DENOMINATOR, alone.reason); assertNull(alone.value, "nie 0 % oder 100 % an dessen Stelle")
            assertEquals(MetricReason.ZERO_DENOMINATOR, fx.eval(t, "mean_arrival_delay").reason)
            fx.arrivesAfter(t, s, 1)
            val v = fx.eval(t, "arrival_on_time"); assertEquals(0, bd("1").compareTo(v.denominator), "die Fahrt ohne Ankunftsereignis zählt nicht im Nenner"); assertEquals(0, bd("1").compareTo(v.value))
            assertEquals(0, bd("1").compareTo(fx.eval(t, "mean_arrival_delay").denominator))
        }
    }

    @Test
    fun ankunft_ohne_zusage_wird_gesondert_ausgewiesen_und_nicht_gezaehlt() {
        ArrivalFixture(arrivals = ArrivalFixture.fixed(3)).use { fx ->
            val t = fx.tenant(); val s = UUID.randomUUID()
            fx.booking(t, s, null, "2026-12-01T09:20:00Z")   // keine Ankunftszusage
            val alone = fx.eval(t, "arrival_on_time")
            assertEquals(MetricReason.ZERO_DENOMINATOR, alone.reason); assertEquals(1L, alone.withoutPromise, "gesondert ausgewiesen")
            fx.arrivesAfter(t, s, 1)
            val v = fx.eval(t, "arrival_on_time"); assertEquals(0, bd("1").compareTo(v.denominator)); assertEquals(1L, v.withoutPromise)
            assertEquals(1L, fx.eval(t, "mean_arrival_delay").withoutPromise)
            assertNull(fx.eval(t, "arrival_on_time", MetricFilter(serviceId = s)).withoutPromise, "mit Dienstfilter ist der Dienst ohne Zusage unbekannt")
        }
    }

    @Test
    fun nicht_aufloesbare_toleranz_mindert_die_abdeckung_und_steht_nicht_im_nenner() {
        val known = UUID.randomUUID(); val unknown = UUID.randomUUID()
        val source = ArrivalToleranceSource { _, _, svc, _ -> if (svc == known) ArrivalTolerance(PickupTolerance(3, "SERVICE", UUID.randomUUID(), 1), PickupTolerance(0, "GLOBAL", null, null)) else null }
        ArrivalFixture(arrivals = source).use { fx ->
            val t = fx.tenant()
            fx.arrivesAfter(t, known, 1); fx.arrivesAfter(t, unknown, 9)
            val v = fx.eval(t, "arrival_on_time")
            assertEquals(MetricState.PROVISIONAL, v.state); assertEquals(0, bd("0.5").compareTo(v.coverage)); assertEquals(0, bd("1").compareTo(v.denominator)); assertEquals(0, bd("1").compareTo(v.value))
            val mean = fx.eval(t, "mean_arrival_delay"); assertEquals(0, bd("2").compareTo(mean.denominator), "die mittlere Verspätung braucht keine Toleranz"); assertEquals(0, bd("5").compareTo(mean.value))
        }
    }

    @Test
    fun geaenderter_grenzwert_aendert_gebundene_klassifikation_und_gespeicherten_stand_nicht() {
        // Revision 1 (Toleranz 3) bis 09:30, Revision 2 (Toleranz 2) ab 09:30; Stichtag = Zusagezeitpunkt
        val r1 = UUID.randomUUID(); val r2 = UUID.randomUUID(); val change = Instant.parse("2026-12-01T09:30:00Z"); val calls = AtomicInteger()
        val source = ArrivalToleranceSource { _, _, _, at ->
            calls.incrementAndGet()
            if (at.isBefore(change)) ArrivalTolerance(PickupTolerance(3, "TENANT", r1, 1), PickupTolerance(0, "GLOBAL", null, null)) else ArrivalTolerance(PickupTolerance(2, "TENANT", r2, 2), PickupTolerance(0, "GLOBAL", null, null))
        }
        ArrivalFixture(arrivals = source).use { fx ->
            val t = fx.tenant(); val s = UUID.randomUUID()
            fx.arrivesAfter(t, s, 3, promisedAt = "2026-12-01T09:00:00Z"); fx.arrivesAfter(t, s, 3, promisedAt = "2026-12-01T09:45:00Z")
            val first = fx.eval(t, "arrival_on_time")
            assertEquals(0, bd("1").compareTo(first.numerator)); assertEquals(0, bd("2").compareTo(first.denominator))
            val versions = fx.base.strings("SELECT parameter_versions::text FROM taktovia.aggregate_snapshot WHERE tenant_id = ? AND id = ?", t.value, first.snapshotId).single()
            assertTrue(versions.contains(r1.toString()) && versions.contains(r2.toString()) && versions.contains("on_time_arrival_tolerance_min") && versions.contains("arrival_grace_min"), "der Snapshot nennt beide Parameter mit Revision: $versions")
            // der Grenzwert wird danach für alle Stichtage auf 0 gesetzt: gebundene Klassifikation und gespeicherter Stand bleiben, die Quelle wird nicht erneut gefragt
            val before = calls.get()
            val again = fx.eval(t, "arrival_on_time", arrivalSource = ArrivalFixture.fixed(0))
            assertEquals(first.snapshotId, again.snapshotId); assertEquals(0, first.value!!.compareTo(again.value)); assertEquals(before, calls.get())
            assertEquals(1, fx.base.count("SELECT count(*) FROM taktovia.aggregate_snapshot WHERE tenant_id = ? AND metric_id = 'arrival_on_time'", t.value))
        }
    }

    @Test
    fun fremder_mandant_geht_weder_in_zaehler_noch_nenner_ein_und_unzulaessiger_filter_ist_400() {
        ArrivalFixture(arrivals = ArrivalFixture.fixed(3)).use { fx ->
            val a = fx.tenant(); val b = fx.tenant(); val s = UUID.randomUUID()
            fx.arrivesAfter(a, s, 1); repeat(5) { fx.arrivesAfter(b, s, 30) }
            val v = fx.eval(a, "arrival_on_time")
            assertEquals(0, bd("1").compareTo(v.denominator), "nur der eigene Mandant"); assertEquals(0, bd("1").compareTo(v.value))
            assertEquals(1, fx.base.count("SELECT count(*) FROM taktovia.arrival_tolerance_binding WHERE tenant_id = ?", a.value))
            assertEquals(ErrorCode.BAD_REQUEST, assertThrows<ProblemException> { fx.eval(a, "arrival_on_time", MetricFilter(channel = "WEB")) }.code)
        }
    }

    @Test
    fun kleine_gruppen_werden_bei_dienstfilter_nicht_ausgewiesen() {
        ArrivalFixture(threshold = 3, arrivals = ArrivalFixture.fixed(3)).use { fx ->
            val t = fx.tenant(); val a = UUID.randomUUID(); val b = UUID.randomUUID()
            fx.arrivesAfter(t, a, 1); repeat(3) { fx.arrivesAfter(t, b, 1) }
            assertEquals(MetricReason.SMALL_GROUP, fx.eval(t, "arrival_on_time", MetricFilter(serviceId = a)).reason)
            assertEquals(MetricReason.SMALL_GROUP, fx.eval(t, "arrival_on_time", MetricFilter(serviceId = b)).reason, "auch das Komplement bleibt verborgen")
        }
    }

    @Test
    fun vertauschte_reihenfolge_von_abhol_und_ankunftsereignis_aendert_beide_kennzahlen_nicht() {
        fun run(arrivalFirst: Boolean): List<BigDecimal?> = ArrivalFixture(arrivals = ArrivalFixture.fixed(3), pickups = ArrivalFixture.fixedPickup(0)).use { fx ->
            val t = fx.tenant(); val s = UUID.randomUUID()
            // Abholung 08:22 (7 Minuten nach 08:15) und Ankunft 17 Minuten nach der Zusage
            fx.booking(t, s, latest, "2026-12-01T09:02:00Z", pickupActual = "2026-12-01T08:22:00Z", arrivalFirst = arrivalFirst)
            listOf("pickup_on_time", "mean_pickup_delay", "arrival_on_time", "mean_arrival_delay").map { fx.eval(t, it).value }
        }
        val normal = run(false); val swapped = run(true)
        assertEquals(normal.map { it!!.stripTrailingZeros() }, swapped.map { it!!.stripTrailingZeros() })
        assertEquals(0, bd("7").compareTo(normal[1])); assertEquals(0, bd("17").compareTo(normal[3])); assertEquals(0, bd("0").compareTo(normal[0])); assertEquals(0, bd("0").compareTo(normal[2]))
    }

    @Test
    fun parallele_abfragen_und_wechsel_der_toleranz_waehrend_der_berechnung_liefern_denselben_wert_und_datenstand() {
        val calls = AtomicInteger(); val r1 = UUID.randomUUID(); val r2 = UUID.randomUUID(); val none = PickupTolerance(0, "GLOBAL", null, null)
        val source = ArrivalToleranceSource { _, _, _, _ -> if (calls.incrementAndGet() <= 5) ArrivalTolerance(PickupTolerance(3, "TENANT", r1, 1), none) else ArrivalTolerance(PickupTolerance(1, "TENANT", r2, 2), none) }
        ArrivalFixture(arrivals = source).use { fx ->
            val t = fx.tenant(); val s = UUID.randomUUID()
            repeat(10) { fx.arrivesAfter(t, s, 2) }
            val pool = Executors.newFixedThreadPool(8); val go = CountDownLatch(1)
            val results = (1..8).map { pool.submit<de.taktovia.analytics.api.MetricValue> { go.await(); fx.eval(t, "arrival_on_time") } }
            go.countDown(); val out = results.map { it.get(120, TimeUnit.SECONDS) }; pool.shutdown()
            assertEquals(1, out.map { it.value!!.stripTrailingZeros() }.distinct().size, out.map { it.value }.toString())
            assertEquals(1, out.map { it.asOf }.distinct().size); assertEquals(1, out.map { it.sourceEvents }.distinct().size); assertEquals(1, out.map { it.snapshotId }.distinct().size, "ein Snapshot für denselben Stand")
            assertEquals(10, fx.base.count("SELECT count(*) FROM taktovia.arrival_tolerance_binding WHERE tenant_id = ?", t.value), "je Buchung und Zusageversion genau eine Bindung")
            assertNotNull(out.first().snapshotId)
        }
    }

    @Test
    fun definitionen_version_zwei_sind_ausfuehrbar_und_version_eins_bleibt_unveraendert() {
        ArrivalFixture(arrivals = ArrivalFixture.fixed(3)).use { fx ->
            val t = fx.tenant()
            val ds = fx.metrics.definitions(t).associateBy { it.metricId }
            listOf("arrival_on_time", "mean_arrival_delay").forEach { id -> assertTrue(ds.getValue(id).executable, id); assertEquals(2, ds.getValue(id).version) }
            assertTrue(!fx.metrics.definition(t, "arrival_on_time", 1).executable, "die freigegebene Version 1 ist unveränderlich")
            fx.metrics.definitions(t); assertEquals(2, fx.base.count("SELECT count(*) FROM taktovia.metric_definition_revision WHERE tenant_id = ? AND metric_id = 'arrival_on_time'", t.value))
        }
    }
}

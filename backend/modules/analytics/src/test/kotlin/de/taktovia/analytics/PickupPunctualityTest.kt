package de.taktovia.analytics

import de.taktovia.analytics.api.MetricFilter
import de.taktovia.analytics.api.MetricQuery
import de.taktovia.analytics.api.MetricReason
import de.taktovia.analytics.api.MetricState
import de.taktovia.analytics.api.NotAssessable
import de.taktovia.analytics.api.PickupTolerance
import de.taktovia.analytics.api.PickupToleranceSource
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
 * Abholpünktlichkeit gegen das Zusagefenster (WP-M4-53, LH-I.6-KPI-007) auf der echten Datenbank unter der Runtime-Rolle. Zusage 08:10 bis 08:15 UTC; Verspätung = Ist minus 08:15 der höchsten Zusageversion.
 * Der Grenzwert kommt aus einer Quelle je Test; die Auflösung gegen die echte Konfiguration prüft `ConfigPickupToleranceSourceTest` (Modul app). Arbeitsannahme OP 29: Vergleichsstichtag = Zusagezeitpunkt.
 */
class PickupPunctualityTest {
    private fun bd(s: String) = BigDecimal(s)
    private val windowStart = "2026-12-01T08:10:00Z"; private val windowEnd = "2026-12-01T08:15:00Z"

    /** Eine Abholung `minutes` Minuten nach dem Ende des Fensters (negativ: früher). */
    private fun PickupFixture.late(t: de.taktovia.platform.tenant.TenantId, service: UUID, minutes: Long, promisedAt: String = "2026-12-01T06:00:00Z") =
        booking(t, service, windowStart, windowEnd, Instant.parse(windowEnd).plusSeconds(minutes * 60).toString(), promisedAt)

    @Test
    fun drei_minuten_ueberschreitung_nach_gebietsgrenzwert() {
        // Gebiet A: Grenzwert 3, Gebiet B: Grenzwert 2 (zwei Dienste desselben Mandanten); dieselbe 3-Minuten-Überschreitung
        val a = UUID.randomUUID(); val b = UUID.randomUUID()
        val source = PickupToleranceSource { _, _, svc, _ -> if (svc == a) PickupTolerance(3, "REGION", UUID.randomUUID(), 1) else PickupTolerance(2, "REGION", UUID.randomUUID(), 1) }
        PickupFixture(source = source).use { fx ->
            val t = fx.tenant()
            fx.late(t, a, 3); fx.late(t, b, 3)
            val inA = fx.eval(t, "pickup_on_time", MetricFilter(serviceId = a)); val inB = fx.eval(t, "pickup_on_time", MetricFilter(serviceId = b))
            assertEquals(MetricState.OK, inA.state); assertEquals(0, bd("1").compareTo(inA.value), "Gebiet A: Grenzwert 3, Überschreitung 3: pünktlich")
            assertEquals(0, bd("0").compareTo(inB.value), "Gebiet B: Grenzwert 2, Überschreitung 3: verspätet"); assertEquals(0, bd("1").compareTo(inB.denominator))
            val all = fx.eval(t, "pickup_on_time"); assertEquals(0, bd("0.5").compareTo(all.value)); assertEquals(0, bd("2").compareTo(all.denominator))
        }
    }

    @Test
    fun grenzfaelle_zwei_drei_vier_minuten_gegen_grenzwert_drei_und_zwei() {
        for ((limit, expectedOnTime) in listOf(3 to 2, 2 to 1)) {
            PickupFixture(source = PickupFixture.fixed(limit)).use { fx ->
                val t = fx.tenant(); val s = UUID.randomUUID()
                listOf(2L, 3L, 4L).forEach { fx.late(t, s, it) }
                val v = fx.eval(t, "pickup_on_time")
                assertEquals(0, BigDecimal(expectedOnTime).compareTo(v.numerator), "Grenzwert $limit: Zähler"); assertEquals(0, bd("3").compareTo(v.denominator))
                // knapp darüber (Grenzwert + 1 Sekunde) ist verspätet: die Klassifikation rechnet auf die Sekunde, nicht auf volle Minuten
            }
        }
        PickupFixture(source = PickupFixture.fixed(3)).use { fx ->
            val t = fx.tenant(); val s = UUID.randomUUID()
            fx.booking(t, s, windowStart, windowEnd, "2026-12-01T08:18:01Z"); fx.booking(t, s, windowStart, windowEnd, "2026-12-01T08:18:00Z")
            assertEquals(0, bd("1").compareTo(fx.eval(t, "pickup_on_time").numerator), "08:18:00 pünktlich, 08:18:01 verspätet")
        }
    }

    @Test
    fun zusage_0810_bis_0815_abholung_0822_ergibt_sieben_minuten_getrennt_von_der_ankunft_e2e_082() {
        PickupFixture(source = PickupFixture.fixed(3)).use { fx ->
            val t = fx.tenant(); val s = UUID.randomUUID()
            val b = fx.booking(t, s, windowStart, windowEnd, "2026-12-01T08:22:00Z", arrivalLatest = "2026-12-01T08:45:00Z", arrivalActual = "2026-12-01T08:45:00Z")
            val mean = fx.eval(t, "mean_pickup_delay"); val ok = fx.eval(t, "pickup_on_time")
            assertEquals(MetricState.OK, mean.state); assertEquals(0, bd("7").compareTo(mean.value), "7 Minuten Abholverspätung"); assertEquals(0, bd("7").compareTo(mean.numerator)); assertEquals(0, bd("1").compareTo(mean.denominator))
            assertEquals("Minuten", mean.definition.unit); assertEquals(2, mean.definition.version)
            assertEquals(0, bd("0").compareTo(ok.value), "bei Grenzwert 3 verspätet")
            // die Ankunft ist eine eigene Messung (hier pünktlich) und verändert die Abholkennzahlen nicht
            val trip = fx.tf.forBooking(t, b)
            assertEquals(420L, trip.pickup.delay.seconds); assertEquals(0L, trip.arrival.delay.seconds)
            assertEquals(1L, trip.pickup.delay.promiseVersion, "gegen die Zusageversion")
        }
    }

    @Test
    fun fruehe_abholung_geht_mit_null_in_die_mittlere_verspaetung_und_ist_puenktlich() {
        PickupFixture(source = PickupFixture.fixed(0)).use { fx ->
            val t = fx.tenant(); val s = UUID.randomUUID()
            fx.late(t, s, -4); fx.late(t, s, 6)
            val mean = fx.eval(t, "mean_pickup_delay")
            assertEquals(0, bd("3").compareTo(mean.value), "(0 + 6) / 2, nie -4 verrechnet"); assertEquals(0, bd("6").compareTo(mean.numerator)); assertEquals(0, bd("2").compareTo(mean.denominator))
            assertEquals(0, bd("0.5").compareTo(fx.eval(t, "pickup_on_time").value), "früh ist pünktlich, 6 Minuten bei Grenzwert 0 verspätet")
        }
    }

    @Test
    fun abholung_ohne_ist_zeit_ist_nicht_bewertbar_und_nicht_im_nenner() {
        PickupFixture(source = PickupFixture.fixed(3)).use { fx ->
            val t = fx.tenant(); val s = UUID.randomUUID()
            val open = fx.booking(t, s, windowStart, windowEnd, null)
            assertEquals(NotAssessable.NO_ACTUAL, fx.tf.forBooking(t, open).pickup.delay.notAssessable)
            val alone = fx.eval(t, "pickup_on_time")
            assertEquals(MetricState.NOT_COMPUTABLE, alone.state); assertEquals(MetricReason.ZERO_DENOMINATOR, alone.reason); assertNull(alone.value, "nie 0 % oder 100 % an dessen Stelle")
            assertEquals(MetricReason.ZERO_DENOMINATOR, fx.eval(t, "mean_pickup_delay").reason)
            fx.late(t, s, 1)
            val v = fx.eval(t, "pickup_on_time"); assertEquals(0, bd("1").compareTo(v.denominator), "die Abholung ohne Ist-Zeit zählt nicht im Nenner"); assertEquals(0, bd("1").compareTo(v.value))
        }
    }

    @Test
    fun nicht_aufloesbarer_grenzwert_mindert_die_abdeckung_und_steht_nicht_im_nenner() {
        val known = UUID.randomUUID(); val unknown = UUID.randomUUID()
        val source = PickupToleranceSource { _, _, svc, _ -> if (svc == known) PickupTolerance(3, "SERVICE", UUID.randomUUID(), 1) else null }
        PickupFixture(source = source).use { fx ->
            val t = fx.tenant()
            fx.late(t, known, 1); fx.late(t, unknown, 9)
            val v = fx.eval(t, "pickup_on_time")
            assertEquals(MetricState.PROVISIONAL, v.state); assertEquals(0, bd("0.5").compareTo(v.coverage)); assertEquals(0, bd("1").compareTo(v.denominator)); assertEquals(0, bd("1").compareTo(v.value))
            val mean = fx.eval(t, "mean_pickup_delay"); assertEquals(0, bd("2").compareTo(mean.denominator), "die mittlere Verspätung braucht keinen Grenzwert"); assertEquals(0, bd("5").compareTo(mean.value))
        }
    }

    @Test
    fun grenzwert_wird_nach_dem_zusagezeitpunkt_geaendert_die_fahrt_bleibt_nach_dem_stichtagsgrenzwert_klassifiziert() {
        // Revision 1 (Grenzwert 3) bis 09:30, Revision 2 (Grenzwert 2) ab 09:30 (Beginn der Wirksamkeit); Stichtag = Zusagezeitpunkt (Arbeitsannahme OP 29)
        val r1 = UUID.randomUUID(); val r2 = UUID.randomUUID(); val change = Instant.parse("2026-12-01T09:30:00Z")
        val seen = AtomicInteger()
        val source = PickupToleranceSource { _, _, _, at -> seen.incrementAndGet(); if (at.isBefore(change)) PickupTolerance(3, "TENANT", r1, 1) else PickupTolerance(2, "TENANT", r2, 2) }
        PickupFixture(source = source).use { fx ->
            val t = fx.tenant(); val s = UUID.randomUUID()
            fx.late(t, s, 3, promisedAt = "2026-12-01T09:00:00Z")   // vor der Änderung zugesagt: Grenzwert 3, pünktlich
            fx.late(t, s, 3, promisedAt = "2026-12-01T09:45:00Z")   // nach der Änderung zugesagt: Grenzwert 2, verspätet
            val first = fx.eval(t, "pickup_on_time")
            assertEquals(0, bd("1").compareTo(first.numerator)); assertEquals(0, bd("2").compareTo(first.denominator))
            val rows = fx.base.strings("SELECT tolerance_min || ':' || config_revision_id FROM taktovia.pickup_tolerance_binding WHERE tenant_id = ? ORDER BY tolerance_min", t.value)
            assertEquals(listOf("2:$r2", "3:$r1"), rows, "jede Bindung nennt die Konfigurationsrevision des Stichtags")
            assertTrue(first.snapshotId != null)
            val versions = fx.base.strings("SELECT parameter_versions::text FROM taktovia.aggregate_snapshot WHERE tenant_id = ? AND id = ?", t.value, first.snapshotId).single()
            assertTrue(versions.contains(r1.toString()) && versions.contains(r2.toString()), "der Snapshot trägt die verwendeten Konfigurationsrevisionen: $versions")
            // spätere Änderung des Grenzwerts (Revision 3 gilt ab sofort für alle Stichtage): bereits gebundene und gespeicherte Stände ändern sich nicht
            val calls = seen.get()
            val later = PickupFixture.fixed(0)
            val again = fx.eval(t, "pickup_on_time", tolerances = later)
            assertEquals(first.snapshotId, again.snapshotId, "derselbe Stand ist derselbe Snapshot"); assertEquals(0, first.value!!.compareTo(again.value)); assertEquals(calls, seen.get())
            assertEquals(1, fx.base.count("SELECT count(*) FROM taktovia.aggregate_snapshot WHERE tenant_id = ? AND metric_id = 'pickup_on_time'", t.value))
        }
    }

    @Test
    fun unzulaessiger_filter_ist_400_und_fremder_mandant_geht_weder_in_zaehler_noch_nenner_ein() {
        PickupFixture(source = PickupFixture.fixed(3)).use { fx ->
            val a = fx.tenant(); val b = fx.tenant(); val s = UUID.randomUUID()
            fx.late(a, s, 1)
            repeat(5) { fx.late(b, s, 30) }
            val bad = assertThrows<ProblemException> { fx.eval(a, "pickup_on_time", MetricFilter(channel = "WEB")) }
            assertEquals(ErrorCode.BAD_REQUEST, bad.code)
            val v = fx.eval(a, "pickup_on_time")
            assertEquals(0, bd("1").compareTo(v.denominator), "nur der eigene Mandant"); assertEquals(0, bd("1").compareTo(v.value))
            assertEquals(0, fx.base.count("SELECT count(*) FROM taktovia.pickup_tolerance_binding WHERE tenant_id = ?", a.value) - 1)
        }
    }

    @Test
    fun kleine_gruppen_werden_bei_dienstfilter_nicht_ausgewiesen() {
        PickupFixture(threshold = 3, source = PickupFixture.fixed(3)).use { fx ->
            val t = fx.tenant(); val a = UUID.randomUUID(); val b = UUID.randomUUID()
            fx.late(t, a, 1); fx.late(t, b, 1); fx.late(t, b, 1); fx.late(t, b, 1)
            val small = fx.eval(t, "pickup_on_time", MetricFilter(serviceId = a))
            assertEquals(MetricReason.SMALL_GROUP, small.reason); assertNull(small.value)
            assertEquals(MetricReason.SMALL_GROUP, fx.eval(t, "pickup_on_time", MetricFilter(serviceId = b)).reason, "auch das Komplement bleibt verborgen")
        }
    }

    @Test
    fun parallele_abfragen_und_revisionswechsel_waehrend_der_berechnung_liefern_denselben_wert_und_datenstand() {
        // Die Quelle liefert ab dem 6. Aufruf eine andere Revision (Grenzwertänderung mitten in der Berechnung): je Buchung zählt genau eine Bindung, alle Abfragen sehen dasselbe
        val calls = AtomicInteger(); val r1 = UUID.randomUUID(); val r2 = UUID.randomUUID()
        val source = PickupToleranceSource { _, _, _, _ -> if (calls.incrementAndGet() <= 5) PickupTolerance(3, "TENANT", r1, 1) else PickupTolerance(1, "TENANT", r2, 2) }
        PickupFixture(source = source).use { fx ->
            val t = fx.tenant(); val s = UUID.randomUUID()
            repeat(10) { fx.late(t, s, 2) }
            val pool = Executors.newFixedThreadPool(8); val go = CountDownLatch(1)
            val results = (1..8).map { pool.submit<de.taktovia.analytics.api.MetricValue> { go.await(); fx.eval(t, "pickup_on_time") } }
            go.countDown(); val out = results.map { it.get(120, TimeUnit.SECONDS) }; pool.shutdown()
            assertEquals(1, out.map { it.value!!.stripTrailingZeros() }.distinct().size, out.map { it.value }.toString())
            assertEquals(1, out.map { it.asOf }.distinct().size); assertEquals(1, out.map { it.sourceEvents }.distinct().size); assertEquals(1, out.map { it.snapshotId }.distinct().size, "ein Snapshot für denselben Stand")
            assertEquals(10, fx.base.count("SELECT count(*) FROM taktovia.pickup_tolerance_binding WHERE tenant_id = ?", t.value), "je Buchung und Zusageversion genau eine Bindung")
            assertNotNull(out.first().snapshotId)
            val later = fx.eval(t, "pickup_on_time"); assertEquals(out.first().snapshotId, later.snapshotId); assertEquals(0, out.first().value!!.compareTo(later.value))
        }
    }

    @Test
    fun definitionen_version_zwei_sind_ausfuehrbar_und_version_eins_bleibt_unveraendert() {
        PickupFixture(source = PickupFixture.fixed(3)).use { fx ->
            val t = fx.tenant()
            val ds = fx.metrics.definitions(t).associateBy { it.metricId }
            listOf("pickup_on_time", "mean_pickup_delay").forEach { id -> assertTrue(ds.getValue(id).executable, id); assertEquals(2, ds.getValue(id).version) }
            val v1 = fx.metrics.definition(t, "pickup_on_time", 1); assertTrue(!v1.executable, "die freigegebene Version 1 ist unveränderlich")
            assertEquals(listOf("service_id"), ds.getValue("pickup_on_time").filterDimensions)
            // Wiederholter Zugriff legt keine weitere Version an
            fx.metrics.definitions(t); assertEquals(2, fx.base.count("SELECT count(*) FROM taktovia.metric_definition_revision WHERE tenant_id = ? AND metric_id = 'pickup_on_time'", t.value))
            assertNull(fx.metrics.evaluate(t, MetricQuery(fx.day, "pickup_on_time")).single().value)
        }
    }
}

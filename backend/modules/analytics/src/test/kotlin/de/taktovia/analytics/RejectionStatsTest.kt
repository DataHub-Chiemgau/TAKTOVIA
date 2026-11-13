package de.taktovia.analytics

import de.taktovia.analytics.api.ConsumeOutcome
import de.taktovia.analytics.api.MetricFilter
import de.taktovia.analytics.api.MetricQuery
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.tenant.TenantId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * WP-M4-48 / LH-I.6-KPI-002: Ablehnungen mit genau einem normierten Primärgrund und Mehrfachursachen als Labels. Die Summe der Primärgründe ist die Zahl der Ablehnungen, Sekundärgründe zählen nicht
 * zusätzlich, ein technischer Fehler ist keine fachliche Ablehnung, die Statistik zeigt nur den eigenen Mandanten (echte Datenbank, Runtime-Rolle, RLS).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RejectionStatsTest {
    private lateinit var fx: KpiFixture
    @BeforeAll fun up() { fx = KpiFixture() }
    @AfterAll fun down() { fx.close() }

    @Test
    fun drei_ablehnungen_summe_primaergruende_drei() {
        val t = fx.tenant(); fx.ph021(t)
        val r = fx.metrics.rejections(t, fx.day)
        assertEquals(10L, r.attempts); assertEquals(3L, r.rejections, "drei Ablehnungen")
        assertEquals(3L, r.rows.sumOf { it.primaryCount }, "die Summe der Primärgründe ist drei")
        assertEquals(setOf("NO_CAPACITY", "LEAD_TIME", "OUTSIDE_AREA"), r.rows.filter { it.primaryCount > 0 }.map { it.code }.toSet())
        // zwei Ablehnungen tragen Sekundärgründe (OUTSIDE_AREA; OUTSIDE_HOURS, NO_STAFF): getrennt ausgewiesen, ohne zusätzliche Ablehnung
        val secondary = r.rows.filter { it.secondaryCount > 0 }.associate { it.code to it.secondaryCount }
        assertEquals(mapOf("OUTSIDE_AREA" to 1L, "OUTSIDE_HOURS" to 1L, "NO_STAFF" to 1L), secondary)
        assertEquals(1L, r.rows.single { it.code == "OUTSIDE_AREA" }.primaryCount, "OUTSIDE_AREA zählt einmal als Primär- und einmal als Sekundärgrund, aber nur einmal als Ablehnung")
        assertEquals(BigDecimal.ZERO, fx.metrics.evaluate(t, MetricQuery(fx.day, metricId = "personnel_rejections")).single().value, "ein Sekundärgrund NO_STAFF ist keine Personalmangelablehnung")
    }

    @Test
    fun kapazitaet_von_zeitfenster_und_tarif_unterscheidbar() {
        val t = fx.tenant()
        fx.feed(t, fx.attempt(t, outcome = "REJECTED", primary = "NO_CAPACITY"), fx.attempt(t, outcome = "REJECTED", primary = "LEAD_TIME"), fx.attempt(t, outcome = "REJECTED", primary = "NO_TARIFF"))
        val rows = fx.metrics.rejections(t, fx.day).rows.associateBy { it.code }
        assertEquals("CAPACITY", rows.getValue("NO_CAPACITY").group); assertEquals("TIME_DEADLINE", rows.getValue("LEAD_TIME").group); assertEquals("TARIFF", rows.getValue("NO_TARIFF").group)
        assertEquals(3, rows.values.map { it.code }.distinct().size); assertEquals(3, rows.values.map { it.group }.distinct().size)
        assertEquals("CAPACITY", rows.getValue("NO_CAPACITY").metric); assertEquals("TIME_WINDOW", rows.getValue("LEAD_TIME").metric); assertEquals(null, rows.getValue("NO_TARIFF").metric, "Tarif ohne eigene Katalogkennzahl")
        val m = fx.metrics.evaluate(t, MetricQuery(fx.day)).associateBy { it.definition.metricId }
        assertEquals(BigDecimal.ONE, m.getValue("capacity_rejections").value); assertEquals(BigDecimal.ONE, m.getValue("time_window_rejections").value)
        assertTrue(rows.values.all { it.text.isNotBlank() })
    }

    @Test
    fun technischer_fehler_zaehlt_nicht_als_fachliche_ablehnung() {
        val t = fx.tenant()
        fx.feed(t, fx.attempt(t, outcome = "TECHNICAL_ERROR"), fx.attempt(t, outcome = "REJECTED", primary = "NO_CAPACITY"))
        val r = fx.metrics.rejections(t, fx.day)
        assertEquals(1L, r.rejections); assertEquals(1L, r.technicalErrors); assertTrue(r.rows.none { it.code == "TECHNICAL" })
        // TECHNICAL als Primär- oder Nebengrund wird abgewiesen
        assertEquals(ConsumeOutcome.REJECTED_REASON, fx.feed(t, fx.attempt(t, outcome = "REJECTED", primary = "TECHNICAL")).single())
        assertEquals(ConsumeOutcome.REJECTED_REASON, fx.feed(t, fx.attempt(t, outcome = "REJECTED", primary = "NO_CAPACITY", secondary = listOf("TECHNICAL"))).single())
    }

    @Test
    fun ablehnungsstatistik_nur_eigener_mandant() {
        val a = fx.tenant(); val b = fx.tenant()
        fx.feed(a, fx.attempt(a, outcome = "REJECTED", primary = "NO_CAPACITY")); fx.feed(b, fx.attempt(b, outcome = "REJECTED", primary = "OUTSIDE_AREA"), fx.attempt(b, outcome = "REJECTED", primary = "OUTSIDE_AREA"))
        assertEquals(mapOf("NO_CAPACITY" to 1L), fx.metrics.rejections(a, fx.day).rows.associate { it.code to it.primaryCount })
        assertEquals(mapOf("OUTSIDE_AREA" to 2L), fx.metrics.rejections(b, fx.day).rows.associate { it.code to it.primaryCount })
    }

    @Test
    fun mehrere_gruende_ergeben_deterministisch_gleichen_primaergrund() {
        // derselbe Ereignisbestand in sechs zufälligen Zustellreihenfolgen mit Wiederholungen ergibt dieselbe Statistik und denselben Datenstand
        val ref = fx.tenant(); val ids = (1..4).map { UUID.randomUUID() }
        fun events(t: TenantId) = listOf(
            fx.attempt(t, ids[0], outcome = "REJECTED", primary = "NO_VEHICLE", secondary = listOf("EQUIPMENT_MISSING", "NO_STAFF")), fx.attempt(t, ids[1], outcome = "REJECTED", primary = "OUTSIDE_AREA", secondary = listOf("OUTSIDE_HOURS")),
            fx.attempt(t, ids[2], outcome = "TECHNICAL_ERROR"), fx.attempt(t, ids[2], revision = 2, outcome = "REJECTED", primary = "SHIFT_ENDED"), fx.attempt(t, ids[3]),
        )
        val base = events(ref); fx.feed(ref, *base.toTypedArray()); val expected = fx.metrics.rejections(ref, fx.day)
        for (seed in 1..6) {
            val t = fx.tenant(); val ev = base.map { (id, e, json) -> Triple(id, e, json.replace(ref.value.toString(), t.value.toString())) }
            val rnd = Random(seed); val delivery = (ev + ev.shuffled(rnd).take(3)).shuffled(rnd)
            fx.feed(t, *delivery.toTypedArray())
            val got = fx.metrics.rejections(t, fx.day)
            assertEquals(expected.rows, got.rows, "Seed $seed"); assertEquals(expected.rejections, got.rejections); assertEquals(expected.asOf, got.asOf); assertEquals(expected.sourceEvents, got.sourceEvents)
        }
        assertEquals(3L, expected.rejections); assertEquals(setOf("NO_VEHICLE", "OUTSIDE_AREA", "SHIFT_ENDED"), expected.rows.filter { it.primaryCount > 0 }.map { it.code }.toSet())
    }

    @Test
    fun parallele_ablehnungen_um_letzten_platz_ohne_doppelzaehlung() {
        // Ergebnis zweier gleichzeitiger Anfragen um den letzten Platz (Entscheidung fällt im Buchungskern): eine Zusage, eine Ablehnung; die Ablehnung wird mehrfach und parallel zugestellt
        val t = fx.tenant(); val won = fx.attempt(t); val lost = fx.attempt(t, outcome = "REJECTED", primary = "NO_CAPACITY", secondary = listOf("NO_VEHICLE"))
        val pool = Executors.newFixedThreadPool(8); val go = CountDownLatch(1)
        val all = listOf(won) + List(7) { lost }
        val results = all.map { e -> pool.submit<ConsumeOutcome> { go.await(); fx.base.analytics.facts.consume(t, e.third) } }
        go.countDown(); val out = results.map { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(2, out.count { it == ConsumeOutcome.PROCESSED }); assertEquals(6, out.count { it == ConsumeOutcome.DUPLICATE })
        val r = fx.metrics.rejections(t, fx.day)
        assertEquals(2L, r.attempts); assertEquals(1L, r.rejections, "genau eine Ablehnung, keine Doppelzählung"); assertEquals(1L, r.rows.single { it.code == "NO_CAPACITY" }.primaryCount)
    }

    @Test
    fun filter_nach_kanal_und_unbekanntem_code() {
        val t = fx.tenant()
        fx.feed(t, *((1..4).map { fx.attempt(t, outcome = "REJECTED", primary = "NO_CAPACITY", channel = "WEB") } + (1..4).map { fx.attempt(t, outcome = "REJECTED", primary = "OUTSIDE_AREA", channel = "APP") }).toTypedArray())
        assertEquals(mapOf("NO_CAPACITY" to 4L), fx.metrics.rejections(t, fx.day, MetricFilter(channel = "WEB")).rows.associate { it.code to it.primaryCount })
        assertEquals(ErrorCode.BAD_REQUEST, assertThrows<ProblemException> { fx.metrics.rejections(t, fx.day, reasonCode = "GIBT_ES_NICHT") }.code, "unbekannter Code als Filter")
        assertEquals(listOf("OUTSIDE_AREA"), fx.metrics.rejections(t, fx.day, reasonCode = "OUTSIDE_AREA").rows.map { it.code })
    }
}

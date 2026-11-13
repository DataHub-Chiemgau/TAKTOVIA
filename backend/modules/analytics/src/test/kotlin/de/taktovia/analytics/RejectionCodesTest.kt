package de.taktovia.analytics

import de.taktovia.analytics.api.ConsumeOutcome
import de.taktovia.analytics.api.MetricQuery
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M4-49 / LH-I.6-KPI-003: die fünf ressourcenbezogenen Ablehnungsgründe (keine Kapazität, kein Fahrzeug, kein Fahrer, Schichtende, falsche Ausstattung) mit fünf unterschiedlichen Codes in derselben
 * Berichtsspalte „Ablehnungsgrund“, Zuordnung zu den Katalogkennzahlen, Primär- und Sekundärgrund. Die **Erzeugung** der Codes aus dem Kandidatenfilter (E2E-080/-081) weist
 * `RejectionResourceCodesTest` im Modul `booking-dispatch` nach; hier die Auswertung der gelieferten Ereignisse.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RejectionCodesTest {
    private lateinit var fx: KpiFixture
    @BeforeAll fun up() { fx = KpiFixture() }
    @AfterAll fun down() { fx.close() }

    private val five = listOf("NO_CAPACITY", "NO_VEHICLE", "NO_STAFF", "SHIFT_ENDED", "EQUIPMENT_MISSING")

    @Test
    fun fuenf_faelle_ergeben_fuenf_codes_in_gleicher_berichtsspalte() {
        val t = fx.tenant()
        five.forEach { fx.feed(t, fx.attempt(t, outcome = "REJECTED", primary = it)) }
        val r = fx.metrics.rejections(t, fx.day)
        assertEquals(five.toSet(), r.rows.map { it.code }.toSet(), "fünf unterschiedliche Codes"); assertEquals(5, r.rows.size)
        assertTrue(r.rows.all { it.primaryCount == 1L && it.text.isNotBlank() }, "jede Zeile mit Zähler und Text in derselben Spalte (Code, Gruppe, Text)")
        assertEquals(5L, r.rejections)
        assertEquals(mapOf("NO_CAPACITY" to "CAPACITY", "NO_VEHICLE" to "CAPACITY", "NO_STAFF" to "PERSONNEL", "SHIFT_ENDED" to "PERSONNEL", "EQUIPMENT_MISSING" to "EQUIPMENT"), r.rows.associate { it.code to it.group })
        assertEquals(five.sortedBy { c -> r.rows.single { it.code == c }.priority }, r.rows.map { it.code }, "Rangfolge nach Priorität")
    }

    @Test
    fun codes_werden_katalogkennzahlen_zugeordnet() {
        val t = fx.tenant()
        five.forEach { fx.feed(t, fx.attempt(t, outcome = "REJECTED", primary = it)) }
        fx.feed(t, fx.attempt(t, outcome = "REJECTED", primary = "NO_VEHICLE"))
        val m = fx.metrics.evaluate(t, MetricQuery(fx.day)).associateBy { it.definition.metricId }
        assertEquals(BigDecimal(3), m.getValue("capacity_rejections").value, "keine Kapazität und kein Fahrzeug zählen als Kapazitätsablehnung")
        assertEquals(BigDecimal(2), m.getValue("personnel_rejections").value, "kein Fahrer und Schichtende zählen als Personalmangel")
        assertEquals(BigDecimal.ONE, m.getValue("equipment_rejections").value); assertEquals(BigDecimal(6), m.getValue("requests").value)
    }

    @Test
    fun zwei_gruende_ergeben_primaer_und_sekundaergrund() {
        val t = fx.tenant()
        fx.feed(t, fx.attempt(t, outcome = "REJECTED", primary = "NO_VEHICLE", secondary = listOf("NO_STAFF")))
        val r = fx.metrics.rejections(t, fx.day)
        assertEquals(1L, r.rejections, "keine zweite Ablehnung")
        assertEquals(1L, r.rows.single { it.code == "NO_VEHICLE" }.primaryCount); assertEquals(0L, r.rows.single { it.code == "NO_VEHICLE" }.secondaryCount)
        assertEquals(0L, r.rows.single { it.code == "NO_STAFF" }.primaryCount); assertEquals(1L, r.rows.single { it.code == "NO_STAFF" }.secondaryCount)
        assertEquals(BigDecimal.ZERO, fx.metrics.evaluate(t, MetricQuery(fx.day, metricId = "personnel_rejections")).single().value)
    }

    @Test
    fun unbekannter_code_wird_abgewiesen() {
        val t = fx.tenant()
        assertEquals(ConsumeOutcome.REJECTED_REASON, fx.feed(t, fx.attempt(t, outcome = "REJECTED", primary = "KEIN_FAHRZEUG_X")).single())
        assertEquals(ConsumeOutcome.REJECTED_REASON, fx.feed(t, fx.attempt(t, outcome = "REJECTED", primary = "NO_VEHICLE", secondary = listOf("GIBT_ES_NICHT"))).single())
        assertEquals(ConsumeOutcome.REJECTED_REASON, fx.feed(t, fx.attempt(t, outcome = "RECORDED", secondary = listOf("NO_STAFF"))).single(), "Sekundärgründe ohne Primärgrund")
        assertEquals(ConsumeOutcome.REJECTED_REASON, fx.feed(t, fx.attempt(t, outcome = "REJECTED")).single(), "Ablehnung ohne Primärgrund")
        assertEquals(ConsumeOutcome.REJECTED_REASON, fx.feed(t, fx.attempt(t, outcome = "REJECTED", primary = "NO_VEHICLE", secondary = listOf("NO_VEHICLE"))).single(), "Primärgrund als Nebengrund")
        assertEquals(ConsumeOutcome.REJECTED_REASON, fx.feed(t, fx.attempt(t, outcome = "REJECTED", primary = "NO_VEHICLE", secondary = listOf("NO_STAFF", "NO_STAFF"))).single(), "doppelte Nebengründe")
        assertEquals(0, fx.base.count("SELECT count(*) FROM taktovia.analytics_event_inbox WHERE tenant_id = ?", t.value), "nichts gespeichert")
        assertEquals(0, fx.base.count("SELECT count(*) FROM taktovia.fact_attempt WHERE tenant_id = ?", t.value))
    }

    @Test
    fun letzter_platz_parallel_genau_eine_zusage() {
        // Zwei Anfragen entscheiden im Buchungskern um den letzten Platz (Hold): eine Zusage, eine Ablehnung NO_CAPACITY. Hier: beide Ergebnisse treffen parallel ein.
        val t = fx.tenant(); val pool = Executors.newFixedThreadPool(2); val go = CountDownLatch(1)
        val events = listOf(fx.attempt(t), fx.attempt(t, outcome = "REJECTED", primary = "NO_CAPACITY"))
        val out = events.map { e -> pool.submit<ConsumeOutcome> { go.await(); fx.base.analytics.facts.consume(t, e.third) } }
        go.countDown(); assertTrue(out.all { it.get(30, TimeUnit.SECONDS) == ConsumeOutcome.PROCESSED }); pool.shutdown()
        val r = fx.metrics.rejections(t, fx.day)
        assertEquals(2L, r.attempts); assertEquals(1L, r.rejections); assertEquals(1L, fx.base.count("SELECT count(*) FROM taktovia.fact_attempt WHERE tenant_id = ? AND outcome = 'RECORDED'", t.value).toLong())
    }

    @Test
    fun schichtende_waehrend_angebotsbildung_gleicher_code_auf_gleichem_datenstand() {
        val t = fx.tenant(); val id = UUID.randomUUID()
        fx.feed(t, fx.attempt(t, id, outcome = "REJECTED", primary = "SHIFT_ENDED", secondary = listOf("NO_CAPACITY")))
        val first = fx.metrics.rejections(t, fx.day)
        repeat(3) { assertEquals(first, fx.metrics.rejections(t, fx.day), "Wiederholung auf gleichem Datenstand: gleiche Zeilen und gleicher Datenstand") }
        assertEquals("SHIFT_ENDED", first.rows.single { it.primaryCount == 1L }.code)
        val values = (1..3).map { fx.metrics.evaluate(t, MetricQuery(fx.day, metricId = "personnel_rejections")).single() }
        assertEquals(1, values.map { it.snapshotId }.distinct().size); assertEquals(BigDecimal.ONE, values[0].value)
    }
}

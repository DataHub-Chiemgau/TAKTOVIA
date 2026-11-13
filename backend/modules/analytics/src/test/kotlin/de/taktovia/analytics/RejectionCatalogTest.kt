package de.taktovia.analytics

import de.taktovia.analytics.api.ConsumeOutcome
import de.taktovia.analytics.api.MetricQuery
import de.taktovia.analytics.api.MetricReason
import de.taktovia.analytics.api.MetricState
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M4-50 / LH-I.6-KPI-004: Bediengebiet, Betriebszeit, Mindestvorlauf, Maximalwartezeit, Umweggrenze, Partnerausfall und technischer Fehler sind katalogisiert, mit deutschem Text, einzeln auswertbar und
 * in den Kennzahlen richtig zugeordnet. Der Vollständigkeitstest bricht, sobald ein Katalogeintrag keinen Text hat; den Abgleich mit den vom Buchungskern erzeugbaren Codes macht `RejectionCatalogContractTest`
 * in `backend/app` (hier sind nur die Fakten und der Katalog sichtbar).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RejectionCatalogTest {
    private lateinit var fx: KpiFixture
    @BeforeAll fun up() { fx = KpiFixture() }
    @AfterAll fun down() { fx.close() }

    /** Arbeitsnamen des Detailvertrags -> Katalogcode (Abstimmung mit dem M2-Katalog). */
    private val seven = linkedMapOf("AREA_NOT_SERVED" to "OUTSIDE_AREA", "OUTSIDE_OPERATING_HOURS" to "OUTSIDE_HOURS", "MIN_LEAD_NOT_MET" to "LEAD_TIME", "MAX_WAIT_EXCEEDED" to "MAX_WAIT_EXCEEDED",
        "MAX_DETOUR_EXCEEDED" to "MAX_DETOUR_EXCEEDED", "PARTNER_UNAVAILABLE" to "PARTNER_UNAVAILABLE", "TECHNICAL_ERROR" to "TECHNICAL")

    @Test
    fun jeder_katalogcode_hat_deutschen_text_und_naechste_handlung() {
        val rows = fx.base.strings("SELECT code || '|' || coalesce(label, '') || '|' || coalesce(next_action, '') || '|' || coalesce(texts->>'en', '') FROM taktovia.rejection_reason ORDER BY code")
        assertTrue(rows.size >= 19, "Katalog vollständig: ${rows.size}")
        for (r in rows) { val p = r.split('|'); assertTrue(p[1].isNotBlank() && p[2].isNotBlank() && p[3].isNotBlank(), "Text fehlt für ${p[0]}") }
    }

    @Test
    fun jeder_der_sieben_gruende_ist_einzeln_auswertbar() {
        val t = fx.tenant()
        seven.values.filter { it != "TECHNICAL" }.forEach { fx.feed(t, fx.attempt(t, outcome = "REJECTED", primary = it)) }
        fx.feed(t, fx.attempt(t, outcome = "TECHNICAL_ERROR"))
        val all = fx.metrics.rejections(t, fx.day)
        assertEquals(seven.values.filter { it != "TECHNICAL" }.toSet(), all.rows.map { it.code }.toSet()); assertEquals(1L, all.technicalErrors)
        for (code in seven.values.filter { it != "TECHNICAL" }) {
            val one = fx.metrics.rejections(t, fx.day, reasonCode = code)
            assertEquals(listOf(code), one.rows.map { it.code }, code); assertEquals(1L, one.rows.single().primaryCount); assertTrue(one.rows.single().text.isNotBlank())
        }
        assertEquals(ErrorCode.BAD_REQUEST, assertThrows<ProblemException> { fx.metrics.rejections(t, fx.day, reasonCode = "AREA_NOT_SERVED") }.code, "Arbeitsname ist kein Katalogcode: 400")
    }

    @Test
    fun zeitfensterablehnungen_trennen_betrieb_geschlossen_und_frist() {
        val t = fx.tenant()
        fx.feed(t, fx.attempt(t, outcome = "REJECTED", primary = "OUTSIDE_HOURS"), fx.attempt(t, outcome = "REJECTED", primary = "LEAD_TIME"), fx.attempt(t, outcome = "REJECTED", primary = "MAX_WAIT_EXCEEDED"),
            fx.attempt(t, outcome = "REJECTED", primary = "OUTSIDE_AREA"), fx.attempt(t, outcome = "REJECTED", primary = "MAX_DETOUR_EXCEEDED"), fx.attempt(t, outcome = "TECHNICAL_ERROR"))
        val rows = fx.metrics.rejections(t, fx.day).rows.associateBy { it.code }
        assertEquals("TIME_OPENING", rows.getValue("OUTSIDE_HOURS").group); assertEquals("TIME_DEADLINE", rows.getValue("LEAD_TIME").group); assertEquals("TIME_DEADLINE", rows.getValue("MAX_WAIT_EXCEEDED").group)
        assertEquals("LIMIT", rows.getValue("MAX_DETOUR_EXCEEDED").group)
        val m = fx.metrics.evaluate(t, MetricQuery(fx.day)).associateBy { it.definition.metricId }
        assertEquals(BigDecimal(3), m.getValue("time_window_rejections").value, "Betrieb geschlossen und Frist zählen in Zeitfensterablehnungen"); assertEquals(BigDecimal.ONE, m.getValue("area_rejections").value)
        assertEquals(BigDecimal.ONE, m.getValue("technical_request_errors").numerator, "TECHNICAL_ERROR zählt in Technische Anfragefehler, nicht in den operativen Ablehnungen")
        assertEquals(BigDecimal.ZERO, m.getValue("capacity_rejections").value, "die Umweggrenze ist keine Kapazitätsablehnung")
    }

    @Test
    fun technische_partnerablehnungen_in_p1_nur_als_definition_partnerausfall_per_testinjektion() {
        val t = fx.tenant()
        assertEquals(ConsumeOutcome.PROCESSED, fx.feed(t, fx.attempt(t, outcome = "REJECTED", primary = "PARTNER_UNAVAILABLE")).single(), "Testinjektion des Partnerausfalls")
        val v = fx.metrics.evaluate(t, MetricQuery(fx.day, metricId = "technical_partner_rejections")).single()
        assertEquals(MetricState.NOT_COMPUTABLE, v.state); assertEquals(MetricReason.NO_PARTNERS_IN_P1, v.reason); assertNull(v.value, "in P1 nur die Definition, kein Wert")
        assertEquals(1L, fx.metrics.rejections(t, fx.day).rows.single { it.code == "PARTNER_UNAVAILABLE" }.primaryCount)
        assertEquals(BigDecimal.ZERO, fx.metrics.evaluate(t, MetricQuery(fx.day, metricId = "capacity_rejections")).single().value)
    }

    @Test
    fun unbekannter_grund_scheitert_vertragstest() {
        val t = fx.tenant()
        assertEquals(ConsumeOutcome.REJECTED_REASON, fx.feed(t, fx.attempt(t, outcome = "REJECTED", primary = "PHANTASIE_GRUND")).single(), "ein nicht katalogisierter Grund des Buchungskerns erreicht die Faktenbasis nicht")
        assertEquals(0, fx.base.count("SELECT count(*) FROM taktovia.fact_attempt WHERE tenant_id = ?", t.value))
        // auch direkt an der Tabelle weist die Datenbank den Code ab (zweite Verteidigungslinie)
        val ex = assertThrows<Exception> { fx.base.tx.run(t) { j -> j.update("INSERT INTO taktovia.fact_attempt(tenant_id,attempt_id,service_id,outcome,primary_reason,time_mode,requested_at,passengers) VALUES (taktovia.current_tenant(),gen_random_uuid(),gen_random_uuid(),'REJECTED','PHANTASIE_GRUND','DEPARTURE',now(),1)") } }
        assertTrue(generateSequence<Throwable>(ex) { it.cause }.any { it.message?.contains("unbekannter Ablehnungsgrund") == true }, ex.toString())
    }

    @Test
    fun parallele_versuche_mit_verschiedenen_gruenden_zaehlen_je_einmal_und_fremder_mandant_erscheint_nicht() {
        val a = fx.tenant(); val b = fx.tenant(); val pool = Executors.newFixedThreadPool(8); val go = CountDownLatch(1)
        val ea = listOf("OUTSIDE_AREA", "LEAD_TIME", "MAX_WAIT_EXCEEDED", "PARTNER_UNAVAILABLE").map { fx.attempt(a, outcome = "REJECTED", primary = it) }
        val eb = fx.attempt(b, outcome = "REJECTED", primary = "OUTSIDE_AREA")
        val futures = (ea + eb).map { e -> pool.submit { go.await(); fx.base.analytics.facts.consume(if (e === eb) b else a, e.third) } }
        go.countDown(); futures.forEach { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
        val r = fx.metrics.rejections(a, fx.day)
        assertEquals(setOf("OUTSIDE_AREA", "LEAD_TIME", "MAX_WAIT_EXCEEDED", "PARTNER_UNAVAILABLE"), r.rows.map { it.code }.toSet()); assertTrue(r.rows.all { it.primaryCount == 1L }); assertEquals(4L, r.rejections)
        assertEquals(1L, fx.metrics.rejections(b, fx.day).rejections)
    }
}

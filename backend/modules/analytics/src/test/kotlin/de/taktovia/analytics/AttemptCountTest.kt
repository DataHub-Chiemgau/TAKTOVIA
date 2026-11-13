package de.taktovia.analytics

import de.taktovia.analytics.api.ConsumeOutcome
import de.taktovia.analytics.api.MetricQuery
import de.taktovia.analytics.api.MetricState
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
 * WP-M4-47 / LH-I.6-KPI-001: jede Fahrtanfrage ist ein eigener Versuch in der Faktenbasis, technische Wiederholungen zählen nicht doppelt, Testanfragen sind ausgeschlossen, der Abgleich gegen die
 * Versuchstabelle meldet Abweichungen, parallele identische Anfragen und Mandanten bleiben getrennt (echte Datenbank, Runtime-Rolle).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AttemptCountTest {
    private lateinit var fx: KpiFixture
    @BeforeAll fun up() { fx = KpiFixture() }
    @AfterAll fun down() { fx.close() }

    private fun requests(t: de.taktovia.platform.tenant.TenantId) = fx.metrics.evaluate(t, MetricQuery(fx.day, metricId = "requests")).single()

    @Test
    fun zehn_anfragen_drei_ablehnungen_ergeben_zehn_nachfrageereignisse() {
        val t = fx.tenant(); val ids = fx.ph021(t)
        assertEquals(10, fx.base.count("SELECT count(*) FROM taktovia.fact_attempt WHERE tenant_id = ?", t.value), "zehn Versuche in der Faktenbasis")
        assertEquals(10, fx.base.count("SELECT count(*) FROM taktovia.analytics_event_inbox WHERE tenant_id = ? AND event_type = 'booking.attempt_recorded'", t.value), "zehn Nachfrageereignisse im Eingang")
        assertEquals(3, fx.base.count("SELECT count(*) FROM taktovia.fact_attempt WHERE tenant_id = ? AND outcome = 'REJECTED'", t.value))
        assertEquals(10, ids.distinct().size); assertEquals(BigDecimal(10), requests(t).value, "Anfragen = 10, die drei Ablehnungen bleiben als Versuche erhalten")
    }

    @Test
    fun versuch_ohne_angebot_ist_in_statistik_vorhanden() {
        val t = fx.tenant(); val id = UUID.randomUUID()
        fx.feed(t, fx.attempt(t, id, outcome = "REJECTED", primary = "NO_CAPACITY"))
        assertEquals(0, fx.base.count("SELECT count(*) FROM taktovia.fact_offer WHERE tenant_id = ?", t.value), "kein Angebot")
        assertEquals(BigDecimal.ONE, requests(t).value, "der Versuch ohne Angebot ist in der Statistik vorhanden")
        assertEquals(1, fx.base.count("SELECT count(*) FROM taktovia.fact_attempt WHERE tenant_id = ? AND attempt_id = ?", t.value, id))
        // nachträglich nicht löschbar: der Eingang ist unveränderlich (Trigger), die Runtime-Rolle kann keine Eingangszeile entfernen
        val ex = org.junit.jupiter.api.assertThrows<Exception> { fx.base.tx.run(t) { j -> j.update("DELETE FROM taktovia.analytics_event_inbox WHERE aggregate_id = ?", id) } }
        assertTrue(generateSequence<Throwable>(ex) { it.cause }.any { it.message?.contains("permission denied") == true || it.message?.contains("immutable") == true }, ex.toString())
        assertEquals(BigDecimal.ONE, requests(t).value)
    }

    @Test
    fun neue_suche_desselben_fahrgasts_zaehlt_separat() {
        val t = fx.tenant()
        // dieselbe Strecke, derselbe Kanal, anderer Versuch (neuer Idempotenzschlüssel im Buchungskern = neue attempt_id)
        fx.feed(t, fx.attempt(t, UUID.randomUUID(), at = "2026-12-01T05:00:00Z"), fx.attempt(t, UUID.randomUUID(), at = "2026-12-01T05:01:00Z"))
        assertEquals(BigDecimal(2), requests(t).value)
    }

    @Test
    fun technischer_retry_zaehlt_nicht_doppelt() {
        val t = fx.tenant(); val id = UUID.randomUUID()
        fx.feed(t, fx.attempt(t, id, outcome = "TECHNICAL_ERROR"))
        val before = fx.metrics.evaluate(t, MetricQuery(fx.day)).associateBy { it.definition.metricId }
        assertEquals(BigDecimal.ONE, before.getValue("requests").value); assertEquals(BigDecimal.ONE, before.getValue("technical_request_errors").numerator)
        // Wiederholung derselben Suche: dieselbe attempt_id, höhere Revision (der Buchungskern legt keinen zweiten Versuch an)
        assertEquals(ConsumeOutcome.PROCESSED, fx.feed(t, fx.attempt(t, id, revision = 2, outcome = "RECORDED")).single())
        val after = fx.metrics.evaluate(t, MetricQuery(fx.day)).associateBy { it.definition.metricId }
        assertEquals(BigDecimal.ONE, after.getValue("requests").value, "kein zweiter Versuch"); assertEquals(BigDecimal.ZERO, after.getValue("technical_request_errors").numerator)
        assertEquals(1, fx.base.count("SELECT count(*) FROM taktovia.fact_attempt WHERE tenant_id = ?", t.value))
        // dasselbe Ereignis erneut zugestellt: keine zweite Wirkung
        val ev = fx.attempt(t, UUID.randomUUID()); fx.feed(t, ev)
        assertEquals(ConsumeOutcome.DUPLICATE, fx.feed(t, ev).single()); assertEquals(BigDecimal(2), requests(t).value)
    }

    @Test
    fun technische_anfragefehler_getrennt_von_ablehnungen() {
        val t = fx.tenant()
        fx.feed(t, fx.attempt(t, outcome = "TECHNICAL_ERROR"), fx.attempt(t, outcome = "REJECTED", primary = "OUTSIDE_AREA"), fx.attempt(t, outcome = "REJECTED", primary = "NO_CAPACITY"), fx.attempt(t))
        val v = fx.metrics.evaluate(t, MetricQuery(fx.day)).associateBy { it.definition.metricId }
        assertEquals(BigDecimal(4), v.getValue("requests").value)
        val tech = v.getValue("technical_request_errors")
        assertEquals(BigDecimal.ONE, tech.numerator); assertEquals(BigDecimal(4), tech.denominator); assertEquals(BigDecimal("0.250000"), tech.value, "Nenner: gültige Anfrageversuche")
        assertEquals(BigDecimal.ONE, v.getValue("area_rejections").value); assertEquals(BigDecimal.ONE, v.getValue("capacity_rejections").value)
        val r = fx.metrics.rejections(t, fx.day)
        assertEquals(2L, r.rejections, "der technische Fehler ist keine fachliche Ablehnung"); assertEquals(1L, r.technicalErrors)
    }

    @Test
    fun testanfragen_sind_ausgeschlossen() {
        val t = fx.tenant()
        fx.feed(t, fx.attempt(t), fx.attempt(t, test = true), fx.attempt(t, test = true, outcome = "REJECTED", primary = "NO_CAPACITY"))
        assertEquals(BigDecimal.ONE, requests(t).value, "Testanfragen zählen nicht")
        assertEquals(BigDecimal.ZERO, fx.metrics.evaluate(t, MetricQuery(fx.day, metricId = "capacity_rejections")).single().value)
        assertEquals(0L, fx.metrics.rejections(t, fx.day).rejections)
        assertEquals(3, fx.base.count("SELECT count(*) FROM taktovia.fact_attempt WHERE tenant_id = ?", t.value), "sie bleiben als Fakt erhalten (nachvollziehbar), nur nicht in der Statistik")
    }

    @Test
    fun abgleich_versuche_gegen_ereignisprotokoll_meldet_abweichung() {
        val t = fx.tenant()
        fun row(id: UUID, outcome: String, primary: String? = null) =
            fx.base.exec("INSERT INTO taktovia.travel_attempt(tenant_id,id,created_at,channel,outcome,primary_rejection) VALUES (?,?,now(),'WEB',?,?)", t.value, id, outcome, primary)
        val ok = UUID.randomUUID(); val rejected = UUID.randomUUID(); val lost = UUID.randomUUID(); val wrong = UUID.randomUUID(); val ghost = UUID.randomUUID()
        row(ok, "OFFERED"); row(rejected, "REJECTED", "NO_CAPACITY"); row(lost, "OFFERED"); row(wrong, "OFFERED")
        fx.feed(t, fx.attempt(t, ok), fx.attempt(t, rejected, outcome = "REJECTED", primary = "NO_CAPACITY"), fx.attempt(t, wrong, outcome = "REJECTED", primary = "OUTSIDE_AREA"), fx.attempt(t, ghost))
        val rec = fx.base.analytics.facts.reconcileAttempts(t)
        assertEquals(listOf(lost), rec.missingInFacts, "Versuch ohne Ereignis"); assertEquals(listOf(ghost), rec.missingInSource, "Ereignis ohne Versuch")
        assertEquals(listOf(wrong), rec.mismatched, "abweichendes Ergebnis"); assertEquals(false, rec.consistent); assertEquals(4L, rec.sourceAttempts); assertEquals(4L, rec.factAttempts)
        // Abweichungen behoben: konsistent
        val t2 = fx.tenant(); val a = UUID.randomUUID()
        fx.base.exec("INSERT INTO taktovia.travel_attempt(tenant_id,id,created_at,channel,outcome) VALUES (?,?,now(),'WEB','OFFERED')", t2.value, a)
        fx.feed(t2, fx.attempt(t2, a))
        assertTrue(fx.base.analytics.facts.reconcileAttempts(t2).consistent)
    }

    @Test
    fun parallele_identische_suchanfragen_ein_versuch() {
        val t = fx.tenant(); val id = UUID.randomUUID(); val event = fx.attempt(t, id)
        val pool = Executors.newFixedThreadPool(8); val go = CountDownLatch(1)
        // dasselbe Ereignis aus acht Workern und acht verschiedene Ereignisse (event_id) derselben Versuchsrevision
        val same = (1..8).map { pool.submit<ConsumeOutcome> { go.await(); fx.base.analytics.facts.consume(t, event.third) } }
        val other = (1..8).map { pool.submit<ConsumeOutcome> { go.await(); fx.base.analytics.facts.consume(t, fx.attempt(t, id).third) } }
        go.countDown(); val o1 = same.map { it.get(60, TimeUnit.SECONDS) }; other.forEach { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(1, o1.count { it == ConsumeOutcome.PROCESSED }, "genau eine Verarbeitung desselben Ereignisses, die übrigen sind Wiederholungen")
        assertEquals(1, fx.base.count("SELECT count(*) FROM taktovia.fact_attempt WHERE tenant_id = ?", t.value), "genau ein Versuch")
        assertEquals(BigDecimal.ONE, requests(t).value)
    }

    @Test
    fun parallele_anfragen_zweier_mandanten_vermischen_sich_nicht() {
        val a = fx.tenant(); val b = fx.tenant(); val pool = Executors.newFixedThreadPool(8); val go = CountDownLatch(1)
        val fa = (1..15).map { pool.submit { go.await(); fx.feed(a, fx.attempt(a)) } }; val fb = (1..9).map { pool.submit { go.await(); fx.feed(b, fx.attempt(b)) } }
        go.countDown(); (fa + fb).forEach { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(BigDecimal(15), requests(a).value); assertEquals(BigDecimal(9), requests(b).value)
        assertEquals(MetricState.OK, requests(a).state)
        assertEquals(0, fx.base.count("SELECT count(*) FROM taktovia.fact_attempt WHERE tenant_id = ? AND attempt_id IN (SELECT attempt_id FROM taktovia.fact_attempt WHERE tenant_id = ?)", a.value, b.value))
    }
}

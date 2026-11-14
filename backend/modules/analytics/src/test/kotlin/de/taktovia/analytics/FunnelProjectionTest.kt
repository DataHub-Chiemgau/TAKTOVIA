package de.taktovia.analytics

import de.taktovia.analytics.api.ConsumeOutcome
import de.taktovia.analytics.api.MetricQuery
import de.taktovia.analytics.api.MetricState
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
 * WP-M4-51 / LH-I.6-KPI-005: Projektion der Trichterfakten aus Ereignissen: doppelte und parallele Zustellung, Mandantentrennung, Angebotsbezug und Zahlungsfehlschlag (echte Datenbank, Runtime-Rolle).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FunnelProjectionTest {
    private lateinit var fx: FunnelFixture
    @BeforeAll fun up() { fx = FunnelFixture() }
    @AfterAll fun down() { fx.close() }

    @Test
    fun doppelte_zustellung_aendert_keine_zahl() {
        val t = fx.tenant(); val log = fx.knownLog(t); fx.feed(t, log.events)
        val before = fx.metrics.funnel(t, fx.day)
        // dieselben Ereignisse gleichzeitig aus zwei Workern je Ereignis: genau eine Wirkung
        val pool = Executors.newFixedThreadPool(8); val go = CountDownLatch(1)
        val futures = log.events.flatMap { e -> (1..2).map { pool.submit<ConsumeOutcome> { go.await(); fx.kpi.base.analytics.facts.consume(t, e.json) } } }
        go.countDown(); val outcomes = futures.map { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
        assertTrue(outcomes.all { it == ConsumeOutcome.DUPLICATE }, outcomes.toString())
        assertEquals(before, fx.metrics.funnel(t, fx.day))
    }

    @Test
    fun fremder_mandant_wird_verworfen_und_sieht_keine_zaehlwerte() {
        val a = fx.tenant(); val b = fx.tenant(); val log = fx.knownLog(a)
        // Ereignis des Mandanten A im Verarbeitungskontext von B: verworfen, nichts gespeichert
        assertTrue(fx.feed(b, log.events).all { it == ConsumeOutcome.REJECTED_TENANT })
        fx.feed(a, log.events)
        val rb = fx.metrics.funnel(b, fx.day)
        assertEquals(listOf(0L, 0L, 0L, 0L), listOf(rb.requests, rb.offers, rb.confirmedBookings, rb.completedBookings)); assertEquals(MetricState.NOT_COMPUTABLE, rb.state); assertNull(rb.asOf)
        assertEquals(0, fx.kpi.base.count("SELECT count(*) FROM taktovia.fact_booking WHERE tenant_id = ?", b.value))
        assertEquals(10L, fx.metrics.funnel(a, fx.day).requests)
        assertTrue(fx.metrics.reconcileFunnel(b, fx.day).stages.all { it.factCount == 0L && it.logCount == 0L })
    }

    @Test
    fun buchung_ohne_angebotsbezug_macht_nicht_gebucht_vorlaeufig() {
        val t = fx.tenant(); val attempt = UUID.randomUUID(); val offer = UUID.randomUUID(); val booking = UUID.randomUUID()
        fx.feed(t, listOf(fx.attemptEv(t, attempt), fx.offer(t, offer, attempt), fx.confirmed(t, booking, offerId = null)))
        val r = fx.metrics.funnel(t, fx.day)
        assertEquals(1, r.unlinkedBookings); assertEquals(MetricState.PROVISIONAL, r.state); assertTrue(r.coverage!! < BigDecimal.ONE)
        val c = fx.metrics.evaluate(t, MetricQuery(fx.day, metricId = "booking_conversion")).single()
        assertEquals(MetricState.PROVISIONAL, c.state, "Konversion ohne Angebotsbezug ist vorläufig")
    }

    @Test
    fun zahlungsfehlschlag_ohne_buchungsbezug_wird_verworfen_und_zaehlt_getrennt() {
        val t = fx.tenant(); val booking = UUID.randomUUID()
        val noBooking = fx.kpi.base.envelope(t, "payment.authorization_failed", UUID.randomUUID(), 1, "2026-12-01T05:00:00Z", """{"payment_id":"${UUID.randomUUID()}","failure_code":"DECLINED"}""").second
        assertEquals(ConsumeOutcome.REJECTED_SCHEMA, fx.kpi.base.analytics.facts.consume(t, noBooking))
        fx.feed(t, listOf(fx.confirmed(t, booking, null), fx.paymentFailed(t, booking, "2026-12-01T05:10:00Z")))
        val r = fx.metrics.funnel(t, fx.day)
        assertEquals(1, r.paymentFailed); assertEquals(0, r.cancelledByPassenger); assertEquals(1, r.confirmedBookings); assertEquals(0, r.completedBookings)
        // Wiederaufbau aus dem Eingang liefert dieselben Zahlen
        fx.kpi.base.analytics.facts.rebuild(t)
        assertEquals(r, fx.metrics.funnel(t, fx.day).copy(asOf = r.asOf)); assertTrue(fx.metrics.reconcileFunnel(t, fx.day).consistent)
    }

    @Test
    fun kanalfilter_ist_fuer_den_trichter_unzulaessig_und_dienstfilter_trennt() {
        val t = fx.tenant(); fx.feed(t, fx.knownLog(t).events)
        val ex = org.junit.jupiter.api.assertThrows<de.taktovia.platform.problem.ProblemException> { fx.metrics.funnel(t, fx.day, de.taktovia.analytics.api.MetricFilter(channel = "WEB")) }
        assertEquals(de.taktovia.platform.problem.ErrorCode.BAD_REQUEST, ex.code)
        val other = fx.metrics.funnel(t, fx.day, de.taktovia.analytics.api.MetricFilter(serviceId = UUID.randomUUID()))
        assertEquals(listOf(0L, 0L, 0L, 0L), listOf(other.requests, other.offers, other.confirmedBookings, other.completedBookings))
    }
}

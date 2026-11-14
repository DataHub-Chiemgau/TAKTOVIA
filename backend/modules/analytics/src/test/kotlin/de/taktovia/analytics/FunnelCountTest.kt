package de.taktovia.analytics

import de.taktovia.analytics.api.ConsumeOutcome
import de.taktovia.analytics.api.FunnelReport
import de.taktovia.analytics.api.MetricQuery
import de.taktovia.analytics.api.MetricState
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * WP-M4-51 / LH-6.14-ANA-001 und LH-I.6-KPI-005: getrennte Zählung der Buchungsstufen und Endzustände aus einem bekannten Ereignisprotokoll (echte Datenbank, Runtime-Rolle, RLS). Die erwarteten Zahlen
 * werden aus der Ereignisliste gezählt, nicht aus den Fakten. Definition der Stufen: OP 27 (Arbeitsannahme: Angebot angezeigt = `booking.offer_created`, abgeschlossen = Ausstieg).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FunnelCountTest {
    private lateinit var fx: FunnelFixture
    @BeforeAll fun up() { fx = FunnelFixture() }
    @AfterAll fun down() { fx.close() }

    private fun report(t: de.taktovia.platform.tenant.TenantId): FunnelReport = fx.metrics.funnel(t, fx.day)

    @Test
    fun stufen_stimmen_mit_ereignisprotokoll_ueberein() {
        val t = fx.tenant(); val log = fx.knownLog(t)
        assertTrue(fx.feed(t, log.events).all { it == ConsumeOutcome.PROCESSED })
        val r = report(t)
        // erwartet aus dem Protokoll gezählt
        assertEquals(log.count("booking.attempt_recorded").toLong(), r.requests, "Anfragen"); assertEquals(log.count("booking.offer_created").toLong(), r.offers, "Angebote")
        assertEquals(log.count("booking.confirmed").toLong(), r.confirmedBookings, "bestätigte Buchungen")
        assertEquals(log.events.count { it.type == "driver.event_recorded" && it.json.contains("CHECK_OUT") }.toLong(), r.completedBookings, "abgeschlossene Buchungen")
        // Endzustände getrennt
        assertEquals(3, r.requestRejected); assertEquals(1, r.requestTechnicalError); assertEquals(2, r.offerNotBooked, "sechs Angebote, vier gebucht"); assertEquals(1, r.paymentFailed)
        assertEquals(1, r.cancelledByPassenger); assertEquals(1, r.cancelledByOperator, "Betreiber- und Fahrgaststorno unterscheidbar"); assertEquals(0, r.cancelledOther)
        assertEquals(0, r.unlinkedBookings); assertEquals(MetricState.OK, r.state); assertNotNull(r.asOf)
        // Abgleichbericht: Differenz 0 je Stufe
        val rec = fx.metrics.reconcileFunnel(t, fx.day)
        assertEquals(setOf("requests", "offers", "confirmed_bookings", "completed_bookings", "payment_failed"), rec.stages.map { it.stage }.toSet())
        assertTrue(rec.consistent, rec.stages.toString()); assertTrue(rec.stages.all { it.difference == 0L && it.factCount == it.logCount })
        // anderer Berichtstag: leer
        val other = fx.metrics.funnel(t, de.taktovia.analytics.api.ReportingPeriod.ofDays(fx.day.zone, fx.day.fromDay.plusDays(5), fx.day.fromDay.plusDays(5)))
        assertEquals(listOf(0L, 0L, 0L, 0L), listOf(other.requests, other.offers, other.confirmedBookings, other.completedBookings))
        assertTrue(fx.metrics.reconcileFunnel(t, fx.day).consistent)
    }

    @Test
    fun vier_zaehlgroessen_getrennt_nie_summe() {
        val t = fx.tenant(); fx.feed(t, fx.knownLog(t).events)
        val r = report(t)
        assertEquals(listOf(10L, 6L, 4L, 1L), listOf(r.requests, r.offers, r.confirmedBookings, r.completedBookings), "vier verschiedene Größen")
        assertTrue(FunnelReport::class.java.declaredFields.none { it.name.lowercase().let { n -> n.startsWith("total") || n.startsWith("sum") } }, "es gibt kein Summenfeld")
        // Kennzahlen mit Stufenbezug: Zähler und Nenner getrennt, Nenner 0 nie 0 oder 100 %
        val v = fx.metrics.evaluate(t, MetricQuery(fx.day)).associateBy { it.definition.metricId }
        assertEquals(BigDecimal("0.666667"), v.getValue("offer_rate").value, "6 von 9 fachlich oder technisch auswertbaren Anfragen (ohne technischen Fehler) haben ein Angebot")
        assertEquals(BigDecimal("0.666667"), v.getValue("booking_conversion").value); assertEquals(BigDecimal(4), v.getValue("booking_conversion").numerator); assertEquals(BigDecimal(6), v.getValue("booking_conversion").denominator)
        assertEquals(BigDecimal("0.250000"), v.getValue("cancellation_rate_passenger").value); assertEquals(BigDecimal("0.250000"), v.getValue("cancellation_rate_operator").value)
        assertTrue(listOf("offer_rate", "booking_conversion", "cancellation_rate_passenger", "cancellation_rate_operator").all { v.getValue(it).definition.executable && v.getValue(it).state == MetricState.OK })
        val empty = fx.tenant(); fx.kpi.feed(empty, fx.kpi.attempt(empty, at = "2026-12-05T10:00:00Z"))
        val z = fx.metrics.evaluate(empty, MetricQuery(fx.day)).associateBy { it.definition.metricId }
        assertEquals(MetricState.NOT_COMPUTABLE, z.getValue("booking_conversion").state); assertEquals(null, z.getValue("booking_conversion").value)
        assertEquals(MetricState.NOT_COMPUTABLE, z.getValue("cancellation_rate_passenger").state); assertEquals(null, z.getValue("cancellation_rate_passenger").value)
    }

    @Test
    fun storno_nach_abschluss_aendert_stufe_abgeschlossen_nicht() {
        val t = fx.tenant(); val log = fx.knownLog(t); fx.feed(t, log.events)
        val before = report(t)
        // Fahrgaststorno der bereits abgeschlossenen Buchung (nachträglich eingetroffen)
        assertEquals(ConsumeOutcome.PROCESSED, fx.feed(t, listOf(fx.cancelled(t, log.b1get, "PASSENGER_REQUEST", "FAHRGAST", "PASSENGER", "2026-12-01T08:00:00Z"))).single())
        val after = report(t)
        assertEquals(1, after.completedBookings, "abgeschlossen bleibt"); assertEquals(before.completedBookings, after.completedBookings)
        assertEquals(before.cancelledByPassenger, after.cancelledByPassenger, "die Buchung zählt nicht zusätzlich als Fahrgaststorno")
        assertEquals(before.confirmedBookings, after.confirmedBookings)
        val v = fx.metrics.evaluate(t, MetricQuery(fx.day, metricId = "cancellation_rate_passenger")).single()
        assertEquals(BigDecimal.ONE, v.numerator, "auch die Quote zählt sie nicht")
        // Betreiberstorno nicht als Fahrgaststorno und umgekehrt
        assertEquals(1, after.cancelledByOperator); assertEquals(1, after.cancelledByPassenger)
        assertTrue(fx.metrics.reconcileFunnel(t, fx.day).consistent)
    }

    @Test
    fun doppelte_ereigniszustellung_aendert_keine_zahl() {
        val t = fx.tenant(); val log = fx.knownLog(t); fx.feed(t, log.events)
        val before = report(t); val dump = fx.kpi.base.factDump(t)
        val again = fx.feed(t, log.events)
        assertTrue(again.all { it == ConsumeOutcome.DUPLICATE }, again.toString())
        assertEquals(before, report(t)); assertEquals(dump, fx.kpi.base.factDump(t))
        // technischer Retry einer Anfrage (höhere Revision, gleiche attempt_id) zählt nicht neu
        val first = log.events.first { it.type == "booking.attempt_recorded" }
        val id = UUID.fromString(Regex("\"attempt_id\":\"([^\"]+)\"").find(first.json)!!.groupValues[1])
        fx.kpi.feed(t, fx.kpi.attempt(t, id, revision = 2, outcome = "REJECTED", primary = "NO_CAPACITY"))
        assertEquals(before.requests, report(t).requests)
    }

    @Test
    fun umsortierte_ereignisse_aendern_keine_zahl() {
        val ordered = fx.tenant(); val log = fx.knownLog(ordered); fx.feed(ordered, log.events)
        val expected = report(ordered)
        // umsortiert (mehrere Mischungen, einschließlich Storno vor Bestätigung) und doppelt, parallel aus acht Workern
        repeat(3) { round ->
            val t = fx.tenant(); val own = fx.knownLog(t).events
            val events = (own + own.shuffled(Random(round))).shuffled(Random(100 + round))
            val pool = Executors.newFixedThreadPool(8); val go = CountDownLatch(1)
            val futures = events.map { e -> pool.submit<ConsumeOutcome> { go.await(); fx.kpi.base.analytics.facts.consume(t, e.json) } }
            go.countDown(); val outcomes = futures.map { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
            assertEquals(own.size, outcomes.count { it == ConsumeOutcome.PROCESSED }, "jedes Ereignis genau einmal wirksam")
            assertEquals(expected.copy(asOf = null, sourceEvents = 0), report(t).copy(asOf = null, sourceEvents = 0), "Runde $round")
            assertEquals(expected.asOf, report(t).asOf, "derselbe Datenstand")
            assertTrue(fx.metrics.reconcileFunnel(t, fx.day).consistent)
            assertFalse(fx.metrics.funnel(t, fx.day).state == MetricState.NOT_COMPUTABLE)
        }
    }
}

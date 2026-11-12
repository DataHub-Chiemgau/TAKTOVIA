package de.taktovia.bookingdispatch

import de.taktovia.bookingdispatch.api.AttemptOutcome
import de.taktovia.bookingdispatch.api.CandidateGate
import de.taktovia.bookingdispatch.api.CandidateGateResult
import de.taktovia.bookingdispatch.api.CandidateRejection
import de.taktovia.bookingdispatch.api.CandidateRequest
import de.taktovia.bookingdispatch.api.CandidateSearch
import de.taktovia.bookingdispatch.api.CandidateSearchRequest
import de.taktovia.bookingdispatch.api.RejectionCategory
import de.taktovia.bookingdispatch.api.RejectionCode
import de.taktovia.bookingdispatch.api.RejectionMetric
import de.taktovia.bookingdispatch.api.ResourceVector
import de.taktovia.bookingdispatch.api.RidePlace
import de.taktovia.bookingdispatch.api.RideTimeWindow
import de.taktovia.bookingdispatch.api.VehicleCandidates
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.servicegeo.api.PublishRelationRule
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** WP-M2-13 / LH-6.5-OPT-011: standardisierte Ablehnungsgründe, deterministischer Primärgrund, technischer Fehler zählt nicht (echte Datenbank, Runtime-Rolle). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RejectionReasonTest {
    private lateinit var fx: RideOfferFixture
    private lateinit var w: RideOfferFixture.World
    private val desired = "2026-11-02T10:00:00+01:00"

    @BeforeAll fun up() { fx = RideOfferFixture(); w = fx.world() }
    @AfterAll fun down() = fx.close()

    private fun row(w: RideOfferFixture.World, id: UUID) = fx.tx.run(w.tenant) { j ->
        j.queryForObject("SELECT outcome, primary_rejection, secondary_rejections::text FROM taktovia.travel_attempt WHERE id = ?", { rs, _ -> Triple(rs.getString(1), rs.getString(2), rs.getString(3)) }, id)!!
    }

    @Test
    fun kapazitaet_regelverbot_gebiet_ergeben_drei_gruende() {
        // (a) kein freier Platz: die Kandidatensuche meldet Kapazität
        val capacity = fx.requests(gate = { _, _ -> CandidateGateResult.NoCandidate(listOf(RejectionCode.NO_CAPACITY)) })
        val a = capacity.request(fx.passenger(w.tenant), fx.command(w, desired))
        // (b) Relation per Regel verboten: erlaubte Zielpunkte enthalten nur den Markt, Ziel ist der Bahnhof
        val rule = fx.world()
        fx.geo.relations.publish(rule.admin, rule.serviceId, 0, PublishRelationRule(listOf(rule.stopA.toString()), RideOfferFixture.T0, null, "Nur Marktplatz als Ziel"))
        val b = fx.requests().request(fx.passenger(rule.tenant), fx.command(rule, desired).copy(destination = RidePlace(13.80, 51.10, rule.stopB)))
        // (c) Ziel außerhalb des Gebiets
        val c = fx.requests().request(fx.passenger(w.tenant), fx.command(w, desired, destination = RidePlace(13.80, 52.50)))
        assertEquals(RejectionCode.NO_CAPACITY, a.rejection!!.primary); assertEquals(RejectionCode.RULE_FORBIDDEN, b.rejection!!.primary); assertEquals(RejectionCode.OUTSIDE_AREA, c.rejection!!.primary)
        assertEquals(3, setOf(a.rejection!!.primary, b.rejection!!.primary, c.rejection!!.primary).size, "drei verschiedene standardisierte Gründe")
        assertEquals(setOf(RejectionMetric.CAPACITY, null, RejectionMetric.AREA), setOf(a.rejection!!.primary.metric, b.rejection!!.primary.metric, c.rejection!!.primary.metric))
        // jeder abgelehnte Versuch trägt Primärgrund und Nebengründe in travel_attempt (Datenbasis für WP-M4-46/-51)
        assertEquals(Triple("REJECTED", "NO_CAPACITY", "{}"), row(w, a.attemptId)); assertEquals("RULE_FORBIDDEN", row(rule, b.attemptId).second)
        assertEquals("OUTSIDE_AREA", row(w, c.attemptId).second)
        assertTrue(c.rejection!!.secondary.all { it != RejectionCode.OUTSIDE_AREA }, "Primärgrund steht nie zusätzlich in den Nebengründen")
        // jede Ablehnung hat genau einen Primärgrund: ohne Primärgrund (Zeitfenster geliefert) gibt es auch keine Nebengründe
        val ok = fx.requests().request(fx.passenger(w.tenant), fx.command(w, desired))
        assertEquals(Triple("OFFERED", null, "{}"), row(w, ok.attemptId))
    }

    @Test
    fun primaergrund_deterministisch_und_technischer_fehler_zaehlt_nicht() {
        // reine Wahl: unabhängig von der Reihenfolge, in der Ursachen festgestellt werden
        val causes = listOf(RejectionCode.NO_CAPACITY, RejectionCode.OUTSIDE_AREA, RejectionCode.EQUIPMENT_MISSING, RejectionCode.LEAD_TIME, RejectionCode.NO_CAPACITY)
        val expected = RejectionCode.decide(causes)!!
        assertEquals(RejectionCode.LEAD_TIME, expected.primary)
        assertEquals(listOf(RejectionCode.OUTSIDE_AREA, RejectionCode.EQUIPMENT_MISSING, RejectionCode.NO_CAPACITY), expected.secondary)
        repeat(50) { assertEquals(expected, RejectionCode.decide(causes.shuffled())) }
        assertNull(RejectionCode.decide(listOf(RejectionCode.TECHNICAL)), "TECHNICAL ist nie ein Grund")
        assertEquals(RejectionCode.NO_CAPACITY, RejectionCode.decide(listOf(RejectionCode.TECHNICAL, RejectionCode.NO_CAPACITY))!!.primary)
        // dieselbe Anfrage auf demselben Datenstand ergibt denselben Primärgrund (auch mit mehreren gleichzeitigen Ursachen)
        val api = fx.requests(); val ctx = fx.passenger(w.tenant)
        val several = fx.command(w, "2026-11-02T23:00:00+01:00", origin = RidePlace(14.5, 51.0))
        val first = api.request(ctx, several.copy(idempotencyKey = "det-schluessel-000001")); val second = api.request(ctx, several.copy(idempotencyKey = "det-schluessel-000002"))
        assertEquals(first.rejection, second.rejection); assertEquals(RejectionCode.OUTSIDE_HOURS, first.rejection!!.primary)

        // technischer Fehler (Routing-Timeout im Kandidatentor): Versuch erfasst, aber keine fachliche Ablehnung
        val failing = AtomicInteger(); var broken = true
        val flaky = fx.requests(gate = { _, _ -> failing.incrementAndGet(); if (broken) throw java.net.SocketTimeoutException("Routing-Timeout") else CandidateGateResult.Reached })
        val p = fx.world(); val pc = fx.passenger(p.tenant); val cmd = fx.command(p, desired, key = "technisch-schluessel-01")
        val err = assertThrows<ProblemException> { flaky.request(pc, cmd) }
        assertEquals(ErrorCode.SERVICE_UNAVAILABLE, err.code)
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.travel_attempt WHERE tenant_id = ? AND outcome = 'TECHNICAL_ERROR' AND primary_rejection IS NULL", p.tenant.value))
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.travel_attempt WHERE tenant_id = ? AND outcome = 'REJECTED'", p.tenant.value), "nicht als Ablehnung gezählt")
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.travel_attempt t JOIN taktovia.rejection_reason r ON r.code = t.primary_rejection WHERE t.tenant_id = ?", p.tenant.value))
        // Retry mit demselben Schlüssel: derselbe Versuch wird fortgeschrieben, kein zweiter
        broken = false
        val ok = flaky.request(pc, cmd)
        assertEquals(AttemptOutcome.OFFERED, ok.outcome)
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.travel_attempt WHERE tenant_id = ?", p.tenant.value))
        assertEquals("OFFERED", row(p, ok.attemptId).first)
        // Ereignisse: TECHNICAL_ERROR (Revision 1), danach RECORDED (Revision 2) desselben Versuchs
        assertEquals(listOf(1L, 2L), fx.tx.run(p.tenant) { j -> j.queryForList("SELECT aggregate_revision FROM taktovia.outbox WHERE aggregate_id = ? ORDER BY aggregate_revision", Long::class.java, ok.attemptId) })
    }

    @Test
    fun parallele_wiederholung_einer_ablehnung_ein_versuch_ein_primaergrund() {
        val p = fx.world(); val api = fx.requests(); val ctx = fx.passenger(p.tenant)
        val cmd = fx.command(p, "2026-11-02T23:00:00+01:00", key = "parallel-ablehnung-01")
        val pool = Executors.newFixedThreadPool(20); val go = CountDownLatch(1)
        try {
            val futures = (1..30).map { pool.submit<de.taktovia.bookingdispatch.api.RideOfferResult> { go.await(); api.request(ctx, cmd) } }
            go.countDown()
            val results = futures.map { it.get(60, TimeUnit.SECONDS) }
            assertEquals(1, results.map { it.attemptId }.toSet().size); assertEquals(1, results.map { it.rejection }.toSet().size)
            assertEquals(RejectionCode.OUTSIDE_HOURS, results.first().rejection!!.primary)
        } finally { pool.shutdownNow() }
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.travel_attempt WHERE tenant_id = ? AND outcome = 'REJECTED' AND primary_rejection = 'OUTSIDE_HOURS'", p.tenant.value), "keine Doppelzählung")
    }

    @Test
    fun katalog_in_der_datenbank_entspricht_der_codeliste() {
        val rows = fx.tx.run(w.tenant) { j -> j.query("SELECT code, priority, counts_as_rejection, metric, label, next_action FROM taktovia.rejection_reason") { rs, _ ->
            listOf(rs.getString(1), rs.getInt(2), rs.getBoolean(3), rs.getString(4), rs.getString(5), rs.getString(6))
        } }
        assertEquals(RejectionCode.entries.size, rows.size)
        for (r in rows) {
            val code = RejectionCode.valueOf(r[0] as String)
            assertEquals(code.priority, r[1], code.name); assertEquals(code.counts, r[2], code.name); assertEquals(code.metric?.name, r[3], code.name)
            assertEquals(code.text, r[4], code.name); assertEquals(code.nextAction, r[5], code.name)
        }
    }

    @Test
    fun tor_ueber_kandidatenfilter_bildet_kategorien_auf_codes_ab() {
        fun gate(rej: CandidateRejection?) = CandidateGate.overVehicleCandidates(object : VehicleCandidates {
            override fun find(ctx: RequestContext, request: CandidateRequest) = CandidateSearch(emptyList(), emptyList(), rej)
        })
        val req = CandidateSearchRequest(w.serviceId, ResourceVector(seats = 2), w.stopA, RideTimeWindow(Instant.parse("2026-11-02T09:00:00Z"), Instant.parse("2026-11-02T09:15:00Z")),
            RideTimeWindow(Instant.parse("2026-11-02T09:10:00Z"), Instant.parse("2026-11-02T09:25:00Z")))
        val ctx = fx.passenger(w.tenant)
        val r = gate(CandidateRejection(RejectionCategory.EQUIPMENT, listOf(RejectionCategory.PERSONNEL, RejectionCategory.CAPACITY))).check(ctx, req) as CandidateGateResult.NoCandidate
        assertEquals(listOf(RejectionCode.EQUIPMENT_MISSING, RejectionCode.NO_STAFF, RejectionCode.NO_CAPACITY), r.causes)
        assertEquals(RejectionCode.EQUIPMENT_MISSING, RejectionCode.decide(r.causes)!!.primary)
        // Kandidat gefunden bzw. kein fester Abholhaltepunkt: erreicht die Suche
        assertEquals(CandidateGateResult.Reached, gate(null).check(ctx, req))
        assertEquals(CandidateGateResult.Reached, gate(CandidateRejection(RejectionCategory.CAPACITY, emptyList())).check(ctx, req.copy(pickupStopId = null)))
    }
}

package de.taktovia.bookingdispatch

import de.taktovia.bookingdispatch.InsertionTestSupport.at
import de.taktovia.bookingdispatch.InsertionTestSupport.stop
import de.taktovia.bookingdispatch.InsertionTestSupport.stopKeys
import de.taktovia.bookingdispatch.api.HardLimits
import de.taktovia.bookingdispatch.api.InsertionDemand
import de.taktovia.bookingdispatch.api.InsertionQuery
import de.taktovia.bookingdispatch.api.InsertionVariant
import de.taktovia.bookingdispatch.api.InsertionVehicle
import de.taktovia.bookingdispatch.api.InsertionWorker
import de.taktovia.bookingdispatch.api.MultiObjectiveScoring
import de.taktovia.bookingdispatch.api.PlanStop
import de.taktovia.bookingdispatch.api.PromiseWindow
import de.taktovia.bookingdispatch.api.RankedResult
import de.taktovia.bookingdispatch.api.ResourceVector
import de.taktovia.bookingdispatch.api.ScoringWeights
import de.taktovia.bookingdispatch.api.ScoringWeightsSource
import de.taktovia.bookingdispatch.api.StopMovement
import de.taktovia.platform.context.Actor
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.tenant.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * WP-M2-20 / LH-6.5-OPT-008: Mehrzielbewertung. Die Bewertungsfunktion ist rein; die Varianten der ersten Tests sind von Hand gebaute, in sich zulässige Pläne zweier Fahrzeuge.
 * Der Test mit paralleler Gewichtsänderung läuft gegen den ECHTEN Python-Worker (übersprungen ohne python3, mit `TAKTOVIA_REQUIRE_DB=true` Fehler).
 *
 * Referenzszenario: Abholfenster ab +600 s bis +3600 s, `max_wait_min` 60, Direktfahrzeit 600 s. Fahrzeug A holt sofort ab (Wartezeit 0), fährt aber 1500 s leer an;
 * Fahrzeug B ist schon da (Leerstrecke 0), holt aber erst nach 1200 s Wartezeit ab.
 */
class MultiObjectiveScoringTest {
    private val limits = HardLimits(60, 100, 0, "cfg-7")
    private val bookingId = UUID.fromString("00000000-0000-0000-0000-0000000000b1")
    private val vec = ResourceVector(1, 0)
    private val vehA = UUID.fromString("00000000-0000-0000-0000-00000000000a")
    private val vehB = UUID.fromString("00000000-0000-0000-0000-00000000000b")

    private fun window() = PromiseWindow(bookingId, at(600), at(3600), at(0), at(7200), limits, 600, 60, at(600))

    private fun vehicle(id: UUID) = InsertionVehicle(id, null, 0, ResourceVector(4, 1), stop(1), at(0), at(36000), emptyList(), stopKeys, emptyList())

    /** Zulässige Variante: Abholung bei [pickupAt] Sekunden, Ankunft nach [rideSec] Sekunden an Bord (ab Ende der Bedienung). */
    private fun variant(vehicleId: UUID, pickupAt: Long, leadIn: Int, rideSec: Long = 600, drive: Int = 600, w: PromiseWindow = window()): InsertionVariant {
        val stops = listOf(
            PlanStop(0, stop(3).stopRevisionId, at(pickupAt), listOf(StopMovement(bookingId, vec)), emptyList(), 600, 6000),
            PlanStop(1, stop(5).stopRevisionId, at(pickupAt + 60 + rideSec), emptyList(), listOf(StopMovement(bookingId, vec)), null, null),
        )
        return InsertionVariant(vehicleId, null, 0, true, stops, 0, 1, drive, w, leadIn)
    }

    private val a = variant(vehA, 600, 1500)
    private val b = variant(vehB, 1800, 0)
    private val fleet = listOf(vehicle(vehA), vehicle(vehB))

    private fun order(weights: ScoringWeights) = MultiObjectiveScoring.rank(listOf(a, b), fleet, weights).ranked.map { it.variant.vehicleId }

    @Test
    fun gewichtsaenderung_aendert_rangfolge_nachvollziehbar() {
        // Standardgewichte 40/20/25/15: Leerstrecke von A (Anteil 0,71) wiegt schwerer als die Wartezeit von B (Anteil 0,33)
        val base = ScoringWeights(40, 20, 25, 15, "cfg-rev-1")
        val r1 = MultiObjectiveScoring.rank(listOf(a, b), fleet, base)
        assertEquals(listOf(vehB, vehA), r1.ranked.map { it.variant.vehicleId })
        // Teilwerte und Gewichte je Variante ausgegeben, Revision genannt
        val top = r1.ranked[0]; val second = r1.ranked[1]
        assertEquals(1, top.rank); assertEquals(2, second.rank)
        assertEquals(1200L, top.parts.waitSec); assertEquals(0, top.parts.emptySec)
        assertEquals(0L, second.parts.waitSec); assertEquals(1500, second.parts.emptySec)
        assertEquals(1200.0 / 3600.0, top.parts.waitPenalty, 1e-9)
        assertEquals(1500.0 / 2100.0, second.parts.emptyPenalty, 1e-9)
        assertEquals("cfg-rev-1", top.weights.revisionId); assertEquals(base, second.weights)
        assertTrue(top.score > second.score)
        // Erhöhung des Gewichts der Wartezeit kehrt die Rangfolge um (Richtung wie erwartet), Senkung des Leerstreckengewichts ebenso
        assertEquals(listOf(vehA, vehB), order(base.copy(wait = 100, revisionId = "cfg-rev-2")))
        assertEquals(listOf(vehA, vehB), order(base.copy(empty = 0)))
        // Erhöhung des Leerstreckengewichts verstärkt die ursprüngliche Reihenfolge
        assertEquals(listOf(vehB, vehA), order(base.copy(empty = 100)))
        // Betriebsqualität: ohne Reserve (Abholung knapp am späten Rand) ist die Variante bei hohem Qualitätsgewicht schlechter
        val tight = variant(vehB, 3600 - 300, 0)
        val q0 = MultiObjectiveScoring.rank(listOf(a, tight), fleet, ScoringWeights(0, 0, 0, 100, null))
        assertEquals(listOf(vehA, vehB), q0.ranked.map { it.variant.vehicleId })
        assertEquals(300L, q0.ranked[1].parts.slackSec)
    }

    @Test
    fun unzulaessige_variante_bleibt_ausgeschlossen() {
        // Variante C ist bei Wartezeit, Umweg und Leerstrecke unschlagbar, verletzt aber das Abholfenster (nach spätestem Rand)
        val vehC = UUID.fromString("00000000-0000-0000-0000-00000000000c")
        val bad = variant(vehC, 3700, 0)
        val all = listOf(a, b, bad)
        val vehicles = fleet + vehicle(vehC)
        for (w in listOf(ScoringWeights(0, 0, 0, 100, "x"), ScoringWeights(100, 100, 100, 0, "x"), ScoringWeights(100, 0, 0, 0, "x"), ScoringWeights(0, 0, 100, 0, "x"))) {
            val r = MultiObjectiveScoring.rank(all, vehicles, w)
            assertEquals(listOf(vehC), r.excluded.map { it.variant.vehicleId }, "Gewichte $w")
            assertEquals(0.0, r.excluded.single().score)
            assertTrue("PICKUP_OUTSIDE_WINDOW" in r.excluded.single().reasons || "MAX_WAIT_EXCEEDED" in r.excluded.single().reasons, r.excluded.single().reasons.toString())
            assertTrue(r.ranked.none { it.variant.vehicleId == vehC })
            assertTrue(r.ranked.all { it.score > 0.0 })
        }
        // auch Umweg über der harten Grenze (Bordzeit 2x Direktfahrzeit bei max 100 %) schließt aus
        val detour = variant(vehC, 600, 0, rideSec = 1500)
        val r = MultiObjectiveScoring.rank(listOf(detour), vehicles, ScoringWeights(0, 0, 0, 100, null))
        assertTrue(r.ranked.isEmpty()); assertTrue("MAX_DETOUR_EXCEEDED" in r.excluded.single().reasons)
        // und eine Variante ohne bekanntes Fahrzeug
        assertEquals(listOf("UNKNOWN_VEHICLE"), MultiObjectiveScoring.rank(listOf(a), emptyList(), ScoringWeights.defaults()).excluded.single().reasons)
    }

    @Test
    fun gewicht_ausserhalb_des_wertebereichs_wird_abgelehnt() {
        assertThrows(IllegalArgumentException::class.java) { ScoringWeights(-1, 20, 25, 15, null) }
        assertThrows(IllegalArgumentException::class.java) { ScoringWeights(40, 101, 25, 15, null) }
        assertThrows(IllegalArgumentException::class.java) { ScoringWeights(0, 0, 0, 0, null) }
        assertThrows(IllegalArgumentException::class.java) { ScoringWeights.fromValues(mapOf("opt_weight_wait" to 250), "r") }
        assertThrows(IllegalArgumentException::class.java) { ScoringWeights.fromValues(mapOf("opt_weight_wait" to 1.5), "r") }
        assertThrows(IllegalArgumentException::class.java) { ScoringWeights.fromValues(mapOf("opt_weight_wait" to "viel"), "r") }
        assertEquals(ScoringWeights(10, 20, 25, 15, "r"), ScoringWeights.fromValues(mapOf("opt_weight_wait" to 10L), "r"))
        assertEquals(ScoringWeights.defaults("r"), ScoringWeights.fromValues(emptyMap(), "r"))
    }

    @Test
    fun gleiche_eingaben_und_revision_ergeben_dieselbe_rangfolge_und_gleichstand_ist_deterministisch() {
        val w = ScoringWeights(40, 20, 25, 15, "r1")
        val first = MultiObjectiveScoring.rank(listOf(a, b), fleet, w)
        repeat(20) { assertEquals(first, MultiObjectiveScoring.rank(listOf(a, b), fleet, w)) }
        assertEquals(first.ranked.map { it.variant.vehicleId }, MultiObjectiveScoring.rank(listOf(b, a), fleet, w).ranked.map { it.variant.vehicleId })
        // exakter Gleichstand (gleiche Teilwerte, gleiche Mehrfahrzeit): Fahrzeug-ID entscheidet, unabhängig von der Eingabereihenfolge
        val twin1 = variant(vehA, 1800, 0); val twin2 = variant(vehB, 1800, 0)
        val t1 = MultiObjectiveScoring.rank(listOf(twin2, twin1), fleet, w); val t2 = MultiObjectiveScoring.rank(listOf(twin1, twin2), fleet, w)
        assertEquals(t1.ranked[0].score, t1.ranked[1].score)
        assertEquals(listOf(vehA, vehB), t1.ranked.map { it.variant.vehicleId }); assertEquals(t1.ranked, t2.ranked)
        // bei Gleichstand der Bewertung entscheidet zuerst die Mehrfahrzeit (Leerweg/Fahrzeit)
        val shorter = variant(vehB, 1800, 0, drive = 100); val longer = variant(vehA, 1800, 0, drive = 900)
        assertEquals(listOf(vehB, vehA), MultiObjectiveScoring.rank(listOf(longer, shorter), fleet, w).ranked.map { it.variant.vehicleId })
    }

    @Test
    fun gewichtsaenderung_parallel_zur_berechnung_wirkt_erst_auf_die_naechste() {
        InsertionTestSupport.WorkerProcess().use { worker ->
            val original = ScoringWeights(40, 20, 25, 15, "rev-1")
            val changed = ScoringWeights(0, 0, 0, 100, "rev-2")
            val source = AtomicReference(original)
            val inWorker = CountDownLatch(1); val release = CountDownLatch(1)
            val blocking = InsertionWorker { body, budget -> inWorker.countDown(); check(release.await(10, TimeUnit.SECONDS)); worker.worker.insert(body, budget) }
            val svc = de.taktovia.bookingdispatch.api.InsertionService(InsertionTestSupport.CorridorRouting(), blocking)
            val ctx: RequestContext = RequestContext(Actor(ActorType.HUMAN, "https://idp.test/realms/booking", "scoring", "sid-scoring", true), TenantId(UUID.randomUUID()), setOf("dispatcher"), setOf("rides:read"), emptySet(), UUID.randomUUID())
            val v1 = vehicle(vehA); val v2 = vehicle(vehB)
            val d = InsertionDemand(UUID.randomUUID(), stop(3), stop(5), vec, at(600), at(3600), at(0), at(7200))
            val q = InsertionQuery(d, listOf(v1, v2), InsertionTestSupport.PARAMS, "car", Duration.ofMillis(3000))
            val pool = Executors.newSingleThreadExecutor()
            try {
                val running = pool.submit<RankedResult> { svc.findRanked(ctx, q, ScoringWeightsSource { source.get() }) }
                check(inWorker.await(10, TimeUnit.SECONDS)) { "Berechnung nicht gestartet" }
                source.set(changed)            // Gewichtsänderung mitten in der Berechnung
                release.countDown()
                val result = running.get(20, TimeUnit.SECONDS) as RankedResult.Ranked
                assertEquals("rev-1", result.ranking.weights.revisionId)
                assertTrue(result.ranking.ranked.isNotEmpty() && result.ranking.ranked.all { it.weights == original })
                val vehicles = listOf(v1, v2)
                assertEquals(MultiObjectiveScoring.rank(result.outcome.variants, vehicles, original), result.ranking)
                // die nächste Berechnung nennt die neue Revision
                release.countDown()
                val next = svc.findRanked(ctx, q, ScoringWeightsSource { source.get() }) as RankedResult.Ranked
                assertEquals("rev-2", next.ranking.weights.revisionId)
            } finally { pool.shutdownNow() }
        }
    }
}

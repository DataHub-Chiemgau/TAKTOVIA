package de.taktovia.bookingdispatch

import de.taktovia.bookingdispatch.api.HoldRideOffer
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * WP-M2-22 / LH-6.5-OPT-009 (Konkurrenztests): 100 parallele Holds über den Hold-Befehl des Angebots gegen echte parallele Transaktionen unter der Runtime-Rolle; die Prüfung der
 * Belegung geschieht unabhängig vom Code unter Test mit Administratorrechten. Lastprofil: 100 gleichzeitige Befehle (D-05 blockiert den Bau nicht).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HoldConcurrencyTest {
    private lateinit var fx: OfferObjectsFixture
    @BeforeAll fun up() { fx = OfferObjectsFixture() }
    @AfterAll fun down() = fx.close()

    private class Outcome(val ok: Boolean, val code: ErrorCode?, val unexpected: Throwable?)

    /** Führt [n] Befehle gleichzeitig aus; jede Transaktion endet mit Erfolg oder einem definierten Fachgrund (`ProblemException`), nie mit einem unbehandelten Fehler (z. B. Deadlock). */
    private fun parallel(n: Int, job: (Int) -> Unit): List<Outcome> {
        val pool = Executors.newFixedThreadPool(n); val start = CountDownLatch(1)
        try {
            val fs = (0 until n).map { i -> pool.submit(Callable { start.await(); try { job(i); Outcome(true, null, null) } catch (e: ProblemException) { Outcome(false, e.code, null) } catch (t: Throwable) { Outcome(false, null, t) } }) }
            start.countDown(); return fs.map { it.get() }
        } finally { pool.shutdownNow() }
    }

    /** Belegung je Abschnitt `(Sitze, Rollstuhlplätze)` aus der Datenbank: gültige gehaltene und bestätigte Holds. */
    private fun occupancy(runId: UUID): List<Pair<Int, Int>> = fx.base.occupancy(runId, 6)

    private fun assertDefined(results: List<Outcome>) {
        results.forEach { assertTrue(it.unexpected == null, "unbehandelter Fehler: ${it.unexpected}") }
        assertTrue(results.filter { !it.ok }.all { it.code == ErrorCode.CAPACITY_UNAVAILABLE }, "abgewiesen nur mit dem Fachgrund CAPACITY_UNAVAILABLE: ${results.mapNotNull { it.code }.toSet()}")
    }

    @Test
    fun test_100_parallele_holds_hoechstens_einer() {
        val w = fx.world(); val run = fx.run(w, OfferObjectsFixture.cap(1, 0))
        val offers = (0 until 100).map { fx.passenger(w.tenant).let { p -> p to fx.issue(w, p, fx.assigning(run, 1, 0, 3), passengers = 1) } }
        val results = parallel(100) { i -> val (p, o) = offers[i]; fx.offers.hold(p, o.id, HoldRideOffer(1, "hold-$i-" + UUID.randomUUID(), o.quote.payAtPlatformMinor, "EUR")) }
        assertDefined(results)
        assertEquals(1, results.count { it.ok }, "höchstens ein Hold auf den letzten Sitz")
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.capacity_hold WHERE run_id = ? AND state = 'HELD'", run.runId))
        assertTrue(occupancy(run.runId).all { it.first <= 1 && it.second == 0 }, "kein Abschnitt über Kapazität: ${occupancy(run.runId)}")
        // die 99 abgewiesenen Angebote sind unverändert (Revision 1, kein Hold-Bezug)
        assertEquals(99, fx.count("SELECT count(*) FROM taktovia.ride_offer WHERE run_id = ? AND hold_id IS NULL AND revision = 1 AND state = 'OFFERED'", run.runId))
    }

    @Test
    fun test_belegter_rollstuhlplatz_und_gegenlaeufige_abschnittsreihenfolge() {
        val w = fx.world()
        // (1) belegter Rollstuhlplatz: ein Platz, 100 Bewerber, ein Sitz bleibt unabhängig davon buchbar
        val run = fx.run(w, OfferObjectsFixture.cap(4, 1))
        val wc = (0 until 100).map { fx.passenger(w.tenant).let { p -> p to fx.issue(w, p, fx.assigning(run, 1, 0, 3), passengers = 1, wheelchair = 1) } }
        val r1 = parallel(100) { i -> val (p, o) = wc[i]; fx.offers.hold(p, o.id, HoldRideOffer(1, "wc-$i-" + UUID.randomUUID(), o.quote.payAtPlatformMinor, "EUR")) }
        assertDefined(r1)
        assertEquals(1, r1.count { it.ok }, "ein Rollstuhlplatz: höchstens ein Hold")
        assertTrue(occupancy(run.runId).all { it.second <= 1 && it.first <= 4 })
        // (2) gegenläufige Abschnittsreihenfolge als Deadlock-Provokation: 100 Befehle auf überlappende, abwechselnd auf- und absteigend beginnende Abschnitte desselben Umlaufs und
        // auf zwei Umläufe in wechselnder Reihenfolge; jede Transaktion endet mit Erfolg oder dem Fachgrund, es gibt keinen Deadlock (eine Sperre je Umlauf, stabile Reihenfolge)
        val runA = fx.run(w, OfferObjectsFixture.cap(3, 0)); val runB = fx.run(w, OfferObjectsFixture.cap(3, 0))
        val spans = listOf(0 to 4, 2 to 6, 1 to 3, 3 to 5, 0 to 2, 4 to 6)
        val offers = (0 until 100).map { i ->
            val p = fx.passenger(w.tenant); val (from, to) = if (i % 2 == 0) spans[i % spans.size] else spans[spans.size - 1 - i % spans.size]
            val first = if (i % 3 == 0) runB else runA
            p to fx.issue(w, p, fx.assigning(first, 1, from, to), passengers = 1)
        }
        val r2 = parallel(100) { i -> val (p, o) = offers[i]; fx.offers.hold(p, o.id, HoldRideOffer(1, "span-$i-" + UUID.randomUUID(), o.quote.payAtPlatformMinor, "EUR")) }
        assertDefined(r2)
        assertTrue(r2.count { it.ok } in 1..99, "es gibt Erfolge und Abweisungen: ${r2.count { it.ok }}")
        for (r in listOf(runA, runB)) assertTrue(occupancy(r.runId).all { it.first <= 3 && it.second == 0 }, "Belegung über Kapazität: ${occupancy(r.runId)}")
        // Gegenprobe: die Belegung je Abschnitt entspricht genau den angenommenen Holds
        val placed = fx.count("SELECT count(*) FROM taktovia.capacity_hold WHERE run_id IN (?, ?) AND state = 'HELD'", runA.runId, runB.runId)
        assertEquals(r2.count { it.ok }, placed)
    }
}

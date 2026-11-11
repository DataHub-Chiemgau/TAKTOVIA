package de.taktovia.booking

import de.taktovia.bookingdispatch.BookingFixture
import de.taktovia.bookingdispatch.api.BookingdispatchApi
import de.taktovia.bookingdispatch.api.HoldOutcome
import de.taktovia.bookingdispatch.api.PlaceHold
import de.taktovia.bookingdispatch.api.ResourceVector
import de.taktovia.bookingdispatch.api.TimeWindow
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/** WP-M2-14: konkurrierende Kapazitätsprüfung (echte parallele Transaktionen unter der Runtime-Rolle). Ausführung des vollständigen Hold/Confirm-Pfads: WP-M2-22/-23. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WheelchairConcurrencyTest {
    private lateinit var fx: BookingFixture
    private lateinit var api: BookingdispatchApi
    private val window = TimeWindow(Instant.parse("2026-12-01T06:00:00Z"), Instant.parse("2026-12-01T07:00:00Z"))

    @BeforeAll fun up() { fx = BookingFixture(); api = BookingdispatchApi.create(fx.ctxTx) }
    @AfterAll fun down() = fx.close()

    private fun cap(seats: Int, wheelchair: Int) = """{"schema_version":"1.0","seats":$seats,"wheelchair":$wheelchair,"equipment":["RAMP"]}"""

    private fun parallel(n: Int, job: (Int) -> HoldOutcome): List<HoldOutcome> {
        val pool = Executors.newFixedThreadPool(n); val start = CountDownLatch(1)
        try {
            val futures = (0 until n).map { i -> pool.submit(Callable { start.await(); job(i) }) }
            start.countDown()
            return futures.map { it.get() }
        } finally { pool.shutdownNow() }
    }

    private fun place(run: BookingFixture.RunRef, demand: ResourceVector, from: Int, to: Int) =
        api.holds.place(fx.context(fx.tenantA), PlaceHold(run.runId, run.revision, run.serviceId, window, from, to, demand, reference = "ref-" + UUID.randomUUID()))

    /** Belegung je Abschnitt aus der Datenbank (Prüfung unabhängig vom Code unter Test, mit Administratorrechten). */
    private fun occupancy(run: BookingFixture.RunRef): List<Pair<Int, Int>> = fx.admin().use { ds -> ds.connection.use { c ->
        c.prepareStatement(
            "SELECT s.seg, coalesce(sum((h.capacity_vector->>'seats')::int),0), coalesce(sum((h.capacity_vector->>'wheelchair')::int),0) FROM generate_series(0, 20) s(seg) " +
                "LEFT JOIN taktovia.capacity_hold h ON h.run_id = ? AND h.interval_from <= s.seg AND h.interval_to > s.seg AND h.state IN ('HELD','CONSUMED') GROUP BY s.seg ORDER BY s.seg",
        ).use { ps -> ps.setObject(1, run.runId); ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getInt(2) to rs.getInt(3)) } } }
    } }

    @Test
    fun zwei_parallele_rollstuhlbuchungen_hoechstens_eine() {
        repeat(25) { round ->
            val run = fx.createRun(cap(4, 1))
            val results = parallel(2) { place(run, ResourceVector(0, 1), 0, 4) }
            assertEquals(1, results.count { it is HoldOutcome.Placed }, "Runde $round: genau ein Rollstuhlplatz")
            assertTrue(occupancy(run).all { it.second <= 1 })
        }
    }

    @Test
    fun sitz_und_rollstuhl_parallel_keine_negative_belegung() {
        val run = fx.createRun(cap(4, 1))
        val rnd = java.util.Random(20261111)
        val specs = (0 until 100).map { i -> val from = rnd.nextInt(8); Triple(if (i % 2 == 0) ResourceVector(1, 0) else ResourceVector(0, 1), from, from + 1 + rnd.nextInt(4)) }
        val results = parallel(100) { i -> place(run, specs[i].first, specs[i].second, specs[i].third) }   // jede Anfrage eine Transaktion; kein Deadlock, kein Fehler
        assertEquals(100, results.size)
        val occ = occupancy(run)
        assertTrue(occ.all { it.first in 0..4 && it.second in 0..1 }, "Belegung je Abschnitt über Kapazität oder negativ: $occ")
        val placed = results.withIndex().filter { it.value is HoldOutcome.Placed }
        assertTrue(placed.any { specs[it.index].first.wheelchair == 1 } && placed.any { specs[it.index].first.seats == 1 })
        // jeder Abschnitt ist in jeder Dimension so voll wie möglich: ein abgewiesener Bedarf hätte an keiner Stelle seines Intervalls Platz gehabt
        // (Reihenfolge ist zufällig, daher nur die Invariante: Summe der angenommenen Bedarfe je Abschnitt entspricht der gemessenen Belegung)
        val expected = (0..20).map { seg ->
            placed.filter { specs[it.index].second <= seg && specs[it.index].third > seg }.let { l -> l.sumOf { specs[it.index].first.seats } to l.sumOf { specs[it.index].first.wheelchair } }
        }
        assertEquals(expected, occ)
        assertTrue(results.filterIsInstance<HoldOutcome.Rejected>().isNotEmpty(), "bei 100 Anfragen auf vier Sitze und einen Rollstuhlplatz muss abgewiesen werden")
    }
}

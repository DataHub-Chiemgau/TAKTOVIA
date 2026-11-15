package de.taktovia.bookingdispatch

import de.taktovia.bookingdispatch.api.HoldRideOffer
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
 * WP-M5-05 / LH-6.19-NFR-001 (konkurrierend, Kapazität): Eine Probebuchung des Probemandanten (`tenant.synthetic`, V50050) verdrängt keine echte Buchung. Echter Hold-Befehl unter der
 * Runtime-Rolle gegen echte parallele Transaktionen; die Belegung prüft die Datenbank unabhängig vom Code unter Test (Administratorrechte). Eigene Klasse (Cluster wave15-c5).
 *
 * Aufbau: Echtmandant A und Probemandant P haben je einen Umlauf mit **einem** Sitz (der „letzte Platz“). Die Kennzeichnung `synthetic` wird im Testaufbau mit ausgesetztem Wächter gesetzt
 * (im Betrieb setzt sie das Anlegen des Probemandanten, `infra/availability`: probemandant.py; nachträglich ist sie unveränderlich).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProbeBookingCapacityTest {
    private lateinit var fx: OfferObjectsFixture
    @BeforeAll fun up() { fx = OfferObjectsFixture() }
    @AfterAll fun down() = fx.close()

    private fun markSynthetic(tenant: UUID) {
        fx.adminUpdate("ALTER TABLE taktovia.tenant DISABLE TRIGGER tenant_synthetic_immutable")
        try { fx.adminUpdate("UPDATE taktovia.tenant SET synthetic = true WHERE id = ?", tenant) } finally { fx.adminUpdate("ALTER TABLE taktovia.tenant ENABLE TRIGGER tenant_synthetic_immutable") }
    }

    private sealed class Outcome { object Held : Outcome(); data class Rejected(val code: String) : Outcome(); data class Failed(val t: Throwable) : Outcome() }

    private fun parallel(jobs: List<() -> Any?>): List<Outcome> {
        val pool = Executors.newFixedThreadPool(jobs.size); val start = CountDownLatch(1)
        try {
            val fs = jobs.map { job -> pool.submit(Callable<Outcome> { start.await(); try { job(); Outcome.Held } catch (e: ProblemException) { Outcome.Rejected(e.code.name) } catch (t: Throwable) { Outcome.Failed(t) } }) }
            start.countDown(); return fs.map { it.get() }
        } finally { pool.shutdownNow() }
    }

    private fun held(runId: UUID) = fx.count("SELECT count(*) FROM taktovia.capacity_hold WHERE run_id = ? AND state = 'HELD'", runId)

    @Test
    fun probebuchung_parallel_zu_echter_buchung_auf_dem_letzten_platz_verdraengt_keine_echte_buchung() {
        val real = fx.world(); val probe = fx.world(); markSynthetic(probe.tenant.value)
        val realRun = fx.run(real, OfferObjectsFixture.cap(1, 0)); val probeRun = fx.run(probe, OfferObjectsFixture.cap(1, 0))
        val n = 20
        val realOffers = (0 until n).map { fx.passenger(real.tenant).let { p -> p to fx.issue(real, p, fx.assigning(realRun, 1, 0, 3), passengers = 1) } }
        val probeOffers = (0 until n).map { fx.passenger(probe.tenant).let { p -> p to fx.issue(probe, p, fx.assigning(probeRun, 1, 0, 3), passengers = 1) } }
        val jobs = realOffers.mapIndexed { i, (p, o) -> { fx.offers.hold(p, o.id, HoldRideOffer(1, "real-$i-" + UUID.randomUUID(), o.quote.payAtPlatformMinor, "EUR")); Unit } } +
            probeOffers.mapIndexed { i, (p, o) -> { fx.offers.hold(p, o.id, HoldRideOffer(1, "probe-$i-" + UUID.randomUUID(), o.quote.payAtPlatformMinor, "EUR")) } }
        val results = parallel(jobs)
        assertTrue(results.none { it is Outcome.Failed }, "keine unbehandelten Fehler: ${results.filterIsInstance<Outcome.Failed>()}")
        val realResults = results.take(n); val probeResults = results.drop(n)
        assertEquals(1, realResults.count { it is Outcome.Held }, "der letzte Platz des Echtmandanten geht an genau eine echte Buchung, unabhängig von der Probe")
        assertEquals(1, probeResults.count { it is Outcome.Held }, "die Probe hält genau ihren eigenen Platz")
        assertEquals(1, held(realRun.runId)); assertEquals(1, held(probeRun.runId))
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.capacity_hold h JOIN taktovia.tenant t ON t.id = h.tenant_id WHERE t.synthetic AND h.run_id = ?", realRun.runId), "kein Probehold auf dem echten Umlauf")
    }

    @Test
    fun probe_mit_planbindung_auf_fremden_umlauf_belegt_keine_kapazitaet_des_echtmandanten() {
        val real = fx.world(); val probe = fx.world(); markSynthetic(probe.tenant.value)
        val realRun = fx.run(real, OfferObjectsFixture.cap(1, 0))
        val pp = fx.passenger(probe.tenant)
        val rp = fx.passenger(real.tenant); val realOffer = fx.issue(real, rp, fx.assigning(realRun, 1, 0, 3), passengers = 1)
        // Fehlkonfiguration: die Planbindung des Probeangebots zeigt auf den Umlauf des Echtmandanten; parallel dazu hält die echte Buchung den letzten Platz
        val results = parallel(listOf(
            { fx.issue(probe, pp, fx.assigning(realRun, 1, 0, 3), passengers = 1) },
            { fx.offers.hold(rp, realOffer.id, HoldRideOffer(1, "real-" + UUID.randomUUID(), realOffer.quote.payAtPlatformMinor, "EUR")) }))
        val probeFailure = (results[0] as? Outcome.Failed)?.t
        assertTrue(probeFailure is org.springframework.dao.DataIntegrityViolationException, "die Datenbank weist die Bindung an den fremden Umlauf ab (mandantengebundener Fremdschlüssel): ${results[0]}")
        assertTrue(results[1] is Outcome.Held, "die echte Buchung erhält den Platz: ${results[1]}")
        assertEquals(1, held(realRun.runId))
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.capacity_hold WHERE tenant_id = ?", probe.tenant.value), "der Probemandant hält nichts")
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.ride_offer WHERE tenant_id = ? AND run_id = ?", probe.tenant.value, realRun.runId))
    }
}

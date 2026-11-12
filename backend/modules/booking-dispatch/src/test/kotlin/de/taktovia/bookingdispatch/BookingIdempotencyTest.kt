package de.taktovia.bookingdispatch

import de.taktovia.booking.ConfirmFixture
import de.taktovia.bookingdispatch.api.ConfirmBooking
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * WP-M2-24 / LH-6.4-BKG-012, fachliche Sicherung (zweite Sicherung hinter dem Idempotenzregister): Wiederholung des Confirm erzeugt nie eine zweite aktive Buchung, auch nach Ablauf des
 * Angebots, und ein Konflikt der Constraints `UNIQUE(tenant_id, hold_id)` und `UNIQUE(tenant_id, federation_ref)` wird von der Datenbank selbst abgewiesen. Die Anbindung an das Register
 * (Scope, Fingerprint, `409 IDEMPOTENCY_CONFLICT`, `OPERATION_IN_PROGRESS`) über die echte Anwendung prüft `BookingIdempotencyHttpTest` im Modul `app`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BookingIdempotencyTest {
    private lateinit var cf: ConfirmFixture

    @BeforeAll fun up() { cf = ConfirmFixture() }
    @AfterAll fun down() = cf.close()

    @Test
    fun wiederholung_confirm_liefert_gleiche_buchung() {
        val w = cf.fx.world(); val run = cf.fx.run(w)
        val h = cf.held(w, run)
        val first = cf.confirm(h); val second = cf.confirm(h); val third = cf.confirm(h)
        assertEquals(false, first.replayed); assertEquals(true, second.replayed); assertEquals(true, third.replayed)
        assertEquals(first.booking, second.booking); assertEquals(first.booking.id, third.booking.id)
        assertEquals(1, cf.count("SELECT count(*) FROM taktovia.booking WHERE hold_id = ?", h.holdId))
        // die Wiederholung erzeugt kein weiteres Ereignis (Outbox-Schlüssel je Aggregat, Revision und Typ)
        assertEquals(1, cf.fx.base.eventPayloads("booking.confirmed", first.booking.id).size)
        assertEquals(1, cf.count("SELECT count(*) FROM taktovia.journey WHERE tenant_id = ?", w.tenant.value))
    }

    @Test
    fun wiederholung_nach_angebotsablauf_liefert_gleiche_buchung() {
        val w = cf.fx.world(); val run = cf.fx.run(w)
        val h = cf.held(w, run)
        val first = cf.confirm(h)
        // Angebot, Preisstand und Hold sind längst abgelaufen: die Wiederholung wird vor dem Ablauf- und dem ETag-Test erkannt
        cf.fx.advance(Duration.ofHours(2))
        val again = cf.confirm(h)
        assertEquals(first.booking.id, again.booking.id); assertEquals(true, again.replayed)
        // auch mit veralteter Angebotsrevision im If-Match (die Wiederholung prüft den ETag nicht erneut)
        assertEquals(first.booking.id, cf.confirm(h, ifMatch = h.offerRevision + 3).booking.id)
        assertEquals(1, cf.count("SELECT count(*) FROM taktovia.booking WHERE hold_id = ?", h.holdId))
        cf.fx.advance(Duration.ofHours(-2))
    }

    @Test
    fun hundert_parallele_identische_requests_eine_buchung() {
        val w = cf.fx.world(); val run = cf.fx.run(w)
        val h = cf.held(w, run)
        val pool = Executors.newFixedThreadPool(32); val start = CountDownLatch(1)
        try {
            val futures = (1..100).map { pool.submit(Callable { start.await(); cf.confirm(h) }) }
            start.countDown()
            val results = futures.map { it.get() }
            assertEquals(1, results.count { !it.replayed }, "genau eine Erstausführung")
            assertEquals(1, results.map { it.booking.id }.toSet().size, "alle erhalten dieselbe Buchung")
        } finally { pool.shutdownNow() }
        assertEquals(1, cf.count("SELECT count(*) FROM taktovia.booking WHERE hold_id = ?", h.holdId))
        assertEquals(1, cf.count("SELECT last_number FROM taktovia.booking_number_counter WHERE tenant_id = ?", w.tenant.value), "es wurde genau eine Nummer vergeben")
    }

    @Test
    fun zwei_schluessel_parallel_hoechstens_eine_buchung() {
        repeat(5) {
            val w = cf.fx.world(); val run = cf.fx.run(w)
            val h = cf.held(w, run)
            val pool = Executors.newFixedThreadPool(2); val start = CountDownLatch(1)
            try {
                val futures = (1..2).map { pool.submit(Callable { start.await(); cf.bookings.confirm(h.passenger.ctx, ConfirmBooking(h.holdId, h.offerRevision, h.grossMinor, "EUR")) }) }
                start.countDown()
                assertEquals(1, futures.map { it.get() }.map { it.booking.id }.toSet().size)
            } finally { pool.shutdownNow() }
            assertEquals(1, cf.count("SELECT count(*) FROM taktovia.booking WHERE hold_id = ?", h.holdId))
        }
    }

    @Test
    fun nach_cacheablauf_keine_zweite_aktive_buchung() {
        val w = cf.fx.world(); val run = cf.fx.run(w)
        val h = cf.held(w, run)
        val first = cf.confirm(h)
        // ohne Registereintrag (hier: der Kern kennt ihn nie) sichern die fachlichen Constraints: dieselbe Buchung statt einer zweiten
        assertEquals(first.booking.id, cf.confirm(h).booking.id)
        // und die Datenbank weist eine zweite Buchung zum Hold selbst unter Administratorrechten ab
        assertThrows<org.postgresql.util.PSQLException> {
            cf.fx.base.adminUpdate(
                "INSERT INTO taktovia.booking(tenant_id, id, hold_id, service_id, federation_ref, state, promise_snapshot, revision) VALUES (?, gen_random_uuid(), ?, ?, 'zweite-ref', 'CONFIRMING', '{}'::jsonb, 1)",
                w.tenant.value, h.holdId, w.serviceId,
            )
        }
        assertThrows<org.postgresql.util.PSQLException> {
            cf.fx.base.adminUpdate(
                "INSERT INTO taktovia.booking(tenant_id, id, hold_id, service_id, federation_ref, state, promise_snapshot, revision) VALUES (?, gen_random_uuid(), gen_random_uuid(), ?, ?, 'CONFIRMING', '{}'::jsonb, 1)",
                w.tenant.value, w.serviceId, first.booking.let { cf.stringOf("SELECT federation_ref FROM taktovia.booking WHERE id = ?", it.id) },
            )
        }
        assertEquals(1, cf.count("SELECT count(*) FROM taktovia.booking WHERE tenant_id = ?", w.tenant.value))
        assertTrue(true)
    }

    @Test
    fun hold_und_confirm_mit_anderem_betrag_sind_keine_wiederholung() {
        val w = cf.fx.world(); val run = cf.fx.run(w)
        val h = cf.held(w, run)
        cf.confirm(h)
        // gleicher Hold, anderer Betrag: nie stillschweigend dieselbe Buchung, sondern Neubepreisung
        assertEquals(ErrorCode.REQUOTE_REQUIRED, assertThrows<ProblemException> { cf.confirm(h, gross = h.grossMinor + 1) }.code)
    }
}

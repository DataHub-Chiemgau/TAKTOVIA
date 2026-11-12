package de.taktovia.booking

import de.taktovia.bookingdispatch.OfferObjectsFixture
import de.taktovia.bookingdispatch.api.ConfirmBooking
import de.taktovia.bookingdispatch.api.HoldRideOffer
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.util.UUID

/**
 * WP-M2-23 / LH-6.4-BKG-003: Hold -> Confirm -> Buchung gegen die echte Datenbank unter der Runtime-Rolle. Preis: 2 Personen x 450 (FESTPREIS) plus Gebühr 50 = 950, 7 % Steuer enthalten.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConfirmBookingTest {
    private lateinit var cf: ConfirmFixture

    @BeforeAll fun up() { cf = ConfirmFixture() }
    @AfterAll fun down() = cf.close()

    private fun code(block: () -> Unit): ErrorCode = assertThrows<ProblemException> { block() }.code

    @Test
    fun hold_confirm_buchung_lesbar() {
        val w = cf.fx.world(); val run = cf.fx.run(w)
        val h = cf.held(w, run)
        val r = cf.confirm(h)
        val b = r.booking
        assertEquals(false, r.replayed)
        // Zahlung noch nicht erfüllt (Betrag 950): CONFIRMING; Revision 1; Zusage mit Ressourcenvektor und Preis
        assertEquals("CONFIRMING", b.state); assertEquals(1, b.revision); assertEquals(h.holdId, b.holdId); assertEquals(w.serviceId, b.serviceId)
        assertEquals(2, b.passengers); assertEquals(0, b.wheelchairSpaces)
        assertEquals(950, b.promise.grossMinor); assertEquals("EUR", b.promise.currency); assertEquals(2, b.promise.resourceVector!!.seats)
        assertEquals(h.offer.cancellationPolicyRef, b.promise.cancellationPolicyRef)
        assertTrue(Regex("BK-2[0-9]{3}-[0-9]{6}").matches(b.bookingNumber), b.bookingNumber)
        // Hold verbraucht, Journey-Hülle mit genau einem Segment, Outbox-Ereignis
        assertEquals("CONSUMED", cf.fx.stateOf(h.holdId))
        assertEquals(1, cf.count("SELECT count(*) FROM taktovia.journey_leg l JOIN taktovia.booking b ON b.tenant_id = l.tenant_id AND b.journey_id = l.journey_id WHERE b.id = ?", b.id))
        assertEquals(1, cf.fx.base.eventPayloads("booking.confirmed", b.id).size)
        // lesbar: Fahrgast eigene Buchung (Get und Liste), Betriebspersonal im Dienstscope
        assertEquals(b, cf.bookings.get(h.passenger.ctx, b.id))
        assertEquals(listOf(b.id), cf.bookings.list(h.passenger.ctx, 10, null).map { it.id })
        val op = cf.operator(w.tenant, setOf(w.serviceId))
        assertEquals(b.id, cf.bookings.get(op, b.id).id); assertEquals(listOf(b.id), cf.bookings.list(op, 10, null).map { it.id })
        // Wiederholung des Confirm findet dieselbe Buchung
        val again = cf.confirm(h)
        assertEquals(true, again.replayed); assertEquals(b.id, again.booking.id); assertEquals(b.bookingNumber, again.booking.bookingNumber)
        assertEquals(1, cf.count("SELECT count(*) FROM taktovia.booking WHERE hold_id = ?", h.holdId))
    }

    @Test
    fun kostenfreie_buchung_ist_sofort_bestaetigt() {
        val w = cf.fx.world(withTariff = false); cf.fx.publishTariff(w, 0, 0); val run = cf.fx.run(w)
        val b = cf.confirm(cf.held(w, run)).booking
        assertEquals(0, b.promise.grossMinor); assertEquals("CONFIRMED", b.state); assertEquals(2, b.revision)
        assertEquals("CONFIRMED", cf.stringOf("SELECT j.state FROM taktovia.journey j JOIN taktovia.booking b ON b.tenant_id = j.tenant_id AND b.journey_id = j.id WHERE b.id = ?", b.id))
    }

    @Test
    fun abgelaufener_hold_ergibt_409_hold_expired() {
        val w = cf.fx.world(); val run = cf.fx.run(w)
        val short = cf.fx.offersWithTtl(Duration.ofMillis(200))
        val h = cf.held(w, run, offers = short)
        Thread.sleep(400)
        assertEquals(ErrorCode.HOLD_EXPIRED, code { cf.confirm(h) })
        assertEquals(0, cf.count("SELECT count(*) FROM taktovia.booking WHERE hold_id = ?", h.holdId))
        // Kapazität frei: ein neuer Hold auf denselben Abschnitten gelingt
        assertEquals(0, cf.fx.base.occupancy(run.runId, 1).sumOf { it.first })
    }

    @Test
    fun freigegebener_und_fremder_hold_werden_abgewiesen() {
        val w = cf.fx.world(); val run = cf.fx.run(w)
        val h = cf.held(w, run)
        cf.fx.offers.release(h.passenger.ctx, h.offer.id, h.offerRevision)
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, code { cf.confirm(h) })
        assertEquals(0, cf.count("SELECT count(*) FROM taktovia.booking WHERE hold_id = ?", h.holdId))
        // Hold eines anderen Fahrgasts: 404, auch mit gültiger Fahrgastidentität
        val h2 = cf.held(w, run); val other = cf.passenger(w)
        assertEquals(ErrorCode.NOT_FOUND, code { cf.confirm(h2, ctx = other.ctx) })
        assertEquals(ErrorCode.NOT_FOUND, code { cf.bookings.confirm(h2.passenger.ctx, ConfirmBooking(UUID.randomUUID(), 2, 950, "EUR")) })
        // ohne Fahrgastkonto keine Buchung
        val noAccount = cf.fx.passenger(w.tenant)
        assertEquals(ErrorCode.FORBIDDEN, code { cf.confirm(h2, ctx = noAccount) })
        assertEquals(0, cf.count("SELECT count(*) FROM taktovia.booking WHERE hold_id = ?", h2.holdId))
    }

    @Test
    fun abweichender_betrag_requote_required() {
        val w = cf.fx.world(); val run = cf.fx.run(w)
        val h = cf.held(w, run)
        for (gross in listOf(949L, 951L, 0L)) assertEquals(ErrorCode.REQUOTE_REQUIRED, code { cf.confirm(h, gross = gross) }, "Betrag $gross")
        assertEquals(0, cf.count("SELECT count(*) FROM taktovia.booking WHERE hold_id = ?", h.holdId))
        assertEquals("HELD", cf.fx.stateOf(h.holdId))
        // veraltete Angebotsrevision: 412, nie stilles Bestätigen
        assertEquals(ErrorCode.REVISION_MISMATCH, code { cf.confirm(h, ifMatch = h.offerRevision + 5) })
        // mit richtigem Betrag und richtiger Revision gelingt die Bestätigung weiterhin
        assertEquals("CONFIRMING", cf.confirm(h).booking.state)
    }

    @Test
    fun promise_snapshot_unveraenderlich_nach_konfig_und_tarifwechsel() {
        val w = cf.fx.world(withTariff = false); val run = cf.fx.run(w)
        cf.fx.publishTariff(w, 450, 50, to = "2026-11-03T00:00:00Z")
        val (h, b) = cf.booked(w, run)
        val before = cf.stringOf("SELECT promise_snapshot::text FROM taktovia.booking WHERE id = ?", b.id)
        // Tarifwechsel nach der Bestätigung (neuer Preis ab sofort): die Zusage bleibt
        cf.fx.publishTariff(w, 999, 10, from = "2026-11-03T00:00:00Z", to = "2027-01-01T00:00:00Z")
        assertEquals(before, cf.stringOf("SELECT promise_snapshot::text FROM taktovia.booking WHERE id = ?", b.id))
        assertEquals(950, cf.bookings.get(h.passenger.ctx, b.id).promise.grossMinor)
        // direktes UPDATE unter der Runtime-Rolle schlägt fehl (Trigger), die Zusage bleibt
        val ex = assertThrows<org.springframework.dao.DataAccessException> {
            cf.fx.base.tx.run(w.tenant) { j -> j.update("UPDATE taktovia.booking SET promise_snapshot = jsonb_set(promise_snapshot, '{gross_minor}', '1') WHERE id = ?", b.id) }
        }
        assertTrue(ex.message!!.contains("immutable"), ex.message)
        assertEquals(before, cf.stringOf("SELECT promise_snapshot::text FROM taktovia.booking WHERE id = ?", b.id))
    }

    @Test
    fun buchungsnummer_je_mandant_eindeutig() {
        val w1 = cf.fx.world(); val w2 = cf.fx.world(); val run1 = cf.fx.run(w1); val run2 = cf.fx.run(w2)
        val a1 = cf.booked(w1, run1, passengers = 1).second; val a2 = cf.booked(w1, run1, passengers = 1).second; val b1 = cf.booked(w2, run2, passengers = 1).second
        assertNotEquals(a1.bookingNumber, a2.bookingNumber)
        // je Mandant lückenlos fortlaufend, ein zweiter Mandant beginnt bei 1 (Nummer verrät den Mandanten nicht)
        assertEquals(1, a1.bookingNumber.takeLast(6).toInt()); assertEquals(2, a2.bookingNumber.takeLast(6).toInt()); assertEquals(1, b1.bookingNumber.takeLast(6).toInt())
        // die Datenbank erzwingt die Eindeutigkeit je Mandant
        assertThrows<org.springframework.dao.DataIntegrityViolationException> {
            cf.fx.base.tx.run(w1.tenant) { j -> j.update("UPDATE taktovia.booking SET booking_no = ? WHERE id = ?", a1.bookingNumber, a2.id) }
        }
    }

    @Test
    fun betreiberpayload_ohne_unterstuetzungsbedarf_fuer_unzustaendige_rollen() {
        val w = cf.fx.world(); val run = cf.fx.run(w)
        val b = cf.booked(w, run).second
        val dispatcher = cf.operator(w.tenant, setOf(w.serviceId), setOf("dispatcher"))
        val support = cf.operator(w.tenant, setOf(w.serviceId), setOf("support"))
        assertEquals(emptyList<String>(), cf.bookings.get(dispatcher, b.id).assistanceCodes)
        assertEquals(null, cf.bookings.get(support, b.id).assistanceCodes, "Unterstützungsbedarf nur für zuständige Rollen")
    }
}

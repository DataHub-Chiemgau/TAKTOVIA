package de.taktovia.bookingdispatch

import de.taktovia.booking.ConfirmFixture
import de.taktovia.bookingdispatch.api.BookingDetail
import de.taktovia.bookingdispatch.api.BookingReason
import de.taktovia.bookingdispatch.api.HistoryView
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.statemachine.BookingState
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * WP-M2-25 / LH-6.4-BKG-013: Statushistorie der Buchung gegen die echte Datenbank unter der Runtime-Rolle. Der Zweig „Abschluss“ (Fahrtende, No-Show) wird in M2 mit Testereignissen nach dem
 * Automaten geprüft (Betriebsübergang über [de.taktovia.bookingdispatch.api.Bookings.transition]); echte Fahrerereignisse folgen mit WP-M3-29/-30.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BookingHistoryTest {
    private lateinit var cf: ConfirmFixture

    @BeforeAll fun up() { cf = ConfirmFixture() }
    @AfterAll fun down() = cf.close()

    private fun entries(bookingId: UUID) = cf.count("SELECT count(*) FROM taktovia.booking_status_history WHERE booking_id = ?", bookingId)
    private fun code(block: () -> Unit) = assertThrows<ProblemException> { block() }.code

    @Test
    fun lebenslauf_anfrage_bis_storno_rekonstruierbar() {
        val w = cf.fx.world(); val run = cf.fx.run(w)
        val (h, b) = cf.booked(w, run)
        val op = cf.operator(w.tenant, setOf(w.serviceId))
        cf.bookings.transition(op, b.id, BookingState.CONFIRMED, BookingReason.PAYMENT_AUTHORIZED, 1)
        cf.bookings.transition(op, b.id, BookingState.CANCEL_PENDING, BookingReason.OPERATOR_CANCELLATION, 2)
        cf.bookings.transition(op, b.id, BookingState.CANCELED, BookingReason.CANCEL_COMPLETED, 3)
        val hist = cf.bookings.history(op, b.id, HistoryView.AUDIT)
        // lückenlos: je Revision ein Eintrag, Kette der Zustände und Gründe, Anzahl = Revision
        assertEquals(listOf(1L, 2L, 3L, 4L), hist.map { it.revision })
        assertEquals(listOf(null, "CONFIRMING", "CONFIRMED", "CANCEL_PENDING"), hist.map { it.fromState })
        assertEquals(listOf("CONFIRMING", "CONFIRMED", "CANCEL_PENDING", "CANCELED"), hist.map { it.toState })
        assertEquals(listOf(BookingReason.HOLD_CONFIRMED, BookingReason.PAYMENT_AUTHORIZED, BookingReason.OPERATOR_CANCELLATION, BookingReason.CANCEL_COMPLETED), hist.map { it.reason })
        assertEquals(hist.size.toLong(), cf.bookings.get(op, b.id).revision)
        assertTrue(hist.zipWithNext().all { (a, c) -> !a.occurredAt.isAfter(c.occurredAt) }, "Zeitpunkte aufsteigend")
        // Akteure: Fahrgast legt an, Betrieb ändert
        assertEquals("PASSENGER", hist[0].actorKind); assertEquals(listOf("OPERATOR", "OPERATOR", "OPERATOR"), hist.drop(1).map { it.actorKind })
        assertTrue(hist.all { it.actorRef != null && it.correlationId != null })
        // Verknüpfungen: Anfrage -> Angebot -> Hold -> Bestätigung sind über die Objekte rekonstruierbar, in zeitlicher Reihenfolge
        val l = hist[0].links!!
        assertEquals(h.holdId, l.holdId); assertEquals(h.offer.id, l.offerId); assertNotNull(l.attemptId); assertNotNull(l.quoteId)
        assertTrue(hist.all { it.links == l }, "alle Einträge tragen dieselben Verknüpfungen")
        val attemptAt = cf.stringOf("SELECT created_at::text FROM taktovia.travel_attempt WHERE id = ?", l.attemptId)
        val offerAt = cf.stringOf("SELECT created_at::text FROM taktovia.ride_offer WHERE id = ?", l.offerId)
        assertTrue(attemptAt <= offerAt, "Anfrage vor Angebot")
        assertEquals(h.holdId.toString(), cf.stringOf("SELECT hold_id::text FROM taktovia.ride_offer WHERE id = ?", l.offerId))
        assertEquals("CONSUMED", cf.fx.stateOf(l.holdId!!), "Hold vor der Bestätigung gehalten und von ihr verbraucht")
    }

    @Test
    fun abschlusszweig_mit_testereignissen_nach_automat() {
        val w = cf.fx.world(); val run = cf.fx.run(w); val op = cf.operator(w.tenant, setOf(w.serviceId))
        val b = cf.booked(w, run, passengers = 1).second
        cf.bookings.transition(op, b.id, BookingState.CONFIRMED, BookingReason.PAYMENT_AUTHORIZED, 1)
        cf.bookings.transition(op, b.id, BookingState.COMPLETED, BookingReason.TRIP_COMPLETED, 2)     // Testereignis „Fahrtende“
        assertEquals(listOf("CONFIRMING", "CONFIRMED", "COMPLETED"), cf.bookings.history(op, b.id, HistoryView.AUDIT).map { it.toState })
        // No-Show: Stornozweig mit dem Grund NO_SHOW
        val c = cf.booked(w, run, passengers = 1).second
        cf.bookings.transition(op, c.id, BookingState.CONFIRMED, BookingReason.PAYMENT_AUTHORIZED, 1)
        cf.bookings.transition(op, c.id, BookingState.CANCEL_PENDING, BookingReason.NO_SHOW, 2)
        assertEquals(BookingReason.NO_SHOW, cf.bookings.history(op, c.id, HistoryView.AUDIT).last().reason)
        // ein abgeschlossener Zustand ist endgültig
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, code { cf.bookings.transition(op, b.id, BookingState.CANCEL_PENDING, BookingReason.OPERATOR_CANCELLATION) })
    }

    @Test
    fun unzulaessiger_uebergang_ohne_historieneintrag() {
        val w = cf.fx.world(); val run = cf.fx.run(w); val op = cf.operator(w.tenant, setOf(w.serviceId))
        val b = cf.booked(w, run).second
        assertEquals(1, entries(b.id))
        // laut Automat unzulässig (CONFIRMING -> COMPLETED): 409, keine Wirkung, kein Eintrag
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, code { cf.bookings.transition(op, b.id, BookingState.COMPLETED, BookingReason.TRIP_COMPLETED) })
        // Grund passt nicht zum Zielzustand: 422, ohne Wirkung
        assertEquals(ErrorCode.VALIDATION_FAILED, code { cf.bookings.transition(op, b.id, BookingState.CONFIRMED, BookingReason.OPERATOR_CANCELLATION) })
        // veraltete Revision: 412
        assertEquals(ErrorCode.REVISION_MISMATCH, code { cf.bookings.transition(op, b.id, BookingState.CONFIRMED, BookingReason.PAYMENT_AUTHORIZED, 7) })
        assertEquals(1, entries(b.id)); assertEquals("CONFIRMING", cf.bookings.get(op, b.id).state); assertEquals(1, cf.bookings.get(op, b.id).revision)
        assertEquals(1, cf.fx.base.eventPayloads("booking.confirmed", b.id).size)
    }

    @Test
    fun fahrgast_pruefersicht_ergibt_403() {
        val w = cf.fx.world(); val run = cf.fx.run(w)
        val (h, b) = cf.booked(w, run)
        assertEquals(ErrorCode.FORBIDDEN, code { cf.bookings.history(h.passenger.ctx, b.id, HistoryView.AUDIT) })
        assertEquals(ErrorCode.FORBIDDEN, code { cf.bookings.transition(h.passenger.ctx, b.id, BookingState.CONFIRMED, BookingReason.PAYMENT_AUTHORIZED) }, "Fahrgäste ändern nur über den Storno")
        assertEquals(1, entries(b.id))
    }

    @Test
    fun fahrgast_sicht_ohne_akteur_ids_dritter() {
        val w = cf.fx.world(); val run = cf.fx.run(w); val op = cf.operator(w.tenant, setOf(w.serviceId))
        val (h, b) = cf.booked(w, run)
        cf.bookings.transition(op, b.id, BookingState.CONFIRMED, BookingReason.PAYMENT_AUTHORIZED, 1)
        val view = cf.bookings.history(h.passenger.ctx, b.id, HistoryView.PASSENGER)
        assertEquals(2, view.size)
        assertTrue(view.all { it.actorRef == null && it.correlationId == null && it.links == null }, "keine Akteur-IDs, keine Korrelation, keine Verknüpfungen")
        assertTrue(view.all { it.reasonText.isNotBlank() && it.actorKind in setOf("PASSENGER", "OPERATOR", "SYSTEM") })
        assertEquals("Die Zahlung wurde bestätigt.", view[1].reasonText)
        // der Betrieb sieht in der Fahrgastsicht dasselbe reduzierte Bild, in der Prüfersicht alles
        assertNull(cf.bookings.history(op, b.id, HistoryView.PASSENGER).first().actorRef); assertNotNull(cf.bookings.history(op, b.id, HistoryView.AUDIT).first().actorRef)
    }

    @Test
    fun parallele_uebergaenge_ein_gewinner_revision_deckungsgleich() {
        repeat(6) { round ->
            val w = cf.fx.world(); val run = cf.fx.run(w); val op = cf.operator(w.tenant, setOf(w.serviceId))
            val b: BookingDetail = cf.booked(w, run, passengers = 1).second
            cf.bookings.transition(op, b.id, BookingState.CONFIRMED, BookingReason.PAYMENT_AUTHORIZED, 1)
            val withRevision = round % 2 == 0     // mit If-Match: der Verlierer erhält 412; ohne: 409 (Zustand weicht ab)
            val pool = Executors.newFixedThreadPool(2); val start = CountDownLatch(1)
            val results = try {
                val f = listOf(
                    Callable { runCatching { cf.bookings.transition(op, b.id, BookingState.CANCEL_PENDING, BookingReason.OPERATOR_CANCELLATION, if (withRevision) 2 else null) } },
                    Callable { runCatching { cf.bookings.transition(op, b.id, BookingState.COMPLETED, BookingReason.TRIP_COMPLETED, if (withRevision) 2 else null) } },
                ).map { t -> pool.submit(Callable { start.await(); t.call() }) }
                start.countDown(); f.map { it.get() }
            } finally { pool.shutdownNow() }
            assertEquals(1, results.count { it.isSuccess }, "genau ein Gewinner: $results")
            val loser = results.single { it.isFailure }.exceptionOrNull() as ProblemException
            assertEquals(if (withRevision) ErrorCode.REVISION_MISMATCH else ErrorCode.INVALID_STATE_TRANSITION, loser.code)
            // Historie und Revision bleiben deckungsgleich: 3 Einträge (Anlage, Zahlung, Gewinner), Revision 3
            assertEquals(3, entries(b.id)); assertEquals(3, cf.bookings.get(op, b.id).revision)
            assertEquals(listOf(1L, 2L, 3L), cf.bookings.history(op, b.id, HistoryView.AUDIT).map { it.revision })
        }
    }

    @Test
    fun fremder_mandant_historie_nicht_lesbar() {
        val w = cf.fx.world(); val run = cf.fx.run(w)
        val b = cf.booked(w, run).second
        val w2 = cf.fx.world()
        assertEquals(ErrorCode.NOT_FOUND, code { cf.bookings.history(cf.operator(w2.tenant, setOf(w2.serviceId)), b.id, HistoryView.AUDIT) })
        assertEquals(ErrorCode.NOT_FOUND, code { cf.bookings.history(cf.passenger(w2).ctx, b.id, HistoryView.PASSENGER) })
        assertEquals(0, cf.fx.base.tx.run(w2.tenant) { j -> j.queryForObject("SELECT count(*) FROM taktovia.booking_status_history WHERE booking_id = ?", Int::class.java, b.id) })
    }

    @Test
    fun fremder_dienst_ohne_scope_ergibt_404() {
        val w = cf.fx.world(); val run = cf.fx.run(w)
        val (h, b) = cf.booked(w, run)
        assertEquals(ErrorCode.NOT_FOUND, code { cf.bookings.history(cf.operator(w.tenant, setOf(UUID.randomUUID())), b.id, HistoryView.AUDIT) })
        assertEquals(ErrorCode.NOT_FOUND, code { cf.bookings.history(cf.operator(w.tenant, emptySet()), b.id, HistoryView.AUDIT) })
        assertEquals(ErrorCode.NOT_FOUND, code { cf.bookings.transition(cf.operator(w.tenant, setOf(UUID.randomUUID())), b.id, BookingState.CONFIRMED, BookingReason.PAYMENT_AUTHORIZED) })
        assertEquals(ErrorCode.NOT_FOUND, code { cf.bookings.history(cf.passenger(w).ctx, b.id, HistoryView.PASSENGER) }, "anderer Fahrgast desselben Mandanten")
        assertEquals(1, cf.bookings.history(h.passenger.ctx, b.id, HistoryView.PASSENGER).size)
    }

    @Test
    fun katalog_der_gruende_in_code_und_datenbank_deckungsgleich() {
        val w = cf.fx.world()
        val db = cf.fx.base.tx.run(w.tenant) { j -> j.queryForList("SELECT code FROM taktovia.booking_status_reason", String::class.java).toSet() }
        assertEquals(BookingReason.entries.map { it.name }.toSet(), db)
        val kinds = cf.fx.base.tx.run(w.tenant) { j -> j.query("SELECT code, kind FROM taktovia.booking_status_reason") { rs, _ -> rs.getString(1) to rs.getString(2) }.toMap() }
        assertTrue(BookingReason.entries.all { kinds[it.name] == it.kind.name })
    }
}

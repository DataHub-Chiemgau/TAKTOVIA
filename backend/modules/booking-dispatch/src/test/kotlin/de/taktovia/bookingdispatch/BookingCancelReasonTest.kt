package de.taktovia.bookingdispatch

import com.networknt.schema.SchemaRegistry
import com.networknt.schema.dialect.Dialects
import de.taktovia.booking.ConfirmFixture
import de.taktovia.bookingdispatch.api.BookingReason
import de.taktovia.bookingdispatch.api.CancelBooking
import de.taktovia.bookingdispatch.api.CauseClass
import de.taktovia.bookingdispatch.api.HistoryView
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.statemachine.BookingState
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * WP-M2-27 / LH-6.4-BKG-014: Stornoursache Fahrzeugausfall gegen die echte Datenbank unter der Runtime-Rolle. Kundenstorno (Verursacherklasse FAHRGAST) und Fahrzeugausfall über den betreiberseitigen
 * Storno-Befehl (FAHRZEUGAUSFALL) sind in Buchungsressource, Historie und Ereignis unterscheidbar; der Grund ist nach dem Setzen unveränderlich. Die Auslösung aus dem Störungsablauf folgt mit
 * WP-M3-53/-54, die Berechnung der Quoten mit WP-M4-46 (Datenbasis: Spalten `booking.cancel_reason_code`/`cancel_cause_class` und Statushistorie, `db/tests/test_cancel_reason_basis.py`).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BookingCancelReasonTest {
    private lateinit var cf: ConfirmFixture

    @BeforeAll fun up() { cf = ConfirmFixture() }
    @AfterAll fun down() = cf.close()

    private fun code(block: () -> Unit) = assertThrows<ProblemException> { block() }.code

    @Test
    fun fahrgast_und_fahrzeugausfall_storno_unterscheidbar() {
        val w = cf.world(); val run = cf.fx.run(w); val op = cf.operator(w.tenant, setOf(w.serviceId))
        val (h1, b1) = cf.booked(w, run); val (_, b2) = cf.booked(w, run, passengers = 1)
        cf.bookings.cancel(h1.passenger.ctx, b1.id, CancelBooking(1, BookingReason.PASSENGER_REQUEST))
        cf.bookings.cancel(op, b2.id, CancelBooking(1, BookingReason.VEHICLE_OUTAGE))
        // Buchungsressource: Grund und Verursacherklasse
        val r1 = cf.bookings.get(op, b1.id); val r2 = cf.bookings.get(op, b2.id)
        assertEquals(BookingReason.PASSENGER_REQUEST to CauseClass.FAHRGAST, r1.cancelReason to r1.cancelCause)
        assertEquals(BookingReason.VEHICLE_OUTAGE to CauseClass.FAHRZEUGAUSFALL, r2.cancelReason to r2.cancelCause)
        assertEquals("CANCELED", r1.state); assertEquals("CANCELED", r2.state)
        // Historie: unterschiedlicher Grund, Akteurklasse
        val h1h = cf.bookings.history(op, b1.id, HistoryView.AUDIT); val h2h = cf.bookings.history(op, b2.id, HistoryView.AUDIT)
        assertEquals(BookingReason.PASSENGER_REQUEST, h1h[1].reason); assertEquals(BookingReason.VEHICLE_OUTAGE, h2h[1].reason)
        assertEquals("PASSENGER", h1h[1].actorKind); assertEquals("OPERATOR", h2h[1].actorKind)
        assertEquals("Die Fahrt entfällt wegen eines Fahrzeugausfalls.", cf.bookings.history(h1.passenger.ctx.let { op }, b2.id, HistoryView.PASSENGER)[1].reasonText)
        // Ereignis: typisierte Felder
        val e1 = cf.fx.base.eventPayloads("booking.cancelled", b1.id).single(); val e2 = cf.fx.base.eventPayloads("booking.cancelled", b2.id).single()
        assertTrue(e1.contains("\"cause_class\": \"FAHRGAST\"") && e1.contains("\"reason_code\": \"PASSENGER_REQUEST\""), e1)
        assertTrue(e2.contains("\"cause_class\": \"FAHRZEUGAUSFALL\"") && e2.contains("\"reason_code\": \"VEHICLE_OUTAGE\"") && e2.contains("\"initiator\": \"OPERATOR\""), e2)
        // Datenbasis: Fahrgaststorno zählt nur als Fahrgast, Fahrzeugausfall getrennt (nicht als Betreiberstorno)
        val basis = cf.fx.base.tx.run(w.tenant) { j -> j.queryForMap("SELECT count(*) FILTER (WHERE cancel_cause_class = 'FAHRGAST') AS p, count(*) FILTER (WHERE cancel_cause_class = 'BETREIBER') AS o, count(*) FILTER (WHERE cancel_cause_class = 'FAHRZEUGAUSFALL') AS v FROM taktovia.booking WHERE cancel_reason_code IS NOT NULL") }
        assertEquals(listOf(1L, 0L, 1L), listOf(basis["p"], basis["o"], basis["v"]).map { it as Long }, "Fahrzeugausfall zählt weder als Fahrgast- noch als Betreiberstorno")
    }

    @Test
    fun ereignis_traegt_reason_code_und_cause_class() {
        val w = cf.world(); val run = cf.fx.run(w); val op = cf.operator(w.tenant, setOf(w.serviceId))
        val schema = SchemaRegistry.withDialect(Dialects.getDraft202012()) { b ->
            b.schemas(mapOf("https://taktovia.example/schemas/resource-vector.v1.schema.json" to Files.readString(BookingTestDb.repoRoot().resolve("docs/umsetzungsplan/vertraege/schemas/resource-vector.v1.schema.json"))))
        }.getSchema(Files.readString(BookingTestDb.repoRoot().resolve("api/events/booking.cancelled.v1.schema.json")))
        for (reason in listOf(BookingReason.PASSENGER_REQUEST, BookingReason.OPERATOR_CANCELLATION, BookingReason.SAFETY, BookingReason.VEHICLE_OUTAGE, BookingReason.NO_SHOW)) {
            val (h, b) = cf.booked(w, run, passengers = 1)
            cf.bookings.transition(op, b.id, BookingState.CONFIRMED, BookingReason.PAYMENT_AUTHORIZED, 1)     // „No-Show“ setzt eine bestätigte Buchung voraus
            cf.bookings.cancel(if (reason == BookingReason.PASSENGER_REQUEST) h.passenger.ctx else op, b.id, CancelBooking(2, reason))
            val payload = cf.fx.base.eventPayloads("booking.cancelled", b.id).single()
            assertEquals(emptyList<Any>(), schema.validate(payload, com.networknt.schema.InputFormat.JSON).toList(), "Schema: $payload")
            assertTrue(payload.contains("\"reason_code\": \"${reason.name}\"") && payload.contains("\"cause_class\": \"${reason.cause!!.name}\""), payload)
        }
    }

    @Test
    fun fahrgast_setzt_betreibergrund_ergibt_403() {
        val w = cf.world(); val run = cf.fx.run(w)
        val (h, b) = cf.booked(w, run)
        for (r in listOf(BookingReason.VEHICLE_OUTAGE, BookingReason.OPERATOR_CANCELLATION, BookingReason.SAFETY, BookingReason.NO_SHOW, BookingReason.PAYMENT_FAILED))
            assertEquals(ErrorCode.FORBIDDEN, code { cf.bookings.cancel(h.passenger.ctx, b.id, CancelBooking(1, r)) }, r.name)
        // auch ein Mensch im Betrieb setzt den Systemgrund „Zahlung gescheitert“ nicht
        assertEquals(ErrorCode.FORBIDDEN, code { cf.bookings.cancel(cf.operator(w.tenant, setOf(w.serviceId)), b.id, CancelBooking(1, BookingReason.PAYMENT_FAILED)) })
        val cur = cf.bookings.get(h.passenger.ctx, b.id)
        assertEquals("CONFIRMING", cur.state); assertNull(cur.cancelReason); assertNull(cur.cancelCause)
        assertEquals(1, cf.count("SELECT count(*) FROM taktovia.booking_status_history WHERE booking_id = ?", b.id))
    }

    @Test
    fun grund_nachtraeglich_aendern_abgewiesen() {
        val w = cf.world(); val run = cf.fx.run(w); val op = cf.operator(w.tenant, setOf(w.serviceId))
        val (h, b) = cf.booked(w, run)
        cf.bookings.cancel(h.passenger.ctx, b.id, CancelBooking(1, BookingReason.PASSENGER_REQUEST))
        // ein zweites Storno mit anderem Grund (aktuelle Revision): 409, der Grund bleibt
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, code { cf.bookings.cancel(op, b.id, CancelBooking(3, BookingReason.VEHICLE_OUTAGE)) })
        assertEquals(BookingReason.PASSENGER_REQUEST, cf.bookings.get(op, b.id).cancelReason)
        // auch per SQL unter der Runtime-Rolle nicht änderbar (Trigger) und nicht mit falscher Verursacherklasse setzbar (CHECK)
        assertThrows<org.springframework.dao.DataAccessException> {
            cf.fx.base.tx.run(w.tenant) { j -> j.update("UPDATE taktovia.booking SET cancel_reason_code = 'VEHICLE_OUTAGE', cancel_cause_class = 'FAHRZEUGAUSFALL' WHERE id = ?", b.id) }
        }
        assertThrows<org.springframework.dao.DataAccessException> {
            cf.fx.base.tx.run(w.tenant) { j -> j.update("UPDATE taktovia.booking SET cancel_cause_class = 'BETREIBER' WHERE id = ?", b.id) }
        }
        val (_, c) = cf.booked(w, run, passengers = 1)
        assertThrows<org.springframework.dao.DataAccessException> {
            cf.fx.base.tx.run(w.tenant) { j -> j.update("UPDATE taktovia.booking SET cancel_reason_code = 'NO_SHOW', cancel_cause_class = 'BETREIBER' WHERE id = ?", c.id) }
        }
        assertEquals(BookingReason.PASSENGER_REQUEST to CauseClass.FAHRGAST, cf.bookings.get(op, b.id).let { it.cancelReason to it.cancelCause })
    }

    @Test
    fun betriebspersonal_ohne_dienst_scope_ergibt_404() {
        val w = cf.world(); val run = cf.fx.run(w)
        val b = cf.booked(w, run).second
        for (services in listOf(emptySet(), setOf(UUID.randomUUID())))
            assertEquals(ErrorCode.NOT_FOUND, code { cf.bookings.cancel(cf.operator(w.tenant, services), b.id, CancelBooking(1, BookingReason.VEHICLE_OUTAGE)) })
        assertNull(cf.bookings.get(cf.operator(w.tenant, setOf(w.serviceId)), b.id).cancelReason)
    }

    @Test
    fun fremde_mandantenbuchung_storno_ergibt_404() {
        val w = cf.world(); val run = cf.fx.run(w); val w2 = cf.world()
        val b = cf.booked(w, run).second
        assertEquals(ErrorCode.NOT_FOUND, code { cf.bookings.cancel(cf.operator(w2.tenant, setOf(w2.serviceId)), b.id, CancelBooking(1, BookingReason.VEHICLE_OUTAGE)) })
        assertEquals(ErrorCode.NOT_FOUND, code { cf.bookings.cancel(cf.passenger(w2).ctx, b.id, CancelBooking(1, BookingReason.PASSENGER_REQUEST)) })
        assertEquals("CONFIRMING", cf.bookings.get(cf.operator(w.tenant, setOf(w.serviceId)), b.id).state)
    }

    @Test
    fun kunden_und_betreiberstorno_parallel_ein_storno_eine_erstattung() {
        repeat(6) {
            val w = cf.world(); val run = cf.fx.run(w); val op = cf.operator(w.tenant, setOf(w.serviceId))
            val (h, b) = cf.booked(w, run)
            val pool = Executors.newFixedThreadPool(2); val start = CountDownLatch(1)
            val results = try {
                val f = listOf(
                    Callable { runCatching { cf.bookings.cancel(h.passenger.ctx, b.id, CancelBooking(1, BookingReason.PASSENGER_REQUEST)); BookingReason.PASSENGER_REQUEST } },
                    Callable { runCatching { cf.bookings.cancel(op, b.id, CancelBooking(1, BookingReason.VEHICLE_OUTAGE)); BookingReason.VEHICLE_OUTAGE } },
                ).map { t -> pool.submit(Callable { start.await(); t.call() }) }
                start.countDown(); f.map { it.get() }
            } finally { pool.shutdownNow() }
            assertEquals(1, results.count { it.isSuccess }, "genau ein Storno: $results")
            val winner = results.single { it.isSuccess }.getOrThrow()
            assertEquals(ErrorCode.REVISION_MISMATCH, (results.single { it.isFailure }.exceptionOrNull() as ProblemException).code)
            val cur = cf.bookings.get(op, b.id)
            assertEquals(winner, cur.cancelReason, "genau ein Grund, der des Gewinners"); assertEquals(winner.cause, cur.cancelCause)
            assertEquals(1, cf.fx.base.eventPayloads("booking.cancelled", b.id).size, "genau eine Erstattungsentscheidung (ein Ereignis)")
            assertEquals(winner, cf.bookings.history(op, b.id, HistoryView.AUDIT)[1].reason)
        }
    }

    @Test
    fun wiederholung_nach_storno_erzeugt_keinen_zweiten_storno() {
        val w = cf.world(); val run = cf.fx.run(w)
        val (h, b) = cf.booked(w, run)
        cf.bookings.cancel(h.passenger.ctx, b.id, CancelBooking(1, BookingReason.PASSENGER_REQUEST))
        // dieselbe Anfrage noch einmal (ohne Idempotenzregister, nur der Kern): kein zweiter Storno, kein zweites Ereignis (die Wiederholung über denselben Schlüssel prüft BookingIdempotencyHttpTest)
        assertEquals(ErrorCode.REVISION_MISMATCH, code { cf.bookings.cancel(h.passenger.ctx, b.id, CancelBooking(1, BookingReason.PASSENGER_REQUEST)) })
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, code { cf.bookings.cancel(h.passenger.ctx, b.id, CancelBooking(3, BookingReason.PASSENGER_REQUEST)) })
        assertEquals(1, cf.fx.base.eventPayloads("booking.cancelled", b.id).size)
        assertEquals(BookingState.CANCELED.name, cf.bookings.get(h.passenger.ctx, b.id).state)
    }
}

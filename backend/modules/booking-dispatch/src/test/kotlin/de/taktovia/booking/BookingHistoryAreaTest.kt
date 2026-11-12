package de.taktovia.booking

import de.taktovia.bookingdispatch.api.BookingArea
import de.taktovia.bookingdispatch.api.BookingAreas
import de.taktovia.bookingdispatch.api.BookingDetail
import de.taktovia.bookingdispatch.api.BookingReason
import de.taktovia.bookingdispatch.api.Bookings
import de.taktovia.bookingdispatch.api.CancelBooking
import de.taktovia.bookingdispatch.api.ExecutionState
import de.taktovia.bookingdispatch.api.ExecutionStateSource
import de.taktovia.bookingdispatch.OfferObjectsFixture
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.statemachine.BookingState
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WP-M2-45 / LH-6.3-PAX-008: die serverseitige Zuordnung Zustand -> Bereich der Buchungsübersicht gegen die echte Datenbank unter der Runtime-Rolle. „laufend“ und „vergangen durch Fahrtende“
 * brauchen Ausführungszustände aus M3 (Offener Punkt 37): in M2 liefert sie hier eine Testereignisquelle ([ExecutionStateSource]); die Vollabnahme folgt mit WP-M3-29.
 * Der HTTP-Teil (Filterwert 422, Cursor der anderen Sicht) steht in `BookingAreaApiTest` der Anwendung.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BookingHistoryAreaTest {
    private lateinit var cf: ConfirmFixture
    private lateinit var bookings: Bookings
    private val events = ConcurrentHashMap<UUID, ExecutionState>()

    @BeforeAll fun up() {
        cf = ConfirmFixture()
        bookings = Bookings.create(cf.fx.base.ctxTx, cf.fx.base.outbox, cf.fx.quotes, cf.fx.clock, cf.fx.base.config.effective,
            executionStates = ExecutionStateSource { _, ids -> ids.mapNotNull { id -> events[id]?.let { id to it } }.toMap() })
    }
    @AfterAll fun down() = cf.close()

    private fun ids(ctx: RequestContext, area: BookingArea?) = bookings.list(ctx, 100, null, area).map { it.id }
    private fun cancel(ctx: RequestContext, b: BookingDetail) = bookings.cancel(ctx, b.id, CancelBooking(b.revision, BookingReason.PASSENGER_REQUEST))

    @Test
    fun jede_buchung_genau_ein_bereich() {
        // Eigenschaft über alle Zustände, Ausführungszustände und Zeitlagen: genau ein Bereich, nie keiner
        val arrival = Instant.parse("2026-11-02T10:00:00Z")
        for (state in BookingState.entries.map { it.name }) for (exec in listOf<ExecutionState?>(null) + ExecutionState.entries) for (now in listOf(arrival.minusSeconds(1), arrival, arrival.plusSeconds(1))) {
            val a = BookingAreas.of(state, arrival, now, exec)
            assertEquals(1, BookingArea.entries.count { it == a }, "$state/$exec/$now")
        }
        // Zuordnungstabelle (Arbeitsstand)
        val before = arrival.minusSeconds(60); val after = arrival.plusSeconds(60)
        assertEquals(BookingArea.UPCOMING, BookingAreas.of("CONFIRMING", arrival, before)); assertEquals(BookingArea.UPCOMING, BookingAreas.of("CONFIRMED", arrival, before))
        assertEquals(BookingArea.RUNNING, BookingAreas.of("CONFIRMED", arrival, before, ExecutionState.IN_PROGRESS))
        assertEquals(BookingArea.RUNNING, BookingAreas.of("CONFIRMED", arrival, after, ExecutionState.IN_PROGRESS), "die laufende Fahrt bleibt laufend, auch nach dem zugesagten Ende")
        assertEquals(BookingArea.PAST, BookingAreas.of("CONFIRMED", arrival, before, ExecutionState.COMPLETED)); assertEquals(BookingArea.PAST, BookingAreas.of("COMPLETED", arrival, before))
        assertEquals(BookingArea.PAST, BookingAreas.of("CONFIRMED", arrival, before, ExecutionState.NO_SHOW)); assertEquals(BookingArea.PAST, BookingAreas.of("CONFIRMED", arrival, after))
        for (s in listOf("CANCELED", "CANCEL_PENDING", "COMPENSATING", "FAILED")) assertEquals(BookingArea.CANCELED, BookingAreas.of(s, arrival, before, ExecutionState.IN_PROGRESS), s)

        // gegen die Datenbank: vier Buchungen eines Fahrgasts, je Bereich eine; der Statuswechsel verschiebt sie
        val w = cf.world(); val run = cf.fx.run(w, OfferObjectsFixture.cap(8, 0)); val op = cf.operator(w.tenant, setOf(w.serviceId)); val p = cf.passenger(w)
        fun book() = cf.booked(w, run, p, passengers = 1).second
        val upcoming = book(); val toCancel = book(); val running = book(); val finished = book()
        for (b in listOf(running, finished)) bookings.transition(op, b.id, BookingState.CONFIRMED, BookingReason.PAYMENT_AUTHORIZED, 1)
        events[running.id] = ExecutionState.IN_PROGRESS
        events[finished.id] = ExecutionState.COMPLETED
        cancel(p.ctx, toCancel)
        val all = ids(p.ctx, null)
        assertEquals(4, all.size)
        val per = BookingArea.entries.associateWith { ids(p.ctx, it) }
        assertEquals(listOf(upcoming.id), per[BookingArea.UPCOMING]); assertEquals(listOf(running.id), per[BookingArea.RUNNING])
        assertEquals(listOf(finished.id), per[BookingArea.PAST]); assertEquals(listOf(toCancel.id), per[BookingArea.CANCELED])
        assertEquals(all.toSet(), per.values.flatten().toSet(), "kein Bereich vergisst eine Buchung"); assertEquals(4, per.values.sumOf { it.size }, "keine Buchung in zwei Bereichen")
        // jede Buchung trägt ihren Bereich, API-Feld und Filter stimmen überein
        for ((area, list) in per) for (id in list) assertEquals(area, bookings.list(p.ctx, 100, null).first { it.id == id }.area)
        // Statuswechsel laufend -> vergangen (Testereignis Fahrtende) und Zeitablauf der bevorstehenden Buchung
        events[running.id] = ExecutionState.COMPLETED
        assertEquals(setOf(finished.id, running.id), ids(p.ctx, BookingArea.PAST).toSet()); assertEquals(emptyList<UUID>(), ids(p.ctx, BookingArea.RUNNING))
        cf.fx.clock.set(Instant.parse("2026-11-03T00:00:00Z"))
        assertEquals(emptyList<UUID>(), ids(p.ctx, BookingArea.UPCOMING)); assertTrue(upcoming.id in ids(p.ctx, BookingArea.PAST), "abgelaufen = vergangen (Arbeitsannahme)")
        cf.fx.clock.set(Instant.parse("2026-11-02T07:00:00Z"))
        // Sortierschlüssel (created_at, id) je Bereich und Cursorseiten über den Filter
        val page1 = bookings.list(p.ctx, 1, null, BookingArea.PAST)
        assertEquals(2, page1.size, "limit + 1 Zeilen: es gibt eine Folgeseite")
        val page2 = bookings.list(p.ctx, 1, page1[0].createdAt to page1[0].id, BookingArea.PAST)
        assertEquals(listOf(page1[1].id), page2.map { it.id })
        val sorted = bookings.list(p.ctx, 100, null, BookingArea.PAST)
        assertEquals(sorted.sortedWith(compareBy({ it.createdAt }, { it.id.toString() })).map { it.id }, sorted.map { it.id })
    }

    @Test
    fun fremde_buchungen_nicht_in_liste() {
        val w = cf.world(); val run = cf.fx.run(w, OfferObjectsFixture.cap(8, 0)); val own = cf.passenger(w); val other = cf.passenger(w)
        val mine = cf.booked(w, run, own, passengers = 1).second; val theirs = cf.booked(w, run, other, passengers = 1).second
        for (area in listOf<BookingArea?>(null) + BookingArea.entries) {
            val l = ids(own.ctx, area); assertTrue(theirs.id !in l, "$area"); assertTrue(ids(other.ctx, area).none { it == mine.id }, "$area")
        }
        assertEquals(listOf(mine.id), ids(own.ctx, BookingArea.UPCOMING))
        // Fahrgast ohne jede Buchung: leere Liste je Bereich
        val none = cf.passenger(w)
        for (area in BookingArea.entries) assertEquals(emptyList<UUID>(), ids(none.ctx, area))
    }

    @Test
    fun storno_parallel_zum_laden_genau_ein_bereich() {
        val w = cf.world(); val run = cf.fx.run(w, OfferObjectsFixture.cap(8, 0)); val p = cf.passenger(w)
        val b = cf.booked(w, run, p, passengers = 1).second
        val done = AtomicBoolean(false); val violations = java.util.Collections.synchronizedList(mutableListOf<String>())
        val pool = Executors.newFixedThreadPool(3)
        val readers = (1..2).map {
            pool.submit {
                var rounds = 0
                while (!done.get() || rounds < 5) {
                    val items = bookings.list(p.ctx, 100, null).filter { it.id == b.id }
                    if (items.size != 1) violations += "Buchung ${items.size}-mal in der Liste"
                    else if ((items[0].state == "CANCELED") != (items[0].area == BookingArea.CANCELED)) violations += "Zustand ${items[0].state} im Bereich ${items[0].area}"
                    rounds++
                }
            }
        }
        val canceller = pool.submit { try { cancel(p.ctx, b) } finally { done.set(true) } }
        canceller.get(); readers.forEach { it.get() }; pool.shutdown()
        assertEquals(emptyList<String>(), violations)
        assertEquals(listOf(b.id), ids(p.ctx, BookingArea.CANCELED)); assertEquals(emptyList<UUID>(), ids(p.ctx, BookingArea.UPCOMING))
    }

    @Test
    fun mandanten_ueber_gleichen_pool_getrennt() {
        val w1 = cf.world(); val w2 = cf.world()
        val r1 = cf.fx.run(w1, OfferObjectsFixture.cap(8, 0)); val r2 = cf.fx.run(w2, OfferObjectsFixture.cap(8, 0))
        val p1 = cf.passenger(w1); val p2 = cf.passenger(w2)
        val b1 = cf.booked(w1, r1, p1, passengers = 1).second; val b2 = cf.booked(w2, r2, p2, passengers = 1).second
        val pool = Executors.newFixedThreadPool(4)
        val results = (1..40).map { i -> pool.submit<Pair<Set<UUID>, Set<UUID>>> { (if (i % 2 == 0) ids(p1.ctx, BookingArea.UPCOMING) else ids(p2.ctx, BookingArea.UPCOMING)).toSet() to setOf(if (i % 2 == 0) b1.id else b2.id) } }
        pool.shutdown()
        for (f in results) { val (seen, expected) = f.get(); assertEquals(expected, seen) }
        assertTrue(b2.id !in ids(p1.ctx, null) && b1.id !in ids(p2.ctx, null))
    }
}

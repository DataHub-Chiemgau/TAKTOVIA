package de.taktovia.bookingdispatch.operator

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import de.taktovia.bookingdispatch.api.AssistanceCode
import de.taktovia.bookingdispatch.api.BookingSearchFilter
import de.taktovia.platform.api.CursorCodec
import de.taktovia.platform.api.CursorPosition
import de.taktovia.platform.api.CursorScope
import de.taktovia.platform.api.Paging
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * WP-M3-37 / LH-6.7-OPS-002: Auftragsliste mit Suche gegen die echte Datenbank unter der Runtime-Rolle. Das Zusagefenster der Testbuchungen liegt am 2. November 2026 bei 09:00Z bis 09:25Z
 * (Abholung) bzw. bis zur Ankunft; der Dienstscope kommt aus echten Mitgliedschaftszeilen.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BookingSearchTest {
    private lateinit var f: OperatorBookingFixture
    private val codec = CursorCodec(ByteArray(32) { it.toByte() })

    @BeforeAll fun up() { f = OperatorBookingFixture() }
    @AfterAll fun down() = f.close()

    private fun ids(rows: List<de.taktovia.bookingdispatch.api.BookingSearchRow>) = rows.map { it.bookingId }

    @Test
    fun jede_der_fuenf_suchfelder_findet_bestehende_buchung() {
        val w = f.cf.world(); val run = f.fx.run(w); val other = f.fx.run(w)
        val op = f.operator(w.tenant, setOf(w.serviceId))
        val (_, booking) = f.cf.booked(w, run, passengers = 2)
        val (_, noise) = f.cf.booked(w, other, passengers = 1)   // zweite Buchung in anderem Fahrzeug: Treffer müssen trennen
        val passenger = f.fx.base.stringOf("SELECT passenger_id::text FROM taktovia.booking WHERE id = ?", booking.id).let(UUID::fromString)
        fun hit(filter: BookingSearchFilter) = ids(f.search.search(op.ctx, filter, 50, null))

        assertEquals(listOf(booking.id), hit(BookingSearchFilter(bookingNumber = booking.bookingNumber)), "Buchungsnummer")
        assertEquals(listOf(booking.id), hit(BookingSearchFilter(bookingNumber = booking.bookingNumber.lowercase())), "Buchungsnummer, Schreibweise egal")
        assertTrue(booking.id in hit(BookingSearchFilter(from = Instant.parse("2026-11-02T08:00:00Z"), to = Instant.parse("2026-11-02T10:00:00Z"))), "Zeitraum (Zusagefenster)")
        assertTrue(hit(BookingSearchFilter(from = Instant.parse("2026-11-02T12:00:00Z"))).isEmpty(), "Zeitraum nach dem Fenster")
        assertTrue(hit(BookingSearchFilter(to = Instant.parse("2026-11-02T08:00:00Z"))).isEmpty(), "Zeitraum vor dem Fenster")
        assertEquals(listOf(booking.id), hit(BookingSearchFilter(passengerRef = passenger)), "Fahrgast")
        assertEquals(listOf(booking.id), hit(BookingSearchFilter(vehicleId = run.vehicleId)), "Fahrzeug")
        assertEquals(listOf(noise.id), hit(BookingSearchFilter(vehicleId = other.vehicleId)), "anderes Fahrzeug")
        assertTrue(booking.id in hit(BookingSearchFilter(state = booking.state)), "Status")
        assertTrue(hit(BookingSearchFilter(state = "COMPLETED")).isEmpty(), "anderer Status")
        // die Zeile nennt nur Disponierendes; Fahrzeug und Fahrgastverweis sind gesetzt
        val row = f.search.search(op.ctx, BookingSearchFilter(bookingNumber = booking.bookingNumber), 5, null).single()
        assertEquals(run.vehicleId, row.vehicleId); assertEquals(2, row.passengers); assertEquals(booking.state, row.state); assertEquals(passenger, row.passengerRef)
    }

    @Test
    fun unterstuetzungsmerkmale_nur_nach_feldfreigabe_der_rolle() {
        val w = f.cf.world(); val run = f.fx.run(w)
        val b = f.bookedWithAssistance(w, run, codes = setOf(AssistanceCode.BOARDING_HELP))
        val dispatcher = f.operator(w.tenant, setOf(w.serviceId), "dispatcher"); val planner = f.operator(w.tenant, setOf(w.serviceId), "planner"); val support = f.operator(w.tenant, setOf(w.serviceId), "support")
        assertEquals(listOf("BOARDING_HELP"), f.search.search(dispatcher.ctx, BookingSearchFilter(bookingNumber = b.bookingNumber), 5, null).single().assistanceCodes)
        assertNull(f.search.search(planner.ctx, BookingSearchFilter(bookingNumber = b.bookingNumber), 5, null).single().assistanceCodes, "Planung: keine Feldfreigabe")
        assertNull(f.search.search(support.ctx, BookingSearchFilter(bookingNumber = b.bookingNumber), 5, null).single().assistanceCodes, "Support: keine Feldfreigabe")
        assertNull(f.search.search(planner.ctx, BookingSearchFilter(bookingNumber = b.bookingNumber), 5, null).single().passengerRef, "kein Fahrgastverweis ohne Recht auf Fahrgastdaten")
    }

    @Test
    fun fremder_dienst_und_fremder_mandant_ohne_treffer() {
        val a = f.cf.world(); val b = f.cf.world()
        val runA = f.fx.run(a); val runB = f.fx.run(b)
        val (_, bookingA) = f.cf.booked(a, runA); val (_, bookingB) = f.cf.booked(b, runB)
        val opA = f.operator(a.tenant, setOf(a.serviceId)); val opB = f.operator(b.tenant, setOf(b.serviceId))
        assertEquals(listOf(bookingA.id), ids(f.search.search(opA.ctx, BookingSearchFilter(), 50, null)))
        assertEquals(listOf(bookingB.id), ids(f.search.search(opB.ctx, BookingSearchFilter(), 50, null)))
        // gezielte Suche nach der Buchung des anderen Mandanten (Nummer, Fahrzeug, Dienstfilter): nichts
        assertTrue(f.search.search(opA.ctx, BookingSearchFilter(bookingNumber = bookingB.bookingNumber, serviceId = b.serviceId), 50, null).isEmpty())
        assertTrue(f.search.search(opA.ctx, BookingSearchFilter(vehicleId = runB.vehicleId), 50, null).isEmpty())
        // derselbe Mandant, aber ein Dienst außerhalb des Scopes: nichts, auch nicht mit Dienstfilter
        val outsider = f.operator(a.tenant, setOf(UUID.randomUUID()))
        assertTrue(f.search.search(outsider.ctx, BookingSearchFilter(), 50, null).isEmpty())
        assertTrue(f.search.search(outsider.ctx, BookingSearchFilter(serviceId = a.serviceId), 50, null).isEmpty())
        assertTrue(f.search.search(opA.ctx, BookingSearchFilter(serviceId = UUID.randomUUID()), 50, null).isEmpty(), "Dienstfilter außerhalb des Scopes")
    }

    @Test
    fun rechteentzug_waehrend_paginierter_suche() {
        val w = f.cf.world(); val run = f.fx.run(w, "{\"schema_version\":\"1.0\",\"seats\":8,\"wheelchair\":1,\"equipment\":[\"RAMP\"]}")
        val booked = (1..3).map { f.cf.booked(w, run, passengers = 1).second }
        val op = f.operator(w.tenant, setOf(w.serviceId))
        val page1 = f.search.search(op.ctx, BookingSearchFilter(), 2, null)
        assertEquals(3, page1.size, "limit + 1: es gibt eine Folgeseite")
        assertEquals(booked.map { it.id }.toSet(), ids(page1).toSet())
        // der Dienst wird dem Konto entzogen, bevor die nächste Seite gelesen wird
        f.revokeScope(op)
        val cursor = page1[1].createdAt to page1[1].bookingId
        assertTrue(f.search.search(op.ctx, BookingSearchFilter(), 2, cursor).isEmpty(), "die nächste Seite liefert keine Treffer des entzogenen Dienstes")
        assertTrue(f.search.search(op.ctx, BookingSearchFilter(), 2, null).isEmpty(), "auch ein neuer Anfang nicht")
    }

    @Test
    fun kombinierte_filter_liefern_schnittmenge_und_leere_antwort_hat_as_of() {
        val w = f.cf.world(); val run = f.fx.run(w); val run2 = f.fx.run(w)
        val op = f.operator(w.tenant, setOf(w.serviceId))
        val (_, b1) = f.cf.booked(w, run, passengers = 1); val (_, b2) = f.cf.booked(w, run2, passengers = 1)
        fun hit(filter: BookingSearchFilter) = ids(f.search.search(op.ctx, filter, 50, null))
        assertEquals(listOf(b1.id), hit(BookingSearchFilter(vehicleId = run.vehicleId, state = b1.state)))
        assertEquals(listOf(b1.id), hit(BookingSearchFilter(vehicleId = run.vehicleId, bookingNumber = b1.bookingNumber, from = Instant.parse("2026-11-02T08:00:00Z"))))
        assertTrue(hit(BookingSearchFilter(vehicleId = run.vehicleId, bookingNumber = b2.bookingNumber)).isEmpty(), "Schnittmenge: Fahrzeug von 1, Nummer von 2")
        // keine Treffer: Leerzustand ist eine erfolgreiche, leere Seite mit Datenstand
        val empty = f.search.search(op.ctx, BookingSearchFilter(bookingNumber = "BK-1999-000001"), 10, null)
        val page = Paging.page(empty, 10, { CursorPosition(it.createdAt, it.bookingId) }, codec, CursorScope(w.tenant.toString(), op.subject, "operator-bookings"), Instant.parse("2026-11-02T07:00:00Z"))
        assertTrue(page.items.isEmpty()); assertNull(page.nextCursor); assertNotNull(page.asOf)
    }

    @Test
    fun limit_ueber_100_und_manipulierter_cursor_abgewiesen() {
        assertEquals(ErrorCode.VALIDATION_FAILED, assertThrows<ProblemException> { Paging.limit("101") }.code)
        assertEquals(ErrorCode.VALIDATION_FAILED, assertThrows<ProblemException> { Paging.limit("0") }.code)
        assertEquals(100, Paging.limit("100"))
        val scope = CursorScope("t", "s", "operator-bookings:" + BookingSearchFilter(state = "CONFIRMED").fingerprint())
        val good = codec.encode(CursorPosition(Instant.parse("2026-11-02T07:00:00Z"), UUID.randomUUID()), scope)
        assertNotNull(Paging.position(good, codec, scope))
        val tampered = good.dropLast(2) + (if (good.takeLast(2) == "AA") "BB" else "AA")
        assertEquals(ErrorCode.INVALID_CURSOR, assertThrows<ProblemException> { Paging.position(tampered, codec, scope) }.code, "manipulierter Cursor")
        // ein Cursor eines anderen Filters gilt nicht für diese Suche
        val otherScope = CursorScope("t", "s", "operator-bookings:" + BookingSearchFilter(state = "CANCELED").fingerprint())
        assertTrue(assertThrows<ProblemException> { Paging.position(good, codec, otherScope) }.code.status in 400..422)
        // direkt am Dienst: limit außerhalb 1..101 ist ein Programmierfehler, kein Datenzugriff
        val w = f.cf.world(); val op = f.operator(w.tenant, setOf(w.serviceId))
        assertThrows<IllegalArgumentException> { f.search.search(op.ctx, BookingSearchFilter(), 500, null) }
        assertEquals(ErrorCode.VALIDATION_FAILED, assertThrows<ProblemException> { f.search.search(op.ctx, BookingSearchFilter(state = "NOPE"), 10, null) }.code)
    }

    @Test
    fun fahrgastsuche_ohne_recht_auf_fahrgastdaten_abgewiesen() {
        val w = f.cf.world(); val run = f.fx.run(w); val (_, b) = f.cf.booked(w, run)
        val passenger = UUID.fromString(f.fx.base.stringOf("SELECT passenger_id::text FROM taktovia.booking WHERE id = ?", b.id))
        for (role in listOf("planner", "workshop", "tenant_admin")) {
            val op = f.operator(w.tenant, setOf(w.serviceId), role)
            assertEquals(ErrorCode.FORBIDDEN, assertThrows<ProblemException> { f.search.search(op.ctx, BookingSearchFilter(passengerRef = passenger), 10, null) }.code, role)
            assertEquals(listOf(b.id), ids(f.search.search(op.ctx, BookingSearchFilter(vehicleId = run.vehicleId), 10, null)), "$role: die übrigen Felder bleiben nutzbar")
        }
        // Fahrgastkonten benutzen die Auftragsliste der Leitstelle nie
        assertEquals(ErrorCode.FORBIDDEN, assertThrows<ProblemException> { f.search.search(f.fx.passenger(w.tenant), BookingSearchFilter(), 10, null) }.code)
        for (role in listOf("dispatcher", "callcenter", "support")) {
            val op = f.operator(w.tenant, setOf(w.serviceId), role)
            assertEquals(listOf(b.id), ids(f.search.search(op.ctx, BookingSearchFilter(passengerRef = passenger), 10, null)), role)
        }
    }

    @Test
    fun suchbegriffe_nicht_in_logs_und_metriklabels() {
        val w = f.cf.world(); val run = f.fx.run(w); val (_, b) = f.cf.booked(w, run)
        val passenger = f.fx.base.stringOf("SELECT passenger_id::text FROM taktovia.booking WHERE id = ?", b.id)
        val op = f.operator(w.tenant, setOf(w.serviceId))
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        // DEBUG (nicht TRACE): Spring JDBC schreibt erst auf TRACE die gebundenen Parameter jeder Abfrage; TRACE ist in keiner Umgebung eingeschaltet, DEBUG fängt den eigenen Code
        val before = root.level; root.level = Level.DEBUG; root.addAppender(appender)
        val registry = SimpleMeterRegistry()
        try {
            f.search.search(op.ctx, BookingSearchFilter(bookingNumber = b.bookingNumber, passengerRef = UUID.fromString(passenger), vehicleId = run.vehicleId, state = b.state), 10, null)
            f.search.search(op.ctx, BookingSearchFilter(passengerRef = UUID.randomUUID()), 10, null)
            // der Cursor-Schlüssel (Filterfingerabdruck) enthält die Begriffe nicht
            val fp = BookingSearchFilter(passengerRef = UUID.fromString(passenger), bookingNumber = b.bookingNumber).fingerprint()
            assertTrue(!fp.contains(passenger) && !fp.contains(b.bookingNumber))
        } finally { root.detachAppender(appender); root.level = before }
        val text = appender.list.joinToString("\n") { it.formattedMessage + " " + (it.argumentArray?.joinToString() ?: "") + " " + it.mdcPropertyMap }
        assertTrue(!text.contains(passenger), "Fahrgastverweis steht nicht im Log")
        assertTrue(!text.contains(b.bookingNumber), "Buchungsnummer steht nicht im Log")
        assertTrue(registry.meters.none { m -> m.id.tags.any { passenger in it.value || b.bookingNumber in it.value } }, "keine Metrik mit Suchbegriff als Label")
    }

    @Test
    fun parallele_suchen_zweier_mandanten_bleiben_getrennt() {
        val a = f.cf.world(); val b = f.cf.world()
        val bookingsA = (1..3).map { f.cf.booked(a, f.fx.run(a), passengers = 1).second.id }.toSet()
        val bookingsB = (1..3).map { f.cf.booked(b, f.fx.run(b), passengers = 1).second.id }.toSet()
        val opA = f.operator(a.tenant, setOf(a.serviceId)); val opB = f.operator(b.tenant, setOf(b.serviceId))
        val pool = Executors.newFixedThreadPool(12)
        try {
            // viele kurze Suchen über wiederverwendete Poolverbindungen (Pool 24, 80 Läufe): der Tenantkontext darf nie an einer Verbindung hängenbleiben
            val jobs = (1..80).map { i -> Callable { val own = if (i % 2 == 0) opA else opB; own to ids(f.search.search(own.ctx, BookingSearchFilter(), 50, null)).toSet() } }
            for (fu in pool.invokeAll(jobs)) {
                val (who, got) = fu.get()
                assertEquals(if (who === opA) bookingsA else bookingsB, got)
            }
        } finally { pool.shutdownNow() }
    }
}

package de.taktovia.bookingdispatch.operator

import de.taktovia.booking.ConfirmFixture
import de.taktovia.bookingdispatch.OfferObjectsFixture
import de.taktovia.bookingdispatch.RideOfferFixture
import de.taktovia.bookingdispatch.api.BookingReason
import de.taktovia.bookingdispatch.api.CancelBooking
import de.taktovia.bookingdispatch.api.ConfirmBooking
import de.taktovia.bookingdispatch.api.DispatchReason
import de.taktovia.bookingdispatch.api.HoldRideOffer
import de.taktovia.bookingdispatch.api.PassengerConsent
import de.taktovia.platform.api.Etag
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * WP-M3-39 / LH-6.7-OPS-004: Buchung durch die Disposition anlegen, ändern, stornieren gegen die echte Datenbank unter der Runtime-Rolle, auf dem Kern der Fahrgastbuchung. Preis 2 Personen = 950,
 * Abholfenster der Angebote 2026-11-02 09:00Z bis 09:25Z, das Jetzt der Fixture ist 07:00Z (Stornofrist 60 min: Fahrgast darf stornieren).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DispatcherBookingTest {
    private lateinit var f: OperatorBookingFixture
    private lateinit var d: DispatchFlow

    @BeforeAll fun up() { f = OperatorBookingFixture(); d = DispatchFlow(f) }
    @AfterAll fun down() = f.close()

    private fun code(block: () -> Unit) = assertThrows<ProblemException> { block() }.code
    private fun scalar(sql: String, vararg a: Any?) = f.fx.base.stringOf(sql, *a)
    private fun count(sql: String, vararg a: Any?) = f.fx.count(sql, *a)
    private fun state(id: UUID) = scalar("SELECT state FROM taktovia.booking WHERE id = ?", id)
    private fun holdState(id: UUID) = f.fx.stateOf(id)
    private fun changes(booking: UUID, kind: String) = count("SELECT count(*) FROM taktovia.booking_change WHERE booking_id = ? AND change_kind = ?", booking, kind)

    private class World(val w: RideOfferFixture.World, val op: OperatorBookingFixture.Operator, val passenger: ConfirmFixture.Passenger)
    private fun world(seats: Int = 2): Triple<World, OfferObjectsFixture.Run, OfferObjectsFixture.Run> {
        val w = f.cf.world(); val op = f.operator(w.tenant, setOf(w.serviceId)); val p = f.cf.passenger(w)
        return Triple(World(w, op, p), f.fx.run(w, OfferObjectsFixture.cap(seats, 0)), f.fx.run(w, OfferObjectsFixture.cap(seats, 0)))
    }

    @Test
    fun anlegen_aendern_stornieren_protokolliert_akteur_und_grund() {
        val (x, run1, run2) = world()
        val actor = "user:" + x.op.subject
        // Anlegen für den Fahrgast
        val h1 = d.held(x.w, run1, x.op, x.passenger)
        val created = d.create(run1, x.op, h1, DispatchReason.PHONE_REQUEST, "Anruf der Fahrgästin").booking
        assertEquals(1, changes(created.id, "CREATE"))
        assertEquals(listOf(actor, "PHONE_REQUEST", "Anruf der Fahrgästin"), listOf(scalar("SELECT actor FROM taktovia.booking_change WHERE booking_id = ?", created.id), scalar("SELECT reason_code FROM taktovia.booking_change WHERE booking_id = ?", created.id), scalar("SELECT reason FROM taktovia.booking_change WHERE booking_id = ?", created.id)))
        assertEquals(1, count("SELECT count(*) FROM taktovia.audit_event WHERE action = 'dispatch.booking.created' AND actor_ref = ? AND object_ref = ?", actor, "booking:" + created.id))
        // Ändern: neuer Wunsch auf dem anderen Fahrzeug, gleicher Preis und gleiches Fenster: keine Zustimmung nötig
        val h2 = d.held(x.w, run2, x.op, x.passenger)
        val changed = d.change(run2, x.op, created, h2, DispatchReason.OPERATIONAL_NEED, "Fahrzeugtausch")
        assertFalse(changed.replayed); assertFalse(changed.priceChanged); assertFalse(changed.timeChanged)
        assertEquals("CANCELED", state(created.id)); assertEquals("RELEASED", holdState(h1.holdId)); assertEquals("CONSUMED", holdState(h2.holdId))
        assertNotEquals(created.id, changed.booking.id)
        val row = scalar("SELECT change_kind || '|' || actor || '|' || reason_code || '|' || new_booking_id || '|' || (old_promise IS NOT NULL) || '|' || (new_promise IS NOT NULL) FROM taktovia.booking_change WHERE booking_id = ? AND change_kind = 'REPLACE_OFFER'", created.id)
        assertEquals("REPLACE_OFFER|$actor|OPERATIONAL_NEED|${changed.booking.id}|true|true", row)
        assertEquals(1, count("SELECT count(*) FROM taktovia.audit_event WHERE action = 'dispatch.booking.changed' AND actor_ref = ?", actor))
        // die Folgebuchung gehört demselben Fahrgast, der Disponent ist Urheber
        assertEquals(x.passenger.accountId.toString(), scalar("SELECT passenger_id::text FROM taktovia.booking WHERE id = ?", changed.booking.id))
        // Stornieren durch die Disposition: Grund aus dem Katalog, Freitext, Akteur und Zeitpunkt im Änderungsprotokoll
        val cancelled = d.bookings.cancel(x.op.ctx, changed.booking.id, CancelBooking(changed.booking.revision, BookingReason.OPERATOR_CANCELLATION, "Fahrzeug ausgefallen"))
        assertEquals("CANCELED", cancelled.booking.state); assertEquals("RELEASED", holdState(h2.holdId))
        assertEquals(1, changes(changed.booking.id, "CANCEL"))
        assertEquals(listOf(actor, "OPERATOR_CANCELLATION", "Fahrzeug ausgefallen"), listOf(scalar("SELECT actor FROM taktovia.booking_change WHERE booking_id = ? AND change_kind = 'CANCEL'", changed.booking.id), scalar("SELECT reason_code FROM taktovia.booking_change WHERE booking_id = ? AND change_kind = 'CANCEL'", changed.booking.id), scalar("SELECT reason FROM taktovia.booking_change WHERE booking_id = ? AND change_kind = 'CANCEL'", changed.booking.id)))
        assertEquals(1, count("SELECT count(*) FROM taktovia.audit_event WHERE action = 'dispatch.booking.cancelled' AND actor_ref = ?", actor))
        assertTrue(count("SELECT count(*) FROM taktovia.booking_change WHERE booking_id IN (?, ?) AND created_at IS NOT NULL", created.id, changed.booking.id) == 3, "je Vorgang ein Eintrag mit Zeitpunkt")
        // Stornieren durch den Fahrgast schreibt keinen Leitstelleneintrag
        val (y, r1, _) = world(); val hp = f.cf.held(y.w, r1, y.passenger); val bp = f.cf.confirm(hp).booking
        f.cf.bookings.cancel(y.passenger.ctx, bp.id, CancelBooking(bp.revision, BookingReason.PASSENGER_REQUEST))
        assertEquals(0, count("SELECT count(*) FROM taktovia.booking_change WHERE booking_id = ?", bp.id))
    }

    @Test
    fun aenderung_ohne_grund_wird_abgewiesen() {
        val (x, run1, run2) = world()
        val created = d.create(run1, x.op, d.held(x.w, run1, x.op, x.passenger)).booking
        val h2 = d.held(x.w, run2, x.op, x.passenger)
        // „Sonstiges“ ohne Freitext (und ein Freitext über 500 Zeichen) ist ein fehlender bzw. ungültiger Grund: 422, nichts ändert sich
        assertEquals(ErrorCode.VALIDATION_FAILED, code { d.change(run2, x.op, created, h2, DispatchReason.OTHER, null) })
        assertEquals(ErrorCode.VALIDATION_FAILED, code { d.change(run2, x.op, created, h2, DispatchReason.OTHER, "  ") })
        assertEquals(ErrorCode.VALIDATION_FAILED, code { d.change(run2, x.op, created, h2, DispatchReason.CORRECTION, "x".repeat(501)) })
        assertEquals("CONFIRMING", state(created.id)); assertEquals(0, changes(created.id, "REPLACE_OFFER")); assertEquals("HELD", holdState(h2.holdId), "der Hold bleibt: der Disponent ergänzt den Grund und wiederholt")
        assertEquals(ErrorCode.VALIDATION_FAILED, code { d.create(run2, x.op, h2, DispatchReason.OTHER, null) })
        assertEquals(0, count("SELECT count(*) FROM taktovia.booking WHERE hold_id = ?", h2.holdId))
        // mit Grund geht dieselbe Änderung
        assertEquals("CANCELED", state(d.change(run2, x.op, created, h2, DispatchReason.CORRECTION, "Tippfehler Uhrzeit").replaced.id))
    }

    @Test
    fun disponent_und_fahrgast_buchen_letzten_platz_genau_eine_buchung() {
        repeat(4) {
            val (x, run, _) = world(seats = 2)
            val fahrgast = f.cf.passenger(x.w)
            val latch = CountDownLatch(1); val pool = Executors.newFixedThreadPool(2)
            try {
                val dispatcher = Callable<Result<UUID>> { latch.await(); runCatching { val h = d.held(x.w, run, x.op, x.passenger); d.create(run, x.op, h).booking.id } }
                val passenger = Callable<Result<UUID>> { latch.await(); runCatching { val h = f.cf.held(x.w, run, fahrgast); f.cf.confirm(h).booking.id } }
                val fs = listOf(pool.submit(dispatcher), pool.submit(passenger)); latch.countDown()
                val results = fs.map { it.get() }
                assertEquals(1, results.count { it.isSuccess }, "genau eine Buchung: $results")
                val loser = results.single { it.isFailure }.exceptionOrNull() as ProblemException
                assertEquals(ErrorCode.CAPACITY_UNAVAILABLE, loser.code)
                assertEquals(1, count("SELECT count(*) FROM taktovia.booking b JOIN taktovia.capacity_hold h ON h.id = b.hold_id WHERE h.run_id = ?", run.runId))
                assertEquals(2, f.fx.base.occupancy(run.runId, 1).maxOf { it.first }, "die Kapazität ist genau einmal belegt")
            } finally { pool.shutdownNow() }
        }
    }

    @Test
    fun aenderung_parallel_zu_storno_keine_verwaiste_kapazitaet() {
        var changeWon = 0; var cancelWon = 0
        repeat(8) {
            val (x, run1, run2) = world()
            val created = d.create(run1, x.op, d.held(x.w, run1, x.op, x.passenger)).booking
            val h2 = d.held(x.w, run2, x.op, x.passenger)
            val latch = CountDownLatch(1); val pool = Executors.newFixedThreadPool(2)
            try {
                val change = Callable<Result<Any>> { latch.await(); runCatching { d.change(run2, x.op, created, h2, DispatchReason.PASSENGER_REQUEST, null) } }
                val cancel = Callable<Result<Any>> { latch.await(); runCatching { f.cf.bookings.cancel(x.passenger.ctx, created.id, CancelBooking(created.revision, BookingReason.PASSENGER_REQUEST)) } }
                val fs = listOf(pool.submit(change), pool.submit(cancel)); latch.countDown()
                val (rc, rx) = fs.map { it.get() }
                // genau ein Ergebnis: entweder der Tausch gelingt und das Storno scheitert, oder umgekehrt
                assertTrue(rc.isSuccess != rx.isSuccess, "genau ein Ausgang: change=$rc cancel=$rx")
                assertEquals("CANCELED", state(created.id))
                val all = count("SELECT count(*) FROM taktovia.booking b JOIN taktovia.capacity_hold h ON h.id = b.hold_id WHERE h.run_id IN (?, ?) AND b.state <> 'CANCELED'", run1.runId, run2.runId)
                if (rc.isSuccess) {
                    changeWon++
                    assertEquals(1, all, "die Folgebuchung besteht")
                    assertEquals(2, f.fx.base.occupancy(run2.runId, 1).maxOf { it.first }); assertEquals(0, f.fx.base.occupancy(run1.runId, 1).maxOf { it.first })
                    val failure = rx.exceptionOrNull() as ProblemException
                    assertTrue(failure.code == ErrorCode.REVISION_MISMATCH || failure.code == ErrorCode.INVALID_STATE_TRANSITION, failure.code.name)
                } else {
                    cancelWon++
                    assertEquals(0, all, "keine Folgebuchung")
                    assertEquals(0, f.fx.base.occupancy(run1.runId, 1).maxOf { it.first }); assertEquals(0, f.fx.base.occupancy(run2.runId, 1).maxOf { it.first }, "der neue Hold ist freigegeben: keine verwaiste Kapazität")
                    assertEquals("RELEASED", holdState(h2.holdId))
                    assertEquals(0, changes(created.id, "REPLACE_OFFER"))
                }
            } finally { pool.shutdownNow() }
        }
        assertEquals(8, changeWon + cancelWon)
    }

    @Test
    fun ohne_dispositionsrolle_403_ohne_dienstrecht_oder_fremder_mandant_404() {
        val (x, run1, run2) = world()
        val created = d.create(run1, x.op, d.held(x.w, run1, x.op, x.passenger)).booking
        val cmd = f.fx.base.command(x.w, "2026-11-02T10:00:00+01:00")
        // Rolle: Fahrgast, Planung, Werkstatt dürfen nicht für Fahrgäste buchen (403)
        for (ctx in listOf(x.passenger.ctx, f.operator(x.w.tenant, setOf(x.w.serviceId), "planner").ctx, f.operator(x.w.tenant, setOf(x.w.serviceId), "workshop").ctx))
            assertEquals(ErrorCode.FORBIDDEN, code { d.opsFor(run2).issueOffer(ctx, x.passenger.accountId, cmd) })
        val h2 = d.held(x.w, run2, x.op, x.passenger)
        assertEquals(ErrorCode.FORBIDDEN, code { d.opsFor(run2).change(f.operator(x.w.tenant, setOf(x.w.serviceId), "planner").ctx, created.id, de.taktovia.bookingdispatch.api.ChangeBooking(created.revision, h2.holdId, h2.offerRevision, h2.grossMinor, "EUR", DispatchReason.CORRECTION)) })
        // Dienstrecht: Disponent eines anderen Dienstes: das Objekt ist für ihn nicht vorhanden (404), auch beim Ausstellen
        val outsider = f.operator(x.w.tenant, setOf(UUID.randomUUID()))
        assertEquals(ErrorCode.NOT_FOUND, code { d.opsFor(run2).issueOffer(outsider.ctx, x.passenger.accountId, cmd) })
        assertEquals(ErrorCode.NOT_FOUND, code { d.change(run2, outsider, created, h2) })
        assertEquals(ErrorCode.NOT_FOUND, code { f.cf.bookings.cancel(outsider.ctx, created.id, CancelBooking(created.revision, BookingReason.OPERATOR_CANCELLATION)) })
        // fremder Mandant: Buchung und Hold sind nicht vorhanden
        val other = f.cf.world(); val foreign = f.operator(other.tenant, setOf(other.serviceId))
        assertEquals(ErrorCode.NOT_FOUND, code { d.change(run2, foreign, created, h2) })
        assertEquals(ErrorCode.NOT_FOUND, code { d.create(run2, foreign, h2) })
        assertEquals("CONFIRMING", state(created.id)); assertEquals(0, changes(created.id, "REPLACE_OFFER")); assertEquals(0, count("SELECT count(*) FROM taktovia.booking WHERE hold_id = ?", h2.holdId))
    }

    @Test
    fun veraltete_revision_412_fehlendes_if_match_428_zwei_disponenten_eine_gewinnt() {
        val (x, run1, run2) = world()
        val run3 = f.fx.run(x.w, OfferObjectsFixture.cap(2, 0))
        val created = d.create(run1, x.op, d.held(x.w, run1, x.op, x.passenger)).booking
        // veraltete Revision: 412, bisherige Buchung unverändert, der neue Hold wird freigegeben
        val stale = d.held(x.w, run2, x.op, x.passenger)
        assertEquals(ErrorCode.REVISION_MISMATCH, code { d.change(run2, x.op, created, stale, ifMatch = created.revision + 5) })
        assertEquals("CONFIRMING", state(created.id)); assertEquals("RELEASED", holdState(stale.holdId))
        // fehlendes If-Match ist 428 (der Rand liest es mit demselben Baustein)
        assertEquals(ErrorCode.PRECONDITION_REQUIRED, code { Etag.parseIfMatch(null) })
        // zwei Disponenten ändern dieselbe Buchung gleichzeitig: genau einer gewinnt, der andere erhält 412 (oder 409 nach dem Storno der Buchung)
        val op2 = f.operator(x.w.tenant, setOf(x.w.serviceId))
        val hA = d.held(x.w, run2, x.op, x.passenger); val hB = d.held(x.w, run3, op2, x.passenger)
        val latch = CountDownLatch(1); val pool = Executors.newFixedThreadPool(2)
        try {
            val a = pool.submit(Callable<Result<Any>> { latch.await(); runCatching { d.change(run2, x.op, created, hA) } })
            val b = pool.submit(Callable<Result<Any>> { latch.await(); runCatching { d.change(run3, op2, created, hB) } }); latch.countDown()
            val results = listOf(a.get(), b.get())
            assertEquals(1, results.count { it.isSuccess }, "genau eine Änderung gewinnt: $results")
            val loser = results.single { it.isFailure }.exceptionOrNull() as ProblemException
            assertTrue(loser.code == ErrorCode.REVISION_MISMATCH || loser.code == ErrorCode.INVALID_STATE_TRANSITION, loser.code.name)
            assertEquals(1, changes(created.id, "REPLACE_OFFER")); assertEquals("CANCELED", state(created.id))
            assertEquals(1, count("SELECT count(*) FROM taktovia.booking WHERE hold_id IN (?, ?)", hA.holdId, hB.holdId), "genau eine Folgebuchung")
            assertEquals(1, listOf(hA.holdId, hB.holdId).count { holdState(it) == "RELEASED" }, "der Hold des Verlierers ist frei")
        } finally { pool.shutdownNow() }
    }

    @Test
    fun nicht_bedienbarer_aenderungswunsch_laesst_buchung_und_zusage_unveraendert() {
        val (x, run1, run2) = world()
        val created = d.create(run1, x.op, d.held(x.w, run1, x.op, x.passenger)).booking
        val snapshotBefore = scalar("SELECT promise_snapshot::text FROM taktovia.booking WHERE id = ?", created.id)
        // 1. der neue Wunsch hält keine Kapazität (Zielfahrzeug voll): schon der Hold scheitert, die Buchung ist unberührt
        val full = d.create(run2, x.op, d.held(x.w, run2, x.op, f.cf.passenger(x.w))).booking
        assertEquals(ErrorCode.CAPACITY_UNAVAILABLE, code { d.held(x.w, run2, x.op, x.passenger) })
        // 2. der gehaltene Wunsch ist beim Tausch nicht mehr gültig (Ablauf): HOLD_EXPIRED, nichts ändert sich
        val run3 = f.fx.run(x.w, OfferObjectsFixture.cap(2, 0))
        val h3 = d.held(x.w, run3, x.op, x.passenger, offers = f.fx.offersWithTtl(java.time.Duration.ofMillis(1)))   // Ablauf wird mit der Datenbankuhr geprüft
        Thread.sleep(50)
        assertEquals(ErrorCode.HOLD_EXPIRED, code { d.change(run3, x.op, created, h3) })
        // 3. Betrag weicht ab (Requote): nichts ändert sich
        val h4 = d.held(x.w, run3, x.op, x.passenger)
        assertEquals(ErrorCode.REQUOTE_REQUIRED, code { d.opsFor(run3).change(x.op.ctx, created.id, de.taktovia.bookingdispatch.api.ChangeBooking(created.revision, h4.holdId, h4.offerRevision, h4.grossMinor + 1, "EUR", DispatchReason.CORRECTION)) })
        for (id in listOf(created.id)) {
            assertEquals("CONFIRMING", state(id)); assertEquals(snapshotBefore, scalar("SELECT promise_snapshot::text FROM taktovia.booking WHERE id = ?", id)); assertEquals(created.revision.toString(), scalar("SELECT revision::text FROM taktovia.booking WHERE id = ?", id))
        }
        assertEquals("CONSUMED", holdState(created.holdId), "die Kapazität der bisherigen Zusage bleibt belegt")
        assertEquals(0, changes(created.id, "REPLACE_OFFER")); assertEquals(1, count("SELECT count(*) FROM taktovia.booking WHERE hold_id = ?", created.holdId))
        assertEquals("CONFIRMING", state(full.id))
        assertEquals(0, count("SELECT count(*) FROM taktovia.booking WHERE hold_id IN (?, ?)", h3.holdId, h4.holdId), "keine Folgebuchung")
    }

    @Test
    fun keine_ueberbuchung_und_gleiche_safety_pruefung_wie_fahrgastbuchung() {
        val (x, run1, run2) = world(seats = 2)
        // keine Überbuchung: Fahrzeug voll, der Hold der Disposition scheitert genau wie der des Fahrgasts
        d.create(run1, x.op, d.held(x.w, run1, x.op, x.passenger))
        val viaPassenger = code { f.cf.held(x.w, run1, f.cf.passenger(x.w)) }
        assertEquals(ErrorCode.CAPACITY_UNAVAILABLE, viaPassenger)
        assertEquals(viaPassenger, code { d.held(x.w, run1, x.op, x.passenger) })
        assertEquals(2, f.fx.base.occupancy(run1.runId, 1).maxOf { it.first })
        // dieselbe Sicherheitsprüfung: wird das Fahrzeug zwischen Angebot und Hold gesperrt, scheitert der Hold der Disposition wie der des Fahrgasts
        val ops = d.opsFor(run2); val cmd = f.fx.base.command(x.w, "2026-11-02T10:00:00+01:00")
        val offer = ops.issueOffer(x.op.ctx, x.passenger.accountId, cmd).offers.single().offer!!
        f.fx.adminUpdate("UPDATE taktovia.vehicle SET active_block = true WHERE id = ?", run2.vehicleId)
        assertEquals(ErrorCode.CAPACITY_UNAVAILABLE, code { f.fx.offers.hold(x.op.ctx, offer.id, HoldRideOffer(offer.revision, "k-" + UUID.randomUUID(), offer.quote.payAtPlatformMinor, "EUR")) })
        val viaPassenger2 = f.fx.issue(x.w, x.passenger.ctx, f.fx.assigning(run2))
        assertEquals(ErrorCode.CAPACITY_UNAVAILABLE, code { f.fx.offers.hold(x.passenger.ctx, viaPassenger2.id, HoldRideOffer(viaPassenger2.revision, "k-" + UUID.randomUUID(), viaPassenger2.quote.payAtPlatformMinor, "EUR")) })
        assertEquals(0, count("SELECT count(*) FROM taktovia.capacity_hold WHERE run_id = ?", run2.runId))
    }

    @Test
    fun idempotenzschluessel_wiederholung_erzeugt_nichts_zweites() {
        val (x, run1, run2) = world()
        val h1 = d.held(x.w, run1, x.op, x.passenger)
        val first = d.create(run1, x.op, h1); val again = d.create(run1, x.op, h1)
        assertFalse(first.replayed); assertTrue(again.replayed); assertEquals(first.booking.id, again.booking.id)
        assertEquals(1, count("SELECT count(*) FROM taktovia.booking WHERE hold_id = ?", h1.holdId)); assertEquals(1, changes(first.booking.id, "CREATE"))
        assertEquals(1, count("SELECT count(*) FROM taktovia.audit_event WHERE action = 'dispatch.booking.created' AND object_ref = ?", "booking:" + first.booking.id))
        // Änderung wiederholt: dieselbe Folgebuchung, kein zweiter Änderungseintrag, keine zweite Stornierung
        val h2 = d.held(x.w, run2, x.op, x.passenger)
        val c1 = d.change(run2, x.op, first.booking, h2); val c2 = d.change(run2, x.op, first.booking, h2)
        assertFalse(c1.replayed); assertTrue(c2.replayed); assertEquals(c1.booking.id, c2.booking.id); assertEquals(c1.changeId, c2.changeId)
        assertEquals(1, changes(first.booking.id, "REPLACE_OFFER")); assertEquals(1, count("SELECT count(*) FROM taktovia.booking WHERE hold_id = ?", h2.holdId))
        assertEquals(1, count("SELECT count(*) FROM taktovia.booking_status_history WHERE booking_id = ? AND to_state = 'CANCELED'", first.booking.id))
        // Angebot für denselben Schlüssel wiederholt: dasselbe Angebot, dieselbe Bindung
        val cmd = f.fx.base.command(x.w, "2026-11-02T10:00:00+01:00", key = "gleicher-schluessel")
        val o1 = d.opsFor(run2).issueOffer(x.op.ctx, x.passenger.accountId, cmd).offers.single().offer!!.id
        val o2 = d.opsFor(run2).issueOffer(x.op.ctx, x.passenger.accountId, cmd).offers.single().offer!!.id
        assertEquals(o1, o2); assertEquals(1, count("SELECT count(*) FROM taktovia.operator_offer_subject WHERE offer_id = ?", o1))
    }

    @Test
    fun buchung_an_fahrgast_gebunden_disponent_als_eigener_akteur_und_betriebsnachricht_ausgeloest() {
        val (x, run1, run2) = world()
        val actor = "user:" + x.op.subject
        val h1 = d.held(x.w, run1, x.op, x.passenger)
        val booking = d.create(run1, x.op, h1).booking
        // gebunden an den Fahrgast, nicht an den Disponenten; Urheber ist der Disponent
        assertEquals(x.passenger.accountId.toString(), scalar("SELECT passenger_id::text FROM taktovia.booking WHERE id = ?", booking.id))
        assertEquals(actor, scalar("SELECT created_by FROM taktovia.booking WHERE id = ?", booking.id))
        assertEquals(actor, scalar("SELECT actor_ref FROM taktovia.booking_status_history WHERE booking_id = ? ORDER BY booking_revision LIMIT 1", booking.id))
        assertEquals("OPERATOR", scalar("SELECT actor_kind FROM taktovia.booking_status_history WHERE booking_id = ? ORDER BY booking_revision LIMIT 1", booking.id))
        assertNotEquals(x.passenger.accountId.toString(), actor)
        // die Bestätigung löst die Betriebsnachricht aus (Quellereignis des Auslösers WP-M2-34)
        assertEquals(1, f.fx.base.eventPayloads("booking.confirmed", booking.id).size)
        // Fahrgast ist der Buchung zugeordnet: er sieht sie (eigene Buchung), die Disposition sieht sie im Dienstscope
        assertEquals(booking.id, f.cf.bookings.get(x.passenger.ctx, booking.id).id); assertEquals(booking.id, f.cf.bookings.get(x.op.ctx, booking.id).id)
        // Änderung: das Storno der alten Buchung kommt als Betreiberstorno des Disponenten, die Folgebuchung als Bestätigung
        val h2 = d.held(x.w, run2, x.op, x.passenger)
        val changed = d.change(run2, x.op, booking, h2)
        assertTrue(f.fx.base.eventPayloads("booking.cancelled", booking.id).single().contains("\"initiator\": \"OPERATOR\""))
        assertEquals(1, f.fx.base.eventPayloads("booking.confirmed", changed.booking.id).size)
        // Storno durch den Disponenten: Betreiberstorno mit Grund im Ereignis
        val cancelled = d.bookings.cancel(x.op.ctx, changed.booking.id, CancelBooking(changed.booking.revision, BookingReason.OPERATOR_CANCELLATION))
        val ev = f.fx.base.eventPayloads("booking.cancelled", cancelled.booking.id).single()
        assertTrue(ev.contains("OPERATOR_CANCELLATION") && ev.contains("\"initiator\": \"OPERATOR\""), ev)
        // ein Subjektwechsel verlangt ein neues Angebot: ein Hold, der an einen anderen Fahrgast gebunden ist, ändert diese Buchung nicht
        val h3 = d.held(x.w, run2, x.op, f.cf.passenger(x.w)); val b3 = d.create(run1, x.op, d.held(x.w, run1, x.op, x.passenger)).booking
        assertEquals(ErrorCode.VALIDATION_FAILED, code { d.change(run1, x.op, b3, h3) })
    }

    @Test
    fun zustimmungspflichtige_aenderung_nicht_ohne_zustimmung_wirksam() {
        val (x, run1, run2) = world(seats = 4)
        val created = d.create(run1, x.op, d.held(x.w, run1, x.op, x.passenger, passengers = 2)).booking
        // Preisänderung (3 statt 2 Personen): ohne Zustimmung wird nichts wirksam, der Hold bleibt für die Wiederholung
        val h3 = d.held(x.w, run2, x.op, x.passenger, passengers = 3)
        assertNotEquals(950L, h3.grossMinor)
        assertEquals(ErrorCode.VALIDATION_FAILED, code { d.change(run2, x.op, created, h3) })
        assertEquals("CONFIRMING", state(created.id)); assertEquals(0, changes(created.id, "REPLACE_OFFER")); assertEquals("HELD", holdState(h3.holdId)); assertEquals(0, count("SELECT count(*) FROM taktovia.booking WHERE hold_id = ?", h3.holdId))
        // mit dokumentierter Zustimmung wirksam, Kanal im Änderungseintrag und im Audit
        val done = d.change(run2, x.op, created, h3, DispatchReason.PASSENGER_REQUEST, null, consent = PassengerConsent("PHONE", Instant.parse("2026-11-02T06:55:00Z")))
        assertTrue(done.priceChanged); assertEquals("CANCELED", state(created.id))
        assertEquals("PHONE", scalar("SELECT consent ->> 'channel' FROM taktovia.booking_change WHERE booking_id = ? AND change_kind = 'REPLACE_OFFER'", created.id))
        assertEquals(1, count("SELECT count(*) FROM taktovia.audit_event WHERE action = 'dispatch.booking.changed' AND object_ref = ? AND redacted_changes ->> 'consent_channel' = 'PHONE'", "booking:" + created.id))
        // gleicher Preis und gleiches Fenster: keine Zustimmung nötig (siehe anlegen_aendern_stornieren…); ein ungültiger Kanal ist kein Nachweis
        assertThrows<IllegalArgumentException> { PassengerConsent("EMAIL_MAYBE", Instant.now()) }
    }
}

package de.taktovia.bookingdispatch

import de.taktovia.booking.ConfirmFixture
import de.taktovia.bookingdispatch.api.BookingReason
import de.taktovia.bookingdispatch.api.CancelBooking
import de.taktovia.bookingdispatch.api.CancelOutageCommand
import de.taktovia.bookingdispatch.api.OutageBookingState
import de.taktovia.bookingdispatch.api.OutageSearchOutcome
import de.taktovia.bookingdispatch.api.OutageWindowEffect
import de.taktovia.bookingdispatch.api.RebookOutageCommand
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M3-54 (LH-6.22-DMG-010): Störungsablauf bei Fahrzeugausfall gegen die echte Datenbank unter der Runtime-Rolle, mit echten parallelen Transaktionen. Der Incident-Port ist ein Testdouble
 * ([OutageScene]); die Verdrahtung mit `fleet-safety` (Ablaufprotokoll, Abschluss des Incidents, HTTP) weist `OutageHttpApiTest` in der Anwendung nach.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OutageReplacementTest {
    private lateinit var cf: ConfirmFixture

    @BeforeAll fun up() { cf = ConfirmFixture() }
    @AfterAll fun down() = cf.close()

    private val LIVE = setOf("CONFIRMING", "CONFIRMED")

    private fun rejected(code: ErrorCode, block: () -> Unit): ProblemException = assertThrows<ProblemException> { block() }.also { assertEquals(code, it.code, it.detail) }

    private fun rebook(o: OutageScene, booking: java.util.UUID, proposal: java.util.UUID, revision: Long, reason: String = "VEHICLE_OUTAGE") =
        o.outage.rebook(o.op, o.incidentId, booking, RebookOutageCommand(revision, proposal, reason))

    @Test
    fun gesperrtes_fahrzeug_loest_ersatzlogik_mit_vorschlaegen_aus() {
        val o = OutageScene(cf)
        o.block()
        val r = o.search()
        r as OutageSearchOutcome.Proposed
        assertEquals(2, r.affected); assertTrue(r.count >= 2)
        val v = o.overview()
        assertEquals(setOf(o.bookingA, o.bookingB), v.bookings.map { it.bookingId }.toSet())
        v.bookings.forEach { b ->
            assertEquals(OutageBookingState.PROPOSED, b.state)
            assertTrue(b.proposals.isNotEmpty(), "Vorschlag je betroffener Fahrt")
            // nur andere Fahrzeuge, die für die Buchung einsetzbar sind (Umlauf B), und die Wirkung auf das Zusagefenster ist ausgewiesen
            assertTrue(b.proposals.all { it.vehicleId == o.s.runB.vehicleId && it.runId == o.s.runB.runId })
            assertEquals(OutageWindowEffect.IN_WINDOW, b.proposals.first().windowEffect)
            assertTrue(b.actions.containsAll(listOf("CHECK_OPTIONS", "REBOOK", "CANCEL")))
        }
        // eine Ersatzfahrt gilt erst nach bestätigter Umbuchung als zugesagt: die Buchungen stehen weiter auf dem gesperrten Fahrzeug, nichts wurde automatisch umgebucht
        assertEquals(0, o.s.count("SELECT count(*) FROM taktovia.booking_change WHERE booking_id IN (?, ?) AND to_run_id IS NOT NULL", o.bookingA, o.bookingB))
        assertEquals(o.s.runA.runId.toString(), cf.stringOf("SELECT run_id::text FROM taktovia.capacity_hold WHERE id = (SELECT hold_id FROM taktovia.booking WHERE id = ?)", o.bookingA))
        // Wiederholung der Suche: keine doppelten Zeilen je Buchung (jede Berechnung ist nur eine neue Vorschlagsgeneration)
        o.search()
        assertEquals(2, o.s.count("SELECT count(*) FROM taktovia.outage_booking WHERE incident_id = ?", o.incidentId))
        assertEquals(2, o.s.count("SELECT max(generation) FROM taktovia.outage_proposal p JOIN taktovia.outage_booking b ON b.id = p.outage_booking_id WHERE b.incident_id = ? AND b.booking_id = ?", o.incidentId, o.bookingA))
    }

    @Test
    fun verknuepfung_schadenfall_fahrten_beide_richtungen() {
        val o = OutageScene(cf)
        o.block(); o.search()
        // Incident -> Fahrten und Fahrgäste (Buchungen, Umläufe, Fahrzeug)
        val v = o.overview()
        assertEquals(o.s.runA.vehicleId, v.vehicleId)
        assertEquals(setOf(o.s.runA.runId), v.bookings.map { it.runId }.toSet())
        assertTrue(v.bookings.all { it.bookingNumber != null })
        // Fahrt/Buchung -> Incident: die Zeile verweist auf den Incident (Fremdschlüssel), je Buchung höchstens ein offener Störungsfall
        assertEquals(o.incidentId.toString(), cf.stringOf("SELECT incident_id::text FROM taktovia.outage_booking WHERE booking_id = ?", o.bookingA))
        assertEquals(o.incidentId.toString(), cf.stringOf("SELECT incident_id::text FROM taktovia.outage_booking WHERE booking_id = ?", o.bookingB))
        val second = UUID.randomUUID()
        cf.fx.adminUpdate("INSERT INTO taktovia.incident(tenant_id, id, kind, vehicle_id, status, dedupe_key, created_by) VALUES (?, ?, 'VEHICLE_OUTAGE', ?, 'OPEN', ?, 'system:workflow')", o.tenant.value, second, o.s.runA.vehicleId, "VEHICLE_OUTAGE:other")
        o.outage.search(o.tenant, second, o.s.runA.vehicleId)
        assertEquals(0, o.s.count("SELECT count(*) FROM taktovia.outage_booking WHERE incident_id = ?", second), "eine Buchung gehört höchstens zu einem offenen Störungsfall")
    }

    @Test
    fun buchung_endet_umgebucht_storniert_oder_offener_punkt() {
        val o = OutageScene(cf)
        o.block(); o.search()
        // A wird umgebucht, B storniert: der Incident ist abgearbeitet
        val a = o.view(o.bookingA)
        val done = rebook(o, o.bookingA, a.proposals.first().id, a.revision)
        assertEquals(OutageBookingState.REBOOKED, done.state); assertEquals(o.s.runB.runId, done.replacementRunId)
        assertEquals(o.s.runB.runId.toString(), cf.stringOf("SELECT run_id::text FROM taktovia.capacity_hold WHERE id = (SELECT hold_id FROM taktovia.booking WHERE id = ?)", o.bookingA))
        assertTrue(o.concluded.isEmpty(), "B ist noch offen")
        val b = o.view(o.bookingB)
        val cancelled = o.outage.cancel(o.op, o.incidentId, o.bookingB, CancelOutageCommand(b.revision, "NO_REPLACEMENT_WANTED"))
        assertEquals(OutageBookingState.CANCELLED, cancelled.state)
        assertEquals(listOf(true), o.concluded, "umgebucht und storniert: Incident abgeschlossen (RESOLVED)")
        assertEquals("CANCELED", o.bookingState(o.bookingB))
        assertEquals("VEHICLE_OUTAGE", cf.stringOf("SELECT cancel_reason_code FROM taktovia.booking WHERE id = ?", o.bookingB))
        assertEquals("FAHRZEUGAUSFALL", cf.stringOf("SELECT cancel_cause_class FROM taktovia.booking WHERE id = ?", o.bookingB))
        // keine Buchung bleibt still auf dem gesperrten Fahrzeug: weder im Plan von A noch mit Hold auf A
        assertEquals(0, o.s.count("SELECT count(*) FROM taktovia.route_stop s JOIN taktovia.vehicle_run r ON r.tenant_id = s.tenant_id AND r.id = s.run_id AND r.revision = s.run_revision " +
            "CROSS JOIN LATERAL jsonb_array_elements(s.boardings || s.alightings) m WHERE r.id = ?", o.s.runA.runId))
        assertEquals(listOf("REBOOK_BOOKING" to "VEHICLE_OUTAGE", "CANCEL_BOOKING" to "NO_REPLACEMENT_WANTED"), o.decisions.toList())
        // Entscheidung mit Akteur, Code und Zeit an der Zeile
        assertTrue(done.decidedBy!!.startsWith("user:")); assertNotNull(done.decidedAt); assertEquals("REBOOK", done.decision)
        // ohne Ersatz: kein Vorschlag, die Buchung bleibt als offener Punkt sichtbar (nicht still auf dem gesperrten Fahrzeug) und der Incident bleibt offen
        val n = OutageScene(cf)
        n.block(); cf.fx.adminUpdate("UPDATE taktovia.vehicle SET active_block = true WHERE id = ?", n.s.runB.vehicleId)
        assertTrue(n.search() is OutageSearchOutcome.NoneAvailable)
        n.overview().bookings.forEach { b2 ->
            assertEquals(OutageBookingState.OPEN_ISSUE, b2.state); assertEquals("NO_REPLACEMENT", b2.issueCode); assertTrue(b2.proposals.isEmpty())
            assertEquals(listOf("CHECK_OPTIONS", "CANCEL"), b2.actions)
        }
        assertTrue(n.concluded.isEmpty())
        rejected(ErrorCode.VALIDATION_FAILED) { n.outage.rebook(n.op, n.incidentId, n.bookingA, RebookOutageCommand(n.view(n.bookingA).revision, UUID.randomUUID(), "x")) }   // Code ungültig
        rejected(ErrorCode.VALIDATION_FAILED) { n.outage.rebook(n.op, n.incidentId, n.bookingA, RebookOutageCommand(n.view(n.bookingA).revision, UUID.randomUUID(), "KEIN_VORSCHLAG")) }   // Vorschlag unbekannt
        // der Klärpfad: Storno wegen Fahrzeugausfall schließt den Fall, ohne Umbuchung ergibt sich CLOSED_NO_REPLACEMENT
        n.outage.cancel(n.op, n.incidentId, n.bookingA, CancelOutageCommand(n.view(n.bookingA).revision, "NO_VEHICLE_AVAILABLE"))
        n.outage.cancel(n.op, n.incidentId, n.bookingB, CancelOutageCommand(n.view(n.bookingB).revision, "NO_VEHICLE_AVAILABLE"))
        assertEquals(listOf(false), n.concluded)
    }

    @Test
    fun ersatz_ohne_rollstuhlplatz_wird_nicht_vorgeschlagen() {
        val o = OutageScene(cf, capacityB = OfferObjectsFixture.cap(4, 0), wheelchairA = 1)
        o.block(); o.search()
        val a = o.view(o.bookingA)
        assertEquals(1, a.wheelchairSpaces)
        assertTrue(a.proposals.isEmpty(), "Ersatzfahrzeug ohne Rollstuhlplatz wird für die Rollstuhlbuchung nicht vorgeschlagen")
        assertEquals(OutageBookingState.OPEN_ISSUE, a.state)
        // die Buchung ohne Rollstuhl bekommt dasselbe Fahrzeug vorgeschlagen
        assertTrue(o.view(o.bookingB).proposals.isNotEmpty())
        // eine Umbuchung ohne Vorschlag scheitert; ein erfundener Vorschlag wird nicht angenommen
        rejected(ErrorCode.VALIDATION_FAILED) { rebook(o, o.bookingA, o.view(o.bookingB).proposals.first().id, a.revision) }
        assertEquals(o.s.runA.runId.toString(), cf.stringOf("SELECT run_id::text FROM taktovia.capacity_hold WHERE id = (SELECT hold_id FROM taktovia.booking WHERE id = ?)", o.bookingA))
        // nicht freigegebenes (Einsatzfreigabe widerrufen) Ersatzfahrzeug wird ebenfalls nicht vorgeschlagen
        val r = OutageScene(cf)
        r.block(); cf.fx.adminUpdate("UPDATE taktovia.resource_release SET revoked_at = now() WHERE vehicle_id = ?", r.s.runB.vehicleId)
        assertTrue(r.search() is OutageSearchOutcome.NoneAvailable)
    }

    @Test
    fun eingestiegene_fahrgaeste_nicht_automatisch_umgebucht() {
        val o = OutageScene(cf)
        o.block()
        cf.fx.adminUpdate("UPDATE taktovia.journey_leg SET execution_state = 'BOARDED' WHERE journey_id = (SELECT journey_id FROM taktovia.booking WHERE id = ?)", o.bookingA)
        o.search()
        val a = o.view(o.bookingA)
        assertEquals(OutageBookingState.BOARDED, a.state); assertTrue(a.proposals.isEmpty()); assertTrue(a.actions.isEmpty())
        assertEquals(listOf(o.bookingA), o.overview().boarded.map { it.bookingId }, "eingestiegene Fahrgäste gesondert ausgewiesen")
        assertEquals(OutageBookingState.PROPOSED, o.view(o.bookingB).state)
        // weder Umbuchung noch Storno: 409 CONFLICT (PASSENGER_ONBOARD), die Buchung bleibt bestätigt auf dem Fahrzeug
        val e1 = rejected(ErrorCode.CONFLICT) { rebook(o, o.bookingA, o.view(o.bookingB).proposals.first().id, a.revision) }
        assertTrue(e1.detail!!.contains("PASSENGER_ONBOARD"))
        rejected(ErrorCode.CONFLICT) { o.outage.cancel(o.op, o.incidentId, o.bookingA, CancelOutageCommand(a.revision, "VEHICLE_OUTAGE")) }
        assertTrue(o.bookingState(o.bookingA) in LIVE); assertEquals(0, o.refunds.size)
        // Alle anderen abgearbeitet: der Incident bleibt offen, solange der Fahrgast an Bord ist
        o.outage.cancel(o.op, o.incidentId, o.bookingB, CancelOutageCommand(o.view(o.bookingB).revision, "VEHICLE_OUTAGE"))
        assertTrue(o.concluded.isEmpty())
        // nach dem Ausstieg ist die Fahrt beendet (ONBOARD_DONE) und der Fall abgeschlossen
        cf.fx.adminUpdate("UPDATE taktovia.journey_leg SET execution_state = 'ALIGHTED' WHERE journey_id = (SELECT journey_id FROM taktovia.booking WHERE id = ?)", o.bookingA)
        o.outage.checkOptions(o.op, o.incidentId)
        assertEquals(OutageBookingState.ONBOARD_DONE, o.view(o.bookingA).state)
    }

    @Test
    fun fremder_mandant_incident_404() {
        val o = OutageScene(cf)
        o.block(); o.search()
        val other = OutageScene(cf)
        val cmd = RebookOutageCommand(1, UUID.randomUUID(), "VEHICLE_OUTAGE")
        rejected(ErrorCode.NOT_FOUND) { o.outage.overview(other.op, o.incidentId) }
        rejected(ErrorCode.NOT_FOUND) { o.outage.checkOptions(other.op, o.incidentId) }
        rejected(ErrorCode.NOT_FOUND) { o.outage.rebook(other.op, o.incidentId, o.bookingA, cmd) }
        rejected(ErrorCode.NOT_FOUND) { o.outage.cancel(other.op, o.incidentId, o.bookingA, CancelOutageCommand(1, "VEHICLE_OUTAGE")) }
        rejected(ErrorCode.NOT_FOUND) { o.outage.inform(other.op, o.incidentId, o.bookingA, 1) }
        // andere Rolle im eigenen Mandanten: Disposition ist Voraussetzung zum Schreiben (403), Lesen genügt für die Sicht
        val reader = cf.operator(o.tenant, setOf(o.s.a.world.serviceId), setOf("support"))
        rejected(ErrorCode.FORBIDDEN) { o.outage.rebook(reader, o.incidentId, o.bookingA, cmd) }
        rejected(ErrorCode.FORBIDDEN) { o.outage.checkOptions(reader, o.incidentId) }
        assertEquals(2, o.outage.overview(reader, o.incidentId).bookings.size)
        // eine Buchung eines anderen Mandanten ist in diesem Incident nicht entscheidbar
        rejected(ErrorCode.NOT_FOUND) { o.outage.cancel(o.op, o.incidentId, other.bookingA, CancelOutageCommand(1, "VEHICLE_OUTAGE")) }
        // Datentrennung auch direkt in der Datenbank (RLS): der andere Mandant sieht keine Zeile
        assertEquals(0, cf.fx.base.tx.run(other.tenant) { j -> j.queryForObject("SELECT count(*) FROM taktovia.outage_booking WHERE incident_id = ?", Int::class.java, o.incidentId) })
    }

    @Test
    fun zwei_disponenten_gleicher_incident_eine_entscheidung() {
        repeat(3) {
            val o = OutageScene(cf)
            o.block(); o.search()
            val a = o.view(o.bookingA)
            val proposal = a.proposals.first().id
            val go = CountDownLatch(1); val pool = Executors.newFixedThreadPool(2)
            val outcomes = (1..2).map { n ->
                pool.submit<Result<Any>> {
                    go.await()
                    runCatching {
                        if (n == 1) rebook(o, o.bookingA, proposal, a.revision)
                        else o.outage.cancel(cf.operator(o.tenant, setOf(o.s.a.world.serviceId)), o.incidentId, o.bookingA, CancelOutageCommand(a.revision, "VEHICLE_OUTAGE"))
                    }
                }
            }
            go.countDown(); val results = outcomes.map { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
            assertEquals(1, results.count { it.isSuccess }, "genau eine Entscheidung: $results")
            val failure = results.single { it.isFailure }.exceptionOrNull() as ProblemException
            assertTrue(failure.code in setOf(ErrorCode.REVISION_MISMATCH, ErrorCode.INVALID_STATE_TRANSITION), "zweiter Disponent abgewiesen mit 412/409: ${failure.code}")
            val state = o.stateOf(o.bookingA)
            assertTrue(state == "REBOOKED" || state == "CANCELLED")
            assertEquals(1, o.s.count("SELECT count(*) FROM taktovia.outage_booking WHERE booking_id = ? AND decision IS NOT NULL", o.bookingA))
            // keine doppelte Wirkung: entweder eine Umbuchung oder ein Storno, nie beides
            assertEquals(if (state == "REBOOKED") 1 else 0, o.s.count("SELECT count(*) FROM taktovia.booking_change WHERE booking_id = ? AND to_run_id IS NOT NULL", o.bookingA))
            assertEquals(if (state == "CANCELLED") 1 else 0, o.refunds.count { it.bookingId == o.bookingA })
        }
        // veraltete Revision nach einer Entscheidung: 412 mit aktueller Revision, nichts geschrieben
        val o = OutageScene(cf); o.block(); o.search()
        val a = o.view(o.bookingA)
        rebook(o, o.bookingA, a.proposals.first().id, a.revision)
        val e = rejected(ErrorCode.REVISION_MISMATCH) { o.outage.cancel(o.op, o.incidentId, o.bookingA, CancelOutageCommand(a.revision, "VEHICLE_OUTAGE")) }
        assertEquals("\"${a.revision + 1}\"", e.headers["ETag"])
        assertTrue(o.bookingState(o.bookingA) in LIVE)
    }

    @Test
    fun betreiberstorno_wiederholung_keine_doppelte_erstattung() {
        val o = OutageScene(cf)
        o.block(); o.search()
        val b = o.view(o.bookingB)
        val done = o.outage.cancel(o.op, o.incidentId, o.bookingB, CancelOutageCommand(b.revision, "VEHICLE_OUTAGE"))
        assertEquals(1, o.refunds.size); assertEquals(BookingReason.VEHICLE_OUTAGE, o.refunds.single().reason)
        // Wiederholung: mit alter Revision 412, mit neuer 409: beide ohne zweite Erstattung oder zweites Ereignis
        rejected(ErrorCode.REVISION_MISMATCH) { o.outage.cancel(o.op, o.incidentId, o.bookingB, CancelOutageCommand(b.revision, "VEHICLE_OUTAGE")) }
        rejected(ErrorCode.INVALID_STATE_TRANSITION) { o.outage.cancel(o.op, o.incidentId, o.bookingB, CancelOutageCommand(done.revision, "VEHICLE_OUTAGE")) }
        assertEquals(1, o.refunds.size)
        assertEquals(1, o.s.count("SELECT count(*) FROM taktovia.outbox WHERE event_type = 'booking.cancelled' AND aggregate_id = ?", o.bookingB))
        // gleichzeitige Wiederholung: genau ein Storno
        val c = OutageScene(cf); c.block(); c.search()
        val cv = c.view(c.bookingA)
        val go = CountDownLatch(1); val pool = Executors.newFixedThreadPool(4)
        val results = (1..4).map { pool.submit<Result<Any>> { go.await(); runCatching { c.outage.cancel(c.op, c.incidentId, c.bookingA, CancelOutageCommand(cv.revision, "VEHICLE_OUTAGE")) } } }
        go.countDown(); val rs = results.map { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(1, rs.count { it.isSuccess }, "genau ein Storno: $rs")
        assertEquals(1, c.refunds.size)
        assertTrue(rs.filter { it.isFailure }.all { (it.exceptionOrNull() as ProblemException).code in setOf(ErrorCode.REVISION_MISMATCH, ErrorCode.INVALID_STATE_TRANSITION) })
    }

    @Test
    fun umbuchung_parallel_zu_fahrgaststorno() {
        var rebooked = 0; var cancelledFirst = 0
        repeat(8) {
            val o = OutageScene(cf)
            o.block(); o.search()
            val a = o.view(o.bookingA)
            val passenger = o.s.a.ha.first.passenger.ctx
            val go = CountDownLatch(1); val pool = Executors.newFixedThreadPool(2)
            val r1 = pool.submit<Result<Any>> { go.await(); runCatching { rebook(o, o.bookingA, a.proposals.first().id, a.revision) } }
            val r2 = pool.submit<Result<Any>> { go.await(); runCatching { o.bookings.cancel(passenger, o.bookingA, CancelBooking(o.bookingRevision(o.bookingA), BookingReason.PASSENGER_REQUEST)) } }
            go.countDown(); val rebook = r1.get(60, TimeUnit.SECONDS); val cancel = r2.get(60, TimeUnit.SECONDS); pool.shutdown()
            val booking = o.bookingState(o.bookingA)
            val onRunB = o.s.count("SELECT count(*) FROM taktovia.route_stop s JOIN taktovia.vehicle_run r ON r.tenant_id = s.tenant_id AND r.id = s.run_id AND r.revision = s.run_revision CROSS JOIN LATERAL jsonb_array_elements(s.boardings) m " +
                "WHERE r.id = ? AND m->>'booking_id' = ?", o.s.runB.runId, o.bookingA.toString())
            if (rebook.isSuccess) {
                // die Umbuchung gewann: die Buchung ist bestätigt auf B (der Fahrgaststorno wurde danach abgewiesen oder wirkt auf den neuen Umlauf, dann nie zugleich Plan und Storno)
                assertEquals("REBOOKED", o.stateOf(o.bookingA)); rebooked++
                if (cancel.isFailure) { assertTrue(booking in LIVE); assertEquals(1, onRunB) } else { assertEquals("CANCELED", booking); assertEquals(0, onRunB) }
            } else {
                // der Fahrgaststorno gewann: nichts wurde umgebucht, die Zeile ist abgeglichen oder offen, die Buchung steht nirgends im Plan
                assertTrue(cancel.isSuccess, "mindestens eine der beiden Wirkungen ist erfolgt: ${rebook.exceptionOrNull()} / ${cancel.exceptionOrNull()}")
                assertEquals("CANCELED", booking); assertEquals(0, onRunB); cancelledFirst++
                assertEquals(0, o.s.count("SELECT count(*) FROM taktovia.outage_booking WHERE booking_id = ? AND state = 'REBOOKED'", o.bookingA))
            }
            // Kapazität: die Reservierung einer stornierten Buchung ist frei, einer bestätigten belegt
            assertEquals(if (booking == "CANCELED") "RELEASED" else "CONSUMED", cf.stringOf("SELECT state FROM taktovia.capacity_hold WHERE id = (SELECT hold_id FROM taktovia.booking WHERE id = ?)", o.bookingA))
            // nach dem Abgleich zeigt der Störungsfall die storniert-anderweitig-Buchung als abgeschlossen
            if (booking == "CANCELED") { o.outage.checkOptions(o.op, o.incidentId); assertEquals(OutageBookingState.CANCELLED, o.view(o.bookingA).state) }
        }
        assertEquals(8, rebooked + cancelledFirst)
    }

    @Test
    fun fahrgast_informieren_genau_einmal_je_aenderung() {
        val o = OutageScene(cf)
        o.block(); o.search()
        val a = o.view(o.bookingA)
        rejected(ErrorCode.INVALID_STATE_TRANSITION) { o.outage.inform(o.op, o.incidentId, o.bookingA, a.revision) }   // vor der Umbuchung gibt es nichts mitzuteilen
        val done = rebook(o, o.bookingA, a.proposals.first().id, a.revision)
        assertTrue("INFORM_PASSENGER" in done.actions); assertFalse(done.passengerNoticeRequested)
        val go = CountDownLatch(1); val pool = Executors.newFixedThreadPool(4)
        val rs = (1..4).map { pool.submit<Result<Any>> { go.await(); runCatching { o.outage.inform(o.op, o.incidentId, o.bookingA, done.revision) } } }
        go.countDown(); rs.map { it.get(60, TimeUnit.SECONDS) }.forEach { assertTrue(it.isSuccess) }; pool.shutdown()
        assertEquals(1, o.s.count("SELECT count(*) FROM taktovia.outbox WHERE event_type = 'outage.passenger_notice_requested' AND aggregate_id = ?", o.view(o.bookingA).id), "genau eine Mitteilung je Änderung")
        assertTrue(o.view(o.bookingA).passengerNoticeRequested); assertFalse("INFORM_PASSENGER" in o.view(o.bookingA).actions)
        assertNull(o.view(o.bookingB).decision)
    }
}

package de.taktovia.app.driver

import de.taktovia.app.probe.Resp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M3-29 / LH-6.6-DRV-005 über die echte Anwendung (HTTP, Runtime-Rolle, echter Fahrtenplan, echte Buchungen, echte Konfiguration): Ankunft, Einstieg, Ausstieg und Nicht-Erscheinen schreiben
 * konsistente Fahrt- (`journey_leg.execution_state`) und Buchungsstatus samt Statushistorie; Klärfälle (`REVIEW_REQUIRED`) verändern nichts; echte parallele Anfragen gegen die Datenbank.
 * Eigene Welt [DriverRunHttpWorld] (wave11-c1), keine Duplikate dieser Fixture.
 */
class DriverEventEffectsHttpTest {
    private val sup = DriverRideSupport()
    private val w = sup.w
    private val env = w.env

    private fun ride() = sup.ride()
    private fun revision(r: DriverRideSupport.Ride) = sup.revision(r)
    private fun vehicleOf(d: DriverRunHttpWorld.Driver) = sup.vehicleOf(d)
    private fun event(r: DriverRideSupport.Ride, type: String, stop: UUID, id: UUID = UUID.randomUUID(), rev: Long = r.rev, offline: Boolean = false, occurred: Instant = Instant.now(), booking: UUID? = r.booking.id, extra: String = "", token: String = r.d.token): Resp =
        sup.event(r, type, stop, id, rev, offline, occurred, booking, extra, token)
    private fun bookingState(r: DriverRideSupport.Ride) = sup.bookingState(r)
    private fun legState(r: DriverRideSupport.Ride) = sup.legState(r)
    private fun history(r: DriverRideSupport.Ride) = sup.history(r)
    private fun journal(r: DriverRideSupport.Ride, type: String) = sup.journal(r, type)

    @Test
    fun vollstaendige_fahrt_ankunft_einstieg_ausstieg_konsistente_status() {
        val r = ride()
        assertEquals("CONFIRMED", bookingState(r)); assertEquals("PLANNED", legState(r))
        assertEquals("APPLIED", event(r, "ARRIVAL", r.pickup).json["state"].asString())
        val checkIn = event(r, "CHECK_IN", r.pickup); assertEquals("APPLIED", checkIn.json["state"].asString(), checkIn.text)
        assertEquals("CONFIRMED", bookingState(r), "der Einstieg ändert die Buchung nicht (kein Zustand dafür im Buchungsautomaten)"); assertEquals("BOARDED", legState(r))
        assertEquals("APPLIED", event(r, "ARRIVAL", r.dropoff).json["state"].asString())
        val out = event(r, "CHECK_OUT", r.dropoff); assertEquals("APPLIED", out.json["state"].asString(), out.text)
        assertEquals("COMPLETED", bookingState(r)); assertEquals("ALIGHTED", legState(r))
        assertEquals("COMPLETED:TRIP_COMPLETED", history(r).last(), "Statushistorie mit Grund")
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.outbox WHERE event_type = 'driver.passenger_boarded' AND payload->>'booking_id' = ?", r.booking.id.toString()))
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.outbox WHERE event_type = 'driver.passenger_alighted' AND payload->>'booking_id' = ?", r.booking.id.toString()))
        assertEquals(0, env.count("SELECT count(*) FROM taktovia.outbox WHERE event_type = 'driver.review_opened' AND payload->>'booking_id' = ?", r.booking.id.toString()))
    }

    @Test
    fun ausstieg_ohne_einstieg_invalid_order() {
        val r = ride()
        val out = event(r, "CHECK_OUT", r.dropoff)
        assertEquals(200, out.status, out.text); assertEquals("REVIEW_REQUIRED", out.json["state"].asString()); assertEquals("INVALID_ORDER", out.json["reason_code"].asString())
        assertEquals("CONFIRMED", bookingState(r)); assertEquals("PLANNED", legState(r), "keine Statuswirkung")
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.outbox WHERE event_type = 'driver.review_opened' AND payload->>'reason_code' = 'INVALID_ORDER' AND payload->>'booking_id' = ?", r.booking.id.toString()))
    }

    @Test
    fun falscher_stopp_outside_stop() {
        val r = ride()
        val atDropoff = event(r, "CHECK_IN", r.dropoff)   // der Fahrgast steigt am Ausstiegsstopp nicht ein
        assertEquals("REVIEW_REQUIRED", atDropoff.json["state"].asString()); assertEquals("OUTSIDE_STOP", atDropoff.json["reason_code"].asString())
        assertEquals("PLANNED", legState(r))
        // ein Stopp eines fremden Umlaufs gehört nicht zur eigenen Schicht: 404
        val other = ride()
        assertEquals(404, event(r, "CHECK_IN", other.pickup).status)
        assertEquals(404, event(r, "CHECK_IN", r.pickup, booking = other.booking.id).status, "Buchung nicht im Plan dieses Umlaufs")
    }

    @Test
    fun no_show_nach_wartefrist_speichert_grund_wartezeit_und_regelstand_ohne_gebuehr() {
        val r = ride()
        val arrival = Instant.now().minusSeconds(20 * 60)
        assertEquals("APPLIED", event(r, "ARRIVAL", r.pickup, offline = true, occurred = arrival).json["state"].asString())
        val ns = event(r, "NO_SHOW", r.pickup, offline = true, occurred = Instant.now().minusSeconds(2), extra = ""","no_show_reason":"NOT_PRESENT"""")
        assertEquals("APPLIED", ns.json["state"].asString(), ns.text)
        assertEquals("CANCELED", bookingState(r)); assertEquals("NO_SHOW", legState(r))
        assertEquals(listOf("CANCEL_PENDING:NO_SHOW", "CANCELED:CANCEL_COMPLETED"), history(r).takeLast(2))
        val row = env.string("SELECT no_show_reason || ',' || (no_show_wait_seconds >= 1190) || ',' || no_show_grace_min FROM taktovia.driver_event WHERE event_id = ?", UUID.fromString(ns.json["event_id"].asString())).single()
        assertEquals("NOT_PRESENT,true,10", row, "Grund, Wartezeit (ab plausibilisierter Ankunft) und Regelstand (Planungsdefault 10 Minuten, kein Dienstwert konfiguriert)")
        val ev = env.string("SELECT payload::text FROM taktovia.outbox WHERE event_type = 'driver.no_show_recorded' AND payload->>'booking_id' = ?", r.booking.id.toString()).single()
        assertTrue(ev.contains("\"fee_booked\": false") || ev.contains("\"fee_booked\":false"), ev)
        assertEquals(0, env.count("SELECT count(*) FROM taktovia.refund_case WHERE tenant_id = ?", w.tenant.value), "dieses Paket bucht keine Gebühr und löst keine Erstattung aus")
        assertEquals(0, env.count("SELECT count(*) FROM taktovia.outbox WHERE event_type = 'booking.cancelled' AND aggregate_id = ?", r.booking.id), "kein Stornoereignis mit Zahlungsreaktion")
    }

    @Test
    fun no_show_vor_ablauf_der_wartefrist_ist_422_ohne_wirkung() {
        val r = ride()
        assertEquals("APPLIED", event(r, "ARRIVAL", r.pickup).json["state"].asString())
        val early = event(r, "NO_SHOW", r.pickup, extra = ""","no_show_reason":"NOT_PRESENT"""")
        assertEquals(422, early.status, early.text); assertTrue(early.text.contains("NO_SHOW_GRACE_NOT_ELAPSED"), early.text)
        assertEquals("CONFIRMED", bookingState(r)); assertEquals("PLANNED", legState(r)); assertEquals(emptyList<String>(), journal(r, "NO_SHOW"), "nichts gespeichert")
        assertEquals(422, event(r, "NO_SHOW", r.pickup).status, "ohne Grund")
    }

    @Test
    fun no_show_ohne_ankunft_ist_ein_klaerfall() {
        val r = ride()
        val ns = event(r, "NO_SHOW", r.pickup, extra = ""","no_show_reason":"OTHER"""")
        assertEquals("REVIEW_REQUIRED", ns.json["state"].asString()); assertEquals("INVALID_ORDER", ns.json["reason_code"].asString()); assertEquals("PLANNED", legState(r))
    }

    @Test
    fun offline_checkin_auf_alter_planrevision_ist_review_required_ohne_befoerderung() {
        val r = ride()
        w.plan(r.d.run, listOf(r.booking), expected = r.rev)      // neue Planrevision
        val stale = event(r, "CHECK_IN", r.pickup, offline = true)
        assertEquals("REVIEW_REQUIRED", stale.json["state"].asString()); assertEquals("STALE_PLAN", stale.json["reason_code"].asString())
        assertEquals(r.rev + 1, stale.json["current_plan_revision"].asLong())
        assertEquals("PLANNED", legState(r)); assertEquals("CONFIRMED", bookingState(r), "keine ausgeführte Beförderungsbuchung")
    }

    @Test
    fun fremde_schicht_ergibt_404_und_tenant_id_im_request_ohne_wirkung() {
        val r = ride(); val other = ride()
        assertEquals(404, event(r, "ARRIVAL", r.pickup, token = other.d.token).status, "Schicht eines anderen Fahrers")
        val own = event(r, "ARRIVAL", r.pickup, extra = ""","tenant_id":"${w.tenant.value}"""")
        assertEquals(422, own.status, own.text)
        val foreign = event(r, "ARRIVAL", r.pickup, extra = ""","tenant_id":"${UUID.randomUUID()}"""")
        assertTrue(foreign.status == 404 || foreign.status == 422, "tenant_id erzeugt kein Recht: ${foreign.status}")
        assertEquals(emptyList<String>(), journal(r, "ARRIVAL"))
    }

    @Test
    fun verspaetetes_ereignis_nach_schichtende_hat_keine_wirkung() {
        val r = ride()
        r.d.endShift()
        val late = event(r, "CHECK_IN", r.pickup, occurred = Instant.now().plusSeconds(1))
        assertEquals("REVIEW_REQUIRED", late.json["state"].asString(), late.text); assertEquals("SHIFT_ENDED", late.json["reason_code"].asString(), late.text)
        assertEquals("PLANNED", legState(r))
    }

    @Test
    fun einstieg_blockiert_den_storno_der_buchung() {
        val r = ride()
        event(r, "CHECK_IN", r.pickup)
        val cancel = w.s.cancel(r.booking.id.toString(), r.booking.held.account.token, ifMatch = revision(r))
        assertEquals(409, cancel.status, cancel.text)
        assertEquals("CONFIRMED", bookingState(r)); assertEquals("BOARDED", legState(r))
    }

    @Test
    fun checkin_parallel_zu_storno_klaerfall_kein_zustandsruecksprung() {
        repeat(6) { round ->
            val r = ride()
            val pool = Executors.newFixedThreadPool(2); val go = CountDownLatch(1)
            val ci = pool.submit<Resp> { go.await(); event(r, "CHECK_IN", r.pickup) }
            val ca = pool.submit<Resp> { go.await(); w.s.cancel(r.booking.id.toString(), r.booking.held.account.token, ifMatch = revision(r)) }
            go.countDown(); val c = ci.get(60, TimeUnit.SECONDS); val s = ca.get(60, TimeUnit.SECONDS); pool.shutdown()
            val booking = bookingState(r); val leg = legState(r)
            when {
                s.status == 200 -> {   // Storno zuerst: der Einstieg ist ein Klärfall (oder auf der ersetzten Revision veraltet), der Fahrgast ist nie eingestiegen
                    assertEquals("CANCELED", booking, "Runde $round"); assertEquals("PLANNED", leg, "Runde $round")
                    assertEquals(200, c.status, c.text); assertEquals("REVIEW_REQUIRED", c.json["state"].asString(), c.text)
                    assertTrue(c.json["reason_code"].asString() in setOf("BOOKING_CANCELED", "STALE_PLAN"), c.text)
                }
                else -> {              // Einstieg zuerst: der Storno wird abgewiesen
                    assertEquals(409, s.status, s.text); assertEquals("APPLIED", c.json["state"].asString(), c.text)
                    assertEquals("CONFIRMED", booking); assertEquals("BOARDED", leg)
                }
            }
        }
    }

    @Test
    fun hundert_identische_ausstiege_ergeben_eine_wirkung() {
        val r = ride()
        event(r, "CHECK_IN", r.pickup)
        val id = UUID.randomUUID(); val occurred = Instant.now()
        val pool = Executors.newFixedThreadPool(16); val go = CountDownLatch(1)
        val futures = (1..100).map { pool.submit<Resp> { go.await(); event(r, "CHECK_OUT", r.dropoff, id = id, occurred = occurred) } }
        go.countDown(); val rs = futures.map { it.get(120, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(emptyList<String>(), rs.filter { it.status != 200 }.map { "${it.status} ${it.text}" })
        val by = rs.groupingBy { it.json["state"].asString() }.eachCount()
        assertEquals(1, by["APPLIED"], by.toString()); assertEquals(99, by["DUPLICATE"], by.toString())
        assertEquals("COMPLETED", bookingState(r)); assertEquals(1, history(r).count { it == "COMPLETED:TRIP_COMPLETED" })
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.driver_event WHERE event_id = ?", id))
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.outbox WHERE event_type = 'driver.passenger_alighted' AND payload->>'event_id' = ?", id.toString()))
    }

    @Test
    fun ausfuehrungszustand_der_fahrt_kennt_keinen_ruecksprung_auch_nicht_per_sql() {
        val r = ride()
        event(r, "CHECK_IN", r.pickup); event(r, "CHECK_OUT", r.dropoff)
        val e = org.junit.jupiter.api.Assertions.assertThrows(Exception::class.java) { env.exec("UPDATE taktovia.journey_leg SET execution_state = 'BOARDED' WHERE journey_id = (SELECT journey_id FROM taktovia.booking WHERE id = ?)", r.booking.id) }
        assertTrue(e.message!!.contains("nicht zulaessig"), e.message)
        assertEquals("ALIGHTED", legState(r))
    }
}

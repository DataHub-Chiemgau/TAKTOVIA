package de.taktovia.booking

import de.taktovia.bookingdispatch.RideOfferFixture
import de.taktovia.bookingdispatch.api.AttemptOutcome
import de.taktovia.bookingdispatch.api.RejectionCode
import de.taktovia.bookingdispatch.api.RideBookingKind
import de.taktovia.bookingdispatch.api.RideTimeKind
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.ViolationCode
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * WP-M2-11 / LH-6.4-BKG-001: Sofort-/Vorausbuchung mit Abfahrt- oder Ankunftswunsch gegen die echte Datenbank (Runtime-Rolle). Jetzt ist Montag, 2. November 2026,
 * 07:00Z (08:00 MEZ); Direktfahrzeit 600 s, `pickup_window_min` 15, `min_booking_lead_min` 30, `max_booking_horizon_days` 14, `arrival_grace_min` 5.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RideOfferRequestTest {
    private lateinit var fx: RideOfferFixture
    private lateinit var w: RideOfferFixture.World
    private fun at(s: String) = Instant.parse(s)

    @BeforeAll fun up() { fx = RideOfferFixture(); w = fx.world() }
    @AfterAll fun down() = fx.close()

    @Test
    fun vier_kombinationen_ergeben_zeitfenster() {
        val api = fx.requests(); val ctx = fx.passenger(w.tenant)
        // Sofort x Abfahrt: Wunsch = jetzt
        val a = api.request(ctx, fx.command(w, "2026-11-02T08:00:00+01:00", RideTimeKind.DEPARTURE))
        assertEquals(AttemptOutcome.OFFERED, a.outcome); assertEquals(RideBookingKind.IMMEDIATE, a.bookingKind)
        a.offers.single().let {
            assertEquals(at("2026-11-02T07:00:00Z"), it.pickupWindow.earliestAt); assertEquals(at("2026-11-02T07:15:00Z"), it.pickupWindow.latestAt)
            assertEquals(at("2026-11-02T07:10:00Z"), it.arrivalWindow.earliestAt); assertEquals(at("2026-11-02T07:25:00Z"), it.arrivalWindow.latestAt)
            assertEquals(5, it.arrivalGraceMin); assertEquals(RideTimeKind.DEPARTURE, it.timeKind)
        }
        // Voraus x Abfahrt
        val b = api.request(ctx, fx.command(w, "2026-11-02T10:00:00+01:00", RideTimeKind.DEPARTURE))
        assertEquals(RideBookingKind.ADVANCE, b.bookingKind)
        b.offers.single().let {
            assertEquals(at("2026-11-02T09:00:00Z"), it.pickupWindow.earliestAt); assertEquals(at("2026-11-02T09:15:00Z"), it.pickupWindow.latestAt)
            assertEquals(at("2026-11-02T09:10:00Z"), it.arrivalWindow.earliestAt); assertEquals(at("2026-11-02T09:25:00Z"), it.arrivalWindow.latestAt)
        }
        // Sofort x Ankunft: Ankunft in 20 Minuten, Abholung beginnt sofort (kein Vorlauf), der frühe Rand wird auf jetzt angehoben
        val c = api.request(ctx, fx.command(w, "2026-11-02T08:20:00+01:00", RideTimeKind.ARRIVAL))
        assertEquals(RideBookingKind.IMMEDIATE, c.bookingKind)
        c.offers.single().let {
            assertEquals(at("2026-11-02T07:00:00Z"), it.pickupWindow.earliestAt); assertEquals(at("2026-11-02T07:10:00Z"), it.pickupWindow.latestAt)
            assertEquals(at("2026-11-02T07:20:00Z"), it.arrivalWindow.latestAt); assertEquals(RideTimeKind.ARRIVAL, it.timeKind)
        }
        // Voraus x Ankunft: Ankunft 12:00 Ortszeit
        val d = api.request(ctx, fx.command(w, "2026-11-02T12:00:00+01:00", RideTimeKind.ARRIVAL))
        assertEquals(RideBookingKind.ADVANCE, d.bookingKind)
        d.offers.single().let {
            assertEquals(at("2026-11-02T10:45:00Z"), it.arrivalWindow.earliestAt); assertEquals(at("2026-11-02T11:00:00Z"), it.arrivalWindow.latestAt)
            assertEquals(at("2026-11-02T10:35:00Z"), it.pickupWindow.earliestAt); assertEquals(at("2026-11-02T10:50:00Z"), it.pickupWindow.latestAt)
        }
        for (r in listOf(a, b, c, d)) r.offers.single().let { o ->
            assertTrue(o.pickupWindow.latestAt > o.pickupWindow.earliestAt && o.arrivalWindow.latestAt > o.arrivalWindow.earliestAt, "nichtleere Fenster")
            assertTrue(o.arrivalWindow.earliestAt >= o.pickupWindow.earliestAt, "Ankunft nicht vor Abholung")
        }
    }

    @Test
    fun vorlauf_horizont_dst_ablehnung() {
        val api = fx.requests(); val ctx = fx.passenger(w.tenant)
        fun primary(desired: String, kind: RideTimeKind = RideTimeKind.DEPARTURE) = api.request(ctx, fx.command(w, desired, kind)).also {
            if (it.outcome == AttemptOutcome.REJECTED) { assertTrue(it.offers.isEmpty(), "kein Angebot bei Ablehnung"); assertNotNull(it.rejection) }
        }
        // Mindestvorlauf 30 min nur für Voraus: 29 min zu knapp, 30 min genau am Grenzwert zulässig
        assertEquals(RejectionCode.LEAD_TIME, primary("2026-11-02T08:29:00+01:00").rejection!!.primary)
        assertEquals(AttemptOutcome.OFFERED, primary("2026-11-02T08:30:00+01:00").outcome)
        // Wunsch in der Vergangenheit (jenseits der Sofort-Toleranz)
        assertEquals(RejectionCode.LEAD_TIME, primary("2026-11-02T07:00:00+01:00").rejection!!.primary)
        // Horizont 14 Tage: genau 16.11. 08:00 Ortszeit zulässig, eine Sekunde später abgelehnt (kein 422)
        assertEquals(AttemptOutcome.OFFERED, primary("2026-11-16T08:00:00+01:00").outcome)
        assertEquals(RejectionCode.HORIZON, primary("2026-11-16T08:00:01+01:00").rejection!!.primary)
        // Ankunftswunsch, dessen Abholung schon vorbei sein müsste: nicht erreichbar, begründet abgelehnt
        assertEquals(RejectionCode.WINDOW_NOT_FEASIBLE, primary("2026-11-02T08:05:00+01:00", RideTimeKind.ARRIVAL).rejection!!.primary)
        // Ankunftswunsch zu knapp für den Vorlauf: Abholung spätestens 08:29 < jetzt + 30 min
        assertEquals(RejectionCode.LEAD_TIME, primary("2026-11-02T08:39:00+01:00", RideTimeKind.ARRIVAL).rejection!!.primary)

        // DST (PH-AT-018, Zeitteil): rund um die Herbstumstellung 25.10.2026, Dienst rund um die Uhr
        val w24 = fx.world(from = "00:00", to = "24:00")
        val early = Clock.fixed(Instant.parse("2026-10-24T10:00:00Z"), ZoneOffset.UTC)
        val dst = fx.requests(clock = early); val c2 = fx.passenger(w24.tenant)
        val first = dst.request(c2, fx.command(w24, "2026-10-25T02:30:00+02:00")).offers.single()
        val second = dst.request(c2, fx.command(w24, "2026-10-25T02:30:00+01:00")).offers.single()
        assertEquals(at("2026-10-25T00:30:00Z"), first.pickupWindow.earliestAt)
        assertEquals(at("2026-10-25T01:30:00Z"), second.pickupWindow.earliestAt)
        // doppelte Ortszeit ohne Offset: zur Klärung zurückgewiesen; Lücke im Frühjahr: abgelehnt (beides 422 mit Feldverweis)
        val noOffset = assertThrows<ProblemException> { dst.request(c2, fx.command(w24, "2026-10-25T02:30:00")) }
        assertEquals(ViolationCode.OFFSET_REQUIRED, noOffset.violations.single().code); assertEquals("/desired/desired_at", noOffset.violations.single().pointer)
        val gap = assertThrows<ProblemException> { dst.request(c2, fx.command(w24, "2027-03-28T02:30:00+01:00")) }
        assertEquals(ViolationCode.LOCAL_TIME_NONEXISTENT, gap.violations.single().code)
    }

    @Test
    fun formal_fehlerhafte_eingabe_ergibt_422() {
        val api = fx.requests(); val ctx = fx.passenger(w.tenant)
        fun invalid(c: de.taktovia.bookingdispatch.api.RideOfferCommand) = assertThrows<ProblemException> { api.request(ctx, c) }.also { assertEquals(ErrorCode.VALIDATION_FAILED, it.code) }
        val ok = fx.command(w, "2026-11-02T10:00:00+01:00")
        assertEquals("/passengers", invalid(ok.copy(passengers = 0)).violations.single().pointer)
        assertEquals(ViolationCode.ZONE_UNKNOWN, invalid(ok.copy(timeZone = "Mars/Olympus")).violations.single().code)
        assertEquals(ViolationCode.ZONE_OFFSET_MISMATCH, invalid(ok.copy(desiredAt = "2026-11-02T10:00:00+05:00")).violations.single().code)
        assertEquals(ViolationCode.INVALID_FORMAT, invalid(ok.copy(idempotencyKey = "kurz")).violations.single().code)
        assertEquals("/wheelchair_spaces", invalid(ok.copy(wheelchairSpaces = 3)).violations.single().pointer)
        // formale Fehler hinterlassen keinen Versuch
        val before = fx.count("SELECT count(*) FROM taktovia.travel_attempt WHERE tenant_id = ?", w.tenant.value)
        invalid(ok.copy(passengers = 0))
        assertEquals(before, fx.count("SELECT count(*) FROM taktovia.travel_attempt WHERE tenant_id = ?", w.tenant.value))
        // unbekannter Dienst: 404, nicht 422
        val nf = assertThrows<ProblemException> { api.request(ctx, ok.copy(serviceId = java.util.UUID.randomUUID())) }
        assertEquals(ErrorCode.NOT_FOUND, nf.code)
    }
}

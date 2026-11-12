package de.taktovia.stafftime

import de.taktovia.platform.problem.ErrorCode
import de.taktovia.stafftime.api.DriverAssistance
import de.taktovia.stafftime.api.DriverRunProjection
import de.taktovia.stafftime.api.DriverRunSource
import de.taktovia.stafftime.api.DriverRunStopView
import de.taktovia.stafftime.api.EndShiftCommand
import de.taktovia.stafftime.api.StartShiftCommand
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * WP-M3-32 / LH-6.6-DRV-008: Unterstützungshinweise (Rollstuhlbedarf, Ein-/Ausstiegshilfe) sieht nur der Fahrer, dem die Beförderung aktuell zugeordnet ist. Gegen die echte Datenbank
 * unter der Runtime-Rolle; die Stoppliste liefert eine Testquelle ([DriverRunSource]) je Schicht, wie sie der Fahrtenplan von `booking-dispatch` liefert (der Nachweis mit echten Plänen,
 * HTTP, Logs und Planrevisionen steht in `DriverRunProjectionTest`/`DriverAssistanceHttpTest` der Anwendung). Hier: Freigabe nur für eigene, angetretene, freigegebene Schicht mit offenem
 * Fenster, Entzug nach Fahrtabschluss und Schichtende, Abrufprotokoll ohne Merkmalswerte, keine Werte in `toString`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DriverAssistanceVisibilityTest {
    private lateinit var fx: StaffFixture
    private lateinit var w: ShiftWorld
    /** Plan je Schicht (Stopps mit Unterstützung, Zustand des Umlaufs); eine Planrevision mit Fahrerwechsel ist hier ein Umhängen des Stopps in die Liste der anderen Schicht. */
    private val plans = ConcurrentHashMap<UUID, DriverRunProjection>()
    private val source = DriverRunSource { _, shiftId, _ -> plans[shiftId] }

    @BeforeAll fun up() { fx = StaffFixture(); w = ShiftWorld(fx, source = source) }
    @AfterAll fun down() = fx.close()

    private fun at(iso: String) { w.clock.now = Instant.parse(iso) }

    private val needy = DriverAssistance(wheelchairRequired = true, boardingAssist = true, alightingAssist = false)
    private fun stop(assistance: DriverAssistance? = needy) = DriverRunStopView(UUID.randomUUID(), 1, "PICKUP", Instant.parse("2026-12-01T07:00:00Z"), Instant.parse("2026-12-01T07:00:00Z"), "Marktplatz", 13.74, 51.05,
        1, listOf(UUID.randomUUID()), null, emptyList(), UUID.randomUUID(), 1, 0, null, assistance)

    /** Angetretene, freigegebene eigene Schicht eines Fahrers (06:00-14:00, Uhr 10:00); die Quelle liefert [stops] mit Planrevision 4. */
    private inner class Scene(stops: List<DriverRunStopView> = listOf(stop()), runState: String? = "ACTIVE") {
        val p = w.person(); val v = w.vehicle(); val s = w.shiftRow(p, v, "2026-12-01T06:00:00Z", "2026-12-01T14:00:00Z"); val ctx = w.driverCtx(p); val dev = w.device(p)
        init {
            w.release(s, v); w.unblock(v); at("2026-12-01T06:00:00Z"); w.staff.driverShifts.start(ctx, StartShiftCommand(s, v, dev)); at("2026-12-01T10:00:00Z")
            plans[s] = DriverRunProjection(4, stops, runState)
        }
        fun events() = fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id = ? AND action = 'staff.driver.assistance_viewed' AND object_ref = ?", w.tenant.value, "staff_shift:$s")
    }

    @Test
    fun zugeordneter_fahrer_sieht_unterstuetzung_am_stopp() {
        val sc = Scene()
        val run = w.staff.driverShifts.run(sc.ctx, sc.s)
        val a = run.stops.single().assistance
        assertNotNull(a); assertTrue(a!!.wheelchairRequired); assertTrue(a.boardingAssist); assertFalse(a.alightingAssist)
        assertEquals(1, sc.events(), "der Abruf mit Merkmalen erzeugt genau einen audit_event")
    }

    @Test
    fun unbeteiligter_fahrer_erhaelt_keine_daten_auch_nicht_per_buchungs_id() {
        val sc = Scene()
        val other = w.person(); val octx = w.driverCtx(other)
        // anderer Fahrer desselben Mandanten: fremde Schicht ist 404, in der eigenen Liste erscheint sie nie
        assertEquals(ErrorCode.NOT_FOUND, ShiftWorld.problem { w.staff.driverShifts.run(octx, sc.s) }.code)
        assertTrue(w.staff.driverShifts.mine(octx).isEmpty())
        // die eigene Schicht des anderen Fahrers liefert nur den eigenen Plan: die Buchung des ersten Fahrers (Buchungs-ID bekannt) taucht dort nicht auf
        val bookingOfFirst = plans.getValue(sc.s).stops.single().bookingIds.single()
        val v2 = w.vehicle(); val s2 = w.shiftRow(other, v2, "2026-12-01T06:00:00Z", "2026-12-01T14:00:00Z"); w.release(s2, v2); w.unblock(v2)
        w.staff.driverShifts.start(octx, StartShiftCommand(s2, v2, w.device(other)))
        plans[s2] = DriverRunProjection(2, listOf(stop(null)), "ACTIVE")
        val run2 = w.staff.driverShifts.run(octx, s2)
        assertTrue(run2.stops.none { bookingOfFirst in it.bookingIds }); assertTrue(run2.stops.all { it.assistance == null })
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id = ? AND action = 'staff.driver.assistance_viewed' AND object_ref = ?", w.tenant.value, "staff_shift:$s2"), "ohne Merkmale kein Abrufprotokoll")
    }

    @Test
    fun planrevision_wechselt_sichtbarkeit_ab_commit() {
        val a = Scene(); val b = Scene(listOf(stop(null)))
        val moved = plans.getValue(a.s).stops.single()
        assertNotNull(w.staff.driverShifts.run(a.ctx, a.s).stops.single().assistance)
        assertNull(w.staff.driverShifts.run(b.ctx, b.s).stops.single().assistance)
        // Commit der Planrevision: der Stopp wandert zum anderen Fahrer
        plans[a.s] = DriverRunProjection(5, emptyList(), "ACTIVE"); plans[b.s] = DriverRunProjection(3, listOf(moved), "ACTIVE")
        assertTrue(w.staff.driverShifts.run(a.ctx, a.s).stops.isEmpty(), "der bisherige Fahrer erhält die Hinweise ab dem Commit nicht mehr")
        assertNotNull(w.staff.driverShifts.run(b.ctx, b.s).stops.single().assistance, "der neue Fahrer erhält sie")
        assertEquals(1, a.events()); assertEquals(1, b.events())   // je ein Abruf mit Merkmalen, der erste von a (vor dem Wechsel), der zweite von b (danach)
    }

    @Test
    fun fremder_mandant_und_fremde_schicht_liefern_404() {
        val sc = Scene()
        val w2 = ShiftWorld(fx); val p2 = w2.person(); val ctx2 = w2.driverCtx(p2)
        assertEquals(ErrorCode.NOT_FOUND, ShiftWorld.problem { w2.staff.driverShifts.run(ctx2, sc.s) }.code)      // Schicht eines anderen Mandanten
        assertEquals(ErrorCode.NOT_FOUND, ShiftWorld.problem { w.staff.driverShifts.run(w.driverCtx(p2), sc.s) }.code)   // Konto eines anderen Mandanten im Mandanten dieser Schicht
        // gleicher Aussteller und Subject, anderer Mandant: kein Zugriff
        val clone = w2.driverCtx(ShiftWorld.Person(p2.driverId, sc.p.subject, p2.membershipId))
        assertEquals(ErrorCode.NOT_FOUND, ShiftWorld.problem { w2.staff.driverShifts.run(clone, sc.s) }.code)
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id = ? AND action = 'staff.driver.assistance_viewed'", w2.tenant.value))
    }

    @Test
    fun nach_fahrtabschluss_und_schichtende_nicht_mehr_abrufbar() {
        // Fahrtabschluss (Umlauf COMPLETED): Stopps bleiben sichtbar, die Merkmale nicht mehr
        val done = Scene(runState = "COMPLETED")
        val run = w.staff.driverShifts.run(done.ctx, done.s)
        assertEquals(1, run.stops.size); assertNull(run.stops.single().assistance); assertEquals(0, done.events())
        // Schichtende durch den Fahrer: keine Aufträge mehr, also keine Merkmale
        val ended = Scene()
        assertNotNull(w.staff.driverShifts.run(ended.ctx, ended.s).stops.single().assistance)
        w.staff.driverShifts.end(ended.ctx, EndShiftCommand(ended.s, ended.dev))
        val after = w.staff.driverShifts.run(ended.ctx, ended.s)
        assertFalse(after.shiftValid); assertTrue(after.stops.isEmpty())
        // Schichtende durch Zeitablauf
        val late = Scene(); at("2026-12-01T14:00:00Z")
        assertTrue(w.staff.driverShifts.run(late.ctx, late.s).stops.isEmpty())
        // Entzug der Freigabe und nicht angetretene Schicht: ebenfalls keine Merkmale
        at("2026-12-01T10:00:00Z")
        val p = w.person(); val v = w.vehicle(); val s = w.shiftRow(p, v, "2026-12-01T06:00:00Z", "2026-12-01T14:00:00Z"); plans[s] = DriverRunProjection(1, listOf(stop()), "ACTIVE")
        assertTrue(w.staff.driverShifts.run(w.driverCtx(p), s).stops.isEmpty(), "nicht angetretene Schicht: keine Aufträge, keine Merkmale")
        // Umlauf storniert: Fenster geschlossen
        val canceled = Scene(runState = "CANCELED")
        assertNull(w.staff.driverShifts.run(canceled.ctx, canceled.s).stops.single().assistance)
    }

    @Test
    fun abruf_erzeugt_audit_event_ohne_merkmalswerte() {
        val sc = Scene(listOf(stop(), stop(null), stop(DriverAssistance(false, false, true))))
        repeat(2) { w.staff.driverShifts.run(sc.ctx, sc.s) }
        assertEquals(2, sc.events(), "je Abruf mit Merkmalen genau ein Eintrag")
        val entry = fx.strings("SELECT redacted_changes::text FROM taktovia.audit_event WHERE tenant_id = ? AND action = 'staff.driver.assistance_viewed' AND object_ref = ? LIMIT 1", w.tenant.value, "staff_shift:${sc.s}").single()
        assertTrue(entry.contains("TRANSPORT_ASSISTANCE") && entry.contains("\"stop_count\"") , entry)
        assertTrue(entry.contains("\"stop_count\": 2") || entry.contains("\"stop_count\":2"), "zwei der drei Stopps tragen Merkmale: $entry")
        for (forbidden in listOf("wheelchair", "boarding", "alighting", "boarding_assist", "alighting_assist", "\"true\"", ": true", ": false")) assertFalse(entry.contains(forbidden, ignoreCase = true), "kein Merkmalswert im Protokoll ($forbidden): $entry")
        assertEquals("user:${sc.p.subject}", fx.strings("SELECT actor_ref FROM taktovia.audit_event WHERE tenant_id = ? AND action = 'staff.driver.assistance_viewed' AND object_ref = ? LIMIT 1", w.tenant.value, "staff_shift:${sc.s}").single())
    }

    @Test
    fun merkmale_stehen_nie_in_tostring_und_ohne_freigabe_nirgends() {
        val sc = Scene()
        val run = w.staff.driverShifts.run(sc.ctx, sc.s)
        val text = run.toString() + run.stops.toString() + run.stops.single().assistance.toString()
        assertFalse(text.contains("wheelchairRequired") || text.contains("boardingAssist") || text.contains("alightingAssist"), "toString verrät keine Werte: $text")
        assertEquals("DriverAssistance(redacted)", run.stops.single().assistance.toString())
        // Quelle liefert Merkmale, aber die Schicht ist nicht freigegeben: die Quelle wird nicht einmal gefragt, es entsteht kein Eintrag
        val p = w.person(); val v = w.vehicle(); val s = w.shiftRow(p, v, "2026-12-01T06:00:00Z", "2026-12-01T14:00:00Z"); val ctx = w.driverCtx(p)
        w.staff.driverShifts.start(ctx, StartShiftCommand(s, v, w.device(p))); plans[s] = DriverRunProjection(1, listOf(stop()), "ACTIVE")
        val blocked = w.staff.driverShifts.run(ctx, s)
        assertTrue(blocked.stops.isEmpty()); assertEquals(0, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id = ? AND object_ref = ? AND action = 'staff.driver.assistance_viewed'", w.tenant.value, "staff_shift:$s"))
    }
}

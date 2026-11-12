package de.taktovia.stafftime

import de.taktovia.platform.readports.StaffingGap
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WP-M4-17 / LH-6.23-SCH-007: Leseport „Schichtbesetzung“ gegen die echte Datenbank (Runtime-Rolle, RLS). Eine Schicht entsteht über den echten Dienstplanweg (Entwurf, Veröffentlichung);
 * der Nachweis, dass Kandidatenfilter und Hold der Disposition diesen Port auswerten, gehört zu WP-M4-65 (Test `ShiftStaffingDispatchIntegrationTest` im Modul `app`).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShiftStaffingPortTest {
    private lateinit var fx: StaffFixture
    @BeforeAll fun up() { fx = StaffFixture() }
    @AfterAll fun down() = fx.close()

    private val from = Instant.parse("2026-12-01T06:00:00Z")   // 07:00 Berlin
    private val to = Instant.parse("2026-12-01T08:00:00Z")

    @Test
    fun schicht_fahrzeug_betrieb_verknuepft_abrufbar() {
        val s = ShiftStaffingTestWorld(fx); val p = s.w.person(mapOf("PERSONENBEFOERDERUNG" to ("2026-01-01T00:00:00Z" to null)))
        val v = s.vehicle(); val r = s.w.roster(s.planner)
        val shift = fx.staff.rosterShifts.create(s.planner, r.rosterId, s.input("2026-12-01", "06:00", "14:00", p.driverId, v, qualification = "PERSONENBEFOERDERUNG"))
        s.publish(r.rosterId)
        val view = fx.staff.shiftStaffing.staffing(s.reader, v, from, to)
        assertTrue(view.staffed, view.toString()); assertNull(view.gap)
        assertEquals(p.driverId, view.driverRef, "Fahrer"); assertEquals(v, view.vehicleId, "Fahrzeug"); assertEquals(s.service, view.serviceId, "geplanter Betrieb (Dienst)")
        assertEquals(s.w.depot, view.depotId); assertEquals(fx.count("SELECT shift_revision FROM taktovia.staff_shift WHERE id = ?", view.shiftId), view.shiftRevision!!.toInt(), "Schichtrevision")
        assertEquals(shift.id.let { _ -> v }, view.vehicleId)
        assertEquals(1L, view.rosterRevision); assertEquals("PERSONENBEFOERDERUNG", view.qualificationCode); assertEquals(1L, view.qualificationRevision, "Qualifikationsrevision")
        assertNotNull(view.shiftId)
        // dieselbe Antwort in der Transaktion des Aufrufers und als Stapelabfrage
        assertEquals(view, fx.tx.run(s.tenant) { j -> fx.staff.shiftStaffing.staffing(j, v, from, to) })
        assertEquals(view, fx.tx.run(s.tenant) { j -> fx.staff.shiftStaffing.staffing(j, listOf(v, UUID.randomUUID()), from, to) }[v])
    }

    @Test
    fun offene_fahrerschicht_ergibt_fahrzeug_unbesetzt() {
        val s = ShiftStaffingTestWorld(fx); val v = s.vehicle(); val r = s.w.roster(s.planner)
        // offene Fahrerschicht: ohne Person und ohne Fahrzeug (SCH-008); veröffentlicht, aber das Fahrzeug bleibt unbesetzt
        fx.staff.rosterShifts.create(s.planner, r.rosterId, s.input("2026-12-01", "06:00", "14:00", driver = null, vehicle = null))
        s.publish(r.rosterId)
        val view = fx.staff.shiftStaffing.staffing(s.reader, v, from, to)
        assertFalse(view.staffed); assertEquals(StaffingGap.NO_SHIFT, view.gap)
        assertNull(view.shiftId, "keine Schicht, die disponierbar machen könnte")
    }

    @Test
    fun entwurfsschicht_macht_fahrzeug_nicht_besetzt() {
        val s = ShiftStaffingTestWorld(fx); val p = s.w.person(); val v = s.vehicle(); val r = s.w.roster(s.planner)
        fx.staff.rosterShifts.create(s.planner, r.rosterId, s.input("2026-12-01", "06:00", "14:00", p.driverId, v))
        val draft = fx.staff.shiftStaffing.staffing(s.reader, v, from, to)
        assertFalse(draft.staffed, "Entwurf zählt nie als Besetzung"); assertEquals(StaffingGap.NO_SHIFT, draft.gap)
        s.publish(r.rosterId)
        assertTrue(fx.staff.shiftStaffing.staffing(s.reader, v, from, to).staffed, "erst die Veröffentlichung macht das Fahrzeug besetzt")
        // ein neuer Entwurf mit anderer Besetzung ändert die operative Sicht nicht
        fx.staff.rosters.openDraft(s.planner, r.rosterId, fx.staff.rosters.get(s.planner, r.rosterId).etag)
        val ds = fx.staff.rosterShifts.list(s.planner, r.rosterId, 10, null).single()
        fx.staff.rosterShifts.update(s.planner, r.rosterId, ds.id, ds.revision, s.input("2026-12-01", "06:00", "14:00", driver = null, vehicle = null))
        assertTrue(fx.staff.shiftStaffing.staffing(s.reader, v, from, to).staffed, "Entwurf ändert die veröffentlichte Besetzung nicht")
    }

    @Test
    fun schicht_ausserhalb_des_zeitraums_ist_unbesetzt() {
        val s = ShiftStaffingTestWorld(fx); val p = s.w.person(); val v = s.vehicle(); val r = s.w.roster(s.planner)
        fx.staff.rosterShifts.create(s.planner, r.rosterId, s.input("2026-12-01", "06:00", "14:00", p.driverId, v))   // 05:00Z bis 13:00Z
        s.publish(r.rosterId)
        val early = fx.staff.shiftStaffing.staffing(s.reader, v, Instant.parse("2026-12-01T03:00:00Z"), Instant.parse("2026-12-01T04:00:00Z"))
        assertFalse(early.staffed); assertEquals(StaffingGap.NO_SHIFT, early.gap, "keine Schicht in der Nähe des Zeitraums")
        val straddling = fx.staff.shiftStaffing.staffing(s.reader, v, Instant.parse("2026-12-01T12:00:00Z"), Instant.parse("2026-12-01T14:00:00Z"))
        assertFalse(straddling.staffed); assertEquals(StaffingGap.OUTSIDE_PERIOD, straddling.gap, "Schicht endet vor dem Zeitraumende")
        assertTrue(fx.staff.shiftStaffing.staffing(s.reader, v, Instant.parse("2026-12-01T05:00:00Z"), Instant.parse("2026-12-01T13:00:00Z")).staffed, "Grenzen eingeschlossen")
    }

    @Test
    fun fehlende_qualifikation_ergibt_unbesetzt() {
        val s = ShiftStaffingTestWorld(fx); val p = s.w.person(); val v = s.vehicle(); val r = s.w.roster(s.planner)
        fx.staff.rosterShifts.create(s.planner, r.rosterId, s.input("2026-12-01", "06:00", "14:00", p.driverId, v))
        val shift = s.publish(r.rosterId)
        assertTrue(fx.staff.shiftStaffing.staffing(s.reader, v, from, to).staffed)
        // die Schicht verlangt (administrativ nachgetragen) eine Qualifikation, die der Fahrer nicht hat: Einsatzvoraussetzung fehlt
        fx.exec("UPDATE taktovia.staff_shift SET required_qualification = 'ROLLSTUHLBEFOERDERUNG' WHERE tenant_id = ? AND vehicle_id = ?", s.tenant.value, v)
        val view = fx.staff.shiftStaffing.staffing(s.reader, v, from, to)
        assertFalse(view.staffed); assertEquals(StaffingGap.QUALIFICATION_MISSING, view.gap)
    }

    @Test
    fun fahrzeug_eines_fremden_mandanten_liefert_nichts_und_der_port_erteilt_keine_freigabe() {
        val a = ShiftStaffingTestWorld(fx); val b = ShiftStaffingTestWorld(fx); val p = a.w.person(); val v = a.vehicle(); val r = a.w.roster(a.planner)
        fx.staff.rosterShifts.create(a.planner, r.rosterId, a.input("2026-12-01", "06:00", "14:00", p.driverId, v)); a.publish(r.rosterId)
        val releases = fx.count("SELECT count(*) FROM taktovia.resource_release WHERE tenant_id = ?", a.tenant.value)
        assertTrue(fx.staff.shiftStaffing.staffing(a.reader, v, from, to).staffed)
        val foreign = fx.staff.shiftStaffing.staffing(b.reader, v, from, to)
        assertFalse(foreign.staffed, "Kennung des Mandanten A im Kontext von B"); assertEquals(StaffingGap.NO_SHIFT, foreign.gap); assertNull(foreign.driverRef)
        assertEquals(releases, fx.count("SELECT count(*) FROM taktovia.resource_release WHERE tenant_id = ?", a.tenant.value), "besetzt ersetzt keine technische Freigabe")
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.resource_release WHERE tenant_id = ?", a.tenant.value))
    }

    @Test
    fun besetzung_entfaellt_waehrend_hold() {
        // Ein Hold liest die Besetzung in seiner Transaktion; vor dem Commit entfällt sie (neue veröffentlichte Revision ohne Fahrer). Die erneute Prüfung in derselben
        // Transaktion sieht den Wegfall und scheitert; der Port liefert den Stand zum Zeitpunkt des Statements, nie eine veraltete Zwischenkopie.
        val s = ShiftStaffingTestWorld(fx); val p = s.w.person(); val v = s.vehicle(); val r = s.w.roster(s.planner)
        fx.staff.rosterShifts.create(s.planner, r.rosterId, s.input("2026-12-01", "06:00", "14:00", p.driverId, v)); s.publish(r.rosterId)
        val firstRead = CountDownLatch(1); val removed = CountDownLatch(1); val pool = Executors.newFixedThreadPool(2)
        try {
            val hold = pool.submit<Pair<Boolean, Boolean>> {
                fx.tx.run(s.tenant) { j ->
                    val before = fx.staff.shiftStaffing.staffing(j, v, from, to).staffed
                    firstRead.countDown(); assertTrue(removed.await(20, TimeUnit.SECONDS))
                    before to fx.staff.shiftStaffing.staffing(j, v, from, to).staffed
                }
            }
            pool.submit {
                assertTrue(firstRead.await(20, TimeUnit.SECONDS))
                fx.staff.rosters.openDraft(s.planner, r.rosterId, fx.staff.rosters.get(s.planner, r.rosterId).etag)
                val ds = fx.staff.rosterShifts.list(s.planner, r.rosterId, 10, null).single()
                fx.staff.rosterShifts.update(s.planner, r.rosterId, ds.id, ds.revision, s.input("2026-12-01", "06:00", "14:00", driver = null, vehicle = null))
                s.publish(r.rosterId); removed.countDown()
            }.get(30, TimeUnit.SECONDS)
            val (before, after) = hold.get(30, TimeUnit.SECONDS)
            assertTrue(before, "zu Beginn besetzt"); assertFalse(after, "nach dem Wegfall der Besetzung scheitert die erneute Prüfung")
        } finally { pool.shutdownNow() }
    }

    @Test
    fun parallele_zuweisung_liefert_nie_einen_zwischenstand() {
        // Die veröffentlichte Schicht wechselt per Administrator-SQL zwischen zwei Fahrern; die Schichtrevision zählt mit. Ein Leser sieht immer Fahrer und Revision derselben Zeile
        // (gerade Revision: Fahrer A, ungerade: Fahrer B), nie eine Mischung.
        val s = ShiftStaffingTestWorld(fx); val a = s.w.person(); val b = s.w.person(); val v = s.vehicle(); val r = s.w.roster(s.planner)
        val shift = fx.staff.rosterShifts.create(s.planner, r.rosterId, s.input("2026-12-01", "06:00", "14:00", a.driverId, v)); s.publish(r.rosterId)
        val base = fx.count("SELECT shift_revision FROM taktovia.staff_shift WHERE id = ?", shift.id)
        val stop = AtomicBoolean(false); val pool = Executors.newFixedThreadPool(2)
        try {
            val writer = pool.submit {
                var n = 0
                while (!stop.get() && n < 150) { n++; fx.exec("UPDATE taktovia.staff_shift SET driver_ref = ? WHERE id = ?", if (n % 2 == 1) b.driverId else a.driverId, shift.id) }
                stop.set(true)
            }
            val reader = pool.submit<Int> {
                var reads = 0
                while (!stop.get() || reads < 50) {
                    val view = fx.staff.shiftStaffing.staffing(s.reader, v, from, to)
                    assertTrue(view.staffed)
                    val expected = if ((view.shiftRevision!! - base) % 2 == 0L) a.driverId else b.driverId
                    assertEquals(expected, view.driverRef, "Fahrer und Revision stammen aus derselben Zeilenversion")
                    reads++
                }
                reads
            }
            writer.get(60, TimeUnit.SECONDS); assertTrue(reader.get(60, TimeUnit.SECONDS) >= 50)
        } finally { stop.set(true); pool.shutdownNow() }
    }
}

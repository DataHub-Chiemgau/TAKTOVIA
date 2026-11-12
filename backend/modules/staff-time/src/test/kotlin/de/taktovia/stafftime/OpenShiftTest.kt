package de.taktovia.stafftime

import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.stafftime.api.RosterViewKind
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** WP-M4-05 / LH-6.23-SCH-008: offene Schichten und unbesetzter Bedarf gegen die echte Datenbank (Runtime-Rolle). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpenShiftTest {
    private lateinit var fx: StaffFixture
    @BeforeAll fun up() { fx = StaffFixture() }
    @AfterAll fun down() = fx.close()

    @Test
    fun offene_schicht_wird_gezaehlt_und_zaehler_sinkt_nach_zuweisung() {
        val w = RosterTestWorld(fx); val ctx = w.planner(); val r = w.roster(ctx)
        val person = UUID.randomUUID()
        val a = fx.staff.rosterShifts.create(ctx, r.rosterId, w.input(need = 1))
        val b = fx.staff.rosterShifts.create(ctx, r.rosterId, w.input(need = 2))
        val c = fx.staff.rosterShifts.create(ctx, r.rosterId, w.input(day = "2026-12-02", need = 1, function = "Leitstelle"))
        assertNull(a.driverRef); assertNull(a.vehicleId)
        var d = fx.staff.rosters.get(ctx, r.rosterId).draft!!
        assertEquals(3, d.openShifts); assertEquals(4, d.unstaffedNeed)
        assertEquals(setOf(a.id, b.id, c.id), fx.staff.rosterShifts.list(ctx, r.rosterId, 10, null, openOnly = true).map { it.id }.toSet())
        // Bedarf je Tag und Funktion
        assertEquals(listOf(Triple(LocalDate.of(2026, 12, 1), "Fahrdienst", 3), Triple(LocalDate.of(2026, 12, 2), "Leitstelle", 1)), d.needLines.map { Triple(it.day, it.function, it.unstaffed) })
        // Zuweisung einer Person: Zähler sinkt um genau eins, die Schicht ist nicht mehr offen
        val assigned = fx.staff.rosterShifts.update(ctx, r.rosterId, a.id, a.revision, w.input(need = 1, driver = person))
        assertEquals(person, assigned.driverRef)
        d = fx.staff.rosters.get(ctx, r.rosterId).draft!!
        assertEquals(2, d.openShifts); assertEquals(3, d.unstaffedNeed)
        assertEquals(setOf(b.id, c.id), fx.staff.rosterShifts.list(ctx, r.rosterId, 10, null, openOnly = true).map { it.id }.toSet())
        // Bedarf 2 mit einer Person: die Schicht ist besetzt (nicht offen), der Rest des Bedarfs bleibt sichtbar unbesetzt
        fx.staff.rosterShifts.update(ctx, r.rosterId, b.id, b.revision, w.input(need = 2, driver = UUID.randomUUID()))
        d = fx.staff.rosters.get(ctx, r.rosterId).draft!!
        assertEquals(1, d.openShifts); assertEquals(2, d.unstaffedNeed)
        // Person entziehen: die Schicht ist wieder offen
        fx.staff.rosterShifts.update(ctx, r.rosterId, a.id, assigned.revision, w.input(need = 1))
        assertEquals(2, fx.staff.rosters.get(ctx, r.rosterId).draft!!.openShifts)
        // leer: ein Dienstplan ohne Schichten zählt 0
        val empty = w.roster(ctx, LocalDate.of(2026, 12, 14))
        assertEquals(0, empty.draft!!.openShifts); assertEquals(0, empty.draft!!.unstaffedNeed)
    }

    @Test
    fun offene_schicht_traegt_weder_person_noch_fahrzeug_und_ist_nicht_als_besetzt_meldbar() {
        val w = RosterTestWorld(fx); val ctx = w.planner(); val r = w.roster(ctx)
        val vehicle = UUID.randomUUID()
        fx.tx.run(w.tenant) { j -> j.update("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (?,?,'fixture','{}')", w.tenant.value, vehicle) }
        // ein Fahrzeug ohne Person wäre eine verbindliche Fahrzeugzuordnung an einer offenen Schicht: 422, nichts gespeichert
        val e = assertThrows<ProblemException> { fx.staff.rosterShifts.create(ctx, r.rosterId, w.input().copy(vehicleId = vehicle)) }
        assertEquals(ErrorCode.VALIDATION_FAILED, e.code); assertEquals("/vehicle_id", e.violations.single().pointer)
        assertEquals(0, fx.staff.rosters.get(ctx, r.rosterId).draft!!.shiftCount)
        // die Besetzung folgt allein der Person: Fahrzeug mit Person ist besetzt
        val s = fx.staff.rosterShifts.create(ctx, r.rosterId, w.input().copy(driverRef = UUID.randomUUID(), vehicleId = vehicle))
        assertEquals(0, fx.staff.rosters.get(ctx, r.rosterId).draft!!.openShifts)
        // Person entfernen und Fahrzeug behalten: abgewiesen
        assertEquals(ErrorCode.VALIDATION_FAILED, assertThrows<ProblemException> { fx.staff.rosterShifts.update(ctx, r.rosterId, s.id, s.revision, w.input().copy(vehicleId = vehicle)) }.code)
        // Filter „offen“ in der verbindlichen Sicht: nur veröffentlichte Schichten; ein Dienstplan ohne Veröffentlichung existiert verbindlich nicht (404)
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { fx.staff.rosterShifts.list(ctx, r.rosterId, 10, null, RosterViewKind.PUBLISHED, openOnly = true) }.code)
    }

    @Test
    fun filter_offen_liefert_keine_schichten_anderer_mandanten() {
        val a = RosterTestWorld(fx); val b = RosterTestWorld(fx)
        val ra = a.roster(a.planner()); val rb = b.roster(b.planner())
        fx.staff.rosterShifts.create(a.planner(), ra.rosterId, a.input()); val sb = fx.staff.rosterShifts.create(b.planner(), rb.rosterId, b.input())
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { fx.staff.rosterShifts.list(a.planner(), rb.rosterId, 10, null, openOnly = true) }.code, "fremder Dienstplan: nicht auffindbar")
        assertEquals(listOf(sb.id), fx.staff.rosterShifts.list(b.planner(), rb.rosterId, 10, null, openOnly = true).map { it.id })
    }

    @Test
    fun parallele_zuweisung_ergibt_genau_eine_besetzung() {
        val w = RosterTestWorld(fx); val ctx = w.planner(); val r = w.roster(ctx)
        val open = fx.staff.rosterShifts.create(ctx, r.rosterId, w.input())
        fx.staff.rosterShifts.create(ctx, r.rosterId, w.input(day = "2026-12-02"))
        val before = fx.staff.rosters.get(ctx, r.rosterId).draft!!.openShifts
        val people = (1..6).map { UUID.randomUUID() }
        val pool = Executors.newFixedThreadPool(6); val go = CountDownLatch(1)
        try {
            val res = people.map { p -> pool.submit<Result<UUID?>> { go.await(); runCatching { fx.staff.rosterShifts.update(w.planner(), r.rosterId, open.id, open.revision, w.input(driver = p)).driverRef } } }
            go.countDown()
            val out = res.map { it.get(60, TimeUnit.SECONDS) }
            assertEquals(1, out.count { it.isSuccess }, "genau eine Besetzung")
            out.filter { it.isFailure }.forEach { assertEquals(ErrorCode.REVISION_MISMATCH, (it.exceptionOrNull() as ProblemException).code) }
            val winner = out.single { it.isSuccess }.getOrNull()
            assertEquals(winner, fx.staff.rosterShifts.get(ctx, r.rosterId, open.id).driverRef)
        } finally { pool.shutdownNow() }
        assertEquals(before - 1, fx.staff.rosters.get(ctx, r.rosterId).draft!!.openShifts, "der Zähler sinkt nur um eins")
    }
}

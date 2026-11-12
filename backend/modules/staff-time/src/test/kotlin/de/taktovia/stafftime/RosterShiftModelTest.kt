package de.taktovia.stafftime

import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.platform.tenant.TenantId
import de.taktovia.stafftime.api.RosterShiftInput
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** WP-M4-03 / LH-6.23-SCH-002: Schichtmodell gegen die echte Datenbank (Runtime-Rolle): Mitternachtsschicht, Pflichtmerkmale, DST, Mandantenbezug, Audit, konkurrierende Änderung. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RosterShiftModelTest {
    private lateinit var fx: StaffFixture
    @BeforeAll fun up() { fx = StaffFixture() }
    @AfterAll fun down() = fx.close()

    /** Mandant mit Betreiber und Depot; Dienstpläne entstehen je Woche bei Bedarf ([roster]). */
    private inner class Org(val world: RosterTestWorld = RosterTestWorld(fx)) {
        val tenant: TenantId get() = world.tenant
        val operator: UUID get() = world.operator
        val depot: UUID get() = world.depot
        private val rosters = mutableMapOf<LocalDate, UUID>()
        private val ctx get() = world.planner()
        fun rosterFor(day: String): UUID { val d = LocalDate.parse(day); val monday = d.minusDays((d.dayOfWeek.value - 1).toLong()); return rosters.getOrPut(monday) { world.roster(ctx, d).rosterId } }
        val roster: UUID get() = rosterFor("2026-12-01")
    }

    private fun org() = Org()

    private fun planner(o: Org) = o.world.planner()

    private fun input(o: Org, start: String = "2026-12-01T22:00:00", end: String = "2026-12-02T06:00:00", plan: Any? = null, need: Int = 2, tz: String = "Europe/Berlin",
                      startOffset: String? = null, depot: UUID = o.depot, operator: UUID = o.operator) =
        RosterShiftInput(start, startOffset, end, null, tz, plan, operator, depot, "Fahrdienst", "PERSONENBEFOERDERUNG", need)

    private fun invalid(block: () -> Unit): ProblemException = assertThrows<ProblemException> { block() }.also { assertEquals(ErrorCode.VALIDATION_FAILED, it.code) }

    @Test
    fun mitternachtsschicht_hat_kalendertag_des_beginns_und_8h() {
        val o = org(); val ctx = planner(o)
        val s = fx.staff.rosterShifts.create(ctx, o.roster, input(o))
        assertEquals(LocalDate.of(2026, 12, 1), s.operatingDay, "Betriebstag = lokaler Tag des Beginns")
        assertEquals(480L, s.durationMinutes); assertEquals(Instant.parse("2026-12-01T21:00:00Z"), s.startsAt); assertEquals(Instant.parse("2026-12-02T05:00:00Z"), s.endsAt)
        assertEquals("2026-12-02T06:00", s.endsLocal.toString())
        // Tagschicht zum Vergleich
        val day = fx.staff.rosterShifts.create(ctx, o.roster, input(o, "2026-12-02T06:00:00", "2026-12-02T14:00:00"))
        assertEquals(LocalDate.of(2026, 12, 2), day.operatingDay); assertEquals(480L, day.durationMinutes)
        // Zeitumstellung: reale Dauer, nicht Wanduhr-Differenz
        val autumn = fx.staff.rosterShifts.create(ctx, o.rosterFor("2026-10-24"), input(o, "2026-10-24T22:00:00", "2026-10-25T06:00:00"))
        val spring = fx.staff.rosterShifts.create(ctx, o.rosterFor("2027-03-27"), input(o, "2027-03-27T22:00:00", "2027-03-28T06:00:00"))
        assertEquals(540L, autumn.durationMinutes); assertEquals(420L, spring.durationMinutes)
        assertEquals(LocalDate.of(2026, 10, 24), autumn.operatingDay)
    }

    @Test
    fun pflichtmerkmale_gespeichert_und_per_api_lesbar() {
        val o = org(); val ctx = planner(o)
        val plan = mapOf("schema_version" to "1.0", "breaks" to listOf(mapOf("offset_min" to 240, "duration_min" to 30, "paid" to false)))
        val created = fx.staff.rosterShifts.create(ctx, o.roster, input(o, plan = plan, need = 3))
        val read = fx.staff.rosterShifts.get(ctx, o.roster, created.id)
        assertEquals(created.copy(conflicts = null), read, "Lesen prüft keine Konflikte (null), Anlegen liefert die Hinweise")
        assertEquals(o.operator, read.operatorId); assertEquals(o.depot, read.depotId); assertEquals("Fahrdienst", read.function); assertEquals("PERSONENBEFOERDERUNG", read.requiredQualification)
        assertEquals(3, read.staffingNeed); assertEquals(1, read.breakPlan.breaks.size); assertEquals(30, read.breakPlan.breaks.single().durationMin)
        assertEquals("DRAFT", read.state); assertEquals(1L, read.revision); assertNull(read.driverRef, "offene Schicht ohne Fahrer"); assertNull(read.vehicleId)
        assertEquals(listOf(created.id), fx.staff.rosterShifts.list(ctx, o.roster, 10, null).map { it.id })
        assertEquals(emptyList<Any>(), fx.staff.rosterShifts.list(ctx, UUID.randomUUID(), 10, null), "anderer Dienstplan")
        // Pausenplan: außerhalb der Schicht, überlappend, falsche Form
        assertEquals("/break_plan/breaks", invalid { fx.staff.rosterShifts.create(ctx, o.roster, input(o, plan = mapOf("schema_version" to "1.0", "breaks" to listOf(mapOf("offset_min" to 470, "duration_min" to 30, "paid" to true))))) }.violations.single().pointer)
        invalid { fx.staff.rosterShifts.create(ctx, o.roster, input(o, plan = mapOf("schema_version" to "1.0", "breaks" to listOf(mapOf("offset_min" to 60, "duration_min" to 60, "paid" to true), mapOf("offset_min" to 90, "duration_min" to 30, "paid" to true))))) }
        invalid { fx.staff.rosterShifts.create(ctx, o.roster, input(o, plan = mapOf("schema_version" to "2.0", "breaks" to emptyList<Any>()))) }
        assertEquals("/staffing_need", invalid { fx.staff.rosterShifts.create(ctx, o.roster, input(o, need = 0)) }.violations.single().pointer)
        // Rechte und Mandant
        assertEquals(ErrorCode.FORBIDDEN, assertThrows<ProblemException> { fx.staff.rosterShifts.create(StaffM4Support.employee(o.tenant), o.roster, input(o)) }.code)
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { fx.staff.rosterShifts.get(planner(org()), o.roster, created.id) }.code)
    }

    @Test
    fun ende_vor_beginn_ergibt_422() {
        val o = org(); val ctx = planner(o)
        assertEquals("/ends_local", invalid { fx.staff.rosterShifts.create(ctx, o.roster, input(o, "2026-12-02T06:00:00", "2026-12-01T22:00:00")) }.violations.single().pointer)
        invalid { fx.staff.rosterShifts.create(ctx, o.roster, input(o, "2026-12-02T06:00:00", "2026-12-02T06:00:00")) }
        invalid { fx.staff.rosterShifts.create(ctx, o.roster, input(o, "2026-12-02T06:00:00", "2026-12-03T06:00:01")) }   // länger als 24 h
        invalid { fx.staff.rosterShifts.create(ctx, o.roster, input(o, tz = "Mars/Olympus")) }
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.staff_shift WHERE tenant_id = ? AND roster_id IS NOT NULL", o.tenant.value), "nichts gespeichert")
    }

    @Test
    fun dst_luecke_wird_abgelehnt() {
        val o = org(); val ctx = planner(o)
        val e = invalid { fx.staff.rosterShifts.create(ctx, o.rosterFor("2027-03-28"), input(o, "2027-03-28T02:30:00", "2027-03-28T10:00:00")) }
        assertEquals(ViolationCode.LOCAL_TIME_NONEXISTENT, e.violations.single().code); assertEquals("/starts_local", e.violations.single().pointer)
        assertEquals(ViolationCode.LOCAL_TIME_NONEXISTENT, invalid { fx.staff.rosterShifts.create(ctx, o.rosterFor("2027-03-27"), input(o, "2027-03-27T22:00:00", "2027-03-28T02:30:00")) }.violations.single().code)
    }

    @Test
    fun doppelte_ortszeit_ohne_offset_wird_abgelehnt() {
        val o = org(); val ctx = planner(o)
        val e = invalid { fx.staff.rosterShifts.create(ctx, o.rosterFor("2026-10-25"), input(o, "2026-10-25T02:30:00", "2026-10-25T10:00:00")) }
        assertEquals(ViolationCode.LOCAL_TIME_AMBIGUOUS, e.violations.single().code)
        val summer = fx.staff.rosterShifts.create(ctx, o.rosterFor("2026-10-25"), input(o, "2026-10-25T02:30:00", "2026-10-25T10:00:00", startOffset = "+02:00"))
        val winter = fx.staff.rosterShifts.create(ctx, o.rosterFor("2026-10-25"), input(o, "2026-10-25T02:30:00", "2026-10-25T10:00:00", startOffset = "+01:00"))
        assertEquals(Instant.parse("2026-10-25T00:30:00Z"), summer.startsAt); assertEquals(Instant.parse("2026-10-25T01:30:00Z"), winter.startsAt)
        assertEquals(ViolationCode.ZONE_OFFSET_MISMATCH, invalid { fx.staff.rosterShifts.create(ctx, o.roster, input(o, "2026-12-01T22:00:00", startOffset = "+05:00")) }.violations.single().code)
    }

    @Test
    fun fremdes_depot_und_betrieb_ergeben_404() {
        val a = org(); val b = org(); val ctx = planner(a)
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { fx.staff.rosterShifts.create(ctx, a.roster, input(a, depot = b.depot)) }.code)
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { fx.staff.rosterShifts.create(ctx, a.roster, input(a, operator = b.operator)) }.code)
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { fx.staff.rosterShifts.create(ctx, a.roster, input(a, depot = UUID.randomUUID())) }.code)
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.staff_shift WHERE tenant_id = ?", a.tenant.value))
        fx.staff.rosterShifts.create(ctx, a.roster, input(a))
    }

    @Test
    fun anlegen_und_aendern_stehen_im_audit_event() {
        val o = org(); val ctx = planner(o)
        val s = fx.staff.rosterShifts.create(ctx, o.roster, input(o))
        val changed = fx.staff.rosterShifts.update(ctx, o.roster, s.id, 1, input(o, need = 4))
        assertEquals(2L, changed.revision); assertEquals(4, changed.staffingNeed)
        val rows = fx.tx.run(o.tenant) { j -> j.queryForList("SELECT action, actor_ref, occurred_at, object_ref FROM taktovia.audit_event WHERE object_ref = ? ORDER BY occurred_at", "shift:${s.id}") }
        assertEquals(listOf("staff.roster_shift.created", "staff.roster_shift.changed"), rows.map { it["action"] })
        assertTrue(rows.all { it["actor_ref"] == "user:${ctx.actor.subject}" && it["occurred_at"] != null }); assertEquals("shift:${s.id}", rows.first()["object_ref"])
        // unveränderter Inhalt: keine neue Revision, kein weiteres Audit
        assertEquals(2L, fx.staff.rosterShifts.update(ctx, o.roster, s.id, 2, input(o, need = 4)).revision)
        assertEquals(2, fx.tx.run(o.tenant) { j -> j.queryForObject("SELECT count(*) FROM taktovia.audit_event WHERE object_ref = ?", Int::class.java, "shift:${s.id}") })
        // veröffentlichte Schicht ist über diese Schnittstelle nicht änderbar
        fx.exec("UPDATE taktovia.staff_shift SET state = 'PUBLISHED' WHERE id = ?", s.id)
        assertEquals(ErrorCode.CONFLICT, assertThrows<ProblemException> { fx.staff.rosterShifts.update(ctx, o.roster, s.id, 3, input(o, need = 5)) }.code)
    }

    @Test
    fun paralleles_aendern_mit_altem_etag_ergibt_412() {
        val o = org(); val ctx = planner(o)
        val s = fx.staff.rosterShifts.create(ctx, o.roster, input(o))
        val pool = Executors.newFixedThreadPool(8); val go = CountDownLatch(1)
        try {
            val results = (1..8).map { n -> pool.submit<Result<Long>> { go.await(); runCatching { fx.staff.rosterShifts.update(ctx, o.roster, s.id, 1, input(o, need = 10 + n)).revision } } }
            go.countDown()
            val r = results.map { it.get(60, TimeUnit.SECONDS) }
            assertEquals(1, r.count { it.isSuccess }, "genau ein Änderer gewinnt")
            r.filter { it.isFailure }.forEach { assertEquals(ErrorCode.REVISION_MISMATCH, (it.exceptionOrNull() as ProblemException).code) }
        } finally { pool.shutdownNow() }
        val now = fx.staff.rosterShifts.get(ctx, o.roster, s.id)
        assertEquals(2L, now.revision); assertNotEquals(2, now.staffingNeed)
        // parallele Anlage zweier Mandanten vermischt nichts
        val a = org(); val b = org()
        val both = listOf(a, b).map { x -> Executors.newSingleThreadExecutor().submit<Int> { repeat(5) { fx.staff.rosterShifts.create(planner(x), x.roster, input(x)) }; fx.staff.rosterShifts.list(planner(x), x.roster, 50, null).size } }
        assertEquals(listOf(5, 5), both.map { it.get(60, TimeUnit.SECONDS) })
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.staff_shift WHERE tenant_id = ? AND depot_id = ?", a.tenant.value, b.depot))
    }
}

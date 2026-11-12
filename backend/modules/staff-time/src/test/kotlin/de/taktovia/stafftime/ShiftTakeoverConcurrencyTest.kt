package de.taktovia.stafftime

import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.stafftime.api.OverlapExceptionInput
import de.taktovia.stafftime.api.ShiftInput
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M4-65, Kriterium 3 und konkurrierender Test: Leitstelle (Schichtzuweisung, `/v1/shifts`) und Planer (Veröffentlichung des Dienstplans) weisen dieselbe Person parallel überlappend zu; es entsteht
 * höchstens eine verbindliche Schicht. Im Entwurf bleibt die Überlappung speicherbar (Konflikt wird gemeldet), erst die verbindliche Schicht ist ausgeschlossen.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShiftTakeoverConcurrencyTest {
    private lateinit var fx: StaffFixture
    @BeforeAll fun up() { fx = StaffFixture() }
    @AfterAll fun down() = fx.close()

    private fun dispatcher(s: ShiftStaffingTestWorld) = StaffM4Support.ctx(s.tenant, UUID.randomUUID(), setOf("dispatcher"), setOf("shifts:write", "shifts:read"))
    private fun leitstelle(driver: UUID, vehicle: UUID, from: String, to: String) = ShiftInput(driver, vehicle, Instant.parse(from), Instant.parse(to), "Europe/Berlin", null, null)

    @Test
    fun leitstelle_und_planer_weisen_dieselbe_person_parallel_zu_hoechstens_eine_verbindliche_schicht() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(15) { i ->
                val s = ShiftStaffingTestWorld(fx); val p = s.w.person(); val v1 = s.vehicle(); val v2 = s.vehicle(); val r = s.w.roster(s.planner)
                fx.staff.rosterShifts.create(s.planner, r.rosterId, s.input("2026-12-01", "06:00", "14:00", p.driverId, v1))     // 05:00Z bis 13:00Z
                val etag = fx.staff.rosters.get(s.planner, r.rosterId).etag
                val start = CountDownLatch(1)
                val planner = pool.submit<Boolean> {
                    start.await(); Thread.sleep((i % 3) * 5L)
                    runCatching { fx.staff.publishedRosters.publish(s.planner, r.rosterId, de.taktovia.stafftime.api.PublishRoster(Instant.parse("2026-12-01T00:00:00Z"), "Freigabe", UUID.randomUUID(), etag, null)) }
                        .fold({ true }, { e -> assertTrue(e is ProblemException && e.code == ErrorCode.CONFLICT, "Planer scheitert nur am Konflikt: $e"); false })
                }
                val dispatch = pool.submit<Boolean> {
                    start.await()
                    runCatching { fx.staff.shifts.create(dispatcher(s), leitstelle(p.driverId, v2, "2026-12-01T09:00:00Z", "2026-12-01T17:00:00Z")) }
                        .fold({ true }, { e -> assertTrue(e is ProblemException && e.code == ErrorCode.CONFLICT, "Leitstelle scheitert nur am Konflikt: $e"); false })
                }
                start.countDown()
                val plannerWon = planner.get(30, TimeUnit.SECONDS); val dispatchWon = dispatch.get(30, TimeUnit.SECONDS)
                assertFalse(plannerWon && dispatchWon, "Lauf $i: nie beide verbindlich")
                assertTrue(plannerWon || dispatchWon, "Lauf $i: eine der beiden Zuweisungen bleibt bestehen")
                val overlapping = fx.count(
                    "SELECT count(*) FROM taktovia.staff_shift a JOIN taktovia.staff_shift b ON a.tenant_id = b.tenant_id AND a.id < b.id AND a.driver_ref = b.driver_ref " +
                        "AND tstzrange(a.starts_at, a.ends_at, '[)') && tstzrange(b.starts_at, b.ends_at, '[)') WHERE a.tenant_id = ? AND a.driver_ref = ? " +
                        "AND a.state IN ('PLANNED','STARTED','ENDED','PUBLISHED') AND b.state IN ('PLANNED','STARTED','ENDED','PUBLISHED')", s.tenant.value, p.driverId)
                assertEquals(0, overlapping, "Lauf $i: keine zwei überlappenden verbindlichen Schichten derselben Person")
            }
        } finally { pool.shutdownNow() }
    }

    @Test
    fun im_entwurf_ist_die_ueberlappung_speicherbar_und_als_konflikt_sichtbar_verbindlich_aber_nicht() {
        val s = ShiftStaffingTestWorld(fx); val p = s.w.person(); val v1 = s.vehicle(); val v2 = s.vehicle(); val r = s.w.roster(s.planner)
        fx.staff.rosterShifts.create(s.planner, r.rosterId, s.input("2026-12-01", "06:00", "14:00", p.driverId, v1))
        val second = fx.staff.rosterShifts.create(s.planner, r.rosterId, s.input("2026-12-01", "10:00", "18:00", p.driverId, v2))    // überlappt im Entwurf: gespeichert
        assertTrue(second.conflicts!!.isNotEmpty(), "Entwurfskonflikt wird gemeldet, nicht verschwiegen")
        // die Veröffentlichung sperrt den Konflikt, die Leitstelle kann der Person die überlappende Zeit nicht verbindlich zuweisen, wenn sie schon verbindlich belegt ist
        val etag = fx.staff.rosters.get(s.planner, r.rosterId).etag
        val e = org.junit.jupiter.api.assertThrows<ProblemException> { fx.staff.publishedRosters.publish(s.planner, r.rosterId, de.taktovia.stafftime.api.PublishRoster(Instant.parse("2026-12-01T00:00:00Z"), "Freigabe", UUID.randomUUID(), etag, null)) }
        assertEquals(ErrorCode.CONFLICT, e.code)
        val bound = fx.staff.shifts.create(dispatcher(s), leitstelle(p.driverId, v1, "2026-12-02T05:00:00Z", "2026-12-02T13:00:00Z"))
        val clash = org.junit.jupiter.api.assertThrows<ProblemException> { fx.staff.shifts.create(dispatcher(s), leitstelle(p.driverId, v2, "2026-12-02T08:00:00Z", "2026-12-02T16:00:00Z")) }
        assertEquals(ErrorCode.CONFLICT, clash.code, "verbindliche Überlappung bleibt ausgeschlossen (Zusage aus WP-M3-07)")
        assertEquals("PLANNED", bound.state)
        // nur mit dokumentierter Ausnahme (PA-03) bleibt sie möglich
        val ok = fx.staff.shifts.create(dispatcher(s), ShiftInput(p.driverId, v2, Instant.parse("2026-12-02T08:00:00Z"), Instant.parse("2026-12-02T16:00:00Z"), "Europe/Berlin", null, OverlapExceptionInput("Leitung", "Doppelschicht genehmigt")))
        assertTrue(ok.overlapException != null)
    }
}

package de.taktovia.stafftime

import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.stafftime.api.PublishRoster
import de.taktovia.stafftime.api.RosterState
import de.taktovia.stafftime.api.ShiftChangeKind
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * WP-M4-15 / LH-6.23-SCH-010: Änderungs- und Veröffentlichungsrevisionen der Schicht gegen die echte Datenbank (Runtime-Rolle): frühere veröffentlichte Fassungen bleiben unverändert abrufbar,
 * jede Änderung und Veröffentlichung steht mit Autor und Zeitstempel im Protokoll, der Zugriff braucht Auditrecht und Depotrecht.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RosterRevisionHistoryTest {
    private lateinit var fx: StaffFixture
    @BeforeAll fun up() { fx = StaffFixture() }
    @AfterAll fun down() = fx.close()

    private fun auditor(w: RosterTestWorld, depots: Collection<UUID> = listOf(w.depot)): RequestContext =
        StaffM4Support.ctx(w.tenant, UUID.randomUUID(), setOf("staff_planning"), setOf("rosters:audit", "rosters:read")).also { w.grantDepots(it, depots) }

    private fun publish(ctx: RequestContext, rosterId: UUID) =
        fx.staff.publishedRosters.publish(ctx, rosterId, PublishRoster(Instant.parse("2026-12-01T00:00:00Z"), "Freigabe", UUID.randomUUID(), fx.staff.rosters.get(ctx, rosterId).etag))

    /** Dienstplan mit einer Schicht; [versions] weitere Veröffentlichungen, jeweils mit verschobenem Beginn. Gibt die Kennung der ersten Schichtzeile zurück. */
    private fun threePublications(w: RosterTestWorld, ctx: RequestContext, rosterId: UUID, p: RosterTestWorld.Person): UUID {
        val first = fx.staff.rosterShifts.create(ctx, rosterId, w.input(day = "2026-12-01", start = "06:00", end = "12:00", driver = p.driverId))
        publish(ctx, rosterId)
        for (start in listOf("07:00", "08:00")) {
            fx.staff.rosters.openDraft(ctx, rosterId, fx.staff.rosters.get(ctx, rosterId).etag)
            val s = fx.staff.rosterShifts.list(ctx, rosterId, 10, null).single()
            fx.staff.rosterShifts.update(ctx, rosterId, s.id, s.revision, w.input(day = "2026-12-01", start = start, end = "14:00", driver = p.driverId))
            publish(ctx, rosterId)
        }
        return first.id
    }

    @Test
    fun fruehere_fassung_nach_zwei_weiteren_veroeffentlichungen_unveraendert_abrufbar() {
        val w = RosterTestWorld(fx); val ctx = w.planner(); val a = auditor(w); val p = w.person(); val r = w.roster(ctx)
        val first = fx.staff.rosterShifts.create(ctx, r.rosterId, w.input(day = "2026-12-01", start = "06:00", end = "12:00", driver = p.driverId))
        publish(ctx, r.rosterId)
        val rev1 = fx.staff.rosterHistory.revision(a, r.rosterId, 1)
        assertEquals(listOf(first.id), rev1.shifts.map { it.shiftId }); assertEquals(Instant.parse("2026-12-01T05:00:00Z"), rev1.shifts.single().startsAt)
        // zwei weitere Veröffentlichungen mit geänderten Schichten
        for (start in listOf("07:00", "08:00")) {
            fx.staff.rosters.openDraft(ctx, r.rosterId, fx.staff.rosters.get(ctx, r.rosterId).etag)
            val s = fx.staff.rosterShifts.list(ctx, r.rosterId, 10, null).single()
            fx.staff.rosterShifts.update(ctx, r.rosterId, s.id, s.revision, w.input(day = "2026-12-01", start = start, end = "14:00", driver = p.driverId))
            publish(ctx, r.rosterId)
        }
        val heads = fx.staff.rosterHistory.revisions(a, r.rosterId)
        assertEquals(listOf(1L, 2L, 3L), heads.map { it.revision }, "lückenlos"); assertEquals(listOf(RosterState.SUPERSEDED, RosterState.SUPERSEDED, RosterState.PUBLISHED), heads.map { it.state })
        assertTrue(heads.all { it.author == "user:" + ctx.actor.subject && it.publishedBy == "user:" + ctx.actor.subject && it.publishedAt != null })
        // Fassung 1 unverändert (Zeiten, Schichtzeile, Pausenplan), Fassung 3 anders
        assertEquals(rev1.shifts.map { it.copy(state = "SUPERSEDED") }, fx.staff.rosterHistory.revision(a, r.rosterId, 1).shifts, "bis auf den Zustand (ersetzt) identisch")
        assertEquals(Instant.parse("2026-12-01T07:00:00Z"), fx.staff.rosterHistory.revision(a, r.rosterId, 3).shifts.single().startsAt)
        assertEquals(rev1.head.publishedAt, fx.staff.rosterHistory.revision(a, r.rosterId, 1).head.publishedAt)
        // die veröffentlichte Revision lässt sich nicht nachträglich ändern oder löschen (auch nicht von der Anwendungsrolle)
        assertTrue(runCatching { fx.exec("UPDATE taktovia.roster_revision SET author = 'user:x' WHERE tenant_id = ? AND roster_id = ? AND revision = 1", w.tenant.value, r.rosterId) }.isFailure)
        assertTrue(runCatching { fx.exec("DELETE FROM taktovia.roster_revision WHERE tenant_id = ? AND roster_id = ? AND revision = 1", w.tenant.value, r.rosterId) }.isFailure)
    }

    @Test
    fun aenderungen_und_veroeffentlichungen_einer_schicht_mit_autor_und_zeitstempel() {
        val w = RosterTestWorld(fx); val ctx = w.planner(); val a = auditor(w); val p = w.person(); val r = w.roster(ctx)
        val first = threePublications(w, ctx, r.rosterId, p)
        val history = fx.staff.rosterHistory.shiftHistory(a, r.rosterId, first)
        assertEquals(
            listOf(ShiftChangeKind.CREATED to 1L, ShiftChangeKind.PUBLISHED to 1L, ShiftChangeKind.CHANGED to 2L, ShiftChangeKind.PUBLISHED to 2L, ShiftChangeKind.CHANGED to 3L, ShiftChangeKind.PUBLISHED to 3L),
            history.map { it.kind to it.rosterRevision }, "alle Änderungen und Veröffentlichungen der Schicht über alle Revisionen",
        )
        assertTrue(history.all { it.actor == "user:" + ctx.actor.subject }, "Autor")
        assertEquals(history.sortedBy { it.occurredAt }.map { it.id }, history.map { it.id }, "aufsteigend nach Zeitstempel"); assertTrue(history.all { it.occurredAt.isAfter(Instant.parse("2026-01-01T00:00:00Z")) })
        assertEquals("2026-12-01T05:00:00+00:00", history[0].snapshot["starts_at"], "Schichtstand zum Zeitpunkt der Änderung")
        assertEquals("2026-12-01T06:00:00+00:00", history[2].snapshot["starts_at"]); assertEquals("2026-12-01T07:00:00+00:00", history[4].snapshot["starts_at"])
        // dieselbe Historie über die Schichtzeile einer späteren Revision
        val latest = fx.staff.rosterShifts.list(ctx, r.rosterId, 10, null, de.taktovia.stafftime.api.RosterViewKind.PUBLISHED).single().id
        assertEquals(history.map { it.id }, fx.staff.rosterHistory.shiftHistory(a, r.rosterId, latest).map { it.id })
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { fx.staff.rosterHistory.shiftHistory(a, r.rosterId, UUID.randomUUID()) }.code)
    }

    @Test
    fun historie_ohne_auditrecht_oder_fremdes_depot_abgewiesen() {
        val w = RosterTestWorld(fx); val ctx = w.planner(); val p = w.person(); val r = w.roster(ctx)
        val first = threePublications(w, ctx, r.rosterId, p)
        assertEquals(ErrorCode.FORBIDDEN, assertThrows<ProblemException> { fx.staff.rosterHistory.revisions(ctx, r.rosterId) }.code, "Planer ohne Auditrecht")
        assertEquals(ErrorCode.FORBIDDEN, assertThrows<ProblemException> { fx.staff.rosterHistory.shiftHistory(w.driver(), r.rosterId, first) }.code)
        val otherDepot = auditor(w, listOf(w.newDepot("anderes Depot")))
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { fx.staff.rosterHistory.revisions(otherDepot, r.rosterId) }.code, "Auditrecht ohne Depotrecht: 404")
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { fx.staff.rosterHistory.revision(otherDepot, r.rosterId, 1) }.code)
        val foreign = RosterTestWorld(fx); val foreignAuditor = auditor(foreign)
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { fx.staff.rosterHistory.revisions(foreignAuditor, r.rosterId) }.code, "fremder Mandant")
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { fx.staff.rosterHistory.revision(auditor(w), r.rosterId, 99) }.code)
        // ein Entwurf ist keine historische veröffentlichte Fassung
        fx.staff.rosters.openDraft(ctx, r.rosterId, fx.staff.rosters.get(ctx, r.rosterId).etag)
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { fx.staff.rosterHistory.revision(auditor(w), r.rosterId, 4) }.code)
        assertEquals(RosterState.DRAFT, fx.staff.rosterHistory.revisions(auditor(w), r.rosterId).last().state)
    }

    @Test
    fun parallele_veroeffentlichung_und_abruf_sehen_nur_vollstaendige_revisionen() {
        val w = RosterTestWorld(fx); val ctx = w.planner(); val a = auditor(w); val r = w.roster(ctx)
        val people = (1..4).map { w.person() }
        people.forEachIndexed { i, p -> fx.staff.rosterShifts.create(ctx, r.rosterId, w.input(day = "2026-12-0${i + 1}", start = "06:00", end = "12:00", driver = p.driverId)) }
        publish(ctx, r.rosterId)
        fx.staff.rosters.openDraft(ctx, r.rosterId, fx.staff.rosters.get(ctx, r.rosterId).etag)
        fx.staff.rosterShifts.list(ctx, r.rosterId, 10, null).forEach { s -> fx.staff.rosterShifts.update(ctx, r.rosterId, s.id, s.revision, w.input(day = s.operatingDay.toString(), start = "07:00", end = "13:00", driver = s.driverRef)) }
        val stop = AtomicBoolean(false); val broken = AtomicInteger(); val pool = Executors.newFixedThreadPool(3)
        val readers = (1..2).map { pool.submit { while (!stop.get()) {
            val heads = fx.staff.rosterHistory.revisions(a, r.rosterId)
            if (heads.map { it.revision } != (1L..heads.size.toLong()).toList()) broken.incrementAndGet()
            heads.filter { it.state != RosterState.DRAFT }.forEach { h -> if (fx.staff.rosterHistory.revision(a, r.rosterId, h.revision).shifts.size != 4) broken.incrementAndGet() }
        } } }
        val c = PublishRoster(Instant.parse("2026-12-01T00:00:00Z"), "Freigabe", UUID.randomUUID(), fx.staff.rosters.get(ctx, r.rosterId).etag)
        val results = (1..2).map { pool.submit<Result<Any>> { runCatching { fx.staff.publishedRosters.publish(ctx, r.rosterId, c) } } }.map { it.get(60, TimeUnit.SECONDS) }
        Thread.sleep(100); stop.set(true); readers.forEach { it.get(30, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(1, results.count { it.isSuccess }, "zwei parallele Publikationen erzeugen genau eine neue Revision")
        assertEquals(0, broken.get(), "Abrufe sehen nur vollständig committete, lückenlose Revisionen")
        assertEquals(listOf(1L, 2L), fx.staff.rosterHistory.revisions(a, r.rosterId).map { it.revision })
    }
}

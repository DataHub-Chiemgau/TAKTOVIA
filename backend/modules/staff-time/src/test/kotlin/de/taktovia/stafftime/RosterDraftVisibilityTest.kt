package de.taktovia.stafftime

import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.tenant.TenantId
import de.taktovia.stafftime.api.RosterShiftInput
import de.taktovia.stafftime.api.RosterState
import de.taktovia.stafftime.api.RosterViewKind
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
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

/** WP-M4-04 / LH-6.23-SCH-004: Dienstplan-Entwurf getrennt vom veröffentlichten Plan, gegen die echte Datenbank (Runtime-Rolle). Veröffentlichung selbst ist WP-M4-14; hier wird sie per Administration nachgestellt. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RosterDraftVisibilityTest {
    private lateinit var fx: StaffFixture
    @BeforeAll fun up() { fx = StaffFixture() }
    @AfterAll fun down() = fx.close()

    /** Stellt die Veröffentlichung der Entwurfsrevision nach (Administration; der Befehl folgt mit WP-M4-14): Revision und Schichten werden verbindlich. */
    private fun publish(w: RosterTestWorld, rosterId: UUID) {
        fx.exec("UPDATE taktovia.staff_shift SET state = 'PUBLISHED' WHERE tenant_id = ? AND roster_id = ? AND state = 'DRAFT'", w.tenant.value, rosterId)
        fx.exec("UPDATE taktovia.roster_revision SET state = 'PUBLISHED', published_at = now(), published_by = 'user:test' WHERE tenant_id = ? AND roster_id = ? AND state = 'DRAFT'", w.tenant.value, rosterId)
    }

    @Test
    fun planer_legt_entwurf_an_aendert_und_liest_ihn() {
        val w = RosterTestWorld(fx); val ctx = w.planner()
        val r = w.roster(ctx)
        assertEquals(RosterState.DRAFT, r.draft!!.state); assertNull(r.published); assertEquals(1L, r.draft!!.revision); assertEquals(java.time.LocalDate.of(2026, 11, 30), r.weekStart)
        val s = fx.staff.rosterShifts.create(ctx, r.rosterId, w.input())
        assertEquals("DRAFT", s.state); assertEquals(1L, s.revision)
        val changed = fx.staff.rosterShifts.update(ctx, r.rosterId, s.id, 1, w.input(need = 3))
        assertEquals(3, changed.staffingNeed)
        val read = fx.staff.rosters.get(ctx, r.rosterId)
        assertEquals(1, read.draft!!.shiftCount); assertTrue(read.draft!!.etag > r.draft!!.etag, "Stand des Entwurfs steigt mit der Änderung")
        assertEquals(listOf(s.id), fx.staff.rosterShifts.list(ctx, r.rosterId, 10, null).map { it.id })
        // Montag-Regel und Doppelanlage
        assertEquals(ErrorCode.VALIDATION_FAILED, assertThrows<ProblemException> { fx.staff.rosters.create(ctx, de.taktovia.stafftime.api.CreateRoster(w.depot, java.time.LocalDate.of(2026, 12, 9))) }.code)
        assertEquals(ErrorCode.CONFLICT, assertThrows<ProblemException> { w.roster(ctx) }.code)
        // Schicht außerhalb der Woche und mit fremdem Depot des Dienstplans
        assertEquals(ErrorCode.VALIDATION_FAILED, assertThrows<ProblemException> { fx.staff.rosterShifts.create(ctx, r.rosterId, w.input(day = "2026-12-08")) }.code)
        assertEquals(ErrorCode.VALIDATION_FAILED, assertThrows<ProblemException> { fx.staff.rosterShifts.create(ctx, r.rosterId, w.input(depotId = w.newDepot())) }.code)
    }

    @Test
    fun fahrer_sieht_entwurf_nicht() {
        val w = RosterTestWorld(fx); val ctx = w.planner()
        val r = w.roster(ctx); val s = fx.staff.rosterShifts.create(ctx, r.rosterId, w.input())
        val driver = w.driver()
        // Fahrer und operative Rollen ohne Planerrecht: 403 auf Entwurfsdaten
        assertEquals(ErrorCode.FORBIDDEN, assertThrows<ProblemException> { fx.staff.rosters.get(driver, r.rosterId) }.code)
        assertEquals(ErrorCode.FORBIDDEN, assertThrows<ProblemException> { fx.staff.rosters.get(driver, r.rosterId, RosterViewKind.PUBLISHED) }.code)
        assertEquals(ErrorCode.FORBIDDEN, assertThrows<ProblemException> { fx.staff.rosterShifts.get(driver, r.rosterId, s.id) }.code)
        assertEquals(ErrorCode.FORBIDDEN, assertThrows<ProblemException> { fx.staff.rosterShifts.list(driver, r.rosterId, 10, null) }.code)
        assertEquals(ErrorCode.FORBIDDEN, assertThrows<ProblemException> { fx.staff.rosters.create(driver, de.taktovia.stafftime.api.CreateRoster(w.depot, java.time.LocalDate.of(2026, 12, 7))) }.code)
        // auch der Planer sieht in der verbindlichen Sicht den Entwurf nicht (existiert verbindlich nicht): 404
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { fx.staff.rosters.get(ctx, r.rosterId, RosterViewKind.PUBLISHED) }.code)
        assertEquals(emptyList<Any>(), fx.staff.rosterShifts.list(ctx, r.rosterId, 10, null, RosterViewKind.PUBLISHED))
        // Direktabfrage unter der Runtime-Rolle ohne Planerrecht (RLS plus Rollenprüfung): keine Entwurfszeile
        val direct = fx.tx.run(w.tenant) { j -> j.queryForObject("SELECT (SELECT count(*) FROM taktovia.staff_shift WHERE roster_id = ?) + (SELECT count(*) FROM taktovia.roster_revision WHERE roster_id = ?)", Int::class.java, r.rosterId, r.rosterId) }
        assertEquals(0, direct)
    }

    @Test
    fun fremdmandant_erhaelt_404() {
        val a = RosterTestWorld(fx); val b = RosterTestWorld(fx)
        val ra = a.roster(a.planner()); val s = fx.staff.rosterShifts.create(a.planner(), ra.rosterId, a.input())
        val other = b.planner()
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { fx.staff.rosters.get(other, ra.rosterId) }.code)
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { fx.staff.rosterShifts.get(other, ra.rosterId, s.id) }.code)
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { fx.staff.rosterShifts.create(other, ra.rosterId, b.input()) }.code)
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { fx.staff.rosters.openDraft(other, ra.rosterId, 1) }.code)
        assertEquals(emptyList<Any>(), fx.staff.rosters.list(other, null, null, null, 10, null).filter { it.rosterId == ra.rosterId })
        // fremdes Depot beim Anlegen: 404 (nicht 403, kein Hinweis auf Existenz)
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { fx.staff.rosters.create(other, de.taktovia.stafftime.api.CreateRoster(a.depot, java.time.LocalDate.of(2026, 12, 7))) }.code)
    }

    @Test
    fun entwurf_aenderung_laesst_veroeffentlichten_plan_unveraendert() {
        val w = RosterTestWorld(fx); val ctx = w.planner()
        val r = w.roster(ctx); val s = fx.staff.rosterShifts.create(ctx, r.rosterId, w.input(need = 2))
        publish(w, r.rosterId)
        val published = fx.staff.rosters.get(ctx, r.rosterId, RosterViewKind.PUBLISHED)
        assertEquals(RosterState.PUBLISHED, published.published!!.state); assertNull(published.draft)
        // veröffentlichte Schicht ist nicht änderbar, neuer Entwurf = Kopie
        assertEquals(ErrorCode.CONFLICT, assertThrows<ProblemException> { fx.staff.rosterShifts.update(ctx, r.rosterId, s.id, fx.staff.rosterShifts.get(ctx, r.rosterId, s.id).revision, w.input(need = 9)) }.code)
        assertEquals(ErrorCode.CONFLICT, assertThrows<ProblemException> { fx.staff.rosterShifts.create(ctx, r.rosterId, w.input()) }.code, "kein Entwurf offen")
        assertEquals(ErrorCode.REVISION_MISMATCH, assertThrows<ProblemException> { fx.staff.rosters.openDraft(ctx, r.rosterId, 999) }.code)
        val opened = fx.staff.rosters.openDraft(ctx, r.rosterId, fx.staff.rosters.get(ctx, r.rosterId).etag)
        assertEquals(2L, opened.draft!!.revision); assertEquals(1, opened.draft!!.shiftCount); assertEquals(RosterState.PUBLISHED, opened.published!!.state)
        assertEquals(ErrorCode.CONFLICT, assertThrows<ProblemException> { fx.staff.rosters.openDraft(ctx, r.rosterId, fx.staff.rosters.get(ctx, r.rosterId).etag) }.code, "nur ein Entwurf")
        val draftShift = fx.staff.rosterShifts.list(ctx, r.rosterId, 10, null).single()
        assertTrue(draftShift.id != s.id && draftShift.state == "DRAFT")
        fx.staff.rosterShifts.update(ctx, r.rosterId, draftShift.id, draftShift.revision, w.input(need = 7, start = "07:00"))
        fx.staff.rosterShifts.create(ctx, r.rosterId, w.input(day = "2026-12-02"))
        // der veröffentlichte Plan ist unverändert lesbar: gleiche Schicht, gleiche Werte, gleicher Stand
        val after = fx.staff.rosters.get(ctx, r.rosterId, RosterViewKind.PUBLISHED)
        assertEquals(published.published, after.published)
        val publishedShifts = fx.staff.rosterShifts.list(ctx, r.rosterId, 10, null, RosterViewKind.PUBLISHED)
        assertEquals(listOf(s.id), publishedShifts.map { it.id }); assertEquals(2, publishedShifts.single().staffingNeed); assertEquals("PUBLISHED", publishedShifts.single().state)
        assertEquals(2, fx.staff.rosterShifts.list(ctx, r.rosterId, 10, null).size, "Planer sieht im Entwurf beide Schichten")
        assertEquals(2, fx.staff.rosters.get(ctx, r.rosterId).draft!!.shiftCount)
        // Fahrer-sichere Sicht: ohne Planerschalter sind nur veröffentlichte Zeilen sichtbar
        assertEquals(1, fx.tx.run(w.tenant) { j -> j.queryForObject("SELECT count(*) FROM taktovia.staff_shift WHERE roster_id = ?", Int::class.java, r.rosterId) })
    }

    @Test
    fun entwurf_aenderung_erzeugt_keine_outbox_ereignisse() {
        val w = RosterTestWorld(fx); val ctx = w.planner()
        val before = fx.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id = ?", w.tenant.value)
        val r = w.roster(ctx); val s = fx.staff.rosterShifts.create(ctx, r.rosterId, w.input())
        fx.staff.rosterShifts.update(ctx, r.rosterId, s.id, 1, w.input(need = 4))
        publish(w, r.rosterId)
        fx.staff.rosters.openDraft(ctx, r.rosterId, fx.staff.rosters.get(ctx, r.rosterId).etag)
        assertEquals(before, fx.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id = ?", w.tenant.value), "weder Anlage noch Änderung noch Entwurfseröffnung erzeugen Ereignisse")
    }

    @Test
    fun zwei_planer_alter_etag_ergibt_412() {
        val w = RosterTestWorld(fx); val ctx = w.planner(); val ctx2 = w.planner()
        val r = w.roster(ctx); val s = fx.staff.rosterShifts.create(ctx, r.rosterId, w.input())
        fx.staff.rosterShifts.update(ctx, r.rosterId, s.id, 1, w.input(need = 2))
        val e = assertThrows<ProblemException> { fx.staff.rosterShifts.update(ctx2, r.rosterId, s.id, 1, w.input(need = 3)) }
        assertEquals(ErrorCode.REVISION_MISMATCH, e.code)
        assertEquals(2, fx.staff.rosterShifts.get(ctx, r.rosterId, s.id).staffingNeed, "kein stilles Überschreiben")
        // echt parallel: genau ein Gewinner
        val s2 = fx.staff.rosterShifts.create(ctx, r.rosterId, w.input(day = "2026-12-02"))
        val pool = Executors.newFixedThreadPool(6); val go = CountDownLatch(1)
        try {
            val res = (1..6).map { n -> pool.submit<Result<Long>> { go.await(); runCatching { fx.staff.rosterShifts.update(if (n % 2 == 0) ctx else ctx2, r.rosterId, s2.id, 1, w.input(day = "2026-12-02", need = 10 + n)).revision } } }
            go.countDown()
            val r2 = res.map { it.get(60, TimeUnit.SECONDS) }
            assertEquals(1, r2.count { it.isSuccess })
            r2.filter { it.isFailure }.forEach { assertEquals(ErrorCode.REVISION_MISMATCH, (it.exceptionOrNull() as ProblemException).code) }
        } finally { pool.shutdownNow() }
    }

    @Test
    fun parallele_entwurfsaenderung_und_eroeffnung_halten_den_stand_konsistent() {
        // Der Stand des Entwurfs (ETag des Dienstplans) steigt mit jeder Schichtänderung genau um eins; parallele Anlagen verlieren keine Änderung.
        val w = RosterTestWorld(fx); val ctx = w.planner()
        val r = w.roster(ctx)
        val start = fx.staff.rosters.get(ctx, r.rosterId).draft!!.etag
        val pool = Executors.newFixedThreadPool(8); val go = CountDownLatch(1)
        try {
            val res = (1..8).map { pool.submit { go.await(); fx.staff.rosterShifts.create(ctx, r.rosterId, w.input()) } }
            go.countDown(); res.forEach { it.get(60, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
        val now = fx.staff.rosters.get(ctx, r.rosterId).draft!!
        assertEquals(8, now.shiftCount); assertEquals(start + 8, now.etag)
        assertNotNull(now.revisionId)
        // fremde Mandantenzeile in der Zählung ausgeschlossen
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.staff_shift WHERE tenant_id <> ? AND roster_id = ?", w.tenant.value, r.rosterId))
        assertEquals(TenantId(w.tenant.value), w.tenant)
    }
}

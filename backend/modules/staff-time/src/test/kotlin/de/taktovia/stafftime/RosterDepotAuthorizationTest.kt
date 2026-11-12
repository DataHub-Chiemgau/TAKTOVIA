package de.taktovia.stafftime

import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.stafftime.api.CreateRoster
import de.taktovia.stafftime.api.DepotGrantKind
import de.taktovia.stafftime.api.RosterViewKind
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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

/** WP-M4-16 / LH-6.23-SCH-009: Mandanten- und Depotberechtigung im Dienstplan gegen die echte Datenbank (Runtime-Rolle, RLS). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RosterDepotAuthorizationTest {
    private lateinit var fx: StaffFixture
    @BeforeAll fun up() { fx = StaffFixture() }
    @AfterAll fun down() = fx.close()

    private fun notFound(block: () -> Unit) = assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { block() }.code)

    @Test
    fun planer_fremdes_depot_lesen_aendern_veroeffentlichen_abgewiesen() {
        val w = RosterTestWorld(fx); val depotB = w.newDepot()
        val a = w.planner(depots = listOf(w.depot)); val b = w.planner(depots = listOf(depotB))
        // eigenes Depot: lesen, ändern, Veröffentlichung prüfen (positiv)
        val own = w.roster(a); val ownShift = fx.staff.rosterShifts.create(a, own.rosterId, w.input())
        assertEquals(1, fx.staff.rosters.get(a, own.rosterId).draft!!.shiftCount)
        fx.staff.rosterShifts.update(a, own.rosterId, ownShift.id, ownShift.revision, w.input(need = 2))
        fx.ctxTx.run(a) { j -> fx.staff.rosters.requirePublishable(j, a, own.rosterId) }
        // Dienstplan des Depots B
        val foreign = w.roster(b, depotId = depotB); val foreignShift = fx.staff.rosterShifts.create(b, foreign.rosterId, w.input(depotId = depotB))
        // Planer A: lesen (404), ändern (404), veröffentlichen (404), nicht in Listen
        notFound { fx.staff.rosters.get(a, foreign.rosterId) }
        notFound { fx.staff.rosters.get(a, foreign.rosterId, RosterViewKind.PUBLISHED) }
        notFound { fx.staff.rosterShifts.get(a, foreign.rosterId, foreignShift.id) }
        notFound { fx.staff.rosterShifts.list(a, foreign.rosterId, 10, null) }
        notFound { fx.staff.rosterShifts.create(a, foreign.rosterId, w.input(depotId = depotB)) }
        notFound { fx.staff.rosterShifts.update(a, foreign.rosterId, foreignShift.id, foreignShift.revision, w.input(depotId = depotB, need = 9)) }
        notFound { fx.staff.rosters.openDraft(a, foreign.rosterId, 1) }
        notFound { fx.ctxTx.run(a) { j -> fx.staff.rosters.requirePublishable(j, a, foreign.rosterId) } }
        notFound { fx.staff.rosters.create(a, CreateRoster(depotB, LocalDate.of(2026, 12, 14))) }
        assertEquals(1, fx.staff.rosters.get(b, foreign.rosterId).draft!!.shiftCount, "nichts verändert")
        assertEquals(1, fx.staff.rosterShifts.get(b, foreign.rosterId, foreignShift.id).staffingNeed)
        // Listen und Filter: der Filter schränkt nur ein, ein fremdes Depot ergibt eine leere Liste (kein Fehler)
        assertEquals(listOf(own.rosterId), fx.staff.rosters.list(a, null, null, null, 10, null).map { it.rosterId })
        assertEquals(emptyList<Any>(), fx.staff.rosters.list(a, depotB, null, null, 10, null))
        assertEquals(listOf(foreign.rosterId), fx.staff.rosters.list(b, null, null, null, 10, null).map { it.rosterId })
    }

    @Test
    fun manipulierte_tenant_id_im_payload_wirkungslos() {
        // Der Mandant stammt ausschließlich aus dem Kontext; Kennungen eines anderen Mandanten (Depot, Betrieb, Dienstplan, Schicht) sind nicht auffindbar (404), nichts wird dort geändert.
        val x = RosterTestWorld(fx); val y = RosterTestWorld(fx)
        val px = x.planner(); val py = y.planner()
        val ry = y.roster(py); val sy = fx.staff.rosterShifts.create(py, ry.rosterId, y.input())
        notFound { fx.staff.rosters.create(px, CreateRoster(y.depot, LocalDate.of(2026, 12, 7))) }
        notFound { fx.staff.rosters.get(px, ry.rosterId) }
        notFound { fx.staff.rosterShifts.update(px, ry.rosterId, sy.id, sy.revision, y.input(need = 9)) }
        val rx = x.roster(px)
        notFound { fx.staff.rosterShifts.create(px, rx.rosterId, x.input(depotId = y.depot)) }                    // Depot des anderen Mandanten im Payload
        notFound { fx.staff.rosterShifts.create(px, rx.rosterId, x.input().copy(operatorId = y.operator)) }       // Betrieb des anderen Mandanten im Payload
        // selbst mit dem ausdrücklichen depotübergreifenden Recht im eigenen Mandanten bleibt der andere Mandant unerreichbar
        val all = x.planner(depots = emptyList()).also { c -> fx.staff.rosterDepotAccess.grantAllDepots(x.admin, c.actor.subject) }
        notFound { fx.staff.rosters.get(all, ry.rosterId) }
        notFound { fx.staff.rosterShifts.update(all, ry.rosterId, sy.id, sy.revision, y.input(need = 9)) }
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.staff_shift WHERE tenant_id = ?", y.tenant.value), "Mandant Y unverändert")
        assertEquals(1, fx.staff.rosterShifts.get(py, ry.rosterId, sy.id).staffingNeed)
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.staff_shift WHERE tenant_id = ? AND depot_id = ?", x.tenant.value, y.depot))
    }

    @Test
    fun rechteentzug_waehrend_bearbeitung_naechster_schreibbefehl_scheitert() {
        val w = RosterTestWorld(fx); val ctx = w.planner()
        val r = w.roster(ctx); val s = fx.staff.rosterShifts.create(ctx, r.rosterId, w.input())
        val grant = fx.staff.rosterDepotAccess.list(w.admin, ctx.actor.subject).single()
        // Bearbeitung läuft; das Recht wird entzogen; der nächste Schreibbefehl scheitert, ebenso Lesen
        val changed = fx.staff.rosterShifts.update(ctx, r.rosterId, s.id, s.revision, w.input(need = 2))
        fx.staff.rosterDepotAccess.revoke(w.admin, grant.id)
        notFound { fx.staff.rosterShifts.update(ctx, r.rosterId, s.id, changed.revision, w.input(need = 3)) }
        notFound { fx.staff.rosterShifts.create(ctx, r.rosterId, w.input()) }
        notFound { fx.staff.rosters.get(ctx, r.rosterId) }
        assertEquals(2, fx.count("SELECT staffing_need FROM taktovia.staff_shift WHERE id = ?", s.id), "die verweigerte Änderung hat nichts geschrieben")
        assertEquals(ErrorCode.CONFLICT, assertThrows<ProblemException> { fx.staff.rosterDepotAccess.revoke(w.admin, grant.id) }.code, "Entzug ist endgültig")
        // Entzug parallel zu einer Publikation: der Entzug wartet bis zum Ende der Publikationstransaktion, danach wird die Publikation abgewiesen
        val p = w.planner(); val r2 = w.roster(p, LocalDate.of(2026, 12, 14)); fx.staff.rosterShifts.create(p, r2.rosterId, w.input(day = "2026-12-14"))
        val g2 = fx.staff.rosterDepotAccess.list(w.admin, p.actor.subject).single()
        val inside = CountDownLatch(1); val release = CountDownLatch(1); val pool = Executors.newFixedThreadPool(2)
        try {
            val publish = pool.submit<Boolean> { fx.ctxTx.run(p) { j -> fx.staff.rosters.requirePublishable(j, p, r2.rosterId); inside.countDown(); release.await(30, TimeUnit.SECONDS); true } }
            assertTrue(inside.await(30, TimeUnit.SECONDS))
            val revoke = pool.submit { fx.staff.rosterDepotAccess.revoke(w.admin, g2.id) }
            Thread.sleep(500)
            assertFalse(revoke.isDone, "der Entzug wartet auf die laufende Publikation")
            release.countDown(); assertTrue(publish.get(30, TimeUnit.SECONDS)); revoke.get(30, TimeUnit.SECONDS)
        } finally { pool.shutdownNow() }
        notFound { fx.ctxTx.run(p) { j -> fx.staff.rosters.requirePublishable(j, p, r2.rosterId) } }
    }

    @Test
    fun depotuebergreifendes_recht_erlaubt_bearbeitung_und_wird_auditiert() {
        val w = RosterTestWorld(fx); val depotB = w.newDepot()
        val owner = w.planner(depots = listOf(depotB)); val roster = w.roster(owner, depotId = depotB)
        val cross = w.planner(depots = emptyList()); val grant = fx.staff.rosterDepotAccess.grantAllDepots(w.admin, cross.actor.subject)
        assertEquals(DepotGrantKind.ALL_DEPOTS, grant.kind)
        val s = fx.staff.rosterShifts.create(cross, roster.rosterId, w.input(depotId = depotB))
        fx.staff.rosterShifts.update(cross, roster.rosterId, s.id, s.revision, w.input(depotId = depotB, need = 3))
        assertEquals(roster.rosterId, fx.staff.rosters.get(cross, roster.rosterId).rosterId)
        val created = w.roster(cross, LocalDate.of(2026, 12, 14), depotB)
        val rows = fx.strings("SELECT details FROM (SELECT redacted_changes::text AS details, occurred_at FROM taktovia.audit_event WHERE tenant_id = ? AND action = 'staff.roster.cross_depot_edit' AND actor_ref = ?) x ORDER BY occurred_at",
            w.tenant.value, "user:" + cross.actor.subject)
        assertEquals(3, rows.size, "Dienstplan anlegen, Schicht anlegen, Schicht ändern: je ein Eintrag; $rows")
        assertEquals(setOf("roster.create", "shift.create", "shift.update"), rows.map { Regex("\"operation\": ?\"([^\"]+)\"").find(it)!!.groupValues[1] }.toSet())
        assertTrue(rows.all { it.contains(depotB.toString()) })
        assertEquals(created.rosterId, created.rosterId)
        // eigenes Depotrecht erzeugt keinen Eintrag für depotübergreifende Bearbeitung
        fx.staff.rosterShifts.create(owner, roster.rosterId, w.input(depotId = depotB))
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id = ? AND action = 'staff.roster.cross_depot_edit' AND actor_ref = ?", w.tenant.value, "user:" + owner.actor.subject))
        // Vergabe und Entzug stehen im Audit
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id = ? AND action = 'staff.roster_depot_grant.granted' AND object_ref = ?", w.tenant.value, "roster_depot_grant:${grant.id}"))
        // ohne Verwaltungsrecht keine Vergabe; Vergabe für fremdes Depot: 404; doppelt: 409
        assertEquals(ErrorCode.FORBIDDEN, assertThrows<ProblemException> { fx.staff.rosterDepotAccess.grantAllDepots(owner, "x") }.code)
        notFound { fx.staff.rosterDepotAccess.grantDepot(w.admin, "x", UUID.randomUUID()) }
        assertEquals(ErrorCode.CONFLICT, assertThrows<ProblemException> { fx.staff.rosterDepotAccess.grantAllDepots(w.admin, cross.actor.subject) }.code)
        // Entzug des ausdrücklichen Rechts: Bearbeitung scheitert sofort
        fx.staff.rosterDepotAccess.revoke(w.admin, grant.id)
        notFound { fx.staff.rosterShifts.update(cross, roster.rosterId, s.id, s.revision + 1, w.input(depotId = depotB, need = 4)) }
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id = ? AND action = 'staff.roster_depot_grant.revoked' AND object_ref = ?", w.tenant.value, "roster_depot_grant:${grant.id}"))
    }

    @Test
    fun leerer_depotscope_liefert_nichts_fail_closed() {
        val w = RosterTestWorld(fx); val owner = w.planner(); val roster = w.roster(owner); fx.staff.rosterShifts.create(owner, roster.rosterId, w.input())
        val none = w.planner(depots = emptyList())      // Funktionsscopes, aber keine Depotvergabe
        notFound { fx.staff.rosters.get(none, roster.rosterId) }
        notFound { fx.staff.rosters.create(none, CreateRoster(w.depot, LocalDate.of(2026, 12, 14))) }
        notFound { fx.staff.rosterShifts.list(none, roster.rosterId, 10, null) }
        assertEquals(emptyList<Any>(), fx.staff.rosters.list(none, null, null, null, 10, null))
        assertEquals(emptyList<Any>(), fx.staff.rosters.list(none, w.depot, null, null, 10, null))
        // entzogene Vergabe: wieder leer
        val g = fx.staff.rosterDepotAccess.grantDepot(w.admin, none.actor.subject, w.depot)
        assertEquals(1, fx.staff.rosters.list(none, null, null, null, 10, null).size)
        fx.staff.rosterDepotAccess.revoke(w.admin, g.id)
        assertEquals(emptyList<Any>(), fx.staff.rosters.list(none, null, null, null, 10, null))
        assertEquals(1, fx.staff.rosterDepotAccess.list(w.admin, none.actor.subject, includeRevoked = true).size); assertEquals(0, fx.staff.rosterDepotAccess.list(w.admin, none.actor.subject).size)
        // Konfliktmeldungen nennen keine Schicht eines nicht sichtbaren Depots
        val depotB = w.newDepot(); val b = w.planner(depots = listOf(depotB)); val rb = w.roster(b, depotId = depotB)
        val person = UUID.randomUUID()
        val sb = fx.staff.rosterShifts.create(b, rb.rosterId, w.input(depotId = depotB, driver = person))
        fx.exec("UPDATE taktovia.staff_shift SET state = 'PUBLISHED' WHERE id = ?", sb.id)      // verbindliche Schicht im Depot B
        val sa = fx.staff.rosterShifts.create(owner, roster.rosterId, w.input(driver = person))   // überlappend im Depot A, Person doppelt belegt
        val seen = sa.conflicts!!.single { it.kind.name == "DOUBLE_BOOKING" }
        assertEquals(listOf(sa.id), seen.shiftIds, "die Schicht des fremden Depots erscheint nicht"); assertFalse(seen.cause.contains(sb.id.toString()))
        val all = w.planner(depots = emptyList()).also { fx.staff.rosterDepotAccess.grantAllDepots(w.admin, it.actor.subject) }
        assertEquals(setOf(sa.id, sb.id), fx.staff.rosters.get(all, roster.rosterId).conflicts!!.single { it.kind.name == "DOUBLE_BOOKING" }.shiftIds.toSet())
    }
}

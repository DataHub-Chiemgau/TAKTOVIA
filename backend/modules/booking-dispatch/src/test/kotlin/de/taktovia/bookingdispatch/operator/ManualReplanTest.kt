package de.taktovia.bookingdispatch.operator

import de.taktovia.booking.ConfirmFixture
import de.taktovia.bookingdispatch.api.PlanStop
import de.taktovia.bookingdispatch.api.ReplanException
import de.taktovia.bookingdispatch.api.ReplanRule
import de.taktovia.bookingdispatch.api.RevisionCause
import de.taktovia.bookingdispatch.api.RunAccess
import de.taktovia.bookingdispatch.api.StopChange
import de.taktovia.bookingdispatch.api.StopChangeKind
import de.taktovia.bookingdispatch.api.StopDiffKind
import de.taktovia.bookingdispatch.api.WindowViolationCode
import de.taktovia.bookingdispatch.OfferObjectsFixture
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M3-40: manuelle Umplanung mit Begründung und Plan-CAS gegen die echte Datenbank unter der Runtime-Rolle (echte parallele Transaktionen). Die Dienstberechtigung je Fahrzeug übernimmt
 * [RunAccess] (Anwendungsschicht, Nachweis in `RunRevisionApiTest`).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ManualReplanTest {
    private lateinit var cf: ConfirmFixture

    @BeforeAll fun up() { cf = ConfirmFixture() }
    @AfterAll fun down() = cf.close()

    private fun rejected(code: ErrorCode, block: () -> Unit): ProblemException = assertThrows<ProblemException> { block() }.also { assertEquals(code, it.code, it.detail) }

    private fun unchanged(s: ReplanScene, vararg runs: UUID) = runs.forEach { r ->
        assertEquals(2L.takeIf { r == s.runA.runId } ?: 1L, s.revisionOf(r), "keine neue Revision")
    }

    @Test
    fun buchung_von_fahrzeug_a_auf_b_verschieben_atomar_mit_begruendung_und_verteilung() {
        val s = ReplanScene(cf)
        val reason = "Fahrzeug A fällt aus, Buchung auf Fahrzeug B"
        val stateBefore = cf.stringOf("SELECT state FROM taktovia.booking WHERE id = ?", s.a.a.id)
        val r = s.commit.replan(s.op, s.runB.runId, s.cmd(s.transferToB(s.a.a.id), base = 1, reason = reason))
        assertTrue(r.committed); assertEquals(2L, r.planRevision); assertEquals(1L, r.previousRevision); assertEquals(RevisionCause.MANUAL_CHANGE, r.cause); assertEquals(reason, r.reason)
        assertEquals(listOf(StopDiffKind.ADDED, StopDiffKind.ADDED), r.diff.map { it.kind })
        // B steht mit der Buchung, A ohne sie (je eine neue Revision), beide Pläne bleiben gültig
        val planB = s.a.api.runs.currentPlan(s.op, s.runB.runId)
        assertEquals(listOf(s.a.t0, s.a.t0.plusSeconds(900)), planB.stops.map { it.plannedAt })
        assertEquals(listOf(s.a.a.id), planB.stops.flatMap { it.boardings }.map { it.bookingId })
        val planA = s.a.api.runs.currentPlan(s.op, s.runA.runId)
        assertEquals(3L, planA.revision); assertTrue(planA.stops.flatMap { it.boardings + it.alightings }.none { it.bookingId == s.a.a.id })
        assertEquals(setOf(s.a.b.id), planA.stops.flatMap { it.boardings }.map { it.bookingId }.toSet())
        // Hold folgt der Buchung (Kapazität auf B, nicht mehr auf A); Umbuchung protokolliert; Begründung und Akteur mit der Revision gespeichert
        val holdId = s.a.ha.first.holdId
        assertEquals(s.runB.runId.toString(), cf.stringOf("SELECT run_id::text FROM taktovia.capacity_hold WHERE id = ?", holdId))
        assertEquals(1, s.count("SELECT count(*) FROM taktovia.booking_change WHERE booking_id = ? AND from_run_id = ? AND to_run_id = ? AND reason = ?", s.a.a.id, s.runA.runId, s.runB.runId, reason))
        assertEquals(reason, cf.stringOf("SELECT reason FROM taktovia.plan_change WHERE run_id = ? AND run_revision = 2", s.runB.runId))
        assertTrue(cf.stringOf("SELECT created_by FROM taktovia.plan_change WHERE run_id = ? AND run_revision = 2", s.runB.runId).startsWith("user:"))
        assertEquals("MANUAL", cf.stringOf("SELECT created_via FROM taktovia.plan_change WHERE run_id = ? AND run_revision = 2", s.runB.runId))
        assertEquals(1, s.count("SELECT count(*) FROM taktovia.audit_event WHERE action = 'dispatch.run.revised' AND object_ref = ? AND redacted_changes->>'reason' = ?", "vehicle_run:${s.runB.runId}", reason))
        // Verteilung an Fahrer und Fahrgäste: Planereignis je Umlauf und Planänderung der Buchung (WP-M2-28)
        assertEquals(1, s.count("SELECT count(*) FROM taktovia.outbox WHERE event_type = 'dispatch.run_revised' AND aggregate_id = ? AND aggregate_revision = 2", s.runB.runId))
        assertEquals(1, s.count("SELECT count(*) FROM taktovia.plan_change_booking pb JOIN taktovia.plan_change pc ON pc.tenant_id = pb.tenant_id AND pc.id = pb.plan_change_id WHERE pb.booking_id = ? AND pc.run_id = ? AND pb.kind = 'ADDED'", s.a.a.id, s.runB.runId))
        // Revisionsliste mit Begründung, Akteur und Planvergleich
        val list = s.commit.revisions(s.op, s.runB.runId, 10)
        assertEquals(1, list.size); assertEquals(reason, list.single().reason); assertEquals(2, list.single().diff.size); assertEquals(r.createdBy, list.single().createdBy)
        // die Buchung ist weiter bestätigt und trägt ihr Zusagefenster unverändert
        assertEquals(stateBefore, cf.stringOf("SELECT state FROM taktovia.booking WHERE id = ?", s.a.a.id))
    }

    @Test
    fun veraltete_revision_ergibt_412_mit_aktueller_revision_und_schreibt_nichts() {
        val s = ReplanScene(cf)
        val e = rejected(ErrorCode.REVISION_MISMATCH) { s.commit.replan(s.op, s.runA.runId, s.cmd(s.swapFirstTwo(), base = 1)) }
        assertEquals("\"2\"", e.headers["ETag"])
        unchanged(s, s.runA.runId, s.runB.runId)
        assertEquals(0, s.count("SELECT count(*) FROM taktovia.plan_change WHERE run_id = ? AND run_revision = 3", s.runA.runId))
        // mit der gelesenen Revision gelingt dieselbe Änderung
        assertEquals(3L, s.commit.replan(s.op, s.runA.runId, s.cmd(s.swapFirstTwo(), base = 2)).planRevision)
    }

    @Test
    fun ohne_begruendung_wird_abgewiesen_und_nichts_geschrieben() {
        val s = ReplanScene(cf)
        for (reason in listOf("", "   ")) {
            val e = rejected(ErrorCode.VALIDATION_FAILED) { s.commit.replan(s.op, s.runA.runId, s.cmd(s.swapFirstTwo(), base = 2, reason = reason)) }
            assertEquals("/reason", e.violations.single().pointer)
        }
        rejected(ErrorCode.VALIDATION_FAILED) { s.commit.replan(s.op, s.runA.runId, s.cmd(s.swapFirstTwo(), base = 2, reason = "x".repeat(501))) }
        rejected(ErrorCode.VALIDATION_FAILED) { s.commit.replan(s.op, s.runA.runId, s.cmd(emptyList(), base = 2)) }
        unchanged(s, s.runA.runId, s.runB.runId)
        assertEquals(0, s.count("SELECT count(*) FROM taktovia.audit_event WHERE action = 'dispatch.run.revised' AND object_ref = ?", "vehicle_run:${s.runA.runId}"))
    }

    @Test
    fun gesperrtes_nicht_freigegebenes_oder_fremdes_zielfahrzeug_scheitert_im_commit() {
        val s = ReplanScene(cf); val r = ReplanScene(cf); val p = ReplanScene(cf)
        // Sperre des Zielfahrzeugs unmittelbar vor dem Commit
        cf.fx.adminUpdate("UPDATE taktovia.vehicle SET active_block = true WHERE id = ?", s.runB.vehicleId)
        var e = rejected(ErrorCode.CONFLICT) { s.commit.replan(s.op, s.runB.runId, s.cmd(s.transferToB(s.a.a.id), base = 1)) }
        assertTrue(e.detail!!.contains("VEHICLE_BLOCKED"))
        cf.fx.adminUpdate("UPDATE taktovia.vehicle SET active_block = false WHERE id = ?", s.runB.vehicleId)
        // Einsatzfreigabe widerrufen: nicht freigegeben
        cf.fx.adminUpdate("UPDATE taktovia.resource_release SET revoked_at = now() WHERE vehicle_id = ?", r.runB.vehicleId)
        e = rejected(ErrorCode.CONFLICT) { r.commit.replan(r.op, r.runB.runId, r.cmd(r.transferToB(r.a.a.id), base = 1)) }
        assertTrue(e.detail!!.contains("NO_VALID_RELEASE"))
        unchanged(s, s.runA.runId, s.runB.runId); unchanged(r, r.runA.runId, r.runB.runId)
        assertEquals(0, s.count("SELECT count(*) FROM taktovia.booking_change WHERE booking_id = ? AND to_run_id IS NOT NULL", s.a.a.id))
        assertEquals(s.runA.runId.toString(), cf.stringOf("SELECT run_id::text FROM taktovia.capacity_hold WHERE id = ?", s.a.ha.first.holdId))
        // Ziel eines Dienstes ohne Pool-Zuordnung: das Fahrzeug ist für die Buchung nicht einsetzbar
        cf.fx.adminUpdate("UPDATE taktovia.vehicle_pool_assignment SET valid_to = '2026-06-01T00:00:00Z' WHERE vehicle_id = ?", p.runB.vehicleId)
        e = rejected(ErrorCode.CONFLICT) { p.commit.replan(p.op, p.runB.runId, p.cmd(p.transferToB(p.a.a.id), base = 1)) }
        assertTrue(e.detail!!.contains("NOT_IN_POOL"))
    }

    @Test
    fun dienstberechtigung_je_fahrzeug_wird_vor_dem_sperren_geprueft_fremder_umlauf_404() {
        val s = ReplanScene(cf)
        val denied = RunAccess { _, vehicle -> if (vehicle == s.runB.vehicleId) throw ProblemException.notFound() }
        rejected(ErrorCode.NOT_FOUND) { s.commit.replan(s.op, s.runB.runId, s.cmd(s.transferToB(s.a.a.id), base = 1), denied) }
        rejected(ErrorCode.NOT_FOUND) { s.commit.revisions(s.op, s.runB.runId, 10, access = denied) }
        // anderer Mandant: der Umlauf existiert nicht
        val other = ReplanScene(cf)
        rejected(ErrorCode.NOT_FOUND) { s.commit.replan(other.op, s.runA.runId, s.cmd(s.swapFirstTwo(), base = 2)) }
        rejected(ErrorCode.NOT_FOUND) { s.commit.revisions(other.op, s.runA.runId, 10) }
        unchanged(s, s.runA.runId, s.runB.runId)
    }

    @Test
    fun stopp_im_geschuetzten_horizont_wird_abgewiesen_und_nur_per_ausnahme_erlaubt() {
        val s = ReplanScene(cf, params = mapOf("protected_stop_horizon_min" to 60))
        // 08:30 Uhr: der erste Stopp um 09:00 liegt 30 Minuten voraus, also im geschützten Horizont von 60 Minuten
        cf.fx.clock.set(Instant.parse("2026-11-02T08:30:00Z"))
        rejected(ErrorCode.PROTECTED_STOP_HORIZON) { s.commit.replan(s.op, s.runA.runId, s.cmd(s.swapFirstTwo(), base = 2)) }
        unchanged(s, s.runA.runId)
        // Ausnahme ohne Begründung oder mit einer anderen Regel hilft nicht
        rejected(ErrorCode.VALIDATION_FAILED) { s.commit.replan(s.op, s.runA.runId, s.cmd(s.swapFirstTwo(), base = 2, exception = ReplanException(setOf(ReplanRule.PROTECTED_STOP_HORIZON), "kurz"))) }
        rejected(ErrorCode.PROTECTED_STOP_HORIZON) { s.commit.replan(s.op, s.runA.runId, s.cmd(s.swapFirstTwo(), base = 2, exception = ReplanException(setOf(ReplanRule.MAX_REASSIGNMENTS), "Ausnahme zur Umbuchung begründet"))) }
        // dokumentierter Ausnahmeweg: Regel, Begründung und Akteur im Datensatz und im Audit
        val justification = "Fahrgast wünscht frühere Abholung, Disposition genehmigt"
        val r = s.commit.replan(s.op, s.runA.runId, s.cmd(s.swapFirstTwo(), base = 2, exception = ReplanException(setOf(ReplanRule.PROTECTED_STOP_HORIZON), justification)))
        assertEquals(setOf(ReplanRule.PROTECTED_STOP_HORIZON), r.exception!!.rules)
        assertEquals("{PROTECTED_STOP_HORIZON}", cf.stringOf("SELECT exception_rules::text FROM taktovia.plan_change WHERE run_id = ? AND run_revision = 3", s.runA.runId))
        assertEquals(justification, cf.stringOf("SELECT exception_justification FROM taktovia.plan_change WHERE run_id = ? AND run_revision = 3", s.runA.runId))
        assertEquals(1, s.count("SELECT count(*) FROM taktovia.audit_event WHERE action = 'dispatch.run.replan_exception' AND object_ref = ?", "vehicle_run:${s.runA.runId}"))
        // der wirksame Wert im Commit: ohne Horizont-Parameter-Treffer (Zeit weit davor) gilt die Regel nicht
        cf.fx.clock.set(Instant.parse("2026-11-02T07:00:00Z"))
        assertEquals(4L, s.commit.replan(s.op, s.runA.runId, s.cmd(s.swapFirstTwo(revision = 3), base = 3)).planRevision)
    }

    @Test
    fun zusagefenster_werden_nicht_still_verletzt_nur_per_ausnahme() {
        val s = ReplanScene(cf)
        // Ausstieg von A vor Einstieg von B: der Ausstieg läge um 09:05, vor dem frühesten Ankunftsfenster (09:10)
        val bad = listOf(StopChange(StopChangeKind.MOVE, s.stopId(s.runA.runId, 2, 1), 3))
        rejected(ErrorCode.PROMISE_WINDOW_VIOLATION) { s.commit.replan(s.op, s.runA.runId, s.cmd(bad, base = 2)) }
        // Vergleich ohne Commit zeigt die Folge für die Buchung (Folgenanzeige), schreibt aber nichts
        val ex = ReplanException(setOf(ReplanRule.PROMISE_WINDOW), "Fahrgast A stimmt telefonisch einer früheren Ankunft zu")
        val preview = s.commit.replan(s.op, s.runA.runId, s.cmd(bad, base = 2, compareOnly = true, exception = ex))
        assertFalse(preview.committed); assertEquals(3L, preview.planRevision)
        assertTrue(preview.impacts.any { it.bookingId == s.a.a.id && WindowViolationCode.ARRIVAL_OUTSIDE_WINDOW in it.windowViolations })
        unchanged(s, s.runA.runId)
        val r = s.commit.replan(s.op, s.runA.runId, s.cmd(bad, base = 2, exception = ex))
        assertTrue(r.committed); assertEquals(setOf(ReplanRule.PROMISE_WINDOW), r.exception!!.rules)
        // eine Buchung ohne Plan (entfernt und nirgends neu eingefügt) ist ebenfalls eine verletzte Zusage
        val s2 = ReplanScene(cf)
        val remove = listOf(StopChange(StopChangeKind.REMOVE, s2.stopId(s2.runA.runId, 2, 0), bookingId = s2.a.a.id))
        rejected(ErrorCode.PROMISE_WINDOW_VIOLATION) { s2.commit.replan(s2.op, s2.runA.runId, s2.cmd(remove, base = 2)) }
    }

    @Test
    fun ueberbuchung_des_zielfahrzeugs_scheitert_je_dimension() {
        // Sitz: B hat nur einen Sitz; beide Buchungen überlappen (09:05 bis 09:15) und brauchen zusammen zwei
        val s = ReplanScene(cf, capacityB = OfferObjectsFixture.cap(1, 0))
        val both = s.transferToB(s.a.a.id, pos = 1 to 2) + s.transferToB(s.a.b.id, boardSeq = 1, alightSeq = 3, pos = 2 to 4).let { l -> l }
        rejected(ErrorCode.CAPACITY_UNAVAILABLE) { s.commit.replan(s.op, s.runB.runId, s.cmd(both, base = 1)) }
        unchanged(s, s.runA.runId, s.runB.runId)
        // Rollstuhlplatz: ein freier Sitz deckt keinen Rollstuhlplatz; B ohne Rollstuhlplatz
        val w = ReplanScene(cf, capacityB = OfferObjectsFixture.cap(4, 0), wheelchairA = 1)
        val e = rejected(ErrorCode.CAPACITY_UNAVAILABLE) { w.commit.replan(w.op, w.runB.runId, w.cmd(w.transferToB(w.a.a.id), base = 1)) }
        assertTrue(e.detail!!.isNotBlank())
        unchanged(w, w.runA.runId, w.runB.runId)
        // mit ausreichender Kapazität beider Dimensionen gelingt es
        val ok = ReplanScene(cf, capacityB = OfferObjectsFixture.cap(4, 1), wheelchairA = 1)
        assertTrue(ok.commit.replan(ok.op, ok.runB.runId, ok.cmd(ok.transferToB(ok.a.a.id), base = 1)).committed)
    }

    @Test
    fun max_reassignments_wird_mit_dem_wirksamen_wert_geprueft_und_ausnahme_dokumentiert() {
        val s = ReplanScene(cf, params = mapOf("max_reassignments" to 1))
        s.commit.replan(s.op, s.runB.runId, s.cmd(s.transferToB(s.a.a.id), base = 1))     // erste Umbuchung A -> B
        // zurück nach A wäre die zweite Umbuchung der Buchung, erlaubt ist eine
        fun back(): List<StopChange> = listOf(
            StopChange(StopChangeKind.INSERT, s.stopId(s.runB.runId, 2, 0), 1, s.a.a.id), StopChange(StopChangeKind.INSERT, s.stopId(s.runB.runId, 2, 1), 3, s.a.a.id),
        )
        val e = rejected(ErrorCode.MAX_REASSIGNMENTS_EXCEEDED) { s.commit.replan(s.op, s.runA.runId, s.cmd(back(), base = 3)) }
        assertTrue(e.detail!!.contains("Höchstzahl 1"))
        assertEquals(1, s.count("SELECT count(*) FROM taktovia.booking_change WHERE booking_id = ? AND to_run_id IS NOT NULL", s.a.a.id))
        // dokumentierte Ausnahme
        val r = s.commit.replan(s.op, s.runA.runId, s.cmd(back(), base = 3, exception = ReplanException(setOf(ReplanRule.MAX_REASSIGNMENTS), "Fahrzeug B ausgefallen, zweite Umbuchung nötig")))
        assertTrue(r.committed); assertEquals(setOf(ReplanRule.MAX_REASSIGNMENTS), r.exception!!.rules)
        assertEquals(s.runA.runId.toString(), cf.stringOf("SELECT run_id::text FROM taktovia.capacity_hold WHERE id = ?", s.a.ha.first.holdId))
        assertEquals(2, s.count("SELECT count(*) FROM taktovia.booking_change WHERE booking_id = ? AND to_run_id IS NOT NULL", s.a.a.id))
        // ohne Parameter gilt der Planungsdefault (2): eine frische Szene lässt zwei Umbuchungen zu
        val d = ReplanScene(cf)
        d.commit.replan(d.op, d.runB.runId, d.cmd(d.transferToB(d.a.a.id), base = 1))
        assertTrue(d.commit.replan(d.op, d.runA.runId, d.cmd(listOf(
            StopChange(StopChangeKind.INSERT, d.stopId(d.runB.runId, 2, 0), 1, d.a.a.id), StopChange(StopChangeKind.INSERT, d.stopId(d.runB.runId, 2, 1), 3, d.a.a.id)), base = 3)).committed)
    }

    @Test
    fun vergleich_ohne_commit_schreibt_nichts_und_liefert_planvergleich_und_folgen() {
        val s = ReplanScene(cf)
        val r = s.commit.replan(s.op, s.runB.runId, s.cmd(s.transferToB(s.a.a.id), base = 1, compareOnly = true))
        assertFalse(r.committed); assertEquals(2L, r.planRevision); assertEquals(1L, r.previousRevision)
        assertEquals(2, r.diff.size); assertTrue(r.impacts.any { it.bookingId == s.a.a.id })
        unchanged(s, s.runA.runId, s.runB.runId)
        assertEquals(0, s.count("SELECT count(*) FROM taktovia.booking_change WHERE booking_id = ? AND to_run_id IS NOT NULL", s.a.a.id))
        assertEquals(0, s.count("SELECT count(*) FROM taktovia.audit_event WHERE action = 'dispatch.run.revised' AND object_ref = ?", "vehicle_run:${s.runB.runId}"))
        // der Vergleich prüft die Revision wie der Commit
        rejected(ErrorCode.REVISION_MISMATCH) { s.commit.replan(s.op, s.runB.runId, s.cmd(s.transferToB(s.a.a.id), base = 7, compareOnly = true)) }
    }

    @Test
    fun zwei_disponenten_aendern_denselben_run_genau_eine_revision_gewinnt() {
        val s = ReplanScene(cf)
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(6) {
                val base = s.revisionOf(s.runA.runId)
                val start = CountDownLatch(1)
                val jobs = (1..2).map { n -> pool.submit<Result<Long>> { start.await(); runCatching { s.commit.replan(s.op, s.runA.runId, s.cmd(s.swapFirstTwo(revision = base), base = base, reason = "Disponent $n ändert den Umlauf")).planRevision } } }
                start.countDown()
                val results = jobs.map { it.get(30, TimeUnit.SECONDS) }
                assertEquals(1, results.count { it.isSuccess }, "genau eine Revision gewinnt: $results")
                val loser = results.single { it.isFailure }.exceptionOrNull() as ProblemException
                assertEquals(ErrorCode.REVISION_MISMATCH, loser.code); assertEquals("\"${base + 1}\"", loser.headers["ETag"])
                assertEquals(base + 1, s.revisionOf(s.runA.runId))
            }
        } finally { pool.shutdownNow() }
        // keine Lücken im Änderungsstrom: je Revision genau eine Planänderung
        assertEquals(6, s.count("SELECT count(*) FROM taktovia.plan_change WHERE run_id = ? AND run_revision > 2", s.runA.runId))
    }

    @Test
    fun umplanung_parallel_zur_automatischen_disposition_genau_eine_gewinnt_ohne_luecke() {
        val s = ReplanScene(cf)
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(6) { i ->
                val base = s.revisionOf(s.runA.runId)
                val start = CountDownLatch(1)
                val manual = pool.submit<Result<Long>> { start.await(); runCatching { s.commit.replan(s.op, s.runA.runId, s.cmd(s.swapFirstTwo(revision = base), base = base)).planRevision } }
                // die automatische Disposition schreibt auf derselben Basisrevision (andere Zeiten, selbe Reihenfolge)
                val auto = pool.submit<Result<Long>> { start.await(); runCatching { s.a.api.runs.appendRevision(s.op, s.runA.runId, base, s.a.stops(60L * (i + 1)), RevisionCause.REPLAN, emptyList()).revision } }
                start.countDown()
                val r = listOf(manual.get(30, TimeUnit.SECONDS), auto.get(30, TimeUnit.SECONDS))
                assertEquals(1, r.count { it.isSuccess }, "genau eine gewinnt: $r")
                assertEquals(ErrorCode.REVISION_MISMATCH, (r.single { it.isFailure }.exceptionOrNull() as ProblemException).code)
                assertEquals(base + 1, s.revisionOf(s.runA.runId))
            }
        } finally { pool.shutdownNow() }
        assertEquals(6, s.count("SELECT count(*) FROM taktovia.plan_change WHERE run_id = ? AND run_revision > 2", s.runA.runId))
    }

    @Test
    fun zwei_umplanungen_in_gegenrichtung_blockieren_sich_nicht_und_buchen_nie_doppelt() {
        // A -> B und B -> A gleichzeitig (jeweils andere Buchung): feste Sperrreihenfolge, kein Deadlock, beide gelingen oder verlieren sauber mit 412
        val s = ReplanScene(cf)
        s.commit.replan(s.op, s.runB.runId, s.cmd(s.transferToB(s.a.a.id), base = 1))   // a liegt auf B; b liegt auf A
        val pool = Executors.newFixedThreadPool(2)
        try {
            val start = CountDownLatch(1)
            val aToB = pool.submit<Result<Long>> { start.await(); runCatching { s.commit.replan(s.op, s.runB.runId, s.cmd(s.transferToB(s.a.b.id, fromRevision = 3, boardSeq = 0, alightSeq = 1, pos = 3 to 4), base = 2)).planRevision } }
            val bToA = pool.submit<Result<Long>> { start.await(); runCatching { s.commit.replan(s.op, s.runA.runId, s.cmd(listOf(
                StopChange(StopChangeKind.INSERT, s.stopId(s.runB.runId, 2, 0), 1, s.a.a.id), StopChange(StopChangeKind.INSERT, s.stopId(s.runB.runId, 2, 1), 3, s.a.a.id)), base = 3,
                exception = ReplanException(setOf(ReplanRule.MAX_REASSIGNMENTS), "gegenläufige Umbuchung, Ausnahme dokumentiert"))).planRevision } }
            start.countDown()
            val r = listOf(aToB.get(30, TimeUnit.SECONDS), bToA.get(30, TimeUnit.SECONDS))
            r.filter { it.isFailure }.forEach { assertTrue((it.exceptionOrNull() as ProblemException).code in setOf(ErrorCode.REVISION_MISMATCH, ErrorCode.INVALID_STATE_TRANSITION, ErrorCode.VALIDATION_FAILED, ErrorCode.CONFLICT, ErrorCode.PROMISE_WINDOW_VIOLATION), r.toString()) }
        } finally { pool.shutdownNow() }
        // jede Buchung steht in genau einem Plan (nie doppelt, nie verwaist)
        for (booking in listOf(s.a.a.id, s.a.b.id)) {
            val holders = listOf(s.runA.runId, s.runB.runId).count { run -> s.a.api.runs.currentPlan(s.op, run).stops.any { st -> st.boardings.any { it.bookingId == booking } } }
            assertEquals(1, holders, "Buchung $booking steht genau einmal im Plan")
        }
    }

    @Test
    fun revisionsliste_absteigend_mit_ausnahmeweg_und_cursor() {
        val s = ReplanScene(cf)
        val ex = ReplanException(setOf(ReplanRule.PROMISE_WINDOW), "Fahrgast A stimmt telefonisch einer früheren Ankunft zu")
        val bad = listOf(StopChange(StopChangeKind.MOVE, s.stopId(s.runA.runId, 2, 1), 3))
        s.commit.replan(s.op, s.runA.runId, s.cmd(bad, base = 2, reason = "erste Änderung", exception = ex))
        s.commit.replan(s.op, s.runA.runId, s.cmd(listOf(StopChange(StopChangeKind.MOVE, s.stopId(s.runA.runId, 3, 1), 3)), base = 3, reason = "zweite Änderung"))
        val all = s.commit.revisions(s.op, s.runA.runId, 10)
        assertEquals(listOf(4L, 3L, 2L), all.map { it.planRevision })
        assertEquals(listOf("zweite Änderung", "erste Änderung", null), all.map { it.reason })
        assertEquals(setOf(ReplanRule.PROMISE_WINDOW), all[1].exception!!.rules); assertTrue(all[0].exception == null)
        assertEquals("system:dispatch", all[2].createdBy)   // Ausgangsrevision aus dem Planungslauf, ohne Begründung
        assertEquals(listOf(3L), s.commit.revisions(s.op, s.runA.runId, 1, before = 4).map { it.planRevision })
        assertEquals(emptyList<Long>(), s.commit.revisions(s.op, s.runA.runId, 5, before = 2).map { it.planRevision })
    }

    @Test
    fun einfuegen_aus_unbekanntem_oder_eigenem_umlauf_ist_422() {
        val s = ReplanScene(cf)
        val own = StopChange(StopChangeKind.INSERT, s.stopId(s.runA.runId, 2, 0), 1, s.a.a.id)
        assertEquals("/changes/0/route_stop_id", rejected(ErrorCode.VALIDATION_FAILED) { s.commit.replan(s.op, s.runA.runId, s.cmd(listOf(own), base = 2)) }.violations.single().pointer)
        val unknown = StopChange(StopChangeKind.INSERT, UUID.randomUUID(), 1, s.a.a.id)
        assertEquals("/changes/0/route_stop_id", rejected(ErrorCode.VALIDATION_FAILED) { s.commit.replan(s.op, s.runB.runId, s.cmd(listOf(unknown), base = 1)) }.violations.single().pointer)
        // nur der Einstieg übernommen: der Plan wäre ungültig (Einstieg ohne Ausstieg), nichts wird gespeichert
        val half = listOf(s.transferToB(s.a.a.id).first())
        rejected(ErrorCode.VALIDATION_FAILED) { s.commit.replan(s.op, s.runB.runId, s.cmd(half, base = 1)) }
        unchanged(s, s.runA.runId, s.runB.runId)
    }
}

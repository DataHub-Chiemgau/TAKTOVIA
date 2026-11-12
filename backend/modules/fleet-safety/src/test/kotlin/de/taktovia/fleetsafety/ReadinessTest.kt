package de.taktovia.fleetsafety

import de.taktovia.fleetsafety.api.BlockOutcome
import de.taktovia.fleetsafety.api.BlockReason
import de.taktovia.fleetsafety.api.BlockSource
import de.taktovia.fleetsafety.api.BlockStatus
import de.taktovia.fleetsafety.api.DispatchRefusal
import de.taktovia.fleetsafety.api.DispatchVerdict
import de.taktovia.fleetsafety.api.IssueRelease
import de.taktovia.fleetsafety.api.ReleaseBlocker
import de.taktovia.fleetsafety.api.ReleaseOutcome
import de.taktovia.fleetsafety.api.ReleaseRevokeReason
import de.taktovia.fleetsafety.api.ReleaseState
import de.taktovia.fleetsafety.api.SetBlock
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * WP-M3-08 (Sperr- und Freigabekern) unter der Runtime-Rolle gegen die echte Datenbank, ohne Mocks. Positiv (Freigabe bei erfüllten Bedingungen), negativ (je fehlende Bedingung ein Fall,
 * abgelaufene Freigabe, Freigabe für fremde Schicht, fremder Mandant 404) und konkurrierend (Sperre und Dispositionsprüfung in parallelen Transaktionen, Qualifikationsentzug parallel
 * zur Freigabe, gleichzeitige gleiche Sperre).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReadinessTest {
    private lateinit var fx: FleetFixture
    private lateinit var w: ReadinessWorld

    @BeforeAll fun up() { fx = FleetFixture(); w = ReadinessWorld(fx) }
    @AfterAll fun down() = fx.close()

    private val readiness get() = fx.fleet.readiness
    private fun problem(block: () -> Unit): ProblemException = assertThrows(ProblemException::class.java, block)
    private fun issue(r: ReadinessWorld.Ready) = readiness.issueRelease(w.safetyService(), IssueRelease(r.vehicle, r.shift, r.inspection))
    private fun refused(o: ReleaseOutcome) = (o as ReleaseOutcome.Refused).missing
    private fun issued(o: ReleaseOutcome) = (o as ReleaseOutcome.Issued)
    private fun verdict(r: ReadinessWorld.Ready, expected: Long? = null, at: Instant = Instant.now()) = readiness.assertDispatchable(w.ctx("dispatcher"), r.vehicle, r.shift, expected, at)
    private fun safetyRevision(v: UUID) = fx.count("SELECT safety_revision FROM taktovia.vehicle WHERE id = ?", v)

    @Test
    fun freigabe_bei_erfuellten_bedingungen() {
        val r = w.ready()
        assertTrue(fx.count("SELECT count(*) FROM taktovia.vehicle WHERE id = ? AND active_block", r.vehicle) == 1, "neues Fahrzeug ist bis zur Freigabe nicht disponierbar (V1-Vorgabe)")
        assertTrue(verdict(r) is DispatchVerdict.Refused)
        val before = safetyRevision(r.vehicle)
        val out = issued(issue(r))
        assertFalse(out.replayed)
        val rel = out.release
        assertEquals(ReleaseState.ISSUED, rel.state); assertEquals(before.toLong(), rel.safetyRevision); assertEquals(r.shift, rel.shiftId); assertEquals(r.inspection, rel.inspectionId)
        assertEquals(1L, rel.qualificationRevision)
        // gültig bis Schichtende (8 h) und höchstens 16 h
        assertTrue(rel.validUntil.isAfter(Instant.now().plusSeconds(7 * 3600)) && rel.validUntil.isBefore(Instant.now().plusSeconds(9 * 3600)))
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.vehicle WHERE id = ? AND active_block", r.vehicle))
        assertEquals(before, safetyRevision(r.vehicle), "die Erteilung ändert die safety_revision nicht")
        val d = verdict(r, expected = before.toLong()) as DispatchVerdict.Dispatchable
        assertEquals(rel.id, d.releaseId)
        // Audit mit Akteur, Grund/Bezug und Zeitpunkt; Ereignis in derselben Transaktion
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='fleet.release.issued' AND object_ref=?", w.tenant.value, "resource_release:${rel.id}"))
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND event_type='fleet.release_issued' AND aggregate_id=?", w.tenant.value, rel.id))
        // Wiederholung: dieselbe Freigabe, keine zweite
        val again = issued(issue(r))
        assertTrue(again.replayed); assertEquals(rel.id, again.release.id)
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.resource_release WHERE inspection_ref = ?", r.inspection))
        // Freigabe nur für Dienstkonto, Werkstatt, Betriebsleitung
        assertEquals(403, problem { readiness.issueRelease(w.ctx("driver"), IssueRelease(r.vehicle, r.shift, r.inspection)) }.code.status)
    }

    @Test
    fun je_fehlende_bedingung_keine_freigabe() {
        // keine Prüfung
        var r = w.ready(); assertEquals(listOf(ReleaseBlocker.NO_INSPECTION), refused(readiness.issueRelease(w.safetyService(), IssueRelease(r.vehicle, r.shift, UUID.randomUUID()))))
        // Prüfung läuft noch
        r = w.ready(); val foreignInspection = w.ready().inspection
        assertEquals(listOf(ReleaseBlocker.NO_INSPECTION), refused(readiness.issueRelease(w.safetyService(), IssueRelease(r.vehicle, r.shift, foreignInspection))), "Prüfung einer anderen Kombination zählt nicht")
        val v2 = w.vehicle(); val d2 = w.driver(); val s2 = w.shift(d2, v2)
        assertEquals(listOf(ReleaseBlocker.INSPECTION_OPEN), refused(readiness.issueRelease(w.safetyService(), IssueRelease(v2, s2, w.inspection(s2, v2, d2, status = "IN_PROGRESS")))))
        // Prüfung nicht bestanden
        val v3 = w.vehicle(); val d3 = w.driver(); val s3 = w.shift(d3, v3)
        assertEquals(listOf(ReleaseBlocker.NO_INSPECTION), refused(readiness.issueRelease(w.safetyService(), IssueRelease(v3, s3, w.inspection(s3, v3, d3, outcome = "BLOCKED")))))
        // Prüfung eines anderen Fahrers derselben Schicht
        val v4 = w.vehicle(); val d4 = w.driver(); val s4 = w.shift(d4, v4)
        assertEquals(listOf(ReleaseBlocker.NO_INSPECTION), refused(readiness.issueRelease(w.safetyService(), IssueRelease(v4, s4, w.inspection(s4, v4, w.driver())))))
        // Qualifikation fehlt bzw. entzogen
        val v5 = w.vehicle(); val d5 = w.driver(qualified = false); val s5 = w.shift(d5, v5)
        val i5 = w.inspection(s5, v5, d5)
        assertEquals(listOf(ReleaseBlocker.QUALIFICATION_MISSING), refused(readiness.issueRelease(w.safetyService(), IssueRelease(v5, s5, i5))))
        w.qualify(d5, revision = 1, seq = 1); w.qualify(d5, revision = 2, seq = 2, status = "REVOKED")
        assertEquals(listOf(ReleaseBlocker.QUALIFICATION_MISSING), refused(readiness.issueRelease(w.safetyService(), IssueRelease(v5, s5, i5))), "entzogene Qualifikation")
        // Schicht beendet bzw. nicht angetreten: keine gültige Schicht
        val v6 = w.vehicle(); val d6 = w.driver(); val s6 = w.shift(d6, v6, state = "ENDED")
        assertEquals(listOf(ReleaseBlocker.VEHICLE_CHANGED), refused(readiness.issueRelease(w.safetyService(), IssueRelease(v6, s6, w.inspection(s6, v6, d6)))))
        // aktive Sperre je Quelle
        val v7 = w.vehicle(); val d7 = w.driver(); val s7 = w.shift(d7, v7); val i7 = w.inspection(s7, v7, d7)
        readiness.setBlock(w.ctx("dispatcher"), SetBlock(v7, BlockSource.OPERATIONS, BlockReason.MANUAL))
        assertEquals(listOf(ReleaseBlocker.OPERATIONAL_BLOCK), refused(readiness.issueRelease(w.safetyService(), IssueRelease(v7, s7, i7))))
        readiness.setBlock(w.safetyService(), SetBlock(v7, BlockSource.SAFETY, BlockReason.DEFECT_CRITICAL, "defect-1"))
        assertEquals(listOf(ReleaseBlocker.DEFECT_BLOCK, ReleaseBlocker.OPERATIONAL_BLOCK), refused(readiness.issueRelease(w.safetyService(), IssueRelease(v7, s7, i7))))
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.resource_release WHERE vehicle_id = ?", v7))
    }

    @Test
    fun sperre_widerruft_im_selben_commit_jede_freigabe_und_erhoeht_die_revision() {
        val r = w.ready(); issue(r)
        val before = safetyRevision(r.vehicle)
        val out = readiness.setBlock(w.ctx("dispatcher"), SetBlock(r.vehicle, BlockSource.OPERATIONS, BlockReason.OPERATIONAL_DISRUPTION, "ticket-7"))
        assertTrue(out.created); assertEquals(before + 1L, out.safetyRevision); assertEquals(1, out.revokedReleaseIds.size)
        assertEquals(before + 1, safetyRevision(r.vehicle)); assertEquals(1, fx.count("SELECT count(*) FROM taktovia.vehicle WHERE id = ? AND active_block", r.vehicle))
        assertEquals(ReleaseState.REVOKED, readiness.release(w.ctx("dispatcher"), out.revokedReleaseIds.single()).state)
        assertEquals("BLOCK_SET", fx.strings("SELECT revoke_reason FROM taktovia.resource_release WHERE id = ?", out.revokedReleaseIds.single()).single())
        val v = verdict(r) as DispatchVerdict.Refused
        assertTrue(DispatchRefusal.VEHICLE_BLOCKED in v.reasons && DispatchRefusal.RELEASE_REVOKED in v.reasons, v.reasons.toString())
        // Audit: Sperre mit Akteur, Quelle, Grund; Widerruf der Freigabe; Ereignisse
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='fleet.vehicle.blocked' AND object_ref=? AND actor_ref LIKE 'user:%'", w.tenant.value, "vehicle:${r.vehicle}"))
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='fleet.release.revoked' AND object_ref=?", w.tenant.value, "resource_release:${out.revokedReleaseIds.single()}"))
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND event_type='fleet.vehicle_blocked' AND aggregate_id=?", w.tenant.value, out.block.id))
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND event_type='fleet.release_revoked' AND aggregate_id=?", w.tenant.value, out.revokedReleaseIds.single()))
        // eine neue Freigabe aus derselben Prüfung gibt es nicht (nur aus einer neuen Prüfung)
        assertEquals(409, problem { issue(r) }.code.status)
    }

    @Test
    fun dieselbe_sperre_ist_idempotent_ohne_zweiten_revisionssprung() {
        val v = w.vehicle(); val cmd = SetBlock(v, BlockSource.SAFETY, BlockReason.INSPECTION_FAILED, "inspection-9")
        val first = readiness.setBlock(w.safetyService(), cmd); val rev = safetyRevision(v)
        val second = readiness.setBlock(w.safetyService(), cmd)
        assertTrue(first.created); assertFalse(second.created); assertEquals(first.block.id, second.block.id)
        assertEquals(rev, safetyRevision(v)); assertEquals(1, fx.count("SELECT count(*) FROM taktovia.vehicle_block WHERE vehicle_id = ?", v))
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND event_type='fleet.vehicle_blocked' AND payload->>'vehicle_id'=?", w.tenant.value, v.toString()))
        // anderer Bezug oder anderer Grund: eigener, unabhängiger Sperrgrund
        assertTrue(readiness.setBlock(w.safetyService(), SetBlock(v, BlockSource.SAFETY, BlockReason.INSPECTION_FAILED, "inspection-10")).created)
        assertEquals(rev + 1, safetyRevision(v), "ein weiterer Grund, ein weiterer Sprung")
    }

    @Test
    fun mehrere_sperrgruende_sind_unabhaengig() {
        val v = w.vehicle()
        val ops = readiness.setBlock(w.ctx("dispatcher"), SetBlock(v, BlockSource.OPERATIONS, BlockReason.MANUAL)).block
        val safety = readiness.setBlock(w.safetyService(), SetBlock(v, BlockSource.SAFETY, BlockReason.DEFECT_CRITICAL, "defect-5")).block
        assertEquals(2, readiness.activeBlocks(w.ctx("dispatcher"), v).size)
        val lift = readiness.liftOperationalBlock(w.ctx("dispatcher"), v, ops.id)
        assertTrue(lift.stillBlocked); assertEquals(BlockStatus.LIFTED, lift.block.status)
        assertEquals(listOf(safety.id), readiness.activeBlocks(w.ctx("dispatcher"), v).map { it.id })
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.vehicle WHERE id = ? AND active_block", v), "Aufheben eines Grundes hebt den anderen nicht auf")
        // Sicherheitssperre: nicht durch die Disposition, nicht erneut, nicht ohne Entscheidung
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, problem { readiness.liftOperationalBlock(w.ctx("dispatcher"), v, safety.id) }.code)
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, problem { readiness.liftOperationalBlock(w.ctx("dispatcher"), v, ops.id) }.code, "aufgehobene Sperre wird nie wieder geöffnet")
        assertEquals(ErrorCode.CONFLICT, problem { readiness.liftSafetyBlock(w.ctx("workshop"), v, safety.id, UUID.randomUUID()) }.code)
        assertEquals(403, problem { readiness.liftSafetyBlock(w.ctx("dispatcher"), v, safety.id, UUID.randomUUID()) }.code.status)
        val rev = safetyRevision(v)
        val done = readiness.liftSafetyBlock(w.ctx("workshop"), v, safety.id, w.releaseDecision(v))
        assertFalse(done.stillBlocked); assertEquals(rev + 1L, done.safetyRevision.toLong())
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.vehicle WHERE id = ? AND active_block", v))
        // Aufheben erteilt keine Freigabe
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.resource_release WHERE vehicle_id = ?", v))
        // Quelle und Grund passen zusammen; Sicherheitssperre setzt nur der Server
        assertThrows(IllegalArgumentException::class.java) { SetBlock(v, BlockSource.SAFETY, BlockReason.MANUAL) }
        assertEquals(403, problem { readiness.setBlock(w.ctx("dispatcher"), SetBlock(v, BlockSource.SAFETY, BlockReason.DEFECT_CRITICAL, "x")) }.code.status)
        assertEquals(403, problem { readiness.setBlock(w.ctx("driver"), SetBlock(v, BlockSource.OPERATIONS, BlockReason.MANUAL)) }.code.status)
    }

    @Test
    fun veraltete_safety_revision_und_abgelaufene_oder_fremde_freigabe_scheitern() {
        val r = w.ready(); issue(r); val rev = safetyRevision(r.vehicle).toLong()
        assertTrue(verdict(r, expected = rev) is DispatchVerdict.Dispatchable)
        assertEquals(listOf(DispatchRefusal.STALE_SAFETY_REVISION), (verdict(r, expected = rev - 1) as DispatchVerdict.Refused).reasons)
        // nach Ablauf (valid_until) und nach Schichtende
        val late = Instant.now().plusSeconds(9 * 3600)
        assertEquals(setOf(DispatchRefusal.NO_VALID_SHIFT, DispatchRefusal.RELEASE_EXPIRED), (verdict(r, at = late) as DispatchVerdict.Refused).reasons.toSet())
        // Freigabe für eine andere Schicht desselben Fahrzeugs gilt nicht
        val otherShift = w.shift(w.driver(), w.vehicle())
        val foreign = (readiness.assertDispatchable(w.ctx("dispatcher"), r.vehicle, otherShift, null, Instant.now()) as DispatchVerdict.Refused).reasons
        assertTrue(DispatchRefusal.NO_VALID_SHIFT in foreign && DispatchRefusal.NO_RELEASE in foreign, foreign.toString())
        // Fahrzeug unbekannt oder fremder Mandant
        assertEquals(listOf(DispatchRefusal.UNKNOWN_VEHICLE), (readiness.assertDispatchable(w.ctx("dispatcher"), UUID.randomUUID(), r.shift, null, Instant.now()) as DispatchVerdict.Refused).reasons)
        // Widerruf von Hand
        val rv = readiness.revokeRelease(w.ctx("dispatcher"), fx.strings("SELECT id::text FROM taktovia.resource_release WHERE inspection_ref = ?", r.inspection).single().let(UUID::fromString), ReleaseRevokeReason.MANUAL)
        assertEquals(ReleaseState.REVOKED, rv.state)
        assertTrue(DispatchRefusal.RELEASE_REVOKED in (verdict(r) as DispatchVerdict.Refused).reasons)
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, problem { readiness.revokeRelease(w.ctx("dispatcher"), rv.id, ReleaseRevokeReason.MANUAL) }.code)
        // Datenbank: ein widerrufener Datensatz bleibt unveränderlich, auch für die Administration
        val e = assertThrows(Exception::class.java) { fx.exec("UPDATE taktovia.resource_release SET revoked_at = NULL WHERE id = ?", rv.id) }
        assertTrue(generateSequence<Throwable>(e) { it.cause }.any { it.message?.contains("revoked once") == true })
    }

    @Test
    fun fremder_mandant_ist_404() {
        val other = ReadinessWorld(fx); val foreign = other.ready(); val r = w.ready()
        val ctx = w.ctx("dispatcher")
        assertEquals(404, problem { readiness.setBlock(ctx, SetBlock(foreign.vehicle, BlockSource.OPERATIONS, BlockReason.MANUAL)) }.code.status)
        assertEquals(404, problem { readiness.activeBlocks(ctx, foreign.vehicle) }.code.status)
        assertEquals(404, problem { readiness.issueRelease(w.safetyService(), IssueRelease(foreign.vehicle, foreign.shift, foreign.inspection)) }.code.status)
        issue(r)   // der eigene Mandant bleibt arbeitsfähig
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.vehicle_block WHERE vehicle_id = ?", foreign.vehicle))
    }

    @Test
    fun sperre_und_dispositionspruefung_parallel_nie_zusage_auf_gesperrtes_fahrzeug() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(14) { i ->
                val r = w.ready(); issue(r)
                val rev = safetyRevision(r.vehicle).toLong()
                val start = CountDownLatch(1)
                val commit = AtomicReference<Instant>(); val blockDone = AtomicReference<Instant>(); val seen = AtomicReference<DispatchVerdict>()
                val dispo = pool.submit {
                    start.await()
                    // wie der Hold-Commit: Prüfung unter der gemeinsamen Zeilensperre, danach eine kurze „Zusage“-Arbeit, dann Commit
                    val v = fx.ctxTx.run(w.ctx("dispatcher")) { j -> readiness.assertDispatchable(j, r.vehicle, r.shift, rev, Instant.now()).also { Thread.sleep(35) } }
                    commit.set(Instant.now()); seen.set(v)
                }
                val block = pool.submit {
                    start.await(); Thread.sleep((i % 5) * 9L)
                    readiness.setBlock(w.ctx("dispatcher"), SetBlock(r.vehicle, BlockSource.OPERATIONS, BlockReason.OPERATIONAL_DISRUPTION, "race-$i")); blockDone.set(Instant.now())
                }
                start.countDown(); dispo.get(20, TimeUnit.SECONDS); block.get(20, TimeUnit.SECONDS)
                when (val v = seen.get()) {
                    is DispatchVerdict.Dispatchable -> assertFalse(blockDone.get().isBefore(commit.get()), "Zusage nur, wenn die Sperre erst nach dem Commit der Zusage wirksam wurde (Lauf $i)")
                    is DispatchVerdict.Refused -> assertTrue(v.reasons.any { it == DispatchRefusal.VEHICLE_BLOCKED || it == DispatchRefusal.STALE_SAFETY_REVISION }, "Lauf $i: ${v.reasons}")
                    else -> throw AssertionError("kein Ergebnis")
                }
                // nach beiden Transaktionen gilt in jeder Serialisierung: gesperrt, Freigabe widerrufen, nicht disponierbar
                assertTrue(verdict(r) is DispatchVerdict.Refused)
            }
        } finally { pool.shutdownNow() }
    }

    @Test
    fun qualifikationsentzug_parallel_zur_freigabe_in_jeder_reihenfolge_nicht_disponierbar() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(10) { i ->
                val r = w.ready()
                val start = CountDownLatch(1)
                val grant = pool.submit<ReleaseOutcome> { start.await(); Thread.sleep((i % 4) * 8L); issue(r) }
                val revoke = pool.submit {
                    start.await()
                    // Entzug wie im Fahrerstamm: Fahrerzeile sperren, neue Revision, Nummer erhöhen (eine Transaktion)
                    fx.tx.run(w.tenant) { j ->
                        j.queryForList("SELECT id FROM taktovia.driver WHERE id = ? FOR UPDATE", r.driver)
                        j.update("INSERT INTO taktovia.qualification_revision(tenant_id,id,driver_id,qualification_code,revision,valid_from,approval_status,driver_seq) VALUES (taktovia.current_tenant(),?,?,'PBEFG',2,now() - interval '30 days','REVOKED',2)", UUID.randomUUID(), r.driver)
                        j.update("UPDATE taktovia.driver SET qualification_seq = 2 WHERE id = ?", r.driver)
                        Thread.sleep(25)
                    }
                }
                start.countDown(); val outcome = grant.get(20, TimeUnit.SECONDS); revoke.get(20, TimeUnit.SECONDS)
                if (outcome is ReleaseOutcome.Issued) assertEquals(1L, outcome.release.qualificationRevision, "Freigabe vor dem Entzug trägt den damaligen Stand")
                else assertEquals(listOf(ReleaseBlocker.QUALIFICATION_MISSING), (outcome as ReleaseOutcome.Refused).missing)
                val v = verdict(r) as DispatchVerdict.Refused
                assertTrue(DispatchRefusal.QUALIFICATION_MISSING in v.reasons, "Lauf $i: nach dem Entzug ist das Fahrzeug nie disponierbar (${v.reasons})")
            }
        } finally { pool.shutdownNow() }
    }

    @Test
    fun gleichzeitig_gleiche_sperre_erzeugt_genau_einen_grund() {
        val v = w.vehicle(); val pool = Executors.newFixedThreadPool(6)
        try {
            val start = CountDownLatch(1)
            val results = (1..6).map { pool.submit<BlockOutcome> { start.await(); readiness.setBlock(w.ctx("dispatcher"), SetBlock(v, BlockSource.OPERATIONS, BlockReason.OPERATIONAL_DISRUPTION, "same")) } }
            start.countDown()
            val outs = results.map { it.get(20, TimeUnit.SECONDS) }
            assertEquals(1, outs.count { it.created }); assertEquals(1, outs.map { it.block.id }.toSet().size)
            assertEquals(1, fx.count("SELECT count(*) FROM taktovia.vehicle_block WHERE vehicle_id = ?", v))
            assertNotNull(outs.first().block); assertEquals(2, safetyRevision(v), "Ausgangsstand 1 + genau ein Sprung")
        } finally { pool.shutdownNow() }
    }
}

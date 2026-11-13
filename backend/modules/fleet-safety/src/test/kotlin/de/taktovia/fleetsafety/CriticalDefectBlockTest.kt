package de.taktovia.fleetsafety

import de.taktovia.fleetsafety.api.AnswerInput
import de.taktovia.fleetsafety.api.BlockKind
import de.taktovia.fleetsafety.api.ChecklistTemplateInput
import de.taktovia.fleetsafety.api.DispatchRefusal
import de.taktovia.fleetsafety.api.DispatchVerdict
import de.taktovia.fleetsafety.api.InspectionOutcome
import de.taktovia.fleetsafety.api.InspectionView
import de.taktovia.fleetsafety.api.IssueRelease
import de.taktovia.fleetsafety.api.ReleaseBlocker
import de.taktovia.fleetsafety.api.ReleaseGate
import de.taktovia.fleetsafety.api.ReleaseOutcome
import de.taktovia.fleetsafety.api.SubmitInspection
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
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

/**
 * WP-M3-16 / LH-6.21-CHK-004 gegen die echte Datenbank, ohne Mocks: ein kritisches Nein löst im selben Commit eine Fahrzeug- oder Dienstsperre aus, verhindert die Einsatzfreigabe und erzeugt genau einen
 * Alarm. Positiv: ein unkritischer Mangel sperrt nicht. Negativ: kritisches Nein ohne Freigabe, Bereitmeldung/Disposition scheitert, Rollback bei Fehler im Alarmpfad. Konkurrierend (PH-AT-020):
 * Submit mit kritischem Nein parallel zur Dispositionsprüfung; zwei gleichzeitige Submits desselben Laufs ergeben genau ein Ergebnis und einen Alarm.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CriticalDefectBlockTest {
    private lateinit var fx: FleetFixture
    private lateinit var w: ReadinessWorld

    @BeforeAll fun up() { fx = FleetFixture(); w = ReadinessWorld(fx); template("KRIT", "VEHICLE"); template("DIENST", "SERVICE") }
    @AfterAll fun down() = fx.close()

    /** Vorlage mit einem kritischen Element (Sperrart nach Vorlage), einem unkritischen und einem zweiten kritischen mit der Gegenart. */
    private fun template(cls: String, kind: String, world: ReadinessWorld = w) {
        val steps = listOf(
            linkedMapOf<String, Any?>("step_code" to "BREMSEN", "title" to "Bremsen", "input_kind" to "OK_DEFECT_NA", "mandatory" to true, "safety_critical" to true, "block_kind" to kind),
            linkedMapOf<String, Any?>("step_code" to "SCHEIBEN", "title" to "Scheiben", "input_kind" to "OK_DEFECT_NA", "mandatory" to true, "safety_critical" to false),
        )
        val d = fx.fleet.checklistTemplates.create(world.ctx("fleet_safety"), ChecklistTemplateInput.parse(linkedMapOf("name" to "Prüfung $cls", "vehicle_class" to cls, "service_mode" to "STANDARD", "steps" to steps)))
        fx.fleet.checklistTemplates.publish(world.ctx("tenant_admin"), d.id, d.revision)
    }

    private class Run(val driver: UUID, val vehicle: UUID, val shift: UUID, val id: UUID, val ctx: de.taktovia.platform.context.RequestContext)

    private fun run(cls: String, world: ReadinessWorld = w): Run {
        val driver = world.driver(true, UUID.randomUUID()); val v = world.iw.vehicle(cls); val shift = world.iw.shift(driver, v); val ctx = world.iw.ctx(driver, "driver")
        return Run(driver, v, shift, world.iw.start(ctx, shift, v, "PRE_SHIFT").inspection.id, ctx)
    }

    private fun answers(brakes: String, glass: String = "OK") = listOf(
        AnswerInput("BREMSEN", brakes, null, if (brakes == "DEFECT") "Bremse schleift" else null, Instant.now().minusSeconds(30)),
        AnswerInput("SCHEIBEN", glass, null, if (glass == "DEFECT") "Riss" else null, Instant.now().minusSeconds(30)),
    )

    private fun submit(r: Run, a: List<AnswerInput>): InspectionView = fx.fleet.inspections.submit(r.ctx, r.id, fx.fleet.inspections.get(r.ctx, r.id).revision, SubmitInspection(a, Instant.now().minusSeconds(5), null))
    private fun alerts(vehicle: UUID) = fx.count("SELECT count(*) FROM taktovia.business_alert WHERE vehicle_id = ? AND alert_kind = 'VEHICLE_BLOCK_CRITICAL'", vehicle)
    private fun verdict(r: Run) = fx.fleet.readiness.assertDispatchable(w.ctx("dispatcher"), r.vehicle, r.shift, null, Instant.now())

    @Test
    fun kritisches_nein_sperrt_synchron_und_verweigert_freigabe() {
        val r = run("KRIT")
        // eine zuvor gültige Freigabe des Fahrzeugs für eine spätere Schicht wird durch die Sperre im selben Commit widerrufen
        val other = w.driver(); val later = UUID.randomUUID()
        fx.exec("INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state) VALUES (?,?,?,?,now() + interval '7 hours',now() + interval '15 hours','Europe/Berlin',0,'PUBLISHED')", w.tenant.value, later, other, r.vehicle)
        val prior = fx.fleet.readiness.issueRelease(w.safetyService(), IssueRelease(r.vehicle, later, w.inspection(later, r.vehicle, other))) as ReleaseOutcome.Issued
        val before = fx.count("SELECT safety_revision FROM taktovia.vehicle WHERE id = ?", r.vehicle)
        val done = submit(r, answers("DEFECT"))
        // Ergebnis gesperrt, keine Einsatzfreigabe, Sperre mit Vorgangsnummer, Fahrzeug nicht disponierbar
        assertEquals(InspectionOutcome.BLOCKED.name, done.outcome); assertNull(done.release); assertEquals(0, fx.count("SELECT count(*) FROM taktovia.resource_release WHERE inspection_ref = ?", r.id))
        val b = done.block!!; assertEquals(BlockKind.VEHICLE, b.kind); assertTrue(Regex("^SPE-[0-9]{4}-[0-9]{6,}$").matches(b.caseNumber)); assertEquals(listOf("BREMSEN"), b.stepCodes)
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.vehicle_block WHERE vehicle_id = ? AND status = 'ACTIVE' AND source = 'SAFETY' AND reason_code = 'INSPECTION_FAILED' AND reason_ref LIKE ? AND case_number = ?", r.vehicle, "${r.id}:%", b.caseNumber))
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.vehicle WHERE id = ? AND active_block", r.vehicle)); assertEquals(before + 1, fx.count("SELECT safety_revision FROM taktovia.vehicle WHERE id = ?", r.vehicle))
        assertEquals(DispatchRefusal.VEHICLE_BLOCKED, (verdict(r) as DispatchVerdict.Refused).reasons.first { it == DispatchRefusal.VEHICLE_BLOCKED })
        // Bereitmeldung/Freigabe scheitert: Server weist ab (Sperre), die Antwort nennt die fehlende Voraussetzung
        val refused = fx.fleet.readiness.issueRelease(w.safetyService(), IssueRelease(r.vehicle, r.shift, r.id)) as ReleaseOutcome.Refused; assertTrue(ReleaseBlocker.DEFECT_BLOCK in refused.missing)
        val req = fx.fleet.readiness.requirements(w.ctx("dispatcher"), r.vehicle, r.shift); assertEquals(ReleaseGate.BLOCKED, req.gate)
        // genau ein Alarm je Sperre, ohne Personendaten und Diagnosetexte; Ereignis Fahrzeug gesperrt aus der Prüfung
        assertEquals(1, alerts(r.vehicle))
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.business_alert WHERE vehicle_id = ? AND (reason_code <> 'INSPECTION_FAILED' OR ref_type <> 'inspection_run')", r.vehicle))
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id = ? AND event_type = 'fleet.vehicle_blocked' AND payload::text LIKE ?", w.tenant.value, "%${r.vehicle}%"))
        // der Fahrer kann die Sperre nicht aufheben (Sicherheitssperre: nur die technische Wiederfreigabe)
        val blockId = fx.fleet.readiness.activeBlocks(w.ctx("dispatcher"), r.vehicle).single().id
        assertThrows(de.taktovia.platform.problem.ProblemException::class.java) { fx.fleet.readiness.liftOperationalBlock(w.ctx("dispatcher"), r.vehicle, blockId) }
        assertThrows(de.taktovia.platform.problem.ProblemException::class.java) { fx.fleet.readiness.liftSafetyBlock(r.ctx, r.vehicle, blockId, UUID.randomUUID()) }
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.resource_release WHERE id = ? AND revoked_at IS NOT NULL AND revoke_reason = 'BLOCK_SET'", prior.release.id), "die frühere Freigabe ist im selben Commit widerrufen")
    }

    @Test
    fun unkritischer_mangel_sperrt_nicht() {
        val r = run("KRIT"); val done = submit(r, answers("OK", glass = "DEFECT"))
        assertEquals(InspectionOutcome.PASSED.name, done.outcome); assertNull(done.block); assertNotNull(done.release)
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.vehicle_block WHERE vehicle_id = ?", r.vehicle)); assertEquals(0, alerts(r.vehicle))
        assertTrue(verdict(r) is DispatchVerdict.Dispatchable)
    }

    @Test
    fun dienstsperre_verhindert_nur_die_freigabe_der_schicht() {
        val r = run("DIENST"); val before = fx.count("SELECT safety_revision FROM taktovia.vehicle WHERE id = ?", r.vehicle)
        val done = submit(r, answers("DEFECT"))
        assertEquals(InspectionOutcome.BLOCKED.name, done.outcome); assertEquals(BlockKind.SERVICE, done.block!!.kind); assertNull(done.release)
        // Dienstsperre: Schichtsperre statt Fahrzeugsperre; das Fahrzeug bleibt für andere Schichten nutzbar, die Safety-Revision ändert sich nicht
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.shift_block WHERE shift_id = ? AND status = 'ACTIVE' AND case_number = ?", r.shift, done.block!!.caseNumber))
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.vehicle_block WHERE vehicle_id = ?", r.vehicle)); assertEquals(before, fx.count("SELECT safety_revision FROM taktovia.vehicle WHERE id = ?", r.vehicle))
        assertEquals(1, alerts(r.vehicle))
        assertEquals(ReleaseGate.BLOCKED, fx.fleet.readiness.requirements(w.ctx("dispatcher"), r.vehicle, r.shift).gate)
        assertTrue((verdict(r) as DispatchVerdict.Refused).reasons.contains(DispatchRefusal.SHIFT_BLOCKED))
        // eine spätere Schicht desselben Fahrzeugs (andere Schicht, andere Prüfung) wird freigegeben: die Sperre gilt nur für die betroffene Schicht
        val d2 = w.driver(true, UUID.randomUUID()); val s2 = UUID.randomUUID()
        fx.exec("INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state) VALUES (?,?,?,?,now() + interval '7 hours',now() + interval '15 hours','Europe/Berlin',0,'PUBLISHED')", w.tenant.value, s2, d2, r.vehicle)
        val i2 = w.inspection(s2, r.vehicle, d2)
        assertTrue(fx.fleet.readiness.issueRelease(w.safetyService(), IssueRelease(r.vehicle, s2, i2)) is ReleaseOutcome.Issued, "andere Schicht desselben Fahrzeugs bleibt freigebbar")
        // die betroffene Schicht bleibt gesperrt, auch mit einer späteren bestandenen Prüfung (Aufheben: WP-M3-51)
        val again = w.inspection(r.shift, r.vehicle, r.driver)
        assertTrue((fx.fleet.readiness.issueRelease(w.safetyService(), IssueRelease(r.vehicle, r.shift, again)) as ReleaseOutcome.Refused).missing.contains(ReleaseBlocker.DEFECT_BLOCK))
    }

    @Test
    fun scheitert_der_alarmpfad_wird_auch_das_ergebnis_nicht_gespeichert() {
        // eigener Mandant: die fehlerhafte Alarmrichtlinie (Revisionen sind unveränderlich) bleibt auf ihn begrenzt
        val w2 = ReadinessWorld(fx); template("KRIT", "VEHICLE", w2)
        val r = run("KRIT", w2); val before = fx.count("SELECT safety_revision FROM taktovia.vehicle WHERE id = ?", r.vehicle)
        fx.exec("INSERT INTO taktovia.alert_policy_revision(tenant_id,id,revision_no,rules,created_by) VALUES (?,?,999,CAST(? AS jsonb),'user:t')", w2.tenant.value, UUID.randomUUID(), """{"rules":[{"kind":"VEHICLE_BLOCK_CRITICAL","priority":"BOGUS","recipient_role":"dispatcher"}]}""")
        assertThrows(RuntimeException::class.java) { submit(r, answers("DEFECT")) }
        // kein Zustand „Mangel gespeichert, Fahrzeug disponierbar“: Lauf offen, keine Antworten, keine Sperre, keine Revisionsänderung, kein Alarm
        assertEquals("IN_PROGRESS", fx.fleet.inspections.get(r.ctx, r.id).status)
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.inspection_answer WHERE inspection_id = ? AND answer = 'DEFECT'", r.id))
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.vehicle_block WHERE vehicle_id = ?", r.vehicle)); assertEquals(before, fx.count("SELECT safety_revision FROM taktovia.vehicle WHERE id = ?", r.vehicle))
        assertEquals(0, alerts(r.vehicle)); assertEquals(0, fx.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id = ? AND event_type = 'fleet.vehicle_blocked'", w2.tenant.value))
    }

    @Test
    fun submit_mit_kritischem_nein_parallel_zur_dispositionspruefung_keine_zusage() {
        repeat(8) { round ->
            // freigegebenes Fahrzeug (bestandene Vor-Schicht-Prüfung); die Folgeprüfung der Schicht liefert das kritische Nein
            val r = run("KRIT"); assertNotNull(submit(r, answers("OK")).release)
            val follow = w.iw.start(r.ctx, r.shift, r.vehicle, "FOLLOW_UP").inspection.id; val rev = fx.fleet.inspections.get(r.ctx, follow).revision
            val pool = Executors.newFixedThreadPool(2); val go = CountDownLatch(1)
            val sub = pool.submit { go.await(); fx.fleet.inspections.submit(r.ctx, follow, rev, SubmitInspection(answers("DEFECT"), Instant.now().minusSeconds(5), null)) }
            // Dispositionsprüfung (Zusage) in einer Mandantentransaktion unter der gemeinsamen Fahrzeugsperre, wie der Hold-Commit
            val check = pool.submit<DispatchVerdict> { go.await(); fx.ctxTx.run(w.ctx("dispatcher")) { j -> fx.fleet.readiness.assertDispatchable(j, r.vehicle, r.shift, null, Instant.now()) } }
            go.countDown(); sub.get(60, TimeUnit.SECONDS); val seen = check.get(60, TimeUnit.SECONDS); pool.shutdown()
            // die gleichzeitige Prüfung sah einen konsistenten Stand (vor oder nach der Sperre); danach gibt es in keiner Serialisierung eine Zusage auf das gesperrte Fahrzeug
            assertTrue(seen is DispatchVerdict.Dispatchable || seen is DispatchVerdict.Refused)
            assertTrue(verdict(r) is DispatchVerdict.Refused, "Runde $round: nach dem Submit keine Zusage auf das gesperrte Fahrzeug")
            assertEquals(1, fx.count("SELECT count(*) FROM taktovia.vehicle_block WHERE vehicle_id = ? AND status = 'ACTIVE'", r.vehicle))
            assertEquals(0, fx.count("SELECT count(*) FROM taktovia.resource_release WHERE vehicle_id = ? AND revoked_at IS NULL", r.vehicle), "Runde $round: jede Freigabe widerrufen")
            assertEquals(1, alerts(r.vehicle))
        }
    }

    @Test
    fun zwei_gleichzeitige_submits_desselben_laufs_genau_ein_ergebnis_ein_alarm() {
        repeat(5) {
            val r = run("KRIT"); val rev = fx.fleet.inspections.get(r.ctx, r.id).revision; val a = answers("DEFECT")
            val pool = Executors.newFixedThreadPool(2); val go = CountDownLatch(1)
            val results = (1..2).map { pool.submit<Result<InspectionView>> { go.await(); runCatching { fx.fleet.inspections.submit(r.ctx, r.id, rev, SubmitInspection(a, Instant.now().minusSeconds(5), null)) } } }
            go.countDown(); val done = results.map { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
            assertEquals(1, done.count { it.isSuccess }, "genau ein Ergebnis"); assertEquals(1, alerts(r.vehicle), "genau ein Alarm"); assertEquals(1, fx.count("SELECT count(*) FROM taktovia.vehicle_block WHERE vehicle_id = ?", r.vehicle))
            assertFalse(done.single { it.isSuccess }.getOrThrow().block == null)
        }
    }
}

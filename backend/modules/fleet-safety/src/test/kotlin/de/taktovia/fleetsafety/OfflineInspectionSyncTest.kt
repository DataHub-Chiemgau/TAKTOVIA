package de.taktovia.fleetsafety

import de.taktovia.fleetsafety.api.BlockReason
import de.taktovia.fleetsafety.api.BlockSource
import de.taktovia.fleetsafety.api.ChecklistTemplateInput
import de.taktovia.fleetsafety.api.DispatchVerdict
import de.taktovia.fleetsafety.api.ReleaseBlocker
import de.taktovia.fleetsafety.api.SetBlock
import de.taktovia.fleetsafety.api.SubmitInspection
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
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

/**
 * WP-M3-33 (Offline-Prüfung): Der Abschluss einer offline erfassten Prüfung trifft später ein. Gerätezeit (`completed_at`) und Serverempfangszeit (`received_at`) sind getrennt, die Freigabe
 * wird **beim Empfang** neu bewertet (Sperre, Schicht, Fahrzeug, Alter) und entsteht nie allein aus der Gerätezeit; der Abschluss wird bei Mehrfachübertragung genau einmal wirksam.
 * Echte Datenbank unter der Runtime-Rolle, echte parallele Transaktionen.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OfflineInspectionSyncTest {
    private lateinit var fx: FleetFixture

    @BeforeAll fun up() { fx = FleetFixture() }
    @AfterAll fun down() = fx.close()

    private fun problem(block: () -> Unit): ProblemException = org.junit.jupiter.api.Assertions.assertThrows(ProblemException::class.java, block)

    /** Offline abgeschlossener Lauf: Antworten mit Gerätezeit [deviceAgo] Sekunden vor dem Empfang. */
    private fun syncAt(w: ReadinessWorld, run: ReadinessWorld.Run, deviceAt: Instant, ctx: de.taktovia.platform.context.RequestContext = run.ctx) =
        fx.fleet.inspections.submit(ctx, run.id, fx.fleet.inspections.get(ctx, run.id).revision, SubmitInspection(w.allOk().map { it.copy(answeredAt = deviceAt.minusSeconds(5)) }, deviceAt, UUID.randomUUID()))

    private fun releases(run: ReadinessWorld.Run) = fx.count("SELECT count(*) FROM taktovia.resource_release WHERE inspection_ref = ?", run.id)
    private fun completedEvents(run: ReadinessWorld.Run) = fx.count("SELECT count(*) FROM taktovia.outbox WHERE event_type = 'inspection.completed' AND aggregate_id = ?", run.id)

    @Test
    fun offline_abschluss_haelt_geraetezeit_und_empfangszeit_getrennt_und_gibt_beim_empfang_frei() {
        val w = ReadinessWorld(fx); val run = w.startedRun()
        val deviceAt = Instant.now().minusSeconds(2 * 3600)
        val done = syncAt(w, run, deviceAt)
        assertEquals("PASSED", done.outcome)
        assertEquals(deviceAt.toEpochMilli(), done.completedAt!!.toEpochMilli(), "Gerätezeit bleibt als Gerätezeit gespeichert")
        assertTrue(done.receivedAt!!.isAfter(deviceAt.plusSeconds(2 * 3600 - 60)), "Serverempfangszeit stammt vom Server, nicht vom Gerät")
        assertNotNull(done.release, "Freigabe entsteht erst jetzt, beim Empfang, nach Neubewertung")
        assertEquals(1, releases(run))
        // vor dem Sync gab es keine Freigabe (kein Lauf-Abschluss, nichts am Server)
        val w2 = ReadinessWorld(fx); val open = w2.startedRun()
        assertEquals(0, releases(open)); assertEquals(listOf(ReleaseBlocker.INSPECTION_OPEN), fx.fleet.inspections.get(open.ctx, open.id).missing)
        assertTrue(fx.fleet.readiness.assertDispatchable(w2.ctx("dispatcher"), open.vehicle, open.shift, null, Instant.now()) !is DispatchVerdict.Dispatchable, "Fahrzeug bleibt serverseitig ohne Freigabe")
    }

    @Test
    fun offline_abschluss_danach_sperre_dann_sync_ergibt_keine_freigabe() {
        val w = ReadinessWorld(fx); val run = w.startedRun()
        // Sperre entsteht serverseitig, während das Gerät offline ist
        w.fx.fleet.readiness.setBlock(w.safetyService(), SetBlock(run.vehicle, BlockSource.SAFETY, BlockReason.DEFECT_CRITICAL, "defekt-offline"))
        val done = syncAt(w, run, Instant.now().minusSeconds(600))
        assertEquals("PASSED", done.outcome, "Das Prüfergebnis bleibt als Nachweis erhalten")
        assertNull(done.release, "keine Freigabe trotz bestandener Prüfung")
        assertTrue(ReleaseBlocker.DEFECT_BLOCK in done.missing, done.missing.toString())
        assertEquals(0, releases(run))
        assertTrue(fx.fleet.readiness.assertDispatchable(w.ctx("dispatcher"), run.vehicle, run.shift, null, Instant.now()) !is DispatchVerdict.Dispatchable)
    }

    @Test
    fun sperre_zwischenzeitlich_aufgehoben_der_gefaelschte_gerätezeitpunkt_umgeht_die_neubewertung_nicht() {
        val w = ReadinessWorld(fx); val run = w.startedRun()
        val block = w.fx.fleet.readiness.setBlock(w.safetyService(), SetBlock(run.vehicle, BlockSource.SAFETY, BlockReason.DEFECT_CRITICAL, "defekt-2")).block
        fx.fleet.readiness.liftSafetyBlock(w.ctx("workshop"), run.vehicle, block.id, w.releaseDecision(run.vehicle))
        // das Gerät behauptet eine Abschlusszeit nach der Aufhebung (innerhalb der Zukunftstoleranz); maßgeblich ist die frühere von Geräte- und Empfangszeit
        val done = syncAt(w, run, Instant.now().plusSeconds(30))
        assertNull(done.release, "eine Prüfung, die nicht nachweislich nach der Wiederfreigabeentscheidung abgeschlossen wurde, gibt nicht frei (AT-60-Regel)")
        assertTrue(ReleaseBlocker.NO_INSPECTION in done.missing, done.missing.toString())
        assertEquals(0, releases(run))
    }

    @Test
    fun sync_nach_schichtende_gibt_nicht_frei_aber_der_nachweis_bleibt() {
        val w = ReadinessWorld(fx); val run = w.startedRun()
        fx.exec("UPDATE taktovia.staff_shift SET state = 'ENDED', ended_at = now() WHERE id = ?", run.shift)
        val done = syncAt(w, run, Instant.now().minusSeconds(3600))
        assertEquals("COMPLETED", done.status); assertEquals("PASSED", done.outcome)
        assertNull(done.release)
        assertTrue(ReleaseBlocker.VEHICLE_CHANGED in done.missing, done.missing.toString())
        assertEquals(0, releases(run)); assertEquals(1, completedEvents(run), "der Nachweis wird genau einmal gemeldet")
    }

    @Test
    fun fahrzeugwechsel_vor_dem_sync_gibt_nicht_frei() {
        val w = ReadinessWorld(fx); val run = w.startedRun()
        val other = w.iw.vehicle("PKW")
        fx.exec("UPDATE taktovia.staff_shift SET vehicle_id = ? WHERE id = ?", other, run.shift)
        val done = syncAt(w, run, Instant.now().minusSeconds(900))
        assertNull(done.release)
        assertTrue(ReleaseBlocker.VEHICLE_CHANGED in done.missing, done.missing.toString())
        assertEquals(0, releases(run))
    }

    @Test
    fun zu_alter_offline_abschluss_wird_nicht_mit_frischer_gueltigkeit_freigegeben() {
        val w = ReadinessWorld(fx); val run = w.startedRun()
        // Gerätezeit 20 h vor dem Empfang, die Höchstgültigkeit der Freigabe beträgt 16 h ab Prüfabschluss
        val done = syncAt(w, run, Instant.now().minusSeconds(20 * 3600))
        assertEquals("PASSED", done.outcome)
        assertNull(done.release)
        assertTrue(ReleaseBlocker.INSPECTION_EXPIRED in done.missing, done.missing.toString())
        assertEquals(0, releases(run))
    }

    @Test
    fun neue_vorlagenrevision_nach_dem_start_aendert_den_laufstand_nicht() {
        val w = ReadinessWorld(fx); val run = w.startedRun()
        val before = fx.fleet.inspections.get(run.ctx, run.id)
        val author = w.ctx("fleet_safety")
        val published = fx.fleet.checklistTemplates.get(author, before.templateRevisionId)
        val draft = fx.fleet.checklistTemplates.newRevision(author, published.id, published.revision)
        fx.fleet.checklistTemplates.updateDraft(author, draft.id, draft.revision, ChecklistTemplateInput.parse(linkedMapOf("name" to "Neu", "vehicle_class" to "PKW", "service_mode" to "STANDARD",
            "steps" to listOf(linkedMapOf("step_code" to "NEU_1", "title" to "Andere Frage", "input_kind" to "OK_DEFECT_NA", "mandatory" to true, "safety_critical" to false)))))
        fx.fleet.checklistTemplates.publish(w.ctx("tenant_admin"), draft.id, fx.fleet.checklistTemplates.get(author, draft.id).revision)
        // Entscheidung (Detailvertrag in docs): der Lauf bleibt an die beim Start bestimmte Revision gebunden; der Offline-Abschluss wird gegen sie bewertet, die neue Revision gilt für den nächsten Lauf
        val done = syncAt(w, run, Instant.now().minusSeconds(300))
        assertEquals(before.templateRevisionId, done.templateRevisionId); assertEquals(before.template.version, done.template.version)
        assertEquals("PASSED", done.outcome); assertNotNull(done.release)
    }

    @Test
    fun offline_begonnener_lauf_wird_nachtraeglich_gestartet_und_scheitert_an_anderer_vorlagenrevision_ohne_wirkung() {
        val w = ReadinessWorld(fx)
        val old = w.template
        val author = w.ctx("fleet_safety")
        val published = fx.fleet.checklistTemplates.get(author, old)
        val draft = fx.fleet.checklistTemplates.newRevision(author, published.id, published.revision)
        fx.fleet.checklistTemplates.updateDraft(author, draft.id, draft.revision, ChecklistTemplateInput.parse(linkedMapOf("name" to "Neu", "vehicle_class" to "PKW", "service_mode" to "STANDARD",
            "steps" to listOf(linkedMapOf("step_code" to "NEU_1", "title" to "Andere Frage", "input_kind" to "OK_DEFECT_NA", "mandatory" to true, "safety_critical" to false)))))
        fx.fleet.checklistTemplates.publish(w.ctx("tenant_admin"), draft.id, fx.fleet.checklistTemplates.get(author, draft.id).revision)
        // Das Gerät hat die alte Revision (STEP_1..3) lokal; der nachträgliche Start am Server wählt die aktuelle Revision, die alten Antworten passen nicht
        val run = w.startedRun()
        val e = problem { syncAt(w, run, Instant.now().minusSeconds(300)) }
        assertEquals(ErrorCode.VALIDATION_FAILED, e.code)
        assertEquals("IN_PROGRESS", fx.fleet.inspections.get(run.ctx, run.id).status, "keine Teilwirkung: der Lauf bleibt offen")
        assertEquals(0, releases(run)); assertEquals(0, completedEvents(run))
    }

    @Test
    fun mehrfachuebertragung_wird_genau_einmal_wirksam() {
        val w = ReadinessWorld(fx); val run = w.startedRun()
        val rev = fx.fleet.inspections.get(run.ctx, run.id).revision
        val deviceEvent = UUID.randomUUID(); val at = Instant.now().minusSeconds(300)
        val cmd = { SubmitInspection(w.allOk().map { it.copy(answeredAt = at.minusSeconds(5)) }, at, deviceEvent) }
        fx.fleet.inspections.submit(run.ctx, run.id, rev, cmd())
        // zweite Übertragung derselben Prüfung (z. B. nach verlorener Antwort): keine zweite Wirkung
        assertEquals(ErrorCode.IMMUTABLE_RECORD, problem { fx.fleet.inspections.submit(run.ctx, run.id, rev + 1, cmd()) }.code)
        assertEquals(ErrorCode.IMMUTABLE_RECORD, problem { fx.fleet.inspections.submit(run.ctx, run.id, rev, cmd()) }.code)
        assertEquals(1, releases(run)); assertEquals(1, completedEvents(run))
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE action='fleet.inspection.completed' AND object_ref=?", "inspection:${run.id}"))
    }

    @Test
    fun sync_derselben_pruefung_von_zwei_app_instanzen_wirkt_genau_einmal() {
        val w = ReadinessWorld(fx); val run = w.startedRun()
        val rev = fx.fleet.inspections.get(run.ctx, run.id).revision
        val n = 8; val pool = Executors.newFixedThreadPool(n); val start = CountDownLatch(1)
        val at = Instant.now().minusSeconds(120)
        val results = (1..n).map {
            pool.submit<Result<Unit>> {
                start.await()
                runCatching { fx.fleet.inspections.submit(run.ctx, run.id, rev, SubmitInspection(w.allOk().map { a -> a.copy(answeredAt = at.minusSeconds(5)) }, at, UUID.randomUUID())); Unit }
            }
        }
        start.countDown()
        val outcomes = results.map { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(1, outcomes.count { it.isSuccess }, "genau ein Abschluss wird wirksam")
        outcomes.filter { it.isFailure }.forEach { r ->
            val p = r.exceptionOrNull() as ProblemException
            assertTrue(p.code == ErrorCode.IMMUTABLE_RECORD || p.code == ErrorCode.REVISION_MISMATCH, p.code.toString())
        }
        assertEquals(1, releases(run)); assertEquals(1, completedEvents(run))
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.inspection_run WHERE id = ? AND status = 'COMPLETED'", run.id))
    }

    @Test
    fun gleichzeitige_sperre_und_offline_sync_ergeben_nie_freigabe_trotz_sperre() {
        repeat(6) {
            val w = ReadinessWorld(fx); val run = w.startedRun()
            val pool = Executors.newFixedThreadPool(2); val start = CountDownLatch(1)
            val sync = pool.submit { start.await(); syncAt(w, run, Instant.now().minusSeconds(60)) }
            val block = pool.submit { start.await(); fx.fleet.readiness.setBlock(w.safetyService(), SetBlock(run.vehicle, BlockSource.SAFETY, BlockReason.DEFECT_CRITICAL, "parallel")) }
            start.countDown(); sync.get(60, TimeUnit.SECONDS); block.get(60, TimeUnit.SECONDS); pool.shutdown()
            // Invariante: nach beiden Vorgängen gilt Sperre aktiv und keine gültige Freigabe (die Sperre widerruft jede Freigabe)
            assertEquals(0, fx.count("SELECT count(*) FROM taktovia.resource_release WHERE inspection_ref = ? AND revoked_at IS NULL", run.id), "keine gültige Freigabe neben aktiver Sperre")
            assertTrue(fx.fleet.readiness.assertDispatchable(w.ctx("dispatcher"), run.vehicle, run.shift, null, Instant.now()) !is DispatchVerdict.Dispatchable)
        }
    }
}

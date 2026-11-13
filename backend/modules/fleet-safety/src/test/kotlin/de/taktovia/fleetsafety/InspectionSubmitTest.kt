package de.taktovia.fleetsafety

import de.taktovia.fleetsafety.api.ChecklistTemplateInput
import de.taktovia.fleetsafety.api.DispatchVerdict
import de.taktovia.fleetsafety.api.ReleaseBlocker
import de.taktovia.fleetsafety.api.SubmitInspection
import de.taktovia.fleetsafety.api.BlockReason
import de.taktovia.fleetsafety.api.BlockSource
import de.taktovia.fleetsafety.api.SetBlock
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.ViolationCode
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
 * WP-M3-14 / LH-6.21-CHK-013 unter der Runtime-Rolle gegen die echte Datenbank. Originalabnahme: eine abgeschlossene Prüfung ist später vollständig und unverfälscht
 * rekonstruierbar. Positiv (AT-27, AT-32), negativ (fehlende Pflichtantwort, falscher Typ, Clientwerte, fremde Prüfung, Mutation nach Abschluss) und konkurrierend (zwei Abschlüsse,
 * Abschluss parallel zu einer Sperre). HTTP-Verhalten (428/412, Idempotenz) prüft `InspectionSubmitApiTest` in `app`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InspectionSubmitTest {
    private lateinit var fx: FleetFixture

    @BeforeAll fun up() { fx = FleetFixture() }
    @AfterAll fun down() = fx.close()

    private fun problem(block: () -> Unit): ProblemException = assertThrows(ProblemException::class.java, block)

    @Test
    fun abschluss_enthaelt_alle_pflichtangaben() {
        val w = ReadinessWorld(fx); val run = w.startedRun()
        val before = fx.fleet.inspections.get(run.ctx, run.id)
        assertEquals("IN_PROGRESS", before.status); assertEquals(listOf(ReleaseBlocker.INSPECTION_OPEN), before.missing)
        // AT-27: vollständige Prüfung, Fahrzeug darf starten
        val done = w.submit(run, w.allOk() )
        assertEquals("COMPLETED", done.status); assertEquals("PASSED", done.outcome)
        assertEquals("user:${run.driver}", done.completedBy)                              // Prüfer-ID aus der Sitzung
        assertEquals(run.vehicle, done.vehicleId); assertEquals(run.shift, done.shiftId)  // Fahrzeug-ID, Schicht-ID
        assertEquals(before.templateRevisionId, done.templateRevisionId); assertEquals(before.template.version, done.template.version)   // Vorlagenversion
        assertNotNull(done.completedAt); assertNotNull(done.receivedAt)                    // Zeitstempel Gerät und Server
        assertEquals(3, done.answers.size)                                                 // alle Einzelantworten
        assertEquals(before.revision + 1, done.revision)
        assertNotNull(done.release); assertTrue(done.missing.isEmpty())
        assertTrue(fx.fleet.readiness.assertDispatchable(w.ctx("dispatcher"), run.vehicle, run.shift, null, Instant.now()) is DispatchVerdict.Dispatchable)
        // Serverzeit stammt von der Datenbank, nicht aus der Gerätezeit; Prüfer in der Zeile
        assertEquals("user:${run.driver}", fx.strings("SELECT completed_by FROM taktovia.inspection_run WHERE id = ?", run.id).single())
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.inspection_run WHERE id = ? AND received_at >= completed_at", run.id))
        assertEquals(3, fx.count("SELECT count(*) FROM taktovia.inspection_answer WHERE inspection_id = ? AND step_id IS NOT NULL", run.id))
        // Audit mit Akteur, Ergebnis, ohne Freitext; Ereignis ohne Freitext und Personendetails
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='fleet.inspection.completed' AND object_ref=? AND actor_ref=?", w.tenant.value, "inspection:${run.id}", "user:${run.driver}"))
        val payload = fx.strings("SELECT payload::text FROM taktovia.outbox WHERE tenant_id=? AND event_type='inspection.completed' AND aggregate_id=?", w.tenant.value, run.id).single()
        val keys = tools.jackson.databind.json.JsonMapper.builder().build().readTree(payload).properties().map { it.key }.toSet()
        assertEquals(setOf("inspection_id", "vehicle_id", "shift_id", "kind", "outcome", "defect_step_count", "template_revision_id"), keys)
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.resource_release WHERE inspection_ref = ?", run.id))
    }

    @Test
    fun historie_zeigt_urspruengliche_fragen_nach_vorlagenaenderung() {
        val w = ReadinessWorld(fx); val run = w.startedRun()
        val done = w.submit(run, w.allOk().map { if (it.stepCode == "STEP_2") w.answer("STEP_2", "DEFECT", note = "Kratzer an der Tür") else it })
        val originalTitles = done.template.steps.map { it.title }
        // spätere Vorlagenänderung: neue Revision mit anderen Titeln und freigegeben (AT-32)
        val published = fx.fleet.checklistTemplates.get(w.ctx("fleet_safety"), done.templateRevisionId)
        val author = w.ctx("fleet_safety"); val draft = fx.fleet.checklistTemplates.newRevision(author, published.id, published.revision)
        fx.fleet.checklistTemplates.updateDraft(author, draft.id, draft.revision, ChecklistTemplateInput.parse(linkedMapOf("name" to "Neu", "vehicle_class" to "PKW", "service_mode" to "STANDARD",
            "steps" to listOf(linkedMapOf("step_code" to "NEU_1", "title" to "Ganz andere Frage", "input_kind" to "OK_DEFECT_NA", "mandatory" to true, "safety_critical" to false)))))
        fx.fleet.checklistTemplates.publish(w.ctx("tenant_admin"), draft.id, fx.fleet.checklistTemplates.get(author, draft.id).revision)
        val history = fx.fleet.inspections.get(w.ctx("dispatcher", subject = UUID.randomUUID()), run.id)
        assertEquals(originalTitles, history.template.steps.map { it.title }); assertEquals(done.templateRevisionId, history.templateRevisionId)
        assertEquals(listOf("OK", "DEFECT", "OK"), history.answers.sortedBy { it.stepCode }.map { it.answer }); assertEquals("Kratzer an der Tür", history.answers.single { it.stepCode == "STEP_2" }.note)
    }

    @Test
    fun fehlende_pflichtantwort_oder_falscher_eingabetyp_ergibt_422() {
        val w = ReadinessWorld(fx); val run = w.startedRun()
        fun violations(answers: List<de.taktovia.fleetsafety.api.AnswerInput>) = problem { w.submit(run, answers) }.also { assertEquals(ErrorCode.VALIDATION_FAILED, it.code) }.violations.map { it.pointer to it.code }
        assertEquals(listOf("/answers/STEP_3" to ViolationCode.REQUIRED), violations(w.allOk(2)), "fehlende Pflichtantwort mit Verweis auf das Element")
        assertTrue(("/answers/STEP_1/answer" to ViolationCode.NOT_IN_ENUM) in violations(listOf(w.answer("STEP_1", "MAYBE")) + w.allOk().drop(1)))
        assertTrue(("/answers/STEP_1/answer" to ViolationCode.NOT_IN_ENUM) in violations(listOf(w.answer("STEP_1", "NOT_CHECKABLE")) + w.allOk().drop(1)), "vierter Statuswert bis OP-16 abgewiesen")
        assertTrue(("/answers/STEP_1/number_value" to ViolationCode.INVALID_VALUE) in violations(listOf(w.answer("STEP_1", number = java.math.BigDecimal.TEN)) + w.allOk().drop(1)), "Wert außerhalb des Eingabetyps")
        assertTrue(("/answers/STEP_2/note" to ViolationCode.REQUIRED) in violations(listOf(w.answer("STEP_2", "DEFECT")) + w.allOk().filter { it.stepCode != "STEP_2" }), "Mangel ohne Beschreibung")
        assertTrue(("/answers/NOPE" to ViolationCode.NOT_IN_ENUM) in violations(w.allOk() + w.answer("NOPE")), "Element gehört nicht zur Vorlagenrevision des Laufs")
        // nichts davon hat etwas gespeichert oder den Lauf geschlossen
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.inspection_answer WHERE inspection_id = ?", run.id))
        assertEquals("IN_PROGRESS", fx.fleet.inspections.get(run.ctx, run.id).status)
        // Gerätezeit in der Zukunft wird abgewiesen
        assertEquals(ViolationCode.INVALID_VALUE, problem { fx.fleet.inspections.submit(run.ctx, run.id, 1, SubmitInspection(w.allOk(), Instant.now().plusSeconds(3600), null)) }.violations.single().code)
    }

    @Test
    fun fremde_pruefung_ergibt_404() {
        val w = ReadinessWorld(fx); val run = w.startedRun(); val other = w.startedRun()
        assertEquals(404, problem { w.submit(run, w.allOk(), ctx = other.ctx, revision = 1) }.code.status, "Fahrer einer anderen Schicht")
        val foreign = ReadinessWorld(fx)
        assertEquals(404, problem { fx.fleet.inspections.submit(foreign.iw.ctx(UUID.randomUUID(), "workshop", tenant = foreign.tenant), run.id, 1, SubmitInspection(w.allOk(), Instant.now(), null)) }.code.status, "anderer Mandant")
        assertEquals(403, problem { w.submit(run, w.allOk(), ctx = w.ctx("dispatcher"), revision = 1) }.code.status, "Disposition schließt keine Prüfung ab")
        assertEquals(404, problem { fx.fleet.inspections.submit(run.ctx, UUID.randomUUID(), 1, SubmitInspection(w.allOk(), Instant.now(), null)) }.code.status, "unbekannte Prüfung")
    }

    @Test
    fun clientwerte_pruefer_mandant_ergebnis_ignoriert_oder_abgewiesen() {
        val base = linkedMapOf<String, Any?>("answers" to listOf(linkedMapOf("step_code" to "STEP_1", "answer" to "OK", "answered_at" to Instant.now().toString())), "client_submitted_at" to Instant.now().toString())
        for (field in listOf("inspector_ref", "completed_by", "tenant_id", "outcome", "release", "vehicle_id", "shift_id", "status")) {
            val v = problem { SubmitInspection.parse(base + (field to "x")) }
            assertEquals(listOf("/$field" to ViolationCode.UNKNOWN_FIELD), v.violations.map { it.pointer to it.code }, field)
        }
        val inner = problem { SubmitInspection.parse(linkedMapOf("answers" to listOf(linkedMapOf("step_code" to "STEP_1", "answer" to "OK", "answered_at" to Instant.now().toString(), "outcome" to "PASSED")), "client_submitted_at" to Instant.now().toString())) }
        assertEquals("/answers/0/outcome", inner.violations.single().pointer)
        // Wirkung: Prüfer ist immer der Sitzungsinhaber
        val w = ReadinessWorld(fx); val run = w.startedRun()
        assertEquals("user:${run.driver}", w.submit(run, w.allOk()).completedBy)
    }

    @Test
    fun mutation_nach_abschluss_ueber_api_abgewiesen() {
        val w = ReadinessWorld(fx); val run = w.startedRun(); val done = w.submit(run, w.allOk())
        assertEquals(ErrorCode.IMMUTABLE_RECORD, problem { w.submit(run, w.allOk(), revision = done.revision) }.code, "zweiter Abschluss")
        // direkte Mutation (Datenbank, auch für die Administration)
        val e1 = assertThrows(Exception::class.java) { fx.exec("UPDATE taktovia.inspection_answer SET answer = 'DEFECT' WHERE inspection_id = ?", run.id) }
        val e2 = assertThrows(Exception::class.java) { fx.exec("DELETE FROM taktovia.inspection_answer WHERE inspection_id = ?", run.id) }
        val e3 = assertThrows(Exception::class.java) { fx.exec("UPDATE taktovia.inspection_run SET outcome = 'BLOCKED' WHERE id = ?", run.id) }
        listOf(e1, e2, e3).forEach { e -> assertTrue(generateSequence<Throwable>(e) { it.cause }.any { it.message?.contains("immutable") == true }, e.message) }
        assertEquals(3, fx.count("SELECT count(*) FROM taktovia.inspection_answer WHERE inspection_id = ? AND answer = 'OK'", run.id))
    }

    @Test
    fun kritischer_mangel_ergibt_gesperrt_ohne_freigabe_unkritischer_nicht() {
        val w = ReadinessWorld(fx)
        val soft = w.startedRun()   // STEP_2 ist nicht sicherheitskritisch
        val ok = w.submit(soft, listOf(w.answer("STEP_1"), w.answer("STEP_2", "DEFECT", note = "Kratzer"), w.answer("STEP_3")))
        assertEquals("PASSED", ok.outcome); assertNotNull(ok.release)
        val hard = w.startedRun()   // STEP_1 ist sicherheitskritisch
        val blocked = w.submit(hard, listOf(w.answer("STEP_1", "DEFECT", note = "Bremsflüssigkeit"), w.answer("STEP_2"), w.answer("STEP_3")))
        assertEquals("BLOCKED", blocked.outcome); assertNull(blocked.release)
        assertTrue(ReleaseBlocker.DEFECT_BLOCK in blocked.missing, "die Sperre aus dem kritischen Mangel steht als fehlende Voraussetzung da (WP-M3-16): ${blocked.missing}")
        assertNotNull(blocked.block, "Sperre mit Vorgangsnummer in derselben Transaktion")
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.resource_release WHERE inspection_ref = ?", hard.id), "keine Einsatzfreigabe nach kritischem Nein")
        assertTrue(fx.fleet.readiness.assertDispatchable(w.ctx("dispatcher"), hard.vehicle, hard.shift, null, Instant.now()) is DispatchVerdict.Refused)
    }

    @Test
    fun altschaeden_muessen_vor_dem_abschluss_quittiert_sein_und_nicht_qualifizierter_fahrer_erhaelt_keine_freigabe() {
        val w = ReadinessWorld(fx)
        val run = w.startedRun(); w.openDefect(run.vehicle)
        // der Altschaden bestand vor dem Lauf: Abschluss erst nach Quittierung
        val e = problem { w.submit(run, w.allOk()) }
        assertEquals(ErrorCode.CONFLICT, e.code); assertTrue(e.detail!!.startsWith("KNOWN_DEFECTS_UNACKNOWLEDGED"))
        val unqualified = w.startedRun(qualified = false)
        val done = w.submit(unqualified, w.allOk())
        assertEquals("PASSED", done.outcome); assertNull(done.release); assertEquals(listOf(ReleaseBlocker.QUALIFICATION_MISSING), done.missing)
    }

    @Test
    fun gleichzeitige_submits_genau_ein_abschluss() {
        val w = ReadinessWorld(fx); val run = w.startedRun(); val rev = fx.fleet.inspections.get(run.ctx, run.id).revision
        val pool = Executors.newFixedThreadPool(5)
        try {
            val start = CountDownLatch(1)
            val results = (1..5).map { pool.submit<Result<Any>> { start.await(); runCatching { fx.fleet.inspections.submit(run.ctx, run.id, rev, SubmitInspection(w.allOk(), Instant.now().minusSeconds(2), null)) } } }
            start.countDown()
            val outs = results.map { it.get(30, TimeUnit.SECONDS) }
            assertEquals(1, outs.count { it.isSuccess }, outs.toString())
            outs.filter { it.isFailure }.forEach { assertEquals(409, (it.exceptionOrNull() as ProblemException).code.status) }
            assertEquals(1, fx.count("SELECT count(*) FROM taktovia.resource_release WHERE inspection_ref = ?", run.id), "genau eine Freigabe")
            assertEquals(1, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='fleet.inspection.completed' AND object_ref=?", w.tenant.value, "inspection:${run.id}"))
            assertEquals(1, fx.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND event_type='inspection.completed' AND aggregate_id=?", w.tenant.value, run.id))
            assertEquals(3, fx.count("SELECT count(*) FROM taktovia.inspection_answer WHERE inspection_id = ?", run.id))
        } finally { pool.shutdownNow() }
    }

    @Test
    fun submit_parallel_zu_neuer_sperre_keine_freigabe() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(10) { i ->
                val w = ReadinessWorld(fx); val run = w.startedRun(); val rev = fx.fleet.inspections.get(run.ctx, run.id).revision
                val start = CountDownLatch(1)
                val submit = pool.submit<de.taktovia.fleetsafety.api.InspectionView> { start.await(); Thread.sleep((i % 3) * 6L); fx.fleet.inspections.submit(run.ctx, run.id, rev, SubmitInspection(w.allOk(), Instant.now().minusSeconds(2), null)) }
                val block = pool.submit { start.await(); fx.fleet.readiness.setBlock(w.ctx("dispatcher"), SetBlock(run.vehicle, BlockSource.OPERATIONS, BlockReason.OPERATIONAL_DISRUPTION, "race-$i")) }
                start.countDown(); val view = submit.get(30, TimeUnit.SECONDS); block.get(30, TimeUnit.SECONDS)
                assertEquals("COMPLETED", view.status)
                // in jeder Serialisierung gilt danach: keine wirksame Freigabe, Fahrzeug nicht disponierbar
                assertEquals(0, fx.count("SELECT count(*) FROM taktovia.resource_release WHERE inspection_ref = ? AND revoked_at IS NULL", run.id), "Lauf $i")
                assertTrue(fx.fleet.readiness.assertDispatchable(w.ctx("dispatcher"), run.vehicle, run.shift, null, Instant.now()) is DispatchVerdict.Refused, "Lauf $i")
            }
        } finally { pool.shutdownNow() }
    }
}

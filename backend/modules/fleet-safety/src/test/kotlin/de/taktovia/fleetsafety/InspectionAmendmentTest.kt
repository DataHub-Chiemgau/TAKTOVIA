package de.taktovia.fleetsafety

import de.taktovia.fleetsafety.api.AmendmentKind
import de.taktovia.fleetsafety.api.CreateInspectionAmendment
import de.taktovia.fleetsafety.api.DispatchVerdict
import de.taktovia.fleetsafety.api.SaveInspectionDraft
import de.taktovia.fleetsafety.api.SubmitInspection
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.ViolationCode
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
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

/**
 * WP-M3-15 / LH-6.21-CHK-014 unter der Runtime-Rolle gegen die echte Datenbank. Originalabnahme: nachträgliche Bearbeitung überschreibt niemals die ursprünglichen Antworten.
 * Positiv (Entwurf ändern, Nachtrag anlegen), negativ (direkte Änderung nach Abschluss, Nachtrag ohne Grund, unberechtigte Rolle, fremder Lauf) und konkurrierend (zwei gleichzeitige
 * Nachträge, Entwurf parallel zum Abschluss). Planungsannahme: berechtigt sind Werkstatt und Betriebsleitung.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InspectionAmendmentTest {
    private lateinit var fx: FleetFixture

    @BeforeAll fun up() { fx = FleetFixture() }
    @AfterAll fun down() = fx.close()

    private fun problem(block: () -> Unit): ProblemException = assertThrows(ProblemException::class.java, block)
    private fun draft(w: ReadinessWorld, run: ReadinessWorld.Run, rev: Long, vararg a: de.taktovia.fleetsafety.api.AnswerInput) =
        fx.fleet.inspections.saveDraft(run.ctx, run.id, rev, SaveInspectionDraft(a.toList()))
    private fun amend(w: ReadinessWorld, run: ReadinessWorld.Run, step: String = "STEP_2", reason: String = "Fehler bei der Eingabe", answer: String = "DEFECT", note: String? = "Kratzer", roles: Array<String> = arrayOf("workshop"),
                      kind: AmendmentKind = AmendmentKind.CORRECTION) =
        fx.fleet.inspections.amend(w.ctx(*roles), run.id, CreateInspectionAmendment(step, kind, answer, null, note, reason))

    @Test
    fun entwurf_aenderbar_und_nach_neustart_vorhanden() {
        val w = ReadinessWorld(fx); val run = w.startedRun()
        val v1 = draft(w, run, 1, w.answer("STEP_1"), w.answer("STEP_2", "DEFECT"))   // Entwurf darf unvollständig sein (Mangel ohne Beschreibung erst beim Abschluss verlangt)
        assertEquals(2L, v1.revision); assertEquals(listOf("STEP_1", "STEP_2"), v1.answers.map { it.stepCode }.sorted())
        // „App-Neustart“: der Entwurf ist beim frischen Lesen vorhanden
        val reread = fx.fleet.inspections.get(run.ctx, run.id)
        assertEquals(listOf("OK", "DEFECT"), reread.answers.sortedBy { it.stepCode }.map { it.answer }); assertEquals("IN_PROGRESS", reread.status)
        // vor dem Abschluss änderbar: zweite Fassung ersetzt, nicht angegebene Schritte bleiben
        val v2 = draft(w, run, 2, w.answer("STEP_2", "OK"))
        assertEquals(3L, v2.revision); assertEquals(mapOf("STEP_1" to "OK", "STEP_2" to "OK"), v2.answers.associate { it.stepCode to it.answer })
        // veraltete Revision: 412, nichts geändert; ungültiger Wert: 422; fremder Fahrer: 404
        assertEquals(ErrorCode.REVISION_MISMATCH, problem { draft(w, run, 2, w.answer("STEP_3")) }.code)
        assertEquals(ViolationCode.NOT_IN_ENUM, problem { draft(w, run, 3, w.answer("STEP_3", "MAYBE")) }.violations.single().code)
        assertEquals(404, problem { fx.fleet.inspections.saveDraft(w.startedRun().ctx, run.id, 3, SaveInspectionDraft(listOf(w.answer("STEP_3")))) }.code.status)
        assertEquals(2, fx.count("SELECT count(*) FROM taktovia.inspection_answer WHERE inspection_id = ?", run.id))
        // der Abschluss nimmt den Entwurf auf (STEP_1, STEP_2) und ergänzt STEP_3
        val done = fx.fleet.inspections.submit(run.ctx, run.id, 3, SubmitInspection(listOf(w.answer("STEP_3")), Instant.now().minusSeconds(2), null))
        assertEquals("COMPLETED", done.status); assertEquals(3, done.answers.size)
    }

    @Test
    fun nachtrag_ueberschreibt_original_nicht() {
        val w = ReadinessWorld(fx); val run = w.startedRun(); val done = w.submit(run, w.allOk())
        val original = done.answers.single { it.stepCode == "STEP_2" }
        val a1 = amend(w, run); val a2 = amend(w, run, reason = "Zweite Ergänzung", answer = "OK", note = null, kind = AmendmentKind.SUPPLEMENT)
        assertEquals(1, a1.version); assertEquals(2, a2.version); assertEquals(setOf(a1.answerId), setOf(a2.answerId))
        assertEquals(listOf(a1.amendedBy), fx.strings("SELECT actor_ref FROM taktovia.audit_event WHERE action='fleet.inspection.amended' AND redacted_changes->>'amendment_id' = ?", a1.id.toString()), "Autor des Nachtrags = Akteur im Audit")
        val view = fx.fleet.inspections.get(w.ctx("dispatcher"), run.id)
        // die Originalantwort ist unverändert lesbar; die Nachträge stehen daneben
        val after = view.answers.single { it.stepCode == "STEP_2" }
        assertEquals(original, after); assertEquals("OK", after.answer)
        assertEquals(listOf(1, 2), view.amendments.map { it.version }); assertEquals("DEFECT", view.amendments[0].newAnswer); assertEquals("Fehler bei der Eingabe", view.amendments[0].reason)
        assertEquals(done.revision, view.revision, "der Nachtrag erhöht die Revision des Prüfnachweises nicht")
        // Audit je Nachtrag ohne Freitext
        assertEquals(2, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='fleet.inspection.amended' AND object_ref=? AND (redacted_changes::text NOT LIKE '%Fehler bei%')", w.tenant.value, "inspection:${run.id}"))
    }

    @Test
    fun nachtrag_ohne_grund_wird_abgewiesen() {
        val w = ReadinessWorld(fx); val run = w.startedRun(); w.submit(run, w.allOk())
        for (reason in listOf("", "   ")) assertEquals(ViolationCode.TOO_SHORT, problem { CreateInspectionAmendment.parse(linkedMapOf("step_code" to "STEP_2", "kind" to "CORRECTION", "new_answer" to "OK", "reason" to reason)) }.violations.single().code)
        assertEquals(ViolationCode.REQUIRED, problem { CreateInspectionAmendment.parse(linkedMapOf("step_code" to "STEP_2", "kind" to "CORRECTION", "new_answer" to "OK")) }.violations.single().code)
        // auch an der Datenbank vorbei: Grund NOT NULL und nicht leer
        val answerId = fx.strings("SELECT id::text FROM taktovia.inspection_answer WHERE inspection_id = ? AND step_code = 'STEP_2'", run.id).single()
        assertThrows(Exception::class.java) { fx.exec("INSERT INTO taktovia.inspection_amendment(tenant_id,id,inspection_id,step_code,answer_id,version,kind,new_answer,amended_by,reason) VALUES (?,?,?,'STEP_2',?::uuid,9,'CORRECTION','OK','user:x','  ')", w.tenant.value, UUID.randomUUID(), run.id, answerId) }
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.inspection_amendment WHERE inspection_id = ?", run.id))
        // Mangel als Nachtrag verlangt eine Beschreibung; unbekannter Schritt: 422; Antwort ohne Original: 422
        assertEquals("/new_note", problem { amend(w, run, note = null) }.violations.single().pointer)
        assertEquals("/step_code", problem { amend(w, run, step = "NOPE") }.violations.single().pointer)
    }

    @Test
    fun direkte_aenderung_nach_abschluss_abgewiesen() {
        val w = ReadinessWorld(fx); val run = w.startedRun(); val done = w.submit(run, w.allOk())
        // API: Entwurf speichern nach dem Abschluss ist nur als Nachtrag möglich
        assertEquals(ErrorCode.IMMUTABLE_RECORD, problem { draft(w, run, done.revision, w.answer("STEP_1", "DEFECT", note = "x")) }.code)
        // Datenbank: UPDATE/DELETE/INSERT auf Antworten des abgeschlossenen Laufs scheitern, die Originalantwort bleibt
        for (sql in listOf("UPDATE taktovia.inspection_answer SET answer = 'DEFECT' WHERE inspection_id = ?", "DELETE FROM taktovia.inspection_answer WHERE inspection_id = ?"))
            assertThrows(Exception::class.java) { fx.exec(sql, run.id) }
        assertEquals(3, fx.count("SELECT count(*) FROM taktovia.inspection_answer WHERE inspection_id = ? AND answer = 'OK'", run.id))
        // ein Nachtrag vor dem Abschluss ist kein Weg: der Lauf muss abgeschlossen sein
        val open = w.startedRun()
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, problem { amend(w, open) }.code)
    }

    @Test
    fun nachtrag_durch_unberechtigte_rolle_abgewiesen() {
        val w = ReadinessWorld(fx); val run = w.startedRun(); w.submit(run, w.allOk())
        for (roles in listOf(arrayOf("driver"), arrayOf("dispatcher"), arrayOf("passenger"), emptyArray())) assertEquals(403, problem { amend(w, run, roles = roles) }.code.status, roles.toList().toString())
        // Betriebsleitung darf; fremder Mandant und unbekannter Lauf: 404
        assertEquals(1, amend(w, run, roles = arrayOf("tenant_admin")).version)
        val foreign = ReadinessWorld(fx)
        assertEquals(404, problem { fx.fleet.inspections.amend(foreign.ctx("workshop", tenant = foreign.tenant), run.id, CreateInspectionAmendment("STEP_2", AmendmentKind.CORRECTION, "OK", null, null, "x")) }.code.status)
        assertEquals(404, problem { fx.fleet.inspections.amend(w.ctx("workshop"), UUID.randomUUID(), CreateInspectionAmendment("STEP_2", AmendmentKind.CORRECTION, "OK", null, null, "x")) }.code.status)
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.inspection_amendment WHERE inspection_id = ?", run.id))
    }

    @Test
    fun nachtrag_aendert_weder_sperre_noch_freigabe_noch_ergebnis() {
        val w = ReadinessWorld(fx); val run = w.startedRun(); val done = w.submit(run, w.allOk())
        fun snapshot() = listOf(
            fx.strings("SELECT outcome FROM taktovia.inspection_run WHERE id = ?", run.id), fx.strings("SELECT active_block::text || ':' || safety_revision FROM taktovia.vehicle WHERE id = ?", run.vehicle),
            fx.strings("SELECT id::text || coalesce(revoked_at::text, '-') FROM taktovia.resource_release WHERE inspection_ref = ?", run.id), fx.strings("SELECT count(*)::text FROM taktovia.vehicle_block WHERE vehicle_id = ?", run.vehicle))
        val before = snapshot()
        amend(w, run, step = "STEP_1", answer = "DEFECT", note = "Kritischer Mangel nachträglich")   // STEP_1 ist sicherheitskritisch: trotzdem keine Sperre
        assertEquals(before, snapshot())
        val view = fx.fleet.inspections.get(run.ctx, run.id)
        assertEquals("PASSED", view.outcome); assertNotNull(view.release)
        assertTrue(fx.fleet.readiness.assertDispatchable(w.ctx("dispatcher"), run.vehicle, run.shift, null, Instant.now()) is DispatchVerdict.Dispatchable, "die Freigabe bleibt bestehen")
    }

    @Test
    fun gleichzeitige_nachtraege_erhalten_fortlaufende_versionen() {
        val w = ReadinessWorld(fx); val run = w.startedRun(); w.submit(run, w.allOk())
        val pool = Executors.newFixedThreadPool(6)
        try {
            val start = CountDownLatch(1)
            val results = (1..6).map { i -> pool.submit<Int> { start.await(); amend(w, run, reason = "Grund $i").version } }
            start.countDown()
            val versions = results.map { it.get(30, TimeUnit.SECONDS) }
            assertEquals((1..6).toList(), versions.sorted(), "alle sechs erhalten, fortlaufend und eindeutig")
            assertEquals(6, fx.count("SELECT count(DISTINCT version) FROM taktovia.inspection_amendment WHERE inspection_id = ?", run.id))
        } finally { pool.shutdownNow() }
    }

    @Test
    fun entwurf_speichern_parallel_zum_abschluss_kein_stiller_verlust() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(10) { i ->
                val w = ReadinessWorld(fx); val run = w.startedRun()
                val rev = fx.fleet.inspections.get(run.ctx, run.id).revision
                val start = CountDownLatch(1)
                // zwei Geräte mit demselben Stand: eines speichert einen Entwurf für STEP_3 (DEFECT), das andere schließt mit STEP_3 = OK ab
                val saver = pool.submit<Result<Any>> { start.await(); Thread.sleep((i % 3) * 5L); runCatching { draft(w, run, rev, w.answer("STEP_3", "DEFECT", note = "Entwurf $i")) } }
                val closer = pool.submit<Result<Any>> { start.await(); runCatching { fx.fleet.inspections.submit(run.ctx, run.id, rev, SubmitInspection(w.allOk(), Instant.now().minusSeconds(2), null)) } }
                start.countDown(); val s = saver.get(30, TimeUnit.SECONDS); val c = closer.get(30, TimeUnit.SECONDS)
                assertTrue(s.isSuccess != c.isSuccess, "Lauf $i: genau einer gewinnt (gleicher Stand): Entwurf=${s.isSuccess} Abschluss=${c.isSuccess}")
                val step3 = fx.strings("SELECT answer FROM taktovia.inspection_answer WHERE inspection_id = ? AND step_code = 'STEP_3'", run.id)
                if (s.isSuccess) { assertEquals(listOf("DEFECT"), step3, "gespeicherter Entwurf ist vorhanden, der Lauf noch offen"); assertEquals("IN_PROGRESS", fx.fleet.inspections.get(run.ctx, run.id).status) }
                else { assertEquals(listOf("OK"), step3, "der Abschluss enthält die Antwort"); assertEquals(409, (s.exceptionOrNull() as ProblemException).code.status) }
                if (!c.isSuccess) assertEquals(412, (c.exceptionOrNull() as ProblemException).code.status)
            }
        } finally { pool.shutdownNow() }
    }
}

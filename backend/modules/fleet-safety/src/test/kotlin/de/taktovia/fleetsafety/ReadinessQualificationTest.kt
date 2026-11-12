package de.taktovia.fleetsafety

import de.taktovia.fleetsafety.api.AnswerInput
import de.taktovia.fleetsafety.api.ChecklistTemplateInput
import de.taktovia.fleetsafety.api.CreateInspectionAmendment
import de.taktovia.fleetsafety.api.DispatchRefusal
import de.taktovia.fleetsafety.api.DispatchVerdict
import de.taktovia.fleetsafety.api.ReleaseBlocker
import de.taktovia.fleetsafety.api.SubmitInspection
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
 * WP-M3-19 / LH-6.21-CHK-010 unter der Runtime-Rolle gegen die echte Datenbank. Originalabnahme: die Anwendung speichert keine Freitext-Gesundheitsdaten zur Selbstauskunft und sperrt
 * fehlende erforderliche Qualifikationen. Positiv (Freigabe mit verwendeter Qualifikationsnummer), negativ (Zusatztext, `NEIN`, fehlende, abgelaufene, nicht freigegebene Qualifikation,
 * fremder Mandant, Nachtrag zur Selbstauskunft) und konkurrierend (Entzug parallel zum Abschluss). Eigene Testwelt je Test: eine Vorlage mit dem Selbstauskunftselement.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReadinessQualificationTest {
    private lateinit var fx: FleetFixture

    @BeforeAll fun up() { fx = FleetFixture() }
    @AfterAll fun down() = fx.close()

    private companion object {
        const val STEP = "EINSATZBEREITSCHAFT"
        const val FREETEXT = "Migraene seit gestern und Rueckenschmerzen"
    }

    /** Mandant mit freigegebener Vorlage (ein Sichtprüfpunkt, die Selbstauskunft) für die Fahrzeugklasse BEREIT. */
    private class Bench(val fx: FleetFixture) {
        val w = ReadinessWorld(fx)
        init {
            val d = fx.fleet.checklistTemplates.create(w.ctx("fleet_safety"), ChecklistTemplateInput.parse(linkedMapOf(
                "name" to "Prüfung mit Selbstauskunft", "vehicle_class" to "BEREIT", "service_mode" to "STANDARD",
                "steps" to listOf(
                    linkedMapOf("step_code" to "STEP_1", "title" to "Sichtprüfung", "input_kind" to "OK_DEFECT_NA", "mandatory" to true, "safety_critical" to true),
                    linkedMapOf("step_code" to STEP, "title" to "Ich bin einsatzbereit", "input_kind" to "READINESS_YES_NO", "mandatory" to true, "safety_critical" to false)))))
            w.iw.publish(d)
        }

        fun run(qualified: Boolean = true): ReadinessWorld.Run {
            val driver = w.driver(qualified, UUID.randomUUID()); val vehicle = w.iw.vehicle("BEREIT"); val shift = w.iw.shift(driver, vehicle)
            val ctx = w.iw.ctx(driver, "driver")
            return ReadinessWorld.Run(driver, vehicle, shift, w.iw.start(ctx, shift, vehicle).inspection.id, ctx)
        }

        fun answers(readiness: String = "JA", note: String? = null, text: String? = null) = listOf(
            w.answer("STEP_1"),
            AnswerInput(STEP, readiness, null, note, Instant.now().minusSeconds(30), text))
    }

    private fun problem(block: () -> Unit): ProblemException = assertThrows(ProblemException::class.java, block)

    private fun answerRow(run: ReadinessWorld.Run) = fx.strings("SELECT concat_ws('|', answer, coalesce(note,'-'), coalesce(text_value,'-'), coalesce(number_value::text,'-')) FROM taktovia.inspection_answer WHERE inspection_id = ? AND step_code = ?", run.id, STEP)

    @Test
    fun gueltige_qualifikation_und_ja_geben_frei_mit_verwendeter_qualifikationsnummer() {
        val b = Bench(fx); val run = b.run()
        val done = b.w.submit(run, b.answers("JA"))
        assertEquals("PASSED", done.outcome); assertNotNull(done.release); assertTrue(done.missing.isEmpty())
        assertEquals(listOf("JA|-|-|-"), answerRow(run), "gespeichert wird nur Ja")
        assertEquals(listOf("1"), fx.strings("SELECT qualification_revision::text FROM taktovia.resource_release WHERE inspection_ref = ?", run.id), "verwendete Qualifikationsnummer steht in der Freigabe")
        assertTrue(fx.fleet.readiness.assertDispatchable(b.w.ctx("dispatcher"), run.vehicle, run.shift, null, Instant.now()) is DispatchVerdict.Dispatchable)
    }

    @Test
    fun fehlende_qualifikation_sperrt_und_selbstauskunft_ohne_freitext() {
        val b = Bench(fx)
        // fehlende Qualifikation: keine Freigabe, nicht disponierbar, Hinweis an die Leitstelle nur mit Kennungen und Code
        val missing = b.run(qualified = false)
        val done = b.w.submit(missing, b.answers("JA"))
        assertNull(done.release); assertEquals(listOf(ReleaseBlocker.QUALIFICATION_MISSING), done.missing)
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.resource_release WHERE inspection_ref = ?", missing.id))
        val verdict = fx.fleet.readiness.assertDispatchable(b.w.ctx("dispatcher"), missing.vehicle, missing.shift, null, Instant.now()) as DispatchVerdict.Refused
        assertTrue(DispatchRefusal.QUALIFICATION_MISSING in verdict.reasons)
        val payloads = fx.strings("SELECT payload::text FROM taktovia.outbox WHERE tenant_id = ? AND event_type = 'inspection.release_withheld'", b.w.tenant.value)
        assertEquals(1, payloads.size)
        val keys = Regex("\"([a-z_]+)\":").findAll(payloads.single()).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("inspection_id", "shift_id", "vehicle_id", "reason"), keys, "Hinweis ohne Personen-, Qualifikations- und HR-Inhalte")
        assertTrue(payloads.single().contains("QUALIFICATION_MISSING"))
        assertFalse(payloads.single().contains(missing.driver.toString()), "keine Fahrerkennung im Hinweis")

        // abgelaufene Qualifikation: gleiches Ergebnis
        val expired = b.run(qualified = false)
        fx.exec("INSERT INTO taktovia.qualification_revision(tenant_id,id,driver_id,qualification_code,revision,valid_from,valid_to,approval_status,driver_seq) VALUES (?,?,?,'PBEFG',1,now() - interval '30 days',now() - interval '1 day','APPROVED',1)",
            b.w.tenant.value, UUID.randomUUID(), expired.driver)
        fx.exec("UPDATE taktovia.driver SET qualification_seq = 1 WHERE id = ?", expired.driver)
        assertEquals(listOf(ReleaseBlocker.QUALIFICATION_MISSING), b.w.submit(expired, b.answers("JA")).missing)

        // nicht freigegebene Revision (wartend) wird nicht verwendet
        val pending = b.run(qualified = false)
        b.w.qualify(pending.driver, status = "PENDING")
        val p = b.w.submit(pending, b.answers("JA"))
        assertNull(p.release); assertEquals(listOf(ReleaseBlocker.QUALIFICATION_MISSING), p.missing)

        // Selbstauskunft ohne Freitext: Notiz, Text, Zahl und fremde Werte werden mit 422 abgewiesen, ohne den Text zu wiederholen
        val ok = b.run()
        val rev = fx.fleet.inspections.get(ok.ctx, ok.id).revision
        fun submit(a: List<AnswerInput>) = fx.fleet.inspections.submit(ok.ctx, ok.id, rev, SubmitInspection(a, Instant.now().minusSeconds(5), null))
        val withNote = problem { submit(b.answers("JA", note = FREETEXT)) }
        assertEquals(ErrorCode.VALIDATION_FAILED, withNote.code)
        assertEquals(listOf("/answers/$STEP/note" to ViolationCode.INVALID_VALUE), withNote.violations.map { it.pointer to it.code })
        val withText = problem { submit(b.answers("NEIN", text = FREETEXT)) }
        assertEquals(listOf("/answers/$STEP/text_value" to ViolationCode.INVALID_VALUE), withText.violations.map { it.pointer to it.code })
        val withNumber = problem { submit(listOf(b.w.answer("STEP_1"), b.w.answer(STEP, "JA", number = java.math.BigDecimal.ONE))) }
        assertEquals(listOf("/answers/$STEP/number_value" to ViolationCode.INVALID_VALUE), withNumber.violations.map { it.pointer to it.code })
        val foreign = problem { submit(b.answers(readiness = "OK")) }
        assertEquals(listOf("/answers/$STEP/answer" to ViolationCode.NOT_IN_ENUM), foreign.violations.map { it.pointer to it.code })
        // JA/NEIN gehören nur zur Selbstauskunft
        val misuse = problem { submit(listOf(b.w.answer("STEP_1", "JA"), b.w.answer(STEP, "JA"))) }
        assertEquals(listOf("/answers/STEP_1/answer" to ViolationCode.NOT_IN_ENUM), misuse.violations.map { it.pointer to it.code })
        listOf(withNote, withText, withNumber, foreign, misuse).forEach { e -> assertFalse((e.detail ?: "").contains("Migraene") || e.violations.any { it.message.contains("Migraene") }, "Fehlermeldung ohne Freitext") }
        // nichts gespeichert: weder Antworten noch Lauf noch Ereignisse enthalten den Freitext
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.inspection_answer WHERE inspection_id = ?", ok.id))
        assertEquals("IN_PROGRESS", fx.strings("SELECT status FROM taktovia.inspection_run WHERE id = ?", ok.id).single())
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id = ? AND payload::text LIKE '%Migraene%'", b.w.tenant.value))
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id = ? AND redacted_changes::text LIKE '%Migraene%'", b.w.tenant.value))
        // auch der Entwurf nimmt keinen Text zur Selbstauskunft an
        val draft = problem { fx.fleet.inspections.saveDraft(ok.ctx, ok.id, rev, de.taktovia.fleetsafety.api.SaveInspectionDraft(listOf(AnswerInput(STEP, "JA", null, FREETEXT, Instant.now().minusSeconds(30))))) }
        assertEquals(ErrorCode.VALIDATION_FAILED, draft.code)
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.inspection_answer WHERE inspection_id = ?", ok.id))
    }

    @Test
    fun nein_zur_einsatzbereitschaft_gibt_nicht_frei_und_speichert_keinen_grund() {
        val b = Bench(fx); val run = b.run()
        val done = b.w.submit(run, b.answers("NEIN"))
        assertEquals("COMPLETED", done.status); assertEquals("BLOCKED", done.outcome); assertNull(done.release)
        assertEquals(listOf(ReleaseBlocker.NO_INSPECTION), done.missing)
        assertEquals(listOf("NEIN|-|-|-"), answerRow(run), "nur Nein, kein Grund")
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.resource_release WHERE inspection_ref = ?", run.id))
        assertTrue(fx.fleet.readiness.assertDispatchable(b.w.ctx("dispatcher"), run.vehicle, run.shift, null, Instant.now()) is DispatchVerdict.Refused)
        // NEIN sperrt nur die Freigabe dieser Prüfung, nicht das Fahrzeug: keine Fahrzeugsperre
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.vehicle_block WHERE vehicle_id = ?", run.vehicle))
        // keine Qualifikationsmeldung: der Hinweis betrifft nur fehlende Qualifikation
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id = ? AND event_type = 'inspection.release_withheld'", b.w.tenant.value))
    }

    @Test
    fun selbstauskunft_ist_pflicht_nicht_nachtragbar_und_mandantengetrennt() {
        val b = Bench(fx); val run = b.run()
        val e = problem { b.w.submit(run, listOf(b.w.answer("STEP_1"))) }
        assertEquals(listOf("/answers/$STEP" to ViolationCode.REQUIRED), e.violations.map { it.pointer to it.code })
        b.w.submit(run, b.answers("JA"))
        // Nachtrag zur Selbstauskunft ausgeschlossen (kein Textkanal über Nachträge)
        val amend = problem { fx.fleet.inspections.amend(b.w.ctx("workshop"), run.id, CreateInspectionAmendment.parse(linkedMapOf("step_code" to STEP, "kind" to "CORRECTION", "new_answer" to "OK", "reason" to "Korrektur"))) }
        assertEquals(ErrorCode.VALIDATION_FAILED, amend.code)
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.inspection_amendment WHERE inspection_id = ?", run.id))
        // fremder Mandant: Lauf nicht sichtbar, kein Abschluss
        val other = Bench(fx); val foreignRun = other.run()
        val foreignCtx = b.w.iw.ctx(foreignRun.driver, "driver")   // Kontext des Mandanten b mit Kennung eines fremden Fahrers
        assertEquals(ErrorCode.NOT_FOUND, problem { fx.fleet.inspections.submit(foreignCtx, foreignRun.id, 1, SubmitInspection(other.answers(), Instant.now().minusSeconds(5), null)) }.code)
        assertEquals("IN_PROGRESS", fx.strings("SELECT status FROM taktovia.inspection_run WHERE id = ?", foreignRun.id).single())
    }

    @Test
    fun qualifikationsentzug_parallel_zum_submit_gibt_nicht_frei() {
        val b = Bench(fx); val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(10) { i ->
                val run = b.run()
                val rev = fx.fleet.inspections.get(run.ctx, run.id).revision
                val start = CountDownLatch(1)
                val submit = pool.submit<Result<de.taktovia.fleetsafety.api.InspectionView>> { start.await(); Thread.sleep((i % 4) * 8L); runCatching { fx.fleet.inspections.submit(run.ctx, run.id, rev, SubmitInspection(b.answers("JA"), Instant.now().minusSeconds(5), null)) } }
                val revoke = pool.submit {
                    start.await()
                    // Entzug wie im Fahrerstamm: Fahrerzeile sperren, neue Revision, Nummer erhöhen (eine Transaktion)
                    fx.tx.run(b.w.tenant) { j ->
                        j.queryForList("SELECT id FROM taktovia.driver WHERE id = ? FOR UPDATE", run.driver)
                        j.update("INSERT INTO taktovia.qualification_revision(tenant_id,id,driver_id,qualification_code,revision,valid_from,approval_status,driver_seq) VALUES (taktovia.current_tenant(),?,?,'PBEFG',2,now() - interval '30 days','REVOKED',2)", UUID.randomUUID(), run.driver)
                        j.update("UPDATE taktovia.driver SET qualification_seq = 2 WHERE id = ?", run.driver)
                        Thread.sleep(25)
                    }
                }
                start.countDown(); val result = submit.get(30, TimeUnit.SECONDS).getOrThrow(); revoke.get(30, TimeUnit.SECONDS)
                if (result.release != null) assertEquals("1", fx.strings("SELECT qualification_revision::text FROM taktovia.resource_release WHERE inspection_ref = ?", run.id).single(), "Lauf $i: Freigabe vor dem Entzug trägt den damaligen Stand")
                else assertEquals(listOf(ReleaseBlocker.QUALIFICATION_MISSING), result.missing, "Lauf $i")
                // nach dem Entzug gibt es in jeder Serialisierung keine disponierbare Freigabe auf der alten Revision
                val verdict = fx.fleet.readiness.assertDispatchable(b.w.ctx("dispatcher"), run.vehicle, run.shift, null, Instant.now()) as DispatchVerdict.Refused
                assertTrue(DispatchRefusal.QUALIFICATION_MISSING in verdict.reasons, "Lauf $i: ${verdict.reasons}")
            }
        } finally { pool.shutdownNow() }
    }

    @Test
    fun zwei_gleichzeitige_submits_erzeugen_genau_eine_freigabe() {
        val b = Bench(fx); val run = b.run(); val rev = fx.fleet.inspections.get(run.ctx, run.id).revision
        val pool = Executors.newFixedThreadPool(2)
        try {
            val start = CountDownLatch(1)
            val results = (1..2).map { pool.submit<Result<Any>> { start.await(); runCatching { fx.fleet.inspections.submit(run.ctx, run.id, rev, SubmitInspection(b.answers("JA"), Instant.now().minusSeconds(5), null)) } } }
            start.countDown()
            val outs = results.map { it.get(30, TimeUnit.SECONDS) }
            assertEquals(1, outs.count { it.isSuccess }, outs.toString())
            assertEquals(1, fx.count("SELECT count(*) FROM taktovia.resource_release WHERE inspection_ref = ?", run.id))
        } finally { pool.shutdownNow() }
    }
}

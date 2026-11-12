package de.taktovia.fleetsafety

import de.taktovia.fleetsafety.api.AnswerInput
import de.taktovia.fleetsafety.api.ChecklistTemplateInput
import de.taktovia.fleetsafety.api.JustifyInspectionAnswer
import de.taktovia.fleetsafety.api.Plausibility
import de.taktovia.fleetsafety.api.SaveInspectionDraft
import de.taktovia.fleetsafety.api.SubmitInspection
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.ViolationCode
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M3-17 / LH-6.21-CHK-008 unter der Runtime-Rolle gegen die echte Datenbank. Originalabnahme: offensichtlich unplausible Werte müssen überprüft oder mit Begründung freigegeben
 * werden. Regeln (OP-15, nur die festgelegten): Kilometerstand unter dem letzten bestätigten Wert, Fahrzeugidentität weicht vom zugewiesenen Fahrzeug ab, Energie außerhalb 0 bis 100 %.
 * Planungsannahme: begründet freigeben dürfen Disposition, Werkstatt und Betriebsleitung (nicht der Fahrer).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShiftStartValuesTest {
    private lateinit var fx: FleetFixture

    @BeforeAll fun up() { fx = FleetFixture() }
    @AfterAll fun down() = fx.close()

    private fun problem(block: () -> Unit): ProblemException = assertThrows(ProblemException::class.java, block)

    /** Mandant mit freigegebener Vorlage der Startwertprüfung (Kilometerstand, Energie, Identität, ein Sichtprüfpunkt) und einem gestarteten Lauf. */
    private inner class Setup(val lastOdometer: Long? = 10_000) {
        val w = ReadinessWorld(fx)
        val label = "FZ-" + UUID.randomUUID().toString().take(8).uppercase()
        val template: UUID
        init {
            val author = w.ctx("fleet_safety")
            val draft = fx.fleet.checklistTemplates.create(author, ChecklistTemplateInput.parse(linkedMapOf("name" to "Startwerte", "vehicle_class" to "TRANSPORTER", "service_mode" to "STANDARD", "steps" to listOf(
                step("ODO", "ODOMETER_KM"), step("ENERGY", "ENERGY_PCT"), step("IDENT", "VEHICLE_IDENTITY"), step("SICHT", "OK_DEFECT_NA")))))
            template = fx.fleet.checklistTemplates.publish(w.ctx("fleet_safety"), draft.id, draft.revision).id
        }
        val driver = w.driver(true, UUID.randomUUID()); val vehicle = w.iw.vehicle("TRANSPORTER")
        val shift: UUID
        val run: ReadinessWorld.Run
        init {
            fx.exec("UPDATE taktovia.vehicle SET vehicle_label = ?, last_confirmed_odometer_km = ? WHERE id = ?", label, lastOdometer, vehicle)
            shift = w.iw.shift(driver, vehicle)
            val ctx = w.iw.ctx(driver, "driver")
            run = ReadinessWorld.Run(driver, vehicle, shift, w.iw.start(ctx, shift, vehicle).inspection.id, ctx)
        }
        private fun step(code: String, kind: String) = linkedMapOf("step_code" to code, "title" to code, "input_kind" to kind, "mandatory" to true, "safety_critical" to false)
        fun revision(): Long = fx.fleet.inspections.get(run.ctx, run.id).revision
        fun a(code: String, number: String? = null, text: String? = null, status: String = "OK") =
            AnswerInput(code, status, number?.let(::BigDecimal), null, Instant.now().minusSeconds(20), text)
        fun valid(odo: String = "12000", energy: String = "55.5", ident: String = label) = listOf(a("ODO", odo), a("ENERGY", energy), a("IDENT", text = ident), a("SICHT"))
        fun submit(answers: List<AnswerInput>, rev: Long = revision(), ctx: de.taktovia.platform.context.RequestContext = run.ctx) =
            fx.fleet.inspections.submit(ctx, run.id, rev, SubmitInspection(answers, Instant.now().minusSeconds(2), null))
        fun draft(vararg answers: AnswerInput) = fx.fleet.inspections.saveDraft(run.ctx, run.id, revision(), SaveInspectionDraft(answers.toList()))
        fun justify(code: String, number: String? = null, text: String? = null, reason: String = "Tachoanzeige defekt, Wert von der Leitstelle bestätigt", roles: Array<String> = arrayOf("dispatcher")) =
            fx.fleet.inspections.justify(w.ctx(*roles), run.id, revision(), code, JustifyInspectionAnswer(number?.let(::BigDecimal), text, reason))
        fun lastKm(): Long? = fx.strings("SELECT coalesce(last_confirmed_odometer_km::text, '') FROM taktovia.vehicle WHERE id = ?", vehicle).single().toLongOrNull()
    }

    @Test
    fun plausible_werte_werden_je_pruefenlauf_gespeichert_und_spaeter_unveraendert_gelesen() {
        val s = Setup()
        val done = s.submit(s.valid())
        assertEquals("COMPLETED", done.status); assertEquals("PASSED", done.outcome)
        val odo = done.answers.single { it.stepCode == "ODO" }; val energy = done.answers.single { it.stepCode == "ENERGY" }; val ident = done.answers.single { it.stepCode == "IDENT" }
        assertEquals(0, BigDecimal("12000").compareTo(odo.numberValue)); assertEquals(Plausibility.PLAUSIBEL, odo.plausibility); assertEquals(0, BigDecimal("10000").compareTo(odo.previousValue))
        assertEquals(0, BigDecimal("55.5").compareTo(energy.numberValue)); assertEquals(s.label, ident.textValue); assertEquals(Plausibility.PLAUSIBEL, ident.plausibility)
        // letzter bestätigter Wert am Fahrzeug fortgeschrieben; später unverändert lesbar; Zahl und Text nach Abschluss nicht mehr änderbar
        assertEquals(12_000L, s.lastKm())
        assertEquals(done.answers, fx.fleet.inspections.get(s.w.ctx("dispatcher"), s.run.id).answers)
        assertThrows(Exception::class.java) { fx.exec("UPDATE taktovia.inspection_answer SET number_value = 1 WHERE inspection_id = ? AND step_code = 'ODO'", s.run.id) }
        // Identität darf in Groß-/Kleinschreibung abweichen, die Fahrzeug-ID gilt auch
        val s2 = Setup(); assertEquals("COMPLETED", s2.submit(s2.valid(ident = s2.label.lowercase())).status)
        val s3 = Setup(); assertEquals("COMPLETED", s3.submit(s3.valid(ident = s3.vehicle.toString())).status)
        // erster Lauf ohne Vorwert: jeder Kilometerstand ist plausibel
        val s4 = Setup(lastOdometer = null); assertEquals(0, s4.submit(s4.valid(odo = "5")).answers.single { it.stepCode == "ODO" }.let { if (it.previousValue == null) 0 else 1 }); assertEquals(5L, s4.lastKm())
    }

    @Test
    fun kilometerstand_unter_vorwert_verlangt_begruendung() {
        val s = Setup()
        // weder Entwurf noch Abschluss speichern still: 422 mit Verweis und Vorwert, nichts gespeichert
        val e = problem { s.draft(s.a("ODO", "9000")) }
        assertEquals(ErrorCode.VALIDATION_FAILED, e.code); assertEquals("/answers/ODO/number_value" to ViolationCode.BELOW_MINIMUM, e.violations.single().let { it.pointer to it.code })
        assertTrue(e.detail!!.startsWith("UEBERPRUEFUNG_ERFORDERLICH") && e.detail!!.contains("Vorwert 10000 km"), e.detail)
        assertEquals(ErrorCode.VALIDATION_FAILED, problem { s.submit(s.valid(odo = "9000")) }.code)
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.inspection_answer WHERE inspection_id = ?", s.run.id))
        // Korrektur auf einen plausiblen Wert löst es
        s.draft(s.a("ODO", "10500"))
        // die begründete Freigabe: Fahrer selbst nicht, leere Begründung nicht, plausibler Wert nicht, fremder Mandant nicht
        assertEquals(403, problem { s.justify("ODO", "9000", roles = arrayOf("driver")) }.code.status)
        assertEquals(ViolationCode.TOO_SHORT, problem { JustifyInspectionAnswer.parse(linkedMapOf("number_value" to 9000, "reason" to "  ")) }.violations.single().code)
        assertEquals("/number_value", problem { s.justify("ODO", "12000") }.violations.single().pointer)
        val foreign = ReadinessWorld(fx)
        assertEquals(404, problem { fx.fleet.inspections.justify(foreign.ctx("dispatcher", tenant = foreign.tenant), s.run.id, 2, "ODO", JustifyInspectionAnswer(BigDecimal(9000), null, "x")) }.code.status)
        assertEquals(ViolationCode.INVALID_VALUE, problem { s.justify("SICHT", "1") }.violations.single().code, "nur Kilometerstand und Identität sind freigebbar")
        val before = s.revision()
        val view = s.justify("ODO", "9000")
        assertEquals(before + 1, view.revision)
        val odo = view.answers.single { it.stepCode == "ODO" }
        assertEquals(Plausibility.BEGRUENDET_FREIGEGEBEN, odo.plausibility); assertEquals(0, BigDecimal(9000).compareTo(odo.numberValue)); assertEquals(0, BigDecimal(10000).compareTo(odo.previousValue))
        val j = odo.justification!!
        assertEquals("Tachoanzeige defekt, Wert von der Leitstelle bestätigt", j.reason); assertTrue(j.justifiedBy.startsWith("user:")); assertNotNull(j.justifiedAt)
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='fleet.inspection.answer_justified' AND object_ref=? AND actor_ref=?", s.w.tenant.value, "inspection:${s.run.id}", j.justifiedBy))
        // ein anderer Wert als der freigegebene ist wieder zu überprüfen; derselbe Wert beim Abschluss ist freigegeben
        assertEquals(ErrorCode.VALIDATION_FAILED, problem { s.submit(s.valid(odo = "9500")) }.code)
        val done = s.submit(s.valid(odo = "9000"))
        assertEquals("COMPLETED", done.status); val kept = done.answers.single { it.stepCode == "ODO" }
        assertEquals(Plausibility.BEGRUENDET_FREIGEGEBEN, kept.plausibility); assertEquals(j, kept.justification)   // Akteur, Zeit, Begründung stehen im Prüfnachweis
        assertEquals(10_000L, s.lastKm(), "der letzte bestätigte Wert fällt nicht zurück")
        // nach dem Abschluss ist die Begründung unveränderlich
        assertThrows(Exception::class.java) { fx.exec("UPDATE taktovia.inspection_answer SET justification_reason = 'andere' WHERE inspection_id = ? AND step_code = 'ODO'", s.run.id) }
        assertEquals(ErrorCode.IMMUTABLE_RECORD, problem { fx.fleet.inspections.justify(s.w.ctx("dispatcher"), s.run.id, done.revision, "ODO", JustifyInspectionAnswer(BigDecimal(9000), null, "nachträglich")) }.code)
    }

    @Test
    fun falsche_fahrzeugidentitaet_und_energie_ausserhalb_0_100_verhindern_abschluss() {
        val s = Setup()
        // Verwechslung: abweichende Identität verhindert den Abschluss für das zugewiesene Fahrzeug
        val wrong = problem { s.submit(s.valid(ident = "FZ-ANDERES")) }
        assertEquals("/answers/IDENT/text_value" to ViolationCode.INVALID_VALUE, wrong.violations.single().let { it.pointer to it.code }); assertTrue(wrong.detail!!.startsWith("UEBERPRUEFUNG_ERFORDERLICH"))
        assertEquals("IN_PROGRESS", fx.fleet.inspections.get(s.run.ctx, s.run.id).status)
        // Energie/Tank: 0 bis 100 %
        assertEquals("/answers/ENERGY/number_value" to ViolationCode.ABOVE_MAXIMUM, problem { s.submit(s.valid(energy = "100.1")) }.violations.single().let { it.pointer to it.code })
        assertEquals("/answers/ENERGY/number_value" to ViolationCode.BELOW_MINIMUM, problem { s.submit(s.valid(energy = "-1")) }.violations.single().let { it.pointer to it.code })
        assertEquals("COMPLETED", Setup().let { it.submit(it.valid(energy = "0")) }.status)
        assertEquals("COMPLETED", Setup().let { it.submit(it.valid(energy = "100")) }.status)
        // Kilometerstand: ganze Zahl, nicht negativ; Identität Pflicht; Mangel ist kein Startwert
        assertEquals(ViolationCode.TYPE_MISMATCH, problem { s.submit(s.valid(odo = "12000.5")) }.violations.single().code)
        assertEquals(ViolationCode.BELOW_MINIMUM, problem { s.submit(s.valid(odo = "-3")) }.violations.single().code)
        assertEquals(ViolationCode.REQUIRED, problem { s.submit(s.valid().map { if (it.stepCode == "IDENT") s.a("IDENT") else it }) }.violations.single().code)
        assertTrue(("/answers/ODO/answer" to ViolationCode.NOT_IN_ENUM) in problem { s.submit(s.valid().map { if (it.stepCode == "ODO") s.a("ODO", "12000", status = "DEFECT") else it }) }.violations.map { it.pointer to it.code })
        // begründete Freigabe der Verwechslung: berechtigte Rolle, dann ist der Abschluss möglich
        assertEquals(403, problem { s.justify("IDENT", text = "FZ-ANDERES", roles = arrayOf("driver")) }.code.status)
        s.justify("IDENT", text = "FZ-ANDERES", roles = arrayOf("workshop"))
        val done = s.submit(s.valid(ident = "FZ-ANDERES"))
        assertEquals("COMPLETED", done.status); assertEquals(Plausibility.BEGRUENDET_FREIGEGEBEN, done.answers.single { it.stepCode == "IDENT" }.plausibility)
        assertNull(done.answers.single { it.stepCode == "ENERGY" }.justification)
    }

    @Test
    fun zwei_pruefabschluesse_desselben_fahrzeugs_der_letzte_bestaetigte_wert_faellt_nicht_zurueck() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(8) { i ->
                val a = Setup(lastOdometer = 10_000)
                // zweiter Prüflauf desselben Fahrzeugs in einer früheren Schicht (Schichten überlappen nicht), anderer Fahrer
                val d2 = a.w.driver(true, UUID.randomUUID()); val shift2 = UUID.randomUUID()
                fx.exec("INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state) VALUES (?,?,?,?,now() - interval '10 hours', now() - interval '4 hours','Europe/Berlin',0,'ENDED')", a.w.tenant.value, shift2, d2, a.vehicle)
                val run2 = UUID.randomUUID()
                val tpl = fx.fleet.inspections.get(a.run.ctx, a.run.id).templateRevisionId
                fx.exec("INSERT INTO taktovia.inspection_run(tenant_id,id,shift_id,vehicle_id,driver_ref,template_revision_id,kind,status,started_at,created_by) VALUES (?,?,?,?,?,?,'PRE_SHIFT','IN_PROGRESS',?,'user:t')",
                    a.w.tenant.value, run2, shift2, a.vehicle, d2, tpl, Timestamp.from(Instant.now().minusSeconds(3600 * 5)))
                val ctx2 = a.w.iw.ctx(d2, "driver")
                val start = CountDownLatch(1)
                val high = pool.submit<Result<Any>> { start.await(); runCatching { a.submit(a.valid(odo = "20000")) } }
                val low = pool.submit<Result<Any>> { start.await(); Thread.sleep((i % 3) * 4L); runCatching { fx.fleet.inspections.submit(ctx2, run2, 1, SubmitInspection(a.valid(odo = "15000"), Instant.now().minusSeconds(2), null)) } }
                start.countDown(); val h = high.get(30, TimeUnit.SECONDS); val l = low.get(30, TimeUnit.SECONDS)
                assertTrue(h.isSuccess, "Lauf $i: ${h.exceptionOrNull()}")
                // der niedrigere Wert ist nur zulässig, wenn er vor dem höheren bestätigt wurde; danach verlangt er Überprüfung (422) und senkt den Vorwert nie
                if (!l.isSuccess) assertEquals(422, (l.exceptionOrNull() as ProblemException).code.status, "Lauf $i")
                assertEquals(20_000L, a.lastKm(), "Lauf $i: der letzte bestätigte Wert fällt nicht zurück")
            }
        } finally { pool.shutdownNow() }
    }
}

package de.taktovia.fleetsafety

import de.taktovia.fleetsafety.api.ChecklistTemplateInput
import de.taktovia.fleetsafety.api.ChecklistTemplateState
import de.taktovia.fleetsafety.api.ChecklistTemplateType
import de.taktovia.fleetsafety.api.MandatoryChecklistPoints
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.ViolationCode
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * WP-M3-11 / LH-6.21-CHK-006: Prüfung vor der Fahrgastaufnahme mit sechs Pflichtpunkten (Sauberkeit, Fahrgastraum, Sitzplätze, Einstiegsbereich, Rückhaltesysteme, Sicherheitsausrüstung).
 * Originalabnahme: die Prüfung kann mit Einzelstatus und bei Abweichung dokumentiertem Mangel abgeschlossen werden. Sie läuft als Prüflauf der Art `PRE_BOARDING` (Planungsannahme,
 * der Auslösezeitpunkt „vor Fahrgastaufnahme“ ist offen), erteilt keine Einsatzfreigabe und nutzt die Vorlage des Typs `PASSENGER_COMPARTMENT`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PassengerCompartmentCheckTest {
    private lateinit var fx: FleetFixture

    @BeforeAll fun up() { fx = FleetFixture() }
    @AfterAll fun down() = fx.close()

    private fun problem(block: () -> Unit): ProblemException = assertThrows(ProblemException::class.java, block)
    private val keys = listOf("SAUBERKEIT", "FAHRGASTRAUM", "SITZPLAETZE", "EINSTIEGSBEREICH", "RUECKHALTESYSTEME", "SICHERHEITSAUSRUESTUNG")

    @Test
    fun abschluss_mit_einzelstatus_und_dokumentiertem_mangel() {
        val w = ReadinessWorld(fx)
        val tech = w.standard(ChecklistTemplateType.VEHICLE_TECHNICAL); val boarding = w.standard(ChecklistTemplateType.PASSENGER_COMPARTMENT)
        assertEquals(ChecklistTemplateState.PUBLISHED, boarding.state); assertEquals(keys, boarding.steps.map { it.code }); assertEquals(keys, MandatoryChecklistPoints.PASSENGER_COMPARTMENT.map { it.key })
        val b = w.boarding()
        // die Art bestimmt die Vorlage: Schichtstart-Prüfung die technische, Prüfung vor Fahrgastaufnahme die des Fahrgastraums (beide für dieselbe Klasse und Bedienart freigegeben)
        assertEquals(tech.id, w.start(b, "PRE_SHIFT").templateRevisionId)
        val run = w.start(b, "PRE_BOARDING")
        assertEquals(boarding.id, run.templateRevisionId); assertNotEquals(tech.id, run.templateRevisionId); assertEquals(6, run.template.steps.size)
        // Abschluss mit Einzelstatus je Punkt (alle OK): kein Einsatzfreigabe-Schritt
        val ok = w.submit(b, run.id, keys.map { w.answer(it) })
        assertEquals("COMPLETED", ok.status); assertEquals("PASSED", ok.outcome); assertEquals(6, ok.answers.size)
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.resource_release WHERE inspection_ref = ?", run.id), "diese Prüfung erteilt keine Einsatzfreigabe")
        assertTrue(ok.missing.isEmpty() && ok.release == null)
        // Abschluss mit einem dokumentierten Mangel (Einzelstatus MANGEL mit Beschreibung)
        val b2 = w.boarding(); val r2 = w.start(b2, "PRE_BOARDING")
        val withDefect = w.submit(b2, r2.id, keys.map { if (it == "SITZPLAETZE") w.answer(it, "DEFECT", note = "Sitz 3 locker") else if (it == "SAUBERKEIT") w.answer(it, "NOT_APPLICABLE") else w.answer(it) })
        assertEquals("COMPLETED", withDefect.status)
        assertEquals(listOf("OK", "OK", "OK", "OK", "DEFECT", "NOT_APPLICABLE").sorted(), withDefect.answers.map { it.answer }.sorted())
        assertEquals("Sitz 3 locker", withDefect.answers.single { it.stepCode == "SITZPLAETZE" }.note)
        // Ereignis und Audit tragen die Art
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND event_type='inspection.completed' AND aggregate_id=? AND payload->>'kind'='PRE_BOARDING'", w.tenant.value, r2.id))
    }

    @Test
    fun abschluss_ohne_einzelstatus_oder_ohne_mangeldokumentation_wird_abgewiesen() {
        val w = ReadinessWorld(fx); w.standard(ChecklistTemplateType.PASSENGER_COMPARTMENT)
        val b = w.boarding(); val run = w.start(b, "PRE_BOARDING")
        // ein Punkt ohne Einzelstatus blockiert den Abschluss (422 mit Verweis auf das Element), je Punkt
        for (k in keys) assertEquals(listOf("/answers/$k" to ViolationCode.REQUIRED), problem { w.submit(b, run.id, keys.filter { it != k }.map { w.answer(it) }) }.violations.map { it.pointer to it.code }, k)
        // Abweichung ohne Mangeldokumentation: 422 mit Verweis auf den Punkt, je Punkt
        for (k in keys) assertEquals(listOf("/answers/$k/note" to ViolationCode.REQUIRED), problem { w.submit(b, run.id, keys.map { if (it == k) w.answer(it, "DEFECT", note = "  ") else w.answer(it) }) }.violations.map { it.pointer to it.code }, k)
        assertEquals("IN_PROGRESS", fx.fleet.inspections.get(b.ctx, run.id).status)
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.inspection_answer WHERE inspection_id = ?", run.id))
        // fremder Abschluss: Fahrer einer anderen Schicht 404, Disposition 403, fremder Mandant 404
        val other = w.boarding()
        assertEquals(404, problem { fx.fleet.inspections.submit(other.ctx, run.id, 1, de.taktovia.fleetsafety.api.SubmitInspection(keys.map { w.answer(it) }, java.time.Instant.now().minusSeconds(2), null)) }.code.status)
        assertEquals(403, problem { fx.fleet.inspections.submit(w.ctx("dispatcher"), run.id, 1, de.taktovia.fleetsafety.api.SubmitInspection(keys.map { w.answer(it) }, java.time.Instant.now().minusSeconds(2), null)) }.code.status)
        val foreign = ReadinessWorld(fx)
        assertEquals(404, problem { fx.fleet.inspections.submit(foreign.ctx("workshop", tenant = foreign.tenant), run.id, 1, de.taktovia.fleetsafety.api.SubmitInspection(keys.map { w.answer(it) }, java.time.Instant.now().minusSeconds(2), null)) }.code.status)
        // zwei gleichzeitige Abschlüsse: genau ein Ergebnis (Verhalten aus WP-M3-14), hier mit demselben Lauf zweimal nacheinander
        w.submit(b, run.id, keys.map { w.answer(it) })
        assertEquals(ErrorCode.IMMUTABLE_RECORD, problem { w.submit(b, run.id, keys.map { w.answer(it) }) }.code)
        // ohne Vorlage des Typs: kein Lauf PRE_BOARDING (der Fahrgastraum-Typ gilt nur für diese Art)
        val none = ReadinessWorld(fx); val nb = none.boarding()
        assertEquals(ErrorCode.CONFLICT, problem { none.start(nb, "PRE_BOARDING") }.code)
    }

    @Test
    fun freigabe_ohne_einen_der_sechs_pflichtpunkte_wird_abgewiesen() {
        val w = ReadinessWorld(fx)
        for (missing in keys) {
            val draft = fx.fleet.checklistTemplates.create(w.ctx("fleet_safety"), ChecklistTemplateInput.parse(linkedMapOf("name" to "Ohne $missing", "vehicle_class" to "PKW", "service_mode" to "STANDARD", "template_type" to "PASSENGER_COMPARTMENT",
                "steps" to keys.filter { it != missing }.map { linkedMapOf("step_code" to it, "title" to it, "input_kind" to "OK_DEFECT_NA", "mandatory" to true, "safety_critical" to false) })))
            val e = problem { fx.fleet.checklistTemplates.publish(w.ctx("tenant_admin"), draft.id, draft.revision) }
            assertEquals(listOf("/steps/$missing" to ViolationCode.REQUIRED), e.violations.map { it.pointer to it.code }, missing)
            assertEquals("MANDATORY_POINTS_MISSING: $missing", e.detail)
        }
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.checklist_template_revision WHERE tenant_id = ? AND state = 'PUBLISHED'", w.tenant.value))
        // die technischen Pflichtpunkte ersetzen die Fahrgastraumpunkte nicht
        val wrongType = fx.fleet.checklistTemplates.create(w.ctx("fleet_safety"), ChecklistTemplateInput.parse(linkedMapOf("name" to "Falscher Satz", "vehicle_class" to "PKW", "service_mode" to "STANDARD", "template_type" to "PASSENGER_COMPARTMENT",
            "steps" to MandatoryChecklistPoints.VEHICLE_TECHNICAL.map { linkedMapOf("step_code" to it.key, "title" to it.title, "input_kind" to "OK_DEFECT_NA", "mandatory" to true, "safety_critical" to false) })))
        assertEquals(keys, problem { fx.fleet.checklistTemplates.publish(w.ctx("tenant_admin"), wrongType.id, wrongType.revision) }.violations.map { it.pointer.substringAfterLast('/') })
    }
}

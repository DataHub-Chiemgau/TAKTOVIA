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
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * WP-M3-11 / LH-6.21-CHK-005: technische Fahrzeugprüfung mit acht Pflichtpunkten (Beleuchtung, Reifen-Sichtprüfung, Scheiben, Spiegel, Türen, Sicherheitsgurte, sichtbare Leckagen,
 * Warnanzeigen). Originalabnahme: eine Fahrzeugvorlage enthält alle Pflichtpunkte und erlaubt die Erfassung eines Mangelbefunds je Punkt. Gegen die echte Datenbank unter der Runtime-Rolle.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MandatoryTemplateTest {
    private lateinit var fx: FleetFixture

    @BeforeAll fun up() { fx = FleetFixture() }
    @AfterAll fun down() = fx.close()

    private fun problem(block: () -> Unit): ProblemException = assertThrows(ProblemException::class.java, block)
    private val keys = listOf("BELEUCHTUNG", "REIFEN_SICHTPRUEFUNG", "SCHEIBEN", "SPIEGEL", "TUEREN", "SICHERHEITSGURTE", "SICHTBARE_LECKAGEN", "WARNANZEIGEN")

    @Test
    fun fahrzeugvorlage_enthaelt_acht_pflichtpunkte_und_erfasst_mangel_je_punkt() {
        val w = ReadinessWorld(fx)
        // Standardvorlage: alle acht Pflichtpunkte als Pflichtschritte, ohne Kennzeichen sicherheitskritisch (Planungsannahme), Entwurf des Systems
        val draft = fx.fleet.checklistTemplates.createStandard(w.ctx("fleet_safety"), ChecklistTemplateType.VEHICLE_TECHNICAL, "PKW", "STANDARD")
        assertEquals(ChecklistTemplateState.DRAFT, draft.state); assertEquals("system:standard-seed", draft.createdBy)
        assertEquals(keys, draft.steps.map { it.code }); assertEquals(keys, MandatoryChecklistPoints.VEHICLE_TECHNICAL.map { it.key })
        assertTrue(draft.steps.all { it.mandatory && !it.safetyCritical && it.title.isNotBlank() && !it.helpText.isNullOrBlank() })
        val published = fx.fleet.checklistTemplates.publish(w.ctx("tenant_admin"), draft.id, draft.revision)
        assertEquals(ChecklistTemplateState.PUBLISHED, published.state); assertEquals(ChecklistTemplateType.VEHICLE_TECHNICAL, published.templateType)
        // Abschluss mit allen Punkten OK
        val b = w.boarding(); val run = w.start(b, "PRE_SHIFT")
        assertEquals(published.id, run.templateRevisionId); assertEquals(8, run.template.steps.size)
        val ok = w.submit(b, run.id, keys.map { w.answer(it) })
        assertEquals("COMPLETED", ok.status); assertEquals("PASSED", ok.outcome); assertEquals(8, ok.answers.size)
        // je Punkt lässt sich ein Mangelbefund erfassen (Antwort DEFECT mit Beschreibung); ein unkritischer Mangel sperrt nicht
        for (k in keys) {
            val b2 = w.boarding(); val r2 = w.start(b2, "PRE_SHIFT")
            val done = w.submit(b2, r2.id, keys.map { if (it == k) w.answer(it, "DEFECT", note = "Mangel an $it") else w.answer(it) })
            assertEquals("COMPLETED", done.status, k); assertEquals("PASSED", done.outcome, k)
            val a = done.answers.single { it.stepCode == k }
            assertEquals("DEFECT", a.answer, k); assertEquals("Mangel an $k", a.note, k)
            assertEquals(7, done.answers.count { it.answer == "OK" }, k)
        }
        // Mangel ohne Beschreibung: 422 mit Verweis auf den Punkt, je Punkt
        for (k in keys) {
            val b3 = w.boarding(); val r3 = w.start(b3, "PRE_SHIFT")
            assertEquals(listOf("/answers/$k/note" to ViolationCode.REQUIRED), problem { w.submit(b3, r3.id, keys.map { if (it == k) w.answer(it, "DEFECT") else w.answer(it) }) }.violations.map { it.pointer to it.code }, k)
        }
        // der Abschluss mit Antwort außerhalb OK/MANGEL/NICHT ANWENDBAR wird abgewiesen
        val b4 = w.boarding(); val r4 = w.start(b4, "PRE_SHIFT")
        assertTrue(("/answers/SPIEGEL/answer" to ViolationCode.NOT_IN_ENUM) in problem { w.submit(b4, r4.id, keys.map { if (it == "SPIEGEL") w.answer(it, "KAPUTT") else w.answer(it) }) }.violations.map { it.pointer to it.code })
    }

    @Test
    fun freigabe_ohne_pflichtpunkt_wird_je_schluessel_abgewiesen() {
        val w = ReadinessWorld(fx)
        for (missing in keys) {
            val steps = keys.filter { it != missing }.map { step(it) }
            val draft = fx.fleet.checklistTemplates.create(w.ctx("fleet_safety"), ChecklistTemplateInput.parse(linkedMapOf("name" to "Ohne $missing", "vehicle_class" to "PKW", "service_mode" to "STANDARD", "template_type" to "VEHICLE_TECHNICAL", "steps" to steps)))
            val e = problem { fx.fleet.checklistTemplates.publish(w.ctx("tenant_admin"), draft.id, draft.revision) }
            assertEquals(ErrorCode.VALIDATION_FAILED, e.code, missing)
            assertEquals(listOf("/steps/$missing" to ViolationCode.REQUIRED), e.violations.map { it.pointer to it.code }, missing)   // je fehlendem Schlüssel genau ein Verweis
            assertEquals("MANDATORY_POINTS_MISSING: $missing", e.detail)
            assertEquals(ChecklistTemplateState.DRAFT, fx.fleet.checklistTemplates.get(w.ctx("fleet_safety"), draft.id).state, "nicht freigegeben")
        }
        // mehrere fehlende Schlüssel: alle genannt; ein Pflichtpunkt, der nur als optional angelegt ist, zählt als fehlend
        val twoMissing = fx.fleet.checklistTemplates.create(w.ctx("fleet_safety"), ChecklistTemplateInput.parse(linkedMapOf("name" to "Teilweise", "vehicle_class" to "PKW", "service_mode" to "STANDARD", "template_type" to "VEHICLE_TECHNICAL",
            "steps" to keys.drop(2).map { step(it, mandatory = it != "TUEREN") })))
        assertEquals(listOf("BELEUCHTUNG", "REIFEN_SICHTPRUEFUNG", "TUEREN"), problem { fx.fleet.checklistTemplates.publish(w.ctx("tenant_admin"), twoMissing.id, twoMissing.revision) }.violations.map { it.pointer.substringAfterLast('/') })
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.checklist_template_revision WHERE tenant_id = ? AND state = 'PUBLISHED'", w.tenant.value))
        // die Datenbank hält es auch bei direktem SQL (Wächter beim Veröffentlichen)
        val e = assertThrows(Exception::class.java) { fx.exec("UPDATE taktovia.checklist_template_revision SET state='PUBLISHED', published_at=now(), approved_by='user:other' WHERE id = ?", twoMissing.id) }
        assertTrue(generateSequence<Throwable>(e) { it.cause }.any { it.message?.contains("MANDATORY_POINTS_MISSING") == true })
        // allgemeine Vorlagen haben keine Pflichtpunkte; der Typ wird geprüft; vollständig ist freigebbar; Ersteller kann nicht selbst freigeben
        val general = fx.fleet.checklistTemplates.create(w.ctx("fleet_safety"), ChecklistTemplateInput.parse(linkedMapOf("name" to "Allgemein", "vehicle_class" to "BUS", "service_mode" to "STANDARD", "steps" to listOf(step("EINZEL")))))
        assertEquals(ChecklistTemplateType.GENERAL, general.templateType)
        assertEquals(ChecklistTemplateState.PUBLISHED, fx.fleet.checklistTemplates.publish(w.ctx("tenant_admin"), general.id, general.revision).state)
        assertEquals(ViolationCode.NOT_IN_ENUM, problem { ChecklistTemplateInput.parse(linkedMapOf("name" to "x", "vehicle_class" to "PKW", "service_mode" to "STANDARD", "template_type" to "ANDERE", "steps" to listOf(step("EINZEL")))) }.violations.single().code)
        val complete = fx.fleet.checklistTemplates.create(w.ctx("fleet_safety"), ChecklistTemplateInput.parse(linkedMapOf("name" to "Voll", "vehicle_class" to "PKW", "service_mode" to "STANDARD", "template_type" to "VEHICLE_TECHNICAL", "steps" to keys.map { step(it) } + step("ZUSATZ"))))
        assertEquals(ChecklistTemplateState.PUBLISHED, fx.fleet.checklistTemplates.publish(w.ctx("tenant_admin"), complete.id, complete.revision).state, "betreiberspezifische Zusatzpunkte sind erlaubt")
        // der Typ bleibt in jeder weiteren Revision erhalten
        val next = fx.fleet.checklistTemplates.newRevision(w.ctx("fleet_safety"), complete.id, fx.fleet.checklistTemplates.get(w.ctx("fleet_safety"), complete.id).revision)
        assertEquals(ChecklistTemplateType.VEHICLE_TECHNICAL, next.templateType)
        assertEquals(ViolationCode.NOT_IN_ENUM, problem { fx.fleet.checklistTemplates.createStandard(w.ctx("fleet_safety"), ChecklistTemplateType.GENERAL, "PKW", "STANDARD") }.violations.single().code, "für allgemeine Vorlagen gibt es keinen Standard")
    }

    private fun step(code: String, mandatory: Boolean = true) = linkedMapOf("step_code" to code, "title" to code, "input_kind" to "OK_DEFECT_NA", "mandatory" to mandatory, "safety_critical" to false)
}

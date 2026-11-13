package de.taktovia.fleetsafety

import de.taktovia.fleetsafety.api.AnswerInput
import de.taktovia.fleetsafety.api.ChecklistTemplateInput
import de.taktovia.fleetsafety.api.DispatchVerdict
import de.taktovia.fleetsafety.api.InspectionOutcome
import de.taktovia.fleetsafety.api.InspectionView
import de.taktovia.fleetsafety.api.ReleaseGate
import de.taktovia.fleetsafety.api.SubmitInspection
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.ViolationCode
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
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
 * WP-M3-12 / LH-6.21-CHK-007 gegen die echte Datenbank, ohne Mocks: ausstattungsabhängige Prüfpunkte (Rampe/Lift, Rollstuhlverankerung, zugängliche Einstiegsstelle, Bedienelemente) entstehen aus der
 * Ausstattung des Fahrzeugs; ein Ausfall schließt das Fahrzeug von Fahrten mit Rollstuhlbedarf aus („eingeschränkt“, reduzierter Ressourcenvektor) oder sperrt es (kritisches Element); „Nicht prüfbar“
 * gilt nie als Ja. Positiv: volle Freigabe bei funktionsfähiger Ausstattung. Negativ: defekte Rampe, nicht prüfbar, Antwort auf einen Punkt, den das Fahrzeug nicht hat. Konkurrierend: zwei
 * gleichzeitige Prüfabschlüsse desselben Laufs ergeben genau ein wirksames Ergebnis.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccessibilityReadinessTest {
    private lateinit var fx: FleetFixture
    private lateinit var w: ReadinessWorld

    @BeforeAll fun up() { fx = FleetFixture(); w = ReadinessWorld(fx); template() }
    @AfterAll fun down() = fx.close()

    private val ramp = listOf("RAMPE_LIFT_FUNKTION", "ROLLSTUHLVERANKERUNG", "EINSTIEGSSTELLE_ZUGAENGLICH", "BEDIENELEMENTE_BARRIEREFREI")
    private fun step(code: String, extra: Map<String, Any?> = emptyMap()) = linkedMapOf<String, Any?>("step_code" to code, "title" to "Punkt $code", "input_kind" to "OK_DEFECT_NA", "mandatory" to true, "safety_critical" to false) + extra

    /** Vorlage für die Klasse BARRIEREFREI: ein allgemeiner Punkt und die vier Ausstattungspunkte (die Verankerung ist kritisch und sperrt, die übrigen schränken ein). */
    private fun template() {
        val steps = listOf(step("BELEUCHTUNG")) + ramp.map { step(it, mapOf("equipment_condition" to "RAMP_OR_LIFT", "restricts_dimension" to "wheelchair", "safety_critical" to (it == "ROLLSTUHLVERANKERUNG"), "block_kind" to (if (it == "ROLLSTUHLVERANKERUNG") "VEHICLE" else null))) }
        val author = w.ctx("fleet_safety")
        val d = fx.fleet.checklistTemplates.create(author, ChecklistTemplateInput.parse(linkedMapOf("name" to "Barrierefrei", "vehicle_class" to "BARRIEREFREI", "service_mode" to "STANDARD", "steps" to steps)))
        fx.fleet.checklistTemplates.publish(w.ctx("tenant_admin"), d.id, d.revision)
    }

    private class Run(val driver: UUID, val vehicle: UUID, val shift: UUID, val id: UUID, val ctx: de.taktovia.platform.context.RequestContext)

    private fun run(equipment: List<String>): Run {
        val driver = w.driver(true, UUID.randomUUID()); val vehicle = w.iw.vehicle("BARRIEREFREI")
        fx.exec("UPDATE taktovia.vehicle SET equipment = CAST(? AS text[]), capacity_config = CAST(? AS jsonb) WHERE id = ?", equipment.joinToString(",", "{", "}"),
            """{"schema_version":"1.0","seats":4,"wheelchair":${if (equipment.isEmpty()) 0 else 1},"equipment":[${equipment.joinToString(",") { "\"$it\"" }}]}""", vehicle)
        val shift = w.iw.shift(driver, vehicle); val ctx = w.iw.ctx(driver, "driver")
        return Run(driver, vehicle, shift, w.iw.start(ctx, shift, vehicle, "PRE_SHIFT").inspection.id, ctx)
    }

    private fun answers(r: Run, overrides: Map<String, String> = emptyMap()): List<AnswerInput> {
        val codes = fx.fleet.inspections.get(r.ctx, r.id).template.steps.map { it.code }
        return codes.map { AnswerInput(it, overrides[it] ?: "OK", null, if ((overrides[it] ?: "OK") == "DEFECT") "defekt" else null, Instant.now().minusSeconds(30)) }
    }

    private fun submit(r: Run, answers: List<AnswerInput>): InspectionView =
        fx.fleet.inspections.submit(r.ctx, r.id, fx.fleet.inspections.get(r.ctx, r.id).revision, SubmitInspection(answers, Instant.now().minusSeconds(5), null))

    private fun verdict(r: Run) = fx.fleet.readiness.assertDispatchable(w.ctx("dispatcher"), r.vehicle, r.shift, null, Instant.now())

    @Test
    fun fahrzeug_mit_rampe_erhaelt_vier_pruefpunkte_ohne_rampe_nicht() {
        val withRamp = run(listOf("RAMP")); val withLift = run(listOf("LIFT")); val without = run(emptyList())
        for (r in listOf(withRamp, withLift)) assertEquals((listOf("BELEUCHTUNG") + ramp).toSet(), fx.fleet.inspections.get(r.ctx, r.id).template.steps.map { it.code }.toSet(), "Rampe oder Lift: alle vier Ausstattungspunkte")
        assertEquals(5, fx.fleet.inspections.get(withRamp.ctx, withRamp.id).template.steps.size)
        assertEquals(5, fx.fleet.inspections.get(withLift.ctx, withLift.id).template.steps.size)
        assertEquals(listOf("BELEUCHTUNG"), fx.fleet.inspections.get(without.ctx, without.id).template.steps.map { it.code }, "ohne Rampe/Lift keine Ausstattungspunkte")
        // eine Antwort auf einen Punkt, den das Fahrzeug nicht hat, wird abgewiesen (422 mit Verweis), nichts wird gespeichert
        val bad = assertThrows(ProblemException::class.java) { submit(without, answers(without) + AnswerInput("RAMPE_LIFT_FUNKTION", "OK", null, null, Instant.now().minusSeconds(30))) }
        assertTrue(bad.violations.any { it.pointer == "/answers/RAMPE_LIFT_FUNKTION" && it.code == ViolationCode.NOT_IN_ENUM })
        assertEquals("IN_PROGRESS", fx.fleet.inspections.get(without.ctx, without.id).status)
        // ohne Ausstattung bleibt das Fahrzeug für Fahrten ohne Rollstuhl voll freigegeben (nur ein Punkt)
        val done = submit(without, answers(without)); assertEquals(InspectionOutcome.PASSED.name, done.outcome)
    }

    @Test
    fun defekte_rampe_schliesst_rollstuhlfahrten_aus() {
        // positiv: funktionsfähige Ausstattung, volle Freigabe inklusive Rollstuhldimension
        val ok = run(listOf("RAMP")); val full = submit(ok, answers(ok))
        assertEquals(InspectionOutcome.PASSED.name, full.outcome); assertNull(full.restriction)
        assertTrue(verdict(ok) is DispatchVerdict.Dispatchable); assertTrue((verdict(ok) as DispatchVerdict.Dispatchable).unavailableDimensions.isEmpty())
        // negativ: defekte Rampe (nicht kritisch): eingeschränkt, die Dimension Rollstuhl entfällt, Freigabe bleibt für andere Fahrten
        val bad = run(listOf("RAMP")); val restricted = submit(bad, answers(bad, mapOf("RAMPE_LIFT_FUNKTION" to "DEFECT")))
        assertEquals(InspectionOutcome.PASSED_RESTRICTED.name, restricted.outcome); assertEquals(setOf("wheelchair"), restricted.restriction!!.unavailableDimensions); assertEquals(listOf("RAMPE_LIFT_FUNKTION"), restricted.restriction!!.stepCodes)
        assertTrue(restricted.release != null, "für Fahrten ohne Rollstuhl bleibt die Freigabe")
        val v = verdict(bad) as DispatchVerdict.Dispatchable; assertEquals(setOf("wheelchair"), v.unavailableDimensions)
        val req = fx.fleet.readiness.requirements(w.ctx("dispatcher"), bad.vehicle, bad.shift); assertEquals(ReleaseGate.RELEASED, req.gate); assertEquals(setOf("wheelchair"), req.unavailableDimensions)
        // je nach Einstufung des Punkts gesperrt: defekte Rollstuhlverankerung (kritisch) sperrt das Fahrzeug, keine Freigabe
        val crit = run(listOf("LIFT")); val blocked = submit(crit, answers(crit, mapOf("ROLLSTUHLVERANKERUNG" to "DEFECT")))
        assertEquals(InspectionOutcome.BLOCKED.name, blocked.outcome); assertNull(blocked.release); assertTrue(verdict(crit) is DispatchVerdict.Refused)
    }

    @Test
    fun nicht_pruefbar_gilt_nicht_als_ja() {
        val r = run(listOf("RAMP"))
        // „Nicht prüfbar“ ist nur für Ausstattungspunkte zugelassen; bei einem allgemeinen Punkt bleibt der vierte Statuswert abgewiesen (OP-16)
        val general = assertThrows(ProblemException::class.java) { submit(r, answers(r, mapOf("BELEUCHTUNG" to "NOT_CHECKABLE"))) }
        assertTrue(general.violations.any { it.pointer == "/answers/BELEUCHTUNG/answer" })
        // bei der Rampe: Ergebnis eingeschränkt, nie volle Freigabe für Rollstuhlfahrten
        val done = submit(r, answers(r, mapOf("RAMPE_LIFT_FUNKTION" to "NOT_CHECKABLE")))
        assertEquals(InspectionOutcome.PASSED_RESTRICTED.name, done.outcome); assertEquals(setOf("wheelchair"), done.restriction!!.unavailableDimensions)
        assertEquals(setOf("wheelchair"), (verdict(r) as DispatchVerdict.Dispatchable).unavailableDimensions)
        // „Nicht anwendbar“ gibt es für Ausstattungspunkte nicht (das Fahrzeug hat die Ausstattung)
        val r2 = run(listOf("RAMP"))
        assertTrue(assertThrows(ProblemException::class.java) { submit(r2, answers(r2, mapOf("EINSTIEGSSTELLE_ZUGAENGLICH" to "NOT_APPLICABLE"))) }.violations.any { it.pointer == "/answers/EINSTIEGSSTELLE_ZUGAENGLICH/answer" })
    }

    @Test
    fun zwei_gleichzeitige_pruefabschluesse_ergeben_genau_ein_wirksames_ergebnis() {
        repeat(5) {
            val r = run(listOf("RAMP")); val a = answers(r, mapOf("RAMPE_LIFT_FUNKTION" to "DEFECT")); val rev = fx.fleet.inspections.get(r.ctx, r.id).revision
            val pool = Executors.newFixedThreadPool(2); val go = CountDownLatch(1)
            val futures = (1..2).map { pool.submit<Result<InspectionView>> { go.await(); runCatching { fx.fleet.inspections.submit(r.ctx, r.id, rev, SubmitInspection(a, Instant.now().minusSeconds(5), null)) } } }
            go.countDown(); val results = futures.map { it.get(30, TimeUnit.SECONDS) }; pool.shutdown()
            assertEquals(1, results.count { it.isSuccess }, "genau ein Abschluss wirkt")
            assertEquals(1, fx.count("SELECT count(*) FROM taktovia.inspection_run WHERE id = ? AND status = 'COMPLETED' AND outcome = 'PASSED_RESTRICTED' AND restriction IS NOT NULL", r.id))
            assertEquals(1, fx.count("SELECT count(*) FROM taktovia.resource_release WHERE inspection_ref = ?", r.id))
        }
    }
}

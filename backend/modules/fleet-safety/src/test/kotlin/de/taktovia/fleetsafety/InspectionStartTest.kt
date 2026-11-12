package de.taktovia.fleetsafety

import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.ViolationCode
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M3-13 / LH-6.21-CHK-002 unter der Runtime-Rolle gegen die echte Datenbank. Originalabnahme: der Fahrer erhält exakt die freigegebene Vorlage für sein Fahrzeug und seinen Dienst.
 * Planungsannahme PA-08: Fahrzeugklasse = `vehicle.vehicle_type`, Bedienart = `vehicle.operation_kind`. Positiv (zwei Klassen, zwei Vorlagen), negativ (keine, mehrere, Entwurf, abgelöst,
 * fremder Mandant, fremde Schicht, ohne Dienstanmeldung, Clientvorlage) und konkurrierend (Doppelstart, Freigabe parallel zum Start).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InspectionStartTest {
    private lateinit var fx: FleetFixture

    @BeforeAll fun up() { fx = FleetFixture() }
    @AfterAll fun down() = fx.close()

    private fun problem(block: () -> Unit): ProblemException = assertThrows(ProblemException::class.java, block)

    @Test
    fun fahrer_erhaelt_exakt_freigegebene_vorlage() {
        val w = InspectionWorld(fx)
        val pkwTpl = w.published("PKW", steps = 3); val busTpl = w.published("KLEINBUS", steps = 5)
        val d1 = UUID.randomUUID(); val d2 = UUID.randomUUID()
        val pkw = w.vehicle("PKW"); val bus = w.vehicle("KLEINBUS")
        val s1 = w.shift(d1, pkw); val s2 = w.shift(d2, bus)

        val a = w.start(w.ctx(d1, "driver"), s1, pkw); val b = w.start(w.ctx(d2, "driver"), s2, bus)
        assertTrue(a.created); assertTrue(b.created)
        // zwei Fahrzeugklassen, zwei verschiedene Vorlagen, jeweils exakt die freigegebene Revision
        assertEquals(pkwTpl.id, a.inspection.templateRevisionId); assertEquals(busTpl.id, b.inspection.templateRevisionId)
        assertNotEquals(a.inspection.templateRevisionId, b.inspection.templateRevisionId)
        assertEquals(3, a.inspection.template.steps.size); assertEquals(5, b.inspection.template.steps.size)   // „Prüfung n / Gesamt“ = Schrittzahl der gewählten Revision
        assertEquals(listOf("STEP_1", "STEP_2", "STEP_3"), a.inspection.template.steps.map { it.code })
        assertEquals("IN_PROGRESS", a.inspection.status); assertNull(a.inspection.outcome); assertNull(a.inspection.release); assertEquals(1, a.inspection.revision)
        assertEquals(d1, a.inspection.driverRef); assertEquals(s1, a.inspection.shiftId); assertEquals(pkw, a.inspection.vehicleId); assertEquals("PKW", a.inspection.template.vehicleClass)
        // Bedienart zählt mit: dieselbe Klasse, andere Bedienart -> andere Vorlage
        val assistTpl = w.published("PKW", "ASSISTENZ", steps = 4)
        val assistVehicle = w.vehicle("PKW", "ASSISTENZ"); val d3 = UUID.randomUUID()
        val c = w.start(w.ctx(d3, "driver"), w.shift(d3, assistVehicle), assistVehicle)
        assertEquals(assistTpl.id, c.inspection.templateRevisionId); assertNotEquals(pkwTpl.id, c.inspection.templateRevisionId)
        // gespeichert wie geliefert, Audit je Start
        assertEquals(pkwTpl.id.toString(), fx.strings("SELECT template_revision_id::text FROM taktovia.inspection_run WHERE id = ?", a.inspection.id).single())
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='fleet.inspection.started' AND object_ref=?", w.tenant.value, "inspection:${a.inspection.id}"))
        // erneuter Start derselben Kombination liefert denselben Lauf (Idempotenz im Modul)
        val again = w.start(w.ctx(d1, "driver"), s1, pkw)
        assertFalse(again.created); assertEquals(a.inspection.id, again.inspection.id)
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.inspection_run WHERE tenant_id=? AND shift_id=?", w.tenant.value, s1))
        // lesen: Fahrer nur den eigenen Lauf, Disposition und Werkstatt jeden des Mandanten
        assertEquals(a.inspection.id, fx.fleet.inspections.get(w.ctx(d1, "driver"), a.inspection.id).id)
        assertEquals(a.inspection.id, fx.fleet.inspections.get(w.ctx(UUID.randomUUID(), "dispatcher"), a.inspection.id).id)
        assertEquals(404, problem { fx.fleet.inspections.get(w.ctx(d2, "driver"), a.inspection.id) }.code.status)
    }

    @Test
    fun keine_passende_vorlage_kein_pruflauf() {
        val w = InspectionWorld(fx)
        fun noRun(shift: UUID) = assertEquals(0, fx.count("SELECT count(*) FROM taktovia.inspection_run WHERE tenant_id=? AND shift_id=?", w.tenant.value, shift))

        // 1. keine Vorlage für die Klasse (BUS): kein Lauf, 409 mit stabilem Kennwort, Hinweis (Audit und Ereignis) an die Leitstelle
        val d1 = UUID.randomUUID(); val bus = w.vehicle("BUS"); val s1 = w.shift(d1, bus)
        val e1 = problem { w.start(w.ctx(d1, "driver"), s1, bus) }
        assertEquals(ErrorCode.CONFLICT, e1.code); assertTrue(e1.detail!!.startsWith("TEMPLATE_UNAVAILABLE"), e1.detail); noRun(s1)
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='fleet.inspection.template_unavailable' AND object_ref=?", w.tenant.value, "staff_shift:$s1"))
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND event_type='inspection.template_unavailable' AND payload->>'shift_id' = ? AND payload->>'reason' = 'NO_TEMPLATE' AND payload->>'vehicle_class' = 'BUS'", w.tenant.value, s1.toString()))
        // der Hinweis enthält nur Kennungen, Codes und Zähler
        val payload = fx.strings("SELECT payload::text FROM taktovia.outbox WHERE tenant_id=? AND event_type='inspection.template_unavailable'", w.tenant.value).single()
        assertFalse(payload.contains(d1.toString()), payload)
        // 2. Vorlage nur im Entwurf: wird nicht verwendet
        w.draft("BUS")
        val d2 = UUID.randomUUID(); val s2 = w.shift(d2, bus, fromHours = 7, toHours = 13, started = true)
        assertEquals(ErrorCode.CONFLICT, problem { w.start(w.ctx(d2, "driver"), s2, bus) }.code); noRun(s2)
        // 3. Fahrzeug ohne gepflegte Fahrzeugklasse
        val none = w.vehicle(null); val d3 = UUID.randomUUID(); val s3 = w.shift(d3, none)
        assertTrue(problem { w.start(w.ctx(d3, "driver"), s3, none) }.detail!!.startsWith("TEMPLATE_UNAVAILABLE")); noRun(s3)
        // 4. Vorlage eines anderen Mandanten wird nie gewählt
        val other = InspectionWorld(fx); other.published("RAMPE")
        val ramp = w.vehicle("RAMPE"); val d4 = UUID.randomUUID(); val s4 = w.shift(d4, ramp)
        assertTrue(problem { w.start(w.ctx(d4, "driver"), s4, ramp) }.detail!!.startsWith("TEMPLATE_UNAVAILABLE")); noRun(s4)
        // 5. abgelöste Revision zählt nicht: nach der Freigabe der Folgerevision wird die abgelöste nie gewählt
        val gone = w.published("KLEINBUS", "SONDER"); val next = fx.fleet.checklistTemplates.newRevision(w.ctx(UUID.randomUUID(), "fleet_safety"), gone.id, gone.revision)
        val kb = w.vehicle("KLEINBUS", "SONDER"); val d5 = UUID.randomUUID(); val s5 = w.shift(d5, kb)
        val pub = w.publish(next)
        val run = w.start(w.ctx(d5, "driver"), s5, kb)
        assertEquals(pub.id, run.inspection.templateRevisionId); assertNotEquals(gone.id, run.inspection.templateRevisionId)
        // 6. mehrere eindeutig passende Vorlagen (zwei Vorlagen, gleiches Paar): kein Lauf, Kennwort AMBIGUOUS
        w.published("PKW", "DOPPELT"); w.published("PKW", "DOPPELT")
        val dv = w.vehicle("PKW", "DOPPELT"); val d6 = UUID.randomUUID(); val s6 = w.shift(d6, dv)
        val e6 = problem { w.start(w.ctx(d6, "driver"), s6, dv) }
        assertEquals(ErrorCode.CONFLICT, e6.code); assertTrue(e6.detail!!.startsWith("TEMPLATE_AMBIGUOUS")); noRun(s6)
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND event_type='inspection.template_unavailable' AND payload->>'shift_id' = ? AND payload->>'reason' = 'AMBIGUOUS_TEMPLATE' AND (payload->>'candidate_count')::int = 2", w.tenant.value, s6.toString()))
    }

    @Test
    fun fremde_schicht_ergibt_404_und_client_vorlage_wirkungslos() {
        val w = InspectionWorld(fx); val tpl = w.published("PKW"); val wrong = w.published("BUS", steps = 2)
        val d = UUID.randomUUID(); val pkw = w.vehicle("PKW"); val s = w.shift(d, pkw)
        // Schicht eines anderen Fahrers: 404, kein Lauf
        val intruder = UUID.randomUUID()
        assertEquals(404, problem { w.start(w.ctx(intruder, "driver"), s, pkw) }.code.status)
        // Schicht eines anderen Mandanten: für den eigenen Mandanten nicht vorhanden (404)
        val o = InspectionWorld(fx); val od = UUID.randomUUID(); val ov = o.vehicle("PKW"); val os = o.shift(od, ov)
        assertEquals(404, problem { w.start(w.ctx(d, "driver"), os, ov) }.code.status)
        assertEquals(404, problem { w.start(w.ctx(od, "driver", tenant = o.tenant), s, pkw) }.code.status)
        // unbekannte Schicht
        assertEquals(404, problem { w.start(w.ctx(d, "driver"), UUID.randomUUID(), pkw) }.code.status)
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.inspection_run WHERE tenant_id=?", w.tenant.value))
        // Fahrzeug nicht das der eigenen Schicht: 422 mit Feldverweis, kein Lauf
        val e = problem { w.start(w.ctx(d, "driver"), s, w.vehicle("PKW")) }
        assertEquals(ErrorCode.VALIDATION_FAILED, e.code); assertEquals("/vehicle_id", e.violations.single().pointer)
        // ohne Dienstanmeldung (Schicht nur geplant) oder nach Dienstende: kein Lauf
        val d2 = UUID.randomUUID(); val v2 = w.vehicle("PKW"); val planned = w.shift(d2, v2, started = false)
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, problem { w.start(w.ctx(d2, "driver"), planned, v2) }.code)
        val d3 = UUID.randomUUID(); val v3 = w.vehicle("PKW"); val ended = w.shift(d3, v3, state = "ENDED", fromHours = -9, toHours = -1)
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, problem { w.start(w.ctx(d3, "driver"), ended, v3) }.code)
        // Rollen: Disposition und Fahrgast starten keinen Lauf
        assertEquals(403, problem { w.start(w.ctx(d, "dispatcher"), s, pkw) }.code.status)
        assertEquals(403, problem { w.start(w.ctx(d, "passenger"), s, pkw) }.code.status)
        // Gerätezeit in der Zukunft
        assertEquals(ErrorCode.VALIDATION_FAILED, problem { w.fx.fleet.inspections.start(w.ctx(d, "driver"), de.taktovia.fleetsafety.api.StartInspection(s, pkw, "PRE_SHIFT", java.time.Instant.now().plusSeconds(3600))) }.code)
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.inspection_run WHERE tenant_id=?", w.tenant.value))
        // Clientvorlage: eine mitgesendete (falsche, auch fremde) Revision ändert die serverbestimmte nicht
        val r = w.start(w.ctx(d, "driver"), s, pkw, extra = mapOf("template_revision_id" to wrong.id.toString()))
        assertEquals(tpl.id, r.inspection.templateRevisionId); assertNotEquals(wrong.id, r.inspection.templateRevisionId)
        // unbekannte Felder (Mandant, Fahrer) sind kein Eingabeweg
        assertEquals(ErrorCode.VALIDATION_FAILED, problem { w.start(w.ctx(d, "driver"), s, pkw, extra = mapOf("driver_ref" to intruder.toString())) }.code)
        val bad = problem { de.taktovia.fleetsafety.api.StartInspection.parse(mapOf("shift_id" to "x", "kind" to "NIE", "client_started_at" to "gestern")) }
        assertEquals(setOf("/shift_id", "/vehicle_id", "/kind", "/client_started_at"), bad.violations.map { it.pointer }.toSet())
        assertTrue(bad.violations.any { it.pointer == "/vehicle_id" && it.code == ViolationCode.REQUIRED })
    }

    @Test
    fun neue_revision_laufender_lauf_behaelt_seine() {
        val w = InspectionWorld(fx); val v1 = w.published("PKW", steps = 3)
        val d = UUID.randomUUID(); val vehicle = w.vehicle("PKW"); val s = w.shift(d, vehicle)
        val run = w.start(w.ctx(d, "driver"), s, vehicle).inspection
        assertEquals(v1.id, run.templateRevisionId)
        // neue Revision (mit anderer Schrittzahl) freigeben: die alte wird abgelöst
        val ctx = w.ctx(UUID.randomUUID(), "fleet_safety")
        val draft = fx.fleet.checklistTemplates.newRevision(ctx, v1.id, v1.revision)
        val v2 = w.publish(fx.fleet.checklistTemplates.updateDraft(ctx, draft.id, draft.revision, de.taktovia.fleetsafety.api.ChecklistTemplateInput.parse(linkedMapOf("name" to "neu", "vehicle_class" to "PKW", "service_mode" to "STANDARD",
            "steps" to (1..6).map { linkedMapOf("step_code" to "NEU_$it", "title" to "Punkt $it", "input_kind" to "OK_DEFECT_NA", "mandatory" to true, "safety_critical" to false) }))))
        assertEquals("PUBLISHED", v2.state.name)
        assertEquals("RETIRED", fx.strings("SELECT state FROM taktovia.checklist_template_revision WHERE id = ?", v1.id).single())
        // der laufende Lauf behält Revision und Prüfschritte; ein erneuter Start liefert denselben Lauf
        val after = fx.fleet.inspections.get(w.ctx(d, "driver"), run.id)
        assertEquals(v1.id, after.templateRevisionId); assertEquals(3, after.template.steps.size); assertEquals(listOf("STEP_1", "STEP_2", "STEP_3"), after.template.steps.map { it.code })
        val again = w.start(w.ctx(d, "driver"), s, vehicle)
        assertFalse(again.created); assertEquals(run.id, again.inspection.id); assertEquals(v1.id, again.inspection.templateRevisionId)
        // ein neuer Lauf (der alte ist abgeschlossen oder abgebrochen) erhält die neue Revision
        fx.exec("UPDATE taktovia.inspection_run SET status = 'CANCELED' WHERE id = ?", run.id)
        val fresh = w.start(w.ctx(d, "driver"), s, vehicle)
        assertTrue(fresh.created); assertNotEquals(run.id, fresh.inspection.id); assertEquals(v2.id, fresh.inspection.templateRevisionId); assertEquals(6, fresh.inspection.template.steps.size)
    }

    @Test
    fun doppelstart_zwei_geraete_ein_offener_lauf() {
        val w = InspectionWorld(fx); val tpl = w.published("PKW")
        val d = UUID.randomUUID(); val vehicle = w.vehicle("PKW"); val s = w.shift(d, vehicle)
        val threads = 8; val pool = Executors.newFixedThreadPool(threads); val gate = CountDownLatch(1)
        try {
            val futures = (1..threads).map { pool.submit<Pair<UUID, Boolean>> { gate.await(); w.start(w.ctx(d, "driver"), s, vehicle).let { it.inspection.id to it.created } } }
            gate.countDown()
            val results = futures.map { it.get(60, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it.second }, "genau ein Start legt an")
            assertEquals(1, results.map { it.first }.toSet().size, "alle erhalten denselben Lauf")
            assertEquals(1, fx.count("SELECT count(*) FROM taktovia.inspection_run WHERE tenant_id=? AND shift_id=? AND status='IN_PROGRESS'", w.tenant.value, s))
            assertEquals(1, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='fleet.inspection.started'", w.tenant.value))
            assertEquals(tpl.id.toString(), fx.strings("SELECT template_revision_id::text FROM taktovia.inspection_run WHERE shift_id = ?", s).single())
        } finally { pool.shutdownNow() }
    }

    @Test
    fun doppelstart_und_vorlagenfreigabe_parallel_der_lauf_haelt_genau_eine_vollstaendige_revision() {
        repeat(5) { round ->
            val w = InspectionWorld(fx); val v1 = w.published("PKW", steps = 3)
            val ctx = w.ctx(UUID.randomUUID(), "fleet_safety")
            val draft = fx.fleet.checklistTemplates.newRevision(ctx, v1.id, v1.revision)
            val d = UUID.randomUUID(); val vehicle = w.vehicle("PKW"); val s = w.shift(d, vehicle)
            val pool = Executors.newFixedThreadPool(3); val gate = CountDownLatch(1)
            try {
                val start = pool.submit<de.taktovia.fleetsafety.api.InspectionView> { gate.await(); w.start(w.ctx(d, "driver"), s, vehicle).inspection }
                val publish = pool.submit<Any> { gate.await(); w.fx.fleet.checklistTemplates.publish(w.ctx(UUID.randomUUID(), "fleet_safety"), draft.id, draft.revision) }
                gate.countDown()
                val run = start.get(60, TimeUnit.SECONDS); val v2 = publish.get(60, TimeUnit.SECONDS) as de.taktovia.fleetsafety.api.ChecklistTemplateView
                // die Revision des Laufs ist die zur Zeit des Starts freigegebene: entweder die alte (Start vor Freigabe) oder die neue, nie etwas Drittes
                assertTrue(run.templateRevisionId == v1.id || run.templateRevisionId == v2.id, "Runde $round")
                val steps = fx.count("SELECT count(*) FROM taktovia.checklist_step WHERE template_revision_id = ?", run.templateRevisionId)
                assertEquals(steps, run.template.steps.size, "vollständig, keine Mischung (Runde $round)")
                assertEquals(1, fx.count("SELECT count(*) FROM taktovia.inspection_run WHERE tenant_id=? AND shift_id=?", w.tenant.value, s))
            } finally { pool.shutdownNow() }
        }
    }
}

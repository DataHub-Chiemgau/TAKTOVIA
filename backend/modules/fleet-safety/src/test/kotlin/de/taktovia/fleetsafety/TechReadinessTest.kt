package de.taktovia.fleetsafety

import de.taktovia.fleetsafety.api.AlertKinds
import de.taktovia.fleetsafety.api.AlertState
import de.taktovia.fleetsafety.api.AnswerInput
import de.taktovia.fleetsafety.api.ChecklistTemplateType
import de.taktovia.fleetsafety.api.DispatchVerdict
import de.taktovia.fleetsafety.api.SubmitInspection
import de.taktovia.platform.alert.AlertPriority
import de.taktovia.platform.context.Actor
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.tenant.TenantId
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
 * WP-M3-18 / LH-6.21-CHK-009 unter der Runtime-Rolle gegen die echte Datenbank. Originalabnahme: Fehler erzeugen ein Technikereignis und einen für die Leitstelle sichtbaren Hinweis.
 * Positiv (alles funktionsfähig: kein Ereignis; Vorlage mit je einem Punkt für Fahrer-App, Mobilfunk, Navigation, Zubehör), negativ (genau ein Ereignis mit Lauf, Fahrzeug, Schicht und
 * Hinweis am Fahrzeug; Duplikat; nicht berechtigter Dienst und fremder Mandant; keine Personendaten; Fahrzeug bleibt disponierbar) und konkurrierend (zwei parallele Übermittlungen).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TechReadinessTest {
    private lateinit var fx: FleetFixture

    @BeforeAll fun up() { fx = FleetFixture() }
    @AfterAll fun down() = fx.close()

    private val techPoints = listOf("FAHRER_APP", "MOBILFUNK", "NAVIGATION", "DIENSTZUBEHOER")

    private fun problem(block: () -> Unit): ProblemException = assertThrows(ProblemException::class.java, block)

    private class Tech(val w: ReadinessWorld, val driver: UUID, val vehicle: UUID, val shift: UUID, val run: UUID, val ctx: RequestContext)

    /** Gestarteter Technikbereitschaftslauf eines Fahrers (Vorlage Typ TECHNICAL_READINESS, freigegeben), Fahrzeug optional einem Dienst zugeordnet. */
    private fun techRun(w: ReadinessWorld = ReadinessWorld(fx), service: UUID? = null): Tech {
        w.standard(ChecklistTemplateType.TECHNICAL_READINESS)
        val driver = w.driver(true, UUID.randomUUID()); val vehicle = w.iw.vehicle("PKW"); val shift = w.iw.shift(driver, vehicle)
        if (service != null) pool(w, service, vehicle)
        val ctx = w.iw.ctx(driver, "driver")
        val started = w.iw.start(ctx, shift, vehicle, "TECH_READINESS")
        return Tech(w, driver, vehicle, shift, started.inspection.id, ctx)
    }

    private fun service(w: ReadinessWorld): UUID {
        val t = w.tenant.value; val cfg = UUID.randomUUID(); val svc = UUID.randomUUID()
        fx.exec("INSERT INTO taktovia.config_revision(tenant_id,id,scope_kind,scope_id,revision,schema_version,values_json,effective_from,created_by,reason) VALUES (?,?,'SERVICE',?,1,'1',CAST('{}' AS jsonb),'2026-01-01T00:00:00Z','user:t','Test')", t, cfg, svc)
        fx.exec("INSERT INTO taktovia.service(tenant_id,id,operator_id,name,timezone,config_revision_id,created_by) VALUES (?,?,?,'Dienst','Europe/Berlin',?,'user:t')", t, svc, w.iw.w.orgs[0], cfg)
        return svc
    }

    private fun pool(w: ReadinessWorld, service: UUID, vehicle: UUID) =
        fx.exec("INSERT INTO taktovia.vehicle_pool_assignment(tenant_id,id,service_id,vehicle_id,valid_from,created_by) VALUES (?,?,?,?,'2026-01-01T00:00:00Z','user:t')", w.tenant.value, UUID.randomUUID(), service, vehicle)

    private fun answers(vararg defects: String) = techPoints.map { code ->
        if (code in defects) AnswerInput(code, "DEFECT", null, "Funktion gestoert", Instant.now().minusSeconds(30)) else AnswerInput(code, "OK", null, null, Instant.now().minusSeconds(30))
    }

    private fun submit(t: Tech, answers: List<AnswerInput>, revision: Long? = null) =
        fx.fleet.inspections.submit(t.ctx, t.run, revision ?: fx.fleet.inspections.get(t.ctx, t.run).revision, SubmitInspection(answers, Instant.now().minusSeconds(5), null))

    private fun dispatcher(tenant: TenantId, vararg services: UUID, roles: Set<String> = setOf("dispatcher")) = RequestContext(
        Actor(ActorType.HUMAN, "https://idp.test/realms/fleet", "disp-" + UUID.randomUUID(), "sid-" + UUID.randomUUID(), true), tenant, roles, setOf("operator:read", "operator:write"), services.toSet(), UUID.randomUUID())

    private fun techEvents(t: Tech) = fx.count("SELECT count(*) FROM taktovia.tech_event WHERE tenant_id = ? AND inspection_id = ?", t.w.tenant.value, t.run)
    private fun techAlerts(t: Tech) = fx.count("SELECT count(*) FROM taktovia.business_alert WHERE tenant_id = ? AND vehicle_id = ? AND alert_kind = 'TECHNICAL_EVENT'", t.w.tenant.value, t.vehicle)

    @Test
    fun vorlage_enthaelt_je_einen_punkt_und_alles_funktionsfaehig_erzeugt_kein_ereignis() {
        val t = techRun()
        val view = fx.fleet.inspections.get(t.ctx, t.run)
        assertEquals("TECH_READINESS", view.kind)
        assertEquals(techPoints.sorted(), view.template.steps.map { it.code }.sorted(), "je ein Punkt für Fahrer-App, Mobilfunk, Navigation, Zubehör")
        val done = submit(t, answers())
        assertEquals("COMPLETED", done.status); assertEquals("PASSED", done.outcome)
        assertEquals(0, techEvents(t)); assertEquals(0, techAlerts(t))
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id = ? AND event_type = 'inspection.tech_event_reported'", t.w.tenant.value))
        assertNull(done.release, "die Technikbereitschaft erteilt keine Einsatzfreigabe")
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.resource_release WHERE inspection_ref = ?", t.run))
    }

    @Test
    fun technikfehler_erzeugt_genau_ein_ereignis_und_leitstellenhinweis() {
        val w = ReadinessWorld(fx); val svc = service(w)
        val t = techRun(w, svc)
        val safetyBefore = fx.strings("SELECT safety_revision::text FROM taktovia.vehicle WHERE id = ?", t.vehicle).single()
        val done = submit(t, answers("NAVIGATION"))
        assertEquals("COMPLETED", done.status)
        // genau ein Technikereignis mit Bezug auf Prüflauf, Fahrzeug und Schicht
        assertEquals(1, techEvents(t))
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.tech_event WHERE tenant_id = ? AND inspection_id = ? AND vehicle_id = ? AND shift_id = ? AND step_code = 'NAVIGATION'", w.tenant.value, t.run, t.vehicle, t.shift))
        // Hinweis für die Leitstelle: Alarm niedriger Priorität, ohne manuelle Suche am Fahrzeug sichtbar
        val c = dispatcher(w.tenant, svc)
        val alerts = fx.fleet.alerts.list(c, AlertState.OPEN, 50, null).filter { it.vehicleId == t.vehicle }
        assertEquals(1, alerts.size); val a = alerts.single()
        assertEquals(AlertKinds.TECHNICAL_EVENT, a.kind); assertEquals(AlertPriority.NORMAL, a.priority); assertEquals("inspection_run", a.refType); assertEquals(t.run, a.refId)
        assertEquals("TECH_NAVIGATION", a.reasonCode); assertEquals(svc, a.serviceId)
        assertEquals(1, fx.fleet.operatorVehicles.get(c, t.vehicle).openAlertCount, "Hinweis am Fahrzeug")
        assertEquals(listOf(t.vehicle), fx.fleet.operatorVehicles.list(c, null, true, 50, null).map { it.id }, "Arbeitsliste: Fahrzeug mit offenem Hinweis")
        // Quellereignis des Alarms ist das Technikereignis
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.business_alert a JOIN taktovia.tech_event e ON e.tenant_id = a.tenant_id AND e.id = a.source_event_id WHERE a.id = ?", a.id))
        // Ereignis über die Outbox, ohne Freitext und Personen
        val payload = fx.strings("SELECT payload::text FROM taktovia.outbox WHERE tenant_id = ? AND event_type = 'inspection.tech_event_reported'", w.tenant.value).single()
        val keys = tools.jackson.databind.json.JsonMapper.builder().build().readTree(payload).properties().map { it.key }.toSet()
        assertEquals(setOf("tech_event_id", "inspection_id", "vehicle_id", "shift_id", "step_code"), keys)
        assertFalse(payload.contains("Funktion gestoert"), "keine Freitexte der Antwort im Ereignis")
        // der Alarm trägt keine Fahrgast-PII und keine Diagnosen: nur Codes und Kennungen (Alarmzeile hat keine Textspalten außer Codes)
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.business_alert WHERE id = ? AND (case_number IS NOT NULL OR affected_run_count IS NOT NULL)", a.id))
        // Technikfehler sperrt das Fahrzeug nicht (Annahme laut Offene Punkte)
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.vehicle_block WHERE vehicle_id = ?", t.vehicle))
        assertEquals(safetyBefore, fx.strings("SELECT safety_revision::text FROM taktovia.vehicle WHERE id = ?", t.vehicle).single(), "keine Sperre, keine neue Sicherheitsrevision")
        // das Ereignis steht auch im Audit
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id = ? AND action = 'fleet.inspection.tech_event_reported'", w.tenant.value))
        // eine Wiederholung (zweiter Abschluss desselben Laufs) erzeugt kein zweites Ereignis
        assertEquals(ErrorCode.IMMUTABLE_RECORD, problem { submit(t, answers("NAVIGATION"), done.revision) }.code)
        assertEquals(1, techEvents(t)); assertEquals(1, techAlerts(t))
    }

    @Test
    fun mehrere_defekte_punkte_ergeben_je_ein_ereignis() {
        val t = techRun()
        submit(t, answers("MOBILFUNK", "DIENSTZUBEHOER"))
        assertEquals(2, techEvents(t)); assertEquals(2, techAlerts(t))
    }

    @Test
    fun technikbereitschaft_beruehrt_die_einsatzfreigabe_nicht() {
        val t = techRun()
        submit(t, answers("NAVIGATION"))
        // ein offener oder abgeschlossener Technikbereitschaftslauf ersetzt die Prüfung vor Schichtbeginn nie
        val verdict = fx.fleet.readiness.assertDispatchable(t.w.ctx("dispatcher"), t.vehicle, t.shift, null, Instant.now())
        assertFalse(verdict is DispatchVerdict.Dispatchable, "ohne Fahrzeugprüfung keine Einsatzfreigabe")
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.resource_release WHERE shift_id = ?", t.shift))
    }

    @Test
    fun hinweis_nur_fuer_berechtigten_dienst_und_mandanten_sichtbar() {
        val w = ReadinessWorld(fx); val mine = service(w); val other = service(w)
        val t = techRun(w, mine)
        submit(t, answers("NAVIGATION"))
        val alertId = fx.strings("SELECT id::text FROM taktovia.business_alert WHERE tenant_id = ? AND vehicle_id = ?", w.tenant.value, t.vehicle).single().let(UUID::fromString)
        val ok = dispatcher(w.tenant, mine)
        assertNotNull(fx.fleet.alerts.get(ok, alertId))
        // Disponent eines nicht berechtigten Dienstes: Alarm und Fahrzeug existieren für ihn nicht (404, leere Liste)
        val wrongService = dispatcher(w.tenant, other)
        assertEquals(ErrorCode.NOT_FOUND, problem { fx.fleet.alerts.get(wrongService, alertId) }.code)
        assertTrue(fx.fleet.alerts.list(wrongService, null, 50, null).none { it.id == alertId })
        assertEquals(ErrorCode.NOT_FOUND, problem { fx.fleet.operatorVehicles.get(wrongService, t.vehicle) }.code)
        // ohne Dienstscope (fail-closed) ebenso
        assertEquals(ErrorCode.NOT_FOUND, problem { fx.fleet.alerts.get(dispatcher(w.tenant), alertId) }.code)
        // fremder Mandant
        val foreign = dispatcher(ReadinessWorld(fx).tenant, mine, other)
        assertEquals(ErrorCode.NOT_FOUND, problem { fx.fleet.alerts.get(foreign, alertId) }.code)
        assertTrue(fx.fleet.alerts.list(foreign, null, 50, null).isEmpty())
        assertEquals(ErrorCode.NOT_FOUND, problem { fx.fleet.operatorVehicles.get(foreign, t.vehicle) }.code)
        // Rolle ohne Leseweg (Fahrer) bekommt 403
        val driver = RequestContext(Actor(ActorType.HUMAN, "https://idp.test/realms/fleet", "drv-" + UUID.randomUUID(), "sid-x", true), w.tenant, setOf("driver"), setOf("driver:run"), setOf(mine), UUID.randomUUID())
        assertEquals(ErrorCode.FORBIDDEN, problem { fx.fleet.alerts.get(driver, alertId) }.code)
    }

    @Test
    fun fremder_fahrer_kann_den_lauf_nicht_abschliessen() {
        val t = techRun()
        val other = t.w.iw.ctx(UUID.randomUUID(), "driver")
        assertEquals(ErrorCode.NOT_FOUND, problem { fx.fleet.inspections.submit(other, t.run, 1, SubmitInspection(answers("NAVIGATION"), Instant.now().minusSeconds(5), null)) }.code)
        assertEquals(0, techEvents(t)); assertEquals(0, techAlerts(t))
    }

    @Test
    fun parallele_uebermittlung_desselben_schritts_erzeugt_ein_ereignis() {
        val t = techRun(); val revision = fx.fleet.inspections.get(t.ctx, t.run).revision
        val pool = Executors.newFixedThreadPool(6); val go = CountDownLatch(1)
        val results = (1..6).map { pool.submit<Any> { go.await(); try { submit(t, answers("NAVIGATION"), revision) } catch (e: Throwable) { e } } }
        go.countDown(); val outcomes = results.map { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(1, outcomes.count { it !is Throwable }, "genau ein Abschluss wirkt: $outcomes")
        outcomes.filterIsInstance<Throwable>().forEach { assertTrue(it is ProblemException && it.code in setOf(ErrorCode.REVISION_MISMATCH, ErrorCode.IMMUTABLE_RECORD), "Verlierer als fachlicher Fehler: $it") }
        assertEquals(1, techEvents(t)); assertEquals(1, techAlerts(t))
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id = ? AND event_type = 'inspection.tech_event_reported'", t.w.tenant.value))
    }
}

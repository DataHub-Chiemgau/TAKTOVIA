package de.taktovia.fleetsafety

import de.taktovia.fleetsafety.api.IncidentStatus
import de.taktovia.fleetsafety.api.ReplacementSearchResult
import de.taktovia.fleetsafety.api.WorkflowAction
import de.taktovia.fleetsafety.api.WorkflowActions
import de.taktovia.fleetsafety.api.WorkflowEventKinds
import de.taktovia.fleetsafety.api.WorkflowOutcome
import de.taktovia.fleetsafety.api.WorkflowRule
import de.taktovia.platform.operation.OperationRunner
import de.taktovia.platform.outbox.InboxOutcome
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
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
 * WP-M3-53 / LH-I.8-AUT-001: regelbasierter Workflow, Incident bei Fahrzeugausfall unter der Runtime-Rolle. Originalabnahme: ein Fahrzeugausfall löst genau einen Incident und eine
 * Ersatzfahrzeugsuche aus. Positiv, negativ (unbekannte Ereignisart, fremder Mandant, fremder Dienst, Rolle) und konkurrierend (drei Ereignisse parallel, 100-fache Wiederzustellung).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IncidentWorkflowTest {
    private lateinit var fx: FleetFixture

    @BeforeAll fun up() { fx = FleetFixture() }
    @AfterAll fun down() = fx.close()

    private fun problem(block: () -> Unit): ProblemException = assertThrows(ProblemException::class.java, block)

    @Test
    fun fahrzeugausfall_ergibt_genau_einen_incident_und_eine_suche() {
        val m = IncidentWorld(fx)
        val r = m.handle(m.event(WorkflowEventKinds.VEHICLE_BLOCKED))
        assertEquals(WorkflowOutcome.PROCESSED, r.outcome); assertTrue(r.incidentCreated); assertTrue(r.searchStarted); assertEquals(1, r.ruleRevisionNo)
        assertEquals(1, m.incidents().size); assertEquals(1, m.operations())
        assertEquals(1, m.outbox("incident.opened")); assertEquals(1, m.outbox("incident.replacement_search_started"))
        val view = m.fleet.incidents.get(m.ctx("dispatcher"), r.incidentId!!)
        assertEquals(IncidentStatus.REPLACEMENT_SEARCH, view.status); assertEquals("VEHICLE_OUTAGE", view.kind); assertEquals(m.vehicle, view.vehicleId)
        assertEquals("VEHICLE_OUTAGE:${m.vehicle}", view.dedupeKey); assertNotNull(view.replacementOperationId); assertNotNull(view.ruleRevisionId)
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.operation WHERE tenant_id=? AND id=? AND kind='REPLACEMENT_SEARCH' AND journey_id IS NULL", m.tenant.value, view.replacementOperationId))
        // dasselbe Ereignis ein zweites Mal: keine Wirkung
        val again = m.handle(m.event(WorkflowEventKinds.VEHICLE_BLOCKED, id = fx.strings("SELECT event_id::text FROM taktovia.workflow_run_log WHERE tenant_id=? LIMIT 1", m.tenant.value).single().let(UUID::fromString)))
        assertEquals(WorkflowOutcome.DUPLICATE, again.outcome); assertEquals(1, m.incidents().size); assertEquals(1, m.operations())
        // die Liste zeigt den Incident dem berechtigten Dienst
        assertEquals(listOf(view.id), m.fleet.incidents.list(m.ctx("dispatcher"), null, m.vehicle, 10, null).map { it.id })
    }

    @Test
    fun drei_ereignisse_parallel_und_100_wiederzustellungen_ein_incident() {
        val m = IncidentWorld(fx)
        val events = listOf(m.event(WorkflowEventKinds.VEHICLE_BLOCKED), m.event(WorkflowEventKinds.CRITICAL_DEFECT, defect = m.defect()), m.event(WorkflowEventKinds.DISRUPTION_REPORTED))
        val pool = Executors.newFixedThreadPool(24)
        try {
            val start = CountDownLatch(1)
            // erst drei verschiedene Ereignisse gleichzeitig, dann jedes 33-mal wiederzugestellt (insgesamt 100 Wiederzustellungen), ebenfalls gleichzeitig
            val first = events.map { e -> pool.submit<Any> { start.await(); m.handle(e) } }
            val redeliveries = (0 until 100).map { i -> pool.submit<Any> { start.await(); m.handle(events[i % 3]) } }
            start.countDown()
            val outcomes = (first + redeliveries).map { it.get(60, TimeUnit.SECONDS) as de.taktovia.fleetsafety.api.WorkflowResult }
            assertEquals(3, outcomes.count { it.outcome == WorkflowOutcome.PROCESSED }, "jedes der drei Ereignisse wird genau einmal verarbeitet")
            assertEquals(100, outcomes.count { it.outcome == WorkflowOutcome.DUPLICATE }, "100 Wiederzustellungen sind Wiederholungen: ${outcomes.groupingBy { it.outcome }.eachCount()}")
        } finally { pool.shutdownNow() }
        assertEquals(1, m.incidents().size, "ein Incident"); assertEquals(1, m.operations(), "eine Suche")
        assertEquals(1, m.outbox("incident.opened")); assertEquals(1, m.outbox("incident.replacement_search_started"))
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.incident_link WHERE tenant_id=? AND link_kind='DEFECT'", m.tenant.value), "die Schadenakte ist verknüpft")
    }

    @Test
    fun wiederzustellung_ueber_die_inbox_ergibt_genau_einen_incident() {
        val m = IncidentWorld(fx)
        val consumer = m.workflow.eventConsumer(m.processor())
        val json = m.envelope("fleet.vehicle_blocked", mapOf("vehicle_id" to m.vehicle.toString(), "block_id" to UUID.randomUUID().toString(), "source" to "SAFETY", "reason_code" to "DEFECT_CRITICAL", "safety_revision" to 8))
        val pool = Executors.newFixedThreadPool(16)
        try {
            val start = CountDownLatch(1)
            val results = (0 until 100).map { pool.submit<InboxOutcome> { start.await(); consumer.consume(json) } }
            start.countDown()
            val counts = results.map { it.get(60, TimeUnit.SECONDS) }.groupingBy { it }.eachCount()
            assertEquals(1, counts[InboxOutcome.PROCESSED]); assertEquals(99, counts[InboxOutcome.DUPLICATE])
        } finally { pool.shutdownNow() }
        assertEquals(1, m.incidents().size); assertEquals(1, m.operations())
        // eine Störungsmeldung (Dienststatus DISRUPTED) für dasselbe Fahrzeug wird demselben Incident zugeordnet
        val dis = m.envelope("vehicle.service_status_changed", mapOf("vehicle_id" to m.vehicle.toString(), "shift_id" to UUID.randomUUID().toString(), "previous" to "EN_ROUTE", "current" to "DISRUPTED", "trigger" to "DRIVER"))
        assertEquals(InboxOutcome.PROCESSED, consumer.consume(dis))
        assertEquals(1, m.incidents().size); assertEquals(1, m.operations()); assertEquals(1, m.outbox("incident.opened"))
        // ein anderer Statuswechsel ist kein Ausfall: kein Eintrag, kein Incident
        val fine = m.envelope("vehicle.service_status_changed", mapOf("vehicle_id" to m.vehicle.toString(), "shift_id" to UUID.randomUUID().toString(), "previous" to "AVAILABLE", "current" to "EN_ROUTE", "trigger" to "SYSTEM"))
        assertEquals(InboxOutcome.PROCESSED, consumer.consume(fine)); assertEquals(1, m.incidents().size)
    }

    @Test
    fun ereignis_ohne_regel_protokolliert_keine_aktion() {
        val m = IncidentWorld(fx)
        for (kind in listOf(WorkflowEventKinds.DEFECT_REPORTED, WorkflowEventKinds.REJECTION, WorkflowEventKinds.CRITICAL_DELAY)) {
            val e = m.event(kind, vehicle = null, subject = "ref-$kind")
            val r = m.handle(e)
            assertEquals(WorkflowOutcome.NO_ACTION, r.outcome, kind); assertNull(r.incidentId)
            assertEquals(listOf("EVALUATE_RULE:NO_RULE", "NO_ACTION:NO_ACTION"), fx.strings("SELECT action || ':' || outcome FROM taktovia.workflow_run_log WHERE tenant_id=? AND event_id=? ORDER BY created_at, id", m.tenant.value, e.eventId), kind)
        }
        assertEquals(0, m.incidents().size); assertEquals(0, m.operations()); assertEquals(0, m.outbox("incident.opened"))
        // eine Wiederholung ist weiterhin keine Wirkung und kein Fehler
        val e = m.event(WorkflowEventKinds.REJECTION, vehicle = null, subject = "ref-x"); m.handle(e)
        assertEquals(WorkflowOutcome.DUPLICATE, m.handle(e).outcome)
    }

    @Test
    fun unbekannter_ereignistyp_und_fremder_mandant_nicht_verarbeitet() {
        val m = IncidentWorld(fx); val other = IncidentWorld(fx)
        // unbekannte Ereignisart: protokolliert und abgewiesen
        val unknown = m.event("DOOR_OPENED")
        val r = m.handle(unknown)
        assertEquals(WorkflowOutcome.REJECTED_UNKNOWN_TYPE, r.outcome); assertEquals(0, m.incidents().size)
        assertEquals(listOf("REJECT_EVENT:UNKNOWN_EVENT_TYPE"), fx.strings("SELECT action || ':' || outcome FROM taktovia.workflow_run_log WHERE tenant_id=? AND event_id=?", m.tenant.value, unknown.eventId))
        // Fahrzeug eines fremden Mandanten: im Empfängermandanten unbekannt, kein Incident, protokolliert übersprungen
        val foreign = m.handle(m.event(WorkflowEventKinds.VEHICLE_BLOCKED, vehicle = other.vehicle))
        assertEquals(WorkflowOutcome.NO_ACTION, foreign.outcome); assertEquals(0, m.incidents().size); assertEquals(0, other.incidents().size); assertEquals(0, m.operations() + other.operations())
        // Bus: Ereignis des Mandanten B an den Mandanten A wird nicht verarbeitet (Empfängerprüfung der Inbox), nichts entsteht
        val consumer = m.workflow.eventConsumer(m.processor())
        val spoof = m.envelope("fleet.vehicle_blocked", mapOf("vehicle_id" to other.vehicle.toString(), "block_id" to UUID.randomUUID().toString(), "source" to "SAFETY", "reason_code" to "MANUAL", "safety_revision" to 1), owner = other.tenant, recipient = m.tenant)
        assertEquals(InboxOutcome.REJECTED_RECIPIENT, consumer.consume(spoof)); assertEquals(0, m.incidents().size); assertEquals(0, other.incidents().size)
        // unbekannter Ereignistyp am Bus: protokolliert und abgewiesen, kein Incident
        val busUnknown = m.envelope("door.opened", mapOf("vehicle_id" to m.vehicle.toString()))
        assertEquals(InboxOutcome.PROCESSED, consumer.consume(busUnknown)); assertEquals(0, m.incidents().size)
        assertEquals(2, fx.count("SELECT count(*) FROM taktovia.workflow_run_log WHERE tenant_id=? AND action='REJECT_EVENT'", m.tenant.value))
    }

    @Test
    fun regelrevision_wirkt_nur_auf_neue_ereignisse() {
        val m = IncidentWorld(fx)
        val dispatcher = m.ctx("dispatcher")
        // Revision 1 der Ablehnung existiert nicht: das Ereignis bleibt ohne Aktion
        val before = m.event(WorkflowEventKinds.REJECTION, vehicle = null, subject = "req-1")
        assertEquals(WorkflowOutcome.NO_ACTION, m.handle(before).outcome)
        val rev1 = m.workflow.publishRule(dispatcher, WorkflowRule(WorkflowEventKinds.REJECTION, listOf(WorkflowAction(WorkflowActions.OPEN_INCIDENT, "REJECTION"))))
        assertEquals(1, rev1.revisionNo)
        // das bereits verarbeitete Ereignis wird nicht neu bewertet, auch nicht bei Wiederzustellung
        assertEquals(WorkflowOutcome.DUPLICATE, m.handle(before).outcome); assertEquals(0, m.incidents().size)
        // ein neues Ereignis folgt der neuen Revision: Incident ohne Ersatzsuche
        val after = m.handle(m.event(WorkflowEventKinds.REJECTION, vehicle = null, subject = "req-2"))
        assertEquals(WorkflowOutcome.PROCESSED, after.outcome); assertTrue(after.incidentCreated); assertEquals(false, after.searchStarted); assertEquals(1, after.ruleRevisionNo)
        assertEquals(1, m.incidents().size); assertEquals(0, m.operations())
        // Ausfall: Standardregel Revision 1 startet die Suche; Revision 2 (ohne Suche) gilt nur für neue Ereignisse
        val v2 = m.fx.vehicle(m.tenant)
        val first = m.handle(m.event(WorkflowEventKinds.VEHICLE_BLOCKED))
        assertTrue(first.searchStarted); assertEquals(1, first.ruleRevisionNo)
        val rev2 = m.workflow.publishRule(dispatcher, WorkflowRule(WorkflowEventKinds.VEHICLE_BLOCKED, listOf(WorkflowAction(WorkflowActions.OPEN_INCIDENT, "VEHICLE_OUTAGE"))))
        assertEquals(2, rev2.revisionNo); assertEquals(2, m.workflow.currentRule(dispatcher, WorkflowEventKinds.VEHICLE_BLOCKED)!!.revisionNo)
        val second = m.handle(m.event(WorkflowEventKinds.VEHICLE_BLOCKED, vehicle = v2))
        assertTrue(second.incidentCreated); assertEquals(false, second.searchStarted); assertEquals(2, second.ruleRevisionNo)
        assertEquals(1, m.operations(), "die Suche des ersten Incidents blieb, für den zweiten entstand keine")
        // jeder Incident nennt die Revision, nach der er entstand
        val v = m.fleet.incidents
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.incident i JOIN taktovia.workflow_rule_revision r ON r.tenant_id=i.tenant_id AND r.id=i.rule_revision_id WHERE i.id=? AND r.revision_no=1", first.incidentId))
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.incident i JOIN taktovia.workflow_rule_revision r ON r.tenant_id=i.tenant_id AND r.id=i.rule_revision_id WHERE i.id=? AND r.revision_no=2", second.incidentId))
        assertNotNull(v)
        // Rolle: ohne Disposition/Betriebsleitung keine Veröffentlichung; ungültige Regel: 422
        assertEquals(ErrorCode.FORBIDDEN, problem { m.workflow.publishRule(m.ctx("callcenter"), WorkflowRule(WorkflowEventKinds.REJECTION, emptyList())) }.code)
        problem { WorkflowRule.parse(mapOf("schema" to "workflow-regel.v1", "event_kind" to "REJECTION", "actions" to listOf(mapOf("action" to "START_REPLACEMENT_SEARCH")))) }
        problem { WorkflowRule.parse(mapOf("schema" to "workflow-regel.v1", "event_kind" to "UNKNOWN", "actions" to emptyList<Any>())) }
    }

    @Test
    fun fehlgeschlagene_suche_incident_bleibt_offen() {
        val m = IncidentWorld(fx)
        m.searchResult = ReplacementSearchResult.Failed("PROVIDER_DOWN")
        val r = m.handle(m.event(WorkflowEventKinds.VEHICLE_BLOCKED))
        val run = m.runner().runNext(m.tenant)!!
        assertEquals(de.taktovia.platform.statemachine.OperationState.FAILED, run.state)
        val view = m.fleet.incidents.get(m.ctx("dispatcher"), r.incidentId!!)
        assertEquals(IncidentStatus.OPEN, view.status, "der Incident bleibt offen und sichtbar")
        assertTrue(m.fleet.incidents.list(m.ctx("dispatcher"), IncidentStatus.OPEN, null, 10, null).any { it.id == view.id })
        assertTrue(m.logActions(view.id.toString()).contains("SEARCH_RESULT:FAILED:PROVIDER_DOWN"), m.logActions(view.id.toString()).toString())
        assertEquals(1, m.operations(), "es gibt genau eine Suche, auch nach dem Fehlschlag")
        // ein erneuter Ausfallhinweis startet keine zweite Suche
        assertEquals(false, m.handle(m.event(WorkflowEventKinds.DISRUPTION_REPORTED)).searchStarted); assertEquals(1, m.operations()); assertEquals(1, m.incidents().size)
        // ohne Ergebnis: ebenfalls offen; mit Vorschlägen: bleibt in der Suche, bis die Disposition umgeplant hat
        val none = IncidentWorld(fx); none.searchResult = ReplacementSearchResult.NoneAvailable
        val n = none.handle(none.event()); none.runner().runNext(none.tenant)
        assertEquals(IncidentStatus.OPEN, none.fleet.incidents.get(none.ctx("dispatcher"), n.incidentId!!).status)
        val ok = IncidentWorld(fx); ok.searchResult = ReplacementSearchResult.Proposed(3)
        val o = ok.handle(ok.event()); val done = ok.runner().runNext(ok.tenant)!!
        assertEquals(de.taktovia.platform.statemachine.OperationState.SUCCEEDED, done.state)
        assertEquals(IncidentStatus.REPLACEMENT_SEARCH, ok.fleet.incidents.get(ok.ctx("dispatcher"), o.incidentId!!).status)
    }

    @Test
    fun incident_fremder_dienst_404() {
        val m = IncidentWorld(fx); val svcA = m.service(); val svcB = m.service(); m.pool(svcA)
        val id = m.handle(m.event()).incidentId!!
        assertEquals(svcA, m.fleet.incidents.get(m.ctx("dispatcher", services = setOf(svcA)), id).serviceId)
        assertEquals(ErrorCode.NOT_FOUND, problem { m.fleet.incidents.get(m.ctx("dispatcher", services = setOf(svcB)), id) }.code)
        assertTrue(m.fleet.incidents.list(m.ctx("dispatcher", services = setOf(svcB)), null, null, 10, null).isEmpty())
        assertEquals(listOf(id), m.fleet.incidents.list(m.ctx("dispatcher", services = setOf(svcA)), null, null, 10, null).map { it.id })
        // ohne Rolle 403, ohne Scope 403, fremder Mandant 404, unbekannte Kennung 404
        assertEquals(ErrorCode.FORBIDDEN, problem { m.fleet.incidents.get(m.ctx("passenger", services = setOf(svcA)), id) }.code)
        assertEquals(ErrorCode.FORBIDDEN, problem { m.fleet.incidents.get(m.ctx("dispatcher", services = setOf(svcA), scopes = emptySet()), id) }.code)
        val other = IncidentWorld(fx)
        assertEquals(ErrorCode.NOT_FOUND, problem { other.fleet.incidents.get(other.ctx("dispatcher"), id) }.code)
        assertEquals(ErrorCode.NOT_FOUND, problem { m.fleet.incidents.get(m.ctx("dispatcher", services = setOf(svcA)), UUID.randomUUID()) }.code)
    }
}

package de.taktovia.fleetsafety

import de.taktovia.fleetsafety.api.IncidentStatus
import de.taktovia.fleetsafety.api.ReplacementSearchResult
import de.taktovia.fleetsafety.api.WorkflowAction
import de.taktovia.fleetsafety.api.WorkflowActions
import de.taktovia.fleetsafety.api.WorkflowEventKinds
import de.taktovia.fleetsafety.api.WorkflowRule
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
import org.springframework.dao.DataAccessException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M3-55 / LH-I.8-AUT-002: Ablaufprotokoll automatisch ausgeführter Vorgänge unter der Runtime-Rolle. Originalabnahme: der Betriebsleiter kann den Ablauf eines Testvorfalls vollständig
 * rekonstruieren. Positiv (Zeitleiste mit Auslöser, Regelversion, Entscheidung, Folgeaktionen und Leitstellenentscheidung), negativ (Rolle, fremder Mandant, keine Rohdaten, keine Änderung)
 * und konkurrierend (parallele Folgeaktionen, alle Einträge erhalten und eindeutig geordnet).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkflowLogTest {
    private lateinit var fx: FleetFixture

    @BeforeAll fun up() { fx = FleetFixture() }
    @AfterAll fun down() = fx.close()

    private fun problem(block: () -> Unit): ProblemException = assertThrows(ProblemException::class.java, block)

    @Test
    fun testvorfall_vollstaendig_rekonstruierbar() {
        val m = IncidentWorld(fx); val ctx = m.ctx("dispatcher")
        val trigger = m.event(WorkflowEventKinds.VEHICLE_BLOCKED)
        val r = m.handle(trigger)
        m.runner().runNext(m.tenant)                                                      // Ersatzsuche läuft: zwei Vorschläge
        // spätere Störungsmeldung desselben Fahrzeugs: wird demselben Incident zugeordnet, die Suche startet nicht noch einmal (übersprungen)
        val second = m.event(WorkflowEventKinds.DISRUPTION_REPORTED); m.handle(second)
        // Entscheidung der Leitstelle mit Akteur, Grund und Zeit (DMG-010 ruft diese Schnittstelle)
        fx.tx.run(m.tenant) { j -> m.workflow.recordDecision(j, ctx, r.incidentId!!, "REPLACEMENT_ACCEPTED", "VEHICLE_AVAILABLE") }
        val view = m.fleet.incidents.get(ctx, r.incidentId!!)
        val log = view.workflowLog!!
        assertEquals((1..log.size).map { it.toLong() }, log.map { it.seq }, "lückenlos und eindeutig geordnet")
        assertEquals(log.sortedBy { it.at }.map { it.seq }, log.map { it.seq }, "zeitliche Reihenfolge")
        // 1. Auslöser, Regelversion und Entscheidung des Workflows
        val evaluate = log.first { it.action == "EVALUATE_RULE" && it.eventId == trigger.eventId }
        assertEquals(WorkflowEventKinds.VEHICLE_BLOCKED, evaluate.eventType); assertEquals(1, evaluate.ruleRevisionNo); assertEquals("RULE_MATCHED", evaluate.decision); assertNotNull(evaluate.ruleRevisionId)
        assertEquals("system:workflow", evaluate.actor); assertEquals(trigger.correlationId, evaluate.correlationId)
        // 2. jede Folgeaktion mit Ergebnis, in der Reihenfolge der Regel
        assertEquals(listOf("OPEN_INCIDENT:CREATED", "START_REPLACEMENT_SEARCH:STARTED"), log.filter { it.eventId == trigger.eventId && it.action != "EVALUATE_RULE" }.map { "${it.action}:${it.outcome}" })
        val searchStarted = log.first { it.action == "START_REPLACEMENT_SEARCH" }; assertEquals(view.replacementOperationId.toString(), searchStarted.ref)
        val result = log.first { it.action == "SEARCH_RESULT" }; assertEquals("PROPOSALS:2", result.outcome); assertEquals(view.replacementOperationId, UUID.fromString(result.ref))
        // 3. übersprungene Aktion bei der zweiten Meldung: sichtbar, nicht verschwunden
        assertEquals(listOf("OPEN_INCIDENT:JOINED", "START_REPLACEMENT_SEARCH:ALREADY_STARTED"), log.filter { it.eventId == second.eventId && it.action != "EVALUATE_RULE" }.map { "${it.action}:${it.outcome}" })
        // 4. Leitstellenentscheidung: Akteur, Grund (Code) und Zeit
        val decision = log.last(); assertEquals("DISPATCH_DECISION", decision.action); assertEquals("REPLACEMENT_ACCEPTED", decision.outcome); assertEquals("VEHICLE_AVAILABLE", decision.reasonCode)
        assertTrue(decision.actor.startsWith("user:")); assertEquals(ctx.correlationId, decision.correlationId); assertEquals(IncidentStatus.REPLACEMENT_SEARCH, view.status)
        // die Zeit stammt vom Server (Datenbank), nicht aus dem Aufruf
        assertEquals(log.size, fx.count("SELECT count(*) FROM taktovia.workflow_run_log WHERE incident_id=? AND created_at <= now()", r.incidentId))
    }

    @Test
    fun fehlgeschlagene_und_uebersprungene_aktionen_erscheinen() {
        val m = IncidentWorld(fx); val ctx = m.ctx("dispatcher")
        m.searchResult = ReplacementSearchResult.Failed("PROVIDER_DOWN")
        val r = m.handle(m.event(WorkflowEventKinds.VEHICLE_BLOCKED)); m.runner().runNext(m.tenant)
        val log = m.fleet.incidents.get(ctx, r.incidentId!!).workflowLog!!
        assertEquals("FAILED:PROVIDER_DOWN", log.first { it.action == "SEARCH_RESULT" }.outcome)
        // übersprungen: Regel für Ablehnung eröffnet einen Incident, die Suche aber ohne Incident-Bezug nicht möglich ist; Ausfallereignis zum offenen Incident: Suche bereits gestartet
        m.handle(m.event(WorkflowEventKinds.DISRUPTION_REPORTED))
        val after = m.fleet.incidents.get(ctx, r.incidentId!!).workflowLog!!
        assertTrue(after.any { it.action == "START_REPLACEMENT_SEARCH" && it.outcome == "ALREADY_STARTED" })
        // Regel ohne Fahrzeug und ohne Bezugskennung: die Eröffnung wird übersprungen und steht im Protokoll (nicht im Incident, weil keiner entsteht)
        m.workflow.publishRule(ctx, WorkflowRule(WorkflowEventKinds.REJECTION, listOf(WorkflowAction(WorkflowActions.OPEN_INCIDENT, "REJECTION"), WorkflowAction(WorkflowActions.START_REPLACEMENT_SEARCH))))
        val skipped = m.event(WorkflowEventKinds.REJECTION, vehicle = null, subject = null); m.handle(skipped)
        assertEquals(listOf("EVALUATE_RULE:MATCHED:2", "OPEN_INCIDENT:SKIPPED_NO_SUBJECT", "START_REPLACEMENT_SEARCH:SKIPPED_NO_INCIDENT"),
            fx.strings("SELECT action || ':' || outcome FROM taktovia.workflow_run_log WHERE tenant_id=? AND event_id=? ORDER BY created_at, id", m.tenant.value, skipped.eventId))
        assertEquals(1, m.incidents().size)
    }

    @Test
    fun zugriff_ohne_rolle_403_fremder_mandant_404() {
        val m = IncidentWorld(fx); val other = IncidentWorld(fx)
        val id = m.handle(m.event()).incidentId!!
        for (role in listOf("passenger", "driver", "finance", "tenant_admin")) assertEquals(ErrorCode.FORBIDDEN, problem { m.fleet.incidents.get(m.ctx(role), id) }.code, role)
        assertEquals(ErrorCode.FORBIDDEN, problem { m.fleet.incidents.get(m.ctx("dispatcher", scopes = emptySet()), id) }.code)
        assertEquals(ErrorCode.NOT_FOUND, problem { other.fleet.incidents.get(other.ctx("dispatcher"), id) }.code, "Protokoll eines Incidents eines anderen Mandanten")
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.workflow_run_log WHERE tenant_id=? AND incident_id=?", other.tenant.value, id))
        // Entscheidung nur durch die Disposition; fremder Incident: 404
        assertEquals(ErrorCode.FORBIDDEN, problem { fx.tx.run(m.tenant) { j -> m.workflow.recordDecision(j, m.ctx("callcenter"), id, "REPLACEMENT_ACCEPTED", "VEHICLE_AVAILABLE") } }.code)
        assertEquals(ErrorCode.NOT_FOUND, problem { fx.tx.run(other.tenant) { j -> other.workflow.recordDecision(j, other.ctx("dispatcher"), id, "REPLACEMENT_ACCEPTED", "VEHICLE_AVAILABLE") } }.code)
        assertEquals(ViolationCode.INVALID_FORMAT, problem { fx.tx.run(m.tenant) { j -> m.workflow.recordDecision(j, m.ctx("dispatcher"), id, "Fahrer Max hat angerufen", "VEHICLE_AVAILABLE") } }.violations.single().code)
    }

    @Test
    fun kein_rohpayload_mit_personendaten() {
        val m = IncidentWorld(fx); val ctx = m.ctx("dispatcher")
        val defect = m.defect()
        val id = m.handle(m.event(WorkflowEventKinds.CRITICAL_DEFECT, defect = defect)).incidentId!!
        m.runner().runNext(m.tenant)
        fx.tx.run(m.tenant) { j -> m.workflow.recordDecision(j, ctx, id, "REPLACEMENT_ACCEPTED", "VEHICLE_AVAILABLE") }
        val codes = Regex("^[A-Za-z0-9_.:-]{1,100}$")
        val log = m.fleet.incidents.get(ctx, id).workflowLog!!
        assertTrue(log.isNotEmpty())
        log.forEach { e -> listOf(e.action, e.outcome, e.decision, e.reasonCode, e.ref, e.eventType).filterNotNull().forEach { assertTrue(codes.matches(it), "kein Freitext im Protokoll: $it") } }
        // die Beschreibung der Schadenakte oder Namen stehen nirgends im Protokoll
        val dump = fx.strings("SELECT row_to_json(l)::text FROM taktovia.workflow_run_log l WHERE tenant_id=? AND incident_id=?", m.tenant.value, id).joinToString("\n")
        assertFalse(dump.contains("Kratzer")); assertFalse(dump.contains("Mustermann"))
        // die Datenbank erzwingt es: Freitext in Aktion oder Ergebnis wird abgewiesen
        for ((a, o) in listOf("Max Mustermann" to "OK", "OPEN_INCIDENT" to "Fahrer Max Mustermann hat angerufen"))
            assertThrows(DataAccessException::class.java) { fx.tx.run(m.tenant) { j -> j.update("INSERT INTO taktovia.workflow_run_log(tenant_id,id,incident_id,event_id,action,outcome,created_by) VALUES (taktovia.current_tenant(),?,?,?,?,?,'system:t')", UUID.randomUUID(), id, UUID.randomUUID(), a, o) } }
    }

    @Test
    fun aenderung_per_api_abgewiesen() {
        val m = IncidentWorld(fx)
        val id = m.handle(m.event()).incidentId!!
        val rowId = fx.strings("SELECT id::text FROM taktovia.workflow_run_log WHERE incident_id=? ORDER BY seq LIMIT 1", id).single()
        // die Schnittstelle des Moduls kennt kein Ändern oder Löschen von Protokolleinträgen; unter der Runtime-Rolle scheitern UPDATE und DELETE am fehlenden Recht, für die Administration am Trigger
        for (sql in listOf("UPDATE taktovia.workflow_run_log SET outcome = 'GEAENDERT' WHERE id = CAST(? AS uuid)", "DELETE FROM taktovia.workflow_run_log WHERE id = CAST(? AS uuid)")) {
            assertThrows(DataAccessException::class.java, { fx.tx.run(m.tenant) { j -> j.update(sql, rowId) } }, sql)
            assertThrows(java.sql.SQLException::class.java, { fx.exec(sql, rowId) }, sql)
        }
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.workflow_run_log WHERE id = CAST(? AS uuid) AND outcome <> 'GEAENDERT'", rowId))
        assertNull(IncidentWorkflowIntrospection.writeMethods().firstOrNull(), "keine schreibende Operation am Protokoll außer der Entscheidung der Leitstelle")
    }

    @Test
    fun parallele_folgeaktionen_alle_eintraege_geordnet() {
        val m = IncidentWorld(fx); val ctx = m.ctx("dispatcher")
        val id = m.handle(m.event()).incidentId!!
        val before = m.fleet.incidents.get(ctx, id).workflowLog!!.size
        val pool = Executors.newFixedThreadPool(16)
        try {
            val start = CountDownLatch(1)
            val results = (0 until 60).map { i -> pool.submit { start.await(); fx.tx.run(m.tenant) { j -> m.workflow.recordDecision(j, ctx, id, "NOTE_$i", "VEHICLE_AVAILABLE") } } }
            start.countDown(); results.forEach { it.get(60, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
        val log = m.fleet.incidents.get(ctx, id).workflowLog!!
        assertEquals(before + 60, log.size, "alle Einträge bleiben erhalten")
        assertEquals((1..log.size).map { it.toLong() }, log.map { it.seq }, "Ordnungsnummer ohne Lücken und Duplikate")
        assertEquals(60, log.count { it.action == "DISPATCH_DECISION" }); assertEquals(60, log.filter { it.action == "DISPATCH_DECISION" }.map { it.outcome }.toSet().size)
    }
}

/** Reflexionsnachweis: die öffentliche Schnittstelle der Lesesicht hat nur lesende Methoden. */
internal object IncidentWorkflowIntrospection {
    fun writeMethods(): List<String> = de.taktovia.fleetsafety.api.Incidents::class.java.methods.map { it.name }.filter { n -> listOf("update", "delete", "remove", "patch", "put", "amend", "edit").any { n.lowercase().startsWith(it) } }
}

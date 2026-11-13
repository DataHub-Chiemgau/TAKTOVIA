package de.taktovia.fleetsafety.internal

import de.taktovia.fleetsafety.api.IncidentWorkflow
import de.taktovia.fleetsafety.api.ReplacementSearchPort
import de.taktovia.fleetsafety.api.ReplacementSearchResult
import de.taktovia.fleetsafety.api.WorkflowAction
import de.taktovia.fleetsafety.api.WorkflowActions
import de.taktovia.fleetsafety.api.WorkflowEvent
import de.taktovia.fleetsafety.api.WorkflowEventConsumer
import de.taktovia.fleetsafety.api.WorkflowEventKinds
import de.taktovia.fleetsafety.api.WorkflowOutcome
import de.taktovia.fleetsafety.api.WorkflowResult
import de.taktovia.fleetsafety.api.WorkflowRule
import de.taktovia.fleetsafety.api.WorkflowRuleView
import de.taktovia.platform.audit.ActorRef
import de.taktovia.platform.audit.AuditActions
import de.taktovia.platform.audit.AuditEntry
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.audit.AuditRequired
import de.taktovia.platform.audit.ObjectRef
import de.taktovia.platform.authz.ScopedObject
import de.taktovia.platform.authz.ServiceScopeAuthorizer
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.Purpose
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.operation.OperationContext
import de.taktovia.platform.operation.OperationHandler
import de.taktovia.platform.operation.OperationOutcome
import de.taktovia.platform.operation.OperationService
import de.taktovia.platform.outbox.AggregateSnapshot
import de.taktovia.platform.outbox.EventEnvelope
import de.taktovia.platform.outbox.InboxHandler
import de.taktovia.platform.outbox.InboxProcessor
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.outbox.OutboxEvent
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcOperations
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Regelbasierter Workflow auf PostgreSQL (WP-M3-53, LH-I.8-AUT-001). Schichten gegen doppelte Wirkung:
 *  1. Ereignissperre (`pg_advisory_xact_lock` je Ereignis) und Ablaufprotokoll `(event_id, action)`: ein erneut zugestelltes Ereignis hat keine Wirkung, auch nicht bei paralleler Zustellung;
 *  2. Ausfallsperre je Dedupe-Schlüssel: drei verschiedene Ereignisse desselben Fahrzeugs laufen nacheinander und treffen dann den offenen Incident;
 *  3. partieller Unique-Index `incident_one_open_per_key` und Unique auf der Ersatzsuche (`replacement_operation_id`) in der Datenbank.
 * Die Ersatzsuche ist eine `operation` (`REPLACEMENT_SEARCH`, ohne Reisebezug) und startet nur, solange der Incident `OPEN` ist und noch keine Suche hatte.
 */
internal class JdbcIncidentWorkflow(
    private val ctxTx: ContextTransactions, private val audit: AuditPort, private val outbox: Outbox, private val clock: Clock, private val search: ReplacementSearchPort,
) : IncidentWorkflow {
    private val json = JsonMapper.builder().build()
    private val log = LoggerFactory.getLogger("taktovia.workflow")
    private val operations = OperationService(clock)

    /** Ein Schritt des Ablaufs (wird nach der Ausführung als Protokollzeile geschrieben). */
    private class Step(val action: String, val outcome: String, val ref: String? = null)
    private class RuleRow(val id: UUID, val revisionNo: Int, val rule: WorkflowRule, val createdBy: String, val createdAt: Instant)

    // ---------------- Verarbeitung ----------------

    override fun handle(jdbc: JdbcOperations, event: WorkflowEvent): WorkflowResult {
        val j = jdbc
        val now = clock.instant()
        lock(j, "wf-event|${event.eventId}")
        if (j.query("SELECT 1 FROM taktovia.workflow_run_log WHERE event_id = ? LIMIT 1", { _, _ -> 1 }, event.eventId).isNotEmpty()) return WorkflowResult(WorkflowOutcome.DUPLICATE)
        val type = event.kind.take(64).takeIf { EVENT_TYPE.matches(it) }
        fun write(incident: UUID?, rule: UUID?, action: String, outcome: String, decision: String? = null, ref: String? = null) =
            appendLog(j, incident, event.eventId, rule, action, outcome, eventType = type, decision = decision, correlation = event.correlationId, ref = ref)
        if (event.kind !in WorkflowEventKinds.ALL) {
            write(null, null, "REJECT_EVENT", "UNKNOWN_EVENT_TYPE", "REJECTED")
            return WorkflowResult(WorkflowOutcome.REJECTED_UNKNOWN_TYPE)
        }
        if (event.vehicleId != null && j.query("SELECT 1 FROM taktovia.vehicle WHERE id = ?", { _, _ -> 1 }, event.vehicleId).isEmpty()) {
            write(null, null, "SKIP_EVENT", "UNKNOWN_VEHICLE", "SKIPPED")   // fremder Mandant, gelöscht: nie ein Incident
            return WorkflowResult(WorkflowOutcome.NO_ACTION)
        }
        val rule = activeRule(j, event.kind)
        if (rule == null) {
            write(null, null, "EVALUATE_RULE", "NO_RULE", "NO_RULE")
            write(null, null, "NO_ACTION", "NO_ACTION", "NO_ACTION")
            return WorkflowResult(WorkflowOutcome.NO_ACTION)
        }
        val steps = mutableListOf<Step>()
        var incident: UUID? = null; var created = false; var started = false
        for (a in rule.rule.actions) when (a.action) {
            WorkflowActions.OPEN_INCIDENT -> {
                val r = openIncident(j, event, rule, a, now)
                steps += Step(a.action, r.outcome, r.incidentId?.toString()); incident = r.incidentId; created = r.created
            }
            WorkflowActions.START_REPLACEMENT_SEARCH -> {
                val id = incident
                if (id == null) steps += Step(a.action, "SKIPPED_NO_INCIDENT") else {
                    val s = startSearch(j, id, event, now)
                    steps += Step(a.action, s.outcome, s.operation?.toString()); started = s.started
                }
            }
        }
        // Protokoll in der Reihenfolge Auslöser/Regel/Entscheidung, dann jede Folgeaktion mit Ergebnis; der Incident ist erst nach der Eröffnung bekannt, deshalb wird nach der Ausführung geschrieben
        write(incident, rule.id, "EVALUATE_RULE", "MATCHED:${rule.rule.actions.size}", "RULE_MATCHED")
        if (rule.rule.actions.isEmpty()) write(null, rule.id, "NO_ACTION", "NO_ACTION", "NO_ACTION")
        steps.forEach { write(incident, rule.id, it.action, it.outcome, ref = it.ref) }
        return WorkflowResult(if (rule.rule.actions.isEmpty()) WorkflowOutcome.NO_ACTION else WorkflowOutcome.PROCESSED, incident, created, started, rule.revisionNo)
    }

    private class Opened(val incidentId: UUID?, val created: Boolean, val outcome: String)

    private fun openIncident(j: JdbcOperations, event: WorkflowEvent, rule: RuleRow, a: WorkflowAction, now: Instant): Opened {
        val kind = a.incidentKind!!
        val subject = event.vehicleId?.toString() ?: event.subjectRef?.takeIf { SUBJECT.matches(it) }
        if (subject == null) return Opened(null, false, "SKIPPED_NO_SUBJECT")
        val key = "$kind:$subject"
        lock(j, "incident|$key")   // drei Auslöser desselben Fahrzeugs laufen nacheinander und sehen dann den offenen Incident
        val id = UUID.randomUUID()
        val n = j.update(
            "INSERT INTO taktovia.incident(tenant_id, id, kind, vehicle_id, status, dedupe_key, rule_revision_id, opened_at, updated_at, created_by, created_at, service_id, trigger_event_id, subject_ref) " +
                "VALUES (taktovia.current_tenant(), ?, ?, ?, 'OPEN', ?, ?, ?, ?, 'system:workflow', ?, ?, ?, ?) ON CONFLICT DO NOTHING",
            id, kind, event.vehicleId, key, rule.id, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), serviceOf(j, event.vehicleId, now), event.eventId, event.subjectRef?.takeIf { SUBJECT.matches(it) })
        val incident = if (n == 1) id else j.query("SELECT id FROM taktovia.incident WHERE dedupe_key = ? AND status IN ('OPEN', 'REPLACEMENT_SEARCH')", { rs, _ -> rs.getObject(1, UUID::class.java) }, key).first()
        if (n == 1) outbox.append(j, OutboxEvent(id, 1, "incident.opened", linkedMapOf("incident_id" to id.toString(), "kind" to kind, "vehicle_id" to event.vehicleId?.toString(), "dedupe_key" to key), now, correlationId = event.correlationId))
        event.defectId?.let { link(j, incident, it) }
        return Opened(incident, n == 1, if (n == 1) "CREATED" else "JOINED")
    }

    /** Verknüpft die Schadenakte mit dem Incident (nur eine Akte des eigenen Mandanten, einmalig); die Akte ist vom Incident aus erreichbar (DMG-003). */
    private fun link(j: JdbcOperations, incident: UUID, defect: UUID) {
        if (j.query("SELECT 1 FROM taktovia.defect_case WHERE id = ?", { _, _ -> 1 }, defect).isEmpty()) return
        j.update("INSERT INTO taktovia.incident_link(tenant_id, id, incident_id, link_kind, object_id, created_by) VALUES (taktovia.current_tenant(), ?, ?, 'DEFECT', ?, 'system:workflow') ON CONFLICT DO NOTHING", UUID.randomUUID(), incident, defect)
    }

    /** Dienstbezug: der Dienst der laufenden Poolzuordnung des Fahrzeugs, wenn es genau einen gibt (sonst mandantenweit sichtbar). */
    private fun serviceOf(j: JdbcOperations, vehicle: UUID?, now: Instant): UUID? = vehicle?.let {
        j.query("SELECT DISTINCT service_id FROM taktovia.vehicle_pool_assignment WHERE vehicle_id = ? AND valid_from <= ? AND (valid_to IS NULL OR valid_to > ?)", { rs, _ -> rs.getObject(1, UUID::class.java) }, it, Timestamp.from(now), Timestamp.from(now))
    }?.singleOrNull()

    private class Started(val outcome: String, val started: Boolean, val operation: UUID? = null)

    private fun startSearch(j: JdbcOperations, incident: UUID, event: WorkflowEvent, now: Instant): Started {
        val row = j.query("SELECT status, replacement_operation_id, revision FROM taktovia.incident WHERE id = ? FOR UPDATE", { rs, _ -> Triple(rs.getString(1), rs.getObject(2, UUID::class.java), rs.getLong(3)) }, incident).first()
        if (row.second != null) return Started("ALREADY_STARTED", false, row.second)   // genau eine Suche je Incident, auch nach einem Fehlschlag oder während sie läuft
        if (row.first != "OPEN") return Started("INCIDENT_NOT_OPEN", false)
        val op = operations.enqueue(j, "REPLACEMENT_SEARCH", now.plus(SEARCH_DEADLINE))
        j.update("UPDATE taktovia.incident SET status = 'REPLACEMENT_SEARCH', replacement_operation_id = ?, revision = revision + 1, updated_at = ? WHERE id = ?", op, Timestamp.from(now), incident)
        outbox.append(j, OutboxEvent(incident, row.third + 1, "incident.replacement_search_started",
            linkedMapOf("incident_id" to incident.toString(), "operation_id" to op.toString(), "vehicle_id" to j.queryForObject("SELECT vehicle_id FROM taktovia.incident WHERE id = ?", UUID::class.java, incident)?.toString()), now, correlationId = event.correlationId))
        return Started("STARTED", true, op)
    }

    private fun appendLog(
        j: JdbcOperations, incident: UUID?, eventId: UUID, rule: UUID?, action: String, outcome: String, ignoreDuplicate: Boolean = false, eventType: String? = null, decision: String? = null,
        correlation: UUID? = null, ref: String? = null, reason: String? = null, actor: String = "system:workflow",
    ) {
        // Die Ordnungsnummer vergibt die Datenbank (Trigger, Zeilensperre des Incidents); das Protokoll trägt nur Codes und Kennungen (CHECKs der Datenbank)
        if (ignoreDuplicate) j.update(
            "INSERT INTO taktovia.workflow_run_log(tenant_id, id, incident_id, event_id, rule_revision_id, action, outcome, created_by, event_type, decision, correlation_id, result_ref, reason_code) " +
                "VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
            UUID.randomUUID(), incident, eventId, rule, action, outcome.take(100), actor, eventType, decision, correlation, ref, reason)
        else j.update(
            "INSERT INTO taktovia.workflow_run_log(tenant_id, id, incident_id, event_id, rule_revision_id, action, outcome, created_by, event_type, decision, correlation_id, result_ref, reason_code) " +
                "VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            UUID.randomUUID(), incident, eventId, rule, action, outcome.take(100), actor, eventType, decision, correlation, ref, reason)
    }

    override fun recordDecision(jdbc: JdbcOperations, ctx: RequestContext, incidentId: UUID, decision: String, reasonCode: String) {
        guard(ctx, "operator:write", PUBLISHERS)
        if (!CODE.matches(decision)) throw ProblemException.invalid(Violation("/decision", ViolationCode.INVALID_FORMAT))
        if (!CODE.matches(reasonCode)) throw ProblemException.invalid(Violation("/reason_code", ViolationCode.INVALID_FORMAT))
        val row = jdbc.query("SELECT service_id, rule_revision_id FROM taktovia.incident WHERE id = ?", { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getObject(2, UUID::class.java) }, incidentId).firstOrNull() ?: throw ProblemException.notFound()
        if (!ServiceScopeAuthorizer.canWrite(jdbc, ctx, ScopedObject("incident", incidentId, ctx.tenant, row.first), Purpose.OPERATIONS)) throw ProblemException.notFound()
        appendLog(jdbc, incidentId, UUID.randomUUID(), row.second, "DISPATCH_DECISION", decision, eventType = "DISPATCH", decision = "MANUAL_DECISION", correlation = ctx.correlationId, reason = reasonCode, actor = "user:" + ctx.actor.subject)
    }

    private fun lock(j: JdbcOperations, key: String) {
        j.query("SELECT pg_advisory_xact_lock(hashtextextended(taktovia.current_tenant()::text || '|' || ?, 0))", { _, _ -> 0 }, key)
    }

    // ---------------- Regeln ----------------

    private fun ruleOf(id: UUID, no: Int, text: String, by: String, at: Instant): RuleRow {
        val n = json.readTree(text)
        val actions = (n.get("actions") ?: json.createArrayNode()).let { a -> (0 until a.size()).map { i -> a.get(i).let { WorkflowAction(it.get("action").asString(), it.get("incident_kind")?.takeIf { k -> k.isString }?.asString()) } } }
        return RuleRow(id, no, WorkflowRule(n.get("event_kind").asString(), actions), by, at)
    }

    private fun latest(j: JdbcOperations, kind: String): RuleRow? =
        j.query("SELECT id, revision_no, rule::text, created_by, created_at FROM taktovia.workflow_rule_revision WHERE event_kind = ? ORDER BY revision_no DESC LIMIT 1",
            { rs, _ -> ruleOf(rs.getObject(1, UUID::class.java), rs.getInt(2), rs.getString(3), rs.getString(4), rs.getTimestamp(5).toInstant()) }, kind).firstOrNull()

    /** Die wirksame Regel zur Verarbeitungszeit; für Ausfallereignisse legt der erste Zugriff die Standardregel als Revision 1 an (parallele Erstanlage: Sperre, dann genau eine). */
    private fun activeRule(j: JdbcOperations, kind: String): RuleRow? {
        latest(j, kind)?.let { return it }
        val default = DEFAULT_RULES[kind] ?: return null
        lock(j, "workflow_rule|$kind")   // gleicher Schlüssel wie der Nummerntrigger der Datenbank
        latest(j, kind)?.let { return it }
        insertRule(j, WorkflowRule(kind, default), "system:workflow-default")
        return latest(j, kind)
    }

    private fun insertRule(j: JdbcOperations, rule: WorkflowRule, by: String): UUID {
        val id = UUID.randomUUID()
        j.update("INSERT INTO taktovia.workflow_rule_revision(tenant_id, id, event_kind, rule, created_by, created_at) VALUES (taktovia.current_tenant(), ?, ?, ?::jsonb, ?, ?)",
            id, rule.eventKind, ruleJson(rule), by, Timestamp.from(clock.instant()))
        return id
    }

    private fun ruleJson(r: WorkflowRule): String = json.writeValueAsString(linkedMapOf("schema" to WorkflowRule.SCHEMA, "event_kind" to r.eventKind,
        "actions" to r.actions.map { a -> linkedMapOf<String, Any?>("action" to a.action).also { m -> a.incidentKind?.let { m["incident_kind"] = it } } }))

    override fun publishRule(ctx: RequestContext, rule: WorkflowRule): WorkflowRuleView {
        guard(ctx, "operator:write", PUBLISHERS)
        return ctxTx.run(ctx) { j -> doPublish(j, ctx, rule) }
    }

    @AuditRequired
    private fun doPublish(j: JdbcOperations, ctx: RequestContext, rule: WorkflowRule): WorkflowRuleView {
        lock(j, "workflow_rule|${rule.eventKind}")
        val id = insertRule(j, rule, "user:" + ctx.actor.subject)
        val row = latest(j, rule.eventKind)!!
        audit.record(j, AuditEntry(ActorRef("user:" + ctx.actor.subject), AuditActions.WORKFLOW_RULE_PUBLISHED, ObjectRef("workflow_rule_revision", id), ctx.correlationId,
            mapOf("event_kind" to rule.eventKind, "revision_no" to row.revisionNo, "action_count" to rule.actions.size), clock.instant()))
        return WorkflowRuleView(id, rule.eventKind, row.revisionNo, rule, "user:" + ctx.actor.subject, row.createdAt)
    }

    override fun currentRule(ctx: RequestContext, eventKind: String): WorkflowRuleView? {
        guard(ctx, "operator:read", READERS)
        return ctxTx.run(ctx, readOnly = true) { j -> latest(j, eventKind)?.let { WorkflowRuleView(it.id, eventKind, it.revisionNo, it.rule, it.createdBy, it.createdAt) } }
    }

    private fun guard(ctx: RequestContext, scope: String, roles: Set<String>) {
        if (ctx.actor.type != ActorType.HUMAN || ctx.actor.sessionId == null || !ctx.hasScope(scope) || ctx.roles.none { it in roles }) throw ProblemException(ErrorCode.FORBIDDEN)
    }

    // ---------------- Ereigniskonsum (Inbox) ----------------

    override fun eventConsumer(processor: InboxProcessor): WorkflowEventConsumer {
        val handler = object : InboxHandler {
            override fun handle(jdbc: JdbcOperations, event: EventEnvelope) { map(event)?.let { this@JdbcIncidentWorkflow.handle(jdbc, it) } }
            override fun applySnapshot(jdbc: JdbcOperations, snapshot: AggregateSnapshot) = Unit
        }
        return WorkflowEventConsumer { jsonText ->
            val e = try { EventEnvelope.fromJson(jsonText) } catch (ex: IllegalArgumentException) { return@WorkflowEventConsumer processor.process(CONSUMER, jsonText, handler) }   // die Inbox meldet das Schemaproblem
            // Der Workflow hält keinen Aggregatzustand; jedes Ereignis ist ein eigenes Aggregat (Revision 1), die Lückenerkennung der Inbox soll hier nie ein Ereignis verlieren.
            processor.process(CONSUMER, e.copy(aggregateId = e.eventId, aggregateRevision = 1).toJson(), handler)
        }
    }

    /** Abbildung der Busereignisse auf Workflow-Ereignisse; `null` = für den Workflow ohne Bedeutung (z. B. ein Statuswechsel, der keine Störung ist). Unbekannte Typen werden als unbekannte Art protokolliert. */
    private fun map(e: EventEnvelope): WorkflowEvent? {
        val p = e.payload
        fun ev(kind: String) = WorkflowEvent(e.eventId, kind, p.uuid("vehicle_id"), e.occurredAt, null, p.uuid("defect_id"), e.correlationId)
        return when (e.eventType) {
            WorkflowEventKinds.BUS_VEHICLE_BLOCKED -> ev(WorkflowEventKinds.VEHICLE_BLOCKED)
            WorkflowEventKinds.BUS_SERVICE_STATUS_CHANGED -> if (p.text("current") == "DISRUPTED") ev(WorkflowEventKinds.DISRUPTION_REPORTED) else null
            WorkflowEventKinds.BUS_DEFECT_TRIAGED -> if (p.text("severity") == "SAFETY_CRITICAL") ev(WorkflowEventKinds.CRITICAL_DEFECT) else null
            WorkflowEventKinds.BUS_DEFECT_REPORTED -> ev(WorkflowEventKinds.DEFECT_REPORTED)
            WorkflowEventKinds.BUS_REJECTION -> ev(WorkflowEventKinds.REJECTION).copy(subjectRef = p.text("request_ref"))
            WorkflowEventKinds.BUS_CRITICAL_DELAY -> ev(WorkflowEventKinds.CRITICAL_DELAY).copy(subjectRef = p.text("run_ref"))
            else -> WorkflowEvent(e.eventId, "UNKNOWN:" + e.eventType.take(60), null, e.occurredAt, null, null, e.correlationId)
        }
    }

    private fun JsonNode.text(f: String): String? = get(f)?.takeIf { it.isString }?.asString()
    private fun JsonNode.uuid(f: String): UUID? = text(f)?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    // ---------------- Ersatzfahrzeugsuche (Operation) ----------------

    override val searchHandler: OperationHandler = object : OperationHandler {
        override val kind = "REPLACEMENT_SEARCH"

        override fun execute(ctx: OperationContext): OperationOutcome {
            class Inc(val id: UUID, val vehicleId: UUID?, val status: String, val ruleId: UUID?)
            val inc = ctxTx.runSystem(ctx.tenant, readOnly = true) { j ->
                j.query("SELECT id, vehicle_id, status, rule_revision_id FROM taktovia.incident WHERE replacement_operation_id = ?",
                    { rs, _ -> Inc(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getString(3), rs.getObject(4, UUID::class.java)) }, ctx.operationId).firstOrNull()
            } ?: return OperationOutcome.Failed("INCIDENT_NOT_FOUND")
            if (inc.status != "REPLACEMENT_SEARCH") return OperationOutcome.Succeeded("INCIDENT_NOT_SEARCHING")   // zwischenzeitlich von der Disposition abgeschlossen
            // Die Suche läuft außerhalb jeder SQL-Transaktion (Port des Moduls booking-dispatch); eine Ausnahme ist unbestimmt und wird vom Runner abgeglichen, nie zu Erfolg oder Fehler verkürzt.
            return when (val r = search.search(ctx.tenant, inc.id, inc.vehicleId)) {
                is ReplacementSearchResult.Proposed -> ctx.commit("PROPOSALS") { j -> appendLog(j, inc.id, ctx.operationId, inc.ruleId, "SEARCH_RESULT", "PROPOSALS:${r.count}", ignoreDuplicate = true, eventType = SEARCH_TYPE, decision = "SEARCH_COMPLETED", ref = ctx.operationId.toString()) }
                ReplacementSearchResult.NoneAvailable -> ctx.commit("NO_RESULT") { j -> reopen(j, inc.id); appendLog(j, inc.id, ctx.operationId, inc.ruleId, "SEARCH_RESULT", "NO_RESULT", ignoreDuplicate = true, eventType = SEARCH_TYPE, decision = "SEARCH_COMPLETED", ref = ctx.operationId.toString()) }
                is ReplacementSearchResult.Failed -> {
                    val code = r.code.filter { it.isLetterOrDigit() || it == '_' }.take(60).ifEmpty { "FAILED" }
                    ctxTx.runSystem(ctx.tenant) { j -> reopen(j, inc.id); appendLog(j, inc.id, ctx.operationId, inc.ruleId, "SEARCH_RESULT", "FAILED:$code", ignoreDuplicate = true, eventType = SEARCH_TYPE, decision = "SEARCH_FAILED", ref = ctx.operationId.toString()) }
                    OperationOutcome.Failed(code)
                }
            }
        }
    }

    /** Suche ohne Ergebnis: zurück nach `OPEN` (der Incident bleibt offen und sichtbar, nie still verschwunden). */
    private fun reopen(j: JdbcOperations, incident: UUID) {
        j.update("UPDATE taktovia.incident SET status = 'OPEN', revision = revision + 1, updated_at = ? WHERE id = ? AND status = 'REPLACEMENT_SEARCH'", Timestamp.from(clock.instant()), incident)
    }

    private companion object {
        val SUBJECT = Regex("^[A-Za-z0-9_.:-]{1,100}$")
        val EVENT_TYPE = Regex("^[A-Za-z0-9_.:-]{1,64}$")
        val CODE = Regex("^[A-Z][A-Z0-9_]{1,59}$")
        const val SEARCH_TYPE = "REPLACEMENT_SEARCH"
        val SEARCH_DEADLINE: Duration = Duration.ofMinutes(30)
        const val CONSUMER = "incident-workflow"
        val PUBLISHERS = setOf("dispatcher", "tenant_admin")
        val READERS = setOf("callcenter", "support", "dispatcher", "planner", "workshop", "tenant_admin")
        /** Standardregeln (Revision 1 je Mandant): ein Fahrzeugausfall eröffnet den Incident und startet die Ersatzsuche (OP-24: weitere Regeln sind nicht P1). */
        val OUTAGE = listOf(WorkflowAction(WorkflowActions.OPEN_INCIDENT, "VEHICLE_OUTAGE"), WorkflowAction(WorkflowActions.START_REPLACEMENT_SEARCH))
        val DEFAULT_RULES: Map<String, List<WorkflowAction>> = mapOf(
            WorkflowEventKinds.VEHICLE_BLOCKED to OUTAGE, WorkflowEventKinds.DISRUPTION_REPORTED to OUTAGE, WorkflowEventKinds.CRITICAL_DEFECT to OUTAGE,
        )
    }
}

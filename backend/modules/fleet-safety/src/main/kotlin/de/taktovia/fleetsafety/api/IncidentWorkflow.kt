package de.taktovia.fleetsafety.api

import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.operation.OperationHandler
import de.taktovia.platform.outbox.InboxOutcome
import de.taktovia.platform.outbox.InboxProcessor
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.platform.tenant.TenantId
import org.springframework.jdbc.core.JdbcOperations
import java.time.Instant
import java.util.UUID

/**
 * Ereignisarten des regelbasierten Workflows (WP-M3-53, LH-I.8-AUT-001). „Fahrzeug gesperrt“, „Störung gemeldet“ und „sicherheitskritischer Schaden“ sind die auslösenden Ereignisse eines
 * Fahrzeugausfalls; „Schaden“, „Ablehnung“ und „kritische Verspätung“ sind als Ereignisarten registriert und werden nach der wirksamen Regelrevision verarbeitet, ohne hinterlegte Regel entsteht
 * ein protokollierter Eintrag „keine Aktion“ (OP-24). Alle anderen Arten sind unbekannt: protokollieren und abweisen (Arbeitsannahme des Vertrags).
 */
object WorkflowEventKinds {
    const val VEHICLE_BLOCKED = "VEHICLE_BLOCKED"
    const val DISRUPTION_REPORTED = "DISRUPTION_REPORTED"
    const val CRITICAL_DEFECT = "CRITICAL_DEFECT"
    const val DEFECT_REPORTED = "DEFECT_REPORTED"
    const val REJECTION = "REJECTION"
    const val CRITICAL_DELAY = "CRITICAL_DELAY"

    val ALL: Set<String> = linkedSetOf(VEHICLE_BLOCKED, DISRUPTION_REPORTED, CRITICAL_DEFECT, DEFECT_REPORTED, REJECTION, CRITICAL_DELAY)

    /** Ereignisse am Bus (Typ `…` der Outbox) und ihre Abbildung auf die Ereignisart; `dispatch.request_rejected` und `dispatch.critical_delay` sind reserviert (noch ohne Erzeuger, OP-24). */
    const val BUS_VEHICLE_BLOCKED = "fleet.vehicle_blocked"
    const val BUS_SERVICE_STATUS_CHANGED = "vehicle.service_status_changed"
    const val BUS_DEFECT_TRIAGED = "defect.triaged"
    const val BUS_DEFECT_REPORTED = "defect.reported"
    const val BUS_REJECTION = "dispatch.request_rejected"
    const val BUS_CRITICAL_DELAY = "dispatch.critical_delay"
}

/** Folgeaktionen einer Regel (Regelformat `workflow-regel.v1`). */
object WorkflowActions {
    const val OPEN_INCIDENT = "OPEN_INCIDENT"
    const val START_REPLACEMENT_SEARCH = "START_REPLACEMENT_SEARCH"
    val ALL: Set<String> = linkedSetOf(OPEN_INCIDENT, START_REPLACEMENT_SEARCH)
    val INCIDENT_KINDS: Set<String> = linkedSetOf("VEHICLE_OUTAGE", "DEFECT", "REJECTION", "CRITICAL_DELAY")
}

/** Eine Aktion einer Regel; [incidentKind] gilt nur für [WorkflowActions.OPEN_INCIDENT]. */
data class WorkflowAction(val action: String, val incidentKind: String? = null)

/**
 * Regel einer Ereignisart (`docs/umsetzungsplan/vertraege/schemas/workflow-regel.v1.schema.json`): geordnete Folgeaktionen. Eine leere Aktionsliste ist eine gültige Regel („nichts tun“).
 * Jede Veröffentlichung ist eine neue, unveränderliche Revision; sie wirkt auf neue Ereignisse, nie auf bereits verarbeitete.
 */
data class WorkflowRule(val eventKind: String, val actions: List<WorkflowAction>) {
    companion object {
        const val SCHEMA = "workflow-regel.v1"
        const val MAX_ACTIONS = 10
        private val FIELDS = setOf("schema", "event_kind", "actions")

        fun parse(raw: Map<String, Any?>): WorkflowRule {
            val v = Violations()
            v.unknown(raw, FIELDS, "")
            if (raw["schema"] != SCHEMA) v.add("/schema", if (raw["schema"] == null) ViolationCode.REQUIRED else ViolationCode.INVALID_VALUE)
            val kind = v.code(raw, "event_kind", WorkflowEventKinds.ALL, true)
            val actions = mutableListOf<WorkflowAction>()
            when (val a = raw["actions"]) {
                null -> v.add("/actions", ViolationCode.REQUIRED)
                is List<*> -> {
                    if (a.size > MAX_ACTIONS) v.add("/actions", ViolationCode.TOO_MANY_ITEMS)
                    a.forEachIndexed { i, e ->
                        val m = e as? Map<*, *>
                        if (m == null) { v.add("/actions/$i", ViolationCode.TYPE_MISMATCH); return@forEachIndexed }
                        v.unknown(m, setOf("action", "incident_kind"), "/actions/$i")
                        val act = v.code(m, "action", WorkflowActions.ALL, true)
                        val ik = v.code(m, "incident_kind", WorkflowActions.INCIDENT_KINDS, act == WorkflowActions.OPEN_INCIDENT)
                        if (act == WorkflowActions.START_REPLACEMENT_SEARCH && m["incident_kind"] != null) v.add("/actions/$i/incident_kind", ViolationCode.INVALID_VALUE)
                        if (act != null) actions += WorkflowAction(act, ik)
                    }
                    if (actions.any { it.action == WorkflowActions.START_REPLACEMENT_SEARCH } &&
                        actions.indexOfFirst { it.action == WorkflowActions.START_REPLACEMENT_SEARCH } < actions.indexOfFirst { it.action == WorkflowActions.OPEN_INCIDENT }.let { if (it < 0) Int.MAX_VALUE else it })
                        v.add("/actions", ViolationCode.INVALID_VALUE)   // die Suche braucht einen vorher eröffneten Incident
                    if (actions.count { it.action == WorkflowActions.OPEN_INCIDENT } > 1 || actions.count { it.action == WorkflowActions.START_REPLACEMENT_SEARCH } > 1) v.add("/actions", ViolationCode.NOT_UNIQUE)
                }
                else -> v.add("/actions", ViolationCode.TYPE_MISMATCH)
            }
            v.throwIfAny()
            return WorkflowRule(kind!!, actions)
        }
    }
}

/** Veröffentlichte Regelrevision (unveränderlich). [revisionNo] zählt je Mandant und Ereignisart ab 1; Standardregeln tragen den Urheber `system:workflow-default`. */
data class WorkflowRuleView(val id: UUID, val eventKind: String, val revisionNo: Int, val rule: WorkflowRule, val createdBy: String, val createdAt: Instant)

/**
 * Ein Ereignis für den Workflow. [kind] ist eine Ereignisart (unbekannte Arten werden protokolliert und abgewiesen), [vehicleId] das betroffene Fahrzeug (kann bei Ablehnung/Verspätung fehlen, dann
 * zählt [subjectRef], eine Kennung des Bezugsobjekts), [defectId] die verknüpfte Schadenakte. Nie Personendaten oder Freitext.
 */
data class WorkflowEvent(
    val eventId: UUID, val kind: String, val vehicleId: UUID?, val occurredAt: Instant, val subjectRef: String? = null, val defectId: UUID? = null, val correlationId: UUID? = null,
)

enum class WorkflowOutcome {
    /** Regel ausgeführt (mindestens eine Aktion). */
    PROCESSED,
    /** Dasselbe Ereignis wurde schon verarbeitet: keine Wirkung. */
    DUPLICATE,
    /** Keine Regel oder Regel ohne Aktion: protokollierter Eintrag „keine Aktion“, kein Fehler. */
    NO_ACTION,
    /** Unbekannte Ereignisart: protokolliert und abgewiesen. */
    REJECTED_UNKNOWN_TYPE,
}

data class WorkflowResult(
    val outcome: WorkflowOutcome, val incidentId: UUID? = null, val incidentCreated: Boolean = false, val searchStarted: Boolean = false, val ruleRevisionNo: Int? = null,
)

/** Ergebnis der Ersatzfahrzeugsuche aus Sicht des Workflows; Inhalt und Umbuchung liefert WP-M3-54 über den Port. */
sealed interface ReplacementSearchResult {
    /** Es gibt [count] Ersatzvorschläge: der Incident bleibt in `REPLACEMENT_SEARCH`, bis die Disposition umgeplant hat. */
    data class Proposed(val count: Int) : ReplacementSearchResult
    /** Die Suche lief, fand aber nichts: der Incident bleibt offen und sichtbar. */
    data object NoneAvailable : ReplacementSearchResult
    /** Die Suche ist gescheitert (bestimmtes Ergebnis): der Incident bleibt offen und sichtbar. */
    data class Failed(val code: String) : ReplacementSearchResult
}

/**
 * Port des Moduls `booking-dispatch` für die Ersatzfahrzeugsuche (Detailvertrag `incident.md`). Ohne Umsetzung (WP-M3-54 liefert sie) meldet der Standard ehrlich
 * `Failed("REPLACEMENT_SEARCH_NOT_AVAILABLE")`: der Incident bleibt offen und sichtbar, es wird nie ein Ergebnis vorgetäuscht.
 */
fun interface ReplacementSearchPort {
    fun search(tenant: TenantId, incidentId: UUID, vehicleId: UUID?): ReplacementSearchResult

    companion object { val UNAVAILABLE = ReplacementSearchPort { _, _, _ -> ReplacementSearchResult.Failed("REPLACEMENT_SEARCH_NOT_AVAILABLE") } }
}

/** Ereigniskonsum des Workflows über die Inbox (Dedupe `(consumer, event_id)`, Wirkung und Inbox-Eintrag in einer Transaktion des Empfängermandanten). */
fun interface WorkflowEventConsumer { fun consume(json: String): InboxOutcome }

/**
 * Regelbasierter Workflow (WP-M3-53, LH-I.8-AUT-001). Ein Fahrzeugausfall (Sperre, Störungsmeldung, sicherheitskritischer Schaden) erzeugt je Fahrzeug höchstens einen offenen Incident
 * (Dedupe-Schlüssel `<Art>:<Fahrzeug>`, partieller Unique-Index) und startet genau eine Ersatzfahrzeugsuche als `operation`, auch bei nahezu gleichzeitigem Eintreffen mehrerer Ereignisse und bei
 * wiederholter Zustellung. Die Regelrevision zur Verarbeitungszeit bestimmt die Folgeaktionen; jeder Schritt steht im Ablaufprotokoll (`workflow_run_log`, append-only).
 */
interface IncidentWorkflow {
    /**
     * Verarbeitet ein Ereignis **in der laufenden Mandantentransaktion** [jdbc] (Mandant = Empfänger). Idempotent über die Ereigniskennung: ein erneut zugestelltes Ereignis hat keine Wirkung
     * ([WorkflowOutcome.DUPLICATE]). Ein Fahrzeug, das im Mandanten nicht existiert (fremd, gelöscht), wird protokolliert übersprungen.
     */
    fun handle(jdbc: JdbcOperations, event: WorkflowEvent): WorkflowResult

    /**
     * Protokolliert eine Entscheidung der Leitstelle am Incident (DMG-010, WP-M3-54 ruft sie in seiner Transaktion): Akteur und Zeit setzt der Server, [decision] und [reasonCode] sind Codes
     * (`[A-Z][A-Z0-9_]{1,59}`, nie Freitext). Nur Disposition und Betriebsleitung (403 sonst); Incident fremd oder nicht im Dienstscope: 404; ungültiger Code: 422. Der Eintrag ist wie jeder
     * andere unveränderlich und bekommt die nächste Ordnungsnummer des Incidents.
     */
    fun recordDecision(jdbc: JdbcOperations, ctx: RequestContext, incidentId: UUID, decision: String, reasonCode: String)

    /** Konsument für Ereignisse vom Bus über die Inbox; unbekannte Ereignisarten werden protokolliert und abgewiesen, nicht verarbeitet. */
    fun eventConsumer(processor: InboxProcessor): WorkflowEventConsumer

    /** Veröffentlicht eine neue Regelrevision (nur Disposition und Betriebsleitung, 403 sonst); sie wirkt auf neue Ereignisse, nie auf bereits verarbeitete. Audit `fleet.workflow.rule_published`. */
    fun publishRule(ctx: RequestContext, rule: WorkflowRule): WorkflowRuleView

    /** Wirksame Regelrevision der Ereignisart (Standardregel eingeschlossen, sobald sie angelegt ist); `null`, wenn es keine gibt. */
    fun currentRule(ctx: RequestContext, eventKind: String): WorkflowRuleView?

    /** Betriebsauftrag der Ersatzfahrzeugsuche (`operation.kind = REPLACEMENT_SEARCH`); registriert beim Operation-Runner der Anwendung. */
    val searchHandler: OperationHandler
}

enum class IncidentStatus { OPEN, REPLACEMENT_SEARCH, RESOLVED, CLOSED_NO_REPLACEMENT;
    val isFinal get() = this == RESOLVED || this == CLOSED_NO_REPLACEMENT
}

/** Zustandsautomat des Incidents (`zustaende/incident.md`); die Datenbank erzwingt dieselbe Tabelle per Trigger `incident_guard` (V30530). */
object IncidentStateMachine {
    val TRANSITIONS: Map<IncidentStatus, Set<IncidentStatus>> = mapOf(
        IncidentStatus.OPEN to setOf(IncidentStatus.REPLACEMENT_SEARCH, IncidentStatus.CLOSED_NO_REPLACEMENT),
        IncidentStatus.REPLACEMENT_SEARCH to setOf(IncidentStatus.OPEN, IncidentStatus.RESOLVED, IncidentStatus.CLOSED_NO_REPLACEMENT),
    )
    fun allowed(from: IncidentStatus, to: IncidentStatus) = to in TRANSITIONS[from].orEmpty()
}

/**
 * Incident für die Arbeitsliste (UI-07): Fahrzeug, Ursache (Art), Zustand und Verknüpfungen (betroffene Fahrten/Buchungen, Schadenakten). Nur Kennungen und Codes, keine Personendaten.
 * [workflowLog] steht nur im Detail ([Incidents.get]).
 */
data class IncidentView(
    val id: UUID, val kind: String, val vehicleId: UUID?, val status: IncidentStatus, val dedupeKey: String, val openedAt: Instant, val updatedAt: Instant, val ruleRevisionId: UUID?,
    val affectedRunIds: List<UUID>, val defectIds: List<UUID>, val replacementOperationId: UUID?, val serviceId: UUID?, val revision: Long, val closedAt: Instant? = null,
    val workflowLog: List<WorkflowLogEntry>? = null,
)

/**
 * Eintrag des Ablaufprotokolls (WP-M3-55); Schema `ablaufprotokoll.v1`. [seq] ist die lückenlose Ordnungsnummer je Incident (0 nur bei Zeilen aus der Zeit vor `V30550`), [at] die Serverzeit,
 * [eventId]/[eventType] der Auslöser, [ruleRevisionNo] die Regelversion, [action]/[outcome] Folgeaktion und Ergebnis, [decision] die Entscheidung der Regel (oder `MANUAL_DECISION` der Leitstelle),
 * [actor] `system:workflow` oder `user:<Kennung>`, [ref] eine Ergebnisreferenz (z. B. die Operation der Ersatzsuche). Alles sind Kennungen und Codes, nie Rohpayloads oder Freitext.
 */
data class WorkflowLogEntry(
    val seq: Long, val at: Instant, val eventId: UUID, val eventType: String?, val ruleRevisionId: UUID?, val ruleRevisionNo: Int?, val action: String, val outcome: String,
    val decision: String?, val actor: String, val correlationId: UUID?, val ref: String?, val reasonCode: String? = null,
)

/**
 * Lesesicht der Incidents (WP-M3-53/-55). Berechtigt sind Rollen der Leitstelle (`callcenter`, `support`, `dispatcher`, `planner`, `workshop`) mit Scope `operator:read`; sichtbar sind nur Incidents
 * der berechtigten Dienste (ohne Dienstbezug mandantenweit), fremder Dienst, fremder Mandant und unbekannte Kennung gleichermaßen 404, ohne Rolle 403.
 */
interface Incidents {
    /** Nach Alter (älteste zuerst); [after] = Position `(opened_at, id)`. Standardmäßig alle Zustände. */
    fun list(ctx: RequestContext, status: IncidentStatus?, vehicleId: UUID?, limit: Int, after: Pair<Instant, UUID>?): List<IncidentView>

    /** Detail mit Ablaufprotokoll in zeitlicher Reihenfolge (WP-M3-55). */
    fun get(ctx: RequestContext, id: UUID): IncidentView
}

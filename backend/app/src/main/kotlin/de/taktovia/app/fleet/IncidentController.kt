package de.taktovia.app.fleet

import com.fasterxml.jackson.annotation.JsonValue
import de.taktovia.app.identity.RequestContexts
import de.taktovia.fleetsafety.api.FleetsafetyApi
import de.taktovia.fleetsafety.api.IncidentStatus
import de.taktovia.fleetsafety.api.IncidentView
import de.taktovia.platform.api.CursorCodec
import de.taktovia.platform.api.CursorPosition
import de.taktovia.platform.api.CursorScope
import de.taktovia.platform.api.Paging
import de.taktovia.platform.api.Revisioned
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

class IncidentResource(@get:JsonValue val data: Map<String, Any?>, override val revision: Long) : Revisioned

/** Wire-Darstellung des Incidents (`Incident` in `api/fleet-safety.yaml`): nur Kennungen, Codes und Zeiten. */
internal object IncidentJson {
    fun of(i: IncidentView): Map<String, Any?> = linkedMapOf<String, Any?>(
        "id" to i.id.toString(), "kind" to i.kind, "vehicle_id" to i.vehicleId?.toString(), "status" to i.status.name, "dedupe_key" to i.dedupeKey, "opened_at" to i.openedAt.toString(),
        "updated_at" to i.updatedAt.toString(), "rule_revision_id" to i.ruleRevisionId?.toString(), "affected_run_ids" to i.affectedRunIds.map { it.toString() },
        "defect_ids" to i.defectIds.map { it.toString() }, "replacement_operation_id" to i.replacementOperationId?.toString(), "service_id" to i.serviceId?.toString(),
        "closed_at" to i.closedAt?.toString(), "revision" to i.revision,
    ).also { m ->
        // Ablaufprotokoll nur im Detail (WP-M3-55, UI-07-Zeitleiste): Auslöser, Regelversion, Entscheidung, Folgeaktionen mit Ergebnis, in zeitlicher Reihenfolge; nur Codes und Kennungen
        i.workflowLog?.let { log ->
            m["workflow_log"] = log.map { e ->
                linkedMapOf<String, Any?>(
                    "seq" to e.seq, "at" to e.at.toString(), "event_id" to e.eventId.toString(), "event_type" to e.eventType, "rule_revision_id" to e.ruleRevisionId?.toString(), "rule_revision_no" to e.ruleRevisionNo,
                    "action" to e.action, "outcome" to e.outcome, "decision" to e.decision, "actor" to e.actor, "correlation_id" to e.correlationId?.toString(), "ref" to e.ref, "reason_code" to e.reasonCode,
                )
            }
        }
    }

    fun resource(i: IncidentView) = IncidentResource(of(i), i.revision)
}

/**
 * Incidents der Leitstelle (WP-M3-53, UI-07): Liste und Detail. Rechte (`operator:read`) aus der Rechtematrix im Filter, Mandant und Dienste aus dem Kontext, nie aus Pfad oder Query. Fremder Dienst
 * und fremder Mandant sind 404. Schreibende Endpunkte gibt es nicht: Incidents entstehen aus Ereignissen durch den Workflow (Änderungsversuche per API gibt es nicht, `405`).
 */
@RestController
class IncidentController(private val fleet: FleetsafetyApi, private val codec: CursorCodec) {

    @GetMapping("/v1/operator/incidents")
    fun list(
        @RequestParam(required = false) status: String?, @RequestParam(required = false) vehicle_id: String?, @RequestParam(required = false) limit: String?,
        @RequestParam(required = false) cursor: String?, request: HttpServletRequest,
    ): Map<String, Any?> {
        val ctx = RequestContexts.require(request)
        val st = status?.let { s -> IncidentStatus.entries.firstOrNull { it.name == s } ?: throw ProblemException.invalid(Violation("/\$query/status", ViolationCode.NOT_IN_ENUM)) }
        val vehicle = vehicle_id?.let { v -> runCatching { UUID.fromString(v) }.getOrNull() ?: throw ProblemException.invalid(Violation("/\$query/vehicle_id", ViolationCode.INVALID_FORMAT)) }
        val scope = CursorScope(ctx.tenant.toString(), ctx.actor.subject, "incidents|${st ?: "-"}|${vehicle ?: "-"}")
        val lim = Paging.limit(limit)
        val rows = fleet.incidents.list(ctx, st, vehicle, lim, Paging.position(cursor, codec, scope)?.let { it.createdAt to it.id })
        return Paging.page(rows, lim, { CursorPosition(it.openedAt, it.id) }, codec, scope, Instant.now()).toMap { IncidentJson.of(it) }
    }

    @GetMapping("/v1/operator/incidents/{incident_id}")
    fun get(@PathVariable("incident_id") id: UUID, request: HttpServletRequest): IncidentResource = IncidentJson.resource(fleet.incidents.get(RequestContexts.require(request), id))
}

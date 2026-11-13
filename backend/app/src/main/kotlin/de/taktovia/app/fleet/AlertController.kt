package de.taktovia.app.fleet

import com.fasterxml.jackson.annotation.JsonValue
import de.taktovia.app.identity.RequestContexts
import de.taktovia.fleetsafety.api.AckCause
import de.taktovia.fleetsafety.api.AlertState
import de.taktovia.fleetsafety.api.AlertView
import de.taktovia.fleetsafety.api.FleetsafetyApi
import de.taktovia.platform.api.CursorCodec
import de.taktovia.platform.api.CursorPosition
import de.taktovia.platform.api.CursorScope
import de.taktovia.platform.api.Etag
import de.taktovia.platform.api.Paging
import de.taktovia.platform.api.Revisioned
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

class AlertResource(@get:JsonValue val data: Map<String, Any?>, override val revision: Long) : Revisioned

/** Wire-Darstellung des Alarms (`Alert` in `api/alerts.yaml`): nur Codes, Kennungen und Zeiten. */
internal object AlertJson {
    fun of(a: AlertView): Map<String, Any?> = linkedMapOf(
        "id" to a.id.toString(), "kind" to a.kind, "priority" to a.priority.name, "state" to a.state.name, "vehicle_id" to a.vehicleId?.toString(), "service_id" to a.serviceId?.toString(),
        "recipient_role" to a.recipientRole, "policy_revision_no" to a.policyRevisionNo, "escalation_level" to a.escalationLevel, "escalated_at" to a.escalatedAt?.toString(),
        "escalate_at" to a.escalateAt?.toString(), "reason_code" to a.reasonCode, "ref_type" to a.refType, "ref_id" to a.refId?.toString(), "created_at" to a.createdAt.toString(),
        "updated_at" to a.updatedAt.toString(), "resolved_at" to a.resolvedAt?.toString(), "revision" to a.revision,
        "case_number" to a.caseNumber, "affected_run_count" to a.affectedRunCount,
        "acknowledgement" to a.acknowledgement?.let { linkedMapOf("acknowledged_by" to it.acknowledgedBy, "acknowledged_at" to it.acknowledgedAt.toString(), "cause" to it.cause?.name, "note" to it.note) },
    )

    fun resource(a: AlertView) = AlertResource(of(a), a.revision)
}

/**
 * Arbeitsliste „Offene Störungen“ und Quittierung (WP-M3-52, UI-07): Rechte (`operator:read`, `operator:write`) aus der Rechtematrix im Filter, Mandant und Dienste aus dem Kontext,
 * nie aus Pfad oder Body. Fremder Dienst und fremder Mandant sind 404.
 */
@RestController
class AlertController(private val fleet: FleetsafetyApi, private val codec: CursorCodec) {

    @GetMapping("/v1/operator/alerts")
    fun list(@RequestParam(required = false) state: String?, @RequestParam(required = false) limit: String?, @RequestParam(required = false) cursor: String?, request: HttpServletRequest): Map<String, Any?> {
        val ctx = RequestContexts.require(request)
        val st = state?.let { s -> AlertState.entries.firstOrNull { it.name == s } ?: throw ProblemException.invalid(Violation("/\$query/state", ViolationCode.NOT_IN_ENUM)) }
        val scope = CursorScope(ctx.tenant.toString(), ctx.actor.subject, "alerts|${st ?: "-"}")
        val lim = Paging.limit(limit)
        val rows = fleet.alerts.list(ctx, st, lim, Paging.position(cursor, codec, scope)?.let { it.createdAt to it.id })
        return Paging.page(rows, lim, { CursorPosition(it.createdAt, it.id) }, codec, scope, Instant.now()).toMap { AlertJson.of(it) }
    }

    @GetMapping("/v1/operator/alerts/{alert_id}")
    fun get(@PathVariable("alert_id") id: UUID, request: HttpServletRequest): AlertResource = AlertJson.resource(fleet.alerts.get(RequestContexts.require(request), id))

    @PostMapping("/v1/operator/alerts/{alert_id}/acknowledgements")
    fun acknowledge(@PathVariable("alert_id") id: UUID, @RequestHeader("If-Match", required = false) ifMatch: String?, @RequestBody body: Map<String, Any?>, request: HttpServletRequest): AlertResource {
        val cause = (body["cause"] as? String)?.let { c -> AckCause.entries.firstOrNull { it.name == c } } ?: throw ProblemException.invalid(Violation("/cause", ViolationCode.NOT_IN_ENUM))
        val note = (body["note"] as? String)
        return AlertJson.resource(fleet.alerts.acknowledge(RequestContexts.require(request), id, Etag.parseIfMatch(ifMatch), cause, note))
    }
}

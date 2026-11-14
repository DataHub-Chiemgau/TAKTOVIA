package de.taktovia.app.booking

import de.taktovia.app.identity.RequestContexts
import de.taktovia.bookingdispatch.api.OperatorFlagItem
import de.taktovia.bookingdispatch.api.OperatorFlags
import de.taktovia.platform.api.CursorCodec
import de.taktovia.platform.api.CursorPosition
import de.taktovia.platform.api.CursorScope
import de.taktovia.platform.api.Paging
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/**
 * Arbeitsliste offener Hinweise `GET /v1/operator/flags` (WP-M3-42, LH-6.7-OPS-006): Überfälligkeit (`OVERDUE`) und ausstehende Informationen (`INFO_PENDING`) mit der Zahl der Betroffenen. Nur Hinweise der
 * Dienste im aktuellen Dienstscope (Hinweise ohne Dienstbezug mandantenweit); Filter `state` (Standard `OPEN`), `kind`, `service_id`; Cursor an die Sicht gebunden. Keine Namen, nur Kennungen und Zahlen.
 */
@RestController
class OperatorFlagController(private val flags: OperatorFlags, private val codec: CursorCodec) {

    @GetMapping("/v1/operator/flags")
    fun list(@RequestParam(required = false) state: String?, @RequestParam(required = false) kind: String?, @RequestParam(name = "service_id", required = false) serviceId: String?,
             @RequestParam(required = false) limit: String?, @RequestParam(required = false) cursor: String?, request: HttpServletRequest): Map<String, Any?> {
        val ctx = RequestContexts.require(request)
        val lim = Paging.limit(limit)
        val service = serviceId?.let { s -> runCatching { UUID.fromString(s) }.getOrNull() ?: throw ProblemException.invalid(Violation("/\$query/service_id", ViolationCode.INVALID_FORMAT)) }
        val scope = CursorScope(ctx.tenant.toString(), ctx.actor.subject, "operator-flags:${state ?: "OPEN"}:${kind ?: "-"}:${service ?: "-"}")
        val pos = Paging.position(cursor, codec, scope)
        val rows = flags.list(ctx, state, kind, service, lim, pos?.let { it.createdAt to it.id })
        return Paging.page(rows, lim, { CursorPosition(it.openedAt, it.id) }, codec, scope, Instant.now()).toMap { render(it) }
    }

    companion object {
        fun render(f: OperatorFlagItem): Map<String, Any?> = linkedMapOf(
            "id" to f.id.toString(), "kind" to f.kind, "cause" to f.cause, "state" to f.state, "service_id" to f.serviceId?.toString(), "vehicle_id" to f.vehicleId?.toString(), "affected_count" to f.affectedCount,
            "opened_at" to f.openedAt.toString(), "resolved_at" to f.resolvedAt?.toString(), "threshold_min" to f.thresholdMin, "revision" to f.revision,
        )
    }
}

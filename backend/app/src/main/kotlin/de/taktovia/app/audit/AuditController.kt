package de.taktovia.app.audit

import de.taktovia.app.identity.RequestContexts
import de.taktovia.platform.api.CursorCodec
import de.taktovia.platform.api.CursorPosition
import de.taktovia.platform.api.CursorScope
import de.taktovia.platform.api.Paging
import de.taktovia.platform.audit.AuditFilter
import de.taktovia.platform.audit.AuditQueryService
import de.taktovia.platform.audit.AuditRecord
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * Auditabfrage (WP-M1-12, `listAuditEvents`, nur lesend). Die Rolle prüft die zentrale Autorisierung (Rechtematrix, Scope `audit:read`),
 * Zweck und Mandantengrenze der [AuditQueryService]. Der Mandant folgt ausschließlich aus dem geprüften Kontext. Der Cursor ist an Mandant,
 * Akteur und Filter gebunden.
 */
@RestController
class AuditController(private val service: AuditQueryService, private val codec: CursorCodec) {

    @GetMapping("/v1/audit-events")
    fun list(
        @RequestParam(required = false) limit: String?, @RequestParam(required = false) cursor: String?,
        @RequestParam("actor_ref", required = false) actorRef: String?, @RequestParam("object_ref", required = false) objectRef: String?,
        @RequestParam(required = false) action: String?, @RequestParam(required = false) from: String?, @RequestParam(required = false) to: String?,
        @RequestParam(required = false) purpose: String?, request: HttpServletRequest,
    ): Map<String, Any?> {
        val ctx = RequestContexts.require(request)
        val filter = AuditFilter(actorRef, objectRef, action, instant("from", from), instant("to", to))
        val scope = CursorScope(ctx.tenant.toString(), ctx.actor.subject, "audit-events:" + filter.hashCode())
        val lim = Paging.limit(limit)
        val pos = Paging.position(cursor, codec, scope)
        val rows = service.query(ctx, purpose, filter, lim, pos?.let { it.createdAt to it.id }) // sql-lint: ok Fachdienst-Methode, kein SQL
        return Paging.page(rows, lim, { CursorPosition(it.occurredAt, it.id) }, codec, scope, Instant.now()).toMap { json(it) }
    }

    private fun instant(name: String, v: String?): Instant? = v?.let {
        try { OffsetDateTime.parse(it).toInstant() } catch (_: DateTimeParseException) { throw ProblemException.invalid(Violation("/\$query/$name", ViolationCode.INVALID_FORMAT)) }
    }

    private fun json(r: AuditRecord): Map<String, Any?> = linkedMapOf(
        "id" to r.id.toString(), "actor_ref" to r.actorRef, "action" to r.action, "object_ref" to r.objectRef,
        "occurred_at" to r.occurredAt.toString(), "correlation_id" to r.correlationId.toString(), "redacted_changes" to r.redactedChanges,
    )
}

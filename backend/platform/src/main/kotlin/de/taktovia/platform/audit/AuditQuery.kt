package de.taktovia.platform.audit

import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * Zulässige Zwecke einer Auditabfrage (WP-M1-12). Die Werteliste ist eine Planungsannahme (OP-14, D-11: zulässiger Supportzugriff ist
 * fachlich nicht entschieden); ohne einen dieser Zwecke wird nicht gelesen.
 */
object AuditPurposes {
    const val SECURITY_REVIEW = "security_review"
    const val INCIDENT_RESPONSE = "incident_response"
    const val SUPPORT_CASE = "support_case"
    const val COMPLIANCE_CHECK = "compliance_check"
    val CODES: Set<String> = setOf(SECURITY_REVIEW, INCIDENT_RESPONSE, SUPPORT_CASE, COMPLIANCE_CHECK)
    /** Funktionsscope der Auditabfrage (Rechtematrix: Operation `listAuditEvents`). */
    const val SCOPE = "audit:read"
}

/** Filter der Auditabfrage: Akteur, Objekt, Aktion (exakt) und Zeitraum (beide Grenzen einschließlich). */
data class AuditFilter(val actorRef: String? = null, val objectRef: String? = null, val action: String? = null, val from: Instant? = null, val to: Instant? = null)

data class AuditRecord(
    val id: UUID, val actorRef: String, val action: String, val objectRef: String, val occurredAt: Instant, val correlationId: UUID,
    val redactedChanges: Map<String, Any?>,
)

/**
 * Auditabfrage (nur lesend) im Mandanten des Kontexts: Berechtigung (Funktionsscope `audit:read`, der aus der Rechtematrix folgt), Zweck
 * (Pflicht, aus [AuditPurposes]) und Mandantengrenze (RLS; fremde Zeilen sind nicht sichtbar, nicht einmal zählbar). Sortierung
 * `(occurred_at, id)` aufsteigend; geliefert werden `limit + 1` Zeilen ab der Position ([after]), damit der Aufrufer die Folgeseite erkennt.
 * Der Auditzugriff selbst wird in **derselben** Transaktion auditiert (Aktion `audit.events.read`, Akteur, Zweck, Filter).
 */
class AuditQueryService(private val ctxTx: ContextTransactions, private val audit: AuditPort) {
    private val mapper = JsonMapper.builder().build()

    fun query(ctx: RequestContext, purpose: String?, filter: AuditFilter, limit: Int, after: Pair<Instant, UUID>?): List<AuditRecord> {
        if (!ctx.hasScope(AuditPurposes.SCOPE)) throw ProblemException(ErrorCode.FORBIDDEN, "Keine Berechtigung für die Auditabfrage.")
        if (purpose == null || purpose !in AuditPurposes.CODES) {
            throw ProblemException.invalid(Violation("/\$query/purpose", if (purpose.isNullOrEmpty()) ViolationCode.REQUIRED else ViolationCode.NOT_IN_ENUM))
        }
        require(limit in 1..MAX_LIMIT) { "limit außerhalb 1..$MAX_LIMIT" }
        if (filter.from != null && filter.to != null && filter.to.isBefore(filter.from)) throw ProblemException.invalid(Violation("/\$query/to", ViolationCode.INTERVAL_INVALID))
        return ctxTx.run(ctx) { j ->
            val rows = j.query(SELECT, { rs, _ ->
                AuditRecord(rs.getObject("id", UUID::class.java), rs.getString("actor_ref"), rs.getString("action"), rs.getString("object_ref"),
                    rs.getTimestamp("occurred_at").toInstant(), rs.getObject("correlation_id", UUID::class.java), parse(rs.getString("changes")))
            },
                filter.actorRef, filter.actorRef, filter.objectRef, filter.objectRef, filter.action, filter.action,
                filter.from?.let(Timestamp::from), filter.from?.let(Timestamp::from), filter.to?.let(Timestamp::from), filter.to?.let(Timestamp::from),
                after?.first?.let(Timestamp::from), after?.first?.let(Timestamp::from), after?.second, limit + 1)
            recordAccess(j, ctx, purpose, filter, rows.size.coerceAtMost(limit), after != null)
            rows
        }
    }

    @AuditRequired
    private fun recordAccess(j: org.springframework.jdbc.core.JdbcOperations, ctx: RequestContext, purpose: String, f: AuditFilter, returned: Int, continued: Boolean) {
        val changes = linkedMapOf<String, Any?>("purpose" to purpose, "returned" to returned, "continued" to continued)
        f.actorRef?.let { changes["filter_actor_ref"] = it }; f.objectRef?.let { changes["filter_object_ref"] = it }; f.action?.let { changes["filter_action"] = it }
        f.from?.let { changes["filter_from"] = it.toString() }; f.to?.let { changes["filter_to"] = it.toString() }
        audit.record(j, AuditEntry(
            ActorRef((if (ctx.actor.type == ActorType.SERVICE) "service:" else "user:") + ctx.actor.subject), AuditActions.AUDIT_READ,
            ObjectRef("audit_query", ctx.correlationId), ctx.correlationId, changes,
        ))
    }

    @Suppress("UNCHECKED_CAST")
    private fun parse(json: String): Map<String, Any?> = mapper.readValue(json, Map::class.java) as Map<String, Any?>

    private companion object {
        const val MAX_LIMIT = 100
        // Konstante SQL mit gebundenen Parametern: `CAST(? AS …) IS NULL OR …` macht jeden Filter optional, ohne SQL zusammenzusetzen.
        const val SELECT = "SELECT id, actor_ref, action, object_ref, occurred_at, correlation_id, redacted_changes::text AS changes FROM taktovia.audit_event " +
            "WHERE (CAST(? AS text) IS NULL OR actor_ref = ?) AND (CAST(? AS text) IS NULL OR object_ref = ?) AND (CAST(? AS text) IS NULL OR action = ?) " +
            "AND (CAST(? AS timestamptz) IS NULL OR occurred_at >= ?) AND (CAST(? AS timestamptz) IS NULL OR occurred_at <= ?) " +
            "AND (CAST(? AS timestamptz) IS NULL OR (occurred_at, id) > (?, CAST(? AS uuid))) " +
            "ORDER BY occurred_at, id LIMIT ?"
    }
}

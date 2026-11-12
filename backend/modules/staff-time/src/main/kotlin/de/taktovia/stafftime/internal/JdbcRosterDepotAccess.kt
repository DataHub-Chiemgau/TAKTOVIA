package de.taktovia.stafftime.internal

import de.taktovia.platform.audit.ActorRef
import de.taktovia.platform.audit.AuditActions
import de.taktovia.platform.audit.AuditEntry
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.audit.AuditRequired
import de.taktovia.platform.audit.ObjectRef
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.stafftime.api.DepotGrantKind
import de.taktovia.stafftime.api.DepotGrantView
import de.taktovia.stafftime.api.RosterDepotAccess
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import java.util.UUID

/** Vergabe und Entzug von Depotrechten (WP-M4-16); jede Änderung steht im `audit_event`. Der Entzug sperrt die Zeile (`UPDATE`) und wartet damit auf Schreibbefehle, die die Vergabe mit `FOR SHARE` halten. */
internal class JdbcRosterDepotAccess(private val ctxTx: ContextTransactions, private val audit: AuditPort) : RosterDepotAccess {
    override fun grantDepot(ctx: RequestContext, subject: String, depotId: UUID): DepotGrantView = grant(ctx, subject, DepotGrantKind.DEPOT, depotId)
    override fun grantAllDepots(ctx: RequestContext, subject: String): DepotGrantView = grant(ctx, subject, DepotGrantKind.ALL_DEPOTS, null)

    override fun revoke(ctx: RequestContext, grantId: UUID): DepotGrantView {
        RosterAuthz.require(ctx, ADMIN)
        return ctxTx.run(ctx) { j -> doRevoke(j, ctx, grantId) }
    }

    override fun list(ctx: RequestContext, subject: String?, includeRevoked: Boolean): List<DepotGrantView> {
        RosterAuthz.require(ctx, ADMIN)
        return ctxTx.run(ctx, readOnly = true) { j ->
            j.query("SELECT $COLUMNS FROM taktovia.roster_depot_grant g WHERE (CAST(? AS text) IS NULL OR g.subject = CAST(? AS text)) AND (? OR g.revoked_at IS NULL) ORDER BY g.granted_at, g.id",
                VIEW, subject?.lowercase(), subject?.lowercase(), includeRevoked)
        }
    }

    private fun grant(ctx: RequestContext, subject: String, kind: DepotGrantKind, depotId: UUID?): DepotGrantView {
        RosterAuthz.require(ctx, ADMIN)
        val s = subject.trim().lowercase()
        if (s.isEmpty() || s.length > 200) throw ProblemException.invalid(Violation("/subject", if (s.isEmpty()) ViolationCode.REQUIRED else ViolationCode.TOO_LONG))
        return try { ctxTx.run(ctx) { j -> doGrant(j, ctx, s, kind, depotId) } } catch (e: DataIntegrityViolationException) {
            val msg = generateSequence<Throwable>(e) { it.cause }.mapNotNull { it.message }.joinToString(" ")
            when {
                msg.contains("roster_depot_grant_one_") -> throw ProblemException(ErrorCode.CONFLICT, "Die Vergabe besteht bereits.")
                msg.contains("foreign key", ignoreCase = true) -> throw ProblemException.notFound()
                else -> throw e
            }
        }
    }

    @AuditRequired
    private fun doGrant(j: JdbcOperations, ctx: RequestContext, subject: String, kind: DepotGrantKind, depotId: UUID?): DepotGrantView {
        val v = j.query("INSERT INTO taktovia.roster_depot_grant AS g (tenant_id, id, subject, scope_kind, depot_id, granted_by) VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?) RETURNING $COLUMNS",
            VIEW, UUID.randomUUID(), subject, kind.name, depotId, "user:" + ctx.actor.subject).single()
        audit.record(j, AuditEntry(ActorRef("user:" + ctx.actor.subject), AuditActions.ROSTER_DEPOT_GRANTED, ObjectRef("roster_depot_grant", v.id), ctx.correlationId,
            mapOf("scope_kind" to kind.name, "depot_id" to depotId?.toString())))
        return v
    }

    @AuditRequired
    private fun doRevoke(j: JdbcOperations, ctx: RequestContext, grantId: UUID): DepotGrantView {
        val cur = j.query("SELECT $COLUMNS FROM taktovia.roster_depot_grant g WHERE g.id = ? FOR UPDATE", VIEW, grantId).firstOrNull() ?: throw ProblemException.notFound()
        if (cur.revokedAt != null) throw ProblemException(ErrorCode.CONFLICT, "Die Vergabe ist bereits entzogen.")
        val v = j.query("UPDATE taktovia.roster_depot_grant g SET revoked_at = clock_timestamp(), revoked_by = ? WHERE g.id = ? RETURNING $COLUMNS", VIEW, "user:" + ctx.actor.subject, grantId).single()
        audit.record(j, AuditEntry(ActorRef("user:" + ctx.actor.subject), AuditActions.ROSTER_DEPOT_REVOKED, ObjectRef("roster_depot_grant", v.id), ctx.correlationId,
            mapOf("scope_kind" to v.kind.name, "depot_id" to v.depotId?.toString())))
        return v
    }

    private companion object {
        const val ADMIN = "organization:write"
        const val COLUMNS = "g.id, g.subject, g.scope_kind, g.depot_id, g.granted_by, g.granted_at, g.revoked_at"
        val VIEW = RowMapper { rs, _ ->
            DepotGrantView(rs.getObject("id", UUID::class.java), rs.getString("subject"), DepotGrantKind.valueOf(rs.getString("scope_kind")), rs.getObject("depot_id", UUID::class.java),
                rs.getString("granted_by"), rs.getTimestamp("granted_at").toInstant(), rs.getTimestamp("revoked_at")?.toInstant())
        }
    }
}

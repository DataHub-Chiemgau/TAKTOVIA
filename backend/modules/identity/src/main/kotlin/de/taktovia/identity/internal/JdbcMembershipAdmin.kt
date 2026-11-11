package de.taktovia.identity.internal

import de.taktovia.identity.api.GrantMembership
import de.taktovia.identity.api.MembershipAdmin
import de.taktovia.identity.api.MembershipView
import de.taktovia.platform.audit.ActorRef
import de.taktovia.platform.audit.AuditActions
import de.taktovia.platform.audit.AuditEntry
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.audit.AuditRequired
import de.taktovia.platform.audit.ObjectRef
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.outbox.OutboxEvent
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Mitgliedschaften erteilen und entziehen: Fachzeile, Audit und Outbox in **einer** Transaktion im Mandanten des Kontexts. */
internal class JdbcMembershipAdmin(
    private val ctxTx: ContextTransactions, private val audit: AuditPort, private val outbox: Outbox, private val clock: Clock,
) : MembershipAdmin {

    override fun grant(ctx: RequestContext, cmd: GrantMembership): MembershipView {
        validate(cmd)
        return ctxTx.run(ctx) { j -> doGrant(j, ctx, cmd) }
    }

    override fun revoke(ctx: RequestContext, id: UUID, expectedRevision: Long) {
        ctxTx.run(ctx) { j -> doRevoke(j, ctx, id, expectedRevision) }
    }

    override fun list(ctx: RequestContext, limit: Int, after: Pair<Instant, UUID>?): List<MembershipView> = ctxTx.run(ctx, readOnly = true) { j ->
        if (after == null) j.query("SELECT $COLUMNS FROM taktovia.membership m ORDER BY m.created_at, m.id LIMIT ?", MAPPER, limit + 1)
        else j.query("SELECT $COLUMNS FROM taktovia.membership m WHERE (m.created_at, m.id) > (?, ?) ORDER BY m.created_at, m.id LIMIT ?", MAPPER,
            Timestamp.from(after.first), after.second, limit + 1)
    }

    @AuditRequired
    private fun doGrant(j: JdbcOperations, ctx: RequestContext, cmd: GrantMembership): MembershipView {
        val id = UUID.randomUUID()
        val view = j.query(
            "INSERT INTO taktovia.membership AS m (tenant_id, id, issuer, subject, role_code, service_scope, valid_from, valid_to) " +
                "VALUES (taktovia.current_tenant(), ?, ?, ?, ?, CAST(? AS uuid[]), COALESCE(?, clock_timestamp()), ?) RETURNING $COLUMNS",
            MAPPER, id, cmd.issuer, cmd.subject, cmd.roleCode, cmd.serviceScope?.joinToString(",", "{", "}"),
            cmd.validFrom?.let(Timestamp::from), cmd.validTo?.let(Timestamp::from),
        ).single()
        audit.record(j, AuditEntry(
            actor = auditActor(ctx), action = AuditActions.MEMBERSHIP_GRANTED, obj = ObjectRef("membership", id), correlationId = ctx.correlationId,
            changes = mapOf("role_code" to cmd.roleCode, "service_scope" to cmd.serviceScope?.map { it.toString() }, "valid_to" to cmd.validTo?.toString(), "revision" to view.revision),
        ))
        outbox.append(j, OutboxEvent(id, view.revision, "membership.granted", payload(view), clock.instant(), correlationId = ctx.correlationId))
        return view
    }

    @AuditRequired
    private fun doRevoke(j: JdbcOperations, ctx: RequestContext, id: UUID, expectedRevision: Long) {
        val views = j.query(
            "UPDATE taktovia.membership m SET revoked_at = clock_timestamp() WHERE m.id = ? AND m.revision = ? AND m.revoked_at IS NULL RETURNING $COLUMNS",
            MAPPER, id, expectedRevision,
        )
        val view = views.singleOrNull() ?: run {
            // Warum keine Zeile: unbekannt (RLS: auch fremder Mandant) 404, schon entzogen 409, veraltete Revision 412.
            val state = j.query("SELECT m.revoked_at IS NOT NULL FROM taktovia.membership m WHERE m.id = ?", { rs, _ -> rs.getBoolean(1) }, id).firstOrNull()
            throw when (state) {
                null -> ProblemException.notFound()
                true -> ProblemException(ErrorCode.CONFLICT, "Die Mitgliedschaft ist bereits entzogen.")
                false -> ProblemException(ErrorCode.REVISION_MISMATCH, "Die Mitgliedschaft wurde zwischenzeitlich geändert; aktuelle Revision lesen.")
            }
        }
        audit.record(j, AuditEntry(
            actor = auditActor(ctx), action = AuditActions.MEMBERSHIP_REVOKED, obj = ObjectRef("membership", id), correlationId = ctx.correlationId,
            changes = mapOf("role_code" to view.roleCode, "revision" to view.revision),
        ))
        outbox.append(j, OutboxEvent(id, view.revision, "membership.revoked", payload(view), clock.instant(), correlationId = ctx.correlationId))
    }

    private fun payload(v: MembershipView): Map<String, Any?> = mapOf(
        "membership_id" to v.id.toString(), "issuer" to v.issuer, "subject" to v.subject, "role_code" to v.roleCode,
        "service_scope" to v.serviceScope?.map { it.toString() }, "valid_from" to v.validFrom.toString(), "valid_to" to v.validTo?.toString(),
        "revoked_at" to v.revokedAt?.toString(), "revision" to v.revision,
    )

    private fun validate(c: GrantMembership) {
        val v = mutableListOf<Violation>()
        if (c.issuer.isEmpty() || c.issuer.length > 512) v += Violation("/issuer", ViolationCode.INVALID_VALUE)
        if (c.subject.isEmpty() || c.subject.length > 200) v += Violation("/subject", ViolationCode.INVALID_VALUE)
        if (!ROLE.matches(c.roleCode)) v += Violation("/role_code", ViolationCode.PATTERN_MISMATCH)
        if ((c.serviceScope?.size ?: 0) > 500) v += Violation("/service_scope", ViolationCode.TOO_MANY_ITEMS)
        if (c.validFrom != null && c.validTo != null && !c.validTo.isAfter(c.validFrom)) v += Violation("/valid_to", ViolationCode.INTERVAL_INVALID)
        if (v.isNotEmpty()) throw ProblemException.invalid(*v.toTypedArray())
    }

    private companion object {
        val ROLE = Regex("^[a-z][a-z0-9_]{1,63}$")
        const val COLUMNS = "m.id, m.issuer, m.subject, m.role_code, m.service_scope, m.valid_from, m.valid_to, m.revoked_at, m.revision, m.created_at"
        val MAPPER = RowMapper { rs, _ ->
            MembershipView(
                rs.getObject("id", UUID::class.java), rs.getString("issuer"), rs.getString("subject"), rs.getString("role_code"),
                rs.getArray("service_scope")?.let { a -> (a.array as Array<*>).map { it as UUID } },
                rs.getTimestamp("valid_from").toInstant(), rs.getTimestamp("valid_to")?.toInstant(), rs.getTimestamp("revoked_at")?.toInstant(),
                rs.getLong("revision"), rs.getTimestamp("created_at").toInstant(),
            )
        }
    }
}

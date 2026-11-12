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
import de.taktovia.stafftime.api.AbsenceKind
import de.taktovia.stafftime.api.AbsenceState
import de.taktovia.stafftime.api.AbsenceView
import de.taktovia.stafftime.api.Absences
import de.taktovia.stafftime.api.AvailabilityRestriction
import de.taktovia.stafftime.api.CreateAbsence
import de.taktovia.stafftime.api.UnavailableInterval
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * Abwesenheit als Verfügbarkeitsrestriktion (WP-M4-06). Rechte: Funktionsscope aus der Rechtematrix (hier zusätzlich als zweite Verteidigungslinie geprüft), Mandant
 * ausschließlich aus dem Kontext (RLS). Die Rücknahme serialisiert über die Zeile (`FOR UPDATE`); die Datenbank begrenzt jede Änderung auf die Rücknahme (Trigger).
 */
internal class JdbcAbsences(private val ctxTx: ContextTransactions, private val audit: AuditPort) : Absences {

    override fun create(ctx: RequestContext, cmd: CreateAbsence): AbsenceView {
        require(ctx, SCOPE_WRITE)
        val person = cmd.personRef.trim()
        if (person.isEmpty() || person.length > 200) throw ProblemException.invalid(Violation("/person_ref", if (person.isEmpty()) ViolationCode.REQUIRED else ViolationCode.TOO_LONG))
        if (!cmd.to.isAfter(cmd.from)) throw ProblemException.invalid(Violation("/to", ViolationCode.INVALID_VALUE))
        return ctxTx.run(ctx) { j -> doCreate(j, ctx, person, cmd) }
    }

    override fun withdraw(ctx: RequestContext, absenceId: UUID, expectedRevision: Long): AbsenceView {
        require(ctx, SCOPE_WRITE)
        return ctxTx.run(ctx) { j -> doWithdraw(j, ctx, absenceId, expectedRevision) }
    }

    override fun get(ctx: RequestContext, absenceId: UUID): AbsenceView {
        require(ctx, SCOPE_DETAIL)
        return ctxTx.run(ctx, readOnly = true) { j -> j.query("SELECT $COLUMNS FROM taktovia.absence_request a WHERE a.id = ?", VIEW, absenceId).firstOrNull() ?: throw ProblemException.notFound() }
    }

    override fun restrictions(ctx: RequestContext, personRef: String?, from: Instant?, to: Instant?, limit: Int, after: Pair<Instant, UUID>?): List<AvailabilityRestriction> {
        require(ctx, SCOPE_READ)
        return ctxTx.run(ctx, readOnly = true) { j ->
            val fromTs = from?.let(Timestamp::from); val toTs = to?.let(Timestamp::from); val afterTs = after?.first?.let(Timestamp::from)
            // feste Anweisung mit optionalen Filtern (NULL = kein Filter); nur Person und Zeitraum verlassen die Datenbank, nie Art oder Urheber
            j.query(
                "SELECT a.id, a.person_ref, a.starts_at, a.ends_at, a.created_at FROM taktovia.absence_request a WHERE a.state = 'APPROVED' " +
                    "AND (CAST(? AS text) IS NULL OR a.person_ref = CAST(? AS text)) AND (CAST(? AS timestamptz) IS NULL OR a.ends_at > CAST(? AS timestamptz)) " +
                    "AND (CAST(? AS timestamptz) IS NULL OR a.starts_at < CAST(? AS timestamptz)) " +
                    "AND (CAST(? AS timestamptz) IS NULL OR (a.created_at, a.id) > (CAST(? AS timestamptz), CAST(? AS uuid))) ORDER BY a.created_at, a.id LIMIT ?",
                RESTRICTION, personRef, personRef, fromTs, fromTs, toTs, toTs, afterTs, afterTs, after?.second, limit + 1,
            )
        }
    }

    override fun restrictionsIn(jdbc: JdbcOperations, personRef: String, from: Instant, to: Instant): List<UnavailableInterval> =
        jdbc.query(
            "SELECT a.starts_at, a.ends_at FROM taktovia.absence_request a WHERE a.state = 'APPROVED' AND a.person_ref = ? AND a.ends_at > ? AND a.starts_at < ? ORDER BY a.starts_at, a.id",
            { rs, _ -> UnavailableInterval(rs.getTimestamp(1).toInstant(), rs.getTimestamp(2).toInstant()) }, personRef, Timestamp.from(from), Timestamp.from(to),
        )

    @AuditRequired
    private fun doCreate(j: JdbcOperations, ctx: RequestContext, person: String, cmd: CreateAbsence): AbsenceView {
        // Absperrung gegen die Publikationsprüfung des Dienstplans (WP-M4-07): geteilt untereinander, exklusiv gegenüber der Prüfung, die den committeten Stand werten muss
        j.queryForObject("SELECT pg_advisory_xact_lock_shared(hashtextextended('roster_absence|' || taktovia.current_tenant()::text, 0))", String::class.java)
        val id = UUID.randomUUID()
        val v = j.query(
            "INSERT INTO taktovia.absence_request AS a (tenant_id, id, person_ref, starts_at, ends_at, kind, created_by) VALUES (taktovia.current_tenant(),?,?,?,?,?,?) RETURNING $COLUMNS",
            VIEW, id, person, Timestamp.from(cmd.from), Timestamp.from(cmd.to), cmd.kind.name, ctx.actor.subject,
        ).single()
        audit.record(j, entry(ctx, v, AuditActions.ABSENCE_CREATED))
        return v
    }

    @AuditRequired
    private fun doWithdraw(j: JdbcOperations, ctx: RequestContext, id: UUID, expected: Long): AbsenceView {
        val cur = j.query("SELECT $COLUMNS FROM taktovia.absence_request a WHERE a.id = ? FOR UPDATE", VIEW, id).firstOrNull() ?: throw ProblemException.notFound()
        if (cur.revision != expected) throw ProblemException(ErrorCode.REVISION_MISMATCH)
        if (cur.state == AbsenceState.WITHDRAWN) return cur   // wiederholt: bereits zurückgenommen, gleicher Stand
        val v = j.query("UPDATE taktovia.absence_request a SET state = 'WITHDRAWN' WHERE a.id = ? RETURNING $COLUMNS", VIEW, id).single()
        audit.record(j, entry(ctx, v, AuditActions.ABSENCE_WITHDRAWN))
        return v
    }

    /** Auditeintrag ohne Person und Art (HR-Daten gehören nicht ins Änderungsprotokoll): Objekt, Verb und Revision. */
    private fun entry(ctx: RequestContext, v: AbsenceView, action: String) =
        AuditEntry(ActorRef("user:" + ctx.actor.subject), action, ObjectRef("absence", v.id), ctx.correlationId, mapOf("revision" to v.revision, "state" to v.state.name))

    private fun require(ctx: RequestContext, scope: String) {
        if (!ctx.hasScope(scope)) throw ProblemException(ErrorCode.FORBIDDEN)
    }

    private companion object {
        const val SCOPE_WRITE = "absences:write"
        const val SCOPE_READ = "absences:read"
        const val SCOPE_DETAIL = "absences:detail"
        const val COLUMNS = "a.id, a.person_ref, a.starts_at, a.ends_at, a.kind, a.state, a.created_by, a.created_at, a.revision"
        val VIEW = RowMapper { rs, _ ->
            AbsenceView(rs.getObject("id", UUID::class.java), rs.getString("person_ref"), rs.getTimestamp("starts_at").toInstant(), rs.getTimestamp("ends_at").toInstant(),
                AbsenceKind.valueOf(rs.getString("kind")), AbsenceState.valueOf(rs.getString("state")), rs.getString("created_by"), rs.getTimestamp("created_at").toInstant(), rs.getLong("revision"))
        }
        val RESTRICTION = RowMapper { rs, _ ->
            AvailabilityRestriction(rs.getObject("id", UUID::class.java), rs.getString("person_ref"), rs.getTimestamp("starts_at").toInstant(), rs.getTimestamp("ends_at").toInstant(), rs.getTimestamp("created_at").toInstant())
        }
    }
}

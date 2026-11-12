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
import de.taktovia.stafftime.api.CreateRoster
import de.taktovia.stafftime.api.NeedLine
import de.taktovia.stafftime.api.RosterSide
import de.taktovia.stafftime.api.RosterState
import de.taktovia.stafftime.api.RosterView
import de.taktovia.stafftime.api.RosterViewKind
import de.taktovia.stafftime.api.Rosters
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import java.sql.Date
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.UUID

/**
 * Dienstplan mit getrenntem Entwurf und veröffentlichtem Plan (WP-M4-04) auf `roster_revision` und `staff_shift`. Die Entwurfssicht setzt erst nach der Rollenprüfung den Datenbankschalter
 * für Entwurfszeilen ([RosterDraftAccess]); die verbindliche Sicht liest ohne ihn. Änderungen am Entwurf sperren die Entwurfszeile des Dienstplans (Reihenfolge Dienstplan, dann Schicht:
 * keine Verklemmung mit der Veröffentlichung) und schreiben nie ein Outbox-Ereignis.
 */
internal class JdbcRosters(private val ctxTx: ContextTransactions, private val audit: AuditPort, private val conflicts: RosterConflictChecker, private val hints: RosterHints) : Rosters {

    override fun create(ctx: RequestContext, cmd: CreateRoster): RosterView {
        RosterAuthz.require(ctx, RosterAuthz.WRITE)
        if (cmd.weekStart.dayOfWeek != DayOfWeek.MONDAY) throw ProblemException.invalid(Violation("/week_start", ViolationCode.INVALID_VALUE, "Die Woche beginnt am Montag"))
        return try {
            ctxTx.run(ctx) { j -> doCreate(j, ctx, cmd) }
        } catch (e: DataIntegrityViolationException) { throw mapCreate(e) }
    }

    override fun get(ctx: RequestContext, rosterId: UUID, view: RosterViewKind): RosterView {
        RosterAuthz.require(ctx, RosterAuthz.READ)
        return ctxTx.run(ctx, readOnly = true) { j ->
            if (view == RosterViewKind.PLANNER) RosterDraftAccess.enable(j)
            DepotGuard.requireForRoster(j, ctx, rosterId, forWrite = false)
            load(j, rosterId, view, canSee = canSee(j, ctx), ctx = ctx) ?: throw ProblemException.notFound()
        }
    }

    override fun list(ctx: RequestContext, depotId: UUID?, from: LocalDate?, to: LocalDate?, limit: Int, after: Pair<LocalDate, UUID>?): List<RosterView> {
        RosterAuthz.require(ctx, RosterAuthz.READ)
        return ctxTx.run(ctx, readOnly = true) { j ->
            RosterDraftAccess.enable(j)
            // Depotscope: Filter schränken ihn nur ein; leer = nichts (fail-closed), `null` = alle Depots (ausdrückliches depotübergreifendes Recht)
            val visible = DepotGuard.visible(j, ctx)
            val scope = visible?.joinToString(",", "{", "}")
            // feste Anweisung mit optionalen Filtern (NULL = kein Filter); je Dienstplan die Kopfzeile der höchsten Revision
            val keys = j.query(
                "SELECT DISTINCT r.roster_id, r.week_start FROM taktovia.roster_revision r WHERE (CAST(? AS uuid) IS NULL OR r.depot_id = CAST(? AS uuid)) " +
                    "AND (CAST(? AS uuid[]) IS NULL OR r.depot_id = ANY (CAST(? AS uuid[]))) " +
                    "AND (CAST(? AS date) IS NULL OR r.week_start >= CAST(? AS date)) AND (CAST(? AS date) IS NULL OR r.week_start <= CAST(? AS date)) " +
                    "AND (CAST(? AS date) IS NULL OR (r.week_start, r.roster_id) > (CAST(? AS date), CAST(? AS uuid))) ORDER BY r.week_start, r.roster_id LIMIT ?",
                { rs, _ -> rs.getObject(1, UUID::class.java) },
                depotId, depotId, scope, scope, from?.let(Date::valueOf), from?.let(Date::valueOf), to?.let(Date::valueOf), to?.let(Date::valueOf), after?.first?.let(Date::valueOf), after?.first?.let(Date::valueOf), after?.second, limit + 1,
            )
            keys.mapNotNull { load(j, it, RosterViewKind.PLANNER, withConflicts = false) }
        }
    }

    override fun openDraft(ctx: RequestContext, rosterId: UUID, expectedEtag: Long): RosterView {
        RosterAuthz.require(ctx, RosterAuthz.WRITE)
        return ctxTx.run(ctx) { j -> doOpenDraft(j, ctx, rosterId, expectedEtag) }
    }

    override fun requirePublishable(jdbc: JdbcOperations, ctx: RequestContext, rosterId: UUID) {
        RosterAuthz.require(ctx, "rosters:publish")
        RosterDraftAccess.enable(jdbc)
        // Absperrung: gleichzeitige Abwesenheitsgenehmigungen warten bis zum Ende dieser Transaktion, die Prüfung wertet den committeten Stand (SCH-006, konkurrierend)
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended('roster_absence|' || taktovia.current_tenant()::text, 0))", String::class.java)
        DepotGuard.requireForRoster(jdbc, ctx, rosterId, forWrite = true)   // Depotrecht mit FOR SHARE: ein gleichzeitiger Entzug wartet, ein committeter wird gesehen (404)
        val draft = jdbc.query("SELECT r.revision FROM taktovia.roster_revision r WHERE r.roster_id = ? AND r.state = 'DRAFT' FOR UPDATE", { rs, _ -> rs.getLong(1) }, rosterId).firstOrNull() ?: throw ProblemException.notFound()
        conflicts.requireNone(conflicts.check(jdbc, rosterId, draft, canSee(jdbc, ctx)))
    }

    @AuditRequired
    private fun doCreate(j: JdbcOperations, ctx: RequestContext, cmd: CreateRoster): RosterView {
        RosterDraftAccess.enable(j)
        val access = DepotGuard.require(j, ctx, cmd.depotId, forWrite = true)
        if (j.queryForObject("SELECT count(*) FROM taktovia.depot WHERE id = ?", Int::class.java, cmd.depotId) == 0) throw ProblemException.notFound()
        val rosterId = UUID.randomUUID()
        j.update(
            "INSERT INTO taktovia.roster_revision (tenant_id, id, roster_id, depot_id, week_start, revision, author) VALUES (taktovia.current_tenant(), ?, ?, ?, ?, 1, ?)",
            UUID.randomUUID(), rosterId, cmd.depotId, Date.valueOf(cmd.weekStart), "user:" + ctx.actor.subject,
        )
        audit.record(j, AuditEntry(ActorRef("user:" + ctx.actor.subject), AuditActions.ROSTER_CREATED, ObjectRef("roster", rosterId), ctx.correlationId,
            mapOf("depot_id" to cmd.depotId.toString(), "week_start" to cmd.weekStart.toString(), "revision" to 1)))
        RosterAudit.crossDepot(audit, j, ctx, access, rosterId, cmd.depotId, "roster.create")
        return load(j, rosterId, RosterViewKind.PLANNER, ctx = ctx)!!
    }

    @AuditRequired
    private fun doOpenDraft(j: JdbcOperations, ctx: RequestContext, rosterId: UUID, expectedEtag: Long): RosterView {
        RosterDraftAccess.enable(j)
        val (depotOfRoster, access) = DepotGuard.requireForRoster(j, ctx, rosterId, forWrite = true)
        val latest = j.query("SELECT r.revision, r.state, r.depot_id, r.week_start, r.entity_revision FROM taktovia.roster_revision r WHERE r.roster_id = ? ORDER BY r.revision DESC LIMIT 1 FOR UPDATE", { rs, _ -> Triple(rs.getLong(1), rs.getString(2), rs.getDate(4).toLocalDate()) to (rs.getObject(3, UUID::class.java) to rs.getLong(5)) }, rosterId)
            .firstOrNull() ?: throw ProblemException.notFound()
        val (rev, state, week) = latest.first; val depot = latest.second.first
        if (state == "DRAFT") throw ProblemException(ErrorCode.CONFLICT, "Der Dienstplan hat bereits einen Entwurf.")
        if (state != "PUBLISHED") throw ProblemException(ErrorCode.CONFLICT, "Der Dienstplan hat keine veröffentlichte Revision, aus der ein Entwurf entstehen könnte.")
        if (latest.second.second != expectedEtag) throw ProblemException(ErrorCode.REVISION_MISMATCH, "Der Dienstplan wurde zwischenzeitlich geändert; aktuellen Stand lesen.")
        val next = rev + 1
        j.update("INSERT INTO taktovia.roster_revision (tenant_id, id, roster_id, depot_id, week_start, revision, author) VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?)",
            UUID.randomUUID(), rosterId, depot, Date.valueOf(week), next, "user:" + ctx.actor.subject)
        // Kopie der veröffentlichten Schichten (neue Kennungen, Zustand DRAFT); die veröffentlichten Zeilen bleiben unberührt
        j.update(
            "INSERT INTO taktovia.staff_shift (tenant_id, id, roster_id, roster_revision, driver_ref, vehicle_id, starts_at, ends_at, timezone, state, operator_id, depot_id, shift_function, required_qualification, " +
                "staffing_need, break_plan, created_by) SELECT s.tenant_id, gen_random_uuid(), s.roster_id, ?, s.driver_ref, s.vehicle_id, s.starts_at, s.ends_at, s.timezone, 'DRAFT', s.operator_id, s.depot_id, " +
                "s.shift_function, s.required_qualification, s.staffing_need, s.break_plan, ? FROM taktovia.staff_shift s WHERE s.roster_id = ? AND s.roster_revision = ? AND s.state = 'PUBLISHED'",
            next, "user:" + ctx.actor.subject, rosterId, rev,
        )
        audit.record(j, AuditEntry(ActorRef("user:" + ctx.actor.subject), AuditActions.ROSTER_DRAFT_OPENED, ObjectRef("roster", rosterId), ctx.correlationId, mapOf("revision" to next, "based_on" to rev)))
        RosterAudit.crossDepot(audit, j, ctx, access, rosterId, depotOfRoster, "draft.open")
        return load(j, rosterId, RosterViewKind.PLANNER, ctx = ctx)!!
    }

    private fun mapCreate(e: DataIntegrityViolationException): ProblemException {
        val msg = generateSequence<Throwable>(e) { it.cause }.mapNotNull { it.message }.joinToString(" ")
        return when {
            msg.contains("roster_revision_tenant_id_depot_id_week_start_revision_key") -> ProblemException(ErrorCode.CONFLICT, "Für Depot und Woche besteht bereits ein Dienstplan.")
            msg.contains("foreign key", ignoreCase = true) -> ProblemException.notFound()
            else -> throw e
        }
    }

    /** Lädt den Dienstplan; `null`, wenn er in der Sicht nicht sichtbar ist (nichts oder nur Entwurfszeilen ohne Schalter). */
    private fun load(j: JdbcOperations, rosterId: UUID, view: RosterViewKind, withConflicts: Boolean = view == RosterViewKind.PLANNER, canSee: (UUID?) -> Boolean = { true }, ctx: RequestContext? = null): RosterView? {
        val heads = j.query(HEAD, HEAD_MAPPER, rosterId).filter { view == RosterViewKind.PLANNER || it.state == RosterState.PUBLISHED }
        val draft = heads.firstOrNull { it.state == RosterState.DRAFT }
        val published = heads.firstOrNull { it.state == RosterState.PUBLISHED }
        if (draft == null && published == null) return null
        val any = draft ?: published!!
        val etag = if (view == RosterViewKind.PLANNER) (draft?.etag ?: published!!.etag) else published!!.etag
        val found = if (withConflicts) (draft?.let { conflicts.check(j, rosterId, it.revision, canSee) } ?: emptyList()) else null
        val found2 = if (withConflicts && ctx != null) (draft ?: published)?.let { hints.compute(j, ctx, rosterId, it.revision, canSee) } else null
        return RosterView(rosterId, any.depotId, any.weekStart, draft?.let { side(j, it) }, published?.let { side(j, it) }, found, etag, found2)
    }

    /** Depots, deren Schichten in Meldungen mit Kennung erscheinen dürfen (Depotscope der Person; `null` = alle). */
    private fun canSee(j: JdbcOperations, ctx: RequestContext): (UUID?) -> Boolean { val v = DepotGuard.visible(j, ctx); return { d -> d == null || v == null || d in v } }

    private class Head(val id: UUID, val revision: Long, val state: RosterState, val author: String, val createdAt: java.time.Instant, val publishedAt: java.time.Instant?, val etag: Long, val depotId: UUID, val weekStart: LocalDate)

    private fun side(j: JdbcOperations, h: Head): RosterSide {
        val lines = RosterCounters.needLines(j, h.id)
        val counts = RosterCounters.counts(j, h.id)
        return RosterSide(h.id, h.revision, h.state, h.author, h.createdAt, h.publishedAt, h.etag, counts.first, counts.second, lines.sumOf { it.unstaffed }, lines)
    }

    private companion object {
        const val HEAD = "SELECT r.id, r.revision, r.state, r.author, r.created_at, r.published_at, r.entity_revision, r.depot_id, r.week_start FROM taktovia.roster_revision r WHERE r.roster_id = ? " +
            "AND r.state IN ('DRAFT', 'PUBLISHED') ORDER BY r.revision DESC"
        val HEAD_MAPPER = RowMapper { rs, _ ->
            Head(rs.getObject("id", UUID::class.java), rs.getLong("revision"), RosterState.valueOf(rs.getString("state")), rs.getString("author"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("published_at")?.toInstant(), rs.getLong("entity_revision"), rs.getObject("depot_id", UUID::class.java), rs.getDate("week_start").toLocalDate())
        }
    }
}

/** Zähler einer Revision (WP-M4-05): Zahl der Schichten, offene Schichten und der Personalbedarf je Tag und Funktion. */
internal object RosterCounters {
    fun counts(j: JdbcOperations, revisionId: UUID): Pair<Int, Int> =
        j.query("SELECT count(*), count(*) FILTER (WHERE s.driver_ref IS NULL) FROM taktovia.staff_shift s JOIN taktovia.roster_revision r ON r.tenant_id = s.tenant_id AND r.roster_id = s.roster_id AND r.revision = s.roster_revision " +
            "WHERE r.id = ? AND s.state <> 'CANCELLED'", { rs, _ -> rs.getInt(1) to rs.getInt(2) }, revisionId).single()

    fun needLines(j: JdbcOperations, revisionId: UUID): List<NeedLine> =
        j.query("SELECT s.operating_day, s.shift_function, sum(s.staffing_need)::int, count(*) FILTER (WHERE s.driver_ref IS NOT NULL)::int FROM taktovia.staff_shift s " +
            "JOIN taktovia.roster_revision r ON r.tenant_id = s.tenant_id AND r.roster_id = s.roster_id AND r.revision = s.roster_revision WHERE r.id = ? AND s.state <> 'CANCELLED' " +
            "GROUP BY s.operating_day, s.shift_function ORDER BY s.operating_day, s.shift_function", { rs, _ -> NeedLine(rs.getDate(1).toLocalDate(), rs.getString(2), rs.getInt(3), rs.getInt(4)) }, revisionId)
}

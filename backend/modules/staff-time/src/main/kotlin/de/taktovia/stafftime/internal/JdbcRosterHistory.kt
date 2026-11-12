package de.taktovia.stafftime.internal

import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.stafftime.api.BreakPlan
import de.taktovia.stafftime.api.HistoricShift
import de.taktovia.stafftime.api.RosterHistory
import de.taktovia.stafftime.api.RosterRevisionHead
import de.taktovia.stafftime.api.RosterRevisionSnapshot
import de.taktovia.stafftime.api.RosterState
import de.taktovia.stafftime.api.ShiftBreak
import de.taktovia.stafftime.api.ShiftChange
import de.taktovia.stafftime.api.ShiftChangeKind
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import tools.jackson.databind.json.JsonMapper
import java.time.ZoneId
import java.util.UUID

/** Revisionen und Historie (WP-M4-15) als reine Leseabfragen über `roster_revision`, `staff_shift` und `roster_shift_change`; Rechte: `rosters:audit` und Depotrecht (404 sonst). */
internal class JdbcRosterHistory(private val ctxTx: ContextTransactions) : RosterHistory {
    private val json = JsonMapper.builder().build()

    override fun revisions(ctx: RequestContext, rosterId: UUID): List<RosterRevisionHead> = read(ctx, rosterId) { j ->
        j.query("SELECT $HEAD_COLUMNS FROM taktovia.roster_revision r WHERE r.roster_id = ? ORDER BY r.revision LIMIT 500", HEAD, rosterId)
    }

    override fun revision(ctx: RequestContext, rosterId: UUID, revision: Long): RosterRevisionSnapshot = read(ctx, rosterId) { j ->
        val head = j.query("SELECT $HEAD_COLUMNS FROM taktovia.roster_revision r WHERE r.roster_id = ? AND r.revision = ? AND r.state IN ('PUBLISHED', 'SUPERSEDED')", HEAD, rosterId, revision).firstOrNull()
            ?: throw ProblemException.notFound()
        val shifts = j.query(
            "SELECT s.id, COALESCE(s.lineage_id, s.id), s.operating_day, s.starts_at, s.ends_at, s.timezone, s.shift_function, s.required_qualification, s.staffing_need, s.driver_ref, s.vehicle_id, s.depot_id, s.break_plan::text, s.state " +
                "FROM taktovia.staff_shift s WHERE s.roster_id = ? AND s.roster_revision = ? AND s.state <> 'CANCELLED' ORDER BY s.starts_at, s.id LIMIT 2000",
            RowMapper { rs, _ ->
                val plan = json.readTree(rs.getString(13)).get("breaks").values().map { b -> ShiftBreak(b.get("offset_min").asInt(), b.get("duration_min").asInt(), b.get("paid").asBoolean()) }
                HistoricShift(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getDate(3).toLocalDate(), rs.getTimestamp(4).toInstant(), rs.getTimestamp(5).toInstant(), ZoneId.of(rs.getString(6)),
                    rs.getString(7), rs.getString(8), rs.getInt(9), rs.getObject(10, UUID::class.java), rs.getObject(11, UUID::class.java), rs.getObject(12, UUID::class.java), BreakPlan(plan), rs.getString(14))
            }, rosterId, revision)
        RosterRevisionSnapshot(head, shifts)
    }

    override fun shiftHistory(ctx: RequestContext, rosterId: UUID, shiftId: UUID): List<ShiftChange> = read(ctx, rosterId) { j ->
        val lineage = j.query("SELECT COALESCE(s.lineage_id, s.id) FROM taktovia.staff_shift s WHERE s.id = ? AND s.roster_id = ?", { rs, _ -> rs.getObject(1, UUID::class.java) }, shiftId, rosterId).firstOrNull()
            ?: throw ProblemException.notFound()
        j.query(
            "SELECT c.id, c.change_kind, c.roster_revision, c.shift_id, c.shift_revision, c.actor, c.occurred_at, c.snapshot::text FROM taktovia.roster_shift_change c WHERE c.roster_id = ? AND c.lineage_id = ? " +
                "ORDER BY c.occurred_at, c.id LIMIT 1000",
            { rs, _ ->
                @Suppress("UNCHECKED_CAST")
                ShiftChange(rs.getObject(1, UUID::class.java), ShiftChangeKind.valueOf(rs.getString(2)), rs.getLong(3), rs.getObject(4, UUID::class.java), rs.getLong(5), rs.getString(6), rs.getTimestamp(7).toInstant(),
                    json.readValue(rs.getString(8), Map::class.java) as Map<String, Any?>)
            }, rosterId, lineage,
        )
    }

    /** Rolle (`rosters:audit`), Entwurfsschalter nach bestandener Rollenprüfung, Depotrecht (404 für nicht sichtbare Dienstpläne). */
    private fun <T> read(ctx: RequestContext, rosterId: UUID, body: (JdbcOperations) -> T): T {
        RosterAuthz.require(ctx, SCOPE_AUDIT)
        return ctxTx.run(ctx, readOnly = true) { j ->
            RosterDraftAccess.enable(j)
            DepotGuard.requireForRoster(j, ctx, rosterId, forWrite = false)
            body(j)
        }
    }

    private companion object {
        const val SCOPE_AUDIT = "rosters:audit"
        const val HEAD_COLUMNS = "r.revision, r.state, r.author, r.created_at, r.published_at, r.published_by, r.effective_from, r.entity_revision"
        val HEAD = RowMapper { rs, _ ->
            RosterRevisionHead(rs.getLong(1), RosterState.valueOf(rs.getString(2)), rs.getString(3), rs.getTimestamp(4).toInstant(), rs.getTimestamp(5)?.toInstant(), rs.getString(6), rs.getTimestamp(7)?.toInstant(), rs.getLong(8))
        }
    }
}

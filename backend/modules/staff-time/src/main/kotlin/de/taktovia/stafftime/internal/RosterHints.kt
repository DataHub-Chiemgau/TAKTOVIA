package de.taktovia.stafftime.internal

import de.taktovia.platform.context.RequestContext
import de.taktovia.stafftime.api.HintBasis
import de.taktovia.stafftime.api.RuleHint
import de.taktovia.stafftime.api.WorkRuleEngine
import de.taktovia.stafftime.api.WorkRuleSource
import de.taktovia.stafftime.api.WorkSpan
import org.springframework.jdbc.core.JdbcOperations
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.time.Duration
import java.time.ZoneId
import java.util.UUID

/**
 * Regelhinweise eines Dienstplans (WP-M4-13): Ruhezeit, Tagesarbeitszeit und Pflichtpause aus den **geplanten** Schichten der Revision, ergänzt um verbindliche Schichten derselben Personen
 * außerhalb dieses Dienstplans (Nachbarwochen, M3-Zuweisungen) als Bezug. Nur lesend; schreibt nie Plan- oder Ist-Daten. Zeilen eines nicht sichtbaren Depots ([canSee]) erscheinen nie mit Kennung.
 */
internal class RosterHints(private val source: WorkRuleSource) {
    private val json = JsonMapper.builder().build()

    private class Row(val span: WorkSpan, val depot: UUID?, val inRevision: Boolean)

    fun compute(j: JdbcOperations, ctx: RequestContext, rosterId: UUID, revision: Long, canSee: (UUID?) -> Boolean = { true }): List<RuleHint> {
        val own = j.query(
            "SELECT s.id, s.driver_ref, s.starts_at, s.ends_at, s.timezone, s.break_plan::text, s.depot_id FROM taktovia.staff_shift s WHERE s.roster_id = ? AND s.roster_revision = ? " +
                "AND s.driver_ref IS NOT NULL AND s.state NOT IN ('CANCELLED', 'SUPERSEDED') ORDER BY s.starts_at, s.id", { rs, _ -> row(rs, true) }, rosterId, revision,
        )
        if (own.isEmpty()) return emptyList()
        val persons = own.map { it.span.personRef }.distinct()
        val from = Timestamp.from(own.minOf { it.span.start }.minus(Duration.ofHours(48))); val to = Timestamp.from(own.maxOf { it.span.end }.plus(Duration.ofHours(48)))
        val neighbours = j.query(
            "SELECT s.id, s.driver_ref, s.starts_at, s.ends_at, s.timezone, s.break_plan::text, s.depot_id FROM taktovia.staff_shift s WHERE s.driver_ref = ANY (CAST(? AS uuid[])) " +
                "AND s.state IN ('PLANNED', 'STARTED', 'ENDED', 'PUBLISHED') AND s.roster_id IS DISTINCT FROM ? AND s.ends_at > ? AND s.starts_at < ? ORDER BY s.starts_at, s.id",
            { rs, _ -> row(rs, false) }, persons.joinToString(",", "{", "}"), rosterId, from, to,
        )
        val rows = own + neighbours
        val byId = rows.associateBy { it.span.shiftId }
        val rules = source.rules(j, ctx, own.minOf { it.span.start })
        return WorkRuleEngine.evaluate(rows.map { it.span }, rules, HintBasis.PLAN) { s -> byId[s.shiftId]?.inRevision == true }.map { h ->
            // Schichten eines nicht sichtbaren Depots nur als Zahl im Text, nie mit Kennung
            h.copy(shiftIds = h.shiftIds.filter { id -> byId[id]?.let { it.inRevision || canSee(it.depot) } ?: true })
        }
    }

    private fun row(rs: java.sql.ResultSet, inRevision: Boolean): Row {
        val breaks = json.readTree(rs.getString(6)).get("breaks").values().sumOf { it.get("duration_min").asLong() }
        return Row(WorkSpan(rs.getObject(2, UUID::class.java).toString(), rs.getTimestamp(3).toInstant(), rs.getTimestamp(4).toInstant(), ZoneId.of(rs.getString(5)), breaks, rs.getObject(1, UUID::class.java)), rs.getObject(7, UUID::class.java), inRevision)
    }
}

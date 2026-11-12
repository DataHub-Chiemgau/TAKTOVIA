package de.taktovia.stafftime.internal

import de.taktovia.platform.retention.ApplyResult
import de.taktovia.platform.retention.Candidate
import de.taktovia.platform.retention.RetentionAction
import de.taktovia.platform.retention.RetentionTarget
import de.taktovia.platform.retention.RuleSnapshot
import org.springframework.jdbc.core.JdbcOperations
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * Löschziel `LK_STANDORT` für Rohpositionen (WP-M3-34, Kriterium 5): fällig ist eine Position, wenn ihre Empfangszeit plus Nachlauffrist der freigegebenen Regel erreicht ist.
 * Gelöscht wird die Zeile selbst (kein Archiv, keine Anonymisierung: Standortdaten sind kurzlebige Rohdaten, Kap. 01 §1.7); Wiederholung ist idempotent.
 */
internal class PositionRetentionTarget : RetentionTarget {
    override val dataClass = "LK_STANDORT"
    override val objectType = "position_sample"
    override val supportedActions = setOf(RetentionAction.DELETE)

    override fun candidates(jdbc: JdbcOperations, rule: RuleSnapshot, now: Instant, after: UUID?, limit: Int): List<Candidate> =
        jdbc.query(
            "SELECT id FROM taktovia.position_sample WHERE received_at + make_interval(days => ?) <= ? AND (CAST(? AS uuid) IS NULL OR id > CAST(? AS uuid)) ORDER BY id LIMIT ?",
            { rs, _ -> Candidate(rs.getObject(1, UUID::class.java), RetentionAction.DELETE) }, rule.retentionDays, Timestamp.from(now), after, after, limit,
        )

    override fun apply(jdbc: JdbcOperations, rule: RuleSnapshot, candidate: Candidate, now: Instant): ApplyResult {
        jdbc.update("DELETE FROM taktovia.position_sample WHERE id = ?", candidate.id)
        return ApplyResult(RetentionAction.DELETE)
    }
}

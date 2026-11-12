package de.taktovia.stafftime.internal

import de.taktovia.stafftime.api.RunReleaseState
import org.springframework.jdbc.core.JdbcOperations
import java.time.Instant
import java.util.UUID

/**
 * Einsatzfreigabe des Fahrzeugs für eine Schicht zum Zeitpunkt [at] (Lesesicht auf `resource_release` und `vehicle`; Entscheidung und Pflege gehören WP-M3-08/-09): gültige, nicht
 * widerrufene Freigabe mit aktueller `safety_revision` und ohne operativen Block. Gemeinsam benutzt vom Fahrerlauf (WP-M3-25) und vom Ereigniseingang (WP-M3-29).
 */
internal object ReleaseCheck {
    fun check(j: JdbcOperations, shiftId: UUID, vehicleId: UUID, at: Instant): Pair<RunReleaseState, List<String>> {
        val v = j.query("SELECT active_block, safety_revision FROM taktovia.vehicle WHERE id = ?", { rs, _ -> rs.getBoolean(1) to rs.getLong(2) }, vehicleId).firstOrNull()
            ?: return RunReleaseState.NOT_RELEASED to listOf("NO_INSPECTION")
        val rel = j.query("SELECT safety_revision, valid_until, revoked_at FROM taktovia.resource_release WHERE shift_id = ? AND vehicle_id = ? ORDER BY valid_until DESC, id LIMIT 1",
            { rs, _ -> Triple(rs.getLong(1), rs.getTimestamp(2).toInstant(), rs.getTimestamp(3)?.toInstant()) }, shiftId, vehicleId).firstOrNull()
        return when {
            rel == null -> RunReleaseState.NOT_RELEASED to listOf("NO_INSPECTION")
            rel.third != null -> RunReleaseState.NOT_RELEASED to listOf("RELEASE_REVOKED")
            !rel.second.isAfter(at) -> RunReleaseState.NOT_RELEASED to listOf("NO_INSPECTION")
            rel.first != v.second -> RunReleaseState.NOT_RELEASED to listOf("SAFETY_REVISION_CHANGED")
            v.first -> RunReleaseState.BLOCKED to listOf("OPERATIONAL_BLOCK")
            else -> RunReleaseState.RELEASED to emptyList()
        }
    }
}

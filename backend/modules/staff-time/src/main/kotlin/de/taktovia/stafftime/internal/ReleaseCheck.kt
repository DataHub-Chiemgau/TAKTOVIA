package de.taktovia.stafftime.internal

import de.taktovia.platform.inspection.InspectionStatus
import de.taktovia.platform.inspection.InspectionStatusDerivation
import de.taktovia.platform.inspection.InspectionStatusFacts
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
        val v = j.query("SELECT active_block, safety_revision, active FROM taktovia.vehicle WHERE id = ?", { rs, _ -> Triple(rs.getBoolean(1), rs.getLong(2), rs.getBoolean(3)) }, vehicleId).firstOrNull()
            ?: return RunReleaseState.NOT_RELEASED to listOf("NO_INSPECTION")
        // aktive Sicherheitssperre (Fahrzeug) oder Dienstsperre dieser Schicht (WP-M3-16): gesperrt mit Grund, unabhängig vom Stand der Freigabe
        val safetyBlocked = j.queryForObject("SELECT (EXISTS (SELECT 1 FROM taktovia.vehicle_block WHERE vehicle_id = ? AND status = 'ACTIVE' AND source = 'SAFETY') " +
            "OR EXISTS (SELECT 1 FROM taktovia.shift_block WHERE shift_id = ? AND status = 'ACTIVE'))", Boolean::class.java, vehicleId, shiftId) == true
        if (safetyBlocked) return RunReleaseState.BLOCKED to listOf("DEFECT_BLOCK")
        if (!v.third) return RunReleaseState.BLOCKED to listOf("OPERATIONAL_BLOCK")
        val rel = j.query("SELECT safety_revision, valid_until, revoked_at FROM taktovia.resource_release WHERE shift_id = ? AND vehicle_id = ? ORDER BY valid_until DESC, id LIMIT 1",
            { rs, _ -> Triple(rs.getLong(1), rs.getTimestamp(2).toInstant(), rs.getTimestamp(3)?.toInstant()) }, shiftId, vehicleId).firstOrNull()
        return when {
            rel == null -> RunReleaseState.NOT_RELEASED to listOf(if (openInspection(j, shiftId, vehicleId)) "INSPECTION_OPEN" else "NO_INSPECTION")
            rel.third != null -> RunReleaseState.NOT_RELEASED to listOf("RELEASE_REVOKED")
            !rel.second.isAfter(at) -> RunReleaseState.NOT_RELEASED to listOf("NO_INSPECTION")
            rel.first != v.second -> RunReleaseState.NOT_RELEASED to listOf("SAFETY_REVISION_CHANGED")
            v.first -> RunReleaseState.BLOCKED to listOf("OPERATIONAL_BLOCK")
            else -> RunReleaseState.RELEASED to emptyList()
        }
    }

    /**
     * Prüfstatus der Kombination für die Fahrer-App (WP-M3-23): dieselbe Ableitung wie die Leitstellenansicht ([InspectionStatusDerivation]), Fakten wie dort gelesen. Zweiter Wert: Statusrevision
     * (`safety_revision` plus Summe der Laufrevisionen der Schicht), monoton steigend.
     */
    fun inspectionStatus(j: JdbcOperations, shiftId: UUID, vehicleId: UUID, at: Instant): Pair<InspectionStatus, Long> {
        val (state, _) = check(j, shiftId, vehicleId, at)
        val blocked = j.queryForObject("SELECT (EXISTS (SELECT 1 FROM taktovia.vehicle_block WHERE vehicle_id = ? AND status = 'ACTIVE') OR EXISTS (SELECT 1 FROM taktovia.shift_block WHERE shift_id = ? AND status = 'ACTIVE') " +
            "OR EXISTS (SELECT 1 FROM taktovia.vehicle WHERE id = ? AND NOT active))", Boolean::class.java, vehicleId, shiftId, vehicleId) == true
        val open = openInspection(j, shiftId, vehicleId)
        val released = state == RunReleaseState.RELEASED
        val restricted = released && j.queryForObject(
            "SELECT count(*) FROM taktovia.inspection_run r WHERE r.id = (SELECT x.inspection_ref FROM taktovia.resource_release x WHERE x.vehicle_id = ? AND x.shift_id = ? ORDER BY (x.revoked_at IS NULL) DESC, x.valid_until DESC, x.id LIMIT 1) " +
                "AND jsonb_array_length(COALESCE(r.restriction -> 'unavailable_dimensions', '[]'::jsonb)) > 0", Int::class.java, vehicleId, shiftId)!! > 0
        val run = j.query("SELECT sum(revision), (array_agg(outcome ORDER BY started_at DESC, id) FILTER (WHERE kind <> 'PRE_BOARDING'))[1] FROM taktovia.inspection_run WHERE shift_id = ? AND vehicle_id = ?",
            { rs, _ -> rs.getLong(1) to rs.getString(2) }, shiftId, vehicleId).firstOrNull()
        val safety = j.query("SELECT safety_revision FROM taktovia.vehicle WHERE id = ?", { rs, _ -> rs.getLong(1) }, vehicleId).firstOrNull() ?: 0L
        // wie die Leitstellenprojektion: Sperrrevisionen und Freigaben (Erteilung, Widerruf) zählen zur Revision
        val extra = j.queryForObject(
            "SELECT COALESCE((SELECT sum(revision) FROM taktovia.vehicle_block WHERE vehicle_id = ?), 0) + COALESCE((SELECT sum(revision) FROM taktovia.shift_block WHERE shift_id = ?), 0) " +
                "+ COALESCE((SELECT sum(1 + (revoked_at IS NOT NULL)::int) FROM taktovia.resource_release WHERE shift_id = ?), 0)", Long::class.java, vehicleId, shiftId, shiftId) ?: 0L
        return InspectionStatusDerivation.derive(InspectionStatusFacts(blocked, open, released, restricted, run?.second)) to (safety + (run?.first ?: 0L) + extra)
    }

    /** Ein offener Prüflauf (nicht die Prüfung vor Fahrgastaufnahme) dieser Kombination: „Prüfung nicht abgeschlossen“ statt „Prüfung fehlt“. */
    private fun openInspection(j: JdbcOperations, shiftId: UUID, vehicleId: UUID): Boolean =
        j.queryForObject("SELECT count(*) FROM taktovia.inspection_run WHERE shift_id = ? AND vehicle_id = ? AND status = 'IN_PROGRESS' AND kind NOT IN ('PRE_BOARDING', 'TECH_READINESS')", Int::class.java, shiftId, vehicleId)!! > 0
}

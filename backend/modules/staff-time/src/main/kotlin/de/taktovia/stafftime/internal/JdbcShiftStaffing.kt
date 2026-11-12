package de.taktovia.stafftime.internal

import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.readports.ShiftStaffingReadPort
import de.taktovia.platform.readports.ShiftStaffingView
import de.taktovia.platform.readports.StaffingGap
import org.springframework.jdbc.core.JdbcOperations
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * Leseport „Schichtbesetzung“ (WP-M4-17, LH-6.23-SCH-007) auf `staff_shift` und `qualification_revision` (eigene Tabellen). **Ein** Statement je Aufruf: die Zeile einer Schicht kommt aus einem
 * Snapshot, eine parallele Zuweisung wird nie als Zwischenstand gelesen. Besetzt ist ein Fahrzeug nur mit einer verbindlichen Schicht (`PUBLISHED` oder bereits `STARTED`), die einen Fahrer trägt und
 * den ganzen Zeitraum deckt; Entwürfe sind per restriktiver RLS-Policy (`draft_visibility`) nie sichtbar, weil der Port den Entwurfsschalter nicht setzt. Verlangt die Schicht eine Qualifikation, muss
 * der Fahrer zu Schichtbeginn eine freigegebene, gültige Revision haben (sonst [StaffingGap.QUALIFICATION_MISSING]). Keine Freigabe, kein Schreibzugriff.
 */
internal class JdbcShiftStaffing(private val ctxTx: ContextTransactions) : ShiftStaffingReadPort {
    override fun staffing(ctx: RequestContext, vehicleId: UUID, from: Instant, to: Instant): ShiftStaffingView =
        ctxTx.run(ctx, readOnly = true) { j -> staffing(j, vehicleId, from, to) }

    override fun staffing(j: JdbcOperations, vehicleId: UUID, from: Instant, to: Instant): ShiftStaffingView = staffing(j, listOf(vehicleId), from, to).getValue(vehicleId)

    override fun staffing(j: JdbcOperations, vehicleIds: Collection<UUID>, from: Instant, to: Instant): Map<UUID, ShiftStaffingView> {
        require(!to.isBefore(from)) { "Zeitraum: Ende vor Beginn" }
        val ids = vehicleIds.distinct()
        if (ids.isEmpty()) return emptyMap()
        val f = Timestamp.from(from); val t = Timestamp.from(to)
        val rows = j.query(
            "SELECT v.vid, s.id, s.shift_revision, s.driver_ref, s.roster_revision, s.depot_id, s.service_id, s.required_qualification, s.starts_at, s.ends_at, q.revision AS q_rev, (o.n > 0) AS other " +
                "FROM unnest(CAST(? AS uuid[])) AS v(vid) " +
                "LEFT JOIN LATERAL (SELECT sh.id, sh.shift_revision, sh.driver_ref, sh.roster_revision, sh.depot_id, sh.service_id, sh.required_qualification, sh.starts_at, sh.ends_at " +
                "FROM taktovia.staff_shift sh WHERE sh.vehicle_id = v.vid AND sh.driver_ref IS NOT NULL AND sh.state IN ('PUBLISHED', 'STARTED') AND sh.starts_at <= ? AND sh.ends_at >= ? " +
                "ORDER BY sh.starts_at, sh.id LIMIT 1) s ON true " +
                "LEFT JOIN LATERAL (SELECT max(qr.revision) AS revision FROM taktovia.qualification_revision qr WHERE qr.driver_id = s.driver_ref AND qr.qualification_code = s.required_qualification " +
                "AND qr.approval_status = 'APPROVED' AND qr.valid_from <= s.starts_at AND (qr.valid_to IS NULL OR qr.valid_to > s.starts_at)) q ON true " +
                "LEFT JOIN LATERAL (SELECT count(*) AS n FROM taktovia.staff_shift ov WHERE ov.vehicle_id = v.vid AND ov.driver_ref IS NOT NULL AND ov.state IN ('PUBLISHED', 'STARTED') " +
                "AND ov.starts_at < ? AND ov.ends_at > ?) o ON true ORDER BY v.vid",
            { rs, _ ->
                val vid = rs.getObject("vid", UUID::class.java); val shift = rs.getObject("id", UUID::class.java)
                val qual = rs.getString("required_qualification"); val qrev = rs.getLong("q_rev").takeUnless { rs.wasNull() }
                val base = ShiftStaffingView(vid, from, to, false, null)
                vid to when {
                    shift == null -> base.copy(gap = if (rs.getBoolean("other")) StaffingGap.OUTSIDE_PERIOD else StaffingGap.NO_SHIFT)
                    qual != null && qrev == null -> base.copy(gap = StaffingGap.QUALIFICATION_MISSING, shiftId = shift)
                    else -> base.copy(
                        staffed = true, shiftId = shift, shiftRevision = rs.getLong("shift_revision"), driverRef = rs.getObject("driver_ref", UUID::class.java), rosterRevision = rs.getLong("roster_revision"),
                        depotId = rs.getObject("depot_id", UUID::class.java), serviceId = rs.getObject("service_id", UUID::class.java), qualificationCode = qual, qualificationRevision = qrev,
                        startsAt = rs.getTimestamp("starts_at").toInstant(), endsAt = rs.getTimestamp("ends_at").toInstant(),
                    )
                }
            },
            "{" + ids.joinToString(",") + "}", f, t, t, f,
        )
        return rows.toMap()
    }
}

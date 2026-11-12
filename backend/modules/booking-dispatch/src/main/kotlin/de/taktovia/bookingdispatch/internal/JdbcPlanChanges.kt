package de.taktovia.bookingdispatch.internal

import de.taktovia.bookingdispatch.api.BookingPlanChange
import de.taktovia.bookingdispatch.api.DriverPlanChange
import de.taktovia.bookingdispatch.api.DriverPlanSnapshot
import de.taktovia.bookingdispatch.api.DriverRunPlan
import de.taktovia.bookingdispatch.api.PassengerBookingPlan
import de.taktovia.bookingdispatch.api.PassengerPlanChange
import de.taktovia.bookingdispatch.api.PassengerPlanSnapshot
import de.taktovia.bookingdispatch.api.PlanBookingChangeKind
import de.taktovia.bookingdispatch.api.PlanChangeEntry
import de.taktovia.bookingdispatch.api.PlanChangeFeed
import de.taktovia.bookingdispatch.api.PlanChanges
import de.taktovia.bookingdispatch.api.PlanChannel
import de.taktovia.bookingdispatch.api.PlanSnapshot
import de.taktovia.bookingdispatch.api.RevisionCause
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import org.springframework.jdbc.core.JdbcOperations
import java.sql.ResultSet
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Änderungsstrom und Snapshot auf `plan_change`/`plan_change_booking` (WP-M2-28). Der Kanal wird gegen die Rolle des Akteurs geprüft; die Empfängerbindung steckt in den Abfragen
 * (Fahrgast: eigene Buchungen über `booking.passenger_id`; Fahrer: Umlauf der eigenen Schicht), der Mandant in RLS. Lesen ändert nichts und läuft in einer Transaktion
 * (konsistenter Stand von Cursor und Einträgen).
 */
internal class JdbcPlanChanges(private val ctxTx: ContextTransactions, private val clock: Clock) : PlanChanges {

    override fun feed(ctx: RequestContext, channel: PlanChannel, after: Long, limit: Int): PlanChangeFeed {
        require(limit in 1..MAX_LIMIT && after >= 0) { "limit/after außerhalb des Bereichs" }
        authorize(ctx, channel)
        return ctxTx.run(ctx, readOnly = true) { j ->
            val items = if (channel == PlanChannel.PASSENGER) passengerFeed(j, ctx, after, limit + 1) else driverFeed(j, ctx, after, limit + 1)
            val page = items.take(limit)
            PlanChangeFeed(page, page.lastOrNull()?.cursor ?: after, items.size > limit)
        }
    }

    override fun snapshot(ctx: RequestContext, channel: PlanChannel): PlanSnapshot {
        authorize(ctx, channel)
        return ctxTx.run(ctx, readOnly = true) { j ->
            val cursor = j.queryForObject("SELECT coalesce(max(seq), 0) FROM taktovia.plan_change", Long::class.java)!!
            if (channel == PlanChannel.PASSENGER) PassengerPlanSnapshot(clock.instant(), cursor, passengerPlans(j, ctx))
            else DriverPlanSnapshot(clock.instant(), cursor, driverPlans(j, ctx))
        }
    }

    private fun authorize(ctx: RequestContext, channel: PlanChannel) {
        val role = if (channel == PlanChannel.PASSENGER) "passenger" else "driver"
        if (ctx.actor.type != ActorType.HUMAN || role !in ctx.roles) throw ProblemException(ErrorCode.FORBIDDEN)
    }

    private fun passengerOf(j: JdbcOperations, ctx: RequestContext): UUID? = j.query(
        "SELECT id FROM taktovia.passenger_private WHERE idp_issuer = ? AND idp_subject = ?", { rs, _ -> rs.getObject(1, UUID::class.java) }, ctx.actor.issuer, ctx.actor.subject,
    ).firstOrNull()

    private fun passengerFeed(j: JdbcOperations, ctx: RequestContext, after: Long, limit: Int): List<PlanChangeEntry> {
        val own = passengerOf(j, ctx) ?: return emptyList()   // ohne Konto gibt es keine eigenen Buchungen
        class Row(val seq: Long, val cause: RevisionCause, val recordedAt: Instant, val change: BookingPlanChange)
        val rows = j.query(
            "SELECT seq, cause, recorded_at, booking_id, kind, booking_seq, pickup_before, pickup_after, dropoff_before, dropoff_after FROM (" +
                "SELECT pc.seq, pc.cause, pc.recorded_at, pcb.booking_id, pcb.kind, pcb.booking_seq, pcb.pickup_before, pcb.pickup_after, pcb.dropoff_before, pcb.dropoff_after, " +
                "dense_rank() OVER (ORDER BY pc.seq) AS page_rank " +
                "FROM taktovia.plan_change pc JOIN taktovia.plan_change_booking pcb ON pcb.tenant_id = pc.tenant_id AND pcb.plan_change_id = pc.id " +
                "JOIN taktovia.booking b ON b.tenant_id = pcb.tenant_id AND b.id = pcb.booking_id " +
                "WHERE b.passenger_id = ? AND b.booking_no IS NOT NULL AND pc.seq > ?) x WHERE page_rank <= ? ORDER BY seq, booking_id",
            { rs, _ ->
                Row(rs.getLong("seq"), RevisionCause.valueOf(rs.getString("cause")), rs.getTimestamp("recorded_at").toInstant(),
                    BookingPlanChange(rs.getObject("booking_id", UUID::class.java), PlanBookingChangeKind.valueOf(rs.getString("kind")), rs.getLong("booking_seq"),
                        rs.instant("pickup_before"), rs.instant("pickup_after"), rs.instant("dropoff_before"), rs.instant("dropoff_after")))
            }, own, after, limit,
        )
        return rows.groupBy { it.seq }.map { (seq, group) -> PassengerPlanChange(seq, group.first().cause, group.first().recordedAt, group.map { it.change }) }
    }

    private fun driverFeed(j: JdbcOperations, ctx: RequestContext, after: Long, limit: Int): List<PlanChangeEntry> = j.query(
        "SELECT pc.seq, pc.cause, pc.recorded_at, pc.run_id, pc.run_revision, pc.previous_revision, vr.shift_id, " +
            "coalesce((SELECT array_agg(pcb.booking_id ORDER BY pcb.booking_id) FROM taktovia.plan_change_booking pcb WHERE pcb.tenant_id = pc.tenant_id AND pcb.plan_change_id = pc.id), '{}') AS booking_ids " +
            "FROM taktovia.plan_change pc JOIN taktovia.vehicle_run vr ON vr.tenant_id = pc.tenant_id AND vr.id = pc.run_id " +
            "JOIN taktovia.staff_shift s ON s.tenant_id = vr.tenant_id AND s.id = vr.shift_id " +
            "WHERE $OWN_SHIFT AND pc.seq > ? ORDER BY pc.seq LIMIT ?",
        { rs, _ ->
            DriverPlanChange(rs.getLong("seq"), RevisionCause.valueOf(rs.getString("cause")), rs.getTimestamp("recorded_at").toInstant(), rs.getObject("shift_id", UUID::class.java),
                rs.getObject("run_id", UUID::class.java), rs.getLong("run_revision"), rs.getLong("previous_revision"), uuids(rs, "booking_ids"))
        }, ctx.actor.subject, ctx.actor.issuer, ctx.actor.subject, after, limit,
    )

    private fun passengerPlans(j: JdbcOperations, ctx: RequestContext): List<PassengerBookingPlan> {
        val own = passengerOf(j, ctx) ?: return emptyList()
        return j.query(
            "SELECT b.id, b.revision, l.booking_seq, l.kind, l.pickup_after, l.dropoff_after FROM taktovia.booking b " +
                "LEFT JOIN LATERAL (SELECT booking_seq, kind, pickup_after, dropoff_after FROM taktovia.plan_change_booking x WHERE x.tenant_id = b.tenant_id AND x.booking_id = b.id ORDER BY booking_seq DESC LIMIT 1) l ON true " +
                "WHERE b.passenger_id = ? AND b.booking_no IS NOT NULL ORDER BY b.created_at DESC, b.id LIMIT ?",
            { rs, _ ->
                val removed = rs.getString("kind") == PlanBookingChangeKind.REMOVED.name
                PassengerBookingPlan(rs.getObject("id", UUID::class.java), rs.getLong("revision"), rs.getLong("booking_seq"),
                    if (removed) null else rs.instant("pickup_after"), if (removed) null else rs.instant("dropoff_after"))
            }, own, SNAPSHOT_MAX,
        )
    }

    private fun driverPlans(j: JdbcOperations, ctx: RequestContext): List<DriverRunPlan> = j.query(
        "SELECT s.id AS shift_id, vr.id AS run_id, vr.revision, vr.state, (SELECT count(*) FROM taktovia.route_stop rs WHERE rs.tenant_id = vr.tenant_id AND rs.run_id = vr.id AND rs.run_revision = vr.revision) AS stops " +
            "FROM taktovia.staff_shift s JOIN taktovia.vehicle_run vr ON vr.tenant_id = s.tenant_id AND vr.shift_id = s.id " +
            "WHERE $OWN_SHIFT AND s.state IN ('PLANNED', 'PUBLISHED', 'STARTED') ORDER BY s.starts_at, s.id LIMIT ?",
        { rs, _ -> DriverRunPlan(rs.getObject("shift_id", UUID::class.java), rs.getObject("run_id", UUID::class.java), rs.getLong("revision"), rs.getString("state"), rs.getInt("stops")) },
        ctx.actor.subject, ctx.actor.issuer, ctx.actor.subject, SNAPSHOT_MAX,
    )

    private fun ResultSet.instant(col: String): Instant? = getTimestamp(col)?.toInstant()

    private fun uuids(rs: ResultSet, col: String): List<UUID> = (rs.getArray(col).array as Array<*>).map { it as UUID }

    private companion object {
        const val MAX_LIMIT = 200
        const val SNAPSHOT_MAX = 100
        /**
         * Eigene Schicht des Fahrers (Konto über Mitgliedschaft und Fahrerstamm, oder die ältere IdP-Subject-UUID in `driver_ref`); gleiche Bedingung wie `staff-time`
         * (`ShiftOwnership`), hier als Lesesicht auf `staff_shift` dupliziert, weil Module nur über ihre API sprechen. Parameter: [subject (klein), issuer, subject].
         */
        const val OWN_SHIFT = "(s.driver_ref::text = lower(?) OR EXISTS (SELECT 1 FROM taktovia.driver dr JOIN taktovia.membership ms ON ms.tenant_id = dr.tenant_id AND ms.id = dr.membership_id " +
            "WHERE dr.tenant_id = s.tenant_id AND dr.id = s.driver_ref AND ms.issuer = ? AND ms.subject = ? AND ms.revoked_at IS NULL))"
    }
}

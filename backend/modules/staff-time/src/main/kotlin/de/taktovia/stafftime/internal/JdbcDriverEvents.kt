package de.taktovia.stafftime.internal

import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.outbox.OutboxEvent
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.stafftime.api.DriverEventCommand
import de.taktovia.stafftime.api.DriverEventOutcome
import de.taktovia.stafftime.api.DriverEventResult
import de.taktovia.stafftime.api.DriverEvents
import org.springframework.jdbc.core.JdbcOperations
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Ereigniseingang der Fahrer-App (WP-M3-24). Eine Transaktion je Ereignis: Gerät prüfen (Konto, Sitzung, nicht beendet), Dedupe über den Primärschlüssel
 * `(tenant_id, event_id)` mit `INSERT … ON CONFLICT DO NOTHING` (atomar auch bei hundert parallelen Wiederholungen), Schicht des Akteurs prüfen, Planrevision vergleichen,
 * Ereignis in der Outbox. Der Fingerprint des Inhalts erkennt „gleiche event_id, anderer Inhalt“ (409).
 * Annahme bis WP-M3-05: `staff_shift.driver_ref` ist die IdP-Subject-UUID des Fahrers (die Tabelle `driver` mit Konto-Bezug entsteht dort).
 */
internal class JdbcDriverEvents(private val ctxTx: ContextTransactions, private val outbox: Outbox, private val clock: Clock) : DriverEvents {

    override fun record(ctx: RequestContext, cmd: DriverEventCommand): DriverEventResult {
        if (ctx.actor.type != ActorType.HUMAN) throw ProblemException(ErrorCode.FORBIDDEN)
        val session = ctx.actor.sessionId ?: throw ProblemException(ErrorCode.FORBIDDEN)
        if (cmd.type.needsBooking && (cmd.bookingId == null || cmd.routeStopId == null)) throw ProblemException.invalid(Violation("/booking_id", ViolationCode.REQUIRED))
        return ctxTx.run(ctx) { j -> doRecord(j, ctx, session, cmd.deviceId ?: boundDevice(j, ctx, session), cmd) }
    }

    private fun doRecord(j: JdbcOperations, ctx: RequestContext, session: String, deviceId: UUID, cmd: DriverEventCommand): DriverEventResult {
        // 1. Gerät: muss dem Akteur gehören (fremd: 404), nicht beendet und an die aktuelle Sitzung gebunden sein (sonst 403: Gerät neu registrieren)
        val dev = j.query("SELECT issuer, subject, idp_session_id, revoked_at FROM taktovia.driver_device WHERE id = ?", { rs, _ ->
            arrayOf<Any?>(rs.getString("issuer"), rs.getString("subject"), rs.getString("idp_session_id"), rs.getTimestamp("revoked_at"))
        }, deviceId).firstOrNull()
        if (dev == null || dev[0] != ctx.actor.issuer || dev[1] != ctx.actor.subject) throw ProblemException.notFound()
        if (dev[3] != null || dev[2] != session) throw ProblemException(ErrorCode.FORBIDDEN)

        val hash = sha256(cmd.contentFingerprint())
        // 2. bereits bekanntes Ereignis: gleicher Inhalt -> DUPLICATE (auch nach Schichtende), anderer Inhalt -> 409
        existing(j, cmd.eventId, hash, ctx)?.let { return it }

        // 3. Schicht und Fahrzeug müssen zum Akteur gehören (fremd, fremder Mandant, fremder Fahrer: 404)
        val shift = j.queryForList("SELECT 1 FROM taktovia.staff_shift s WHERE s.id = ? AND s.vehicle_id = ? AND $OWN_SHIFT", cmd.shiftId, cmd.vehicleId, *ShiftOwnership.args(ctx.actor.issuer, ctx.actor.subject))
        if (shift.isEmpty()) throw ProblemException.notFound()

        // 4. Planrevision: ein planbezogenes Ereignis auf veralteter Revision ist ein Klärfall, nie ein stiller Zustandswechsel
        val runRevision = j.queryForObject("SELECT max(revision) FROM taktovia.vehicle_run WHERE shift_id = ? AND vehicle_id = ?", Long::class.javaObjectType, cmd.shiftId, cmd.vehicleId)
        val current = maxOf(runRevision ?: 0L, cmd.planRevision)
        val stale = cmd.type.planRelative && runRevision != null && runRevision > cmd.planRevision
        val outcome = if (stale) DriverEventOutcome.REVIEW_REQUIRED else DriverEventOutcome.APPLIED
        val reason = if (stale) "STALE_PLAN" else null

        // 5. einfügen: atomar; ein paralleler Gewinner macht dieses Ereignis zum Duplikat
        val received = j.query(
            "INSERT INTO taktovia.driver_event (tenant_id, event_id, device_id, shift_id, vehicle_id, event_type, booking_id, route_stop_id, payload_sha256, outcome, reason_code, plan_revision, device_recorded_at, occurred_at) " +
                "VALUES (taktovia.current_tenant(),?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT (tenant_id, event_id) DO NOTHING RETURNING received_at",
            { rs, _ -> rs.getTimestamp(1) }, cmd.eventId, deviceId, cmd.shiftId, cmd.vehicleId, cmd.type.name, cmd.bookingId, cmd.routeStopId, hash, outcome.name, reason, cmd.planRevision,
            Timestamp.from(cmd.deviceRecordedAt), Timestamp.from(cmd.occurredAt),
        ).firstOrNull() ?: return existing(j, cmd.eventId, hash, ctx) ?: throw ProblemException(ErrorCode.CONFLICT)
        val receivedAt = received.toInstant()
        outbox.append(j, OutboxEvent(cmd.eventId, 1, "driver.event_recorded", linkedMapOf(
            "event_id" to cmd.eventId.toString(), "shift_id" to cmd.shiftId.toString(), "vehicle_id" to cmd.vehicleId.toString(), "event_type" to cmd.type.name,
            "outcome" to outcome.name, "route_stop_id" to cmd.routeStopId?.toString(), "received_at" to receivedAt.toString(),
        ), clock.instant(), correlationId = ctx.correlationId))
        return DriverEventResult(cmd.eventId, outcome, receivedAt, current, reason, null)
    }

    /** Ohne `device_id` gilt das eine, an die aktuelle Sitzung gebundene und nicht beendete Gerät des Kontos; sonst ist kein Gerät registriert (404). */
    private fun boundDevice(j: JdbcOperations, ctx: RequestContext, session: String): UUID =
        j.query("SELECT id FROM taktovia.driver_device WHERE issuer = ? AND subject = ? AND idp_session_id = ? AND revoked_at IS NULL", { rs, _ -> rs.getObject(1, UUID::class.java) },
            ctx.actor.issuer, ctx.actor.subject, session).singleOrNull() ?: throw ProblemException.notFound()

    private fun existing(j: JdbcOperations, eventId: UUID, hash: String, ctx: RequestContext): DriverEventResult? {
        val row = j.query("SELECT e.payload_sha256, e.outcome, e.reason_code, e.received_at, e.plan_revision, d.subject, d.issuer FROM taktovia.driver_event e " +
            "JOIN taktovia.driver_device d ON d.tenant_id = e.tenant_id AND d.id = e.device_id WHERE e.event_id = ?", { rs, _ ->
            arrayOf<Any?>(rs.getString(1), rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant(), rs.getLong(5), rs.getString(6), rs.getString(7))
        }, eventId).firstOrNull() ?: return null
        if (row[5] != ctx.actor.subject || row[6] != ctx.actor.issuer) throw ProblemException.notFound()   // Ereignis eines anderen Kontos: nicht auffindbar
        if (row[0] != hash) throw ProblemException(ErrorCode.IDEMPOTENCY_CONFLICT)
        return DriverEventResult(eventId, DriverEventOutcome.DUPLICATE, row[3] as Instant, row[4] as Long, row[2] as String?, DriverEventOutcome.valueOf(row[1] as String))
    }

    private companion object {
        /** Eigene Schicht: Fahrerstamm über die Mitgliedschaft oder (Altbestand) die IdP-Subject-UUID (siehe [ShiftOwnership]). */
        const val OWN_SHIFT = ShiftOwnership.PREDICATE
    }

    private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

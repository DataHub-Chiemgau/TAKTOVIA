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
import de.taktovia.stafftime.api.DriverEventEffects
import de.taktovia.stafftime.api.DriverEventOutcome
import de.taktovia.stafftime.api.DriverEventResult
import de.taktovia.stafftime.api.DriverEventType
import de.taktovia.stafftime.api.DriverEvents
import de.taktovia.stafftime.api.EffectKind
import de.taktovia.stafftime.api.EffectRequest
import de.taktovia.stafftime.api.EffectResult
import de.taktovia.stafftime.api.RunReleaseState
import de.taktovia.stafftime.api.StafftimeSettings
import org.springframework.jdbc.core.JdbcOperations
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Ereigniseingang der Fahrer-App (WP-M3-24) mit fachlicher Wirkung (WP-M3-29). Eine Transaktion je Ereignis: Gerät prüfen (Konto, Sitzung, nicht beendet), **Sperre auf die `event_id`**
 * (parallele identische Wiederholungen laufen nacheinander und sehen dann das erste Ergebnis), Dedupe über den Primärschlüssel `(tenant_id, event_id)`, Schicht des Akteurs prüfen, dann
 * die Prüfkette eines stopp- oder buchungsbezogenen Ereignisses:
 *
 * 1. Schicht nicht angetreten: 409; Ereignis nach Schichtende (Ereigniszeit nach dem Ende) oder Schicht storniert: `REVIEW_REQUIRED` `SHIFT_ENDED`;
 * 2. Uhrabweichung (online: Gerätezeit gegen Serveruhr; jedes Ereignis: Ereigniszeit in der Zukunft): `CLOCK_SKEW`;
 * 3. veraltete Planrevision: `STALE_PLAN`; 4. fehlende Einsatzfreigabe (nicht für den Ausstieg): `RELEASE_REQUIRED`;
 * 5. Wartefrist des Nicht-Erscheinens gegen die plausibilisierte Ankunftszeit (zu früh: 422, nichts gespeichert; ohne Ankunft `INVALID_ORDER`);
 * 6. Wirkung auf Stopp, Buchung und Fahrt über den Port [DriverEventEffects] (`OUTSIDE_STOP`, `INVALID_ORDER`, `BOOKING_CANCELED`, unbekannt: 404).
 *
 * Jedes `REVIEW_REQUIRED` bleibt als historische Beobachtung im Journal, ändert aber nie Buchung, Fahrt oder Fahrzeugsperre (auch keine Schadenssperre: dieses Modul schreibt sie nie).
 * Plausibilisierte Zeit (`effective_at`): online die Serverempfangszeit (die Gerätezeit ist nur auf Abweichung geprüft), offline die Ereigniszeit, höchstens die Empfangszeit.
 */
internal class JdbcDriverEvents(
    private val ctxTx: ContextTransactions, private val outbox: Outbox, private val clock: Clock,
    private val settings: StafftimeSettings = StafftimeSettings(), private val effects: DriverEventEffects = DriverEventEffects.NONE,
) : DriverEvents {

    override fun record(ctx: RequestContext, cmd: DriverEventCommand): DriverEventResult {
        if (ctx.actor.type != ActorType.HUMAN) throw ProblemException(ErrorCode.FORBIDDEN)
        val session = ctx.actor.sessionId ?: throw ProblemException(ErrorCode.FORBIDDEN)
        if (cmd.type.needsBooking && (cmd.bookingId == null || cmd.routeStopId == null)) throw ProblemException.invalid(Violation("/booking_id", ViolationCode.REQUIRED))
        if (cmd.type == DriverEventType.ARRIVAL && cmd.routeStopId == null) throw ProblemException.invalid(Violation("/route_stop_id", ViolationCode.REQUIRED))
        if (cmd.type == DriverEventType.NO_SHOW && cmd.noShowReason == null) throw ProblemException.invalid(Violation("/no_show_reason", ViolationCode.REQUIRED))
        return ctxTx.run(ctx) { j -> doRecord(j, ctx, session, cmd.deviceId ?: boundDevice(j, ctx, session), cmd) }
    }

    private class ShiftRow(val state: String, val startedAt: Instant?, val endedAt: Instant?)

    private fun doRecord(j: JdbcOperations, ctx: RequestContext, session: String, deviceId: UUID, cmd: DriverEventCommand): DriverEventResult {
        // 1. Gerät: muss dem Akteur gehören (fremd: 404), nicht beendet und an die aktuelle Sitzung gebunden sein (sonst 403: Gerät neu registrieren)
        val dev = j.query("SELECT issuer, subject, idp_session_id, revoked_at FROM taktovia.driver_device WHERE id = ?", { rs, _ ->
            arrayOf<Any?>(rs.getString("issuer"), rs.getString("subject"), rs.getString("idp_session_id"), rs.getTimestamp("revoked_at"))
        }, deviceId).firstOrNull()
        if (dev == null || dev[0] != ctx.actor.issuer || dev[1] != ctx.actor.subject) throw ProblemException.notFound()
        if (dev[3] != null || dev[2] != session) throw ProblemException(ErrorCode.FORBIDDEN)

        // 2. dieselbe event_id nie parallel verarbeiten: wer die Sperre erhält, sieht ein bereits committetes Ergebnis des Vorgängers
        j.query("SELECT pg_advisory_xact_lock(hashtextextended(taktovia.current_tenant()::text || ':driver_event:' || ?, 0))", { _, _ -> 0 }, cmd.eventId.toString())
        val hash = sha256(cmd.contentFingerprint())
        existing(j, cmd.eventId, hash, ctx)?.let { return it }

        // 3. Schicht und Fahrzeug müssen zum Akteur gehören (fremd, fremder Mandant, fremder Fahrer: 404)
        val shift = j.query("SELECT s.state, s.started_at, s.ended_at FROM taktovia.staff_shift s WHERE s.id = ? AND s.vehicle_id = ? AND $OWN_SHIFT", { rs, _ ->
            ShiftRow(rs.getString(1), rs.getTimestamp(2)?.toInstant(), rs.getTimestamp(3)?.toInstant())
        }, cmd.shiftId, cmd.vehicleId, *ShiftOwnership.args(ctx.actor.issuer, ctx.actor.subject)).firstOrNull() ?: throw ProblemException.notFound()

        val now = clock.instant()
        val runRevision = j.queryForObject("SELECT max(revision) FROM taktovia.vehicle_run WHERE shift_id = ? AND vehicle_id = ?", Long::class.javaObjectType, cmd.shiftId, cmd.vehicleId)
        val current = maxOf(runRevision ?: 0L, cmd.planRevision)
        val effective = if (cmd.offlineRecorded) minOf(cmd.occurredAt, now) else now
        var outcome = DriverEventOutcome.APPLIED
        var reason: String? = null
        var noShow: NoShowFacts? = null

        if (cmd.type.stopRelated) {
            val tol = settings.clockSkewTolerance
            if (shift.startedAt == null && shift.state != "ENDED" && shift.state != "CANCELLED")
                throw ProblemException(ErrorCode.INVALID_STATE_TRANSITION, "Die Schicht ist nicht angetreten; Fahrerereignisse werden erst nach dem Dienstantritt angenommen.")
            reason = when {
                shift.state == "CANCELLED" || (shift.endedAt != null && cmd.occurredAt.isAfter(shift.endedAt)) -> "SHIFT_ENDED"
                skewed(cmd, now, tol) -> "CLOCK_SKEW"
                runRevision != null && runRevision > cmd.planRevision -> "STALE_PLAN"   // ein planbezogenes Ereignis auf veralteter Revision ist ein Klärfall, nie ein stiller Zustandswechsel
                cmd.type != DriverEventType.CHECK_OUT && ReleaseCheck.check(j, cmd.shiftId, cmd.vehicleId, effective).first != RunReleaseState.RELEASED -> "RELEASE_REQUIRED"
                else -> null
            }
            if (reason == null && cmd.type == DriverEventType.NO_SHOW) {
                val grace = effects.noShowGraceMinutes(j, ctx, cmd.bookingId!!, effective)?.toLong() ?: settings.noShowGraceDefault.toMinutes()
                val arrival = j.query("SELECT min(effective_at) FROM taktovia.driver_event WHERE shift_id = ? AND route_stop_id = ? AND event_type = 'ARRIVAL' AND outcome = 'APPLIED'",
                    { rs, _ -> rs.getTimestamp(1)?.toInstant() }, cmd.shiftId, cmd.routeStopId).firstOrNull()
                if (arrival == null) reason = "INVALID_ORDER"   // Nicht-Erscheinen ohne gemeldete Ankunft am Stopp: die Wartefrist hat nie begonnen
                else {
                    val waited = Duration.between(arrival, effective)
                    if (waited < Duration.ofMinutes(grace)) throw ProblemException.invalid(Violation("/event_type", ViolationCode.NO_SHOW_GRACE_NOT_ELAPSED))
                    noShow = NoShowFacts(cmd.noShowReason!!.name, waited.seconds.toInt(), grace.toInt())
                }
            }
            if (reason == null) {
                val kind = when (cmd.type) { DriverEventType.ARRIVAL -> EffectKind.ARRIVAL; DriverEventType.CHECK_IN -> EffectKind.BOARDED; DriverEventType.CHECK_OUT -> EffectKind.ALIGHTED; else -> EffectKind.NO_SHOW }
                when (val e = effects.apply(j, ctx, EffectRequest(cmd.shiftId, cmd.vehicleId, cmd.routeStopId!!, cmd.bookingId, kind, effective, cmd.eventId))) {
                    is EffectResult.NotFound -> throw ProblemException.notFound()
                    is EffectResult.Review -> reason = e.reasonCode
                    is EffectResult.Applied -> {}
                }
            }
            if (reason != null) { outcome = DriverEventOutcome.REVIEW_REQUIRED; noShow = null }
        }

        // 4. einfügen: atomar; ein paralleler Gewinner macht dieses Ereignis zum Duplikat
        val received = j.query(
            "INSERT INTO taktovia.driver_event (tenant_id, event_id, device_id, shift_id, vehicle_id, event_type, booking_id, route_stop_id, payload_sha256, outcome, reason_code, plan_revision, " +
                "device_recorded_at, occurred_at, received_at, effective_at, offline_recorded, no_show_reason, no_show_wait_seconds, no_show_grace_min) " +
                "VALUES (taktovia.current_tenant(),?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT (tenant_id, event_id) DO NOTHING RETURNING received_at",
            { rs, _ -> rs.getTimestamp(1) }, cmd.eventId, deviceId, cmd.shiftId, cmd.vehicleId, cmd.type.name, cmd.bookingId, cmd.routeStopId, hash, outcome.name, reason, cmd.planRevision,
            Timestamp.from(cmd.deviceRecordedAt), Timestamp.from(cmd.occurredAt), Timestamp.from(now), Timestamp.from(effective), cmd.offlineRecorded, noShow?.reason, noShow?.waitSeconds, noShow?.graceMin,
        ).firstOrNull() ?: return existing(j, cmd.eventId, hash, ctx) ?: throw ProblemException(ErrorCode.CONFLICT)
        val receivedAt = received.toInstant()
        publish(j, ctx, cmd, outcome, reason, noShow, receivedAt, effective)
        return DriverEventResult(cmd.eventId, outcome, receivedAt, current, reason, null)
    }

    private class NoShowFacts(val reason: String, val waitSeconds: Int, val graceMin: Int)

    /** Uhrabweichung: ein Ereignis aus der Zukunft, online eine Gerätezeit außerhalb der Toleranz gegen die Serveruhr, und eine Ereigniszeit weit nach der Gerätezeit der Erfassung. */
    private fun skewed(cmd: DriverEventCommand, now: Instant, tol: Duration): Boolean =
        cmd.occurredAt.isAfter(now.plus(tol)) || cmd.occurredAt.isAfter(cmd.deviceRecordedAt.plus(tol)) ||
            (!cmd.offlineRecorded && Duration.between(cmd.deviceRecordedAt, now).abs() > tol)

    private fun publish(j: JdbcOperations, ctx: RequestContext, cmd: DriverEventCommand, outcome: DriverEventOutcome, reason: String?, noShow: NoShowFacts?, receivedAt: Instant, effective: Instant) {
        fun emit(type: String, payload: LinkedHashMap<String, Any?>) =
            outbox.append(j, OutboxEvent(cmd.eventId, 1, type, payload, clock.instant(), correlationId = ctx.correlationId))
        emit("driver.event_recorded", linkedMapOf<String, Any?>(
            "event_id" to cmd.eventId.toString(), "shift_id" to cmd.shiftId.toString(), "vehicle_id" to cmd.vehicleId.toString(), "event_type" to cmd.type.name,
            "outcome" to outcome.name, "route_stop_id" to cmd.routeStopId?.toString(), "received_at" to receivedAt.toString(), "booking_id" to cmd.bookingId?.toString(),
            "reason_code" to reason, "effective_at" to effective.toString(), "offline_recorded" to cmd.offlineRecorded,
            "no_show_reason" to noShow?.reason, "no_show_wait_seconds" to noShow?.waitSeconds, "no_show_grace_min" to noShow?.graceMin,
        ))
        if (outcome == DriverEventOutcome.REVIEW_REQUIRED) {
            emit("driver.review_opened", linkedMapOf("event_id" to cmd.eventId.toString(), "shift_id" to cmd.shiftId.toString(), "event_type" to cmd.type.name, "reason_code" to reason,
                "booking_id" to cmd.bookingId?.toString(), "route_stop_id" to cmd.routeStopId?.toString()))
            return
        }
        when (cmd.type) {
            DriverEventType.CHECK_IN -> emit("driver.passenger_boarded", linkedMapOf("event_id" to cmd.eventId.toString(), "shift_id" to cmd.shiftId.toString(), "booking_id" to cmd.bookingId.toString(),
                "route_stop_id" to cmd.routeStopId.toString(), "effective_at" to effective.toString()))
            DriverEventType.CHECK_OUT -> emit("driver.passenger_alighted", linkedMapOf("event_id" to cmd.eventId.toString(), "shift_id" to cmd.shiftId.toString(), "booking_id" to cmd.bookingId.toString(),
                "route_stop_id" to cmd.routeStopId.toString(), "effective_at" to effective.toString()))
            // Regelstand und Gebührenwirkung: dieses Paket bucht keine Gebühr (BKG-015 ist P2, OP-21); `fee_booked` ist deshalb immer falsch
            DriverEventType.NO_SHOW -> emit("driver.no_show_recorded", linkedMapOf("event_id" to cmd.eventId.toString(), "shift_id" to cmd.shiftId.toString(), "booking_id" to cmd.bookingId.toString(),
                "route_stop_id" to cmd.routeStopId.toString(), "effective_at" to effective.toString(), "reason" to noShow!!.reason, "wait_seconds" to noShow.waitSeconds, "grace_min" to noShow.graceMin,
                "fee_booked" to false))
            else -> {}
        }
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

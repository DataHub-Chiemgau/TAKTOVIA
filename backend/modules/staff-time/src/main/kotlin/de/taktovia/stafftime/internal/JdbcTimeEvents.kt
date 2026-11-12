package de.taktovia.stafftime.internal

import de.taktovia.platform.audit.ActorRef
import de.taktovia.platform.audit.AuditActions
import de.taktovia.platform.audit.AuditEntry
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.audit.AuditRequired
import de.taktovia.platform.audit.ObjectRef
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.outbox.OutboxEvent
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.stafftime.api.BackfillTimeEvent
import de.taktovia.stafftime.api.DerivedTime
import de.taktovia.stafftime.api.RecordTimeEvent
import de.taktovia.stafftime.api.TimeEventKind
import de.taktovia.stafftime.api.TimeEventResult
import de.taktovia.stafftime.api.TimeEventSource
import de.taktovia.stafftime.api.TimeEventView
import de.taktovia.stafftime.api.TimeEvents
import de.taktovia.stafftime.api.TimeIntervals
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Zeitereignisse der Ist-Arbeitszeit (WP-M4-08). Eine Transaktion je Ereignis: Schicht prüfen, atomar einfügen (`INSERT … ON CONFLICT DO NOTHING`, auch bei parallelen
 * Wiederholungen genau eine Zeile), Ereignis in der Outbox (nur bei Erstwirkung), bei Nacherfassung zusätzlich Audit. Der Inhaltshash erkennt „gleiche ID, anderer Inhalt“.
 * Annahme wie bei den Fahrerereignissen (WP-M3-24): `staff_shift.driver_ref` ist das IdP-Subject (UUID) der Person.
 */
internal class JdbcTimeEvents(private val ctxTx: ContextTransactions, private val audit: AuditPort, private val outbox: Outbox, private val clock: Clock) : TimeEvents {

    override fun record(ctx: RequestContext, cmd: RecordTimeEvent): TimeEventResult {
        require(ctx, SCOPE_WRITE)
        if (ctx.actor.type != ActorType.HUMAN) throw ProblemException(ErrorCode.FORBIDDEN)
        if (cmd.channel == TimeEventSource.BACKFILL) throw ProblemException.invalid(Violation("/channel", ViolationCode.NOT_IN_ENUM))
        val person = ctx.actor.subject
        val hash = sha256(listOf(cmd.eventId, person, cmd.kind, cmd.occurredAt, cmd.deviceRecordedAt, cmd.channel, person, cmd.shiftId, null).joinToString("|"))
        return ctxTx.run(ctx) { j ->
            existing(j, cmd.eventId)?.let { return@run replay(it, hash, selfPerson = person) }
            if (cmd.shiftId != null) requireOwnShift(j, cmd.shiftId, person, notFound = true)
            val row = insert(j, ctx, cmd.eventId, person, cmd.kind, cmd.occurredAt, cmd.deviceRecordedAt, cmd.channel, person, cmd.shiftId, null, hash)
                ?: return@run replay(existing(j, cmd.eventId) ?: throw ProblemException(ErrorCode.CONFLICT), hash, selfPerson = person)
            publish(j, ctx, row)
            TimeEventResult(row, true)
        }
    }

    override fun backfill(ctx: RequestContext, cmd: BackfillTimeEvent): TimeEventResult {
        require(ctx, SCOPE_MANAGE)
        if (ctx.actor.type != ActorType.HUMAN) throw ProblemException(ErrorCode.FORBIDDEN)
        val person = cmd.personRef.trim(); val reason = cmd.reason.trim()
        if (person.isEmpty() || person.length > 200) throw ProblemException.invalid(Violation("/person_ref", if (person.isEmpty()) ViolationCode.REQUIRED else ViolationCode.TOO_LONG))
        if (reason.length < 3 || reason.length > 500) throw ProblemException.invalid(Violation("/reason", if (reason.length < 3) ViolationCode.TOO_SHORT else ViolationCode.TOO_LONG))
        val hash = sha256(listOf(cmd.eventId, person, cmd.kind, cmd.occurredAt, null, TimeEventSource.BACKFILL, ctx.actor.subject, cmd.shiftId, reason).joinToString("|"))
        return ctxTx.run(ctx) { j ->
            existing(j, cmd.eventId)?.let { return@run replay(it, hash, selfPerson = null) }
            if (cmd.shiftId != null) requireOwnShift(j, cmd.shiftId, person, notFound = false)
            val row = insert(j, ctx, cmd.eventId, person, cmd.kind, cmd.occurredAt, null, TimeEventSource.BACKFILL, ctx.actor.subject, cmd.shiftId, reason, hash)
                ?: return@run replay(existing(j, cmd.eventId) ?: throw ProblemException(ErrorCode.CONFLICT), hash, selfPerson = null)
            audited(j, ctx, row)
            publish(j, ctx, row)
            TimeEventResult(row, true)
        }
    }

    override fun list(ctx: RequestContext, personRef: String?, from: Instant?, to: Instant?, shiftId: UUID?, limit: Int, after: Pair<Instant, UUID>?): List<TimeEventView> {
        require(ctx, SCOPE_READ)
        val person = visiblePerson(ctx, personRef) ?: return emptyList()
        return ctxTx.run(ctx, readOnly = true) { j ->
            val fromTs = from?.let(Timestamp::from); val toTs = to?.let(Timestamp::from); val afterTs = after?.first?.let(Timestamp::from)
            // feste Anweisung mit optionalen Filtern (NULL = kein Filter); alle Werte gebunden
            j.query(
                "SELECT $COLUMNS FROM taktovia.time_event e WHERE e.person_ref = ? AND (CAST(? AS timestamptz) IS NULL OR e.occurred_at >= CAST(? AS timestamptz)) " +
                    "AND (CAST(? AS timestamptz) IS NULL OR e.occurred_at < CAST(? AS timestamptz)) AND (CAST(? AS uuid) IS NULL OR e.shift_id = CAST(? AS uuid)) " +
                    "AND (CAST(? AS timestamptz) IS NULL OR (e.received_at, e.id) > (CAST(? AS timestamptz), CAST(? AS uuid))) ORDER BY e.received_at, e.id LIMIT ?",
                VIEW, person, fromTs, fromTs, toTs, toTs, shiftId, shiftId, afterTs, afterTs, after?.second, limit + 1,
            )
        }
    }

    override fun intervals(ctx: RequestContext, personRef: String?, from: Instant, to: Instant): DerivedTime {
        require(ctx, SCOPE_READ)
        if (!to.isAfter(from) || Duration.between(from, to) > MAX_WINDOW) throw ProblemException.invalid(Violation("/\$query/to", ViolationCode.INVALID_VALUE))
        val person = visiblePerson(ctx, personRef) ?: return DerivedTime(emptyList(), emptyList())
        val events = ctxTx.run(ctx, readOnly = true) { j ->
            j.query("SELECT $COLUMNS FROM taktovia.time_event e WHERE e.person_ref = ? AND e.occurred_at >= ? AND e.occurred_at < ? ORDER BY e.occurred_at, e.received_at, e.id LIMIT ?",
                VIEW, person, Timestamp.from(from), Timestamp.from(to), MAX_EVENTS + 1)
        }
        if (events.size > MAX_EVENTS) throw ProblemException.invalid(Violation("/\$query/to", ViolationCode.TOO_MANY_ITEMS))
        return TimeIntervals.derive(events)
    }

    /** Eigene Person (Standard) oder – nur mit `time-events:manage` – eine andere; sonst ist die fremde Person nicht auffindbar. Rückgabe `null` ist nicht möglich (404). */
    private fun visiblePerson(ctx: RequestContext, requested: String?): String? {
        val self = ctx.actor.subject
        if (requested == null || requested == self) return self
        if (!ctx.hasScope(SCOPE_MANAGE)) throw ProblemException.notFound()
        return requested
    }

    private fun requireOwnShift(j: JdbcOperations, shiftId: UUID, person: String, notFound: Boolean) {
        val owner = j.query("SELECT driver_ref::text FROM taktovia.staff_shift WHERE id = ?", { rs, _ -> rs.getString(1) }, shiftId).firstOrNull() ?: throw ProblemException.notFound()
        if (!owner.equals(person, ignoreCase = true)) throw if (notFound) ProblemException.notFound() else ProblemException.invalid(Violation("/shift_id", ViolationCode.INVALID_VALUE))
    }

    private fun existing(j: JdbcOperations, id: UUID): Stored? = j.query("SELECT $COLUMNS, e.payload_sha256 FROM taktovia.time_event e WHERE e.id = ?", { rs, i -> Stored(VIEW.mapRow(rs, i)!!, rs.getString("payload_sha256")) }, id).firstOrNull()

    /** Bekanntes Ereignis: gleicher Inhalt = dasselbe Ereignis; fremde Person (Selbsterfassung) nicht auffindbar; anderer Inhalt 409. */
    private fun replay(s: Stored, hash: String, selfPerson: String?): TimeEventResult {
        if (selfPerson != null && s.view.personRef != selfPerson) throw ProblemException.notFound()
        if (s.hash != hash) throw ProblemException(ErrorCode.IDEMPOTENCY_CONFLICT)
        return TimeEventResult(s.view, false)
    }

    private fun insert(
        j: JdbcOperations, ctx: RequestContext, id: UUID, person: String, kind: TimeEventKind, occurredAt: Instant, deviceAt: Instant?, source: TimeEventSource, by: String,
        shiftId: UUID?, reason: String?, hash: String,
    ): TimeEventView? = j.query(
        "INSERT INTO taktovia.time_event AS e (tenant_id, id, person_ref, kind, occurred_at, device_recorded_at, source, recorded_by, shift_id, backfill_reason, payload_sha256) " +
            "VALUES (taktovia.current_tenant(),?,?,?,?,?,?,?,?,?,?) ON CONFLICT (tenant_id, id) DO NOTHING RETURNING $COLUMNS",
        VIEW, id, person, kind.name, Timestamp.from(occurredAt), deviceAt?.let(Timestamp::from), source.name, by, shiftId, reason, hash,
    ).firstOrNull()

    @AuditRequired
    private fun audited(j: JdbcOperations, ctx: RequestContext, v: TimeEventView) {
        audit.record(j, AuditEntry(ActorRef("user:" + ctx.actor.subject), AuditActions.TIME_EVENT_BACKFILLED, ObjectRef("time_event", v.eventId), ctx.correlationId, mapOf("kind" to v.kind.name, "source" to v.source.name)))
    }

    private fun publish(j: JdbcOperations, ctx: RequestContext, v: TimeEventView) {
        outbox.append(j, OutboxEvent(v.eventId, 1, "staff.time_event_recorded", linkedMapOf(
            "event_id" to v.eventId.toString(), "person_ref" to v.personRef, "kind" to v.kind.name, "source" to v.source.name, "shift_id" to v.shiftId?.toString(),
            "occurred_at" to v.occurredAt.toString(), "received_at" to v.receivedAt.toString(),
        ), clock.instant(), correlationId = ctx.correlationId))
    }

    private fun require(ctx: RequestContext, scope: String) {
        if (!ctx.hasScope(scope)) throw ProblemException(ErrorCode.FORBIDDEN)
    }

    private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private class Stored(val view: TimeEventView, val hash: String)

    private companion object {
        const val SCOPE_WRITE = "time-events:write"
        const val SCOPE_READ = "time-events:read"
        const val SCOPE_MANAGE = "time-events:manage"
        val MAX_WINDOW: Duration = Duration.ofDays(31)
        const val MAX_EVENTS = 5000
        const val COLUMNS = "e.id, e.person_ref, e.kind, e.occurred_at, e.device_recorded_at, e.received_at, e.source, e.recorded_by, e.shift_id, e.backfill_reason"
        val VIEW = RowMapper { rs, _ ->
            TimeEventView(rs.getObject("id", UUID::class.java), rs.getString("person_ref"), TimeEventKind.valueOf(rs.getString("kind")), rs.getTimestamp("occurred_at").toInstant(),
                rs.getTimestamp("device_recorded_at")?.toInstant(), rs.getTimestamp("received_at").toInstant(), TimeEventSource.valueOf(rs.getString("source")), rs.getString("recorded_by"),
                rs.getObject("shift_id", UUID::class.java), rs.getString("backfill_reason"))
        }
    }
}

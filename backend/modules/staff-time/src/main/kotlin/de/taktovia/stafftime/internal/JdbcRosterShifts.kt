package de.taktovia.stafftime.internal

import de.taktovia.platform.audit.ActorRef
import de.taktovia.platform.audit.AuditActions
import de.taktovia.platform.audit.AuditEntry
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.audit.AuditRequired
import de.taktovia.platform.audit.ObjectRef
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.platform.time.IanaZones
import de.taktovia.platform.time.LocalDateTimes
import de.taktovia.platform.time.ZonedMoments
import de.taktovia.stafftime.api.BreakPlan
import de.taktovia.stafftime.api.RosterShiftInput
import de.taktovia.stafftime.api.RosterShiftView
import de.taktovia.stafftime.api.RosterShifts
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/**
 * Schichtmodell (WP-M4-03) auf `staff_shift`. Zeitpunkte entstehen aus lokaler Zeit und Zone nach Kap. 05 §5.1 (Lücke abgelehnt, doppelte Ortszeit nur mit Offset); Betriebstag und Revision leitet die
 * Datenbank ab (Trigger). Die bedingte Änderung (`shift_revision = ?`) serialisiert gleichzeitige Änderer: genau einer gewinnt, der andere erhält 412, nie ein stilles Überschreiben.
 */
internal class JdbcRosterShifts(private val ctxTx: ContextTransactions, private val audit: AuditPort) : RosterShifts {
    private val json = JsonMapper.builder().build()

    override fun create(ctx: RequestContext, rosterId: UUID, input: RosterShiftInput): RosterShiftView {
        require(ctx, SCOPE_WRITE)
        val c = check(input)
        return try { ctxTx.run(ctx) { j -> doCreate(j, ctx, rosterId, input, c) } } catch (e: DataIntegrityViolationException) { throw mapReference(e) }
    }

    override fun get(ctx: RequestContext, rosterId: UUID, shiftId: UUID): RosterShiftView {
        require(ctx, SCOPE_READ)
        return ctxTx.run(ctx, readOnly = true) { j -> j.query("SELECT $COLUMNS FROM taktovia.staff_shift s WHERE s.id = ? AND s.roster_id = ?", VIEW, shiftId, rosterId).firstOrNull() ?: throw ProblemException.notFound() }
    }

    override fun list(ctx: RequestContext, rosterId: UUID, limit: Int, after: Pair<Instant, UUID>?): List<RosterShiftView> {
        require(ctx, SCOPE_READ)
        return ctxTx.run(ctx, readOnly = true) { j ->
            j.query("SELECT $COLUMNS FROM taktovia.staff_shift s WHERE s.roster_id = ? AND (CAST(? AS timestamptz) IS NULL OR (s.starts_at, s.id) > (CAST(? AS timestamptz), CAST(? AS uuid))) ORDER BY s.starts_at, s.id LIMIT ?",
                VIEW, rosterId, after?.first?.let(Timestamp::from), after?.first?.let(Timestamp::from), after?.second, limit + 1)
        }
    }

    override fun update(ctx: RequestContext, rosterId: UUID, shiftId: UUID, expectedRevision: Long, input: RosterShiftInput): RosterShiftView {
        require(ctx, SCOPE_WRITE)
        val c = check(input)
        return try { ctxTx.run(ctx) { j -> doUpdate(j, ctx, rosterId, shiftId, expectedRevision, input, c) } } catch (e: DataIntegrityViolationException) { throw mapReference(e) }
    }

    @AuditRequired
    private fun doCreate(j: JdbcOperations, ctx: RequestContext, rosterId: UUID, i: RosterShiftInput, c: Checked): RosterShiftView {
        val id = UUID.randomUUID()
        val v = j.query(
            "INSERT INTO taktovia.staff_shift AS s (tenant_id, id, roster_id, driver_ref, vehicle_id, starts_at, ends_at, timezone, roster_revision, state, operator_id, depot_id, shift_function, " +
                "required_qualification, staffing_need, break_plan, created_by) VALUES (taktovia.current_tenant(),?,?,?,?,?,?,?,0,'DRAFT',?,?,?,?,?,CAST(? AS jsonb),?) RETURNING $COLUMNS",
            VIEW, id, rosterId, i.driverRef, i.vehicleId, Timestamp.from(c.start), Timestamp.from(c.end), c.zone.id, i.operatorId, i.depotId, i.function!!.trim(), i.requiredQualification, i.staffingNeed,
            json.writeValueAsString(c.plan.toMap()), "user:" + ctx.actor.subject,
        ).single()
        audit.record(j, entry(ctx, v, AuditActions.ROSTER_SHIFT_CREATED))
        return v
    }

    @AuditRequired
    private fun doUpdate(j: JdbcOperations, ctx: RequestContext, rosterId: UUID, shiftId: UUID, expected: Long, i: RosterShiftInput, c: Checked): RosterShiftView {
        val cur = j.query("SELECT $COLUMNS FROM taktovia.staff_shift s WHERE s.id = ? AND s.roster_id = ? FOR UPDATE", VIEW, shiftId, rosterId).firstOrNull() ?: throw ProblemException.notFound()
        if (cur.revision != expected) throw ProblemException(ErrorCode.REVISION_MISMATCH, "Die Schicht wurde zwischenzeitlich geändert; aktuelle Revision lesen.")
        if (cur.state != "DRAFT") throw ProblemException(ErrorCode.CONFLICT, "Nur Entwürfe sind änderbar; veröffentlichte Schichten ändert der Dienstplan.")
        val v = j.query(
            "UPDATE taktovia.staff_shift s SET driver_ref = ?, vehicle_id = ?, starts_at = ?, ends_at = ?, timezone = ?, operator_id = ?, depot_id = ?, shift_function = ?, required_qualification = ?, " +
                "staffing_need = ?, break_plan = CAST(? AS jsonb) WHERE s.id = ? AND s.shift_revision = ? RETURNING $COLUMNS",
            VIEW, i.driverRef, i.vehicleId, Timestamp.from(c.start), Timestamp.from(c.end), c.zone.id, i.operatorId, i.depotId, i.function!!.trim(), i.requiredQualification, i.staffingNeed,
            json.writeValueAsString(c.plan.toMap()), shiftId, expected,
        ).firstOrNull() ?: throw ProblemException(ErrorCode.REVISION_MISMATCH)
        if (v.revision != cur.revision) audit.record(j, entry(ctx, v, AuditActions.ROSTER_SHIFT_CHANGED))
        return v
    }

    private class Checked(val start: Instant, val end: Instant, val zone: ZoneId, val plan: BreakPlan)

    /** Formale Prüfung (422 mit Feldverweisen, nichts wird gespeichert): Zone, Ortszeiten nach Kap. 05 §5.1, Ende nach Beginn, höchstens 24 h, Pausenplan, Pflichtmerkmale. */
    private fun check(i: RosterShiftInput): Checked {
        val v = mutableListOf<Violation>()
        val zone = IanaZones.check(i.timezone, "/timezone"); v += zone.violations
        fun moment(local: String?, offset: String?, name: String): Instant? {
            val l = LocalDateTimes.parse(local, "/${name}_local"); v += l.violations
            val off: ZoneOffset? = offset?.let { runCatching { ZoneOffset.of(it) }.getOrNull() ?: run { v += Violation("/${name}_offset", ViolationCode.INVALID_FORMAT); null } }
            if (!l.ok || !zone.ok) return null
            val r = if (off != null) ZonedMoments.fromLocal(l.value!!, zone.value!!, off, "/${name}_local") else ZonedMoments.fromLocal(l.value!!, zone.value!!, "/${name}_local")
            v += r.violations
            return r.value?.instant
        }
        val start = moment(i.startsLocal, i.startsOffset, "starts"); val end = moment(i.endsLocal, i.endsOffset, "ends")
        if (start != null && end != null) {
            if (!end.isAfter(start)) v += Violation("/ends_local", ViolationCode.INTERVAL_INVALID)
            else if (Duration.between(start, end) > Duration.ofHours(24)) v += Violation("/ends_local", ViolationCode.INVALID_VALUE, "Eine Schicht dauert höchstens 24 Stunden")
        }
        val minutes = if (start != null && end != null && end.isAfter(start)) Duration.between(start, end).toMinutes() else null
        val plan = BreakPlan.parse(i.breakPlan, minutes); v += plan.second
        if (i.function.isNullOrBlank()) v += Violation("/function", ViolationCode.REQUIRED) else if (i.function.trim().length > 100) v += Violation("/function", ViolationCode.TOO_LONG)
        if (i.requiredQualification != null && !QUALIFICATION.matches(i.requiredQualification)) v += Violation("/required_qualification", ViolationCode.PATTERN_MISMATCH)
        if (i.staffingNeed !in 1..50) v += Violation("/staffing_need", if (i.staffingNeed < 1) ViolationCode.BELOW_MINIMUM else ViolationCode.ABOVE_MAXIMUM)
        if (v.isNotEmpty()) throw ProblemException.invalid(*v.toTypedArray())
        return Checked(start!!, end!!, zone.value!!, plan.first!!)
    }

    /** Fremde oder unbekannte Betriebs-, Depot- oder Fahrzeug-IDs: für den Aufrufer „nicht gefunden“ (der Fremdschlüssel schließt andere Mandanten aus). */
    private fun mapReference(e: DataIntegrityViolationException): ProblemException {
        val msg = generateSequence<Throwable>(e) { it.cause }.mapNotNull { it.message }.joinToString(" ")
        return if (msg.contains("foreign key", ignoreCase = true)) ProblemException.notFound() else throw e
    }

    private fun entry(ctx: RequestContext, v: RosterShiftView, action: String) =
        AuditEntry(ActorRef("user:" + ctx.actor.subject), action, ObjectRef("shift", v.id), ctx.correlationId, mapOf("revision" to v.revision, "state" to v.state, "operating_day" to v.operatingDay.toString()))

    private fun require(ctx: RequestContext, scope: String) { if (!ctx.hasScope(scope)) throw ProblemException(ErrorCode.FORBIDDEN) }

    private companion object {
        const val SCOPE_WRITE = "rosters:write"
        const val SCOPE_READ = "rosters:read"
        val QUALIFICATION = Regex("^[A-Z][A-Z0-9_]{1,39}$")
        const val COLUMNS = "s.id, s.roster_id, s.operating_day, s.timezone, s.starts_at, s.ends_at, s.break_plan::text AS break_plan, s.operator_id, s.depot_id, s.shift_function, s.required_qualification, " +
            "s.staffing_need, s.driver_ref, s.vehicle_id, s.state, s.shift_revision, s.created_by, s.created_at"
        private val mapper = JsonMapper.builder().build()
        val VIEW = RowMapper { rs, _ ->
            val starts = rs.getTimestamp("starts_at").toInstant(); val ends = rs.getTimestamp("ends_at").toInstant()
            val plan = mapper.readTree(rs.getString("break_plan")).get("breaks").values().map { b -> de.taktovia.stafftime.api.ShiftBreak(b.get("offset_min").asInt(), b.get("duration_min").asInt(), b.get("paid").asBoolean()) }
            RosterShiftView(rs.getObject("id", UUID::class.java), rs.getObject("roster_id", UUID::class.java), rs.getDate("operating_day").toLocalDate(), ZoneId.of(rs.getString("timezone")), starts, ends,
                Duration.between(starts, ends).toMinutes(), BreakPlan(plan), rs.getObject("operator_id", UUID::class.java), rs.getObject("depot_id", UUID::class.java), rs.getString("shift_function"),
                rs.getString("required_qualification"), rs.getInt("staffing_need"), rs.getObject("driver_ref", UUID::class.java), rs.getObject("vehicle_id", UUID::class.java), rs.getString("state"),
                rs.getLong("shift_revision"), rs.getString("created_by") ?: "", rs.getTimestamp("created_at").toInstant())
        }
    }
}

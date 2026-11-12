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
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.stafftime.api.ShiftInput
import de.taktovia.stafftime.api.ShiftOverlapException
import de.taktovia.stafftime.api.ShiftState
import de.taktovia.stafftime.api.ShiftView
import de.taktovia.stafftime.api.Shifts
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * Schichtzuweisung der Leitstelle (WP-M3-07). Je Anlage und Änderung eine Transaktion: Fahrer und Fahrzeug des Mandanten prüfen (fremd: 404), Sperre je Fahrer und Fahrzeug
 * (`staff_shift_lock`, serialisiert konkurrierende Zuweisungen), Konfliktprüfung mit der Regel der Datenbank (`staff_shift_clash`), erst dann schreiben. Eine vollständige
 * Ausnahme (Genehmiger, Grund) wird nur gespeichert, wenn tatsächlich eine Überlappung besteht; der Datenbank-Trigger bleibt als letzte Absicherung für jeden anderen Schreibpfad.
 */
internal class JdbcShifts(private val ctxTx: ContextTransactions, private val audit: AuditPort) : Shifts {

    override fun create(ctx: RequestContext, input: ShiftInput): ShiftView {
        guard(ctx, SCOPE_WRITE)
        return ctxTx.run(ctx) { j -> doCreate(j, ctx, input) }
    }

    override fun get(ctx: RequestContext, shiftId: UUID): ShiftView {
        guard(ctx, SCOPE_READ)
        return ctxTx.run(ctx, readOnly = true) { j -> load(j, shiftId) ?: throw ProblemException.notFound() }
    }

    override fun list(ctx: RequestContext, driverId: UUID?, vehicleId: UUID?, from: Instant?, to: Instant?, limit: Int, after: Pair<Instant, UUID>?): List<ShiftView> {
        guard(ctx, SCOPE_READ)
        return ctxTx.run(ctx, readOnly = true) { j ->
            val f = from?.let(Timestamp::from); val t = to?.let(Timestamp::from); val a = after?.first?.let(Timestamp::from)
            // feste Anweisung mit optionalen Filtern (NULL = kein Filter); alle Werte gebunden; Fenster überlappt halboffen [from, to)
            j.query(
                "SELECT $COLUMNS FROM taktovia.staff_shift s WHERE (CAST(? AS uuid) IS NULL OR s.driver_ref = CAST(? AS uuid)) AND (CAST(? AS uuid) IS NULL OR s.vehicle_id = CAST(? AS uuid)) " +
                    "AND (CAST(? AS timestamptz) IS NULL OR s.ends_at > CAST(? AS timestamptz)) AND (CAST(? AS timestamptz) IS NULL OR s.starts_at < CAST(? AS timestamptz)) " +
                    "AND (CAST(? AS timestamptz) IS NULL OR (s.starts_at, s.id) > (CAST(? AS timestamptz), CAST(? AS uuid))) ORDER BY s.starts_at, s.id LIMIT ?",
                MAPPER, driverId, driverId, vehicleId, vehicleId, f, f, t, t, a, a, after?.second, limit + 1,
            )
        }
    }

    override fun update(ctx: RequestContext, shiftId: UUID, expectedRevision: Long, input: ShiftInput): ShiftView {
        guard(ctx, SCOPE_WRITE)
        return ctxTx.run(ctx) { j -> doUpdate(j, ctx, shiftId, expectedRevision, input) }
    }

    @AuditRequired
    private fun doCreate(j: JdbcOperations, ctx: RequestContext, input: ShiftInput): ShiftView {
        references(j, input)
        val exception = decideException(j, input, self = null)
        val id = UUID.randomUUID()
        val view = try {
            j.query(
                "INSERT INTO taktovia.staff_shift AS s (tenant_id, id, driver_ref, vehicle_id, starts_at, ends_at, timezone, roster_revision, state, overlap_exception_by, overlap_exception_reason, overlap_exception_at) " +
                    "VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?, 0, 'PLANNED', ?, ?, ?) RETURNING $COLUMNS",
                MAPPER, id, input.driverId, input.vehicleId, Timestamp.from(input.startsAt), Timestamp.from(input.endsAt), input.timezone, exception?.first, exception?.second, exception?.third?.let(Timestamp::from),
            ).single()
        } catch (e: DataAccessException) { throw translate(e) }
        audit.record(j, entry(ctx, AuditActions.SHIFT_CREATED, view, null))
        return view
    }

    @AuditRequired
    private fun doUpdate(j: JdbcOperations, ctx: RequestContext, shiftId: UUID, expected: Long, input: ShiftInput): ShiftView {
        val before = j.query("SELECT $COLUMNS FROM taktovia.staff_shift s WHERE s.id = ? FOR UPDATE", MAPPER, shiftId).firstOrNull() ?: throw ProblemException.notFound()
        if (ShiftState.entries.none { it.name == before.state }) throw ProblemException(ErrorCode.INVALID_STATE_TRANSITION, "Die Schicht gehört dem Dienstplan und ist hier nicht änderbar.")
        if (before.revision != expected) throw ProblemException(ErrorCode.REVISION_MISMATCH, "Die Schichtzuweisung wurde zwischenzeitlich geändert; aktuelle Revision lesen.")
        val target = input.state ?: ShiftState.valueOf(before.state)
        // Fahrer, Zeiten und Fahrzeug einer laufenden oder beendeten Schicht sind festgeschrieben; nur der Entzug (CANCELLED) ist zulässig.
        if (before.state == "STARTED" || before.state == "ENDED") {
            val unchanged = input.driverId == before.driverId && input.vehicleId == before.vehicleId && input.startsAt == before.startsAt && input.endsAt == before.endsAt
            if (before.state == "ENDED" || !(unchanged || target == ShiftState.CANCELLED)) throw ProblemException(ErrorCode.INVALID_STATE_TRANSITION, "Die Schicht ist angetreten oder beendet und nur noch zu entziehen.")
        }
        if (before.state == "CANCELLED" && target == ShiftState.CANCELLED && !unchanged(before, input)) throw ProblemException(ErrorCode.INVALID_STATE_TRANSITION, "Die Schicht ist entzogen.")
        references(j, input)
        val exception = if (target == ShiftState.CANCELLED) null else decideException(j, input, self = shiftId, inherited = before.overlapException)
        val view = try {
            // Ausnahme nur beibehalten, wenn sie für diese Belegung gebraucht wird; sonst entfällt sie mit der Änderung.
            j.query(
                "UPDATE taktovia.staff_shift AS s SET driver_ref = ?, vehicle_id = ?, starts_at = ?, ends_at = ?, timezone = ?, state = ?, overlap_exception_by = ?, overlap_exception_reason = ?, overlap_exception_at = ? " +
                    "WHERE s.id = ? AND s.revision = ? RETURNING $COLUMNS",
                MAPPER, input.driverId, input.vehicleId, Timestamp.from(input.startsAt), Timestamp.from(input.endsAt), input.timezone, target.name,
                exception?.first ?: if (target == ShiftState.CANCELLED) before.overlapException?.approvedBy else null,
                exception?.second ?: if (target == ShiftState.CANCELLED) before.overlapException?.reason else null,
                (exception?.third ?: if (target == ShiftState.CANCELLED) before.overlapException?.approvedAt else null)?.let(Timestamp::from),
                shiftId, expected,
            ).singleOrNull() ?: throw ProblemException(ErrorCode.REVISION_MISMATCH, "Die Schichtzuweisung wurde zwischenzeitlich geändert; aktuelle Revision lesen.")
        } catch (e: DataAccessException) { throw translate(e) }
        audit.record(j, entry(ctx, AuditActions.SHIFT_CHANGED, view, before))
        return view
    }

    private fun unchanged(b: ShiftView, i: ShiftInput) = i.driverId == b.driverId && i.vehicleId == b.vehicleId && i.startsAt == b.startsAt && i.endsAt == b.endsAt

    /** Fahrer und Fahrzeug müssen im Mandanten existieren (fremd oder unbekannt: 404) und aktiv sein (422). */
    private fun references(j: JdbcOperations, input: ShiftInput) {
        val driver = j.query("SELECT status FROM taktovia.driver WHERE id = ?", { rs, _ -> rs.getString(1) }, input.driverId).firstOrNull() ?: throw ProblemException.notFound()
        val vehicle = j.query("SELECT active FROM taktovia.vehicle WHERE id = ?", { rs, _ -> rs.getBoolean(1) }, input.vehicleId).firstOrNull() ?: throw ProblemException.notFound()
        val v = buildList {
            if (driver != "ACTIVE") add(Violation("/driver_id", ViolationCode.INVALID_VALUE))
            if (!vehicle) add(Violation("/vehicle_id", ViolationCode.INVALID_VALUE))
        }
        if (v.isNotEmpty()) throw ProblemException.invalid(*v.toTypedArray())
    }

    /**
     * Sperrt Fahrer und Fahrzeug und prüft die Überlappung. Ohne Überlappung: keine Ausnahme speichern (eine unvollständige Ausnahme ohne Überlappung ist ein Feldfehler).
     * Mit Überlappung: vollständige Ausnahme -> (Genehmiger, Grund, Zeitpunkt), sonst 409 mit Ursache und kollidierender Schicht. [self] ist die geänderte Schicht (kollidiert nie mit sich selbst).
     */
    private fun decideException(j: JdbcOperations, input: ShiftInput, self: UUID?, inherited: ShiftOverlapException? = null): Triple<String, String, Instant>? {
        j.queryForObject("SELECT taktovia.staff_shift_lock(taktovia.current_tenant(), ?, ?)::text", String::class.java, input.driverId, input.vehicleId)
        val clash = j.query("SELECT cause, shift_id FROM taktovia.staff_shift_clash(taktovia.current_tenant(), ?, ?, ?, ?, ?)", { rs, _ -> rs.getString(1) to rs.getObject(2, UUID::class.java) },
            self ?: UUID(0, 0), input.driverId, input.vehicleId, Timestamp.from(input.startsAt), Timestamp.from(input.endsAt))
        val ex = input.overlapException
        if (clash.isEmpty()) {
            if (ex != null && !ex.complete) throw ProblemException.invalid(
                *buildList {
                    if (ex.approvedBy.isNullOrBlank()) add(Violation("/overlap_exception/approved_by", ViolationCode.REQUIRED))
                    if (ex.reason.isNullOrBlank() || ex.reason.trim().length < 3) add(Violation("/overlap_exception/reason", if (ex.reason.isNullOrBlank()) ViolationCode.REQUIRED else ViolationCode.TOO_SHORT))
                }.toTypedArray())
            return null
        }
        if (ex != null && ex.complete) return Triple(ex.approvedBy!!.trim(), ex.reason!!.trim(), Instant.now())
        // eine schon dokumentierte Ausnahme der Schicht gilt weiter, solange die Änderung sie nicht ersetzt (z. B. nur die Zeitzone ändert sich)
        if (ex == null && inherited != null) return Triple(inherited.approvedBy, inherited.reason, inherited.approvedAt)
        throw conflict(clash.first().first, clash.first().second)
    }

    private fun conflict(cause: String, other: UUID): ProblemException {
        val pointer = if (cause == "DRIVER") "/driver_id" else "/vehicle_id"
        val what = if (cause == "DRIVER") "des Fahrers" else "des Fahrzeugs"
        return ProblemException(ErrorCode.CONFLICT, "Die Schicht überlappt die Schicht $other $what; nur eine dokumentierte Ausnahme (Genehmiger und Grund) erlaubt die Doppelbelegung.",
            listOf(Violation(pointer, ViolationCode.NOT_UNIQUE)))
    }

    /** Letzte Absicherung: ein paralleler Schreibpfad, der die Prüfung unterlief, scheitert im Trigger (SQLSTATE 23P01) und wird zum selben Konflikt. */
    private fun translate(e: DataAccessException): RuntimeException {
        val ex = generateSequence<Throwable>(e) { it.cause }.filterIsInstance<SQLException>().firstOrNull()
        if (ex?.sqlState == "23P01") {
            val m = Regex("(DRIVER|VEHICLE):([0-9a-fA-F-]{36})").find(ex.message ?: "")
            if (m != null) return conflict(m.groupValues[1], UUID.fromString(m.groupValues[2]))
            return ProblemException(ErrorCode.CONFLICT, "Die Schicht überlappt eine andere Schicht.")
        }
        return e
    }

    private fun load(j: JdbcOperations, id: UUID): ShiftView? = j.query("SELECT $COLUMNS FROM taktovia.staff_shift s WHERE s.id = ?", MAPPER, id).firstOrNull()

    private fun entry(ctx: RequestContext, action: String, v: ShiftView, before: ShiftView?) = AuditEntry(
        ActorRef((if (ctx.actor.type == ActorType.SERVICE) "service:" else "user:") + ctx.actor.subject), action, ObjectRef("staff_shift", v.id), ctx.correlationId,
        buildMap {
            put("driver_id", v.driverId.toString()); put("vehicle_id", v.vehicleId.toString()); put("starts_at", v.startsAt.toString()); put("ends_at", v.endsAt.toString())
            put("state", v.state); put("revision", v.revision)
            if (before != null) { put("previous_state", before.state); put("previous_revision", before.revision) }
            put("overlap_exception", v.overlapException != null)
            v.overlapException?.let { put("exception_approved_by", it.approvedBy) }
        },
    )

    private fun guard(ctx: RequestContext, scope: String) {
        if (ctx.actor.type != ActorType.HUMAN || !ctx.hasScope(scope)) throw ProblemException(ErrorCode.FORBIDDEN)
    }

    companion object {
        const val SCOPE_WRITE = "shifts:write"
        const val SCOPE_READ = "shifts:read"
        const val COLUMNS = "s.id, s.driver_ref, s.vehicle_id, s.starts_at, s.ends_at, s.timezone, s.state, s.revision, s.roster_revision, s.overlap_exception_by, s.overlap_exception_reason, " +
            "s.overlap_exception_at, s.started_at, s.ended_at"
        val MAPPER = RowMapper { rs, _ ->
            ShiftView(
                rs.getObject("id", UUID::class.java), rs.getObject("driver_ref", UUID::class.java), rs.getObject("vehicle_id", UUID::class.java), rs.getTimestamp("starts_at").toInstant(),
                rs.getTimestamp("ends_at").toInstant(), rs.getString("timezone"), rs.getString("state"), rs.getLong("revision"), rs.getLong("roster_revision"),
                rs.getString("overlap_exception_by")?.let { ShiftOverlapException(it, rs.getString("overlap_exception_reason"), rs.getTimestamp("overlap_exception_at").toInstant()) },
                rs.getTimestamp("started_at")?.toInstant(), rs.getTimestamp("ended_at")?.toInstant(),
            )
        }
    }
}

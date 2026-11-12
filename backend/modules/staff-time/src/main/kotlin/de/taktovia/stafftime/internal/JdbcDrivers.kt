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
import de.taktovia.stafftime.api.ContactChannel
import de.taktovia.stafftime.api.DriverCreate
import de.taktovia.stafftime.api.DriverHrFields
import de.taktovia.stafftime.api.DriverStatus
import de.taktovia.stafftime.api.DriverUpdate
import de.taktovia.stafftime.api.DriverView
import de.taktovia.stafftime.api.Drivers
import de.taktovia.stafftime.api.HR_ROLE
import de.taktovia.stafftime.api.QualificationInput
import de.taktovia.stafftime.api.QualificationStatus
import de.taktovia.stafftime.api.QualificationView
import de.taktovia.stafftime.api.ValidQualification
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * Fahrerstamm und Qualifikationsrevisionen (WP-M3-05). Mandantentrennung: RLS und zusammengesetzte Schlüssel (Mitgliedschaft des eigenen Mandanten). Jede Änderung sperrt die
 * Fahrerzeile (`FOR NO KEY UPDATE`) und vergleicht die erwartete Revision unter der Sperre; die Freigabe an Partner (`driver_release`, `FOR SHARE`) und die Einsatzfreigabe
 * (WP-M3-08) serialisieren sich über dieselbe Zeile. HR-nahe Felder gibt nur die Rolle [HR_ROLE] heraus.
 */
internal class JdbcDrivers(private val ctxTx: ContextTransactions, private val audit: AuditPort) : Drivers {

    override fun create(ctx: RequestContext, input: DriverCreate): DriverView {
        try {
            return ctxTx.run(ctx) { j -> doCreate(j, actor(ctx), ctx.correlationId, hr(ctx), input) }
        } catch (e: DataIntegrityViolationException) {
            if (sqlState(e) == "23505") throw ProblemException(ErrorCode.CONFLICT, "Die Mitgliedschaft ist bereits einem Fahrer zugeordnet.", listOf(Violation("/membership_id", ViolationCode.NOT_UNIQUE)), cause = e)
            throw e
        }
    }

    override fun get(ctx: RequestContext, driverId: UUID): DriverView = ctxTx.run(ctx, readOnly = true) { j -> load(j, driverId, false, hr(ctx)) ?: throw ProblemException.notFound() }

    override fun list(ctx: RequestContext, status: DriverStatus?, limit: Int, after: Pair<Instant, UUID>?): List<DriverView> = ctxTx.run(ctx, readOnly = true) { j ->
        val afterTs = after?.first?.let(Timestamp::from)
        j.query(
            "SELECT $COLUMNS FROM taktovia.driver d WHERE (CAST(? AS text) IS NULL OR d.status = CAST(? AS text)) " +
                "AND (CAST(? AS timestamptz) IS NULL OR (d.created_at, d.id) > (CAST(? AS timestamptz), CAST(? AS uuid))) ORDER BY d.created_at, d.id LIMIT ?",
            mapper(hr(ctx)), status?.name, status?.name, afterTs, afterTs, after?.second, limit + 1,
        )
    }

    override fun update(ctx: RequestContext, driverId: UUID, expectedRevision: Long, input: DriverUpdate): DriverView =
        ctxTx.run(ctx) { j -> doUpdate(j, actor(ctx), ctx.correlationId, hr(ctx), driverId, expectedRevision, input) }

    override fun qualifications(ctx: RequestContext, driverId: UUID, at: Instant?, onlyValid: Boolean): List<QualificationView> = ctxTx.run(ctx, readOnly = true) { j ->
        if (j.queryForObject("SELECT count(*) FROM taktovia.driver WHERE id = ?", Int::class.java, driverId) != 1) throw ProblemException.notFound()
        val ts = Timestamp.from(at ?: Instant.now()); val hr = hr(ctx)
        j.query(QUALIFICATIONS, { rs, _ -> qualification(rs, hr) }, ts, ts, driverId).filter { !onlyValid || it.validAt }
    }

    override fun addQualification(ctx: RequestContext, driverId: UUID, expectedRevision: Long, input: QualificationInput): Pair<QualificationView, DriverView> =
        ctxTx.run(ctx) { j -> doAddQualification(j, actor(ctx), ctx.correlationId, hr(ctx), driverId, expectedRevision, input) }

    override fun validQualifications(jdbc: JdbcOperations, driverId: UUID, at: Instant): List<ValidQualification> {
        val ts = Timestamp.from(at)
        return jdbc.query(QUALIFICATIONS, { rs, _ -> qualification(rs, false) }, ts, ts, driverId).filter { it.validAt }.map { ValidQualification(it.code, it.revision, it.validFrom, it.validTo) }
    }

    override fun qualificationRevision(jdbc: JdbcOperations, driverId: UUID): Long =
        jdbc.query("SELECT qualification_seq FROM taktovia.driver WHERE id = ?", { rs, _ -> rs.getLong(1) }, driverId).firstOrNull() ?: 0L

    @AuditRequired
    private fun doCreate(j: JdbcOperations, actor: ActorRef, correlationId: UUID, hr: Boolean, input: DriverCreate): DriverView {
        // Mitgliedschaft des eigenen Mandanten (RLS), aktiv und mit der Rolle driver; eine fremde ist nicht auffindbar (Feldfehler, keine Existenzauskunft)
        val m = j.query("SELECT role_code, revoked_at IS NOT NULL AS revoked FROM taktovia.membership WHERE id = ? FOR SHARE", { rs, _ -> rs.getString(1) to rs.getBoolean(2) }, input.membershipId).firstOrNull()
        if (m == null || m.second || m.first != "driver") throw ProblemException.invalid(Violation("/membership_id", ViolationCode.INVALID_VALUE))
        val id = UUID.randomUUID()
        val view = j.query(
            "INSERT INTO taktovia.driver AS d (tenant_id, id, membership_id, display_name, contact_channel, contact_value, status, created_by) VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?, ?) RETURNING $COLUMNS",
            mapper(hr), id, input.membershipId, input.displayName, input.contactChannel.name, input.contactValue, input.status.name, actor.value,
        ).single()
        audit.record(j, AuditEntry(actor, AuditActions.DRIVER_CREATED, ObjectRef("driver", id), correlationId, mapOf("membership_id" to input.membershipId.toString(), "status" to input.status.name, "revision" to view.revision)))
        return view
    }

    @AuditRequired
    private fun doUpdate(j: JdbcOperations, actor: ActorRef, correlationId: UUID, hr: Boolean, id: UUID, expected: Long, input: DriverUpdate): DriverView {
        val before = rawDriver(j, id) ?: throw ProblemException.notFound()
        if (before.revision != expected) throw ProblemException(ErrorCode.REVISION_MISMATCH, "Der Fahrer wurde zwischenzeitlich geändert; aktuelle Revision lesen.")
        val view = j.query(
            "UPDATE taktovia.driver AS d SET display_name = ?, contact_channel = ?, contact_value = ?, status = ?, revision = revision + 1, updated_at = clock_timestamp() " +
                "WHERE d.id = ? AND d.revision = ? RETURNING $COLUMNS",
            mapper(hr), input.displayName, input.contactChannel.name, input.contactValue, input.status.name, id, expected,
        ).singleOrNull() ?: throw ProblemException(ErrorCode.REVISION_MISMATCH, "Der Fahrer wurde zwischenzeitlich geändert; aktuelle Revision lesen.")
        // Auditdetails ohne Klarnamen und Kontaktdaten: nur welche Felder sich geändert haben
        val changed = listOfNotNull(if (before.name != input.displayName) "display_name" else null, if (before.channel != input.contactChannel.name || before.value != input.contactValue) "contact" else null,
            if (before.status != input.status.name) "status" else null)
        audit.record(j, AuditEntry(actor, AuditActions.DRIVER_CHANGED, ObjectRef("driver", id), correlationId, mapOf("changed_fields" to changed, "status" to input.status.name, "previous_revision" to expected, "revision" to view.revision)))
        return view
    }

    @AuditRequired
    private fun doAddQualification(j: JdbcOperations, actor: ActorRef, correlationId: UUID, hr: Boolean, id: UUID, expected: Long, input: QualificationInput): Pair<QualificationView, DriverView> {
        val before = rawDriver(j, id) ?: throw ProblemException.notFound()   // gesperrt: gleichzeitige Änderungen werden nacheinander entschieden
        if (before.revision != expected) throw ProblemException(ErrorCode.REVISION_MISMATCH, "Der Fahrer wurde zwischenzeitlich geändert; aktuelle Revision lesen.")
        val revision = j.queryForObject("SELECT coalesce(max(revision), 0) + 1 FROM taktovia.qualification_revision WHERE driver_id = ? AND qualification_code = ?", Long::class.java, id, input.code)!!
        val effectiveChange = input.status != QualificationStatus.PENDING
        val seq: Long? = if (effectiveChange) before.seq + 1 else null
        val qid = UUID.randomUUID()
        val decided = effectiveChange
        j.update(
            "INSERT INTO taktovia.qualification_revision (tenant_id, id, driver_id, qualification_code, revision, valid_from, valid_to, evidence_ref, approval_status, approved_by, approved_at, driver_seq, created_by) " +
                "VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?, ?, ?, ?, CASE WHEN ? THEN clock_timestamp() END, ?, ?)",
            qid, id, input.code, revision, Timestamp.from(input.validFrom), input.validTo?.let(Timestamp::from), input.evidenceRef, input.status.name, if (decided) actor.value else null, decided, seq, actor.value,
        )
        val driver = j.query(
            "UPDATE taktovia.driver AS d SET qualification_seq = ?, revision = revision + 1, updated_at = clock_timestamp() WHERE d.id = ? AND d.revision = ? RETURNING $COLUMNS",
            mapper(hr), seq ?: before.seq, id, expected,
        ).singleOrNull() ?: throw ProblemException(ErrorCode.REVISION_MISMATCH, "Der Fahrer wurde zwischenzeitlich geändert; aktuelle Revision lesen.")
        audit.record(j, AuditEntry(actor, AuditActions.QUALIFICATION_CHANGED, ObjectRef("driver", id), correlationId,
            mapOf("qualification_code" to input.code, "status" to input.status.name, "qualification_revision" to revision, "driver_seq" to seq, "valid_from" to input.validFrom.toString(), "valid_to" to input.validTo?.toString(), "revision" to driver.revision)))
        val ts = Timestamp.from(Instant.now())
        val created = j.query(QUALIFICATIONS, { rs, _ -> qualification(rs, hr) }, ts, ts, id).single { it.id == qid }
        return created to driver
    }

    private class Raw(val revision: Long, val seq: Long, val name: String, val channel: String, val value: String, val status: String)

    private fun rawDriver(j: JdbcOperations, id: UUID): Raw? = j.query(
        "SELECT revision, qualification_seq, display_name, contact_channel, contact_value, status FROM taktovia.driver WHERE id = ? FOR NO KEY UPDATE",
        { rs, _ -> Raw(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)) }, id,
    ).firstOrNull()

    private fun load(j: JdbcOperations, id: UUID, @Suppress("UNUSED_PARAMETER") lock: Boolean, hr: Boolean): DriverView? = j.query("SELECT $COLUMNS FROM taktovia.driver d WHERE d.id = ?", mapper(hr), id).firstOrNull()

    private fun hr(ctx: RequestContext) = HR_ROLE in ctx.roles

    private fun sqlState(e: Throwable): String? = generateSequence(e) { it.cause }.filterIsInstance<SQLException>().firstOrNull()?.sqlState

    private fun actor(ctx: RequestContext) = ActorRef((if (ctx.actor.type == ActorType.SERVICE) "service:" else "user:") + ctx.actor.subject)

    private fun mapper(hr: Boolean) = RowMapper { rs, _ ->
        DriverView(
            rs.getObject("id", UUID::class.java), rs.getObject("membership_id", UUID::class.java), DriverStatus.valueOf(rs.getString("status")), rs.getLong("qualification_seq"), rs.getLong("revision"),
            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
            if (hr) DriverHrFields(rs.getString("display_name"), ContactChannel.valueOf(rs.getString("contact_channel")), rs.getString("contact_value")) else null,
        )
    }

    private fun qualification(rs: java.sql.ResultSet, hr: Boolean) = QualificationView(
        rs.getObject("id", UUID::class.java), rs.getString("qualification_code"), rs.getLong("revision"), QualificationStatus.valueOf(rs.getString("approval_status")),
        rs.getTimestamp("valid_from").toInstant(), rs.getTimestamp("valid_to")?.toInstant(), rs.getString("approved_by"), rs.getTimestamp("approved_at")?.toInstant(),
        rs.getObject("driver_seq") as Long?, if (hr) rs.getString("evidence_ref") else null, rs.getTimestamp("created_at").toInstant(), rs.getBoolean("effective"), rs.getBoolean("valid_at"),
    )

    private companion object {
        const val COLUMNS = "d.id, d.membership_id, d.display_name, d.contact_channel, d.contact_value, d.status, d.qualification_seq, d.revision, d.created_at, d.updated_at"

        /** Wirksam ist je Qualifikation die höchste nicht wartende Revision; sie gilt, wenn sie freigegeben ist und `at` im Intervall [valid_from, valid_to) liegt. Parameter: at, at, driver. */
        const val QUALIFICATIONS =
            "SELECT q.id, q.qualification_code, q.revision, q.approval_status, q.valid_from, q.valid_to, q.approved_by, q.approved_at, q.driver_seq, q.evidence_ref, q.created_at, " +
                "e.is_effective AS effective, (e.is_effective AND q.approval_status = 'APPROVED' AND q.valid_from <= ? AND (q.valid_to IS NULL OR q.valid_to > ?)) AS valid_at " +
                "FROM taktovia.qualification_revision q CROSS JOIN LATERAL (SELECT q.approval_status <> 'PENDING' AND q.revision = (SELECT max(x.revision) FROM taktovia.qualification_revision x " +
                "WHERE x.tenant_id = q.tenant_id AND x.driver_id = q.driver_id AND x.qualification_code = q.qualification_code AND x.approval_status <> 'PENDING') AS is_effective) e " +
                "WHERE q.driver_id = ? ORDER BY q.qualification_code, q.revision DESC"
    }
}

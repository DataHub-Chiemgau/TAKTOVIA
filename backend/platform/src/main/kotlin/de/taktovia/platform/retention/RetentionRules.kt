package de.taktovia.platform.retention

import de.taktovia.platform.audit.ActorRef
import de.taktovia.platform.audit.AuditActions
import de.taktovia.platform.audit.AuditEntry
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.audit.AuditRequired
import de.taktovia.platform.audit.ObjectRef
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.operation.OperationService
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Datenklassen = Löschklassen des Dateninventars (`db/dateninventar_katalog.json`, ein Test hält beide synchron). */
object RetentionDataClasses {
    val ALL = setOf("LK_STAMM", "LK_BETRIEB", "LK_BELEG", "LK_PII", "LK_STANDORT", "LK_AUDIT", "LK_TECHNISCH", "LK_KATALOG")
}

/**
 * Verwaltung der Aufbewahrungsregeln und Start der Löschläufe (WP-M1-33, LH-6.18-SEC-007). Eine Regel entsteht als Entwurf, ändert sich nur mit
 * Revision (If-Match) und gilt erst nach Freigabe durch eine **andere** Person (Vier-Augen, zusätzlich als CHECK in der Datenbank). Eine Änderung
 * einer freigegebenen Regel setzt sie auf den Entwurf zurück. Jede Änderung und jeder Lauf ist auditiert (Änderungen ohne Personendaten).
 * Fristen sind nicht festgelegt (D-11): die Werte stammen immer vom Aufrufer; Testfristen gelten nur für Tests.
 */
class RetentionRules(
    private val ctxTx: ContextTransactions, private val audit: AuditPort, private val operations: OperationService = OperationService(),
    private val clock: Clock = Clock.systemUTC(),
) {
    private val ruleMapper = RowMapper { rs, _ ->
        RetentionRuleView(
            rs.getObject("id", UUID::class.java), rs.getString("data_class"), rs.getInt("retention_days"), RetentionAction.valueOf(rs.getString("action")),
            rs.getObject("archive_days") as Int?, RuleStatus.valueOf(rs.getString("status")), rs.getLong("revision"), rs.getTimestamp("created_at").toInstant(),
        )
    }
    private val jobMapper = RowMapper { rs, _ -> ErasureJobs.map(rs) }

    fun list(ctx: RequestContext, limit: Int, after: Pair<Instant, UUID>?): List<RetentionRuleView> = ctxTx.run(ctx, readOnly = true) { j ->
        val afterTs = after?.first?.let(Timestamp::from)
        j.query("SELECT $RULE_COLUMNS FROM taktovia.retention_rule WHERE (CAST(? AS timestamptz) IS NULL OR (created_at, id) > (CAST(? AS timestamptz), CAST(? AS uuid))) " +
            "ORDER BY created_at, id LIMIT ?", ruleMapper, afterTs, afterTs, after?.second, limit + 1)
    }

    fun create(ctx: RequestContext, dataClass: String, retentionDays: Int, action: RetentionAction, archiveDays: Int?): RetentionRuleView {
        validate(dataClass, retentionDays, action, archiveDays)
        return ctxTx.run(ctx) { j -> doCreate(j, actor(ctx), ctx, dataClass, retentionDays, action, archiveDays) }
    }

    @AuditRequired
    private fun doCreate(j: JdbcOperations, actor: ActorRef, ctx: RequestContext, dataClass: String, days: Int, action: RetentionAction, archiveDays: Int?): RetentionRuleView {
        val id = UUID.randomUUID()
        j.update("INSERT INTO taktovia.retention_rule (tenant_id, id, data_class, retention_days, action, archive_days, changed_by) VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?)",
            id, dataClass, days, action.name, archiveDays, actor.value)
        audit.record(j, AuditEntry(actor, AuditActions.RETENTION_RULE_CHANGED, ObjectRef("retention_rule", id), ctx.correlationId,
            mapOf("data_class" to dataClass, "retention_days" to days, "action" to action.name, "archive_days" to archiveDays, "status" to RuleStatus.DRAFT.name, "revision" to 1)))
        return load(j, id)!!
    }

    /** Ändert Frist und Aktion; die Regel ist danach ein Entwurf (neue Freigabe nötig). Veraltete Revision: 412. Ohne Änderung: unverändert zurück. */
    fun update(ctx: RequestContext, ruleId: UUID, expectedRevision: Long, retentionDays: Int, action: RetentionAction, archiveDays: Int?): RetentionRuleView {
        ctxTx.run(ctx, readOnly = true) { j -> load(j, ruleId) ?: throw ProblemException.notFound() }
        validate(null, retentionDays, action, archiveDays)
        return ctxTx.run(ctx) { j -> doUpdate(j, actor(ctx), ctx, ruleId, expectedRevision, retentionDays, action, archiveDays) }
    }

    @AuditRequired
    private fun doUpdate(j: JdbcOperations, actor: ActorRef, ctx: RequestContext, id: UUID, expected: Long, days: Int, action: RetentionAction, archiveDays: Int?): RetentionRuleView {
        val cur = lock(j, id) ?: throw ProblemException.notFound()
        if (cur.revision != expected) throw stale()
        if (cur.retentionDays == days && cur.action == action && cur.archiveDays == archiveDays) return cur
        j.update("UPDATE taktovia.retention_rule SET retention_days = ?, action = ?, archive_days = ?, status = 'DRAFT', approved_by = NULL, approved_at = NULL, changed_by = ? WHERE id = ?",
            days, action.name, archiveDays, actor.value, id)
        val now = load(j, id)!!
        audit.record(j, AuditEntry(actor, AuditActions.RETENTION_RULE_CHANGED, ObjectRef("retention_rule", id), ctx.correlationId,
            mapOf("data_class" to now.dataClass, "retention_days" to days, "action" to action.name, "archive_days" to archiveDays, "status" to now.status.name, "revision" to now.revision)))
        return now
    }

    /** Gibt den Entwurf frei. Wer ihn zuletzt geändert hat, darf ihn nicht freigeben (403); je Datenklasse gilt höchstens eine freigegebene Regel (409). */
    fun approve(ctx: RequestContext, ruleId: UUID, expectedRevision: Long): RetentionRuleView {
        ctxTx.run(ctx, readOnly = true) { j -> load(j, ruleId) ?: throw ProblemException.notFound() }
        try {
            return ctxTx.run(ctx) { j -> doApprove(j, actor(ctx), ctx, ruleId, expectedRevision) }
        } catch (e: DuplicateKeyException) {
            throw ProblemException(ErrorCode.CONFLICT, "Für diese Datenklasse gilt bereits eine freigegebene Regel.", cause = e)
        }
    }

    @AuditRequired
    private fun doApprove(j: JdbcOperations, actor: ActorRef, ctx: RequestContext, id: UUID, expected: Long): RetentionRuleView {
        val cur = lock(j, id) ?: throw ProblemException.notFound()
        if (cur.revision != expected) throw stale()
        if (cur.status == RuleStatus.APPROVED) return cur
        val changedBy = j.queryForObject("SELECT changed_by FROM taktovia.retention_rule WHERE id = ?", String::class.java, id)
        if (changedBy == actor.value) throw ProblemException(ErrorCode.FORBIDDEN, "Die Freigabe braucht eine andere Person als die letzte Änderung (Vier-Augen).")
        j.update("UPDATE taktovia.retention_rule SET status = 'APPROVED', approved_by = ?, approved_at = clock_timestamp() WHERE id = ?", actor.value, id)
        val now = load(j, id)!!
        audit.record(j, AuditEntry(actor, AuditActions.RETENTION_RULE_APPROVED, ObjectRef("retention_rule", id), ctx.correlationId,
            mapOf("data_class" to now.dataClass, "retention_days" to now.retentionDays, "action" to now.action.name, "revision" to now.revision)))
        return now
    }

    /**
     * Legt einen Löschlauf für die **freigegebene** Regel an (nicht freigegeben: 409) und stellt die `operation` (`RETENTION_RUN`) in dieselbe Transaktion;
     * die Ausführung übernimmt der Operation-Runner mit [ErasureOperationHandler]. Die Regelwerte werden im Job festgehalten.
     */
    fun requestRun(ctx: RequestContext, ruleId: UUID): ErasureJobView = ctxTx.run(ctx) { j -> doRequestRun(j, actor(ctx), ctx, ruleId) }

    @AuditRequired
    private fun doRequestRun(j: JdbcOperations, actor: ActorRef, ctx: RequestContext, ruleId: UUID): ErasureJobView {
        val rule = lock(j, ruleId) ?: throw ProblemException.notFound()
        if (rule.status != RuleStatus.APPROVED) throw ProblemException(ErrorCode.CONFLICT, "Die Regel ist nicht freigegeben; ein Löschlauf wendet sie nicht an.")
        val jobId = UUID.randomUUID()
        val op = operations.enqueue(j, "RETENTION_RUN", clock.instant().plus(Duration.ofDays(1)))
        j.update("INSERT INTO taktovia.erasure_job (tenant_id, id, rule_id, rule_revision, data_class, action, retention_days, archive_days, operation_id, created_by) " +
            "VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?, ?, ?, ?)", jobId, ruleId, rule.revision, rule.dataClass, rule.action.name, rule.retentionDays, rule.archiveDays, op, actor.value)
        audit.record(j, AuditEntry(actor, AuditActions.RETENTION_ERASURE_STARTED, ObjectRef("erasure_job", jobId), ctx.correlationId,
            mapOf("rule_id" to ruleId.toString(), "data_class" to rule.dataClass, "action" to rule.action.name)))
        return ErasureJobs.find(j, jobId)!!
    }

    fun jobs(ctx: RequestContext, limit: Int, after: Pair<Instant, UUID>?): List<ErasureJobView> = ctxTx.run(ctx, readOnly = true) { j ->
        val afterTs = after?.first?.let(Timestamp::from)
        j.query("SELECT $JOB_COLUMNS FROM taktovia.erasure_job WHERE (CAST(? AS timestamptz) IS NULL OR (created_at, id) > (CAST(? AS timestamptz), CAST(? AS uuid))) ORDER BY created_at, id LIMIT ?",
            jobMapper, afterTs, afterTs, after?.second, limit + 1)
    }

    /** Löschprotokoll des Mandanten (optional eines Laufs), älteste zuerst; ohne Personendaten. */
    fun journal(ctx: RequestContext, jobId: UUID?, limit: Int, after: Pair<Instant, UUID>?): List<JournalEntryView> = ctxTx.run(ctx, readOnly = true) { j ->
        val afterTs = after?.first?.let(Timestamp::from)
        j.query("SELECT id, job_id, data_class, object_ref, action, applied_at FROM taktovia.erasure_journal WHERE (CAST(? AS uuid) IS NULL OR job_id = CAST(? AS uuid)) " +
            "AND (CAST(? AS timestamptz) IS NULL OR (applied_at, id) > (CAST(? AS timestamptz), CAST(? AS uuid))) ORDER BY applied_at, id LIMIT ?",
            { rs, _ -> JournalEntryView(rs.getObject("id", UUID::class.java), rs.getObject("job_id", UUID::class.java), rs.getString("data_class"), rs.getString("object_ref"),
                RetentionAction.valueOf(rs.getString("action")), rs.getTimestamp("applied_at").toInstant()) },
            jobId, jobId, afterTs, afterTs, after?.second, limit + 1)
    }

    private fun validate(dataClass: String?, days: Int, action: RetentionAction, archiveDays: Int?) {
        val v = buildList {
            if (dataClass != null && dataClass !in RetentionDataClasses.ALL) add(Violation("/data_category", ViolationCode.NOT_IN_ENUM))
            if (days !in 1..36500) add(Violation("/retention_days", if (days < 1) ViolationCode.BELOW_MINIMUM else ViolationCode.ABOVE_MAXIMUM))
            if (action == RetentionAction.ARCHIVE && archiveDays == null) add(Violation("/archive_days", ViolationCode.REQUIRED))
            if (action != RetentionAction.ARCHIVE && archiveDays != null) add(Violation("/archive_days", ViolationCode.INVALID_VALUE))
            if (archiveDays != null && archiveDays !in 1..36500) add(Violation("/archive_days", if (archiveDays < 1) ViolationCode.BELOW_MINIMUM else ViolationCode.ABOVE_MAXIMUM))
        }
        if (v.isNotEmpty()) throw ProblemException.invalid(*v.toTypedArray())
    }

    private fun load(j: JdbcOperations, id: UUID): RetentionRuleView? = j.query("SELECT $RULE_COLUMNS FROM taktovia.retention_rule WHERE id = ?", ruleMapper, id).firstOrNull()
    private fun lock(j: JdbcOperations, id: UUID): RetentionRuleView? = j.query("SELECT $RULE_COLUMNS FROM taktovia.retention_rule WHERE id = ? FOR UPDATE", ruleMapper, id).firstOrNull()
    private fun stale() = ProblemException(ErrorCode.REVISION_MISMATCH, "Die Regel wurde zwischenzeitlich geändert; aktuelle Revision lesen.")
    private fun actor(ctx: RequestContext) = ActorRef((if (ctx.actor.type == ActorType.SERVICE) "service:" else "user:") + ctx.actor.subject)

    private companion object {
        const val RULE_COLUMNS = "id, data_class, retention_days, action, archive_days, status, revision, created_at"
        const val JOB_COLUMNS = ErasureJobs.COLUMNS
    }
}

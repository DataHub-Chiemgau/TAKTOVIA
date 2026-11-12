package de.taktovia.platform.retention

import de.taktovia.platform.audit.ActorRef
import de.taktovia.platform.audit.AuditActions
import de.taktovia.platform.audit.AuditEntry
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.audit.ObjectRef
import de.taktovia.platform.operation.OperationContext
import de.taktovia.platform.operation.OperationHandler
import de.taktovia.platform.operation.OperationOutcome
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.storage.ObjectKey
import de.taktovia.platform.storage.TenantObjectStore
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcOperations
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

internal object ErasureJobs {
    const val COLUMNS = "id, rule_id, data_class, action, status, processed_count, skipped_count, result_code, operation_id, created_at, finished_at, revision"

    fun map(rs: ResultSet) = ErasureJobView(
        rs.getObject("id", UUID::class.java), rs.getObject("rule_id", UUID::class.java), rs.getString("data_class"), RetentionAction.valueOf(rs.getString("action")),
        JobStatus.valueOf(rs.getString("status")), rs.getInt("processed_count"), rs.getInt("skipped_count"), rs.getString("result_code"), rs.getObject("operation_id", UUID::class.java),
        rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("finished_at")?.toInstant(), rs.getLong("revision"),
    )

    fun find(j: JdbcOperations, id: UUID): ErasureJobView? = j.query("SELECT $COLUMNS FROM taktovia.erasure_job WHERE id = ?", { rs, _ -> map(rs) }, id).firstOrNull()
}

/**
 * Löschlauf (WP-M1-33): wendet eine **freigegebene** Aufbewahrungsregel auf die fälligen Objekte der Datenklasse an, nur im Kontext **eines** Mandanten (RLS), und
 * protokolliert jedes Objekt ohne Personendaten im Lösch-/Sperrjournal.
 *
 * Fortsetzbar und ohne Doppelverarbeitung: je Objekt eine Transaktion, die (1) das Objekt per Advisory-Lock gegen einen parallelen Lauf sperrt, (2) im Journal prüft,
 * ob es schon bearbeitet wurde, (3) die Aktion über das [RetentionTarget] ausführt, (4) Objekte im Objektspeicher löscht, (5) das Journal schreibt und den Jobstand
 * (Zähler, Fortsetzungsmarke, Lease) fortschreibt. Bricht der Lauf ab, bleibt der Job `RESUMABLE`; ein hart beendeter Worker hinterlässt `RUNNING` mit abgelaufener Lease,
 * die ein anderer Lauf übernimmt. Zwei Läufe gleichzeitig bearbeiten ein Objekt nicht doppelt (Lock plus eindeutiges Journal).
 */
class ErasureEngine(
    private val tx: TenantTransactions,
    targets: List<RetentionTarget>,
    private val audit: AuditPort,
    private val objects: TenantObjectStore? = null,
    private val clock: Clock = Clock.systemUTC(),
    private val batchSize: Int = 50,
    private val lease: Duration = Duration.ofMinutes(5),
) {
    private val log = LoggerFactory.getLogger("taktovia.retention")
    private val byClass = targets.groupBy { it.dataClass }
    private val actor = ActorRef.system("retention-worker")

    private class Claim(val job: ErasureJobView, val rule: RuleSnapshot, val resumeAfter: UUID?, val correlation: UUID)

    /** Führt den Job im Kontext von [tenant] aus oder setzt ihn fort und liefert den Stand danach. Ein bereits laufender (gültige Lease) oder beendeter Job bleibt unberührt. */
    fun run(tenant: TenantId, jobId: UUID): ErasureJobView {
        val claim = when (val c = claim(tenant, jobId)) { is ClaimResult.Final -> return c.view; is ClaimResult.Won -> c.claim }
        val ts = byClass[claim.rule.dataClass].orEmpty()
        if (ts.isEmpty()) return finish(tenant, claim, JobStatus.ABORTED, "NO_TARGET")
        if (ts.any { claim.rule.action !in it.supportedActions }) return finish(tenant, claim, JobStatus.ABORTED, "ACTION_NOT_SUPPORTED")
        try {
            for (t in ts) {
                var after = if (ts.size == 1) claim.resumeAfter else null
                while (true) {
                    val now = clock.instant()
                    val batch = tx.run(tenant, readOnly = true) { j -> ArchiveAccess.enable(j); t.candidates(j, claim.rule, now, after, batchSize) }
                    if (batch.isEmpty()) break
                    for (c in batch) { processOne(tenant, claim, t, c); after = c.id }
                }
            }
        } catch (e: RuntimeException) {
            log.warn("{\"event\":\"erasure_interrupted\",\"job\":\"{}\",\"error\":\"{}\"}", jobId, e.javaClass.simpleName)
            return tx.run(tenant) { j ->
                j.update("UPDATE taktovia.erasure_job SET status = 'RESUMABLE', result_code = 'INTERRUPTED', lease_until = NULL WHERE id = ? AND status = 'RUNNING'", jobId)
                ErasureJobs.find(j, jobId)!!
            }
        }
        return finish(tenant, claim, JobStatus.COMPLETED, "OK")
    }

    private sealed interface ClaimResult {
        class Final(val view: ErasureJobView) : ClaimResult
        class Won(val claim: Claim) : ClaimResult
    }

    private fun claim(tenant: TenantId, jobId: UUID): ClaimResult = tx.run(tenant) { j ->
        val row = j.query("SELECT status, lease_until, rule_id, rule_revision, data_class, action, retention_days, archive_days, resume_after FROM taktovia.erasure_job WHERE id = ? FOR UPDATE", { rs, _ ->
            Row(JobStatus.valueOf(rs.getString("status")), rs.getTimestamp("lease_until")?.toInstant(), rs.getObject("rule_id", UUID::class.java), rs.getLong("rule_revision"),
                rs.getString("data_class"), RetentionAction.valueOf(rs.getString("action")), rs.getInt("retention_days"), rs.getObject("archive_days") as Int?,
                rs.getObject("resume_after", UUID::class.java))
        }, jobId).firstOrNull() ?: throw ProblemException.notFound()
        val now = clock.instant()
        val view = ErasureJobs.find(j, jobId)!!
        if (row.status == JobStatus.COMPLETED || row.status == JobStatus.ABORTED) return@run ClaimResult.Final(view)
        if (row.status == JobStatus.RUNNING && row.leaseUntil != null && row.leaseUntil.isAfter(now)) return@run ClaimResult.Final(view)
        // Die Regel muss jetzt noch freigegeben und unverändert sein; sonst wird nichts angewendet.
        val rule = j.query("SELECT status, revision FROM taktovia.retention_rule WHERE id = ?", { rs, _ -> rs.getString(1) to rs.getLong(2) }, row.ruleId).firstOrNull()
        val problem = when {
            rule == null || rule.first != "APPROVED" -> "RULE_NOT_APPROVED"
            rule.second != row.ruleRevision -> "RULE_CHANGED"
            else -> null
        }
        if (problem != null) {
            j.update("UPDATE taktovia.erasure_job SET status = 'ABORTED', result_code = ?, finished_at = clock_timestamp(), lease_until = NULL WHERE id = ?", problem, jobId)
            return@run ClaimResult.Final(ErasureJobs.find(j, jobId)!!)
        }
        j.update("UPDATE taktovia.erasure_job SET status = 'RUNNING', result_code = NULL, lease_until = ? WHERE id = ?", Timestamp.from(now.plus(lease)), jobId)
        ClaimResult.Won(Claim(ErasureJobs.find(j, jobId)!!, RuleSnapshot(row.ruleId, row.dataClass, row.action, row.retentionDays, row.archiveDays), row.resumeAfter, UUID.randomUUID()))
    }

    private class Row(val status: JobStatus, val leaseUntil: Instant?, val ruleId: UUID, val ruleRevision: Long, val dataClass: String, val action: RetentionAction,
                      val retentionDays: Int, val archiveDays: Int?, val resumeAfter: UUID?)

    private fun processOne(tenant: TenantId, claim: Claim, target: RetentionTarget, c: Candidate) {
        val ref = "${target.objectType}:${c.id}"
        tx.run(tenant) { j ->
            ArchiveAccess.enable(j)
            // Parallele Läufe: derselbe Objektbezug wird nacheinander bearbeitet; der zweite sieht danach den Journaleintrag.
            j.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(taktovia.current_tenant()::text || ?, 0))::text", String::class.java, ref)
            val done = j.queryForObject("SELECT count(*) FROM taktovia.erasure_journal WHERE object_ref = ? AND action = ?", Int::class.java, ref, c.action.name)!! > 0
            if (done) {
                j.update("UPDATE taktovia.erasure_job SET skipped_count = skipped_count + 1, resume_after = ?, lease_until = ? WHERE id = ?", c.id, Timestamp.from(clock.instant().plus(lease)), claim.job.id)
                return@run
            }
            val now = clock.instant()
            val result = target.apply(j, claim.rule, c, now)
            if (result.effective != RetentionAction.ARCHIVE) objects?.let { store ->
                target.objectPrefixes(c.id).forEach { p -> store.list(tenant, p).forEach { name -> store.delete(ObjectKey(tenant, name)) } }
            }
            j.update("INSERT INTO taktovia.erasure_journal (tenant_id, id, job_id, data_class, object_ref, action, archive_until) VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT (tenant_id, object_ref, action) DO NOTHING", UUID.randomUUID(), claim.job.id, claim.rule.dataClass, ref, result.effective.name, result.archiveUntil?.let(Timestamp::from))
            j.update("UPDATE taktovia.erasure_job SET processed_count = processed_count + 1, resume_after = ?, lease_until = ? WHERE id = ?", c.id, Timestamp.from(now.plus(lease)), claim.job.id)
        }
    }

    private fun finish(tenant: TenantId, claim: Claim, status: JobStatus, code: String): ErasureJobView = tx.run(tenant) { j ->
        j.update("UPDATE taktovia.erasure_job SET status = ?, result_code = ?, finished_at = clock_timestamp(), lease_until = NULL WHERE id = ? AND status = 'RUNNING'", status.name, code, claim.job.id)
        val view = ErasureJobs.find(j, claim.job.id)!!
        audit.record(j, AuditEntry(actor, AuditActions.RETENTION_ERASURE_FINISHED, ObjectRef("erasure_job", claim.job.id), claim.correlation,
            mapOf("status" to view.status.name, "result_code" to view.resultCode, "processed" to view.processedCount, "skipped" to view.skippedCount, "data_class" to view.dataClass)))
        view
    }
}

/** Operation-Handler für `RETENTION_RUN` (WP-M1-15): der Job der Operation wird fortsetzbar ausgeführt; Unterbrechungen führen zu einem späteren Neuversuch. */
class ErasureOperationHandler(private val engine: ErasureEngine, private val tx: TenantTransactions) : OperationHandler {
    override val kind = "RETENTION_RUN"

    override fun execute(ctx: OperationContext): OperationOutcome {
        val jobId = tx.run(ctx.tenant, readOnly = true) { j ->
            j.query("SELECT id FROM taktovia.erasure_job WHERE operation_id = ?", { rs, _ -> rs.getObject(1, UUID::class.java) }, ctx.operationId).firstOrNull()
        } ?: return OperationOutcome.Failed("JOB_NOT_FOUND")
        val view = engine.run(ctx.tenant, jobId)
        return when (view.status) {
            JobStatus.COMPLETED -> OperationOutcome.Succeeded("OK")
            JobStatus.ABORTED -> OperationOutcome.Failed(view.resultCode ?: "ABORTED")
            JobStatus.RESUMABLE, JobStatus.RUNNING -> OperationOutcome.Retry(view.resultCode ?: "IN_PROGRESS", Duration.ofSeconds(30))
        }
    }
}

package de.taktovia.documentsfinance.internal

import de.taktovia.documentsfinance.api.ConfirmLedgerImport
import de.taktovia.documentsfinance.api.ExportRequestOutcome
import de.taktovia.documentsfinance.api.LedgerBatch
import de.taktovia.documentsfinance.api.LedgerBatchJournal
import de.taktovia.documentsfinance.api.LedgerBatchLine
import de.taktovia.documentsfinance.api.LedgerDownload
import de.taktovia.documentsfinance.api.LedgerDownloadLink
import de.taktovia.documentsfinance.api.LedgerExportConfig
import de.taktovia.documentsfinance.api.LedgerExportLineView
import de.taktovia.documentsfinance.api.LedgerExportState
import de.taktovia.documentsfinance.api.LedgerExportView
import de.taktovia.documentsfinance.api.LedgerExports
import de.taktovia.documentsfinance.api.RequestLedgerExport
import de.taktovia.platform.audit.ActorRef
import de.taktovia.platform.audit.AuditActions
import de.taktovia.platform.audit.AuditEntry
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.audit.AuditRequired
import de.taktovia.platform.audit.ObjectRef
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.operation.OperationContext
import de.taktovia.platform.operation.OperationHandler
import de.taktovia.platform.operation.OperationOutcome
import de.taktovia.platform.operation.OperationService
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.outbox.OutboxEvent
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import java.security.MessageDigest
import java.sql.Date
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private val IMPORT_REF = Regex("[A-Za-z0-9._:/-]{1,100}")
private const val SCOPE_EXPORT = "ledger:export"
private const val SCOPE_READ = "ledger:read"

/**
 * ERP-Export (WP-M4-37). Sperren: Advisory-Lock je Umfang (Rechtsträger, Zielsystem, Zeitraum) beim Anfordern, Advisory-Lock je Rechtsträger im Job (serialisiert parallele Exportläufe, die sich überschneiden);
 * die Eindeutigkeit „je Journal genau ein Batch“ sichert zusätzlich der Primärschlüssel von `ledger_export_line`.
 */
internal class JdbcLedgerExports(
    private val ctxTx: ContextTransactions, private val audit: AuditPort, private val outbox: Outbox, private val clock: Clock, private val config: LedgerExportConfig,
) : LedgerExports {
    private val operations = OperationService(clock)

    // ---- Anfordern ----

    override fun request(ctx: RequestContext, cmd: RequestLedgerExport): ExportRequestOutcome {
        authorize(ctx, SCOPE_EXPORT)
        if (!cmd.periodTo.isAfter(cmd.periodFrom)) throw ProblemException.invalid(Violation("/period_to", ViolationCode.INVALID_VALUE, "Ende muss nach dem Beginn liegen"))
        if (cmd.periodFrom.plusDays(MAX_DAYS).isBefore(cmd.periodTo)) throw ProblemException.invalid(Violation("/period_to", ViolationCode.ABOVE_MAXIMUM, "höchstens $MAX_DAYS Tage"))
        if (cmd.targetSystem != LedgerExports.NEUTRAL) throw ProblemException.invalid(Violation("/target_system", ViolationCode.NOT_IN_ENUM, "bis zum Beschluss D-06 nur NEUTRAL"))
        return ctxTx.run(ctx) { j -> doRequest(j, ctx, cmd) }
    }

    @AuditRequired
    private fun doRequest(j: JdbcOperations, ctx: RequestContext, cmd: RequestLedgerExport): ExportRequestOutcome {
        if (j.queryForObject("SELECT count(*) FROM taktovia.legal_entity WHERE id = ?", Int::class.java, cmd.legalEntityId) != 1) throw ProblemException.notFound()
        j.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended('ledger_export_scope:' || taktovia.current_tenant()::text || ':' || ? || ':' || ? || ':' || ? || ':' || ?, 0))::text", String::class.java,
            cmd.legalEntityId.toString(), cmd.targetSystem, cmd.periodFrom.toString(), cmd.periodTo.toString())
        // derselbe Umfang: der offene Auftrag, sonst (nichts Neues exportierbar) der vorhandene Batch
        j.query("$SELECT WHERE legal_entity_id = ? AND target_system = ? AND period_from = ? AND period_to = ? AND state = 'REQUESTED'", MAPPER, cmd.legalEntityId, cmd.targetSystem, Date.valueOf(cmd.periodFrom), Date.valueOf(cmd.periodTo))
            .firstOrNull()?.let { return ExportRequestOutcome.Existing(it) }
        val pending = j.queryForObject("SELECT count(*) FROM taktovia.journal jn $ELIGIBLE", Int::class.java, cmd.legalEntityId, Date.valueOf(cmd.periodFrom), Date.valueOf(cmd.periodTo))!!
        if (pending == 0) {
            j.query("$SELECT WHERE legal_entity_id = ? AND target_system = ? AND period_from = ? AND period_to = ? AND state IN ('PROVIDED', 'CONFIRMED') ORDER BY created_at DESC, id DESC LIMIT 1",
                MAPPER, cmd.legalEntityId, cmd.targetSystem, Date.valueOf(cmd.periodFrom), Date.valueOf(cmd.periodTo)).firstOrNull()?.let { return ExportRequestOutcome.Existing(it) }
            throw ProblemException(ErrorCode.CONFLICT, "Im Zeitraum gibt es keine exportierbaren Journale (gebucht, vollständig freigegeben, noch in keinem Batch).")
        }
        val id = UUID.randomUUID()
        val op = operations.enqueue(j, LedgerExports.OPERATION_KIND, clock.instant().plus(Duration.ofDays(1)))
        j.update("INSERT INTO taktovia.ledger_export (tenant_id, id, legal_entity_id, target_system, period_from, period_to, operation_id, requested_by) VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?, ?)",
            id, cmd.legalEntityId, cmd.targetSystem, Date.valueOf(cmd.periodFrom), Date.valueOf(cmd.periodTo), op, ctx.actor.subject.take(200))
        audit.record(j, AuditEntry(DocumentRows.actor(ctx), AuditActions.FINANCE_LEDGER_EXPORT_CREATED, ObjectRef("ledger_export", id), ctx.correlationId,
            mapOf("legal_entity_id" to cmd.legalEntityId.toString(), "period_from" to cmd.periodFrom.toString(), "period_to" to cmd.periodTo.toString(), "target_system" to cmd.targetSystem, "pending_journals" to pending)))
        return ExportRequestOutcome.Queued(read(j, id))
    }

    // ---- Lesen ----

    override fun get(ctx: RequestContext, exportId: UUID): LedgerExportView {
        authorize(ctx, SCOPE_READ)
        return ctxTx.run(ctx, readOnly = true) { j -> j.query("$SELECT WHERE id = ?", MAPPER, exportId).firstOrNull() ?: throw ProblemException.notFound() }
    }

    override fun list(ctx: RequestContext, legalEntityId: UUID?, limit: Int, after: Pair<Instant, UUID>?): List<LedgerExportView> {
        authorize(ctx, SCOPE_READ)
        if (limit !in 1..200) throw ProblemException.invalid(Violation("/limit", ViolationCode.INVALID_VALUE))
        return ctxTx.run(ctx, readOnly = true) { j ->
            val ts = after?.first?.let { Timestamp.from(it) }
            j.query("$SELECT WHERE (CAST(? AS uuid) IS NULL OR legal_entity_id = ?) AND (CAST(? AS timestamptz) IS NULL OR (created_at, id) > (CAST(? AS timestamptz), CAST(? AS uuid))) ORDER BY created_at, id LIMIT ?",
                MAPPER, legalEntityId, legalEntityId, ts, ts, after?.second, limit)
        }
    }

    override fun lines(ctx: RequestContext, exportId: UUID): List<LedgerExportLineView> {
        authorize(ctx, SCOPE_READ)
        return ctxTx.run(ctx, readOnly = true) { j ->
            read(j, exportId)
            j.query("SELECT journal_id, document_id, import_ref, confirmed_at FROM taktovia.ledger_export_line WHERE export_id = ? ORDER BY journal_id",
                { rs, _ -> LedgerExportLineView(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getString(3), rs.getTimestamp(4)?.toInstant()) }, exportId)
        }
    }

    // ---- Download ----

    override fun downloadLink(ctx: RequestContext, exportId: UUID): LedgerDownloadLink {
        authorize(ctx, SCOPE_EXPORT)
        ctxTx.run(ctx, readOnly = true) { j ->
            val v = read(j, exportId)
            if (v.state != LedgerExportState.PROVIDED && v.state != LedgerExportState.CONFIRMED) throw ProblemException(ErrorCode.CONFLICT, "Der Batch ist nicht bereitgestellt.")
        }
        val until = clock.instant().plus(config.linkTtl.coerceAtMost(MAX_LINK_TTL))
        val expSec = until.epochSecond + (if (until.nano > 0) 1 else 0)   // aufgerundet auf volle Sekunden: die Adresse gilt nie kürzer als die Gültigkeit
        return LedgerDownloadLink("/v1/ledger/exports/$exportId/content?exp=$expSec&sig=${mac(ctx.tenant, exportId, expSec)}", Instant.ofEpochSecond(expSec))
    }

    override fun content(ctx: RequestContext, exportId: UUID, expires: Long, signature: String): LedgerDownload {
        authorize(ctx, SCOPE_EXPORT)
        val notFound = ProblemException.notFound()
        // Signatur, Mandant (steckt in der Signatur) und Ablauf; jede Abweichung ist von „nicht vorhanden“ nicht zu unterscheiden
        if (!MessageDigest.isEqual(mac(ctx.tenant, exportId, expires).toByteArray(), signature.toByteArray()) || !Instant.ofEpochSecond(expires).isAfter(clock.instant())) throw notFound
        return ctxTx.run(ctx) { j -> doContent(j, ctx, exportId) }
    }

    @AuditRequired
    private fun doContent(j: JdbcOperations, ctx: RequestContext, exportId: UUID): LedgerDownload {
        val row = j.query("SELECT media_type, content, content_sha256, batch_ref FROM taktovia.ledger_export WHERE id = ? AND state IN ('PROVIDED', 'CONFIRMED')",
            { rs, _ -> arrayOf(rs.getString(1), rs.getBytes(2), rs.getString(3).trim(), rs.getString(4)) }, exportId).firstOrNull() ?: throw ProblemException.notFound()
        audit.record(j, AuditEntry(DocumentRows.actor(ctx), AuditActions.FINANCE_LEDGER_EXPORT_DOWNLOADED, ObjectRef("ledger_export", exportId), ctx.correlationId, mapOf("batch_ref" to row[3] as String)))
        return LedgerDownload(row[0] as String, "${row[3]}.json", row[1] as ByteArray, row[2] as String)
    }

    private fun mac(tenant: TenantId, exportId: UUID, exp: Long): String {
        val m = Mac.getInstance("HmacSHA256"); m.init(SecretKeySpec(config.downloadSecret, "HmacSHA256"))
        return m.doFinal("ledger_export\n${tenant.value}\n$exportId\n$exp".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    // ---- Bestätigung ----

    override fun confirm(ctx: RequestContext, exportId: UUID, cmd: ConfirmLedgerImport): LedgerExportView {
        authorize(ctx, SCOPE_EXPORT)
        if (cmd.entries.isEmpty()) throw ProblemException.invalid(Violation("/entries", ViolationCode.TOO_FEW_ITEMS))
        if (cmd.entries.size > config.maxJournals) throw ProblemException.invalid(Violation("/entries", ViolationCode.TOO_MANY_ITEMS))
        cmd.entries.forEachIndexed { i, e -> if (!IMPORT_REF.matches(e.importRef)) throw ProblemException.invalid(Violation("/entries/$i/import_ref", ViolationCode.PATTERN_MISMATCH)) }
        if (cmd.entries.map { it.journalId }.toSet().size != cmd.entries.size) throw ProblemException.invalid(Violation("/entries", ViolationCode.NOT_UNIQUE, "ein Journal höchstens einmal"))
        if (cmd.entries.map { it.importRef }.toSet().size != cmd.entries.size) throw ProblemException.invalid(Violation("/entries", ViolationCode.NOT_UNIQUE, "eine Import-ID höchstens einmal"))
        return ctxTx.run(ctx) { j -> doConfirm(j, ctx, exportId, cmd) }
    }

    @AuditRequired
    private fun doConfirm(j: JdbcOperations, ctx: RequestContext, exportId: UUID, cmd: ConfirmLedgerImport): LedgerExportView {
        val ex = j.query("$SELECT WHERE id = ? FOR UPDATE", MAPPER, exportId).firstOrNull() ?: throw ProblemException.notFound()
        val lines = j.query("SELECT journal_id, import_ref FROM taktovia.ledger_export_line WHERE export_id = ? ORDER BY journal_id", { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getString(2) }, exportId)
        val wanted = cmd.entries.associate { it.journalId to it.importRef }
        if (ex.state == LedgerExportState.CONFIRMED) {
            if (lines.associate { it } == wanted) return ex   // dieselbe Bestätigung wiederholt: unveränderter Stand
            throw ProblemException(ErrorCode.CONFLICT, "Der Batch ist bereits mit anderen Import-IDs bestätigt.")
        }
        if (ex.state != LedgerExportState.PROVIDED) throw ProblemException(ErrorCode.CONFLICT, "Bestätigt wird nur ein bereitgestellter Batch (Zustand ${ex.state}).")
        if (lines.map { it.first }.toSet() != wanted.keys) throw ProblemException.invalid(Violation("/entries", ViolationCode.INVALID_VALUE, "die Bestätigung muss alle ${lines.size} Journale des Batchs genau einmal nennen"))
        val now = clock.instant(); val by = ctx.actor.subject.take(200)
        wanted.forEach { (journal, ref) -> check(j.update("UPDATE taktovia.ledger_export_line SET import_ref = ?, confirmed_at = ? WHERE export_id = ? AND journal_id = ? AND import_ref IS NULL", ref, Timestamp.from(now), exportId, journal) == 1) }
        check(j.update("UPDATE taktovia.ledger_export SET state = 'CONFIRMED', confirmed_at = ?, confirmed_by = ? WHERE id = ? AND state = 'PROVIDED'", Timestamp.from(now), by, exportId) == 1)
        j.queryForObject("SELECT set_config('app.export_job', 'on', true)", String::class.java)
        j.update("UPDATE taktovia.expense_document SET export_state = 'CONFIRMED', export_confirmed_at = ? WHERE export_state = 'EXPORTED' AND id IN (SELECT document_id FROM taktovia.ledger_export_line WHERE export_id = ?)", Timestamp.from(now), exportId)
        audit.record(j, AuditEntry(DocumentRows.actor(ctx), AuditActions.FINANCE_LEDGER_EXPORT_CONFIRMED, ObjectRef("ledger_export", exportId), ctx.correlationId, mapOf("batch_ref" to ex.batchRef, "confirmed_count" to lines.size)))
        outbox.append(j, OutboxEvent(exportId, 2, "ledger_export.confirmed", linkedMapOf("export_id" to exportId.toString(), "batch_ref" to ex.batchRef, "legal_entity_id" to ex.legalEntityId.toString(),
            "confirmed_count" to lines.size, "confirmed_at" to now.toString()), now, correlationId = ctx.correlationId))
        return read(j, exportId)
    }

    // ---- Job ----

    override fun operationHandler(tx: TenantTransactions): OperationHandler = object : OperationHandler {
        override val kind = LedgerExports.OPERATION_KIND
        override fun execute(ctx: OperationContext): OperationOutcome = runJob(tx, ctx)
        /** Ausgang unbekannt (Ausnahme nach dem Start): der Auftrag prüft seinen Stand und führt ihn höchstens einmal zu Ende; ein bereits bereitgestellter Batch bleibt unverändert. */
        override fun reconcile(ctx: OperationContext): OperationOutcome = runJob(tx, ctx)
    }

    private class Journal(val id: UUID, val eventId: UUID, val documentId: UUID, val reverses: UUID?, val date: LocalDate, val currency: String)

    private fun runJob(tx: TenantTransactions, ctx: OperationContext): OperationOutcome {
        val format = config.format
        return ctx.commit("OK") { j ->
            j.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended('ledger_export_entity:' || taktovia.current_tenant()::text, 0))::text", String::class.java)   // Exportläufe des Mandanten laufen nacheinander
            val ex = j.query("$SELECT WHERE operation_id = ? FOR UPDATE", MAPPER, ctx.operationId).firstOrNull() ?: return@commit
            if (ex.state != LedgerExportState.REQUESTED) return@commit   // schon bearbeitet (Wiederanlauf): keine zweite Wirkung
            if (format == null) { finish(j, ex.id, "FAILED", "NO_EXPORT_FORMAT"); return@commit }
            val journals = j.query("SELECT jn.id, jn.event_id, e.document_id, e.reverses_id, jn.accounting_date, jn.currency FROM taktovia.journal jn $ELIGIBLE ORDER BY jn.accounting_date, jn.id LIMIT ?",
                RowMapper { rs, _ -> Journal(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getObject(3, UUID::class.java), rs.getObject(4, UUID::class.java), rs.getDate(5).toLocalDate(), rs.getString(6).trim()) },
                ex.legalEntityId, Date.valueOf(ex.periodFrom), Date.valueOf(ex.periodTo), config.maxJournals)
            if (journals.isEmpty()) { finish(j, ex.id, "EMPTY", "NOTHING_TO_EXPORT"); return@commit }
            val lines = j.query("SELECT journal_id, account_code, side, amount_minor FROM taktovia.journal_line WHERE journal_id = ANY(CAST(? AS uuid[])) ORDER BY journal_id, account_code, side, amount_minor",
                { rs, _ -> rs.getObject(1, UUID::class.java) to LedgerBatchLine(rs.getString(2), rs.getString(3), rs.getLong(4)) }, journals.joinToString(",", "{", "}") { it.id.toString() }).groupBy({ it.first }, { it.second })
            val batchRef = "EXP-" + ex.id.toString().replace("-", "").take(12).uppercase()
            val rendered = format.render(LedgerBatch(batchRef, ex.id, ex.legalEntityId, ex.periodFrom, ex.periodTo,
                journals.map { LedgerBatchJournal(it.id, it.eventId, it.documentId, it.reverses, it.date, it.currency, lines[it.id].orEmpty()) }))
            val sha = MessageDigest.getInstance("SHA-256").digest(rendered.bytes).joinToString("") { "%02x".format(it) }
            val now = clock.instant()
            journals.forEach { jn -> j.update("INSERT INTO taktovia.ledger_export_line (tenant_id, journal_id, export_id, document_id) VALUES (taktovia.current_tenant(), ?, ?, ?)", jn.id, ex.id, jn.documentId) }   // Trigger: nur freigegebene Journale
            check(j.update("UPDATE taktovia.ledger_export SET state = 'PROVIDED', batch_ref = ?, format_name = ?, format_version = ?, media_type = ?, content = ?, content_sha256 = ?, content_size = ?, journal_count = ?, provided_at = ?, result_code = 'OK' " +
                "WHERE id = ? AND state = 'REQUESTED'", batchRef, format.name, format.version, rendered.mediaType, rendered.bytes, sha, rendered.bytes.size, journals.size, Timestamp.from(now), ex.id) == 1)
            j.queryForObject("SELECT set_config('app.export_job', 'on', true)", String::class.java)   // nur der Exportjob setzt den Exportstatus am Beleg
            j.update("UPDATE taktovia.expense_document SET export_state = 'EXPORTED', exported_at = ?, exported_by = ? WHERE export_state = 'NOT_EXPORTED' AND id = ANY(CAST(? AS uuid[]))", Timestamp.from(now), "service:${LedgerExports.JOB_ACTOR}",
                journals.map { it.documentId }.distinct().joinToString(",", "{", "}"))
            audit.record(j, AuditEntry(ActorRef.service(LedgerExports.JOB_ACTOR), AuditActions.FINANCE_LEDGER_EXPORT_PROVIDED, ObjectRef("ledger_export", ex.id), UUID.randomUUID(),
                mapOf("batch_ref" to batchRef, "journal_count" to journals.size, "content_sha256" to sha, "format" to format.name)))
            outbox.append(j, OutboxEvent(ex.id, 1, "ledger_export.provided", linkedMapOf("export_id" to ex.id.toString(), "batch_ref" to batchRef, "legal_entity_id" to ex.legalEntityId.toString(), "journal_count" to journals.size,
                "content_sha256" to sha, "provided_at" to now.toString()), now))
        }
    }

    private fun finish(j: JdbcOperations, id: UUID, state: String, code: String) {
        check(j.update("UPDATE taktovia.ledger_export SET state = ?, result_code = ? WHERE id = ? AND state = 'REQUESTED'", state, code, id) == 1)
    }

    // ---- gemeinsame Bausteine ----

    /** Scope; Menschen zusätzlich mit Finanzrolle (Dienstkonten genügt der Scope). */
    private fun authorize(ctx: RequestContext, scope: String) {
        if (!ctx.hasScope(scope) || (ctx.actor.type == ActorType.HUMAN && !DocumentRows.isFinance(ctx))) throw ProblemException(ErrorCode.FORBIDDEN)
    }

    private fun read(j: JdbcOperations, id: UUID): LedgerExportView = j.query("$SELECT WHERE id = ?", MAPPER, id).firstOrNull() ?: throw ProblemException.notFound()

    private companion object {
        const val MAX_DAYS = 400L
        val MAX_LINK_TTL: Duration = Duration.ofSeconds(60)
        const val SELECT = "SELECT id, legal_entity_id, target_system, period_from, period_to, state, operation_id, batch_ref, content_sha256, content_size, journal_count, format_name, format_version, media_type, result_code, requested_by, " +
            "created_at, provided_at, confirmed_at FROM taktovia.ledger_export"
        /** Exportierbare Journale: gebucht, Rechtsträger und Zeitraum, Beleg gebucht und freigegeben (Budgetfreigabe; bei Überschreitung der Schwelle führt der Beleg erst mit der zweiten Freigabe zum Posting), in keinem Batch. */
        const val ELIGIBLE = "JOIN taktovia.economic_event e ON e.tenant_id = jn.tenant_id AND e.id = jn.event_id JOIN taktovia.expense_document d ON d.tenant_id = e.tenant_id AND d.id = e.document_id " +
            "WHERE jn.legal_entity_id = ? AND jn.state = 'POSTED' AND jn.accounting_date >= ? AND jn.accounting_date < ? AND d.state = 'POSTED' AND d.approved_by IS NOT NULL " +
            "AND EXISTS (SELECT 1 FROM taktovia.approval_step s WHERE s.tenant_id = d.tenant_id AND s.document_id = d.id AND s.step = 'BUDGET' AND s.decision = 'APPROVED') " +
            "AND NOT EXISTS (SELECT 1 FROM taktovia.ledger_export_line l WHERE l.tenant_id = jn.tenant_id AND l.journal_id = jn.id)"
        val MAPPER = RowMapper { rs, _ ->
            LedgerExportView(rs.getObject("id", UUID::class.java), rs.getObject("legal_entity_id", UUID::class.java), rs.getString("target_system"), rs.getDate("period_from").toLocalDate(), rs.getDate("period_to").toLocalDate(),
                LedgerExportState.valueOf(rs.getString("state")), rs.getObject("operation_id", UUID::class.java), rs.getString("batch_ref"), rs.getString("content_sha256")?.trim(), rs.getObject("content_size") as Int?,
                rs.getObject("journal_count") as Int?, rs.getString("format_name"), rs.getString("format_version"), rs.getString("media_type"), rs.getString("result_code"), rs.getString("requested_by"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("provided_at")?.toInstant(), rs.getTimestamp("confirmed_at")?.toInstant())
        }
    }
}

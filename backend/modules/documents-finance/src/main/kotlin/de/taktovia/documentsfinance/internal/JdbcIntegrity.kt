package de.taktovia.documentsfinance.internal

import de.taktovia.documentsfinance.api.ApprovalClearance
import de.taktovia.documentsfinance.api.DocumentState
import de.taktovia.documentsfinance.api.IntegrityChecks
import de.taktovia.documentsfinance.api.IntegrityFailure
import de.taktovia.documentsfinance.api.IntegrityResult
import de.taktovia.documentsfinance.api.IntegrityState
import de.taktovia.documentsfinance.api.IntegrityTrigger
import de.taktovia.documentsfinance.api.SweepResult
import de.taktovia.documentsfinance.internal.DocumentRows.SCOPE_WRITE
import de.taktovia.documentsfinance.internal.DocumentRows.require
import de.taktovia.documentsfinance.internal.DocumentRows.sha256
import de.taktovia.platform.audit.ActorRef
import de.taktovia.platform.audit.AuditActions
import de.taktovia.platform.audit.ObjectRef
import de.taktovia.platform.audit.SecurityEvent
import de.taktovia.platform.audit.SecurityEventPort
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.outbox.OutboxEvent
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.storage.ObjectKey
import de.taktovia.platform.storage.TenantObjectStore
import org.springframework.jdbc.core.JdbcOperations
import java.time.Clock
import java.util.UUID

internal object IntegrityChecksSupport {
    fun violation() = ProblemException(ErrorCode.DOCUMENT_INTEGRITY_VIOLATION, "Das Original dieses Belegs stimmt nicht mehr mit dem erfassten Hash überein; der Beleg ist gesperrt.")
}

/**
 * Integritätsprüfung der Originale (WP-M4-24). Das Objekt wird außerhalb jeder Datenbanktransaktion gelesen und gehasht; die Feststellung (Zustand `VIOLATED`, unveränderlicher Vorfall,
 * Sicherheitsereignis, Outbox-Ereignis) geschieht in einer Transaktion unter Zeilensperre. `VIOLATED` ist endgültig (Trigger), ein Vorfall entsteht je Beleg höchstens einmal.
 */
internal class JdbcIntegrity(
    private val ctxTx: ContextTransactions, private val security: SecurityEventPort, private val outbox: Outbox, private val store: TenantObjectStore, private val clock: Clock,
) : IntegrityChecks {

    override fun check(ctx: RequestContext, documentId: UUID, trigger: IntegrityTrigger): IntegrityResult {
        // Auch die Auslieferung (Lesescope) stößt die Prüfung an; ein direkter Aufruf verlangt den Schreibscope.
        if (!ctx.hasScope(SCOPE_WRITE) && !ctx.hasScope(DocumentRows.SCOPE_READ)) throw ProblemException(ErrorCode.FORBIDDEN)
        val row = ctxTx.run(ctx, readOnly = true) { j -> load(j, ctx, documentId) }
        return verify(ctx, row, trigger)
    }

    private fun load(j: JdbcOperations, ctx: RequestContext, id: UUID): DocRow =
        if (ctx.actor.type == ActorType.SERVICE) j.query("SELECT $DOC_COLUMNS FROM taktovia.expense_document d WHERE d.id = ? AND d.upload_ticket_id IS NOT NULL", DocumentRows.MAPPER, id).firstOrNull() ?: throw ProblemException.notFound()
        else DocumentRows.visible(j, ctx, id)

    private fun verify(ctx: RequestContext, row: DocRow, trigger: IntegrityTrigger): IntegrityResult {
        val id = row.view.documentId
        if (row.view.state != DocumentState.STORED) return IntegrityResult(id, row.view.integrity, null, null)   // in Quarantäne gibt es noch kein Original
        if (row.view.integrity == IntegrityState.VIOLATED) return IntegrityResult(id, IntegrityState.VIOLATED, null, null)
        val bytes = store.get(ObjectKey(ctx.tenant, row.objectKey))   // außerhalb der Transaktion
        val actual = bytes?.let { sha256(it) }
        val failure = when { bytes == null -> IntegrityFailure.OBJECT_MISSING; actual != row.view.sha256 -> IntegrityFailure.HASH_MISMATCH; else -> null }
        return ctxTx.run(ctx) { j ->
            val cur = j.query("SELECT d.integrity_state FROM taktovia.expense_document d WHERE d.id = ? FOR NO KEY UPDATE", { rs, _ -> rs.getString(1) }, id).firstOrNull() ?: throw ProblemException.notFound()
            if (cur == "VIOLATED") return@run IntegrityResult(id, IntegrityState.VIOLATED, null, null)   // schon festgestellt (parallel)
            if (failure == null) {
                j.update("UPDATE taktovia.expense_document SET integrity_state = 'VERIFIED', integrity_checked_at = clock_timestamp() WHERE id = ?", id)
                return@run IntegrityResult(id, IntegrityState.VERIFIED, null, null)
            }
            val incident = UUID.randomUUID()
            j.update("INSERT INTO taktovia.document_integrity_incident (tenant_id, id, document_id, trigger_kind, failure, expected_sha256, actual_sha256, detected_by) VALUES (taktovia.current_tenant(),?,?,?,?,?,?,?)",
                incident, id, trigger.name, failure.name, row.view.sha256, actual, ctx.actor.subject.take(200))
            j.update("UPDATE taktovia.expense_document SET integrity_state = 'VIOLATED', integrity_checked_at = clock_timestamp() WHERE id = ?", id)
            security.report(j, SecurityEvent(ActorRef.system("integrity-check"), AuditActions.SECURITY_DOCUMENT_INTEGRITY_VIOLATED, ObjectRef("document", id), ctx.correlationId,
                mapOf("trigger" to trigger.name, "failure" to failure.name)))
            outbox.append(j, OutboxEvent(id, row.view.revision, "document.integrity_violated", linkedMapOf(   // ohne Hashwerte (lange Hexfolgen), sie stehen im Vorfall
                "document_id" to id.toString(), "trigger" to trigger.name, "failure" to failure.name, "detected_at" to clock.instant().toString()), clock.instant(), correlationId = ctx.correlationId))
            IntegrityResult(id, IntegrityState.VIOLATED, failure, incident)
        }
    }

    override fun sweep(ctx: RequestContext, limit: Int, after: UUID?): SweepResult {
        require(limit in 1..1000) { "limit 1..1000" }
        require(ctx, SCOPE_WRITE)
        val ids = ctxTx.run(ctx, readOnly = true) { j ->
            j.query("SELECT d.id FROM taktovia.expense_document d WHERE d.state = 'STORED' AND d.integrity_state <> 'VIOLATED' AND d.upload_ticket_id IS NOT NULL AND (?::uuid IS NULL OR d.id > ?::uuid) ORDER BY d.id LIMIT ?",
                { rs, _ -> rs.getObject(1, UUID::class.java) }, after, after, limit)
        }
        var violations = 0
        for (id in ids) {
            val row = ctxTx.run(ctx, readOnly = true) { j -> load(j, ctx, id) }
            if (verify(ctx, row, IntegrityTrigger.SCHEDULED).state == IntegrityState.VIOLATED) violations++
        }
        return SweepResult(ids.size, violations, if (ids.size == limit) ids.last() else null)
    }

    override fun clearForApproval(ctx: RequestContext, documentId: UUID, expectedRevision: Long): ApprovalClearance {
        require(ctx, SCOPE_WRITE)
        val row = ctxTx.run(ctx, readOnly = true) { j -> load(j, ctx, documentId) }
        if (row.view.revision != expectedRevision) throw ProblemException(ErrorCode.REVISION_MISMATCH)
        if (row.view.state != DocumentState.STORED) throw ProblemException(ErrorCode.CONFLICT, "Nur ein geprüftes Original (Zustand STORED) ist freigabefähig.")
        if (verify(ctx, row, IntegrityTrigger.APPROVAL).state == IntegrityState.VIOLATED) throw IntegrityChecksSupport.violation()
        // Bescheinigung nur, wenn der Beleg unterdessen weder verletzt noch geändert wurde (Zeilensperre bis zum Ende dieser Transaktion).
        return ctxTx.run(ctx) { j ->
            val r = j.query("SELECT d.integrity_state, d.revision FROM taktovia.expense_document d WHERE d.id = ? FOR SHARE", { rs, _ -> rs.getString(1) to rs.getLong(2) }, documentId).firstOrNull() ?: throw ProblemException.notFound()
            if (r.first == "VIOLATED") throw IntegrityChecksSupport.violation()
            if (r.second != expectedRevision) throw ProblemException(ErrorCode.REVISION_MISMATCH)
            ApprovalClearance(documentId, expectedRevision, clock.instant())
        }
    }

    override fun requirePostable(jdbc: JdbcOperations, documentId: UUID) {
        val state = jdbc.query("SELECT d.integrity_state FROM taktovia.expense_document d WHERE d.id = ? FOR SHARE", { rs, _ -> rs.getString(1) }, documentId).firstOrNull() ?: throw ProblemException.notFound()
        if (state == "VIOLATED") throw IntegrityChecksSupport.violation()
    }
}

/** Spaltenliste der Belegzeile (siehe [DocumentRows]); als Konstante, weil der Query-Lint nur Konstanten als Interpolation zulässt. */
private const val DOC_COLUMNS = DocumentRows.COLUMNS

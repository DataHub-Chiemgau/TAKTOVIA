package de.taktovia.documentsfinance.internal

import de.taktovia.documentsfinance.api.ApprovalDecision
import de.taktovia.documentsfinance.api.ApprovalOutcome
import de.taktovia.documentsfinance.api.ApprovalStep
import de.taktovia.documentsfinance.api.ApprovalStepView
import de.taktovia.documentsfinance.api.CostEvents
import de.taktovia.documentsfinance.api.DocumentKind
import de.taktovia.documentsfinance.api.ExpenseApprovals
import de.taktovia.documentsfinance.api.ExpenseFields
import de.taktovia.documentsfinance.api.ExpenseStates
import de.taktovia.documentsfinance.api.ExpenseView
import de.taktovia.documentsfinance.api.IntegrityState
import de.taktovia.documentsfinance.api.PostExpense
import de.taktovia.documentsfinance.internal.DocumentRows.SCOPE_READ
import de.taktovia.documentsfinance.internal.DocumentRows.SCOPE_WRITE
import de.taktovia.documentsfinance.internal.DocumentRows.require
import de.taktovia.platform.audit.AuditActions
import de.taktovia.platform.audit.AuditEntry
import de.taktovia.platform.audit.AuditPort
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
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import java.time.Clock
import java.util.UUID

/** Lesen der Freigabeschritte, geteilt von Beleglesen und Workflow. */
internal object ApprovalRows {
    private val MAPPER = RowMapper { rs, _ ->
        ApprovalStepView(rs.getObject("id", UUID::class.java), ApprovalStep.valueOf(rs.getString("step")), rs.getLong("document_revision"), ApprovalDecision.valueOf(rs.getString("decision")),
            rs.getString("decided_by"), rs.getString("reason"), rs.getTimestamp("decided_at").toInstant())
    }

    fun steps(j: JdbcOperations, documentId: UUID): List<ApprovalStepView> =
        j.query("SELECT id, step, document_revision, decision, decided_by, reason, decided_at FROM taktovia.approval_step WHERE document_id = ? ORDER BY decided_at, id", MAPPER, documentId)

    fun exportState(j: JdbcOperations, documentId: UUID): de.taktovia.documentsfinance.api.ExportState =
        j.query("SELECT export_state FROM taktovia.expense_document WHERE id = ?", { rs, _ -> de.taktovia.documentsfinance.api.ExportState.valueOf(rs.getString(1)) }, documentId).firstOrNull() ?: de.taktovia.documentsfinance.api.ExportState.NOT_EXPORTED
}

/**
 * Freigabe-Workflow (WP-M4-34). Jeder Befehl: Recht prüfen (403 vor jeder Wirkung und ohne Schritteintrag), Belegzeile sperren (`FOR NO KEY UPDATE`), exakte Revision prüfen (412), Zustand prüfen (409),
 * Blockaden prüfen ([ApprovalGate]), Schritt, Zustand, Audit und Ereignis in **einer** Transaktion; die endgültige Freigabe bucht in derselben Transaktion ([CostEvents.postExpense]), daher gibt es `POSTED` nie
 * ohne Ereignis, Journal und Outbox, und ein scheiterndes Posting lässt den Beleg unverändert. Parallele Freigaben serialisiert die Belegsperre: genau eine gewinnt, die andere sieht den neuen Zustand.
 */
internal class JdbcExpenseApprovals(
    private val ctxTx: ContextTransactions, private val audit: AuditPort, private val outbox: Outbox, private val costEvents: CostEvents, private val clock: Clock,
) : ExpenseApprovals {

    // ---- Erfassung abschließen ----

    override fun submit(ctx: RequestContext, documentId: UUID, expectedRevision: Long): ExpenseView {
        require(ctx, SCOPE_WRITE)
        DocumentRows.requireHuman(ctx)
        return ctxTx.run(ctx) { j ->
            val row = lockedAt(j, ctx, documentId, expectedRevision)
            when {
                row.stateRaw == "QUARANTINED" || row.stateRaw == "QUARANTINE_REJECTED" -> throw ProblemException(ErrorCode.CONFLICT, "Ein Beleg in Quarantäne kann nicht eingereicht werden.")
                row.stateRaw != ExpenseStates.STORED -> throw ProblemException(ErrorCode.INVALID_STATE_TRANSITION, "Nur ein Beleg in Erfassung kann eingereicht werden.")
                row.view.integrity == IntegrityState.VIOLATED -> throw IntegrityChecksSupport.violation()
            }
            DuplicateRows.detect(j, audit, outbox, clock, ctx, documentId); DuplicateRows.requireNoBlock(j, documentId, includeOpen = false)   // Evidenz einer Dublette wird nie eigener Kostenfall
            transition(j, ctx, row, ApprovalStep.CAPTURE, ApprovalDecision.APPROVED, "Erfassung abgeschlossen", ExpenseStates.STORED, ExpenseStates.IN_REVIEW)
            ExpenseViews.build(j, DocumentRows.visible(j, ctx, documentId), ctx)
        }
    }

    // ---- fachliche Prüfung ----

    override fun review(ctx: RequestContext, documentId: UUID, expectedRevision: Long, decision: ApprovalDecision, reason: String): ExpenseView {
        requireRight(ctx, SCOPE_REVIEW)
        val text = reasonOf(reason)
        if (decision == ApprovalDecision.APPROVED) ApprovalGate.precheck(ctxTx, audit, outbox, clock, ctx, documentId)
        return ctxTx.run(ctx) { j ->
            val row = lockedAt(j, ctx, documentId, expectedRevision)
            if (row.stateRaw != ExpenseStates.IN_REVIEW) throw ProblemException(ErrorCode.INVALID_STATE_TRANSITION, "Nur ein eingereichter Beleg kann fachlich geprüft werden.")
            if (decision == ApprovalDecision.APPROVED) {
                ApprovalGate.require(j, audit, outbox, clock, ctx, row, includeCosts = true)
                transition(j, ctx, row, ApprovalStep.REVIEW, decision, text, ExpenseStates.IN_REVIEW, ExpenseStates.REVIEWED)
                event(j, ctx, row, "expense.reviewed", ExpenseStates.REVIEWED)
            } else transition(j, ctx, row, ApprovalStep.REVIEW, decision, text, ExpenseStates.IN_REVIEW, ExpenseStates.STORED)   // zurück in die Erfassung, ohne Kostenereignis und Journal
            ExpenseViews.build(j, DocumentRows.visible(j, ctx, documentId), ctx)
        }
    }

    // ---- Budgetfreigabe und zweite Freigabe ----

    override fun approve(ctx: RequestContext, documentId: UUID, validatedRevision: Long, reason: String): ApprovalOutcome {
        requireRight(ctx, SCOPE_APPROVE)
        val text = reasonOf(reason)
        ApprovalGate.precheck(ctxTx, audit, outbox, clock, ctx, documentId)
        return ctxTx.run(ctx) { j -> doApprove(j, ctx, documentId, validatedRevision, text) }
    }

    private fun doApprove(j: JdbcOperations, ctx: RequestContext, id: UUID, revision: Long, reason: String): ApprovalOutcome {
        val row = lockedAt(j, ctx, id, revision)
        val second = when (row.stateRaw) {
            ExpenseStates.REVIEWED -> false
            ExpenseStates.WAITING_SECOND_APPROVAL -> true
            ExpenseStates.POSTED, ExpenseStates.APPROVED -> throw ProblemException(ErrorCode.INVALID_STATE_TRANSITION, "Der Beleg ist bereits freigegeben bzw. gebucht.")
            else -> throw ProblemException(ErrorCode.INVALID_STATE_TRANSITION, "Der Beleg hat die fachliche Prüfung noch nicht bestanden.")
        }
        val actor = ctx.actor.subject.take(200)
        // Rollentrennung: der Erfasser gibt nie selbst frei, die zweite Freigabe erteilt nie der Erstfreigeber (auch nicht über eine Vertretung: ein Vertreter handelt unter eigener Identität)
        if (actor == row.createdBy) throw ProblemException(ErrorCode.FORBIDDEN, "Der Erfasser kann seinen Beleg nicht selbst freigeben.")
        if (second) {
            val first = j.query("SELECT decided_by FROM taktovia.approval_step WHERE document_id = ? AND document_revision = ? AND step = 'BUDGET' AND decision = 'APPROVED' ORDER BY decided_at DESC, id DESC LIMIT 1",
                { rs, _ -> rs.getString(1) }, id, revision).firstOrNull()
            if (first == actor) throw ProblemException(ErrorCode.FORBIDDEN, "Die zweite Freigabe erteilt eine andere Person als die Erstfreigabe.")
        }
        ApprovalGate.require(j, audit, outbox, clock, ctx, row, includeCosts = true)
        val fields = ExpenseFieldCodec.parseStored(row.fieldsJson)
        val step = if (second) ApprovalStep.SECOND_APPROVAL else ApprovalStep.BUDGET
        if (!second && requiresSecond(j, fields)) {
            transition(j, ctx, row, step, ApprovalDecision.APPROVED, reason, ExpenseStates.REVIEWED, ExpenseStates.WAITING_SECOND_APPROVAL)
            event(j, ctx, row, "expense.second_approval_pending", ExpenseStates.WAITING_SECOND_APPROVAL)
            return ApprovalOutcome(id, revision, ExpenseStates.WAITING_SECOND_APPROVAL, null, ExpenseViews.build(j, DocumentRows.visible(j, ctx, id), ctx))
        }
        // endgültige Freigabe: Schritt, Freigabezustand und Posting (Ereignis, Journal, Outbox) in derselben Transaktion; POSTED erst nach dem atomaren Commit
        transition(j, ctx, row, step, ApprovalDecision.APPROVED, reason, row.stateRaw, ExpenseStates.APPROVED)
        check(j.update("UPDATE taktovia.expense_document SET approved_by = ? WHERE id = ? AND state = 'APPROVED'", DocumentRows.actor(ctx).value, id) == 1)
        val posting = costEvents.postExpense(j, ctx, id, PostExpense(categoryOf(row.view.kind)))
        event(j, ctx, row, "expense.approved", ExpenseStates.POSTED)
        return ApprovalOutcome(id, revision, ExpenseStates.POSTED, posting.event.id, ExpenseViews.build(j, DocumentRows.visible(j, ctx, id), ctx))
    }

    /** Vier-Augen-Schwelle: Betrag **größer** als die Schwelle (exakt auf der Schwelle genügt eine Freigabe); andere Währung als die der Schwelle: stets zweite Stufe; ohne Schwelle keine zweite Stufe. */
    private fun requiresSecond(j: JdbcOperations, fields: Map<String, Any>): Boolean {
        val policy = JdbcFinancePolicies.load(j)
        val threshold = policy.approvalSecondThresholdMinor ?: return false
        val gross = fields[ExpenseFields.AMOUNT_MINOR] as? Long ?: return true
        return fields[ExpenseFields.CURRENCY] != policy.approvalThresholdCurrency || gross > threshold
    }

    private fun categoryOf(kind: DocumentKind) = when (kind) { DocumentKind.FUEL_RECEIPT -> "FUEL"; DocumentKind.INVOICE -> "INVOICE"; DocumentKind.RECEIPT -> "RECEIPT"; DocumentKind.OTHER -> "OTHER" }

    // ---- Ablehnung in Budgetfreigabe oder zweiter Freigabe ----

    override fun reject(ctx: RequestContext, documentId: UUID, expectedRevision: Long, reason: String): ExpenseView {
        requireRight(ctx, SCOPE_APPROVE)
        val text = reasonOf(reason)
        return ctxTx.run(ctx) { j ->
            val row = lockedAt(j, ctx, documentId, expectedRevision)
            val step = when (row.stateRaw) {
                ExpenseStates.REVIEWED -> ApprovalStep.BUDGET
                ExpenseStates.WAITING_SECOND_APPROVAL -> ApprovalStep.SECOND_APPROVAL
                else -> throw ProblemException(ErrorCode.INVALID_STATE_TRANSITION, "Ablehnen lässt sich nur ein Beleg in der Budgetfreigabe oder in der zweiten Freigabe.")
            }
            transition(j, ctx, row, step, ApprovalDecision.REJECTED, text, row.stateRaw, ExpenseStates.STORED)   // zurück in die Erfassung, ohne Kostenereignis und Journal
            ExpenseViews.build(j, DocumentRows.visible(j, ctx, documentId), ctx)
        }
    }

    // ---- Lesen und Export ----

    override fun steps(ctx: RequestContext, documentId: UUID): List<ApprovalStepView> {
        require(ctx, SCOPE_READ)
        return ctxTx.run(ctx, readOnly = true) { j -> DocumentRows.visible(j, ctx, documentId); ApprovalRows.steps(j, documentId) }
    }

    override fun markExported(ctx: RequestContext, documentId: UUID): ExpenseView {
        if (!ctx.hasScope(SCOPE_EXPORT)) throw ProblemException(ErrorCode.FORBIDDEN)
        return ctxTx.run(ctx) { j ->
            val row = j.query(EXPORT_DOC_SQL, DocumentRows.MAPPER, documentId).firstOrNull() ?: throw ProblemException.notFound()
            if (row.stateRaw != ExpenseStates.POSTED) throw ProblemException(ErrorCode.INVALID_STATE_TRANSITION, "Exportiert wird nur ein gebuchter, also vollständig freigegebener Beleg.")
            if (ApprovalRows.exportState(j, documentId) == de.taktovia.documentsfinance.api.ExportState.NOT_EXPORTED) {
                j.queryForObject("SELECT set_config('app.export_job', 'on', true)", String::class.java)   // nur der Exportjob setzt den Exportstatus (WP-M4-37); dieser Port ist sein Altpfad mit Exportberechtigung
                j.update("UPDATE taktovia.expense_document SET export_state = 'EXPORTED', exported_at = clock_timestamp(), exported_by = ? WHERE id = ? AND state = 'POSTED' AND export_state = 'NOT_EXPORTED'", ctx.actor.subject.take(200), documentId)
                audit.record(j, AuditEntry(DocumentRows.actor(ctx), AuditActions.FINANCE_EXPENSE_EXPORTED, ObjectRef("expense", documentId), ctx.correlationId, mapOf("revision" to row.view.revision)))
            }
            ExpenseViews.build(j, j.query(DOC_SQL, DocumentRows.MAPPER, documentId).single(), ctx)   // der Exportjob hat keine Finanzrolle: der Beleg ist über seine Kennung bereits bestimmt
        }
    }

    // ---- gemeinsame Bausteine ----

    /** Menschliche Finanzkraft mit dem Funktionsscope (403 sonst, vor jeder Wirkung). */
    private fun requireRight(ctx: RequestContext, scope: String) {
        if (!ctx.hasScope(scope) || ctx.actor.type != ActorType.HUMAN || !DocumentRows.isFinance(ctx)) throw ProblemException(ErrorCode.FORBIDDEN)
    }

    private fun reasonOf(reason: String): String {
        val t = reason.trim()
        if (t.length < 3) throw ProblemException.invalid(Violation("/reason", if (t.isEmpty()) ViolationCode.REQUIRED else ViolationCode.TOO_SHORT))
        if (t.length > 1000) throw ProblemException.invalid(Violation("/reason", ViolationCode.TOO_LONG))
        return t
    }

    /** Beleg gesperrt und auf exakter Revision (412 sonst). */
    private fun lockedAt(j: JdbcOperations, ctx: RequestContext, id: UUID, revision: Long): DocRow {
        val row = DocumentRows.visible(j, ctx, id, lock = true)
        if (row.view.revision != revision) throw ProblemException(ErrorCode.REVISION_MISMATCH, "Der Beleg wurde zwischenzeitlich geändert; aktuelle Revision lesen.")
        return row
    }

    /** Schritt eintragen und Zustand wechseln (bedingtes UPDATE auf den erwarteten Zustand; der Beleg ist gesperrt); Audit je Schritt. */
    private fun transition(j: JdbcOperations, ctx: RequestContext, row: DocRow, step: ApprovalStep, decision: ApprovalDecision, reason: String, from: String, to: String) {
        val id = row.view.documentId
        j.update("INSERT INTO taktovia.approval_step (tenant_id, id, document_id, document_revision, step, decision, decided_by, reason) VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?, ?)",
            UUID.randomUUID(), id, row.view.revision, step.name, decision.name, ctx.actor.subject.take(200), reason)
        check(j.update("UPDATE taktovia.expense_document SET state = ? WHERE id = ? AND state = ?", to, id, from) == 1) { "bedingter Zustandswechsel traf keine Zeile" }
        audit.record(j, AuditEntry(DocumentRows.actor(ctx), AuditActions.FINANCE_EXPENSE_APPROVAL_STEP, ObjectRef("expense", id), ctx.correlationId,
            mapOf("step" to step.name, "decision" to decision.name, "revision" to row.view.revision, "state" to to)))
    }

    private fun event(j: JdbcOperations, ctx: RequestContext, row: DocRow, type: String, state: String) {
        outbox.append(j, OutboxEvent(row.view.documentId, row.view.revision, type, linkedMapOf("document_id" to row.view.documentId.toString(), "revision" to row.view.revision, "state" to state,
            "occurred_at" to clock.instant().toString()), clock.instant(), correlationId = ctx.correlationId))
    }

    private companion object {
        const val SCOPE_REVIEW = "expenses:review"
        const val SCOPE_APPROVE = "expenses:approve"
        const val SCOPE_EXPORT = "expenses:export"
        const val DOC_SQL = "SELECT ${DocumentRows.COLUMNS} FROM taktovia.expense_document d WHERE d.id = ?"
        const val EXPORT_DOC_SQL = "SELECT ${DocumentRows.COLUMNS} FROM taktovia.expense_document d WHERE d.id = ? AND d.upload_ticket_id IS NOT NULL FOR NO KEY UPDATE"
    }
}

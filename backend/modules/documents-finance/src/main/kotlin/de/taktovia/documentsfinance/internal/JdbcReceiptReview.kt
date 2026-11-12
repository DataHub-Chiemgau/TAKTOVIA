package de.taktovia.documentsfinance.internal

import de.taktovia.documentsfinance.api.ApprovalClearance
import de.taktovia.documentsfinance.api.ExpenseFields
import de.taktovia.documentsfinance.api.IntegrityChecks
import de.taktovia.documentsfinance.api.IntegrityState
import de.taktovia.documentsfinance.api.ReceiptReview
import de.taktovia.documentsfinance.api.ReleaseBlocker
import de.taktovia.documentsfinance.api.ReviewState
import de.taktovia.documentsfinance.internal.DocumentRows.SCOPE_READ
import de.taktovia.documentsfinance.internal.DocumentRows.SCOPE_WRITE
import de.taktovia.documentsfinance.internal.DocumentRows.require
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import org.springframework.jdbc.core.JdbcOperations
import java.time.Clock
import java.util.UUID

/**
 * Regeln der Bestätigungspflicht (WP-M4-28, DOC-003). Ein Belegfeld ist erst Belegstand, wenn ein Mensch es bestätigt oder korrigiert hat (`current_fields` wird nur über [JdbcExpenses] geschrieben);
 * deshalb genügt für ein Pflichtfeld, dass es in `current_fields` steht. Zusätzlich bleibt ein markierter OCR-Vorschlag (niedrige Konfidenz, Summenabweichung) ohne Entscheidung ein offener Punkt, auch wenn
 * sein Feld nicht Pflicht ist, und die bestätigten Werte müssen Netto + Steuer = Brutto erfüllen.
 */
internal object ReviewRules {
    fun state(j: JdbcOperations, documentId: UUID, current: Map<String, Any>): ReviewState {
        val mandatory = JdbcDocumentSettings.load(j).receiptMandatoryFields
        val missing = ExpenseFields.ALL.filter { it in mandatory && it !in current }
        val flagged = j.query(
            "SELECT DISTINCT f.field_kind FROM taktovia.extracted_field f WHERE f.origin = 'OCR' AND f.flag_reason IS NOT NULL AND f.normalized_value IS NOT NULL " +
                "AND f.run_id = (SELECT r.id FROM taktovia.extraction_run r WHERE r.document_id = ? AND r.status = 'COMPLETED' ORDER BY r.requested_at DESC, r.id LIMIT 1) " +
                "AND NOT EXISTS (SELECT 1 FROM taktovia.extracted_field h WHERE h.corrects_field_id = f.id)",
            { rs, _ -> de.taktovia.documentsfinance.api.ExtractedFieldKind.valueOf(rs.getString(1)).expenseField }, documentId).toSet()
        val unconfirmed = ExpenseFields.ALL.filter { it in missing || it in flagged }
        return ReviewState(unconfirmed, sumMismatch(current))
    }

    /** Netto + Steuer ≠ Brutto, wenn alle drei Werte bestätigt vorliegen. */
    fun sumMismatch(current: Map<String, Any>): Boolean {
        val gross = current[ExpenseFields.AMOUNT_MINOR] as? Long ?: return false
        val net = current[ExpenseFields.NET_AMOUNT_MINOR] as? Long ?: return false
        val vat = current[ExpenseFields.VAT_AMOUNTS] as? List<*> ?: return false
        return net + vat.sumOf { ((it as Map<*, *>)["amount_minor"] as Number).toLong() } != gross
    }

    fun blockers(s: ReviewState): List<ReleaseBlocker> = buildList {
        if (s.unconfirmedFields.isNotEmpty()) add(ReleaseBlocker.UNCONFIRMED_FIELDS)
        if (s.sumMismatch) add(ReleaseBlocker.SUM_MISMATCH)
    }

    fun problem(s: ReviewState) = ProblemException(
        ErrorCode.UNCONFIRMED_FIELDS, "Pflichtfelder sind nicht bestätigt oder korrigiert, oder Netto + Steuer entspricht nicht dem Brutto; ohne Bestätigung wird nichts freigegeben.",
        s.unconfirmedFields.map { Violation("/fields/$it", ViolationCode.REQUIRED, "Feld ist nicht bestätigt oder korrigiert") } +
            (if (s.sumMismatch) listOf(Violation("/fields/${ExpenseFields.AMOUNT_MINOR}", ViolationCode.INVALID_VALUE, "Netto + Steuer entspricht nicht dem Brutto")) else emptyList()),
    )
}

internal class JdbcReceiptReview(private val ctxTx: ContextTransactions, private val integrity: IntegrityChecks, private val clock: Clock, private val audit: de.taktovia.platform.audit.AuditPort, private val outbox: de.taktovia.platform.outbox.Outbox) : ReceiptReview {

    override fun state(ctx: RequestContext, id: UUID): ReviewState {
        require(ctx, SCOPE_READ)
        return ctxTx.run(ctx, readOnly = true) { j -> ReviewRules.state(j, id, ExpenseFieldCodec.parseStored(DocumentRows.visible(j, ctx, id).fieldsJson)) }
    }

    override fun requireApprovable(ctx: RequestContext, id: UUID, expectedRevision: Long): ApprovalClearance {
        require(ctx, SCOPE_WRITE)
        val clearance = integrity.clearForApproval(ctx, id, expectedRevision)   // Hash jetzt prüfen; veraltete Revision 412, nicht STORED 409
        ctxTx.run(ctx) { j ->   // Treffer und Hinweise zuerst festschreiben (eigene Transaktion), damit sie entscheidbar und quittierbar bleiben
            val r = DocumentRows.visible(j, ctx, id, lock = true)
            DuplicateRows.detect(j, audit, outbox, clock, ctx, id); PlausibilityRows.check(j, audit, ctx, r.view.kind, id, r.view.revision)
        }
        ctxTx.run(ctx) { j -> requireApprovable(j, ctx, id, expectedRevision) }
        return ApprovalClearance(id, expectedRevision, maxOf(clearance.checkedAt, clock.instant()))
    }

    override fun requireApprovable(jdbc: JdbcOperations, ctx: RequestContext, id: UUID, expectedRevision: Long) {
        require(ctx, SCOPE_WRITE)
        val row = DocumentRows.visible(jdbc, ctx, id, lock = true)   // FOR UPDATE: keine Feldkorrektur zwischen Prüfung und Zustandswechsel
        if (row.view.revision != expectedRevision) throw ProblemException(ErrorCode.REVISION_MISMATCH, "Der Beleg wurde zwischenzeitlich geändert; aktuelle Revision lesen.")
        if (row.stateRaw != "STORED") throw ProblemException(ErrorCode.CONFLICT, "Nur ein Beleg im Entwurfszustand ist freigabefähig.")
        if (row.view.integrity == IntegrityState.VIOLATED) throw IntegrityChecksSupport.violation()
        if (row.view.senderVerified == false) throw ProblemException(ErrorCode.CONFLICT, "Der Absender ist nicht verifiziert.")
        DuplicateRows.detect(jdbc, audit, outbox, clock, ctx, id); DuplicateRows.requireNoBlock(jdbc, id)   // Dublette: 409, bevor Einzelprüfungen laufen (WP-M4-32)
        val s = ReviewRules.state(jdbc, id, ExpenseFieldCodec.parseStored(row.fieldsJson))
        if (!s.ready) throw ReviewRules.problem(s)
        if (row.view.kind == de.taktovia.documentsfinance.api.DocumentKind.FUEL_RECEIPT) {
            // Tankwerte vollständig (solange `fuel_receipt_required` gilt) und Plausibilitätshinweise quittiert (WP-M4-30, -31)
            val fuel = FuelRows.forDocument(jdbc, row.view.kind, id)
            if (JdbcFinancePolicies.load(jdbc).fuelReceiptRequired && fuel != null && !fuel.complete) throw FuelRows.missingProblem(fuel)
            PlausibilityRows.requireAcknowledged(jdbc, audit, ctx, row.view.kind, id, row.view.revision)
        }
    }
}

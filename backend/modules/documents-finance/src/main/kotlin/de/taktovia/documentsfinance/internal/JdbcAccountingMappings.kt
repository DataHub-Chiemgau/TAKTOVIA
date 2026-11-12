package de.taktovia.documentsfinance.internal

import de.taktovia.documentsfinance.api.AccountingMappingView
import de.taktovia.documentsfinance.api.AccountingMappings
import de.taktovia.documentsfinance.api.PutAccountingMapping
import de.taktovia.documentsfinance.internal.DocumentRows.isFinance
import de.taktovia.documentsfinance.internal.DocumentRows.require
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
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import java.util.UUID

/** Kontierungsregeln (WP-M4-33): Kostenart -> Konten je Rechtsträger. Ein Rechtsträger eines anderen Mandanten ist nicht auffindbar (404); Änderung erhöht die Revision, gleiche Werte ändern nichts. */
internal class JdbcAccountingMappings(private val ctxTx: ContextTransactions, private val audit: AuditPort) : AccountingMappings {

    override fun put(ctx: RequestContext, cmd: PutAccountingMapping): AccountingMappingView {
        require(ctx, SCOPE)
        if (!isFinance(ctx)) throw ProblemException(ErrorCode.FORBIDDEN)
        val bad = listOfNotNull(
            if (!CATEGORY.matches(cmd.category)) Violation("/category", ViolationCode.PATTERN_MISMATCH) else null,
            if (!ACCOUNT.matches(cmd.expenseAccount)) Violation("/expense_account", ViolationCode.PATTERN_MISMATCH) else null,
            if (!ACCOUNT.matches(cmd.inputTaxAccount)) Violation("/input_tax_account", ViolationCode.PATTERN_MISMATCH) else null,
            if (!ACCOUNT.matches(cmd.payableAccount)) Violation("/payable_account", ViolationCode.PATTERN_MISMATCH) else null,
            if (cmd.expenseAccount == cmd.payableAccount || cmd.inputTaxAccount == cmd.payableAccount) Violation("/payable_account", ViolationCode.INVALID_VALUE) else null,
        )
        if (bad.isNotEmpty()) throw ProblemException.invalid(*bad.toTypedArray())
        return ctxTx.run(ctx) { j -> doPut(j, ctx, cmd) }
    }

    @AuditRequired
    private fun doPut(j: JdbcOperations, ctx: RequestContext, cmd: PutAccountingMapping): AccountingMappingView {
        if (j.queryForObject("SELECT count(*) FROM taktovia.legal_entity WHERE id = ?", Int::class.java, cmd.legalEntityId) != 1) throw ProblemException.notFound()
        val by = DocumentRows.actor(ctx).value
        val current = j.query("SELECT $COLUMNS FROM taktovia.accounting_mapping WHERE legal_entity_id = ? AND category = ? FOR UPDATE", MAPPER, cmd.legalEntityId, cmd.category).firstOrNull()
        val view = when {
            current == null -> j.query("INSERT INTO taktovia.accounting_mapping (tenant_id, legal_entity_id, category, expense_account, input_tax_account, payable_account, updated_by) VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?) RETURNING $COLUMNS",
                MAPPER, cmd.legalEntityId, cmd.category, cmd.expenseAccount, cmd.inputTaxAccount, cmd.payableAccount, by).single()
            current.expenseAccount == cmd.expenseAccount && current.inputTaxAccount == cmd.inputTaxAccount && current.payableAccount == cmd.payableAccount -> return current
            else -> j.query("UPDATE taktovia.accounting_mapping SET expense_account = ?, input_tax_account = ?, payable_account = ?, revision = revision + 1, updated_by = ?, updated_at = clock_timestamp() " +
                "WHERE legal_entity_id = ? AND category = ? RETURNING $COLUMNS", MAPPER, cmd.expenseAccount, cmd.inputTaxAccount, cmd.payableAccount, by, cmd.legalEntityId, cmd.category).single()
        }
        audit.record(j, AuditEntry(DocumentRows.actor(ctx), AuditActions.FINANCE_ACCOUNTING_MAPPING_CHANGED, ObjectRef("accounting_mapping", cmd.legalEntityId), ctx.correlationId, mapOf("category" to cmd.category, "revision" to view.revision)))
        return view
    }

    override fun get(ctx: RequestContext, legalEntityId: UUID, category: String): AccountingMappingView? {
        require(ctx, SCOPE)
        return ctxTx.run(ctx, readOnly = true) { j -> j.query("SELECT $COLUMNS FROM taktovia.accounting_mapping WHERE legal_entity_id = ? AND category = ?", MAPPER, legalEntityId, category).firstOrNull() }
    }

    private companion object {
        const val SCOPE = "expenses:approve"
        const val COLUMNS = "legal_entity_id, category, expense_account, input_tax_account, payable_account, revision"
        val CATEGORY = Regex("[A-Z][A-Z0-9_]{0,39}")
        val ACCOUNT = Regex("[0-9A-Za-z._-]{1,20}")
        val MAPPER = RowMapper { rs, _ ->
            AccountingMappingView(rs.getObject("legal_entity_id", UUID::class.java), rs.getString("category"), rs.getString("expense_account"), rs.getString("input_tax_account"), rs.getString("payable_account"), rs.getLong("revision"))
        }
    }
}

package de.taktovia.documentsfinance.internal

import de.taktovia.documentsfinance.api.FinancePolicies
import de.taktovia.documentsfinance.api.FinancePolicyView
import de.taktovia.documentsfinance.api.UpdateFinancePolicy
import de.taktovia.documentsfinance.internal.DocumentRows.SCOPE_READ
import de.taktovia.documentsfinance.internal.DocumentRows.SCOPE_WRITE
import de.taktovia.documentsfinance.internal.DocumentRows.require
import de.taktovia.platform.audit.AuditActions
import de.taktovia.platform.audit.AuditEntry
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.audit.ObjectRef
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper

/** Fachparameter des Moduls je Mandant (`finance_policy`, WP-M4-30). Ohne Zeile gelten die Vorgaben von [FinancePolicyView]. */
internal class JdbcFinancePolicies(private val ctxTx: ContextTransactions, private val audit: AuditPort) : FinancePolicies {

    override fun get(ctx: RequestContext): FinancePolicyView {
        require(ctx, SCOPE_READ)
        return ctxTx.run(ctx, readOnly = true) { j -> load(j) }
    }

    override fun update(ctx: RequestContext, cmd: UpdateFinancePolicy): FinancePolicyView {
        require(ctx, SCOPE_WRITE)
        if (!DocumentRows.isFinance(ctx)) throw ProblemException(ErrorCode.FORBIDDEN)
        return ctxTx.run(ctx) { j ->
            val current = load(j)
            val required = cmd.fuelReceiptRequired ?: current.fuelReceiptRequired
            val v = j.query(
                "INSERT INTO taktovia.finance_policy AS p (tenant_id, fuel_receipt_required, updated_by) VALUES (taktovia.current_tenant(), ?, ?) " +
                    "ON CONFLICT (tenant_id) DO UPDATE SET fuel_receipt_required = EXCLUDED.fuel_receipt_required, updated_by = EXCLUDED.updated_by, updated_at = clock_timestamp(), revision = p.revision + 1 " +
                    "RETURNING fuel_receipt_required, revision", MAPPER, required, ctx.actor.subject.take(200)).single()
            audit.record(j, AuditEntry(DocumentRows.actor(ctx), AuditActions.FINANCE_POLICY_CHANGED, ObjectRef("finance_policy", ctx.tenant.value.toString()), ctx.correlationId, mapOf("revision" to v.revision)))
            v
        }
    }

    companion object {
        private val MAPPER = RowMapper { rs, _ -> FinancePolicyView(rs.getBoolean("fuel_receipt_required"), rs.getLong("revision")) }

        /** Parameter des Mandanten der laufenden Transaktion (RLS); Vorgaben ohne Zeile. */
        fun load(j: JdbcOperations): FinancePolicyView =
            j.query("SELECT fuel_receipt_required, revision FROM taktovia.finance_policy", MAPPER).firstOrNull() ?: FinancePolicyView()
    }
}

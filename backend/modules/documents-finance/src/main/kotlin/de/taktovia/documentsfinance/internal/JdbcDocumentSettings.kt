package de.taktovia.documentsfinance.internal

import de.taktovia.documentsfinance.api.DocumentSettings
import de.taktovia.documentsfinance.api.DocumentSettingsView
import de.taktovia.documentsfinance.api.UpdateDocumentSettings
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
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import java.util.UUID

/** Mandanteneinstellungen der Belege. Ohne Zeile: OCR an, kein E-Mail-Eingang, keine verifizierte Domäne. */
internal class JdbcDocumentSettings(private val ctxTx: ContextTransactions, private val audit: AuditPort) : DocumentSettings {

    override fun get(ctx: RequestContext): DocumentSettingsView {
        require(ctx, SCOPE_READ)
        return ctxTx.run(ctx, readOnly = true) { j -> load(j) }
    }

    override fun update(ctx: RequestContext, cmd: UpdateDocumentSettings): DocumentSettingsView {
        require(ctx, SCOPE_WRITE)
        if (!DocumentRows.isFinance(ctx)) throw ProblemException(ErrorCode.FORBIDDEN)
        val address = cmd.invoiceEmailInbound?.trim()?.lowercase()
        if (address != null && !ADDRESS.matches(address)) throw ProblemException.invalid(Violation("/invoice_email_inbound", ViolationCode.INVALID_FORMAT))
        val domains = cmd.verifiedSenderDomains.map { it.trim().lowercase() }.toSortedSet()
        domains.firstOrNull { !DOMAIN.matches(it) }?.let { throw ProblemException.invalid(Violation("/verified_sender_domains", ViolationCode.INVALID_FORMAT)) }
        if (address != null && cmd.inboundLegalEntityId == null) throw ProblemException.invalid(Violation("/inbound_legal_entity_id", ViolationCode.REQUIRED))
        return ctxTx.run(ctx) { j ->
            cmd.inboundLegalEntityId?.let { le -> if (j.query("SELECT 1 FROM taktovia.legal_entity WHERE id = ?", { _, _ -> 1 }, le).isEmpty()) throw ProblemException.notFound() }
            // Domänen sind auf [a-z0-9.-] geprüft: im Array-Literal ist kein Quoting nötig
            val v = j.query(
                "INSERT INTO taktovia.document_settings AS s (tenant_id, receipt_ocr_enabled, invoice_email_inbound, inbound_legal_entity_id, verified_sender_domains, updated_by) VALUES (taktovia.current_tenant(),?,?,?,CAST(? AS text[]),?) " +
                    "ON CONFLICT (tenant_id) DO UPDATE SET receipt_ocr_enabled = EXCLUDED.receipt_ocr_enabled, invoice_email_inbound = EXCLUDED.invoice_email_inbound, inbound_legal_entity_id = EXCLUDED.inbound_legal_entity_id, " +
                    "verified_sender_domains = EXCLUDED.verified_sender_domains, updated_by = EXCLUDED.updated_by, revision = s.revision + 1 " +
                    "RETURNING receipt_ocr_enabled, invoice_email_inbound, inbound_legal_entity_id, verified_sender_domains, revision",
                MAPPER, cmd.receiptOcrEnabled, address, cmd.inboundLegalEntityId, "{" + domains.joinToString(",") + "}", ctx.actor.subject.take(200)).single()
            audit.record(j, AuditEntry(DocumentRows.actor(ctx), AuditActions.FINANCE_DOCUMENT_SETTINGS_CHANGED, ObjectRef("document_settings", ctx.tenant.value.toString()), ctx.correlationId, mapOf("revision" to v.revision)))
            v
        }
    }

    companion object {
        private val ADDRESS = Regex("[a-z0-9._%+-]{1,64}@[a-z0-9.-]{1,200}\\.[a-z]{2,}")
        private val DOMAIN = Regex("[a-z0-9.-]{1,200}\\.[a-z]{2,}")
        private val MAPPER = RowMapper { rs, _ ->
            DocumentSettingsView(rs.getBoolean("receipt_ocr_enabled"), rs.getString("invoice_email_inbound"), rs.getObject("inbound_legal_entity_id", UUID::class.java),
                (rs.getArray("verified_sender_domains").array as Array<*>).map { it.toString() }.toSet(), rs.getLong("revision"))
        }
        private val DEFAULTS = DocumentSettingsView(receiptOcrEnabled = true, invoiceEmailInbound = null, inboundLegalEntityId = null, verifiedSenderDomains = emptySet(), revision = 0)

        /** Einstellungen des Mandanten der laufenden Transaktion (RLS), Vorgabewerte ohne Zeile. */
        fun load(j: JdbcOperations): DocumentSettingsView =
            j.query("SELECT receipt_ocr_enabled, invoice_email_inbound, inbound_legal_entity_id, verified_sender_domains, revision FROM taktovia.document_settings", MAPPER).firstOrNull() ?: DEFAULTS
    }
}

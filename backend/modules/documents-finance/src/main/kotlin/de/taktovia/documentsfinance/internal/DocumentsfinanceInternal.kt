package de.taktovia.documentsfinance.internal

import de.taktovia.documentsfinance.api.CostCenterAdmin
import de.taktovia.documentsfinance.api.DocumentsfinanceApi
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.context.ContextTransactions

/** Nur innerhalb des Moduls `documents-finance` sichtbar (durch Architekturtest erzwungen). */
internal object DocumentsfinanceServicesFactory {
    fun create(ctxTx: ContextTransactions, audit: AuditPort): DocumentsfinanceApi = object : DocumentsfinanceApi {
        override val costCenters: CostCenterAdmin = JdbcCostCenterAdmin(ctxTx, audit)
    }
}

/** Fabrik des Upload-Bausteins (WP-M4-21); nur innerhalb des Moduls sichtbar. */
internal object UploadServicesFactory {
    fun create(
        ctxTx: de.taktovia.platform.context.ContextTransactions, audit: de.taktovia.platform.audit.AuditPort, security: de.taktovia.platform.audit.SecurityEventPort,
        outbox: de.taktovia.platform.outbox.Outbox, store: de.taktovia.platform.storage.TenantObjectStore, scanner: de.taktovia.documentsfinance.api.MalwareScanner,
        policy: de.taktovia.documentsfinance.api.UploadPolicy, signer: de.taktovia.documentsfinance.api.UploadTargetSigner?, clock: java.time.Clock,
    ): de.taktovia.documentsfinance.api.Uploads = JdbcUploads(ctxTx, audit, security, outbox, store, scanner, policy, signer, clock)
}

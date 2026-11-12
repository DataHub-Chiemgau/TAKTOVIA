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

/** Fabrik der Belegbausteine (WP-M4-21 bis -27); nur innerhalb des Moduls sichtbar. */
internal object UploadServicesFactory {
    fun create(
        ctxTx: ContextTransactions, audit: AuditPort, security: de.taktovia.platform.audit.SecurityEventPort, outbox: de.taktovia.platform.outbox.Outbox, store: de.taktovia.platform.storage.TenantObjectStore,
        scanner: de.taktovia.documentsfinance.api.MalwareScanner, policy: de.taktovia.documentsfinance.api.UploadPolicy, signer: de.taktovia.documentsfinance.api.UploadTargetSigner?, clock: java.time.Clock,
    ): de.taktovia.documentsfinance.api.Uploads = JdbcUploads(ctxTx, audit, security, outbox, store, scanner, policy, signer, clock, JdbcIntegrity(ctxTx, security, outbox, store, clock))

    fun services(
        ctxTx: ContextTransactions, audit: AuditPort, security: de.taktovia.platform.audit.SecurityEventPort, outbox: de.taktovia.platform.outbox.Outbox, store: de.taktovia.platform.storage.TenantObjectStore,
        scanner: de.taktovia.documentsfinance.api.MalwareScanner, policy: de.taktovia.documentsfinance.api.UploadPolicy, signer: de.taktovia.documentsfinance.api.UploadTargetSigner?, clock: java.time.Clock,
        recognizer: de.taktovia.documentsfinance.api.ReceiptRecognizer?, extraction: de.taktovia.documentsfinance.api.ExtractionPolicy,
    ): de.taktovia.documentsfinance.api.DocumentServices {
        // Originale sind schreibgeschützt: der Objektspeicher der Bausteine lässt unter `originals/` weder Überschreiben noch Löschen zu (WP-M4-23).
        val guarded = de.taktovia.documentsfinance.api.ProtectedObjectStore(store)
        val integrity = JdbcIntegrity(ctxTx, security, outbox, guarded, clock)
        val uploads = JdbcUploads(ctxTx, audit, security, outbox, guarded, scanner, policy, signer, clock, integrity)
        return de.taktovia.documentsfinance.api.DocumentServices(
            uploads, JdbcExpenses(ctxTx, audit, outbox, guarded, uploads, policy, clock), JdbcIntake(ctxTx, uploads, policy), JdbcDocumentSettings(ctxTx, audit), integrity,
            JdbcExtraction(ctxTx, outbox, guarded, recognizer, extraction, clock), JdbcCostAssignments(ctxTx, audit, outbox, clock),
            JdbcCostEvents(ctxTx, audit, outbox, integrity, clock), JdbcAccountingMappings(ctxTx, audit),
        )
    }
}

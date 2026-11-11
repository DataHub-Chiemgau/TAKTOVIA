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

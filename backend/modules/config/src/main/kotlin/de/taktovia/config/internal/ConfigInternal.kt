package de.taktovia.config.internal

import de.taktovia.config.api.CompanyProfileService
import de.taktovia.config.api.ConfigApi
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.outbox.Outbox
import java.time.Clock

/** Nur innerhalb des Moduls `config` sichtbar (durch Architekturtest erzwungen). */
internal object ConfigServicesFactory {
    fun create(ctxTx: ContextTransactions, audit: AuditPort, outbox: Outbox, clock: Clock): ConfigApi {
        val profile = JdbcCompanyProfileService(ctxTx, audit, outbox, clock)
        return object : ConfigApi { override val companyProfile: CompanyProfileService = profile }
    }
}

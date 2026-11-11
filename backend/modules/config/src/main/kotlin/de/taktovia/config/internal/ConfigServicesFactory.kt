package de.taktovia.config.internal

import de.taktovia.config.api.ConfigApi
import de.taktovia.config.api.ConfigRevisions
import de.taktovia.config.api.ParameterRegistry
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.outbox.Outbox
import java.time.Clock

/** Verdrahtung der Dienste des Moduls; von außen nur über [ConfigApi.create] erreichbar. */
internal object ConfigServicesFactory {
    fun create(ctxTx: ContextTransactions, audit: AuditPort, outbox: Outbox, clock: Clock): ConfigApi {
        val catalogs = PolicyCatalogRepository()
        val parameters = JdbcParameterRegistry(ctxTx, catalogs, clock)
        val revisions = JdbcConfigRevisions(ctxTx, catalogs, audit, outbox, clock)
        return object : ConfigApi {
            override val parameters: ParameterRegistry = parameters
            override val revisions: ConfigRevisions = revisions
        }
    }
}

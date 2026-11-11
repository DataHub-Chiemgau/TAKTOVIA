package de.taktovia.servicegeo.internal

import de.taktovia.config.api.ConfigApi
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.servicegeo.api.OrganizationStructure
import de.taktovia.servicegeo.api.ServicegeoApi
import java.time.Clock

/** Nur innerhalb des Moduls `service-geo` sichtbar (durch Architekturtest erzwungen). */
internal object ServicegeoServicesFactory {
    fun create(ctxTx: ContextTransactions, audit: AuditPort, config: ConfigApi, clock: Clock): ServicegeoApi = object : ServicegeoApi {
        override val structure: OrganizationStructure = JdbcOrganizationStructure(ctxTx, audit, config, clock)
    }
}

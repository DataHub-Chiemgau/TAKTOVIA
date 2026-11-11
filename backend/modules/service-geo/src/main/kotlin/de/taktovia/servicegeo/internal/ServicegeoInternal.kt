package de.taktovia.servicegeo.internal

import de.taktovia.config.api.ConfigApi
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.authz.ObjectAuthorizer
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.servicegeo.api.OrganizationStructure
import de.taktovia.servicegeo.api.ServicegeoApi
import de.taktovia.platform.outbox.Outbox
import de.taktovia.servicegeo.api.ServiceAvailabilities
import de.taktovia.servicegeo.api.ServiceRevisions
import java.time.Clock

/** Nur innerhalb des Moduls `service-geo` sichtbar (durch Architekturtest erzwungen). */
internal object ServicegeoServicesFactory {
    fun offerModelValidator(): de.taktovia.config.api.RevisionValidator = ServiceConfigRevisionValidator()

    fun create(ctxTx: ContextTransactions, audit: AuditPort, config: ConfigApi, clock: Clock, authorizer: ObjectAuthorizer, outbox: Outbox): ServicegeoApi = object : ServicegeoApi {
        override val structure: OrganizationStructure = JdbcOrganizationStructure(ctxTx, audit, config, clock, authorizer)
        override val configurations: de.taktovia.servicegeo.api.ServiceConfigurations = JdbcServiceConfigurations(config, clock)
        override val serviceRevisions: ServiceRevisions = JdbcServiceRevisions(ctxTx, audit, outbox, clock, authorizer)
        override val availability: ServiceAvailabilities = JdbcServiceAvailabilities(ctxTx)
    }
}

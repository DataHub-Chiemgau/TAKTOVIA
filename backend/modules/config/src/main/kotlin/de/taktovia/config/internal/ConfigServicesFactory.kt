package de.taktovia.config.internal

import de.taktovia.config.api.CompanyProfileService
import de.taktovia.config.api.ConfigApi
import de.taktovia.config.api.ConfigRevisions
import de.taktovia.config.api.ImpactAnalysis
import de.taktovia.config.api.ImpactProvider
import de.taktovia.config.api.ConfigPublication
import de.taktovia.config.api.RevisionValidator
import de.taktovia.config.api.ParameterRegistry
import de.taktovia.config.api.EffectiveConfig
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.authz.ObjectAuthorizer
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.outbox.Outbox
import java.time.Clock

/** Verdrahtung der Dienste des Moduls; von außen nur über [ConfigApi.create] erreichbar. */
internal object ConfigServicesFactory {
    fun create(ctxTx: ContextTransactions, audit: AuditPort, outbox: Outbox, clock: Clock, authorizer: ObjectAuthorizer, impactProviders: List<ImpactProvider>, validators: List<RevisionValidator>): ConfigApi {
        val catalogs = PolicyCatalogRepository()
        val parameters = JdbcParameterRegistry(ctxTx, catalogs, clock)
        val revisions = JdbcConfigRevisions(ctxTx, catalogs, audit, outbox, clock)
        val impact = JdbcImpactAnalysis(ctxTx, revisions, audit, impactProviders, clock)
        val publication = JdbcConfigPublication(ctxTx, revisions, catalogs, impact, audit, outbox, validators, clock)
        val profile = JdbcCompanyProfileService(ctxTx, audit, outbox, clock)
        val effective = JdbcEffectiveConfig(ctxTx, catalogs, authorizer, clock)
        return object : ConfigApi {
            override val companyProfile: CompanyProfileService = profile
            override val parameters: ParameterRegistry = parameters
            override val revisions: ConfigRevisions = revisions
            override val effective: EffectiveConfig = effective
            override val impact: ImpactAnalysis = impact
            override val publication: ConfigPublication = publication
        }
    }
}

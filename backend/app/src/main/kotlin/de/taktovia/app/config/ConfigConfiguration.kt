package de.taktovia.app.config

import de.taktovia.app.identity.TenantSeed
import de.taktovia.config.api.CompanyProfileInput
import de.taktovia.config.api.ConfigApi
import de.taktovia.config.api.ImpactProvider
import de.taktovia.config.api.RevisionValidator
import de.taktovia.platform.audit.ActorRef
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.outbox.Outbox
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Verdrahtung des Moduls `config` (WP-M1-24). Die Erstausstattung eines neuen Mandanten (WP-M1-05) legt die erste Profilrevision in **derselben**
 * Transaktion an: schlägt sie fehl (z. B. unbekannte Währung), entsteht kein Mandant.
 */
@Configuration
class ConfigConfiguration {
    /** Wirkungsanbieter der Fachmodule (M2: Bediengebiete und Buchungen, M4: Schichten) werden als Beans `ImpactProvider` eingehängt; in M1 gibt es keinen. */
    /** Fachliche Prüfregeln der Module (WP-M1-22): Beans `RevisionValidator` laufen bei der Validierung und noch einmal bei der Publikation (z. B. Modellregeln der Dienstkonfiguration, WP-M1-25). */
    @Bean fun configApi(
        ctxTx: ContextTransactions, audit: AuditPort, outbox: Outbox, impactProviders: ObjectProvider<ImpactProvider>, validators: ObjectProvider<RevisionValidator>,
    ): ConfigApi = ConfigApi.create(ctxTx, audit, outbox, impactProviders = impactProviders.orderedStream().toList(), revisionValidators = validators.orderedStream().toList())

    @Bean fun tenantProfileSeed(config: ConfigApi): TenantSeed = TenantSeed { jdbc, _, legalName, contactEmail, currency, actor, correlationId ->
        config.companyProfile.seed(jdbc, ActorRef(actor), correlationId, CompanyProfileInput(legalName, contactEmail, currency))
    }
}

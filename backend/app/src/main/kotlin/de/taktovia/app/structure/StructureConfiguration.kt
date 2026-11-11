package de.taktovia.app.structure

import de.taktovia.config.api.ConfigApi
import de.taktovia.config.api.RevisionValidator
import de.taktovia.documentsfinance.api.DocumentsfinanceApi
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.outbox.Outbox
import de.taktovia.servicegeo.api.ServicegeoApi
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Verdrahtung der Organisationsstruktur (WP-M1-09): Region, Betriebshof, Dienstkopf (`service-geo`) und Kostenstellen (`documents-finance`). */
@Configuration
class StructureConfiguration {
    @Bean fun servicegeoApi(ctxTx: ContextTransactions, audit: AuditPort, config: ConfigApi, outbox: Outbox): ServicegeoApi = ServicegeoApi.create(ctxTx, audit, config, outbox = outbox)
    /** Modellregeln der Dienstkonfiguration (WP-M1-25): laufen bei Validierung und Publikation jeder Dienstrevision; ohne Abhängigkeit von `ConfigApi` (kein Verdrahtungszyklus). */
    @Bean fun offerModelRevisionValidator(): RevisionValidator = ServicegeoApi.offerModelValidator()
    @Bean fun documentsfinanceApi(ctxTx: ContextTransactions, audit: AuditPort): DocumentsfinanceApi = DocumentsfinanceApi.create(ctxTx, audit)
}

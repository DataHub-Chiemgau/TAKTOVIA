package de.taktovia.app.config

import de.taktovia.config.api.ConfigApi
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.outbox.Outbox
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Verdrahtung des Moduls `config` (WP-M1-19, WP-M1-20): Parameterkatalog und Konfigurationsrevisionen. */
@Configuration
class ConfigConfiguration {
    @Bean fun configApi(ctxTx: ContextTransactions, audit: AuditPort, outbox: Outbox): ConfigApi = ConfigApi.create(ctxTx, audit, outbox)
}

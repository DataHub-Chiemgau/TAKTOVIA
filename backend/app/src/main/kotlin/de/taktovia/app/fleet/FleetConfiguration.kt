package de.taktovia.app.fleet

import de.taktovia.fleetsafety.api.FleetsafetyApi
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.outbox.Outbox
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Verdrahtung des Moduls `fleet-safety` (WP-M1-34). */
@Configuration
class FleetConfiguration {
    @Bean fun fleetsafetyApi(ctxTx: ContextTransactions, audit: AuditPort, outbox: Outbox): FleetsafetyApi = FleetsafetyApi.create(ctxTx, audit, outbox)
}

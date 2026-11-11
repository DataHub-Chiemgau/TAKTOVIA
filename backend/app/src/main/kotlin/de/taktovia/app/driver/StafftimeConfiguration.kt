package de.taktovia.app.driver

import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.outbox.Outbox
import de.taktovia.stafftime.api.StafftimeApi
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Verdrahtung des Moduls `staff-time` (WP-M3-24). */
@Configuration
class StafftimeConfiguration {
    @Bean fun stafftimeApi(ctxTx: ContextTransactions, audit: AuditPort, outbox: Outbox): StafftimeApi = StafftimeApi.create(ctxTx, audit, outbox)
}

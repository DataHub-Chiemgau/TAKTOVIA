package de.taktovia.app

import de.taktovia.farepayment.api.Tariffs
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.outbox.Outbox
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Verdrahtung der Tarifrevisionen (WP-M2-07). Rechte kommen aus der Rechtematrix (`tariffs:*`), Mandantentrennung aus RLS. */
@Configuration
class TariffConfiguration {
    @Bean fun tariffs(ctxTx: ContextTransactions, audit: AuditPort, outbox: Outbox): Tariffs = Tariffs.create(ctxTx, audit, outbox)
}

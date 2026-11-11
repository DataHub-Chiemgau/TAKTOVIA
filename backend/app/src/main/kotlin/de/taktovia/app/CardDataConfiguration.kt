package de.taktovia.app

import de.taktovia.app.web.CardDataBodyInspector
import de.taktovia.app.web.CardDataRequestFilter
import de.taktovia.app.web.ProblemWriter
import de.taktovia.app.web.RequestBodyInspector
import de.taktovia.platform.audit.SecurityEventPort
import de.taktovia.platform.carddata.CardDataIncidents
import de.taktovia.platform.tenant.TenantTransactions
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered

/**
 * Verdrahtung der Zahlungsdatentrennung (WP-M1-37, SEC-008): Eingangsfilter gegen kartennummernähnliche Muster in Query, Headern und Body.
 * Der Filter läuft nach den Pfad- und Herkunftsprüfungen (+3) und vor der Quote (+10); der Body-Hook läuft in der Eingabeprüfung.
 * Datenfluss und Systemgrenzen: docs/umsetzungsplan/vertraege/psp-trennung.md.
 */
@Configuration
class CardDataConfiguration {
    @Bean fun cardDataIncidents(tx: TenantTransactions, security: SecurityEventPort) = CardDataIncidents(tx, security)

    @Bean fun cardDataRequestFilter(problems: ProblemWriter, incidents: CardDataIncidents) =
        FilterRegistrationBean(CardDataRequestFilter(problems, incidents)).apply { order = Ordered.HIGHEST_PRECEDENCE + 4 }

    @Bean fun cardDataBodyInspector(incidents: CardDataIncidents): RequestBodyInspector = CardDataBodyInspector(incidents)
}

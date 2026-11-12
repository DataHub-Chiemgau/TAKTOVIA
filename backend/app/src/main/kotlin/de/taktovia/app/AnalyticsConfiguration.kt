package de.taktovia.app

import de.taktovia.analytics.api.AnalyticsApi
import de.taktovia.platform.tenant.TenantTransactions
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Faktenbasis des Moduls `analytics` (WP-M4-45). Der Verbraucher ([AnalyticsApi.facts]) wird von einem Projektionsworker mit Tenantkontext je Transaktion aufgerufen; die Anbindung an die
 * Warteschlange (Abonnement der Ereignistypen in [de.taktovia.analytics.api.FactBase]) gehört zur Betriebsverdrahtung und ist nicht Teil dieses Arbeitspakets (kein Verbraucher in P1 bis WP-M4-46).
 */
@Configuration
class AnalyticsConfiguration {
    @Bean fun analyticsApi(tx: TenantTransactions): AnalyticsApi = AnalyticsApi.create(tx)
}

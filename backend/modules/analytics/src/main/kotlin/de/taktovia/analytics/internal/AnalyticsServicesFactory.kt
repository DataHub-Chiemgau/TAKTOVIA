package de.taktovia.analytics.internal

import de.taktovia.analytics.api.AnalyticsApi
import de.taktovia.analytics.api.FactBase
import de.taktovia.platform.tenant.TenantTransactions
import java.time.Clock

/** Nur innerhalb des Moduls `analytics` sichtbar (durch Architekturtest erzwungen). */
internal object AnalyticsServicesFactory {
    fun create(tx: TenantTransactions, clock: Clock): AnalyticsApi = object : AnalyticsApi {
        override val facts: FactBase = JdbcFactBase(tx, clock)
    }
}

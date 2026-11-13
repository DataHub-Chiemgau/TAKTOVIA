package de.taktovia.analytics.internal

import de.taktovia.analytics.api.AnalyticsApi
import de.taktovia.analytics.api.FactBase
import de.taktovia.analytics.api.ReportJobs
import de.taktovia.analytics.api.TimeFacts
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.tenant.TenantTransactions
import java.time.Clock
import java.time.Duration

/** Nur innerhalb des Moduls `analytics` sichtbar (durch Architekturtest erzwungen). */
internal object AnalyticsServicesFactory {
    fun create(ctxTx: ContextTransactions, tx: TenantTransactions, audit: AuditPort, secret: ByteArray, clock: Clock, ttl: Duration, checklist: List<String>): AnalyticsApi {
        val factBase = createFacts(tx, clock)
        val jobs = JdbcReportJobs(ctxTx, tx, audit, DownloadTokens(secret), clock, ttl, checklist)
        return object : AnalyticsApi {
            override val facts: FactBase = factBase
            override val reportJobs: ReportJobs = jobs
            override val timeFacts: TimeFacts = createTimeFacts(tx)
        }
    }

    fun createFacts(tx: TenantTransactions, clock: Clock): FactBase = JdbcFactBase(tx, clock)

    fun createTimeFacts(tx: TenantTransactions): TimeFacts = JdbcTimeFacts(tx)
}

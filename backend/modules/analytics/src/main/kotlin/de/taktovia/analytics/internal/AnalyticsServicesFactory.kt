package de.taktovia.analytics.internal

import de.taktovia.analytics.api.AnalyticsApi
import de.taktovia.analytics.api.FactBase
import de.taktovia.analytics.api.KmClassification
import de.taktovia.analytics.api.ReportJobs
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
        val kmService = createKm(ctxTx, tx, clock)
        return object : AnalyticsApi {
            override val km: KmClassification = kmService
            override val facts: FactBase = factBase
            override val reportJobs: ReportJobs = jobs
        }
    }

    fun createFacts(tx: TenantTransactions, clock: Clock): FactBase = JdbcFactBase(tx, clock)

    fun createKm(ctxTx: ContextTransactions, tx: TenantTransactions, clock: Clock): KmClassification = JdbcKmClassification(ctxTx, tx, clock)
}

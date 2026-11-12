package de.taktovia.analytics.api

import de.taktovia.analytics.internal.AnalyticsServicesFactory
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.tenant.TenantTransactions
import java.time.Clock
import java.time.Duration

/** Öffentliche Ports des Moduls `analytics`; wächst mit den Arbeitspaketen (bisher: Exportaufträge `/v1/reports/jobs`, WP-M5-19). */
interface AnalyticsApi {
    /** Exportaufträge mit Empfängerrechten und Datenstand (WP-M5-19). */
    val reportJobs: ReportJobs

    companion object {
        /**
         * [downloadSecret] signiert die kurzlebigen Abruflinks (HMAC-SHA256, mindestens 32 Byte, nie im Repository); [checklist] ist die Freigabecheckliste des PBefG-Exports
         * (im Pflichtenheft nicht enthalten, OP-13: Arbeitsannahme, konfigurierbar).
         */
        fun create(
            ctxTx: ContextTransactions, tx: TenantTransactions, audit: AuditPort, downloadSecret: ByteArray, clock: Clock = Clock.systemUTC(),
            downloadTtl: Duration = Duration.ofMinutes(5), checklist: List<String> = ExportCategories.DEFAULT_PBEFG_CHECKLIST,
        ): AnalyticsApi = AnalyticsServicesFactory.create(ctxTx, tx, audit, downloadSecret, clock, downloadTtl, checklist)
    }
}

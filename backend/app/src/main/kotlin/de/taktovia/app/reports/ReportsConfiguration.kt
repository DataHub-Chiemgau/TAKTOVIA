package de.taktovia.app.reports

import de.taktovia.analytics.api.AnalyticsApi
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.outbox.TenantSource
import de.taktovia.platform.tenant.TenantTransactions
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.security.SecureRandom
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Verdrahtung der Exportaufträge (WP-M5-19, `/v1/reports/jobs`). `taktovia.reports.download-secret` (Secret-Domäne der Umgebung, mindestens 32 Zeichen) signiert die kurzlebigen Abruflinks; ohne Wert entsteht
 * je Prozess ein zufälliger Schlüssel (Links gelten dann nur auf dieser Instanz, Warnung im Log; mehrere Instanzen brauchen den gemeinsamen Wert). `taktovia.reports.download-ttl-seconds` (Planungsdefault 300).
 * Die Kilometerklassifikation (WP-M4-42) läuft als eigener Job nur mit `taktovia.analytics.km-worker-enabled=true`. Der Verarbeitungsjob läuft nur mit `taktovia.reports.worker-enabled=true` und einer [TenantSource]-Bean des Betriebs; Takt `taktovia.reports.poll-seconds` (Standard 5).
 */
@Configuration
class ReportsConfiguration {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean fun analyticsApi(
        ctxTx: ContextTransactions, tx: TenantTransactions, audit: AuditPort, tenants: ObjectProvider<TenantSource>, env: Environment,
        @Value("\${taktovia.reports.download-secret:}") secret: String, @Value("\${taktovia.reports.download-ttl-seconds:300}") ttlSeconds: Long,
    ): AnalyticsApi {
        val key = if (secret.length >= 32) secret.toByteArray(Charsets.UTF_8) else {
            log.warn("taktovia.reports.download-secret fehlt oder ist kürzer als 32 Zeichen: zufälliger Schlüssel dieser Instanz, Abruflinks gelten nur hier")
            ByteArray(32).also { SecureRandom().nextBytes(it) }
        }
        val api = AnalyticsApi.create(ctxTx, tx, audit, key, downloadTtl = Duration.ofSeconds(ttlSeconds))
        val source = tenants.getIfAvailable()
        if (source != null && env.getProperty("taktovia.reports.worker-enabled", Boolean::class.java, false)) {
            val poll = env.getProperty("taktovia.reports.poll-seconds", Long::class.java, 5)
            Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "report-jobs").also { it.isDaemon = true } }.scheduleWithFixedDelay({
                runCatching { source.tenants().distinct().forEach { t -> api.reportJobs.runPending(t) } }.onFailure { log.warn("Exportaufträge: Lauf fehlgeschlagen ({})", it.javaClass.simpleName) }
            }, poll, poll, TimeUnit.SECONDS)
        }
        // Klassifikationsjob der Fahrzeugkilometer (WP-M4-42): nur mit `taktovia.analytics.km-worker-enabled=true`; klassifiziert den Vortag und den laufenden Tag (UTC), Takt `taktovia.analytics.km-poll-seconds` (Standard 900)
        if (source != null && env.getProperty("taktovia.analytics.km-worker-enabled", Boolean::class.java, false)) {
            val poll = env.getProperty("taktovia.analytics.km-poll-seconds", Long::class.java, 900)
            Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "km-classification").also { it.isDaemon = true } }.scheduleWithFixedDelay({
                runCatching {
                    val today = java.time.LocalDate.now(java.time.ZoneOffset.UTC)
                    source.tenants().distinct().forEach { t -> listOf(today.minusDays(1), today).forEach { d -> api.km.classifyDay(t, d) } }
                }.onFailure { log.warn("Kilometerklassifikation: Lauf fehlgeschlagen ({})", it.javaClass.simpleName) }
            }, poll, poll, TimeUnit.SECONDS)
        }
        return api
    }
}

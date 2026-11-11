package de.taktovia.app

import de.taktovia.platform.alert.OperatorAlertService
import de.taktovia.platform.security.IncidentRules
import de.taktovia.platform.security.SecurityIncidentMonitor
import de.taktovia.platform.tenant.TenantTransactions
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Alarmregeln auf Sicherheitsereignisse (WP-M1-41): gehäufte Fremdzugriffe, MFA-Fehlschläge, veränderter Payload bei gleicher Ereignis-ID.
 * Schwellwerte und Fenster sind Konfiguration (Planungswerte, OP-32); der Alarm geht über den Betriebsalarmkanal (WP-M1-47) an den
 * Empfänger der Umgebung. Der Monitor ist zugleich der `SecurityEventListener` des `SecurityEventPort` und wertet nach COMMIT markierte Mandanten
 * im Takt von `taktovia.security.incident.interval-seconds` aus.
 */
@Configuration
class SecurityIncidentConfig {
    @Bean
    fun securityIncidentMonitor(
        tx: TenantTransactions, alerts: OperatorAlertService,
        @Value("\${taktovia.security.incident.cross-tenant-threshold:3}") crossTenant: Int,
        @Value("\${taktovia.security.incident.mfa-failure-threshold:5}") mfa: Int,
        @Value("\${taktovia.security.incident.payload-change-threshold:1}") payload: Int,
        @Value("\${taktovia.security.incident.window-minutes:10}") windowMinutes: Long,
        @Value("\${taktovia.security.incident.interval-seconds:10}") intervalSeconds: Long,
    ): SecurityIncidentMonitor {
        val monitor = SecurityIncidentMonitor(tx, alerts, IncidentRules.defaults(crossTenant, mfa, payload, Duration.ofMinutes(windowMinutes)))
        val log = LoggerFactory.getLogger("taktovia.security")
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "security-incident-monitor").also { it.isDaemon = true } }
            .scheduleWithFixedDelay({ runCatching { monitor.drain() }.onFailure { log.warn("Sicherheitsalarmregeln nicht auswertbar: {}", it.javaClass.simpleName) } },
                intervalSeconds, intervalSeconds, TimeUnit.SECONDS)
        return monitor
    }
}

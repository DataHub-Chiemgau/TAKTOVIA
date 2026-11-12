package de.taktovia.app.observability

import de.taktovia.platform.outbox.TenantSource
import de.taktovia.platform.tenant.TenantTransactions
import de.taktovia.platform.telemetry.LabelPolicyFilter
import de.taktovia.platform.telemetry.PlatformMetrics
import de.taktovia.platform.telemetry.QueueMetrics
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.observation.ObservationFilter
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.env.Environment
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Telemetrie des Fachkerns (WP-M5-03, docs/betrieb/telemetrie.md): Labelschutz für Metriken, Maskierung der Trace-Attribute, Latenz je Endpunktklasse und
 * Warteschlangenkennzahlen je Mandant (Zeitgeber nur mit einer [TenantSource]-Bean des Betriebs, wie bei den Workern OP-06/OP-08).
 * Die Prometheus-Ansicht liegt auf dem Verwaltungsport (`TAKTOVIA_MANAGEMENT_PORT`), getrennt vom Mandanten-API-Port (Betriebszugang ≠ Mandantenrollen).
 */
@Configuration
class TelemetryConfiguration {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean fun labelPolicyFilter() = LabelPolicyFilter()

    @Bean fun maskingObservationFilter(): ObservationFilter = MaskingObservationFilter()

    @Bean fun platformMetrics(registry: MeterRegistry) = PlatformMetrics(registry)

    @Bean fun httpMetricsFilter(metrics: PlatformMetrics) = FilterRegistrationBean(HttpMetricsFilter(metrics)).apply { order = Ordered.HIGHEST_PRECEDENCE + 5 }

    @Bean fun queueMetrics(tx: TenantTransactions, tenants: ObjectProvider<TenantSource>, registry: MeterRegistry, env: Environment): QueueMetrics {
        val qm = QueueMetrics(tx, { tenants.getIfAvailable { TenantSource { emptyList() } }.tenants() }, registry)
        if (tenants.getIfAvailable() != null && env.getProperty("taktovia.telemetry.queue-metrics-enabled", Boolean::class.java, true)) {
            val every = env.getProperty("taktovia.telemetry.queue-metrics-seconds", Long::class.java, 15)
            Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "queue-metrics").also { it.isDaemon = true } }
                .scheduleWithFixedDelay({ runCatching { qm.refresh() }.onFailure { log.warn("Warteschlangenmetriken: Lauf fehlgeschlagen ({})", it.javaClass.simpleName) } }, every, every, TimeUnit.SECONDS)
        }
        return qm
    }
}

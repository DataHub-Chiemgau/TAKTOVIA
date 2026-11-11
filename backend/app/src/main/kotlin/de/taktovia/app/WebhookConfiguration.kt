package de.taktovia.app

import de.taktovia.platform.alert.OperatorAlertPort
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.net.SsrfGuard
import de.taktovia.platform.outbox.TenantSource
import de.taktovia.platform.secret.SecretResolver
import de.taktovia.platform.tenant.TenantTransactions
import de.taktovia.platform.webhook.DeliveryConfig
import de.taktovia.platform.webhook.PinnedHttpTransport
import de.taktovia.platform.webhook.WebhookDeliveryWorker
import de.taktovia.platform.webhook.WebhookFanout
import de.taktovia.platform.webhook.WebhookSubscriptionService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Verdrahtung der Webhook-Grundlage (WP-M1-16). Geheimnisdomäne der Umgebung: `taktovia.secrets.domain`, sonst `taktovia-<umgebung>-secrets`. Der Zustellworker
 * läuft nur mit Secret-Verwaltung (`taktovia.secrets.dir`), und sein Zeitgeber nur mit `taktovia.webhooks.worker-enabled=true` und einer [TenantSource]-Bean des Betriebs
 * (OP-06/OP-08). Planungsdefaults: `taktovia.webhooks.max-attempts` 12, `timeout-seconds` 10, `poll-seconds` 5.
 */
@Configuration
class WebhookConfiguration {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean fun webhookSubscriptionService(audit: AuditPort, ssrf: SsrfGuard, env: Environment) =
        WebhookSubscriptionService(audit, ssrf, env.getProperty("taktovia.secrets.domain") ?: "taktovia-${env.getProperty("taktovia.environment", "local")}-secrets")

    @Bean fun webhookFanout(tx: TenantTransactions) = WebhookFanout(tx)

    @Bean @ConditionalOnProperty("taktovia.secrets.dir")
    fun webhookDeliveryWorker(tx: TenantTransactions, secrets: SecretResolver, ssrf: SsrfGuard, alerts: OperatorAlertPort, fanout: WebhookFanout, tenants: ObjectProvider<TenantSource>, env: Environment): WebhookDeliveryWorker {
        val worker = WebhookDeliveryWorker(tx, PinnedHttpTransport(), secrets, ssrf, alerts, java.time.Clock.systemUTC(), DeliveryConfig(
            maxAttempts = env.getProperty("taktovia.webhooks.max-attempts", Int::class.java, 12), timeout = Duration.ofSeconds(env.getProperty("taktovia.webhooks.timeout-seconds", Long::class.java, 10))))
        val source = tenants.getIfAvailable()
        if (source != null && env.getProperty("taktovia.webhooks.worker-enabled", Boolean::class.java, false)) {
            val poll = env.getProperty("taktovia.webhooks.poll-seconds", Long::class.java, 5)
            Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "webhook-delivery").also { it.isDaemon = true } }.scheduleWithFixedDelay({
                runCatching { source.tenants().distinct().forEach { t -> fanout.fanOut(t); worker.runTenant(t) } }.onFailure { log.warn("Webhook-Zustellung: Lauf fehlgeschlagen ({})", it.javaClass.simpleName) }
            }, poll, poll, TimeUnit.SECONDS)
        }
        return worker
    }
}

package de.taktovia.app

import de.taktovia.app.openapi.OpenApiRegistry
import de.taktovia.app.web.IdempotencyFilter
import de.taktovia.app.web.ProblemWriter
import de.taktovia.app.web.RemoteAddressIdentity
import de.taktovia.app.web.RequestIdentityResolver
import de.taktovia.platform.idempotency.IdempotencyCleanup
import de.taktovia.platform.idempotency.IdempotencyConfig
import de.taktovia.platform.idempotency.IdempotencyRegistry
import de.taktovia.platform.idempotency.IdempotencyService
import de.taktovia.platform.outbox.TenantSource
import de.taktovia.platform.tenant.TenantTransactions
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.env.Environment
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Verdrahtung des Idempotenzregisters (WP-M1-13). Einstellungen (`taktovia.idempotency.*`, Planungsdefaults): `response-ttl-hours` 72 (Entwurfswert des
 * Pflichtenhefts), `lease-seconds` 120, `in-progress-wait-millis` 10000, `cleanup-minutes` 10 (nur mit einer [TenantSource]; ohne sie
 * ist die Bereinigung Sache des Betriebs, siehe OP-06/OP-08).
 */
@Configuration
class IdempotencyConfiguration {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean fun idempotencyRegistry(tx: TenantTransactions) = IdempotencyRegistry(tx)

    @Bean fun idempotencyService(registry: IdempotencyRegistry, env: Environment) = IdempotencyService(registry, java.time.Clock.systemUTC(), IdempotencyConfig(
        responseTtl = Duration.ofHours(env.getProperty("taktovia.idempotency.response-ttl-hours", Long::class.java, 72)),
        lease = Duration.ofSeconds(env.getProperty("taktovia.idempotency.lease-seconds", Long::class.java, 120)),
        inProgressWait = Duration.ofMillis(env.getProperty("taktovia.idempotency.in-progress-wait-millis", Long::class.java, 10_000)),
        poll = Duration.ofMillis(50),
    ))

    /** Nach Eingabeprüfung (+20), vor dem Controller. */
    @Bean fun idempotencyFilter(registry: OpenApiRegistry, service: IdempotencyService, identity: ObjectProvider<RequestIdentityResolver>, problems: ProblemWriter) =
        FilterRegistrationBean(IdempotencyFilter(registry, service, { identity.getIfAvailable { RemoteAddressIdentity } }, problems)).apply { order = Ordered.HIGHEST_PRECEDENCE + 25 }

    /** Bereinigung abgelaufener Einträge, sobald der Betrieb eine Mandantenquelle bereitstellt. */
    @Bean fun idempotencyCleanupJob(registry: IdempotencyRegistry, tenants: ObjectProvider<TenantSource>, env: Environment): IdempotencyCleanupJob {
        val source = tenants.getIfAvailable() ?: return IdempotencyCleanupJob(null)
        val cleanup = IdempotencyCleanup(registry, source)
        val minutes = env.getProperty("taktovia.idempotency.cleanup-minutes", Long::class.java, 10)
        val executor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "idempotency-cleanup").also { it.isDaemon = true } }
        executor.scheduleWithFixedDelay({ runCatching { cleanup.runOnce() }.onFailure { log.warn("Idempotenz-Bereinigung fehlgeschlagen ({})", it.javaClass.simpleName) } }, minutes, minutes, TimeUnit.MINUTES)
        return IdempotencyCleanupJob(cleanup)
    }
}

/** Marker für die laufende Bereinigung (leer ohne Mandantenquelle). */
class IdempotencyCleanupJob(val cleanup: IdempotencyCleanup?)

package de.taktovia.app

import de.taktovia.platform.operation.OperationConfig
import de.taktovia.platform.operation.OperationHandler
import de.taktovia.platform.operation.OperationRunner
import de.taktovia.platform.operation.OperationService
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.outbox.TenantSource
import de.taktovia.platform.tenant.TenantTransactions
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Verdrahtung der `operation`-Ressource (WP-M1-15). Einstellungen (`taktovia.operations.*`, Planungsdefaults): `lease-seconds` 60,
 * `default-retry-after-seconds` 3, `reconcile-delay-seconds` 5, `poll-seconds` 2 (nur mit `worker-enabled=true` und einer [TenantSource]-Bean des Betriebs,
 * wie beim Outbox-Relay OP-06/OP-08). Module tragen ihre [OperationHandler] als Beans bei.
 */
@Configuration
class OperationConfiguration {
    private val log = LoggerFactory.getLogger(javaClass)

    private fun config(env: Environment) = OperationConfig(
        lease = Duration.ofSeconds(env.getProperty("taktovia.operations.lease-seconds", Long::class.java, 60)),
        defaultRetryAfterSeconds = env.getProperty("taktovia.operations.default-retry-after-seconds", Long::class.java, 3),
        reconcileDelay = Duration.ofSeconds(env.getProperty("taktovia.operations.reconcile-delay-seconds", Long::class.java, 5)),
    )

    @Bean fun operationService(env: Environment) = OperationService(Clock.systemUTC(), config(env))

    @Bean fun operationRunner(tx: TenantTransactions, outbox: Outbox, handlers: ObjectProvider<OperationHandler>, tenants: ObjectProvider<TenantSource>, env: Environment): OperationRunner {
        val runner = OperationRunner(tx, handlers.orderedStream().toList(), outbox, Clock.systemUTC(), config(env), "worker-" + UUID.randomUUID())
        val source = tenants.getIfAvailable()
        if (source != null && env.getProperty("taktovia.operations.worker-enabled", Boolean::class.java, false)) {
            val poll = env.getProperty("taktovia.operations.poll-seconds", Long::class.java, 2)
            Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "operation-runner").also { it.isDaemon = true } }
                .scheduleWithFixedDelay({ runCatching { runner.runOnce(source) }.onFailure { log.warn("Operation-Runner: Lauf fehlgeschlagen ({})", it.javaClass.simpleName) } }, poll, poll, TimeUnit.SECONDS)
        }
        return runner
    }
}

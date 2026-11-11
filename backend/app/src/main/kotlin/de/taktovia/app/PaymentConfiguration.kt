package de.taktovia.app

import de.taktovia.adapters.psp.api.PspAdapter
import de.taktovia.farepayment.api.ProviderEventConfig
import de.taktovia.farepayment.api.ProviderEventKey
import de.taktovia.farepayment.api.ProviderEventKeys
import de.taktovia.farepayment.api.ProviderEvents
import de.taktovia.platform.audit.SecurityEventPort
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.secret.SecretRef
import de.taktovia.platform.secret.SecretResolver
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.ResolvableType
import org.springframework.core.env.Environment
import java.net.URI
import java.time.Duration
import java.time.Instant

/** Konfiguration eines Signaturschlüssels eines Providers: nur **Referenzen** auf die Secret-Verwaltung, nie Werte (Kap. 01 §1.8). */
class ProviderKeyProperties {
    /** Mandant, dem dieser Schlüssel zugeordnet ist (Ereignisse landen nur in diesem Mandanten). */
    var tenant: String? = null
    var secretRef: String? = null
    /** Vorheriger Schlüssel während der Rotation, gültig bis [previousValidUntil]. */
    var previousSecretRef: String? = null
    var previousValidUntil: String? = null
}

/**
 * Verdrahtung von Zahlungsadapter und Provider-Ereigniseingang (WP-M2-29). Schlüssel je Provider und Mandant:
 * `taktovia.payments.providers.<provider>[<n>].tenant` und `.secret-ref` (`secret://<domäne>/<name>`), bei Rotation zusätzlich `.previous-secret-ref` und `.previous-valid-until`.
 * Die Werte löst [SecretResolver] bei **jeder** Prüfung neu auf (Rotation ohne Neustart); ist die Secret-Verwaltung nicht konfiguriert oder fehlt ein Wert, gilt der Schlüssel nicht
 * (die Anfrage wird mit 401 abgewiesen, nie ohne Prüfung angenommen). PSP-Adapter nur mit `taktovia.psp.base-url` (Simulator oder Sandbox; Produktivschaltung blockiert D-03).
 */
@Configuration
class PaymentConfiguration {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun providerEventKeys(env: Environment, secrets: ObjectProvider<SecretResolver>): ProviderEventKeys {
        val type = ResolvableType.forClassWithGenerics(Map::class.java, ResolvableType.forClass(String::class.java), ResolvableType.forClassWithGenerics(List::class.java, ProviderKeyProperties::class.java))
        @Suppress("UNCHECKED_CAST") val bound = Binder.get(env).bind("taktovia.payments.providers", Bindable.of<Any>(type) as Bindable<Map<String, List<ProviderKeyProperties>>>)
        val configured: Map<String, List<ProviderKeyProperties>> = if (bound.isBound) bound.get() else emptyMap()
        return ProviderEventKeys { provider ->
            val resolver = secrets.getIfAvailable()
            if (resolver == null) emptyList() else configured[provider].orEmpty().flatMap { p ->
                val tenant = runCatching { TenantId.parse(p.tenant!!) }.getOrNull() ?: return@flatMap emptyList<ProviderEventKey>()
                fun key(ref: String?, until: Instant?): ProviderEventKey? = runCatching { ProviderEventKey(tenant, provider, resolver.resolve(SecretRef.requireRef("taktovia.payments.providers", ref)).reveal().toByteArray(), until) }
                    .onFailure { log.error("Signaturschlüssel des Providers nicht verfügbar: {}", it.javaClass.simpleName) }.getOrNull()
                listOfNotNull(key(p.secretRef, null), p.previousSecretRef?.let { key(it, p.previousValidUntil?.let(Instant::parse)) })
            }
        }
    }

    @Bean
    fun providerEvents(tx: TenantTransactions, keys: ProviderEventKeys, security: SecurityEventPort, outbox: Outbox, env: Environment): ProviderEvents =
        ProviderEvents.create(tx, keys, security, outbox, ProviderEventConfig(maxBodyBytes = env.getProperty("taktovia.payments.max-event-bytes", Int::class.java, 64 * 1024),
            tolerance = Duration.ofSeconds(env.getProperty("taktovia.payments.timestamp-tolerance-seconds", Long::class.java, 300))))

    @Bean @ConditionalOnProperty("taktovia.psp.base-url")
    fun pspAdapter(env: Environment, secrets: ObjectProvider<SecretResolver>): PspAdapter {
        val keyRef = env.getProperty("taktovia.psp.api-key-ref")
        return PspAdapter.http(env.getRequiredProperty("taktovia.psp.provider"), URI(env.getRequiredProperty("taktovia.psp.base-url")),
            Duration.ofSeconds(env.getProperty("taktovia.psp.timeout-seconds", Long::class.java, 10))) { keyRef?.let { secrets.getObject().resolve(it).reveal() } }
    }
}

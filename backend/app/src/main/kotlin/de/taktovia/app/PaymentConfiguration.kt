package de.taktovia.app

import de.taktovia.adapters.psp.api.PspAdapter
import de.taktovia.farepayment.api.ProviderEventConfig
import de.taktovia.farepayment.api.ProviderEventKey
import de.taktovia.farepayment.api.ProviderEventKeys
import de.taktovia.farepayment.api.FareQuotes
import de.taktovia.farepayment.api.PaymentConfig
import de.taktovia.farepayment.api.PaymentGateway
import de.taktovia.farepayment.api.Payments
import de.taktovia.farepayment.api.ProviderEvents
import de.taktovia.app.payment.PaymentBookingCoupling
import de.taktovia.app.payment.PspPaymentGateway
import de.taktovia.bookingdispatch.api.Bookings
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.operation.OperationHandler
import de.taktovia.platform.outbox.TenantSource
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
    fun providerEvents(tx: TenantTransactions, keys: ProviderEventKeys, security: SecurityEventPort, outbox: Outbox, env: Environment, payments: Payments): ProviderEvents =
        ProviderEvents.create(tx, keys, security, outbox, ProviderEventConfig(maxBodyBytes = env.getProperty("taktovia.payments.max-event-bytes", Int::class.java, 64 * 1024),
            tolerance = Duration.ofSeconds(env.getProperty("taktovia.payments.timestamp-tolerance-seconds", Long::class.java, 300))), payments = payments)

    /**
     * Zahlungsautomat (WP-M2-30). Planungsdefaults: `taktovia.payments.authorization-window-seconds` 180 (Hold-Dauer), `taktovia.payments.operation-deadline-minutes` 15,
     * `taktovia.payments.settlement-poll-seconds` 30; `taktovia.psp.provider` ist der Provider der Zahlungen (ohne Angabe `unconfigured`: dann gibt es keinen Adapter und keine Handler).
     */
    @Bean
    fun payments(tx: TenantTransactions, ctxTx: ContextTransactions, quotes: FareQuotes, outbox: Outbox, env: Environment): Payments =
        Payments.create(tx, ctxTx, quotes, outbox, PaymentConfig(
            provider = env.getProperty("taktovia.psp.provider", "unconfigured"),
            authorizationWindow = Duration.ofSeconds(env.getProperty("taktovia.payments.authorization-window-seconds", Long::class.java, 180)),
            operationDeadline = Duration.ofMinutes(env.getProperty("taktovia.payments.operation-deadline-minutes", Long::class.java, 15)),
            settlementPoll = Duration.ofSeconds(env.getProperty("taktovia.payments.settlement-poll-seconds", Long::class.java, 30)),
        ))

    @Bean @ConditionalOnProperty("taktovia.psp.base-url")
    fun paymentGateway(psp: PspAdapter): PaymentGateway = PspPaymentGateway(psp)

    // Die Handler der Zahlungsoperationen: nur mit PSP-Adapter. Der Operation-Runner (OperationConfiguration) sammelt alle OperationHandler-Beans.
    @Bean @ConditionalOnProperty("taktovia.psp.base-url") fun authorizePaymentHandler(payments: Payments, gateway: PaymentGateway): OperationHandler = payments.handlers(gateway).first { it.kind == "AUTHORIZE_PAYMENT" }
    @Bean @ConditionalOnProperty("taktovia.psp.base-url") fun capturePaymentHandler(payments: Payments, gateway: PaymentGateway): OperationHandler = payments.handlers(gateway).first { it.kind == "CAPTURE_PAYMENT" }
    @Bean @ConditionalOnProperty("taktovia.psp.base-url") fun voidPaymentHandler(payments: Payments, gateway: PaymentGateway): OperationHandler = payments.handlers(gateway).first { it.kind == "VOID_PAYMENT" }
    @Bean @ConditionalOnProperty("taktovia.psp.base-url") fun reconcilePaymentHandler(payments: Payments, gateway: PaymentGateway): OperationHandler = payments.handlers(gateway).first { it.kind == "RECONCILE_PAYMENT" }

    @Bean
    fun paymentBookingCoupling(tx: TenantTransactions, payments: Payments, bookings: Bookings, tenants: ObjectProvider<TenantSource>, env: Environment): PaymentBookingCoupling {
        val coupling = PaymentBookingCoupling(tx, payments, bookings)
        val source = tenants.getIfAvailable()
        if (source != null && env.getProperty("taktovia.payments.coupling-enabled", Boolean::class.java, false)) {
            val poll = env.getProperty("taktovia.payments.coupling-poll-seconds", Long::class.java, 2)
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "payment-coupling").also { it.isDaemon = true } }
                .scheduleWithFixedDelay({ source.tenants().distinct().forEach { t -> runCatching { coupling.runTenant(t) }.onFailure { log.warn("Zahlungskopplung: Lauf fehlgeschlagen ({})", it.javaClass.simpleName) } } }, poll, poll, java.util.concurrent.TimeUnit.SECONDS)
        }
        return coupling
    }

    @Bean @ConditionalOnProperty("taktovia.psp.base-url")
    fun pspAdapter(env: Environment, secrets: ObjectProvider<SecretResolver>): PspAdapter {
        val keyRef = env.getProperty("taktovia.psp.api-key-ref")
        return PspAdapter.http(env.getRequiredProperty("taktovia.psp.provider"), URI(env.getRequiredProperty("taktovia.psp.base-url")),
            Duration.ofSeconds(env.getProperty("taktovia.psp.timeout-seconds", Long::class.java, 10))) { keyRef?.let { secrets.getObject().resolve(it).reveal() } }
    }
}

package de.taktovia.app

import de.taktovia.platform.transport.Endpoint
import de.taktovia.platform.transport.TransportPolicy
import org.springframework.beans.factory.InitializingBean
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

/**
 * Verweigert den Start mit Klartext-Konfiguration (LH-6.18-SEC-002, WP-M1-36): PostgreSQL, RabbitMQ, Redis, Objektspeicher und IdP
 * nur über TLS, die Anwendung selbst nur mit TLS oder hinter einem Gateway, das TLS terminiert (`taktovia.transport.tls-at-gateway`).
 * Ausnahme ist ausschließlich `taktovia.environment=local` (lokale Entwicklung, Testläufe); fehlt die Angabe, gilt die strenge Prüfung.
 */
@Component
class TransportGuard(private val env: Environment) : InitializingBean {
    override fun afterPropertiesSet() {
        val problems = violations()
        check(problems.isEmpty()) { "Klartext-Konfiguration abgewiesen: ${problems.joinToString("; ")}" }
    }

    fun violations(): List<String> {
        if (env.getProperty("taktovia.environment") == "local") return emptyList()
        val endpoints = listOf(
            Triple(Endpoint.POSTGRES, "spring.datasource.url", env.getProperty("spring.datasource.url")),
            Triple(Endpoint.RABBITMQ, "taktovia.rabbitmq.uri", env.getProperty("taktovia.rabbitmq.uri")),
            Triple(Endpoint.REDIS, "taktovia.redis.uri", env.getProperty("taktovia.redis.uri")),
            Triple(Endpoint.OBJECT_STORE, "taktovia.objectstore.endpoint", env.getProperty("taktovia.objectstore.endpoint")),
            Triple(Endpoint.IDP, "taktovia.idp.issuer-uri", env.getProperty("taktovia.idp.issuer-uri")),
        )
        return buildList {
            addAll(TransportPolicy.check(endpoints))
            if (env.getProperty("server.ssl.enabled", Boolean::class.java, false).not() &&
                !env.getProperty("taktovia.transport.tls-at-gateway", Boolean::class.java, false))
                add("server.ssl.enabled=false ohne taktovia.transport.tls-at-gateway=true")
        }
    }
}

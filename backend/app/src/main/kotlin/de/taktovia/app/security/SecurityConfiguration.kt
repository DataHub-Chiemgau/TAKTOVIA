package de.taktovia.app.security

import de.taktovia.app.openapi.OpenApiRegistry
import de.taktovia.app.web.ProblemWriter
import de.taktovia.app.web.RequestIdentityResolver
import de.taktovia.platform.audit.ActorRef
import de.taktovia.platform.audit.AuditActions
import de.taktovia.platform.audit.ObjectRef
import de.taktovia.platform.audit.SecurityEvent
import de.taktovia.platform.audit.SecurityEventPort
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.InitializingBean
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.core.Ordered
import org.springframework.core.env.Environment
import java.net.URI

/**
 * Verdrahtung des Ressourcenservers (WP-M1-04). Standard: eingeschaltet (`taktovia.idp.enabled`, Vorgabe `true`); ohne Issuer startet die
 * Anwendung nicht. Ausschalten ist nur mit `taktovia.environment=local` zulässig (lokale Entwicklung und Tests), nie in einer anderen Umgebung.
 */
@Configuration
class SecurityConfiguration {
    @Bean
    fun idpGuard(env: Environment) = IdpGuard(env)

    @Bean @ConditionalOnProperty("taktovia.idp.enabled", havingValue = "true", matchIfMissing = true)
    fun idpProperties(env: Environment, @Suppress("UNUSED_PARAMETER") guard: IdpGuard) = IdpProperties.from(env)

    @Bean @ConditionalOnProperty("taktovia.idp.enabled", havingValue = "true", matchIfMissing = true)
    fun jwksKeySource(p: IdpProperties): JwksKeySource = HttpJwksKeySource(URI(p.jwksUri), p.jwksCacheSeconds, p.jwksMinRefreshSeconds, p.maxStaleSeconds)

    @Bean @ConditionalOnProperty("taktovia.idp.enabled", havingValue = "true", matchIfMissing = true)
    fun tokenVerifier(p: IdpProperties, keys: JwksKeySource) = TokenVerifier(p, keys)

    @Bean @ConditionalOnProperty("taktovia.idp.enabled", havingValue = "true", matchIfMissing = true)
    fun mfaDenialReporter(tx: TenantTransactions, events: SecurityEventPort): MfaDenialReporter = TenantMfaDenialReporter(tx, events)

    @Bean @ConditionalOnProperty("taktovia.idp.enabled", havingValue = "true", matchIfMissing = true)
    fun authenticationFilter(
        verifier: TokenVerifier, p: IdpProperties, registry: OpenApiRegistry, directory: ObjectProvider<PrivilegedRoleDirectory>,
        reporter: MfaDenialReporter, problems: ProblemWriter,
    ) = FilterRegistrationBean(AuthenticationFilter(verifier, p, registry, directory.getIfAvailable { PrivilegedRoleDirectory.None }, reporter, problems))
        // nach Correlation, Header, Pfad- und Herkunftsprüfung, vor der Quote (Quote je Mandant und Akteur braucht den geprüften Akteur)
        .apply { order = Ordered.HIGHEST_PRECEDENCE + 5 }

    /** Mandant und Akteur der Quote aus dem geprüften Token (nie aus Headern). */
    @Bean @Primary @ConditionalOnProperty("taktovia.idp.enabled", havingValue = "true", matchIfMissing = true)
    fun tokenIdentity(): RequestIdentityResolver = TokenIdentity
}

object TokenIdentity : RequestIdentityResolver {
    override fun tenant(request: HttpServletRequest) = AuthenticatedPrincipal.of(request)?.tenantIds?.singleOrNull()?.toString() ?: "-"
    override fun actor(request: HttpServletRequest) = AuthenticatedPrincipal.of(request)?.let { "${it.type.name.lowercase()}:${it.subject}" } ?: request.remoteAddr ?: "unknown"
}

/** Schreibt das Sicherheitsereignis in den Mandanten, den das Token als Hinweis nennt (höchstens 5); ohne Mandant nur als Logzeile (OP-14). */
class TenantMfaDenialReporter(private val tx: TenantTransactions, private val events: SecurityEventPort) : MfaDenialReporter {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun report(principal: AuthenticatedPrincipal, request: HttpServletRequest, privilegedVia: String) {
        val correlation = de.taktovia.app.web.Correlation.of(request)
        val event = SecurityEvent(
            actor = ActorRef.user(principal.subject), action = AuditActions.SECURITY_MFA_MISSING, obj = ObjectRef("user", principal.subject), correlationId = correlation,
            details = mapOf("required_acr" to AuthenticatedPrincipal.ACR_MFA, "presented_acr" to principal.acr, "client_id" to principal.clientId, "privileged_via" to privilegedVia),
        )
        val tenants = principal.tenantIds.take(5)
        if (tenants.isEmpty()) return events.reportWithoutTenant(event)
        var written = 0
        tenants.forEach { t ->
            runCatching { tx.run(TenantId(t)) { j -> events.report(j, event) } }
                .onSuccess { written++ }
                .onFailure { log.warn("Sicherheitsereignis für Mandant {} nicht geschrieben ({})", t, it.javaClass.simpleName) }
        }
        if (written == 0) events.reportWithoutTenant(event)
    }
}

/** Startprüfung: Issuer gesetzt, JWKS nur über TLS (außer lokal), Ausschalten nur lokal. */
class IdpGuard(private val env: Environment) : InitializingBean {
    override fun afterPropertiesSet() {
        val local = env.getProperty("taktovia.environment") == "local"
        val enabled = env.getProperty("taktovia.idp.enabled", Boolean::class.java, true)
        if (!enabled) { check(local) { "taktovia.idp.enabled=false ist nur mit taktovia.environment=local zulässig" }; return }
        val issuer = env.getProperty("taktovia.idp.issuer-uri")?.trim().orEmpty()
        check(issuer.isNotEmpty()) { "taktovia.idp.issuer-uri fehlt: ohne Issuer startet der Ressourcenserver nicht" }
        if (!local) {
            val jwks = env.getProperty("taktovia.idp.jwks-uri")?.takeIf { it.isNotBlank() } ?: issuer
            check(issuer.startsWith("https://") && jwks.startsWith("https://")) { "Issuer und JWKS-Adresse nur über https (taktovia.environment != local)" }
        }
    }
}

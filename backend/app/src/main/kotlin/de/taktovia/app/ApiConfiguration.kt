package de.taktovia.app

import de.taktovia.app.openapi.OpenApiRegistry
import de.taktovia.app.openapi.SemanticKeyword
import de.taktovia.app.openapi.TimeKeyword
import de.taktovia.app.web.CorrelationFilter
import de.taktovia.app.web.ProblemWriter
import de.taktovia.app.web.OriginPolicyFilter
import de.taktovia.app.web.PathHardeningFilter
import de.taktovia.app.web.RateLimitFilter
import de.taktovia.app.web.RemoteAddressIdentity
import de.taktovia.app.web.RequestIdentityResolver
import de.taktovia.app.web.RequestLimits
import de.taktovia.app.web.RequestBodyInspector
import de.taktovia.app.web.RequestValidationFilter
import de.taktovia.app.web.SecurityHeadersFilter
import de.taktovia.platform.api.CursorCodec
import de.taktovia.platform.api.InMemoryRateLimitStore
import de.taktovia.platform.api.Quota
import de.taktovia.platform.api.RateLimitStore
import de.taktovia.platform.api.RateLimiter
import de.taktovia.platform.cache.RedisRateLimitStore
import de.taktovia.platform.money.CurrencyCatalog
import de.taktovia.platform.money.JdbcCurrencyCatalog
import de.taktovia.platform.money.MoneyValidator
import de.taktovia.platform.net.SsrfGuard
import de.taktovia.platform.tenant.TenantTransactions
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import de.taktovia.app.web.ProblemErrorValveCustomizer
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory
import org.springframework.boot.web.server.WebServerFactoryCustomizer
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.env.Environment
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import tools.jackson.core.StreamReadConstraints
import tools.jackson.core.json.JsonFactory
import tools.jackson.databind.json.JsonMapper
import java.security.SecureRandom
import javax.sql.DataSource

/**
 * Verdrahtung des API-Grundgerüsts (WP-M1-02): Filterkette Correlation → Quote → Eingabeprüfung, Problem-Schreiber,
 * OpenAPI-Register, Cursor, Geldprüfung. Einstellungen (Planungsdefaults, OP-20) unter `taktovia.api.*`.
 */
@Configuration
class ApiConfiguration {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Eigener Mapper für Anfrage-Body und Problem-Antworten, mit Tiefen- und Längenlimit. */
    @Bean fun apiJson(env: Environment): JsonMapper {
        val depth = env.getProperty("taktovia.api.max-json-depth", Int::class.java, 32)
        val factory = JsonFactory.builder().streamReadConstraints(
            StreamReadConstraints.builder().maxNestingDepth(depth).maxStringLength(env.getProperty("taktovia.api.max-string-length", Int::class.java, 100_000))
                .maxNumberLength(50).build()).build()
        // Doppelte Schlüssel (`{"role":"a","role":"b"}`) und Text nach dem JSON-Wert sind Angriffsmuster: abweisen statt „letzter gewinnt“.
        return JsonMapper.builder(factory).enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build()
    }

    @Bean fun requestLimits(env: Environment) = RequestLimits(
        env.getProperty("taktovia.api.max-body-bytes", Int::class.java, 1_048_576), env.getProperty("taktovia.api.max-json-depth", Int::class.java, 32))

    @Bean fun problemWriter(apiJson: JsonMapper) = ProblemWriter(apiJson)

    @Bean fun currencyCatalog(dataSource: DataSource): CurrencyCatalog = JdbcCurrencyCatalog(dataSource)

    /** Zulässige Ziele ausgehender Aufrufe (Webhooks): https, öffentliche Adressen, freigegebene Ports (`taktovia.security.ssrf.*`). */
    @Bean fun ssrfGuard(env: Environment) = SsrfGuard(
        allowedPorts = (env.getProperty("taktovia.security.ssrf.allowed-ports") ?: "443").split(',').map { it.trim().toInt() }.toSet(),
        allowHttp = env.getProperty("taktovia.security.ssrf.allow-http", Boolean::class.java, false),
    )

    @Bean fun moneyValidator(catalog: CurrencyCatalog) = MoneyValidator(catalog)

    @Bean fun tenantTransactions(dataSource: DataSource) = TenantTransactions(dataSource)

    @Bean fun openApiRegistry(money: MoneyValidator, ssrf: SsrfGuard, env: Environment, semantic: ObjectProvider<SemanticKeywords>): OpenApiRegistry {
        val resolver = PathMatchingResourcePatternResolver()
        val patterns = listOf("classpath:api/*.yaml") + (env.getProperty("taktovia.api.extra-specs")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList())
        val docs = patterns.flatMap { resolver.getResources(it).toList() }.associate { r -> r.filename!! to r.inputStream.use { String(it.readAllBytes(), Charsets.UTF_8) } }
        val keywords = listOf(
            SemanticKeyword("x-money") { node, arg, pointer -> money.validate(de.taktovia.app.openapi.JsonPlain.of(node), pointer, arg == "signed") },
            SemanticKeyword("x-time", TimeKeyword::check),
            SemanticKeyword("x-url") { node, _, pointer -> if (node.isString) ssrf.checkLiteral(node.asString(), pointer.ifEmpty { "/" }) else emptyList() },
        ) + (semantic.getIfAvailable()?.keywords ?: emptyList())
        return OpenApiRegistry(docs, keywords)
    }

    @Bean fun cursorCodec(env: Environment): CursorCodec {
        val configured = env.getProperty("taktovia.api.cursor-secret")?.takeIf { it.isNotBlank() }
        val secret = configured?.toByteArray() ?: ByteArray(32).also {
            SecureRandom().nextBytes(it)
            log.warn("taktovia.api.cursor-secret ist nicht gesetzt: zufälliger Schlüssel nur für diese Instanz; Cursor überleben keinen Neustart und gelten nicht instanzübergreifend.")
        }
        return CursorCodec(secret)
    }

    @Bean fun rateLimitStore(env: Environment): RateLimitStore {
        val url = env.getProperty("taktovia.api.rate-limit.redis-url")?.takeIf { it.isNotBlank() }
        return if (url != null) RedisRateLimitStore.connect(url) else InMemoryRateLimitStore()
    }

    @Bean fun rateLimiter(store: RateLimitStore, env: Environment) = RateLimiter(store,
        Quota(env.getProperty("taktovia.api.rate-limit.requests", Int::class.java, 600), env.getProperty("taktovia.api.rate-limit.window-seconds", Int::class.java, 60)))

    /** Container-Fehler (vor allen Filtern) als Problem statt HTML-Seite. */
    @Bean fun problemErrorValve() = WebServerFactoryCustomizer<TomcatServletWebServerFactory> { it.addContextCustomizers(ProblemErrorValveCustomizer()) }

    @Bean fun correlationFilter() = FilterRegistrationBean(CorrelationFilter()).apply { order = Ordered.HIGHEST_PRECEDENCE }

    @Bean fun securityHeadersFilter() = FilterRegistrationBean(SecurityHeadersFilter()).apply { order = Ordered.HIGHEST_PRECEDENCE + 1 }

    @Bean fun pathHardeningFilter(problems: ProblemWriter) = FilterRegistrationBean(PathHardeningFilter(problems)).apply { order = Ordered.HIGHEST_PRECEDENCE + 2 }

    @Bean fun originPolicyFilter(env: Environment, problems: ProblemWriter) = FilterRegistrationBean(
        OriginPolicyFilter(env.getProperty("taktovia.security.cors.allowed-origins")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(), problems),
    ).apply { order = Ordered.HIGHEST_PRECEDENCE + 3 }

    @Bean fun rateLimitFilter(limiter: RateLimiter, identity: ObjectProvider<RequestIdentityResolver>, problems: ProblemWriter) =
        FilterRegistrationBean(RateLimitFilter(limiter, { identity.getIfAvailable { RemoteAddressIdentity } }, problems)).apply { order = Ordered.HIGHEST_PRECEDENCE + 10 }

    @Bean fun requestValidationFilter(registry: OpenApiRegistry, limits: RequestLimits, problems: ProblemWriter, apiJson: JsonMapper, inspectors: ObjectProvider<RequestBodyInspector>) =
        FilterRegistrationBean(RequestValidationFilter(registry, limits, problems, apiJson, inspectors.orderedStream().toList())).apply { order = Ordered.HIGHEST_PRECEDENCE + 20 }
}

/** Erweiterungspunkt für weitere semantische Schema-Schlüsselwörter (z. B. `x-time`, WP-M1-18). */
class SemanticKeywords(val keywords: List<SemanticKeyword>)

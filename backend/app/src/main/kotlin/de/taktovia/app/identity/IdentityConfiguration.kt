package de.taktovia.app.identity

import de.taktovia.app.openapi.OpenApiRegistry
import de.taktovia.app.security.IdpProperties
import de.taktovia.app.web.ProblemWriter
import de.taktovia.app.web.RequestBodyInspector
import de.taktovia.app.security.KeycloakIdpAdmin
import de.taktovia.identity.api.IdentityApi
import de.taktovia.identity.api.IdpAdmin
import de.taktovia.identity.api.SessionSettings
import de.taktovia.platform.cache.RedisSessionBlockCache
import de.taktovia.platform.cache.SessionBlockCache
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.audit.SecurityEventPort
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.tenant.TenantTransactions
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.env.Environment
import java.security.SecureRandom
import java.time.Duration

/**
 * Verdrahtung von Identität, Anfragekontext und Mandantenwechsel (WP-M1-06). Einstellungen unter `taktovia.identity.*`:
 * `context-secret` (mind. 32 Byte; ohne Wert zufällig je Instanz, nur für Entwicklung: Mandantenbindungen gelten dann nicht
 * instanzübergreifend) und `context-ttl-seconds` (Standard 3600, Planungsdefault).
 */
@Configuration
class IdentityConfiguration {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean fun identityApi(
        tx: TenantTransactions, audit: AuditPort, security: SecurityEventPort, outbox: Outbox,
        idp: IdpAdmin, blockCache: SessionBlockCache, settings: SessionSettings,
    ): IdentityApi = IdentityApi.create(tx, audit, security, outbox, idp = idp, blockCache = blockCache, sessionSettings = settings)

    /**
     * Sitzungseinstellungen (WP-M1-10, Planungswerte OP-13): `taktovia.identity.session.max-age-seconds` (36000), `block-ttl-seconds` (Sitzungssperren im
     * Cache, 86400), `cache-ttl-seconds` (Kontosperren im Cache, höchstens 60, Standard 60).
     */
    @Bean fun sessionSettings(env: Environment): SessionSettings = SessionSettings(
        maxSessionAge = Duration.ofSeconds(env.getProperty("taktovia.identity.session.max-age-seconds", Long::class.java, 36_000L)),
        sessionBlockTtl = Duration.ofSeconds(env.getProperty("taktovia.identity.session.block-ttl-seconds", Long::class.java, 86_400L)),
        accountBlockTtl = Duration.ofSeconds(env.getProperty("taktovia.identity.session.cache-ttl-seconds", Long::class.java, 60L)),
    )

    /** Redis nur als Cache der Sperrliste (`taktovia.identity.session.redis-url`); ohne Wert entscheidet immer die Datenbank. */
    @Bean fun sessionBlockCache(env: Environment): SessionBlockCache =
        env.getProperty("taktovia.identity.session.redis-url")?.takeIf { it.isNotBlank() }?.let { RedisSessionBlockCache.connect(it) } ?: SessionBlockCache.None

    /**
     * IdP-Administration (`taktovia.idp.admin.*`): `base-url` des Keycloak, `realm` (Standard: letztes Segment des Issuers), `client-id` (Standard
     * `taktovia-idp-admin`), `client-secret` bzw. Datei `client-secret-file` aus der Secret-Verwaltung (nie im Repository). Ohne `base-url` gibt es
     * keine Anbindung: lokale Sperren gelten, IdP-Schritte melden Fehler.
     */
    @Bean fun idpAdmin(env: Environment): IdpAdmin {
        val base = env.getProperty("taktovia.idp.admin.base-url")?.takeIf { it.isNotBlank() } ?: return IdpAdmin.Unconfigured
        val issuer = env.getProperty("taktovia.idp.issuer-uri") ?: error("taktovia.idp.issuer-uri fehlt für die IdP-Administration")
        val secretFile = env.getProperty("taktovia.idp.admin.client-secret-file")?.takeIf { it.isNotBlank() }
        val secret = env.getProperty("taktovia.idp.admin.client-secret")?.takeIf { it.isNotBlank() }
        require(secretFile != null || secret != null) { "taktovia.idp.admin.client-secret-file oder client-secret fehlt" }
        return KeycloakIdpAdmin(base, env.getProperty("taktovia.idp.admin.realm") ?: issuer.trimEnd('/').substringAfterLast('/'), issuer,
            env.getProperty("taktovia.idp.admin.client-id") ?: "taktovia-idp-admin",
            { secretFile?.let { java.nio.file.Files.readString(java.nio.file.Path.of(it)).trim() } ?: secret!! })
    }

    /** Einziger vorgesehener Weg für Fachoperationen eines Requests (Wiederprüfung der Mitgliedschaft in der Transaktion). */
    @Bean fun contextTransactions(tx: TenantTransactions, identity: IdentityApi) = ContextTransactions(tx, identity.revalidator)

    @Bean fun contextTicketCodec(env: Environment): ContextTicketCodec {
        val configured = env.getProperty("taktovia.identity.context-secret")?.takeIf { it.isNotBlank() }
        val secret = configured?.toByteArray() ?: ByteArray(32).also {
            SecureRandom().nextBytes(it)
            log.warn("taktovia.identity.context-secret ist nicht gesetzt: zufälliger Schlüssel nur für diese Instanz; Mandantenbindungen überleben keinen Neustart und gelten nicht instanzübergreifend.")
        }
        return ContextTicketCodec(secret, Duration.ofSeconds(env.getProperty("taktovia.identity.context-ttl-seconds", Long::class.java, 3600)))
    }

    /** Protokolliert das Ausrollen der Rechtematrix beim Start (IAM-004 AK 4); schlägt es fehl, startet die Anwendung nicht. */
    @Bean fun rightsMatrixRolloutRecorder(identity: IdentityApi, env: Environment) = RightsMatrixRolloutRecorder(identity, env.getProperty("spring.application.name") ?: "taktovia")

    @Bean fun tenantMismatchReporter(tx: TenantTransactions, events: SecurityEventPort) = TenantMismatchReporter(tx, events)

    @Bean fun tenantClaimInspector(m: TenantMismatchReporter): RequestBodyInspector = TenantClaimInspector(m)

    /**
     * Quelle der Rollen aus Mitgliedschaften für die Mehrfaktor-Pflicht (WP-M1-04 Hook `PrivilegedRoleDirectory`): Eine privilegierte
     * Rolle nur in `membership.role_code` unterliegt derselben Pflicht wie eine Token-Rolle, und eine Zuweisung während der Sitzung gilt
     * mit der nächsten Anfrage. Andere Quellen (Tests) werden vereinigt.
     */
    @Bean @org.springframework.context.annotation.Primary @ConditionalOnProperty("taktovia.idp.enabled", havingValue = "true", matchIfMissing = true)
    fun privilegedRoleDirectory(identity: IdentityApi, props: IdpProperties, others: org.springframework.beans.factory.ObjectProvider<de.taktovia.app.security.PrivilegedRoleDirectory>): de.taktovia.app.security.PrivilegedRoleDirectory =
        CompositeRoleDirectory(MembershipRoleDirectory(identity, props.issuer), others)

    @Bean @ConditionalOnProperty("taktovia.idp.enabled", havingValue = "true", matchIfMissing = true)
    fun tenantRequestContextFilter(
        identity: IdentityApi, registry: OpenApiRegistry, props: IdpProperties, tickets: ContextTicketCodec, tx: TenantTransactions, events: SecurityEventPort,
        mismatch: TenantMismatchReporter, problems: ProblemWriter, env: Environment,
    ): FilterRegistrationBean<RequestContextFilter> {
        // Zusatzverträge (nur Tests, `taktovia.api.extra-specs`) tragen eigene Prüfidentitäten und keinen Mitgliedschaftskontext.
        val excluded = (env.getProperty("taktovia.api.extra-specs")?.split(',')?.map { it.trim().substringAfterLast('/') }?.filter { it.isNotEmpty() } ?: emptyList()).toSet()
        // nach Token-Prüfung (+5) und Quote (+10), vor der Eingabeprüfung (+20)
        return FilterRegistrationBean(RequestContextFilter(identity, registry, props, tickets, tx, events, mismatch, problems, excluded)).apply { order = Ordered.HIGHEST_PRECEDENCE + 15 }
    }
}

/** Rollen eines Kontos aus aktiven Mitgliedschaften aller Mandanten (nur für die Mehrfaktor-Prüfung; liefert keine Rechte). */
class MembershipRoleDirectory(private val identity: IdentityApi, private val issuer: String) : de.taktovia.app.security.PrivilegedRoleDirectory {
    override fun rolesOf(subject: java.util.UUID): Set<String> =
        identity.lookup.selectableTenants(de.taktovia.identity.api.SubjectRef(issuer, subject.toString())).flatMap { it.roles }.toSet()
}

/** Vereinigt die Mitgliedschaftsrollen mit weiteren Quellen (ohne sich selbst); die weiteren werden erst bei der Anfrage aufgelöst. */
class CompositeRoleDirectory(
    private val membership: de.taktovia.app.security.PrivilegedRoleDirectory,
    private val others: org.springframework.beans.factory.ObjectProvider<de.taktovia.app.security.PrivilegedRoleDirectory>,
) : de.taktovia.app.security.PrivilegedRoleDirectory {
    override fun rolesOf(subject: java.util.UUID): Set<String> =
        membership.rolesOf(subject) + others.orderedStream().filter { it !== this }.toList().flatMap { it.rolesOf(subject) }
}

/** Startschritt: Version und Prüfsumme der mitgelieferten Rechtematrix im Ausrollprotokoll festhalten, wenn sie von der zuletzt protokollierten abweicht. */
class RightsMatrixRolloutRecorder(private val identity: IdentityApi, private val application: String) : org.springframework.boot.ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Als Runner, damit die Umgebungsprüfung (`EnvironmentGuard`) immer zuerst entscheidet und ein Fehlstart nie in ein fremdes Protokoll schreibt. */
    override fun run(args: org.springframework.boot.ApplicationArguments) {
        val m = identity.rights
        val problems = m.problems()
        check(problems.isEmpty()) { "Rechtematrix ungültig: ${problems.joinToString("; ")}" }
        val r = identity.rollout.record("service:$application")
        log.info("Rechtematrix Version {} geladen, Ausrollen {} (sha256 {})", r.version, if (r.recorded) "protokolliert" else "unverändert", r.sha256.take(12))
    }
}

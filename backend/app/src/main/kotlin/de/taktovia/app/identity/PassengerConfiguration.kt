package de.taktovia.app.identity

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import de.taktovia.app.security.AuthenticatedPrincipal
import de.taktovia.identity.api.ContactChannel
import de.taktovia.identity.api.ContactKey
import de.taktovia.identity.api.DeliveryUnavailableException
import de.taktovia.identity.api.IdentityApi
import de.taktovia.identity.api.IdpAdmin
import de.taktovia.identity.api.InMemoryVerificationSender
import de.taktovia.identity.api.PassengerApi
import de.taktovia.identity.api.PassengerSettings
import de.taktovia.identity.api.RecoveryDispatch
import de.taktovia.identity.api.VerificationSender
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.audit.SecurityEventPort
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.fieldprotection.EnvelopeCipher
import de.taktovia.platform.fieldprotection.FieldAccess
import de.taktovia.platform.fieldprotection.FieldAccessRoleGuard
import de.taktovia.platform.fieldprotection.FieldRights
import de.taktovia.platform.fieldprotection.KekProvider
import de.taktovia.platform.fieldprotection.ProtectedColumnStore
import de.taktovia.platform.fieldprotection.SecretKekProvider
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.secret.SecretRef
import de.taktovia.platform.secret.SecretResolver
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.env.Environment
import java.security.SecureRandom
import java.time.Duration
import javax.sql.DataSource

/**
 * Wer sich als Fahrgast selbst registrieren darf (WP-M1-28): nur Menschen-Tokens bestimmter Clients (Standard `passenger-web`, `taktovia.identity.enrollment-clients`),
 * deren Token **genau einen** Mandanten im Hinweisclaim `tenant_ids` trägt (der IdP setzt ihn je Vertriebskontext des Clients). Das ist nur die Auswahl des Mandanten
 * für die Anlage; Rechte entstehen erst durch die Mitgliedschaft, die die Registrierung selbst erteilt (Rolle `passenger`).
 */
class EnrollmentPolicy(private val clients: Set<String>) {
    fun tenantFor(p: AuthenticatedPrincipal): TenantId? =
        if (p.clientId in clients && p.type == de.taktovia.app.security.PrincipalType.HUMAN) p.tenantIds.singleOrNull()?.let { TenantId(it) } else null
}

/** Verbindung des Modulzugangs `taktovia_fields` (WP-M1-31); bewusst kein `DataSource`-/`TenantTransactions`-Bean, damit die Standardverbindung eindeutig bleibt. */
class FieldAccessConnection(val dataSource: HikariDataSource) : AutoCloseable {
    val tx = TenantTransactions(dataSource)
    override fun close() = dataSource.close()
}

/** Alle Fahrgastbausteine mit ihren Verdrahtungen (WP-M1-28, -29, -30). */
@Configuration
class PassengerConfiguration {
    private val log = LoggerFactory.getLogger(javaClass)

    private fun isDevelopment(env: Environment) = env.getProperty("taktovia.environment", "production") in setOf("local", "development", "test")

    @Bean fun enrollmentPolicy(env: Environment) = EnrollmentPolicy(
        env.getProperty("taktovia.identity.enrollment-clients", "passenger-web").split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet())

    /**
     * Modulzugang für sensible Felder: Passwort `taktovia.fields.datasource.password` (Umgebung `TAKTOVIA_DB_FIELDS_PASSWORD`, aus der Secret-Verwaltung). Nur in Entwicklung und
     * Test wird es wie in `db/db.py` aus dem Runtime-Passwort abgeleitet; Staging und Produktion verlangen den ausdrücklichen Wert.
     */
    @org.springframework.context.annotation.Lazy @Bean(destroyMethod = "close") fun fieldAccessConnection(env: Environment): FieldAccessConnection {
        val explicit = env.getProperty("taktovia.fields.datasource.password")?.takeIf { it.isNotBlank() }
        val runtimePw = env.getProperty("spring.datasource.password")?.takeIf { it.isNotBlank() } ?: System.getenv("TAKTOVIA_DB_RUNTIME_PASSWORD")?.takeIf { it.isNotBlank() }
        val password = explicit ?: if (isDevelopment(env) && runtimePw != null)
            java.security.MessageDigest.getInstance("SHA-256").digest(("taktovia_fields:$runtimePw").toByteArray()).joinToString("") { "%02x".format(it) }
        else error("taktovia.fields.datasource.password fehlt (Modulzugang für sensible Felder, WP-M1-31)")
        val cfg = HikariConfig().also {
            it.jdbcUrl = env.getProperty("taktovia.fields.datasource.url") ?: env.getRequiredProperty("spring.datasource.url")
            it.username = env.getProperty("taktovia.fields.datasource.username", "taktovia_fields"); it.password = password
            it.maximumPoolSize = env.getProperty("taktovia.fields.datasource.max-pool-size", Int::class.java, 4); it.minimumIdle = 0
            it.poolName = "taktovia-fields"; it.initializationFailTimeout = -1   // Start unabhängig vom Modulzugang; der Rollenwächter prüft beim Start
        }
        return FieldAccessConnection(HikariDataSource(cfg))
    }

    /**
     * Schlüsselverschlüsselungsschlüssel: mit Secret-Verwaltung (`taktovia.secrets.dir`) über `taktovia.fields.kek-ref.<kennung>` (`secret://…`) und `taktovia.fields.kek-current`;
     * in Entwicklung und Test ein zufälliger Schlüssel je Instanz (nach einem Neustart nicht entschlüsselbar). Staging und Produktion ohne Secret-Verwaltung: kein Start.
     */
    @org.springframework.context.annotation.Lazy @Bean fun fieldKeys(env: Environment, resolver: ObjectProvider<SecretResolver>): KekProvider {
        val current = env.getProperty("taktovia.fields.kek-current")?.takeIf { it.isNotBlank() }
        val r = resolver.ifAvailable
        if (current != null && r != null) {
            val prefix = "taktovia.fields.kek-ref."
            val refs = listOf(current).plus(env.getProperty("taktovia.fields.kek-known", "").split(',').map { it.trim() }.filter { it.isNotEmpty() }).distinct()
                .associateWith { SecretRef.requireRef(prefix + it, env.getRequiredProperty(prefix + it)) }
            return SecretKekProvider(r, refs, current)
        }
        check(isDevelopment(env)) { "Schlüssel für den Feldschutz nicht konfiguriert (taktovia.fields.kek-current und Secret-Verwaltung)" }
        log.warn("Feldschutz: zufälliger Schlüssel nur für diese Instanz (Entwicklung/Test); verschlüsselte Werte überleben keinen Neustart.")
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return object : KekProvider { override val currentKeyId = "dev"; override fun kek(keyId: String) = if (keyId == "dev") key else error("unbekannte Schlüsselkennung") }
    }

    @org.springframework.context.annotation.Lazy @Bean fun protectedColumnStore(keys: KekProvider) = ProtectedColumnStore(EnvelopeCipher(keys))

    /** Einziger Lesepfad auf Kontakt und Unterstützungsmerkmal für Betreiber (Feldrecht und Zweck). */
    @org.springframework.context.annotation.Lazy @Bean fun fieldAccess(ctxTx: ContextTransactions, fields: FieldAccessConnection, store: ProtectedColumnStore, audit: AuditPort, security: SecurityEventPort, meters: ObjectProvider<MeterRegistry>) =
        FieldAccess(ctxTx, fields.tx, FieldRights.load(), store, audit, security, meters.ifAvailable)

    /**
     * Startprüfung des Fahrgastkontos, **nach** allen anderen Startprüfungen (niedrigste Rangfolge): Die Bausteine werden bewusst erst hier erzeugt, damit
     * fehlende Konfiguration (Modulzugang, Schlüssel, Kontakt-Token-Schlüssel) den Start verhindert, ohne die Meldungen der übrigen Prüfungen zu überdecken.
     * Verbindungen: der Modulzugang liest Ciphertext, die Runtime-Verbindung nicht; ein Verstoß verhindert den Start. Ist die Prüfung selbst nicht ausführbar
     * (Datenbank ohne die Rechte des Feldschutzes), wird das nur gewarnt: dann scheitert der Feldzugriff beim Benutzen in der Datenbank.
     */
    @Bean fun passengerStartupCheck(passengers: ObjectProvider<PassengerApi>, access: ObjectProvider<FieldAccess>, fields: ObjectProvider<FieldAccessConnection>, runtime: DataSource) =
        PassengerStartupCheck(passengers, access, fields, runtime)

    /** Test-SMS-Adapter für Entwicklung/Test (M1); in anderen Umgebungen ohne Adapter kein Versand (der produktive Kanal folgt mit M2). */
    @Bean @ConditionalOnMissingBean(VerificationSender::class)
    fun verificationSender(env: Environment): VerificationSender =
        if (isDevelopment(env)) InMemoryVerificationSender().also { log.warn("Verifikationscodes gehen an den Test-Adapter (kein produktiver Versand, M1).") }
        else VerificationSender { _, _, _ -> throw DeliveryUnavailableException("kein produktiver Versandkanal konfiguriert (SMS/E-Mail mit M2)") }

    @Bean @ConditionalOnMissingBean(RecoveryDispatch::class)
    fun recoveryDispatch(idp: IdpAdmin) = RecoveryDispatch { issuer, subject, channel, _ ->
        when (channel) {
            ContactChannel.EMAIL -> idp.startRecovery(issuer, subject)
            ContactChannel.PHONE -> throw DeliveryUnavailableException("Wiederherstellung per SMS folgt mit M2")
        }
    }

    @org.springframework.context.annotation.Lazy @Bean fun contactKey(env: Environment): ContactKey {
        val configured = env.getProperty("taktovia.identity.contact-token-secret")?.takeIf { it.isNotBlank() }
        if (configured != null) return ContactKey(configured.toByteArray())
        check(isDevelopment(env)) { "taktovia.identity.contact-token-secret fehlt (Schlüssel der Kontakt-Token, mindestens 32 Byte aus der Secret-Verwaltung)" }
        log.warn("taktovia.identity.contact-token-secret ist nicht gesetzt: zufälliger Schlüssel nur für diese Instanz; Kontakt-Token überleben keinen Neustart.")
        return ContactKey(ByteArray(32).also { SecureRandom().nextBytes(it) })
    }

    /** Planungswerte (OP-23, Beschluss vor Produktivschaltung): `taktovia.identity.passenger.*`. */
    @Bean fun passengerSettings(env: Environment): PassengerSettings {
        val d = PassengerSettings()
        fun p(k: String) = "taktovia.identity.passenger.$k"
        return PassengerSettings(
            codeLength = env.getProperty(p("code-length"), Int::class.java, d.codeLength),
            codeTtl = Duration.ofSeconds(env.getProperty(p("code-ttl-seconds"), Long::class.java, d.codeTtl.seconds)),
            maxAttempts = env.getProperty(p("max-attempts"), Int::class.java, d.maxAttempts),
            maxStartsPerHour = env.getProperty(p("max-starts-per-hour"), Int::class.java, d.maxStartsPerHour),
            lockout = Duration.ofSeconds(env.getProperty(p("lockout-seconds"), Long::class.java, d.lockout.seconds)),
            retention = Duration.ofDays(env.getProperty(p("retention-days"), Long::class.java, d.retention.toDays())),
            consentTextVersion = env.getProperty("taktovia.identity.consent.text-version", d.consentTextVersion),
        )
    }

    @org.springframework.context.annotation.Lazy @Bean fun passengerApi(
        identity: IdentityApi, tx: TenantTransactions, fields: FieldAccessConnection, store: ProtectedColumnStore, audit: AuditPort, security: SecurityEventPort, outbox: Outbox,
        key: ContactKey, sender: VerificationSender, recovery: RecoveryDispatch, settings: PassengerSettings,
    ): PassengerApi = PassengerApi.create(identity, tx, fields.tx, store, audit, security, outbox, key, sender, recovery, settings)
}

/** Siehe [PassengerConfiguration.passengerStartupCheck]. */
class PassengerStartupCheck(
    private val passengers: ObjectProvider<PassengerApi>, private val access: ObjectProvider<FieldAccess>, private val fields: ObjectProvider<FieldAccessConnection>, private val runtime: DataSource,
) : org.springframework.boot.ApplicationRunner, Ordered {
    private val log = LoggerFactory.getLogger(javaClass)
    override fun getOrder() = Ordered.LOWEST_PRECEDENCE
    override fun run(args: org.springframework.boot.ApplicationArguments) {
        passengers.getObject(); access.getObject()
        val problems = try { FieldAccessRoleGuard.problems(fields.getObject().dataSource, runtime) } catch (e: Exception) {
            log.warn("Feldschutz-Verbindungen nicht prüfbar ({})", e.javaClass.simpleName); emptyList()
        }
        check(problems.isEmpty()) { "Feldschutz-Verbindungen unzulässig: ${problems.joinToString("; ")}" }
    }
}

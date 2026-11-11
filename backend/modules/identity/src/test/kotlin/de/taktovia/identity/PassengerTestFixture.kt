package de.taktovia.identity

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import de.taktovia.identity.api.ContactChannel
import de.taktovia.identity.api.ContactInput
import de.taktovia.identity.api.ContactKey
import de.taktovia.identity.api.InMemoryVerificationSender
import de.taktovia.identity.api.PassengerApi
import de.taktovia.identity.api.PassengerSettings
import de.taktovia.identity.api.RecoveryDispatch
import de.taktovia.identity.api.Registered
import de.taktovia.identity.api.RegistrationCaller
import de.taktovia.platform.audit.SecurityEventPort
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.fieldprotection.EnvelopeCipher
import de.taktovia.platform.fieldprotection.KekProvider
import de.taktovia.platform.fieldprotection.ProtectedColumnStore
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/** Uhr für Ablauftests (Codegültigkeit, Sperrzeit): läuft mit der Systemzeit (die Datenbank stempelt mit ihrer Uhr) plus einem vorgestellten Versatz. */
class MutableTestClock : Clock() {
    private val offset = AtomicReference(Duration.ZERO)
    override fun getZone() = ZoneOffset.UTC
    override fun withZone(zone: java.time.ZoneId?) = this
    override fun instant(): Instant = Instant.now().plus(offset.get())
    fun advance(d: Duration) { offset.updateAndGet { it.plus(d) } }
}

/**
 * Aufbau für die Tests des Fahrgastkontos (WP-M1-28/-29/-30): echte Datenbank, Runtime-Rolle für die Fachtransaktionen, getrennter Modulzugang
 * `taktovia_fields` für den verschlüsselten Kontakt, Test-SMS-Adapter und Aufzeichnung der angestoßenen Wiederherstellungen.
 */
class PassengerTestFixture(poolSize: Int = 24, val settings: PassengerSettings = PassengerSettings(), val clock: MutableTestClock = MutableTestClock()) : AutoCloseable {
    val base = IdentityFixture(poolSize)
    private val fieldPool: HikariDataSource
    init {
        val pw = System.getenv("TAKTOVIA_DB_FIELDS_PASSWORD")?.takeIf { it.isNotBlank() }
            ?: java.security.MessageDigest.getInstance("SHA-256").digest(("taktovia_fields:" + System.getenv("TAKTOVIA_DB_RUNTIME_PASSWORD")).toByteArray()).joinToString("") { "%02x".format(it) }
        fieldPool = HikariDataSource(HikariConfig().also {
            it.jdbcUrl = "jdbc:postgresql://${System.getenv("TAKTOVIA_DB_HOST") ?: "127.0.0.1"}:${System.getenv("TAKTOVIA_DB_PORT") ?: "5432"}/${System.getenv("TAKTOVIA_DB_NAME") ?: "taktovia"}"
            it.username = "taktovia_fields"; it.password = pw; it.maximumPoolSize = poolSize; it.minimumIdle = 1
        })
    }
    val sender = InMemoryVerificationSender()
    data class Recovered(val issuer: String, val subject: String, val channel: ContactChannel, val destination: String)
    val recovered = CopyOnWriteArrayList<Recovered>()
    private val kek = ByteArray(32) { (it * 5 + 11).toByte() }
    private val cipher = EnvelopeCipher(object : KekProvider { override val currentKeyId = "v1"; override fun kek(keyId: String) = kek })
    val api: PassengerApi = PassengerApi.create(
        base.identity, base.tx, TenantTransactions(fieldPool), ProtectedColumnStore(cipher), base.audit, SecurityEventPort(base.audit), base.outbox,
        ContactKey(ByteArray(32) { (it + 1).toByte() }), sender, RecoveryDispatch { i, s, c, d -> recovered += Recovered(i, s, c, d) }, settings, clock)

    fun tenant(): TenantId = base.tenant()

    fun register(tenant: TenantId, subject: String = UUID.randomUUID().toString(), channel: ContactChannel = ContactChannel.EMAIL, value: String = defaultValue(channel), locale: String = "de-DE"): Registered =
        api.accounts.register(RegistrationCaller(base.issuer, subject, UUID.randomUUID()), tenant, locale, ContactInput(channel, value))

    /** Kontext des registrierten Fahrgasts (Mitgliedschaft `passenger`, entstanden bei der Registrierung). */
    fun ctx(tenant: TenantId, subject: String): RequestContext {
        val ids = base.tx.run(tenant) { j -> j.query("SELECT id FROM taktovia.membership WHERE subject = ? AND role_code = 'passenger'", { rs, _ -> rs.getObject(1, UUID::class.java) }, subject) }.toSet()
        return base.context(tenant, subject, ids, setOf("passenger"))
    }

    /** Registrierung und Verifikation des E-Mail-Kanals; liefert Subject und Kontext. */
    fun verifiedPassenger(tenant: TenantId, channel: ContactChannel = ContactChannel.EMAIL): Pair<String, RequestContext> {
        val subject = UUID.randomUUID().toString(); val value = defaultValue(channel)
        register(tenant, subject, channel, value)
        val ctx = ctx(tenant, subject)
        api.accounts.confirmVerification(ctx, channel, sender.last(channel, value)!!)
        return subject to ctx
    }

    fun count(sql: String, vararg args: Any?) = base.adminCount(sql, *args)

    override fun close() { fieldPool.close(); base.close() }

    companion object {
        fun defaultValue(channel: ContactChannel) = if (channel == ContactChannel.EMAIL) "anna.${UUID.randomUUID().toString().take(8)}@example.org" else "+49151${(1000000..9999999).random()}"
    }
}

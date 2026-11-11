package de.taktovia.bookingdispatch

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import de.taktovia.platform.context.Actor
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.ContextRevalidator
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import javax.sql.DataSource

/**
 * Datenbankabhängige Tests des Moduls `booking-dispatch` (eigene Hilfsklassen, WP-M2-02): Zugangsdaten aus der Umgebung; fehlen sie, werden die Tests übersprungen,
 * mit `TAKTOVIA_REQUIRE_DB=true` (CI) schlagen sie fehl. Die Anwendung läuft unter der Runtime-Rolle, die Referenzregion lädt die Migrator-Rolle (wie in
 * `tests/acceptance`), der Administrator räumt nur auf.
 */
object BookingTestDb {
    private fun env(k: String) = System.getenv(k)?.takeIf { it.isNotBlank() }
    private val url get() = "jdbc:postgresql://${env("TAKTOVIA_DB_HOST") ?: "127.0.0.1"}:${env("TAKTOVIA_DB_PORT") ?: "5432"}/${env("TAKTOVIA_DB_NAME") ?: "taktovia"}"

    fun require() {
        if (env("TAKTOVIA_DB_RUNTIME_PASSWORD") == null || env("TAKTOVIA_DB_ADMIN_PASSWORD") == null || env("TAKTOVIA_DB_MIGRATOR_PASSWORD") == null) {
            if (env("TAKTOVIA_REQUIRE_DB") == "true") throw AssertionError("Datenbank erforderlich (TAKTOVIA_REQUIRE_DB=true)")
            assumeTrue(false, "Datenbank-Zugangsdaten nicht gesetzt")
        }
    }

    private fun pool(user: String, password: String, size: Int) = HikariDataSource(HikariConfig().also {
        it.jdbcUrl = url; it.username = user; it.password = password; it.maximumPoolSize = size; it.minimumIdle = 1
    })

    fun runtime(size: Int = 12): HikariDataSource = pool("taktovia_runtime", env("TAKTOVIA_DB_RUNTIME_PASSWORD")!!, size)
    fun migrator(): HikariDataSource = pool("taktovia_migrator", env("TAKTOVIA_DB_MIGRATOR_PASSWORD")!!, 1)
    fun admin(): HikariDataSource = pool(env("TAKTOVIA_DB_ADMIN_USER") ?: "dev_admin", env("TAKTOVIA_DB_ADMIN_PASSWORD")!!, 1)
    fun repoRoot(): Path = Path.of(System.getProperty("taktovia.repoRoot"))
}

/** Fixture-ID wie im Seed: `md5('m2ref/<mandant>/<schlüssel>')::uuid`. */
fun fixtureId(tenantLabel: String, key: String): UUID {
    val hex = MessageDigest.getInstance("MD5").digest("m2ref/$tenantLabel/$key".toByteArray()).joinToString("") { "%02x".format(it) }
    return UUID.fromString("${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}")
}

/** Referenzregion (Seed der Fixture, zwei Mandanten A/B) und Zugriff unter der Runtime-Rolle. */
class BookingFixture : AutoCloseable {
    init { BookingTestDb.require(); loadSeed() }
    val ds: DataSource = BookingTestDb.runtime()
    val tx = TenantTransactions(ds)
    val ctxTx = ContextTransactions(tx, object : ContextRevalidator { override fun revalidate(jdbc: org.springframework.jdbc.core.JdbcOperations, ctx: RequestContext, lock: Boolean) {} })

    val tenantA = TenantId(UUID.fromString("00000000-0000-4000-8000-00000000000a"))
    val tenantB = TenantId(UUID.fromString("00000000-0000-4000-8000-00000000000b"))

    private fun loadSeed() {
        val sql = Files.readString(BookingTestDb.repoRoot().resolve("tests/acceptance/fixtures/m2_referenzregion/seed.sql"))
        BookingTestDb.migrator().use { m ->
            m.connection.use { c ->
                c.autoCommit = false
                c.createStatement().use { it.execute(sql) }
                c.commit()
            }
        }
    }

    fun context(tenant: TenantId, subject: String = "dispatcher-" + UUID.randomUUID()) =
        RequestContext(Actor(ActorType.HUMAN, "https://idp.test/realms/booking", subject, "sid-$subject", true), tenant, setOf("dispatcher"), setOf("rides:read", "rides:write"), emptySet(), UUID.randomUUID())

    fun admin(): HikariDataSource = BookingTestDb.admin()

    override fun close() { (ds as HikariDataSource).close() }
}

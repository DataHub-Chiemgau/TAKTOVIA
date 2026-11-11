package de.taktovia.config

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import de.taktovia.config.api.ConfigApi
import de.taktovia.platform.audit.JdbcAuditPort
import de.taktovia.platform.context.Actor
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.ContextRevalidator
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.util.UUID
import javax.sql.DataSource

/**
 * Datenbankabhängige Tests des Moduls `config`: Zugangsdaten aus der Umgebung; fehlen sie, werden die Tests übersprungen, mit
 * `TAKTOVIA_REQUIRE_DB=true` (CI) schlagen sie fehl. Die Anwendung läuft unter der Runtime-Rolle; nur Testmandanten legt der Administrator an.
 * Die Mitgliedschaftsprüfung gehört dem Modul `identity` und wird hier durch eine stets zustimmende Wiederprüfung ersetzt; sie ist Teil der Anwendungstests.
 */
object ConfigTestDb {
    private fun env(k: String) = System.getenv(k)?.takeIf { it.isNotBlank() }
    private val url get() = "jdbc:postgresql://${env("TAKTOVIA_DB_HOST") ?: "127.0.0.1"}:${env("TAKTOVIA_DB_PORT") ?: "5432"}/${env("TAKTOVIA_DB_NAME") ?: "taktovia"}"

    fun require() {
        if (env("TAKTOVIA_DB_RUNTIME_PASSWORD") == null || env("TAKTOVIA_DB_ADMIN_PASSWORD") == null) {
            if (env("TAKTOVIA_REQUIRE_DB") == "true") throw AssertionError("Datenbank erforderlich (TAKTOVIA_REQUIRE_DB=true)")
            assumeTrue(false, "Datenbank-Zugangsdaten nicht gesetzt")
        }
    }

    private fun pool(user: String, password: String, size: Int) = HikariDataSource(HikariConfig().also {
        it.jdbcUrl = url; it.username = user; it.password = password; it.maximumPoolSize = size; it.minimumIdle = 1
    })

    fun runtime(size: Int = 8): HikariDataSource = pool("taktovia_runtime", env("TAKTOVIA_DB_RUNTIME_PASSWORD")!!, size)
    fun admin(): HikariDataSource = pool(env("TAKTOVIA_DB_ADMIN_USER") ?: "dev_admin", env("TAKTOVIA_DB_ADMIN_PASSWORD")!!, 1)
}

class ConfigFixture : AutoCloseable {
    init { ConfigTestDb.require() }
    val ds: DataSource = ConfigTestDb.runtime()
    private val admin = ConfigTestDb.admin()
    val tx = TenantTransactions(ds)
    val ctxTx = ContextTransactions(tx, object : ContextRevalidator { override fun revalidate(jdbc: org.springframework.jdbc.core.JdbcOperations, ctx: RequestContext, lock: Boolean) {} })
    val config: ConfigApi = ConfigApi.create(ctxTx, JdbcAuditPort(), Outbox())

    fun tenant(): TenantId {
        val id = UUID.randomUUID()
        admin.connection.use { c -> c.prepareStatement("INSERT INTO taktovia.tenant(id,name,status) VALUES (?,?,'ACTIVE')").use { it.setObject(1, id); it.setString(2, "t-$id"); it.executeUpdate() } }
        return TenantId(id)
    }

    fun context(tenant: TenantId, subject: String = "admin-" + UUID.randomUUID()) =
        RequestContext(Actor(ActorType.HUMAN, "https://idp.test/realms/config", subject, "sid-$subject", true), tenant, setOf("tenant_admin"), setOf("company:read", "company:write"), emptySet(), UUID.randomUUID())

    fun count(sql: String, vararg args: Any?): Int = admin.connection.use { c ->
        c.prepareStatement(sql).use { ps -> args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }; ps.executeQuery().use { it.next(); it.getInt(1) } }
    }

    fun strings(sql: String, vararg args: Any?): List<String> = admin.connection.use { c ->
        c.prepareStatement(sql).use { ps -> args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }; ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } } }
    }

    override fun close() { (ds as HikariDataSource).close(); admin.close() }
}

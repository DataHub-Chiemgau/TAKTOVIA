package de.taktovia.identity

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import de.taktovia.identity.api.IdentityApi
import de.taktovia.platform.audit.JdbcAuditPort
import de.taktovia.platform.audit.SecurityEventPort
import de.taktovia.platform.context.Actor
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.util.UUID
import javax.sql.DataSource

/**
 * Datenbankabhängige Tests des Moduls: Zugangsdaten aus der Umgebung. Fehlen sie, werden die Tests übersprungen; mit
 * `TAKTOVIA_REQUIRE_DB=true` (CI) schlagen sie stattdessen fehl. Die Anwendung läuft wie in Produktion unter der Runtime-Rolle,
 * nur Testdaten (Mandanten) legt der Administrator an.
 */
object IdentityTestDb {
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

    fun runtime(poolSize: Int = 8): HikariDataSource = pool("taktovia_runtime", env("TAKTOVIA_DB_RUNTIME_PASSWORD")!!, poolSize)
    fun admin(): HikariDataSource = pool(env("TAKTOVIA_DB_ADMIN_USER") ?: "dev_admin", env("TAKTOVIA_DB_ADMIN_PASSWORD")!!, 1)
}

/** Gemeinsamer Aufbau: Runtime-Pool, Dienste des Moduls, Testmandanten. */
class IdentityFixture(poolSize: Int = 8) : AutoCloseable {
    init { IdentityTestDb.require() }
    val ds: DataSource = IdentityTestDb.runtime(poolSize)
    private val admin = IdentityTestDb.admin()
    val tx = TenantTransactions(ds)
    val audit = JdbcAuditPort()
    val outbox = Outbox()
    val identity: IdentityApi = IdentityApi.create(tx, audit, SecurityEventPort(audit), outbox)
    val issuer = "https://idp.test/realms/identity-" + UUID.randomUUID()

    fun tenant(name: String = "t-" + UUID.randomUUID(), status: String = "ACTIVE"): TenantId {
        val id = UUID.randomUUID()
        admin.connection.use { c -> c.prepareStatement("INSERT INTO taktovia.tenant(id,name,status) VALUES (?,?,?)").use { it.setObject(1, id); it.setString(2, name); it.setString(3, status); it.executeUpdate() } }
        return TenantId(id)
    }

    /** Ein Kontext, wie ihn der Anfragefilter nach der Prüfung bildet (hier direkt für Tests des Moduls). */
    fun context(tenant: TenantId, subject: String, membershipIds: Set<UUID>, roles: Set<String> = setOf("tenant_admin"), type: ActorType = ActorType.HUMAN) =
        RequestContext(Actor(type, issuer, subject, "sid-$subject", true), tenant, roles, identity.scopePolicy.scopesFor(roles), emptySet(), UUID.randomUUID(), membershipIds = membershipIds)

    /** Legt eine Mitgliedschaft direkt (Administrator, ohne Auditspur) an; liefert ihre Id. Nur für Testaufbau. */
    fun seedMembership(tenant: TenantId, subject: String, role: String = "tenant_admin", scope: List<UUID>? = null): UUID {
        val id = UUID.randomUUID()
        tx.run(tenant) { j ->
            j.update("INSERT INTO taktovia.membership(tenant_id,id,issuer,subject,role_code,service_scope) VALUES (?,?,?,?,?,CAST(? AS uuid[]))",
                tenant.value, id, issuer, subject, role, scope?.joinToString(",", "{", "}"))
        }
        return id
    }

    fun adminCount(sql: String, vararg args: Any?): Int = admin.connection.use { c ->
        c.prepareStatement(sql).use { ps -> args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }; ps.executeQuery().use { it.next(); it.getInt(1) } }
    }

    fun adminStrings(sql: String, vararg args: Any?): List<String> = admin.connection.use { c ->
        c.prepareStatement(sql).use { ps -> args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }; ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } } }
    }

    override fun close() { (ds as HikariDataSource).close(); admin.close() }
}

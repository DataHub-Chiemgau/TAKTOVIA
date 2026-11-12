package de.taktovia.fleetsafety

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import de.taktovia.fleetsafety.api.FleetsafetyApi
import de.taktovia.platform.audit.JdbcAuditPort
import de.taktovia.platform.context.Actor
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.ContextRevalidator
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.storage.InMemoryObjectStore
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.util.UUID
import javax.sql.DataSource

/**
 * Datenbankabhängige Tests des Moduls `fleet-safety`: Zugangsdaten aus der Umgebung; fehlen sie, werden die Tests übersprungen, mit `TAKTOVIA_REQUIRE_DB=true` (CI)
 * schlagen sie fehl. Die Anwendung läuft unter der Runtime-Rolle; Mandanten, Organisationen und Fixture-Fahrzeuge (Stammteil, M3) legt der Administrator an.
 * Die Mitgliedschaftsprüfung gehört dem Modul `identity` und ist hier durch eine zustimmende Wiederprüfung ersetzt; sie ist Teil der Anwendungstests.
 */
object FleetTestDb {
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

    fun runtime(size: Int = 12): HikariDataSource = pool("taktovia_runtime", env("TAKTOVIA_DB_RUNTIME_PASSWORD")!!, size)
    fun admin(): HikariDataSource = pool(env("TAKTOVIA_DB_ADMIN_USER") ?: "dev_admin", env("TAKTOVIA_DB_ADMIN_PASSWORD")!!, 1)
}

class FleetFixture : AutoCloseable {
    init { FleetTestDb.require() }
    val ds: DataSource = FleetTestDb.runtime()
    private val admin = FleetTestDb.admin()
    val tx = TenantTransactions(ds)
    val ctxTx = ContextTransactions(tx, object : ContextRevalidator { override fun revalidate(jdbc: org.springframework.jdbc.core.JdbcOperations, ctx: RequestContext, lock: Boolean) {} })
    val store = InMemoryObjectStore()
    val fleet: FleetsafetyApi = FleetsafetyApi.create(ctxTx, JdbcAuditPort(), Outbox(), store)

    /** Ein Mandant mit Rechtsträger, vier Organisationen und einem Fixture-Fahrzeug. */
    class World(val tenant: TenantId, val orgs: List<UUID>, val vehicle: UUID)

    fun world(orgCount: Int = 5): World {
        val t = UUID.randomUUID(); val le = UUID.randomUUID(); val vehicle = UUID.randomUUID(); val orgs = List(orgCount) { UUID.randomUUID() }
        exec("INSERT INTO taktovia.tenant(id,name,status) VALUES (?,?,'ACTIVE')", t, "t-$t")
        val tenant = TenantId(t)
        tx.run(tenant) { j ->
            j.update("INSERT INTO taktovia.legal_entity(tenant_id,id,name,country) VALUES (?,?,'LE','DE')", t, le)
            orgs.forEachIndexed { i, o -> j.update("INSERT INTO taktovia.organization(tenant_id,id,legal_entity_id,name,federation_ref,roles) VALUES (?,?,?,?,?,CAST(? AS text[]))", t, o, le, "Org $i", "f-$o", "{TRANSPORT_COMPANY}") }
            j.update("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (?,?,'fixture','{}')", t, vehicle)
        }
        return World(tenant, orgs, vehicle)
    }

    fun vehicle(t: TenantId): UUID = UUID.randomUUID().also { id -> tx.run(t) { j -> j.update("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (?,?,'fixture','{}')", t.value, id) } }

    fun context(tenant: TenantId, subject: String = "dispatcher-" + UUID.randomUUID()) =
        RequestContext(Actor(ActorType.HUMAN, "https://idp.test/realms/fleet", subject, "sid-$subject", true), tenant, setOf("dispatcher"), setOf("fleet:read", "fleet:write"), emptySet(), UUID.randomUUID())

    fun exec(sql: String, vararg args: Any?) { admin.connection.use { c -> c.prepareStatement(sql).use { ps -> args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }; ps.executeUpdate() } } }

    fun count(sql: String, vararg args: Any?): Int = admin.connection.use { c ->
        c.prepareStatement(sql).use { ps -> args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }; ps.executeQuery().use { it.next(); it.getInt(1) } }
    }

    fun strings(sql: String, vararg args: Any?): List<String> = admin.connection.use { c ->
        c.prepareStatement(sql).use { ps -> args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }; ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } } }
    }

    override fun close() { (ds as HikariDataSource).close(); admin.close() }
}

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
import java.time.Clock
import java.util.UUID
import javax.sql.DataSource

/**
 * Datenbankabhängige Tests des Moduls: Zugangsdaten aus der Umgebung. Fehlen sie, werden die Tests übersprungen; mit `TAKTOVIA_REQUIRE_DB=true`
 * (CI) schlagen sie stattdessen fehl. Die Anwendung läuft wie in Produktion unter der Runtime-Rolle, nur Testdaten (Mandanten, neue
 * Katalogversionen) legt der Administrator an.
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

    fun runtime(poolSize: Int = 8): HikariDataSource = pool("taktovia_runtime", env("TAKTOVIA_DB_RUNTIME_PASSWORD")!!, poolSize)
    fun admin(): HikariDataSource = pool(env("TAKTOVIA_DB_ADMIN_USER") ?: "dev_admin", env("TAKTOVIA_DB_ADMIN_PASSWORD")!!, 2)
}

/** Gemeinsamer Aufbau: Runtime-Pool, Dienste des Moduls, Testmandanten, Kontexte. Der Mitgliedschaftswiderruf gehört zu `identity` und ist hier ohne Wirkung. */
class ConfigFixture(poolSize: Int = 8, clock: Clock = Clock.systemUTC()) : AutoCloseable {
    init { ConfigTestDb.require() }
    val ds: DataSource = ConfigTestDb.runtime(poolSize)
    private val admin = ConfigTestDb.admin()
    val tx = TenantTransactions(ds)
    val ctxTx = ContextTransactions(tx, object : ContextRevalidator { override fun revalidate(jdbc: org.springframework.jdbc.core.JdbcOperations, ctx: RequestContext, lock: Boolean) = Unit })
    val audit = JdbcAuditPort()
    val outbox = Outbox()
    val config: ConfigApi = ConfigApi.create(ctxTx, audit, outbox, clock)

    fun tenant(name: String = "t-" + UUID.randomUUID()): TenantId {
        val id = UUID.randomUUID()
        admin.connection.use { c -> c.prepareStatement("INSERT INTO taktovia.tenant(id,name,status) VALUES (?,?,'ACTIVE')").use { it.setObject(1, id); it.setString(2, name); it.executeUpdate() } }
        return TenantId(id)
    }

    /** Kontext wie ihn der Anfragefilter nach der Prüfung bildet: Akteur [subject], Rolle und Funktionsscopes. */
    fun context(tenant: TenantId, subject: String = UUID.randomUUID().toString(), roles: Set<String> = setOf("tenant_admin"),
                scopes: Set<String> = setOf("config:read", "config:write", "config:publish")) =
        RequestContext(Actor(ActorType.HUMAN, "https://idp.test/realms/config", subject, "sid-$subject", true), tenant, roles, scopes, emptySet(), UUID.randomUUID())

    fun adminCount(sql: String, vararg args: Any?): Int = admin.connection.use { c ->
        c.prepareStatement(sql).use { ps -> args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }; ps.executeQuery().use { it.next(); it.getInt(1) } }
    }

    fun adminStrings(sql: String, vararg args: Any?): List<String> = admin.connection.use { c ->
        c.prepareStatement(sql).use { ps -> args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }; ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } } }
    }

    /** Mehrere Anweisungen auf **einer** Administrator-Verbindung (z. B. mit `session_replication_role = replica` für Testobjekte ohne vollständige Fremdschlüsselkette). */
    fun <T> adminSession(block: (java.sql.Connection) -> T): T = admin.connection.use(block)

    fun adminUpdate(sql: String, vararg args: Any?): Int = admin.connection.use { c ->
        c.prepareStatement(sql).use { ps -> args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }; ps.executeUpdate() }
    }

    /**
     * Veröffentlicht einen Entwurf wie WP-M1-22 es tun wird (Zustandsübergänge unter der Runtime-Rolle: geprüft, freigegeben von einer zweiten Person,
     * publiziert). Nur Testaufbau für die Rekonstruktion; die Publikation selbst (Vier-Augen, Wirkungsanalyse) liefert WP-M1-22.
     */
    fun publish(tenant: TenantId, revisionId: UUID) {
        for ((state, extra) in listOf("VALIDATED" to "", "APPROVED" to ", approved_by = 'user:zweite-person'", "PUBLISHED" to ", published_at = clock_timestamp()")) {
            tx.run(tenant) { j ->
                if (state == "PUBLISHED") j.update("UPDATE taktovia.config_revision p SET effective_to = n.effective_from FROM taktovia.config_revision n WHERE n.id = ? AND p.tenant_id = n.tenant_id AND p.scope_kind = n.scope_kind AND p.scope_id = n.scope_id AND p.state = 'PUBLISHED' AND p.effective_to IS NULL AND p.effective_from < n.effective_from", revisionId)   // Vorgänger schließen (WP-M1-22: Überlappungsverbot)
                j.update("UPDATE taktovia.config_revision SET state = ?$extra WHERE id = ?", state, revisionId)
            }
        }
    }

    fun revisionRows(tenant: TenantId) = adminCount("SELECT count(*) FROM taktovia.config_revision WHERE tenant_id = ?", tenant.value)

    override fun close() { (ds as HikariDataSource).close(); admin.close() }
}

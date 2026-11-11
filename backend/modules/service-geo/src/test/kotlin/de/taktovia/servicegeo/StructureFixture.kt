package de.taktovia.servicegeo

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import de.taktovia.config.api.ConfigApi
import de.taktovia.config.api.ConfigRevisionView
import de.taktovia.config.api.CreateDraft
import de.taktovia.config.api.ScopeKind
import de.taktovia.platform.audit.JdbcAuditPort
import de.taktovia.platform.context.Actor
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.ContextRevalidator
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import de.taktovia.servicegeo.api.ServicegeoApi
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * Datenbankabhängige Tests des Moduls `service-geo` (WP-M1-09): Zugangsdaten aus der Umgebung; fehlen sie, werden die Tests übersprungen, mit
 * `TAKTOVIA_REQUIRE_DB=true` (CI) schlagen sie fehl. Die Anwendung läuft unter der Runtime-Rolle; Mandanten und Organisationen legt der Administrator an.
 * Die Mitgliedschaftsprüfung gehört dem Modul `identity` und ist durch eine zustimmende Wiederprüfung ersetzt (Teil der Anwendungstests).
 */
object StructureTestDb {
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

class StructureFixture : AutoCloseable {
    init { StructureTestDb.require() }
    val ds: DataSource = StructureTestDb.runtime()
    private val admin = StructureTestDb.admin()
    val tx = TenantTransactions(ds)
    val ctxTx = ContextTransactions(tx, object : ContextRevalidator { override fun revalidate(jdbc: org.springframework.jdbc.core.JdbcOperations, ctx: RequestContext, lock: Boolean) {} })
    val audit = JdbcAuditPort()
    val config: ConfigApi = ConfigApi.create(ctxTx, audit, Outbox(), revisionValidators = listOf(ServicegeoApi.offerModelValidator()))
    val geo: ServicegeoApi = ServicegeoApi.create(ctxTx, audit, config)

    /** Ein Mandant mit Rechtsträger und Betreiber-Organisation. */
    class World(val tenant: TenantId, val legalEntity: UUID, val operator: UUID)

    fun world(): World {
        val t = UUID.randomUUID(); val le = UUID.randomUUID(); val op = UUID.randomUUID()
        adminUpdate("INSERT INTO taktovia.tenant(id,name,status) VALUES (?,?,'ACTIVE')", t, "t-$t")
        val tenant = TenantId(t)
        tx.run(tenant) { j ->
            j.update("INSERT INTO taktovia.legal_entity(tenant_id,id,name,country) VALUES (?,?,'LE','DE')", t, le)
            j.update("INSERT INTO taktovia.organization(tenant_id,id,legal_entity_id,name,federation_ref,roles) VALUES (?,?,?,?,?,CAST(? AS text[]))", t, op, le, "Betreiber", "f-$op", "{TRANSPORT_COMPANY}")
        }
        return World(tenant, le, op)
    }

    fun context(tenant: TenantId, subject: String = UUID.randomUUID().toString(), services: Set<UUID> = emptySet(),
                scopes: Set<String> = setOf("organization:read", "organization:write", "services:read", "services:write", "config:read", "config:write", "config:publish")) =
        RequestContext(Actor(ActorType.HUMAN, "https://idp.test/realms/structure", subject, "sid-$subject", true), tenant, setOf("tenant_admin"),
            scopes, services, UUID.randomUUID())

    /** Legt einen Entwurf für [kind]/[scopeId] an und veröffentlicht ihn (Testaufbau; die Publikation liefert WP-M1-22). */
    fun publishedRevision(ctx: RequestContext, kind: ScopeKind, scopeId: UUID, values: Map<String, Any?>, from: Instant = Instant.parse("2026-01-01T00:00:00Z")): ConfigRevisionView {
        val v = config.revisions.createDraft(ctx, CreateDraft(kind, scopeId, "1", values, from, null, "Testaufbau"))
        for ((state, extra) in listOf("VALIDATED" to "", "APPROVED" to ", approved_by = 'user:zweite-person'", "PUBLISHED" to ", published_at = clock_timestamp()")) {
            tx.run(ctx.tenant) { j ->
                if (state == "PUBLISHED") j.update("UPDATE taktovia.config_revision p SET effective_to = n.effective_from FROM taktovia.config_revision n WHERE n.id = ? AND p.tenant_id = n.tenant_id AND p.scope_kind = n.scope_kind AND p.scope_id = n.scope_id AND p.state = 'PUBLISHED' AND p.effective_to IS NULL AND p.effective_from < n.effective_from", v.id)
                j.update("UPDATE taktovia.config_revision SET state = ?$extra WHERE id = ?", state, v.id)
            }
        }
        return config.revisions.get(ctx, v.id)
    }

    fun serviceConfig(operator: UUID) = mapOf("service_config" to mapOf(
        "service_area_id" to UUID.randomUUID().toString(), "traffic_type" to "AREA_ON_DEMAND", "operator_id" to operator.toString(),
        "operating_hours" to listOf(mapOf("weekdays" to listOf("MON", "TUE"), "from" to "06:00", "to" to "22:00")), "tariff_id" to UUID.randomUUID().toString()))

    fun adminCount(sql: String, vararg args: Any?): Int = admin.connection.use { c ->
        c.prepareStatement(sql).use { ps -> args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }; ps.executeQuery().use { it.next(); it.getInt(1) } }
    }

    fun adminUpdate(sql: String, vararg args: Any?): Int = admin.connection.use { c ->
        c.prepareStatement(sql).use { ps -> args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }; ps.executeUpdate() }
    }

    override fun close() { (ds as HikariDataSource).close(); admin.close() }
}

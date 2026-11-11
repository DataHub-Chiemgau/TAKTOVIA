package de.taktovia.stafftime

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import de.taktovia.platform.audit.JdbcAuditPort
import de.taktovia.platform.context.Actor
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.ContextRevalidator
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import de.taktovia.stafftime.api.StafftimeApi
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.util.UUID
import javax.sql.DataSource

/**
 * Datenbankabhängige Tests des Moduls `staff-time`: Zugangsdaten aus der Umgebung; fehlen sie, werden die Tests übersprungen, mit `TAKTOVIA_REQUIRE_DB=true` (CI) schlagen sie fehl.
 * Die Anwendung läuft unter der Runtime-Rolle; Mandanten, Fahrzeuge und Schichten (Stammteil) legt der Administrator an. Die Mitgliedschaftsprüfung gehört dem Modul `identity`
 * und ist hier durch eine zustimmende Wiederprüfung ersetzt (Teil der Anwendungstests).
 */
object StaffTestDb {
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

    fun runtime(size: Int = 24): HikariDataSource = pool("taktovia_runtime", env("TAKTOVIA_DB_RUNTIME_PASSWORD")!!, size)
    fun admin(): HikariDataSource = pool(env("TAKTOVIA_DB_ADMIN_USER") ?: "dev_admin", env("TAKTOVIA_DB_ADMIN_PASSWORD")!!, 1)
}

class StaffFixture : AutoCloseable {
    init { StaffTestDb.require() }
    val ds: DataSource = StaffTestDb.runtime()
    private val admin = StaffTestDb.admin()
    val tx = TenantTransactions(ds)
    val ctxTx = ContextTransactions(tx, object : ContextRevalidator { override fun revalidate(jdbc: org.springframework.jdbc.core.JdbcOperations, ctx: RequestContext, lock: Boolean) {} })
    val staff: StafftimeApi = StafftimeApi.create(ctxTx, JdbcAuditPort(), Outbox())

    /** Mandant mit Fahrzeug, Schicht des Fahrers [subject] und (optional) einem Umlauf in Revision [runRevision]. */
    class World(val tenant: TenantId, val subject: UUID, val vehicle: UUID, val shift: UUID)

    fun world(runRevision: Long? = null): World {
        val t = UUID.randomUUID(); val subject = UUID.randomUUID(); val vehicle = UUID.randomUUID(); val shift = UUID.randomUUID()
        exec("INSERT INTO taktovia.tenant(id,name,status) VALUES (?,?,'ACTIVE')", t, "t-$t")
        val tenant = TenantId(t)
        tx.run(tenant) { j ->
            j.update("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (?,?,'fixture','{}')", t, vehicle)
            j.update("INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state) VALUES (?,?,?,?,now(),now()+interval '8 hours','Europe/Berlin',0,'PLANNED')", t, shift, subject, vehicle)
            if (runRevision != null) j.update("INSERT INTO taktovia.vehicle_run(tenant_id,id,vehicle_id,shift_id,revision,state) VALUES (?,?,?,?,?,'ACTIVE')", t, UUID.randomUUID(), vehicle, shift, runRevision)
        }
        return World(tenant, subject, vehicle, shift)
    }

    fun context(tenant: TenantId, subject: UUID, session: String = "sid-$subject") =
        RequestContext(Actor(ActorType.HUMAN, "https://idp.test/realms/driver", subject.toString(), session, true), tenant, setOf("driver"), setOf("driver:events", "driver:devices"), emptySet(), UUID.randomUUID())

    fun exec(sql: String, vararg args: Any?) { admin.connection.use { c -> c.prepareStatement(sql).use { ps -> args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }; ps.executeUpdate() } } }

    fun count(sql: String, vararg args: Any?): Int = admin.connection.use { c ->
        c.prepareStatement(sql).use { ps -> args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }; ps.executeQuery().use { it.next(); it.getInt(1) } }
    }

    fun strings(sql: String, vararg args: Any?): List<String> = admin.connection.use { c ->
        c.prepareStatement(sql).use { ps -> args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }; ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } } }
    }

    override fun close() { (ds as HikariDataSource).close(); admin.close() }
}

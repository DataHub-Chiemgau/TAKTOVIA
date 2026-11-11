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

    /** Frischer, isolierter Fahrtenplan (eigenes Fahrzeug mit [capacityJson], Schicht 04:00-14:00Z, gültige Freigabe, Pool von „nord“) – der Seed der Fixture legt ihn mit der Migrator-Rolle an. */
    class RunRef(val runId: UUID, val vehicleId: UUID, val serviceId: UUID, val revision: Long = 1)

    fun createRun(capacityJson: String, tenant: TenantId = tenantA, blocked: Boolean = false): RunRef {
        val label = if (tenant == tenantA) "a" else "b"
        val vehicle = UUID.randomUUID(); val shift = UUID.randomUUID(); val run = UUID.randomUUID(); val service = fixtureId(label, "svc-buchung")
        BookingTestDb.migrator().use { m ->
            m.connection.use { c ->
                c.autoCommit = false
                fun exec(sql: String, vararg a: Any?) = c.prepareStatement(sql).use { ps -> a.forEachIndexed { i, v -> ps.setObject(i + 1, v) }; ps.execute() }
                exec("SELECT set_config('app.tenant_id', ?, true)", tenant.toString())
                exec("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config,active_block,safety_revision) VALUES (?,?,'fed-org-m2-$label',CAST(? AS jsonb),?,1)", tenant.value, vehicle, capacityJson, blocked)
                exec("INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state) VALUES (?,?,?,?,'2026-12-01 04:00:00+00','2026-12-01 14:00:00+00','Europe/Berlin',1,'PUBLISHED')", tenant.value, shift, UUID.randomUUID(), vehicle)
                exec("INSERT INTO taktovia.resource_release(tenant_id,id,vehicle_id,shift_id,safety_revision,inspection_ref,qualification_revision,valid_until) VALUES (?,?,?,?,1,?,1,'2026-12-01 14:00:00+00')", tenant.value, UUID.randomUUID(), vehicle, shift, UUID.randomUUID())
                exec("INSERT INTO taktovia.vehicle_pool_assignment(tenant_id,id,service_id,vehicle_id,valid_from,created_by) VALUES (?,?,?,?,'2026-01-01T00:00:00Z','system:test')", tenant.value, UUID.randomUUID(), service, vehicle)
                exec("INSERT INTO taktovia.vehicle_run(tenant_id,id,vehicle_id,shift_id,revision,state) VALUES (?,?,?,?,1,'ACTIVE')", tenant.value, run, vehicle, shift)
                c.commit()
            }
        }
        return RunRef(run, vehicle, service)
    }

    /** Eine einzelne SQL-Anweisung unter der Migrator-Rolle im Mandanten (nur Testaufbau und Gegenproben). */
    fun migratorExec(tenant: TenantId, sql: String, vararg args: Any?) {
        BookingTestDb.migrator().use { m -> m.connection.use { c ->
            c.autoCommit = false
            c.prepareStatement("SELECT set_config('app.tenant_id', ?, true)").use { it.setString(1, tenant.toString()); it.execute() }
            c.prepareStatement(sql).use { ps -> args.forEachIndexed { i, v -> ps.setObject(i + 1, v) }; ps.execute() }
            c.commit()
        } }
    }

    fun context(tenant: TenantId, subject: String = "dispatcher-" + UUID.randomUUID()) =
        RequestContext(Actor(ActorType.HUMAN, "https://idp.test/realms/booking", subject, "sid-$subject", true), tenant, setOf("dispatcher"), setOf("rides:read", "rides:write"), emptySet(), UUID.randomUUID())

    fun admin(): HikariDataSource = BookingTestDb.admin()

    override fun close() { (ds as HikariDataSource).close() }
}

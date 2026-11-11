package de.taktovia.farepayment

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import de.taktovia.platform.tenant.TenantId
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.springframework.jdbc.core.JdbcTemplate
import java.nio.file.Path
import java.util.UUID

/** Datenbankabhängige Tests des Moduls `fare-payment` (eigene Hilfsklasse, WP-M2-29): Zugangsdaten aus der Umgebung; fehlen sie, werden die Tests übersprungen, mit `TAKTOVIA_REQUIRE_DB=true` schlagen sie fehl. */
object PaymentTestDb {
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
    fun admin(): HikariDataSource = pool(env("TAKTOVIA_DB_ADMIN_USER") ?: "dev_admin", env("TAKTOVIA_DB_ADMIN_PASSWORD")!!, 2)
    fun repoRoot(): Path = Path.of(System.getProperty("taktovia.repoRoot"))

    /** Frischer Mandant je Aufruf (Administrator legt ihn an; die Runtime-Rolle kann keine Mandanten anlegen). */
    fun newTenant(admin: JdbcTemplate): TenantId = TenantId(UUID.randomUUID()).also {
        admin.update("INSERT INTO taktovia.tenant(id,name,status) VALUES (?,?, 'ACTIVE')", it.value, "pay-$it")
    }
}

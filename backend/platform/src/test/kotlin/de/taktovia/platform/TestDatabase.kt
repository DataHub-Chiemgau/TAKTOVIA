package de.taktovia.platform

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.Assumptions.assumeTrue
import javax.sql.DataSource

/**
 * Datenbankabhängige Tests: Zugangsdaten aus der Umgebung. Fehlen sie, werden die Tests übersprungen;
 * mit `TAKTOVIA_REQUIRE_DB=true` (CI) schlagen sie stattdessen fehl.
 */
object TestDatabase {
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

    /** Runtime-Rolle mit kleinem Pool, damit Verbindungen bewusst wiederverwendet werden. */
    fun runtime(poolSize: Int = 2): HikariDataSource = pool("taktovia_runtime", env("TAKTOVIA_DB_RUNTIME_PASSWORD")!!, poolSize)
    /**
     * Modulzugang für sensible Felder (`taktovia_fields`, WP-M1-31). Passwort ausdrücklich aus `TAKTOVIA_DB_FIELDS_PASSWORD`, sonst wie in
     * `db/db.py` (nur Entwicklung/Test) aus dem Runtime-Passwort abgeleitet.
     */
    fun fieldAccess(poolSize: Int = 2): HikariDataSource {
        val pw = env("TAKTOVIA_DB_FIELDS_PASSWORD") ?: java.security.MessageDigest.getInstance("SHA-256")
            .digest(("taktovia_fields:" + env("TAKTOVIA_DB_RUNTIME_PASSWORD")!!).toByteArray()).joinToString("") { "%02x".format(it) }
        return pool("taktovia_fields", pw, poolSize)
    }
    /** Administrator nur für Testdaten (Mandanten anlegen); nie in Produktcode. */
    fun admin(): DataSource = pool(env("TAKTOVIA_DB_ADMIN_USER") ?: "dev_admin", env("TAKTOVIA_DB_ADMIN_PASSWORD")!!, 1)
}

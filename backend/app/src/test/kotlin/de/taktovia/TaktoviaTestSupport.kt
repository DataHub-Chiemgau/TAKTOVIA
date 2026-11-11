package de.taktovia

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.postgresql.ds.PGSimpleDataSource
import javax.sql.DataSource

/**
 * Datenbankabhängige Tests brauchen eine laufende Datenbank (lokal oder CI-Service) und lesen die Zugangsdaten
 * aus der Umgebung. Fehlt sie, werden die Tests übersprungen; mit `TAKTOVIA_REQUIRE_DB=true` (CI) schlagen sie stattdessen fehl.
 */
object TaktoviaTestSupport {
    private fun env(k: String) = System.getenv(k)?.takeIf { it.isNotBlank() }
    private val required get() = env("TAKTOVIA_REQUIRE_DB") == "true"

    fun skipOrFail(reason: String) {
        if (required) throw AssertionError("Datenbank erforderlich (TAKTOVIA_REQUIRE_DB=true): $reason")
        assumeTrue(false, reason)
    }

    fun requireDatabase() {
        if (env("TAKTOVIA_DB_RUNTIME_PASSWORD") == null) skipOrFail("TAKTOVIA_DB_RUNTIME_PASSWORD nicht gesetzt")
    }

    private fun ds(user: String, password: String?): DataSource? = password?.let {
        PGSimpleDataSource().apply {
            setURL("jdbc:postgresql://${env("TAKTOVIA_DB_HOST") ?: "127.0.0.1"}:${env("TAKTOVIA_DB_PORT") ?: "5432"}/${env("TAKTOVIA_DB_NAME") ?: "taktovia"}")
            this.user = user; this.password = it
        }
    }

    fun adminDataSourceOrNull() = ds(env("TAKTOVIA_DB_ADMIN_USER") ?: "dev_admin", env("TAKTOVIA_DB_ADMIN_PASSWORD"))
    fun migratorDataSourceOrNull() = ds("taktovia_migrator", env("TAKTOVIA_DB_MIGRATOR_PASSWORD"))
}

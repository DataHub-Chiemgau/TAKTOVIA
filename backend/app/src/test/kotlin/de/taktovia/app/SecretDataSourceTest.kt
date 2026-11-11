package de.taktovia.app

import de.taktovia.TaktoviaTestSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.jdbc.core.JdbcTemplate
import java.nio.file.Files
import javax.sql.DataSource

/** WP-M1-36 (SEC-003, positiv): Betrieb mit Secrets aus der Secret-Verwaltung; Konfiguration enthält nur die Referenz, kein Passwort. */
class SecretDataSourceTest {
    @Test fun `Anwendung laeuft mit Datenbankzugang aus der Secret-Referenz ohne spring_datasource_password`() {
        TaktoviaTestSupport.requireDatabase()
        val dir = Files.createTempDirectory("secrets"); val domain = "taktovia-development-secrets"
        Files.createDirectories(dir.resolve(domain))
        Files.writeString(dir.resolve(domain).resolve("db_runtime_credential"), "taktovia_runtime\n${System.getenv("TAKTOVIA_DB_RUNTIME_PASSWORD")}\n")
        val ctx = SpringApplicationBuilder(TaktoviaApplication::class.java).run(
            "--server.port=0", "--spring.datasource.password=", "--taktovia.secrets.dir=$dir", "--taktovia.secrets.domain=$domain",
            "--taktovia.secrets.db-credential=secret://$domain/db_runtime_credential")
        ctx.use {
            assertEquals("taktovia_runtime", JdbcTemplate(it.getBean(DataSource::class.java)).queryForObject("SELECT current_user", String::class.java))
        }
    }
}

package de.taktovia.app

import de.taktovia.platform.secret.FileSecretStore
import de.taktovia.platform.secret.SecretBackedDataSource
import de.taktovia.platform.secret.SecretRef
import de.taktovia.platform.secret.SecretResolver
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Path
import javax.sql.DataSource

/**
 * Datenbankzugang aus der Secret-Verwaltung statt aus Konfigurationswerten (LH-6.18-SEC-003): aktiv, sobald `taktovia.secrets.dir`
 * gesetzt ist. `spring.datasource.password` entfällt dann; die Referenz (`taktovia.secrets.db-credential`) bleibt bei Rotation gleich.
 */
@Configuration
@ConditionalOnProperty("taktovia.secrets.dir")
class SecretDataSourceConfig {
    @Bean
    fun secretResolver(@Value("\${taktovia.secrets.dir}") dir: String, @Value("\${taktovia.secrets.domain}") domain: String) =
        SecretResolver(domain, FileSecretStore(Path.of(dir)))

    @Bean
    fun dataSource(resolver: SecretResolver, @Value("\${spring.datasource.url}") url: String,
                   @Value("\${taktovia.secrets.db-credential}") ref: String): DataSource =
        SecretBackedDataSource(url, SecretRef.requireRef("taktovia.secrets.db-credential", ref), resolver)
}

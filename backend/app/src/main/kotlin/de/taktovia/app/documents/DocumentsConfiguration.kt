package de.taktovia.app.documents

import de.taktovia.documentsfinance.api.ClamdScanner
import de.taktovia.documentsfinance.api.DocumentUploads
import de.taktovia.documentsfinance.api.EicarSignatureScanner
import de.taktovia.documentsfinance.api.MalwareScanner
import de.taktovia.documentsfinance.api.UnavailableScanner
import de.taktovia.documentsfinance.api.UploadPolicy
import de.taktovia.documentsfinance.api.Uploads
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.audit.SecurityEventPort
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.storage.FileObjectStore
import de.taktovia.platform.storage.InMemoryObjectStore
import de.taktovia.platform.storage.TenantObjectStore
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.nio.file.Path
import java.time.Duration

/**
 * Verdrahtung von Uploadticket, Quarantäne und Malware-Prüfung (WP-M4-21). Einstellungen (`taktovia.documents.…`, Planungsdefaults, Offener Punkt 13, nicht freigegeben):
 * `store-dir` (Wurzel des Dateisystem-Objektspeichers; **Pflicht** außerhalb von `local`/`development`, dort sonst ein flüchtiger Speicher), `scanner` (`clamd` mit `clamd-host`/`clamd-port`;
 * `signature` = nur EICAR, nur in `local`/`development`; ohne Angabe: in `local`/`development` EICAR, sonst **kein Prüfer** = alles bleibt in Quarantäne), `max-bytes` (10 MiB),
 * `ticket-ttl-minutes` (15). Ein S3/MinIO-Objektspeicher-Adapter ist nicht ausgeführt (WP-M0-08).
 */
@Configuration
class DocumentsConfiguration {
    private fun localLike(env: Environment) = (env.getProperty("taktovia.environment")?.trim()?.ifEmpty { null } ?: "production") in setOf("local", "development")

    @Bean
    fun tenantObjectStore(env: Environment): TenantObjectStore {
        val dir = env.getProperty("taktovia.documents.store-dir")?.trim()?.ifEmpty { null }
        check(dir != null || localLike(env)) { "taktovia.documents.store-dir fehlt: außerhalb der lokalen Umgebung ist ein dauerhafter Objektspeicher Pflicht (Belegoriginale)" }
        return if (dir != null) FileObjectStore(Path.of(dir)) else InMemoryObjectStore()
    }

    @Bean
    fun malwareScanner(env: Environment): MalwareScanner {
        val kind = env.getProperty("taktovia.documents.scanner")?.trim()?.ifEmpty { null } ?: if (localLike(env)) "signature" else "none"
        return when (kind) {
            "clamd" -> ClamdScanner(env.getProperty("taktovia.documents.clamd-host", "127.0.0.1"), env.getProperty("taktovia.documents.clamd-port", Int::class.java, 3310))
            "signature" -> { check(localLike(env)) { "taktovia.documents.scanner=signature (nur EICAR) ist außerhalb der lokalen Umgebung nicht zulässig" }; EicarSignatureScanner() }
            "none" -> UnavailableScanner()
            else -> throw IllegalStateException("taktovia.documents.scanner: unbekannter Wert '$kind' (clamd|signature|none)")
        }
    }

    @Bean
    fun uploadPolicy(env: Environment) = UploadPolicy(
        maxBytes = env.getProperty("taktovia.documents.max-bytes", Long::class.java, 10L * 1024 * 1024),
        ticketTtl = Duration.ofMinutes(env.getProperty("taktovia.documents.ticket-ttl-minutes", Long::class.java, 15L)),
    )

    @Bean
    fun uploads(ctxTx: ContextTransactions, audit: AuditPort, security: SecurityEventPort, outbox: Outbox, store: TenantObjectStore, scanner: MalwareScanner, policy: UploadPolicy): Uploads =
        DocumentUploads.create(ctxTx, audit, security, outbox, store, scanner, policy)
}

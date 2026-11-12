package de.taktovia.app.fleet

import de.taktovia.documentsfinance.api.MalwareScanner
import de.taktovia.documentsfinance.api.ScanResult
import de.taktovia.fleetsafety.api.DefectEvidenceWiring
import de.taktovia.fleetsafety.api.EvidencePolicy
import de.taktovia.fleetsafety.api.FleetsafetyApi
import de.taktovia.fleetsafety.api.MediaInspectors
import de.taktovia.fleetsafety.api.MediaVerdict
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.storage.SignedUrls
import de.taktovia.platform.storage.TenantObjectStore
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.security.SecureRandom
import java.time.Duration

/**
 * Verdrahtung des Moduls `fleet-safety` (WP-M1-34, WP-M3-44). Fotoevidenz: mandantengetrennter Objektspeicher und Malware-Prüfer sind die Bausteine aus WP-M1-11 und WP-M4-21 (Konfiguration
 * `taktovia.documents.*`: ohne Prüfer bleibt jedes Foto in Quarantäne, fail-closed); Schlüssel der signierten Links `taktovia.fleet.evidence-link-secret` (mindestens 32 Zeichen, **Pflicht** außerhalb
 * von `local`/`development`; ohne Angabe dort ein zufälliger Schlüssel je Prozess), Ticketgültigkeit `taktovia.fleet.evidence-ticket-ttl-minutes` (15), Linkgültigkeit höchstens 60 s.
 */
@Configuration
class FleetConfiguration {
    private val log = LoggerFactory.getLogger(FleetConfiguration::class.java)

    @Bean
    fun defectEvidenceWiring(env: Environment, store: TenantObjectStore, scanner: MalwareScanner): DefectEvidenceWiring {
        val local = (env.getProperty("taktovia.environment")?.trim()?.ifEmpty { null } ?: "production") in setOf("local", "development")
        val configured = env.getProperty("taktovia.fleet.evidence-link-secret")?.trim()?.ifEmpty { null }
        check(configured != null || local) { "taktovia.fleet.evidence-link-secret fehlt: außerhalb der lokalen Umgebung ist der Schlüssel der signierten Fotolinks Pflicht" }
        val secret = configured?.toByteArray() ?: ByteArray(32).also { SecureRandom().nextBytes(it); log.warn("Kein taktovia.fleet.evidence-link-secret gesetzt: zufälliger Schlüssel nur für diesen Prozess") }
        val inspector = MediaInspectors.basic(extraScan = { bytes ->
            when (val r = scanner.scan(bytes)) {
                ScanResult.Clean -> null
                is ScanResult.Infected -> MediaVerdict.Infected(r.signature)
                is ScanResult.Unavailable -> MediaVerdict.Unavailable(r.reason)
            }
        }, engine = "media+" + scanner.engine)
        val policy = EvidencePolicy(ticketTtl = Duration.ofMinutes(env.getProperty("taktovia.fleet.evidence-ticket-ttl-minutes", Long::class.java, 15L)))
        return DefectEvidenceWiring(store, inspector, SignedUrls(secret), policy)
    }

    @Bean fun fleetsafetyApi(ctxTx: ContextTransactions, audit: AuditPort, outbox: Outbox, store: TenantObjectStore, evidence: DefectEvidenceWiring): FleetsafetyApi =
        FleetsafetyApi.create(ctxTx, audit, outbox, store, evidence = evidence)
}

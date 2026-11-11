package de.taktovia.app.audit

import de.taktovia.identity.api.IdentityApi
import de.taktovia.identity.api.SubjectRef
import de.taktovia.platform.audit.AuditExporter
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.audit.AuditQueryService
import de.taktovia.platform.audit.IdpAuditImporter
import de.taktovia.platform.audit.IdpTenantResolver
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.tenant.PlatformTenants
import de.taktovia.platform.tenant.TenantTransactions
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment

/**
 * Verdrahtung von Auditabfrage, IdP-Ereignisübernahme und Export (WP-M1-12). Der Export braucht den Schlüssel der Exportdomäne
 * (`taktovia.audit.export-key`, mindestens 32 Byte, aus dem Secret-Speicher, nie im Repository) und ist ohne ihn nicht aktiv.
 */
@Configuration
class AuditConfiguration {
    @Bean fun auditQueryService(ctxTx: ContextTransactions, audit: AuditPort) = AuditQueryService(ctxTx, audit)

    /** Mandantenzuordnung der IdP-Konten über die aktiven Mitgliedschaften (Identitätsbaustein). */
    @Bean fun idpTenantResolver(identity: IdentityApi) = IdpTenantResolver { issuer, subject ->
        identity.lookup.selectableTenants(SubjectRef(issuer, subject)).map { it.tenant }
    }

    @Bean fun idpAuditImporter(tx: TenantTransactions, audit: AuditPort, resolver: IdpTenantResolver) = IdpAuditImporter(tx, audit, resolver)

    /**
     * Prüft beim Start, ob der Plattformbetrieb den Systemmandanten bereitgestellt hat (`python db/db.py provision-system-tenant`). Fehlt er,
     * landen Ereignisse ohne Mandant nur in der Logzeile; die Anwendung startet trotzdem (Fachbetrieb hängt nicht davon ab).
     */
    @Bean fun systemTenantCheck(tx: TenantTransactions) = org.springframework.boot.ApplicationRunner {
        val present = runCatching { tx.run(PlatformTenants.SYSTEM, readOnly = true) { j -> j.queryForObject("SELECT count(*) FROM taktovia.tenant WHERE id = taktovia.current_tenant()", Int::class.java) } }.getOrNull() ?: 0
        if (present == 0) org.slf4j.LoggerFactory.getLogger("taktovia.audit").warn("Systemmandant fehlt: Ereignisse ohne Mandantenkontext werden nur protokolliert, nicht im Audit gespeichert (db.py provision-system-tenant).")
    }

    @Bean @ConditionalOnProperty("taktovia.audit.export-key")
    fun auditExporter(tx: TenantTransactions, env: Environment) = AuditExporter(tx, env.getRequiredProperty("taktovia.audit.export-key").toByteArray())
}

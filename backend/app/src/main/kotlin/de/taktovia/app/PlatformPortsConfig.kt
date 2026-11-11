package de.taktovia.app

import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.audit.JdbcAuditPort
import de.taktovia.platform.audit.SecurityEventPort
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.tenant.TenantTransactions
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import javax.sql.DataSource

/** Plattformbausteine für die Module (WP-M1-14, -46): Mandanten-Transaktion, Audit-/Sicherheitsereignis-Port, Outbox. */
@Configuration
class PlatformPortsConfig {
    @Bean @ConditionalOnMissingBean
    fun tenantTransactions(dataSource: DataSource) = TenantTransactions(dataSource)

    @Bean @ConditionalOnMissingBean(AuditPort::class)
    fun auditPort(): AuditPort = JdbcAuditPort()

    @Bean @ConditionalOnMissingBean
    fun securityEventPort(audit: AuditPort, tx: TenantTransactions) = SecurityEventPort(audit, tx)

    @Bean @ConditionalOnMissingBean
    fun outbox() = Outbox()
}

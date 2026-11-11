package de.taktovia.platform.audit

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcOperations
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.util.UUID

/**
 * Audit-Port (WP-M1-46): schreibt Audit- und Sicherheitsereignisse in derselben Transaktion wie die Fachänderung. Die übergebene
 * [JdbcOperations] muss die der aktuellen Mandanten-Transaktion sein (`TenantTransactions.run`); schlägt die Fachänderung fehl und die
 * Transaktion rollt zurück, existiert kein Auditeintrag; gelingt sie, genau einer. Es gibt nur Anhängen: UPDATE/DELETE scheitern in der
 * Datenbank (Rechte und Trigger `audit_immutable`). Korrekturen sind neue Gegenereignisse.
 */
interface AuditPort {
    fun record(jdbc: JdbcOperations, entry: AuditEntry): UUID
}

class JdbcAuditPort(private val clock: Clock = Clock.systemUTC()) : AuditPort {
    private val mapper = JsonMapper.builder().build()

    override fun record(jdbc: JdbcOperations, entry: AuditEntry): UUID {
        val tenant = jdbc.queryForObject("SELECT taktovia.current_tenant()", UUID::class.java)
            ?: throw IllegalStateException("Audit ohne Mandantenkontext nicht möglich (OP-14: Ereignisse ohne Mandant siehe SecurityEventPort)")
        val id = UUID.randomUUID()
        val changes = mapper.writeValueAsString(AuditRedactor.redact(entry.changes, tenant).json)
        jdbc.update(
            "INSERT INTO taktovia.audit_event(tenant_id,id,actor_ref,action,object_ref,occurred_at,correlation_id,redacted_changes) " +
                "VALUES (taktovia.current_tenant(),?,?,?,?,?,?,?::jsonb)",
            id, entry.actor.value, entry.action, entry.obj.toString(), java.sql.Timestamp.from(entry.occurredAt ?: clock.instant()), entry.correlationId, changes,
        )
        return id
    }
}

/**
 * Sicherheitsereignis-Port: Sicherheitsereignisse sind Auditereignisse mit Aktion `security.*`. Ohne Mandantenkontext
 * (z. B. fehlgeschlagener Login, OP-14) kann `audit_event` (tenant_id Pflicht) nicht beschrieben werden: [reportWithoutTenant] schreibt dann
 * nur eine strukturierte Logzeile; die Übernahme in einen Plattformmandanten ist mit OP-14/OP-08 zu klären.
 */
class SecurityEventPort(private val audit: AuditPort) {
    private val log = LoggerFactory.getLogger("taktovia.security")

    /** In der Transaktion des Mandanten (zusammen mit der Fachänderung). */
    fun report(jdbc: JdbcOperations, e: SecurityEvent): UUID =
        audit.record(jdbc, AuditEntry(e.actor, e.action, e.obj, e.correlationId, e.details))

    fun reportWithoutTenant(e: SecurityEvent) {
        log.warn("{\"event\":\"security_event_without_tenant\",\"action\":\"{}\",\"correlation_id\":\"{}\"}", e.action, e.correlationId)
    }
}

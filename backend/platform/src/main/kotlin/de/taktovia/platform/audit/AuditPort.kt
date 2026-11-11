package de.taktovia.platform.audit

import de.taktovia.platform.tenant.PlatformTenants
import de.taktovia.platform.tenant.TenantTransactions
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

    /**
     * Wie [record], aber mit vorgegebener Kennung und idempotent: existiert `(tenant, id)` schon, geschieht nichts (`false`). Für die
     * Übernahme von Fremdereignissen mit eigener Ereignis-ID (IdP, WP-M1-12), die bei doppelter Lieferung keinen zweiten Eintrag erzeugen dürfen.
     */
    fun recordOnce(jdbc: JdbcOperations, id: UUID, entry: AuditEntry): Boolean
}

class JdbcAuditPort(private val clock: Clock = Clock.systemUTC()) : AuditPort {
    private val mapper = JsonMapper.builder().build()

    override fun record(jdbc: JdbcOperations, entry: AuditEntry): UUID = UUID.randomUUID().also { insert(jdbc, it, entry, onConflictDoNothing = false) }

    override fun recordOnce(jdbc: JdbcOperations, id: UUID, entry: AuditEntry): Boolean = insert(jdbc, id, entry, onConflictDoNothing = true)

    private fun insert(jdbc: JdbcOperations, id: UUID, entry: AuditEntry, onConflictDoNothing: Boolean): Boolean {
        val tenant = jdbc.queryForObject("SELECT taktovia.current_tenant()", UUID::class.java)
            ?: throw IllegalStateException("Audit ohne Mandantenkontext nicht möglich (OP-14: Ereignisse ohne Mandant siehe SecurityEventPort)")
        val changes = mapper.writeValueAsString(AuditRedactor.redact(entry.changes, tenant).json)
        val n = jdbc.update(if (onConflictDoNothing) INSERT_ONCE else INSERT, id, entry.actor.value, entry.action, entry.obj.toString(),
            java.sql.Timestamp.from(entry.occurredAt ?: clock.instant()), entry.correlationId, changes)
        return n == 1
    }

    private companion object {
        const val INSERT = "INSERT INTO taktovia.audit_event(tenant_id,id,actor_ref,action,object_ref,occurred_at,correlation_id,redacted_changes) " +
            "VALUES (taktovia.current_tenant(),?,?,?,?,?,?,?::jsonb)"
        const val INSERT_ONCE = "$INSERT ON CONFLICT (tenant_id, id) DO NOTHING"
    }
}

/**
 * Sicherheitsereignis-Port: Sicherheitsereignisse sind Auditereignisse mit Aktion `security.*`. Ohne Mandantenkontext
 * (z. B. fehlgeschlagener Login, OP-14) kann `audit_event` (tenant_id Pflicht) nicht beschrieben werden: [reportWithoutTenant] schreibt dann
 * nur eine strukturierte Logzeile; die Übernahme in einen Plattformmandanten ist mit OP-14/OP-08 zu klären.
 */
class SecurityEventPort(private val audit: AuditPort, private val systemSink: TenantTransactions? = null) {
    private val log = LoggerFactory.getLogger("taktovia.security")

    /** In der Transaktion des Mandanten (zusammen mit der Fachänderung). */
    fun report(jdbc: JdbcOperations, e: SecurityEvent): UUID =
        audit.record(jdbc, AuditEntry(e.actor, e.action, e.obj, e.correlationId, e.details))

    /**
     * Ohne Mandantenkontext (OP-14, Vorschlag umgesetzt in WP-M1-12): immer eine strukturierte Logzeile; ist ein [systemSink] gesetzt, zusätzlich
     * ein Auditeintrag im reservierten Systemmandanten ([PlatformTenants.SYSTEM]). Ein Fehler dabei verändert die Ablehnung nicht.
     */
    fun reportWithoutTenant(e: SecurityEvent) {
        log.warn("{\"event\":\"security_event_without_tenant\",\"action\":\"{}\",\"correlation_id\":\"{}\"}", e.action, e.correlationId)
        val tx = systemSink ?: return
        try { tx.run(PlatformTenants.SYSTEM) { j -> report(j, e) } } catch (ex: Exception) {
            log.warn("Sicherheitsereignis ohne Mandant nicht im Systemmandanten gespeichert: {}", ex.javaClass.simpleName)
        }
    }
}

package de.taktovia.platform

import de.taktovia.platform.audit.JdbcAuditPort
import de.taktovia.platform.audit.SecurityEventPort
import de.taktovia.platform.context.Actor
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.ContextRevalidator
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.tenant.PlatformTenants
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

/** Gemeinsamer Aufbau der WP-M1-12-Tests: echte Datenbank, Runtime-Rolle, zwei Mandanten. Der Administrator legt nur Mandanten an. */
class AuditTestSupport(poolSize: Int = 16) {
    val a = TenantId(UUID.randomUUID())
    val b = TenantId(UUID.randomUUID())
    val pool = TestDatabase.runtime(poolSize)
    val tx = TenantTransactions(pool)
    val audit = JdbcAuditPort()
    val security = SecurityEventPort(audit, tx)
    /** Wiederprüfung der Mitgliedschaft gehört zu `identity` und wird dort getestet; hier bewusst ohne Wirkung. */
    val ctxTx = ContextTransactions(tx, object : ContextRevalidator { override fun revalidate(jdbc: JdbcOperations, ctx: RequestContext, lock: Boolean) = Unit })

    init {
        TestDatabase.require()
        val admin = JdbcTemplate(TestDatabase.admin())
        // Plattformbetrieb: reservierter Systemmandant für Ereignisse ohne Mandantenkontext (wie `db.py provision-system-tenant`)
        admin.update("INSERT INTO taktovia.tenant(id,name,status) VALUES (?,?, 'SUSPENDED') ON CONFLICT (id) DO NOTHING", PlatformTenants.SYSTEM.value, "platform-system")
        for (t in listOf(a, b)) admin.update("INSERT INTO taktovia.tenant(id,name,status) VALUES (?,?, 'ACTIVE')", t.value, "audit12-$t")
    }

    fun context(tenant: TenantId, subject: UUID = UUID.randomUUID(), scopes: Set<String> = setOf("audit:read")) =
        RequestContext(Actor(ActorType.HUMAN, "https://idp.test", subject.toString(), "sid", true), tenant, setOf("tenant_admin"), scopes, emptySet(), UUID.randomUUID())

    fun close() = pool.close()
}

package de.taktovia.platform.webhook

import de.taktovia.platform.outbox.OutboxTestSupport
import de.taktovia.platform.tenant.TenantId
import java.time.Instant
import java.util.UUID

/** Gemeinsame Bausteine der Webhook-Tests: echte Datenbank unter der Runtime-Rolle (über [OutboxTestSupport]). */
class WebhookTestSupport {
    val base = OutboxTestSupport()
    val tx get() = base.tx
    val admin get() = base.admin

    fun insertSubscription(t: TenantId, url: String, types: List<String> = listOf("membership.granted"), secretRef: String = "secret://taktovia-test-secrets/webhook_" + UUID.randomUUID().toString().replace("-", ""),
                           active: Boolean = true, createdAt: Instant = Instant.now().minusSeconds(3600)): UUID {
        val id = UUID.randomUUID()
        admin.update("INSERT INTO taktovia.webhook_subscription(tenant_id,id,url,event_types,secret_ref,created_by,created_at,active,deactivated_at) VALUES (?,?,?,?,?, 'user:test', ?, ?, ?)",
            t.value, id, url, types.toTypedArray(), secretRef, java.sql.Timestamp.from(createdAt), active, if (active) null else java.sql.Timestamp.from(Instant.now()))
        return id
    }

    fun deliveries(t: TenantId, where: String = "true") = admin.queryForList("SELECT state || ':' || attempts || ':' || coalesce(last_error,'-') FROM taktovia.webhook_delivery WHERE tenant_id = ? AND $where ORDER BY created_at, id", String::class.java, t.value)
    fun close() = base.close()
}

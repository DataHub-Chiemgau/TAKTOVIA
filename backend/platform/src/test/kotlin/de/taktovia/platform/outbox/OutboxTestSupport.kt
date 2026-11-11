package de.taktovia.platform.outbox

import de.taktovia.platform.TestDatabase
import de.taktovia.platform.alert.AlertChannel
import de.taktovia.platform.alert.AlertNotification
import de.taktovia.platform.alert.OperatorAlertService
import de.taktovia.platform.audit.JdbcAuditPort
import de.taktovia.platform.audit.SecurityEventPort
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Gemeinsame Bausteine der Outbox-/Inbox-Tests: echte Datenbank unter der Runtime-Rolle, frischer Mandant je Test. */
class OutboxTestSupport {
    val pool = TestDatabase.runtime(poolSize = 16)
    val tx = TenantTransactions(pool)
    val admin = JdbcTemplate(TestDatabase.admin())
    val audit = JdbcAuditPort()
    val security = SecurityEventPort(audit)
    val outbox = Outbox()
    val metrics = SimpleMeterRegistry()
    val delivered = CopyOnWriteArrayList<AlertNotification>()
    val alerts = OperatorAlertService(AlertChannel { delivered.add(it) }, metrics, { })

    class TestClock(var now: Instant = Instant.now()) : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant() = now
        fun advance(d: Duration) { now = now.plus(d) }
    }

    fun newTenant(): TenantId = TenantId(UUID.randomUUID()).also {
        admin.update("INSERT INTO taktovia.tenant(id,name,status) VALUES (?,?, 'ACTIVE')", it.value, "ob-$it")
    }

    fun append(t: TenantId, agg: UUID = UUID.randomUUID(), rev: Long = 1, type: String = "test.thing.changed", recipient: TenantId? = null,
               payload: Map<String, Any?> = mapOf("n" to rev), occurredAt: Instant = Instant.parse("2026-01-02T03:04:05Z")): UUID =
        tx.run(t) { j -> outbox.append(j, OutboxEvent(agg, rev, type, payload, occurredAt, recipient = recipient, correlationId = UUID.randomUUID())) }

    fun <T> read(t: TenantId, block: (JdbcOperations) -> T): T = tx.run(t, readOnly = true, block = block)
    fun close() = pool.close()
}

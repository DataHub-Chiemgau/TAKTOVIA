package de.taktovia.platform.idempotency

import de.taktovia.platform.TestDatabase
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.outbox.OutboxEvent
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** Echte Datenbank unter der Runtime-Rolle; die Tests prüfen das Register an einem Testbefehl, dessen Wirkung eine Outbox-Zeile ist. */
class IdempotencyTestSupport(poolSize: Int = 24) {
    val pool = TestDatabase.runtime(poolSize)
    val tx = TenantTransactions(pool)
    val admin = JdbcTemplate(TestDatabase.admin())
    val outbox = Outbox()
    val registry = IdempotencyRegistry(tx)

    class TestClock(var now: Instant = Instant.now()) : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant() = now
        fun advance(d: Duration) { now = now.plus(d) }
    }

    fun tenant(): TenantId = TenantId(UUID.randomUUID()).also { admin.update("INSERT INTO taktovia.tenant(id,name,status) VALUES (?,?, 'ACTIVE')", it.value, "idem-$it") }

    fun request(t: TenantId, principal: String = "iss|user-1", scope: String = "confirmThing", key: String = "key-" + UUID.randomUUID(), fingerprint: String = "a".repeat(64)) =
        IdempotentRequest(t, principal, scope, key, fingerprint)

    /**
     * Testbefehl: legt in einer Fachtransaktion genau eine Wirkung an (Outbox-Zeile je Aggregat; die Wirkung ist `aggregate`) und liefert 201 mit der Ressourcen-ID.
     * Zählt die Ausführungen, damit „genau eine Wirkung“ sichtbar wird.
     */
    fun command(t: TenantId, aggregate: UUID = UUID.randomUUID(), counter: java.util.concurrent.atomic.AtomicInteger, pause: () -> Unit = {}): () -> StoredResponse = {
        counter.incrementAndGet()
        pause()
        val id = tx.run(t) { j ->
            outbox.append(j, OutboxEvent(aggregate, 1, "test.thing.created", mapOf("n" to 1), Instant.parse("2026-01-02T03:04:05Z")))
            aggregate
        }
        StoredResponse(201, mapOf("Location" to "/v1/things/$id", "Content-Type" to "application/json"), """{"id":"$id"}""", operationId = null)
    }

    fun outboxCount(t: TenantId) = admin.queryForObject("SELECT count(*) FROM taktovia.outbox WHERE tenant_id = ?", Int::class.java, t.value)!!
    fun records(t: TenantId) = admin.queryForObject("SELECT count(*) FROM taktovia.idempotency_record WHERE tenant_id = ?", Int::class.java, t.value)!!
    fun close() = pool.close()
}

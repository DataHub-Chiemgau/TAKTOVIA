package de.taktovia.platform.outbox

import de.taktovia.platform.alert.OperatorAlertPort
import de.taktovia.platform.alert.OperatorWarning
import de.taktovia.platform.alert.Severity
import de.taktovia.platform.audit.ActorRef
import de.taktovia.platform.audit.AuditActions
import de.taktovia.platform.audit.ObjectRef
import de.taktovia.platform.audit.SecurityEvent
import de.taktovia.platform.audit.SecurityEventPort
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import org.springframework.jdbc.core.JdbcOperations
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.math.min
import kotlin.math.pow

/**
 * Liefert, bei welchen Mandanten Outbox-Einträge abzuholen sind. Wegen RLS kann der Relay Mandanten nicht selbst aufzählen
 * (OP-06/OP-08 offen): der Betrieb liefert die Liste (Konfiguration oder Plattformverwaltung), je Mandant läuft eine eigene Transaktion.
 */
fun interface TenantSource { fun tenants(): Collection<TenantId> }

data class RelayConfig(
    val batchSize: Int = 100,
    val lease: Duration = Duration.ofSeconds(60),
    /** Nach dieser Zahl von Zustellversuchen wird der Eintrag Dead-Letter und löst genau eine Betreiberwarnung aus. */
    val maxAttempts: Int = 20,
)

data class RelayStats(val published: Int = 0, val retried: Int = 0, val deadLettered: Int = 0) {
    operator fun plus(o: RelayStats) = RelayStats(published + o.published, retried + o.retried, deadLettered + o.deadLettered)
}

/** Retry-Staffel (Planungsdefault Kap. 05 §5.6): 1 s, 5 s, 30 s, 2 min, 10 min, danach exponentiell mit Jitter, höchstens 24 h. */
object RetrySchedule {
    private val FIXED = listOf(1L, 5L, 30L, 120L, 600L).map { Duration.ofSeconds(it) }
    private val CAP = Duration.ofHours(24)

    fun delay(attempt: Int, jitter: () -> Double = { Math.random() }): Duration {
        require(attempt >= 1)
        if (attempt <= FIXED.size) return FIXED[attempt - 1]
        val exp = Duration.ofSeconds(600).multipliedBy(2.0.pow(min(attempt - FIXED.size, 20)).toLong())
        val capped = if (exp > CAP) CAP else exp
        return Duration.ofMillis((capped.toMillis() * (0.5 + 0.5 * jitter())).toLong())   // Jitter: 50 % bis 100 %, nie über 24 h
    }
}

/**
 * Outbox-Relay PostgreSQL -> Broker (WP-M1-14), mindestens einmal. Ablauf je Mandant: Einträge in einer kurzen Transaktion beanspruchen
 * (`FOR UPDATE SKIP LOCKED`, Lease `claimed_until`), **außerhalb** der Transaktion veröffentlichen, danach als veröffentlicht markieren.
 * Mehrere Relay-Instanzen teilen sich die Einträge ohne Doppelzustellung; stirbt ein Relay, wird der Eintrag nach Ablauf der Lease von
 * einem anderen nachgeliefert (bei Tod nach Veröffentlichung, vor Markierung: Duplikat, das die Inbox verwirft).
 * Fehlgeschlagene Zustellung wird nach der Staffel wiederholt; nach `maxAttempts` Dead-Letter plus genau eine Betreiberwarnung (WP-M1-47).
 * Ereignisse ohne zulässige Empfängeradressierung werden nicht zugestellt, sondern sofort Dead-Letter (mit Warnung und Sicherheitsereignis).
 */
class OutboxRelay(
    private val tx: TenantTransactions,
    private val publisher: MessagePublisher,
    private val tenants: TenantSource,
    private val alerts: OperatorAlertPort,
    private val security: SecurityEventPort,
    private val policy: RecipientPolicy = RecipientPolicy.OWNER_ONLY,
    private val clock: Clock = Clock.systemUTC(),
    private val config: RelayConfig = RelayConfig(),
    private val jitter: () -> Double = { Math.random() },
    private val metrics: de.taktovia.platform.telemetry.PlatformMetrics? = null,   // WP-M5-03: Zustellverzögerung Commit bis Veröffentlichung
) {
    private class Claimed(val e: EventEnvelope, val attempts: Int)

    /** Ein Durchlauf über alle Mandanten der Quelle. Fehler eines Mandanten stoppen die anderen nicht. */
    fun runOnce(): RelayStats = tenants.tenants().distinct().fold(RelayStats()) { acc, t ->
        acc + (runCatching { runTenant(t) }.getOrDefault(RelayStats()))
    }

    fun runTenant(tenant: TenantId): RelayStats {
        var stats = RelayStats()
        while (true) {
            val claimed = claim(tenant)
            if (claimed.isEmpty()) return stats
            for (c in claimed) stats += deliver(tenant, c)
            if (claimed.size < config.batchSize) return stats
        }
    }

    private fun claim(tenant: TenantId): List<Claimed> {
        val now = clock.instant(); val ts = Timestamp.from(now)
        return tx.run(tenant) { j ->
            j.query(
                """UPDATE taktovia.outbox o SET claimed_until = ?, delivery_attempts = o.delivery_attempts + 1
                   WHERE (o.tenant_id, o.event_id) IN (
                     SELECT tenant_id, event_id FROM taktovia.outbox
                      WHERE published_at IS NULL AND dead_lettered_at IS NULL
                        AND (next_attempt_at IS NULL OR next_attempt_at <= ?) AND (claimed_until IS NULL OR claimed_until <= ?)
                      ORDER BY occurred_at, aggregate_revision, event_id LIMIT ? FOR UPDATE SKIP LOCKED)
                   RETURNING o.event_id, o.aggregate_id, o.aggregate_revision, o.event_type, o.schema_version, o.occurred_at, o.recorded_at,
                             o.payload::text AS payload, o.recipient_tenant_id, o.correlation_id, o.delivery_attempts""",
                { rs, _ ->
                    Claimed(EventEnvelope(
                        rs.getObject("event_id", UUID::class.java), rs.getString("event_type"), rs.getString("schema_version"),
                        rs.getObject("aggregate_id", UUID::class.java), rs.getLong("aggregate_revision"), rs.getTimestamp("occurred_at").toInstant(),
                        rs.getTimestamp("recorded_at").toInstant(), tenant.value, rs.getObject("recipient_tenant_id", UUID::class.java),
                        rs.getObject("correlation_id", UUID::class.java), EventEnvelope.parsePayload(rs.getString("payload"))), rs.getInt("delivery_attempts"))
                },
                Timestamp.from(now.plus(config.lease)), ts, ts, config.batchSize,
            )
        }
    }

    private fun deliver(tenant: TenantId, c: Claimed): RelayStats {
        val e = c.e
        if (!policy.allowed(e.ownerTenantId, e.recipientTenantId!!)) {
            deadLetter(tenant, c, "recipient_not_allowed", securityEvent = true)
            return RelayStats(deadLettered = 1)
        }
        try {
            publisher.publish("events.${e.recipientTenantId}.${e.eventType}", e.eventId.toString(), e.toJson())
        } catch (ex: Exception) {
            return if (c.attempts >= config.maxAttempts) { deadLetter(tenant, c, "publish_failed"); RelayStats(deadLettered = 1) }
            else {
                val next = clock.instant().plus(RetrySchedule.delay(c.attempts, jitter))
                tx.run(tenant) { j -> j.update("UPDATE taktovia.outbox SET next_attempt_at=?, claimed_until=NULL, last_error='publish_failed' WHERE tenant_id=taktovia.current_tenant() AND event_id=? AND published_at IS NULL",
                    Timestamp.from(next), e.eventId) }
                RelayStats(retried = 1)
            }
        }
        tx.run(tenant) { j -> j.update("UPDATE taktovia.outbox SET published_at=?, claimed_until=NULL, last_error=NULL WHERE tenant_id=taktovia.current_tenant() AND event_id=? AND published_at IS NULL",
            Timestamp.from(clock.instant()), e.eventId) }
        val publishedAt = clock.instant()
        metrics?.outboxPublishDelay(e.recordedAt, publishedAt)
        metrics?.outboxResult("published")
        return RelayStats(published = 1)
    }

    private fun deadLetter(tenant: TenantId, c: Claimed, reason: String, securityEvent: Boolean = false) {
        val e = c.e
        val moved = tx.run(tenant) { j: JdbcOperations ->
            val n = j.update("UPDATE taktovia.outbox SET dead_lettered_at=?, claimed_until=NULL, last_error=? WHERE tenant_id=taktovia.current_tenant() AND event_id=? AND dead_lettered_at IS NULL AND published_at IS NULL",
                Timestamp.from(clock.instant()), reason, e.eventId)
            if (n == 1 && securityEvent) security.report(j, SecurityEvent(ActorRef.system("outbox-relay"), AuditActions.SECURITY_RECIPIENT_MISMATCH,
                ObjectRef("event", e.eventId), e.correlationId ?: UUID.randomUUID(), mapOf("event_type" to e.eventType, "reason_code" to reason)))
            n
        }
        if (moved == 1) alerts.report(OperatorWarning("outbox-relay", "dead_letter", Severity.CRITICAL, e.correlationId ?: UUID.randomUUID(), tenant.value,
            mapOf("event_type" to e.eventType, "attempts" to c.attempts.toString(), "reason_code" to reason)))   // genau eine Warnung je Eintrag
    }
}

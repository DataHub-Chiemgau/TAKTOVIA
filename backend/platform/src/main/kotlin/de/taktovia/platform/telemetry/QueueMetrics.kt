package de.taktovia.platform.telemetry

import de.taktovia.platform.outbox.TenantSource
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/** Momentaufnahme der technischen Warteschlangenkennzahlen eines Mandanten. Nur Zähler und Dauern, keine Inhalte. */
data class QueueSnapshot(
    val outboxDepth: Long, val outboxDeadLettered: Long, val outboxOldestAgeSeconds: Double,
    val inboxLagSeconds: Double, val inboxGapSnapshots: Long,
    val operationsOpen: Long, val operationLeasesOverdue: Long, val operationsDeadlineOverdue: Long,
    val paymentIntentsIndeterminate: Long,
)

/**
 * Warteschlangen- und Zustandsmetriken je Mandant (WP-M5-03): Tiefe und Alter von `outbox`, Rückstand/Verzug der `inbox`, offene und überfällige
 * `operation`-Leases, `payment_intent` in unbestimmtem Zustand (`CAPTURE_UNKNOWN`, `RECONCILING`). Gelesen wird je Mandant in einer eigenen
 * Transaktion (Row-Level-Security bleibt wirksam, wie im Outbox-Relay); `refresh()` ruft ein Zeitgeber regelmäßig auf. Fällt die Datenbank
 * aus, bleiben die letzten Werte stehen und die Fehlerzählung steigt; `refresh()` wirft nie.
 *
 * Der Rückstand der Nachrichtenwarteschlangen selbst (RabbitMQ) kommt aus dem RabbitMQ-Exporter (infra/observability), nicht aus der Datenbank.
 */
class QueueMetrics(private val tx: TenantTransactions, private val tenants: TenantSource, private val registry: MeterRegistry) {
    private val values = ConcurrentHashMap<String, AtomicReference<Double>>()

    /** Liest alle Mandanten; Rückgabe: Anzahl erfolgreich gelesener Mandanten. */
    fun refresh(): Int {
        var ok = 0
        for (t in runCatching { tenants.tenants().distinct() }.getOrDefault(emptyList())) {
            val s = runCatching { snapshot(t) }.getOrNull() ?: run { SafeTelemetry.recordFailure(); null } ?: continue
            publish(t, s); ok++
        }
        return ok
    }

    fun snapshot(t: TenantId): QueueSnapshot = tx.run(t, readOnly = true) { j ->
        val outbox = j.queryForMap(
            """SELECT count(*) FILTER (WHERE dead_lettered_at IS NULL) AS depth, count(*) FILTER (WHERE dead_lettered_at IS NOT NULL) AS dead,
                      coalesce(extract(epoch FROM (clock_timestamp() - min(recorded_at) FILTER (WHERE dead_lettered_at IS NULL))), 0) AS age
                 FROM taktovia.outbox WHERE tenant_id = taktovia.current_tenant() AND published_at IS NULL""")
        val inbox = j.queryForMap(
            """SELECT coalesce(max(extract(epoch FROM (received_at - occurred_at))), 0) AS lag, count(*) FILTER (WHERE outcome = 'GAP_SNAPSHOT') AS gaps
                 FROM taktovia.inbox WHERE tenant_id = taktovia.current_tenant() AND received_at > clock_timestamp() - interval '5 minutes'""")
        val op = j.queryForMap(
            """SELECT count(*) AS open_ops,
                      count(*) FILTER (WHERE status = 'RUNNING' AND lease_until < clock_timestamp()) AS lease_overdue,
                      count(*) FILTER (WHERE deadline_at < clock_timestamp()) AS deadline_overdue
                 FROM taktovia.operation WHERE tenant_id = taktovia.current_tenant() AND status IN ('QUEUED', 'RUNNING', 'RECONCILING')""")
        val pay = j.queryForObject(
            "SELECT count(*) FROM taktovia.payment_intent WHERE tenant_id = taktovia.current_tenant() AND state IN ('CAPTURE_UNKNOWN', 'RECONCILING')", Long::class.java) ?: 0L
        fun n(m: Map<String, Any?>, k: String) = (m[k] as Number).toLong()
        fun d(m: Map<String, Any?>, k: String) = (m[k] as Number).toDouble()
        QueueSnapshot(n(outbox, "depth"), n(outbox, "dead"), d(outbox, "age"), d(inbox, "lag"), n(inbox, "gaps"),
            n(op, "open_ops"), n(op, "lease_overdue"), n(op, "deadline_overdue"), pay)
    }

    private fun publish(t: TenantId, s: QueueSnapshot) = SafeTelemetry.safely {
        set(t, "taktovia_outbox_depth", s.outboxDepth.toDouble())
        set(t, "taktovia_outbox_dead_lettered", s.outboxDeadLettered.toDouble())
        set(t, "taktovia_outbox_oldest_age_seconds", s.outboxOldestAgeSeconds)
        set(t, "taktovia_inbox_lag_seconds", s.inboxLagSeconds)
        set(t, "taktovia_inbox_gap_snapshots", s.inboxGapSnapshots.toDouble())
        set(t, "taktovia_operation_open", s.operationsOpen.toDouble())
        set(t, "taktovia_operation_lease_overdue", s.operationLeasesOverdue.toDouble())
        set(t, "taktovia_operation_deadline_overdue", s.operationsDeadlineOverdue.toDouble())
        set(t, "taktovia_payment_intent_indeterminate", s.paymentIntentsIndeterminate.toDouble())
    }

    private fun set(t: TenantId, name: String, v: Double) {
        val key = "$name|${t.value}"
        values.computeIfAbsent(key) {
            AtomicReference(v).also { ref -> Gauge.builder(name) { ref.get() }.tag("tenant_id", t.value.toString()).register(registry) }
        }.set(v)
    }
}

package de.taktovia.platform.alert

import io.micrometer.core.instrument.MeterRegistry
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimaler Betriebsalarmkanal (WP-M1-47): Warnung melden (Metrik, strukturierte Logzeile), gleichartige zu einem offenen Alarm
 * zusammenfassen und genau einmal je Alarm an den Empfängerkanal zustellen; nicht erreichbarer Kanal -> Wiederholung mit Staffel.
 *
 * Zusammenfassungsregel (Vorschlag dieses Plans, im Pflichtenheft nicht beziffert): gleichartig = gleiche Quelle und gleiche Art;
 * ein Alarm bleibt offen, solange innerhalb von [quietWindow] (Standard 15 min) eine weitere gleichartige Warnung eintrifft; danach
 * (oder per [resolve]) schließt er und die nächste Warnung öffnet einen neuen. Eine höhere Schwere eskaliert den offenen Alarm (neue Zustellung).
 * Der Zustand liegt im Prozess (keine neuen Tabellen laut WP); über Instanzen hinweg fasst der Empfänger anhand von `dedupKey` zusammen.
 */
class OperatorAlertService(
    private val channel: AlertChannel,
    private val metrics: MeterRegistry,
    private val log: OperatorLog = Slf4jOperatorLog(),
    private val clock: Clock = Clock.systemUTC(),
    private val quietWindow: Duration = Duration.ofMinutes(15),
    private val retrySchedule: List<Duration> = listOf(1, 5, 30, 120, 600).map { Duration.ofSeconds(it.toLong()) },
) : OperatorAlertPort {
    private class Open(val fingerprint: String, val source: String, val kind: String, var severity: Severity, val openedAt: Instant,
                       var lastSeen: Instant, var count: Long, var lastCorrelation: UUID)
    private class Pending(val n: AlertNotification, var attempts: Int, var due: Instant)

    private val open = ConcurrentHashMap<String, Open>()
    private val pending = PriorityBlockingQueue<Pending>(16, compareBy { it.due })
    private val openGauge = AtomicInteger()

    init {
        metrics.gauge("taktovia_operator_alerts_open", openGauge)
        metrics.gauge("taktovia_operator_alert_pending_deliveries", pending) { it.size.toDouble() }
    }

    override fun report(warning: OperatorWarning) {
        OperatorWarningPolicy.validate(warning)
        val now = clock.instant()
        val fp = "${warning.source}/${warning.kind}"
        var notify: String? = null
        var snapshot: Open? = null
        open.compute(fp) { _, cur ->
            val alert = if (cur == null || Duration.between(cur.lastSeen, now) > quietWindow) {
                notify = "opened"; Open(fp, warning.source, warning.kind, warning.severity, now, now, 1, warning.correlationId)
            } else cur.also {
                if (warning.severity > it.severity) { it.severity = warning.severity; notify = "escalated" }
                it.lastSeen = now; it.count++; it.lastCorrelation = warning.correlationId
            }
            snapshot = alert; alert
        }
        openGauge.set(open.size)
        metrics.counter("taktovia_operator_warnings_total", "source", warning.source, "kind", warning.kind, "severity", warning.severity.name.lowercase()).increment()
        val state = notify ?: "grouped"
        log.line(json("operator_warning", "source" to warning.source, "kind" to warning.kind, "severity" to warning.severity.name.lowercase(),
            "correlation_id" to warning.correlationId, "tenant_ref" to warning.tenantRef, "alert" to state, "alert_count" to snapshot!!.count))
        if (notify != null) {
            val n = AlertNotification(fp, warning.source, warning.kind, snapshot!!.severity, warning.correlationId, warning.tenantRef, now,
                warning.attributes, notify!!)
            pending.add(Pending(n, 0, now))
            flush()
        }
    }

    /** Schließt einen offenen Alarm (z. B. nach Behebung). */
    fun resolve(source: String, kind: String) { open.remove("$source/$kind"); openGauge.set(open.size) }

    fun openAlerts(): List<OpenAlert> = open.values.map {
        OpenAlert(it.fingerprint, it.source, it.kind, it.severity, it.openedAt, it.lastSeen, it.count, it.lastCorrelation)
    }

    fun pendingDeliveries(): Int = pending.size

    /**
     * Stellt fällige Mitteilungen zu; bei Fehler wird mit der Staffel wiederholt (Mitteilung bleibt in der Warteschlange, nie verworfen).
     * Von einem Zeitgeber regelmäßig aufzurufen; `report` ruft es ebenfalls auf.
     */
    fun flush() {
        val now = clock.instant()
        while (true) {
            val p = pending.peek()?.takeIf { !it.due.isAfter(now) } ?: return
            if (!pending.remove(p)) continue
            try {
                channel.deliver(p.n)
                val delivered = clock.instant()
                metrics.counter("taktovia_operator_alert_deliveries_total", "result", "delivered").increment()
                log.line(json("operator_alert_delivered", "source" to p.n.source, "kind" to p.n.kind, "correlation_id" to p.n.correlationId,
                    "triggered_at" to p.n.triggeredAt, "delivered_at" to delivered, "attempts" to p.attempts + 1))
            } catch (e: Exception) {
                p.attempts++
                p.due = now.plus(retrySchedule[minOf(p.attempts - 1, retrySchedule.size - 1)])
                pending.add(p)
                metrics.counter("taktovia_operator_alert_deliveries_total", "result", "failed").increment()
                log.line(json("operator_alert_delivery_failed", "source" to p.n.source, "kind" to p.n.kind, "correlation_id" to p.n.correlationId,
                    "attempts" to p.attempts, "next_attempt_at" to p.due, "error" to e.javaClass.simpleName))
                return   // Kanal gerade nicht erreichbar: nächste Mitteilung erst nach Fälligkeit
            }
        }
    }

    companion object {
        /** Einfacher JSON-Zeilenbau; Werte sind Strings, Zahlen, UUIDs, Zeitpunkte oder null – nie Freitext aus Fachdaten. */
        fun json(event: String, vararg kv: Pair<String, Any?>): String = buildString {
            append("{\"event\":\"").append(event).append('"')
            for ((k, v) in kv) {
                append(",\"").append(k).append("\":")
                when (v) { null -> append("null"); is Number -> append(v); else -> append('"').append(v.toString().replace("\\", "\\\\").replace("\"", "\\\"")).append('"') }
            }
            append('}')
        }
    }
}

class Slf4jOperatorLog : OperatorLog {
    private val logger = org.slf4j.LoggerFactory.getLogger("taktovia.operator")
    override fun line(json: String) = logger.warn(json)
}

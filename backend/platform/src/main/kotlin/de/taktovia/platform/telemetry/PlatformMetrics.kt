package de.taktovia.platform.telemetry

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Technische Metriken der Plattform (WP-M5-03). Alle Methoden sind ausfallsicher ([SafeTelemetry]); Namen und Labels folgen [MetricConvention].
 * Sie ersetzen keine Fachkennzahlen aus `kataloge.csv`.
 */
class PlatformMetrics(private val registry: MeterRegistry) {
    private val buckets = arrayOf(0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0).map { Duration.ofMillis((it * 1000).toLong()) }.toTypedArray()

    /** HTTP-Latenz je Endpunktklasse (serverseitig, Kap. 08 §8.6); Histogrammgrenzen decken 1 s, 5 s und 10 s ab. */
    fun httpRequest(routeClass: RouteClass, method: String?, status: Int, duration: Duration) = SafeTelemetry.safely {
        Timer.builder("taktovia_http_server_request_seconds").tag("route_class", routeClass.label).tag("method", RouteClass.method(method))
            .tag("status_class", RouteClass.statusClass(status)).serviceLevelObjectives(*buckets).register(registry).record(duration)
    }

    /** Zustellverzögerung zwischen Commit (Aufzeichnung) und Veröffentlichung durch das Relay. */
    fun outboxPublishDelay(recordedAt: Instant, publishedAt: Instant) = SafeTelemetry.safely {
        Timer.builder("taktovia_outbox_publish_delay_seconds").serviceLevelObjectives(*buckets).register(registry)
            .record(Duration.between(recordedAt, publishedAt).coerceAtLeast(Duration.ZERO))
    }

    fun outboxResult(result: String, count: Long = 1) = SafeTelemetry.safely {
        registry.counter("taktovia_outbox_relay_events_total", "result", result).increment(count.toDouble())
    }

    /** Wartezeit auf Sperren bei Hold/Confirm (Aufrufer: `LockWait.measure`). */
    fun lockWait(operation: String, waited: Duration) = SafeTelemetry.safely {
        Timer.builder("taktovia_db_lock_wait_seconds").tag("operation", operation).serviceLevelObjectives(*buckets).register(registry).record(waited)
    }

    fun telemetryDropped(reason: String) = SafeTelemetry.safely { registry.counter("taktovia_telemetry_dropped_total", "reason", reason).increment() }

    /** Misst die Wartezeit eines Sperraufrufs (z. B. `SELECT … FOR UPDATE`) und liefert dessen Ergebnis; Fehler des Blocks bleiben unverändert. */
    fun <T> measureLock(operation: String, block: () -> T): T {
        val t0 = System.nanoTime()
        try { return block() } finally { lockWait(operation, Duration.ofNanos(System.nanoTime() - t0)) }
    }

    companion object { fun tenantTag(t: UUID) = MetricConvention.tenantLabel(t) }
}

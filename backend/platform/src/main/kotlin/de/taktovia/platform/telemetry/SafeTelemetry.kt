package de.taktovia.platform.telemetry

import java.util.concurrent.atomic.AtomicLong

/**
 * Telemetrie darf nie das Geschäft stören (WP-M5-03, konkurrierender Test: Sammler ausgefallen): jeder Aufruf der Instrumentierung läuft
 * über [safely]; Ausnahmen werden verschluckt und nur gezählt. Fachlicher Code ruft Metriken nur so auf.
 */
object SafeTelemetry {
    private val failures = AtomicLong()
    fun failures(): Long = failures.get()

    inline fun safely(block: () -> Unit) {
        try { block() } catch (e: Exception) { recordFailure() }
    }

    fun recordFailure() { failures.incrementAndGet() }
}

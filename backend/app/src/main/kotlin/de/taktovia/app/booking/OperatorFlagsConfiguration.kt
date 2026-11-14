package de.taktovia.app.booking

import de.taktovia.bookingdispatch.api.DueSignal
import de.taktovia.bookingdispatch.api.FlagCause
import de.taktovia.bookingdispatch.api.OperatorFlags
import de.taktovia.bookingdispatch.api.SignalSource
import de.taktovia.bookingdispatch.api.ThresholdSource
import de.taktovia.fleetsafety.api.AlertSettings
import de.taktovia.fleetsafety.api.FleetsafetyApi
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.outbox.TenantSource
import de.taktovia.stafftime.api.StafftimeApi
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Verdrahtung der Überfälligkeitsauswertung (WP-M3-42). **Schwellen** (Minuten, Planungsdefaults, der Parameterkatalog ist eingefroren und kennt die Parameter noch nicht): Abholung überfällig
 * `taktovia.overdue.pickup-tolerance-minutes` (5, `on_time_pickup_tolerance_min`), Standort veraltet `taktovia.overdue.position-stale-minutes` (Standard: `vehicle_unavailability_alert_min` der Alarm-Grundlage,
 * 15), Planbestätigung `taktovia.stafftime.plan-ack-overdue-minutes` (5, wie im Fahrerbetrieb), offener Klärfall `taktovia.overdue.review-open-minutes` (Standard: `alert_escalation_delay_min`, 10).
 * Die Meldungen aus dem Fahrerbetrieb kommen aus `staff-time`; Hinweise lösen Alarme der Alarm-Grundlage aus (WP-M3-52). **Lauf:** nur mit `taktovia.overdue.enabled=true` und einer
 * [TenantSource]-Bean des Betriebs; Takt `taktovia.overdue.poll-seconds` (30). Die Lease je Mandant liegt im Auswertungslauf selbst (zwei Instanzen werten nie gleichzeitig denselben Mandanten aus).
 */
@Configuration
class OperatorFlagsConfiguration {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun operatorFlags(ctxTx: ContextTransactions, fleet: FleetsafetyApi, staff: StafftimeApi, alertSettings: AlertSettings, env: Environment, tenants: ObjectProvider<TenantSource>): OperatorFlags {
        fun minutes(name: String, default: Long) = env.getProperty(name, Long::class.java, default).also { require(it in 1..10_080) { "$name außerhalb 1..10080 Minuten" } }
        val pickup = minutes("taktovia.overdue.pickup-tolerance-minutes", 5)
        val stale = minutes("taktovia.overdue.position-stale-minutes", alertSettings.vehicleUnavailabilityAlert.toMinutes().coerceAtLeast(1))
        val planAck = minutes("taktovia.stafftime.plan-ack-overdue-minutes", 5)
        val review = minutes("taktovia.overdue.review-open-minutes", alertSettings.escalationDelay.toMinutes().coerceAtLeast(1))
        val thresholds = ThresholdSource { _, _, cause ->
            when (cause) { FlagCause.PICKUP_OVERDUE -> pickup.toInt(); FlagCause.POSITION_STALE -> stale.toInt(); FlagCause.PLAN_ACK_MISSING -> planAck.toInt(); FlagCause.REVIEW_REQUIRED_OPEN -> review.toInt() }
        }
        val signals = SignalSource { tenant, _ ->
            staff.signals.staleVehicles(tenant, Duration.ofMinutes(stale)).map { DueSignal(FlagCause.POSITION_STALE, it.vehicleId, null, it.since, stale.toInt()) } +
                staff.planAcks.reportOverdue(tenant).map { DueSignal(FlagCause.PLAN_ACK_MISSING, it.vehicleId, it.runId, it.since, planAck.toInt()) } +
                staff.signals.openReviews(tenant, Duration.ofMinutes(review)).map { DueSignal(FlagCause.REVIEW_REQUIRED_OPEN, it.vehicleId, null, it.since, review.toInt()) }
        }
        val flags = OperatorFlags.create(ctxTx, thresholds = thresholds, signals = signals, alerts = fleet.alerts.port)
        val source = tenants.getIfAvailable()
        if (source != null && env.getProperty("taktovia.overdue.enabled", Boolean::class.java, false)) {
            val poll = env.getProperty("taktovia.overdue.poll-seconds", Long::class.java, 30)
            Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "overdue-evaluation").also { it.isDaemon = true } }.scheduleWithFixedDelay({
                runCatching { source.tenants().distinct().forEach { t -> flags.evaluate(t) } }.onFailure { log.warn("Überfälligkeitsauswertung: Lauf fehlgeschlagen ({})", it.javaClass.simpleName) }
            }, poll, poll, TimeUnit.SECONDS)
        }
        return flags
    }
}

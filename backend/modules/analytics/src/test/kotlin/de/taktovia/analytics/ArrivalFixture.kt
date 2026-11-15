package de.taktovia.analytics

import de.taktovia.analytics.api.AnalyticsApi
import de.taktovia.analytics.api.ArrivalTolerance
import de.taktovia.analytics.api.ArrivalToleranceSource
import de.taktovia.analytics.api.MetricFilter
import de.taktovia.analytics.api.MetricQuery
import de.taktovia.analytics.api.MetricValue
import de.taktovia.analytics.api.Metrics
import de.taktovia.analytics.api.PickupTolerance
import de.taktovia.analytics.api.PickupToleranceSource
import de.taktovia.analytics.api.ReportingPeriod
import de.taktovia.analytics.api.TimeFacts
import de.taktovia.platform.tenant.TenantId
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Testhilfe des Clusters wave15-c2 (WP-M4-54, Ankunftspünktlichkeit): Buchungen mit Abholfenster, zugesagter spätester Ankunft und Ist-Zeiten als Ereignisse der echten Zeitfaktenverarbeitung (Runtime-Rolle, RLS).
 * Die Toleranz kommt aus einer steuerbaren [ArrivalToleranceSource], der Abholgrenzwert aus einer [PickupToleranceSource] (die Anbindung an die Konfiguration prüft `ConfigArrivalToleranceSourceTest` in der Anwendung).
 * Berichtstag 1.12.2026 in Europe/Berlin.
 */
class ArrivalFixture(threshold: Int = 1, private val arrivals: ArrivalToleranceSource, private val pickups: PickupToleranceSource = fixedPickup(0)) : AutoCloseable {
    val base = AnalyticsFixture()
    val tf: TimeFacts = AnalyticsApi.createTimeFacts(base.tx)
    val metrics: Metrics = AnalyticsApi.createMetrics(base.tx, threshold)
    val day: ReportingPeriod = ReportingPeriod.ofDays(ZoneId.of("Europe/Berlin"), LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 1))

    fun tenant(): TenantId = base.tenant()

    /**
     * Eine bestätigte Buchung: Abholfenster 08:10 bis 08:15, zugesagte späteste Ankunft [arrivalLatest] (zugesagt zum Zeitpunkt [promisedAt]; `null` = keine Ankunftszusage), Ist-Abholung [pickupActual] und Ist-Ankunft
     * [arrivalActual] (jeweils `null` = kein Ereignis). Mit [arrivalFirst] trifft das Ankunftsereignis vor dem Abholereignis ein (vertauschte Reihenfolge).
     */
    fun booking(
        t: TenantId, service: UUID, arrivalLatest: String?, arrivalActual: String?, pickupActual: String? = "2026-12-01T08:12:00Z", promisedAt: String = "2026-12-01T06:00:00Z",
        arrivalFirst: Boolean = false, revision: Long = 1, booking: UUID = UUID.randomUUID(),
    ): UUID {
        val arrival = arrivalLatest?.let { ""","arrival_latest":"$it"""" } ?: ""
        tf.consume(t, base.envelope(t, "booking.confirmed", booking, revision, promisedAt,
            """{"booking_id":"$booking","hold_id":"${UUID.randomUUID()}","service_id":"$service","run_id":"${UUID.randomUUID()}","run_revision":1,"pickup_earliest":"2026-12-01T08:10:00Z","pickup_latest":"2026-12-01T08:15:00Z"$arrival,"gross_minor":850,"currency":"EUR","capacity_vector":{"seats":1}}""").second)
        val steps = listOfNotNull(pickupActual?.let { "CHECK_IN" to it }, arrivalActual?.let { "CHECK_OUT" to it }).let { if (arrivalFirst) it.reversed() else it }
        steps.forEach { (type, at) -> tf.consume(t, driver(t, booking, type, at).second) }
        return booking
    }

    private fun driver(t: TenantId, booking: UUID, type: String, effective: String) =
        base.envelope(t, "driver.event_recorded", UUID.randomUUID(), 1, effective,
            """{"event_id":"${UUID.randomUUID()}","shift_id":"${UUID.randomUUID()}","vehicle_id":"${UUID.randomUUID()}","event_type":"$type","outcome":"APPLIED","route_stop_id":"${UUID.randomUUID()}","received_at":"$effective","booking_id":"$booking","reason_code":null,"effective_at":"$effective","offline_recorded":false,"no_show_reason":null,"no_show_wait_seconds":null,"no_show_grace_min":null}""")

    fun eval(t: TenantId, metric: String, filter: MetricFilter = MetricFilter(), arrivalSource: ArrivalToleranceSource = arrivals): MetricValue =
        metrics.evaluate(t, MetricQuery(day, metric, null, filter), pickups, arrivalSource).single()

    override fun close() = base.close()

    companion object {
        fun fixedPickup(minutes: Int) = PickupToleranceSource { _, _, _, _ -> PickupTolerance(minutes, "TENANT", UUID.randomUUID(), 1) }

        /** Fester Wert für alle Dienste: Toleranz [tolerance], Karenz [grace] (Ebene Mandant mit synthetischer Revision). */
        fun fixed(tolerance: Int, grace: Int = 0, tolRev: UUID = UUID.randomUUID(), graceRev: UUID = UUID.randomUUID()) =
            ArrivalToleranceSource { _, _, _, _ -> ArrivalTolerance(PickupTolerance(tolerance, "TENANT", tolRev, 1), PickupTolerance(grace, "TENANT", graceRev, 1)) }
    }
}

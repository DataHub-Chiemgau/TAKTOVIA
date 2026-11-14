package de.taktovia.analytics

import de.taktovia.analytics.api.AnalyticsApi
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
 * Testhilfe des Clusters wave14-c10 (WP-M4-53, Abholpünktlichkeit): Buchungen mit Zusagefenster und Ist-Abholung als Ereignisse der echten Zeitfaktenverarbeitung (Runtime-Rolle, RLS). Der Grenzwert kommt
 * aus einer steuerbaren [PickupToleranceSource] (die Anbindung an die Konfiguration prüft `ConfigPickupToleranceSourceTest` in der Anwendung). Berichtstag 1.12.2026 in Europe/Berlin.
 */
class PickupFixture(threshold: Int = 1, private val source: PickupToleranceSource) : AutoCloseable {
    val base = AnalyticsFixture()
    val tf: TimeFacts = AnalyticsApi.createTimeFacts(base.tx)
    val metrics: Metrics = AnalyticsApi.createMetrics(base.tx, threshold)
    val day: ReportingPeriod = ReportingPeriod.ofDays(ZoneId.of("Europe/Berlin"), LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 1))

    fun tenant(): TenantId = base.tenant()

    /**
     * Eine bestätigte Buchung mit Abholfenster [earliest]..[latest] (Zusage zum Zeitpunkt [promisedAt]) und optionaler Ist-Abholung [actual] (Einstieg) sowie Ist-Ankunft [arrivalActual];
     * [arrivalLatest] ist die zugesagte späteste Ankunft. Zeiten als ISO-Instants (UTC).
     */
    fun booking(
        t: TenantId, service: UUID, earliest: String, latest: String, actual: String?, promisedAt: String = "2026-12-01T06:00:00Z",
        arrivalLatest: String? = null, arrivalActual: String? = null, revision: Long = 1, booking: UUID = UUID.randomUUID(),
    ): UUID {
        val run = UUID.randomUUID()
        val arrival = arrivalLatest?.let { ""","arrival_latest":"$it"""" } ?: ""
        tf.consume(t, base.envelope(t, "booking.confirmed", booking, revision, promisedAt,
            """{"booking_id":"$booking","hold_id":"${UUID.randomUUID()}","service_id":"$service","run_id":"$run","run_revision":1,"pickup_earliest":"$earliest","pickup_latest":"$latest"$arrival,"gross_minor":850,"currency":"EUR","capacity_vector":{"seats":1}}""").second)
        actual?.let { tf.consume(t, driver(t, booking, "CHECK_IN", it).second) }
        arrivalActual?.let { tf.consume(t, driver(t, booking, "CHECK_OUT", it).second) }
        return booking
    }

    private fun driver(t: TenantId, booking: UUID, type: String, effective: String) =
        base.envelope(t, "driver.event_recorded", UUID.randomUUID(), 1, effective,
            """{"event_id":"${UUID.randomUUID()}","shift_id":"${UUID.randomUUID()}","vehicle_id":"${UUID.randomUUID()}","event_type":"$type","outcome":"APPLIED","route_stop_id":"${UUID.randomUUID()}","received_at":"$effective","booking_id":"$booking","reason_code":null,"effective_at":"$effective","offline_recorded":false,"no_show_reason":null,"no_show_wait_seconds":null,"no_show_grace_min":null}""")

    fun eval(t: TenantId, metric: String, filter: MetricFilter = MetricFilter(), tolerances: PickupToleranceSource = source): MetricValue = metrics.evaluate(t, MetricQuery(day, metric, null, filter), tolerances).single()

    override fun close() = base.close()

    companion object {
        /** Fester Grenzwert für alle Dienste, ohne Konfigurationsrevision (Ebene Mandant mit synthetischer Revision). */
        fun fixed(minutes: Int, rev: UUID = UUID.randomUUID()) = PickupToleranceSource { _, _, _, _ -> PickupTolerance(minutes, "TENANT", rev, 1) }
    }
}

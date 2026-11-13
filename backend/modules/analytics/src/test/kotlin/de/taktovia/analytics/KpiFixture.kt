package de.taktovia.analytics

import de.taktovia.analytics.api.AnalyticsApi
import de.taktovia.analytics.api.ConsumeOutcome
import de.taktovia.analytics.api.MetricFilter
import de.taktovia.analytics.api.Metrics
import de.taktovia.analytics.api.ReportingPeriod
import de.taktovia.platform.tenant.TenantId
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Testhilfe des Clusters wave13-c6 (WP-M4-46 bis -50): Kennzahlregister und Versuchsereignisse auf der echten Datenbank (Runtime-Rolle, RLS). Baut auf [AnalyticsFixture] auf (Mandanten, Hüllen, Abzüge)
 * und ergänzt Versuchsereignisse mit Ausgang, Gründen, Kanal und Testkennzeichen sowie den Berichtszeitraum des 1.12.2026 in Europe/Berlin.
 */
class KpiFixture : AutoCloseable {
    val base = AnalyticsFixture()
    val metrics: Metrics = AnalyticsApi.createMetrics(base.tx, smallGroupThreshold = 3)
    val service: UUID = UUID.randomUUID()

    /** Berichtstag 1.12.2026 (Europe/Berlin): [2026-11-30T23:00Z, 2026-12-01T23:00Z). */
    val day: ReportingPeriod = ReportingPeriod.ofDays(ZoneId.of("Europe/Berlin"), LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 1))

    fun tenant(): TenantId = base.tenant()

    /** Ereignis `booking.attempt_recorded` (Aggregat = Versuch); [revision] > 1 ist die Wiederholung (technischer Retry) desselben Versuchs. */
    fun attempt(
        t: TenantId, id: UUID = UUID.randomUUID(), revision: Long = 1, outcome: String = "RECORDED", primary: String? = null, secondary: List<String> = emptyList(), at: String = "2026-12-01T05:00:00Z",
        channel: String = "WEB", test: Boolean = false, serviceId: UUID = service, eventId: UUID = UUID.randomUUID(),
    ): Triple<UUID, UUID, String> {
        val payload = buildString {
            append("""{"attempt_id":"$id","service_id":"$serviceId","outcome":"$outcome","time_mode":"DEPARTURE","requested_at":"$at","passengers":1,"wheelchair":0,"coarse_origin":"Z-1","coarse_destination":"Z-2","channel":"$channel"""")
            if (primary != null) append(""","primary_reason":"$primary"""")
            if (secondary.isNotEmpty()) append(""","secondary_reasons":[${secondary.joinToString(",") { "\"$it\"" }}]""")
            if (test) append(""","is_test":true""")
            append("}")
        }
        val (eid, json) = base.envelope(t, "booking.attempt_recorded", id, revision, at, payload, eventId)
        return Triple(id, eid, json)
    }

    fun feed(t: TenantId, vararg events: Triple<UUID, UUID, String>): List<ConsumeOutcome> = events.map { base.analytics.facts.consume(t, it.third) }

    /** Fixture PH-AT-021: zehn Anfragen, davon drei Ablehnungen (zwei mit Sekundärgründen), eine technisch fehlgeschlagene und anschließend erfolgreich wiederholte Anfrage. */
    fun ph021(t: TenantId): List<UUID> {
        val ids = (1..10).map { UUID.randomUUID() }
        val ev = ids.mapIndexed { i, id ->
            when (i) {
                0 -> attempt(t, id, outcome = "REJECTED", primary = "NO_CAPACITY", secondary = listOf("OUTSIDE_AREA"))
                1 -> attempt(t, id, outcome = "REJECTED", primary = "LEAD_TIME", secondary = listOf("OUTSIDE_HOURS", "NO_STAFF"))
                2 -> attempt(t, id, outcome = "REJECTED", primary = "OUTSIDE_AREA")
                else -> attempt(t, id)
            }
        }
        feed(t, *ev.toTypedArray())
        return ids
    }

    override fun close() = base.close()
}

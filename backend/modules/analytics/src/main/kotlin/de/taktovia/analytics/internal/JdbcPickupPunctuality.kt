package de.taktovia.analytics.internal

import de.taktovia.analytics.api.MetricFilter
import de.taktovia.analytics.api.PickupToleranceSource
import de.taktovia.analytics.api.ReportingPeriod
import de.taktovia.platform.tenant.TenantId
import org.springframework.jdbc.core.JdbcOperations
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * Abholpünktlichkeit gegen das Zusagefenster (WP-M4-53, LH-I.6-KPI-007) auf den Zeitfakten (WP-M4-52).
 *
 * Verspätung = Ist-Abholung (früheste) minus Ende des zugesagten Abholfensters der **höchsten Zusageversion**; pünktlich heißt Verspätung höchstens Grenzwert, früh ist pünktlich. Der Grenzwert ist je Buchung
 * und Zusageversion **einmal** zum Vergleichsstichtag (Zusagezeitpunkt) aufgelöst und unveränderlich gebunden (`pickup_tolerance_binding`): eine spätere Änderung des Grenzwerts verändert keine Klassifikation.
 * Alle Zähler, der Datenstand und die verwendeten Konfigurationsrevisionen entstehen in einem Statement ([COUNTS], ein Lesestand).
 */
internal class JdbcPickupPunctuality {

    class Counts(
        /** Abholungen mit Ist-Zeit und Zusagefenster im Zeitraum (Nenner der mittleren Verspätung). */
        val assessable: Long, val assessableUnfiltered: Long,
        /** Davon mit gebundenem Grenzwert (Nenner der Pünktlichkeit) und pünktlich. */
        val bound: Long, val onTime: Long, val boundUnfiltered: Long,
        /** Summe der positiven Verspätungen in Sekunden über alle bewertbaren Abholungen. */
        val lateSeconds: Long,
        val asOf: Timestamp?, val sourceEvents: Long, val hasData: Boolean, val versionsJson: String,
    )

    /** Bindet fehlende Grenzwerte (Vergleichsstichtag je Zusageversion) und liefert danach die Zähler im selben Lesestand. */
    fun counts(j: JdbcOperations, tenant: TenantId, p: ReportingPeriod, f: MetricFilter, source: PickupToleranceSource, bind: Boolean): Counts {
        if (bind) bindMissing(j, tenant, p, f, source)
        return j.query(COUNTS, { rs, _ ->
            Counts(rs.getLong("assessable"), rs.getLong("assessable_unfiltered"), rs.getLong("bound"), rs.getLong("on_time"), rs.getLong("bound_unfiltered"), rs.getLong("late_seconds"),
                rs.getTimestamp("as_of"), rs.getLong("source_events"), rs.getBoolean("has_data"), rs.getString("versions"))
        }, Timestamp.from(p.from), Timestamp.from(p.to), f.serviceId?.toString(), f.serviceId?.toString()).single()
    }

    private class Missing(val booking: UUID, val version: Long, val service: UUID?, val boundAt: Instant)

    private fun bindMissing(j: JdbcOperations, tenant: TenantId, p: ReportingPeriod, f: MetricFilter, source: PickupToleranceSource) {
        val missing = j.query(MISSING, { rs, _ -> Missing(rs.getObject("booking_id", UUID::class.java), rs.getLong("promise_version"), rs.getObject("service_id", UUID::class.java), rs.getTimestamp("bound_at").toInstant()) },
            Timestamp.from(p.from), Timestamp.from(p.to), f.serviceId?.toString(), f.serviceId?.toString())
        if (missing.isEmpty()) return
        for (m in missing) {
            val t = source.resolve(j, tenant, m.service, m.boundAt) ?: continue   // die Konfiguration cached selbst (Schlüssel mit den beteiligten Revisionen)
            j.update(INSERT_BINDING, m.booking, m.version, m.service, Timestamp.from(m.boundAt), t.minutes, t.level, t.configRevisionId, t.configRevision)
        }
    }

    companion object {
        val METRICS = setOf("pickup_on_time", "mean_pickup_delay")

        /** Höchste Zusageversion der Abholung je Buchung und früheste Ist-Abholung (dieselbe Regel wie `time_fact_delay`). */
        private const val BASE = """
            promised AS (SELECT DISTINCT ON (f.booking_id) f.booking_id, f.promise_version, f.value_at AS target_at, f.service_id, coalesce(f.promised_at, f.recorded_at) AS bound_at
                         FROM taktovia.time_fact f WHERE f.stop_event = 'PICKUP' AND f.time_kind = 'PROMISED' ORDER BY f.booking_id, f.promise_version DESC, f.seq DESC),
            actual AS (SELECT DISTINCT ON (f.booking_id) f.booking_id, f.value_at AS actual_at FROM taktovia.time_fact f
                       WHERE f.stop_event = 'PICKUP' AND f.time_kind = 'ACTUAL' ORDER BY f.booking_id, f.value_at, f.seq),
            q AS (SELECT p.booking_id, p.promise_version, p.service_id, p.bound_at, round(extract(epoch FROM (a.actual_at - p.target_at)))::bigint AS delay_s
                  FROM actual a JOIN promised p ON p.booking_id = a.booking_id WHERE a.actual_at >= ? AND a.actual_at < ?),
            sel AS (SELECT q.* FROM q WHERE CAST(? AS uuid) IS NULL OR q.service_id = CAST(? AS uuid))"""

        /** Abholungen im Zeitraum ohne Bindung dieser Zusageversion. */
        const val MISSING = "WITH$BASE SELECT s.booking_id, s.promise_version, s.service_id, s.bound_at FROM sel s " +
            "WHERE NOT EXISTS (SELECT 1 FROM taktovia.pickup_tolerance_binding b WHERE b.booking_id = s.booking_id AND b.promise_version = s.promise_version) ORDER BY s.bound_at, s.booking_id"

        const val INSERT_BINDING = "INSERT INTO taktovia.pickup_tolerance_binding(tenant_id, booking_id, promise_version, service_id, bound_at, tolerance_min, config_level, config_revision_id, config_revision) " +
            "VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING"

        /**
         * Zähler, Datenstand und Revisionen in einem Statement. Datenstand = jüngste Aufnahmezeit der Zeitfakten; die Zahl der Quellzeilen zählt Zeitfakten **und** Bindungen (beide nur angehängt), damit
         * derselbe Stand genau einen Snapshot ergibt und jede neue Zeile einen neuen.
         */
        const val COUNTS = """
            WITH$BASE,
            j AS (SELECT s.delay_s, b.tolerance_min, b.config_level, b.config_revision_id, b.config_revision FROM sel s
                  LEFT JOIN taktovia.pickup_tolerance_binding b ON b.booking_id = s.booking_id AND b.promise_version = s.promise_version),
            v AS (SELECT config_level, config_revision_id, config_revision, tolerance_min, count(*) AS n FROM j WHERE tolerance_min IS NOT NULL GROUP BY 1, 2, 3, 4)
            SELECT (SELECT count(*) FROM j) AS assessable, (SELECT count(*) FROM q) AS assessable_unfiltered,
                   (SELECT count(*) FROM j WHERE tolerance_min IS NOT NULL) AS bound,
                   (SELECT count(*) FROM j WHERE tolerance_min IS NOT NULL AND delay_s <= tolerance_min * 60) AS on_time,
                   (SELECT count(*) FROM q JOIN taktovia.pickup_tolerance_binding b ON b.booking_id = q.booking_id AND b.promise_version = q.promise_version) AS bound_unfiltered,
                   (SELECT coalesce(sum(greatest(delay_s, 0)), 0) FROM j) AS late_seconds,
                   (SELECT max(recorded_at) FROM taktovia.time_fact) AS as_of,
                   (SELECT count(*) FROM taktovia.time_fact) + (SELECT count(*) FROM taktovia.pickup_tolerance_binding) AS source_events,
                   EXISTS (SELECT 1 FROM taktovia.time_fact) AS has_data,
                   coalesce((SELECT jsonb_agg(jsonb_build_object('parameter', 'on_time_pickup_tolerance_min', 'level', config_level, 'config_revision_id', config_revision_id, 'config_revision', config_revision,
                             'value', tolerance_min, 'bookings', n) ORDER BY config_level, config_revision, tolerance_min)::text FROM v), '[]') AS versions"""
    }
}

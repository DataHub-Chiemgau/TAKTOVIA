package de.taktovia.analytics.internal

import de.taktovia.analytics.api.ArrivalToleranceSource
import de.taktovia.analytics.api.MetricFilter
import de.taktovia.analytics.api.ReportingPeriod
import de.taktovia.platform.tenant.TenantId
import org.springframework.jdbc.core.JdbcOperations
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * Ankunftspünktlichkeit gegen die zugesagte späteste Ankunft (WP-M4-54, LH-I.6-KPI-008) auf den Zeitfakten (WP-M4-52), **getrennt von der Abholung**: eigene Zeilen (`stop_event = ARRIVAL`), eigene Bindung,
 * kein gemeinsames Flag. Verspätung = Ist-Ankunft (früheste) minus zugesagte späteste Ankunft der höchsten Zusageversion (bei gewähltem Servicefenster dessen Ende); pünktlich heißt Verspätung höchstens
 * wirksame Toleranz, früh ist pünktlich. Die Toleranz ist je Buchung und Zusageversion einmal zum Vergleichsstichtag aufgelöst und unveränderlich gebunden (`arrival_tolerance_binding`).
 * Fahrten ohne Ankunftsereignis sind nie im Nenner; Ankünfte ohne zugesagte Ankunft werden gesondert gezählt ([Counts.withoutPromise]). Alle Zähler, der Datenstand und die Konfigurationsrevisionen
 * entstehen in einem Statement ([COUNTS], ein Lesestand).
 */
internal class JdbcArrivalPunctuality {

    class Counts(
        /** Ankünfte mit Ist-Zeit und Zusage im Zeitraum (Nenner der mittleren Verspätung). */
        val assessable: Long, val assessableUnfiltered: Long,
        /** Davon mit gebundener Toleranz (Nenner der Pünktlichkeit) und pünktlich. */
        val bound: Long, val onTime: Long, val boundUnfiltered: Long,
        /** Summe der positiven Verspätungen in Sekunden über alle bewertbaren Ankünfte. */
        val lateSeconds: Long,
        /** Ankünfte mit Ist-Zeit im Zeitraum, aber ohne zugesagte Ankunft (der Dienst ist nicht bekannt: nur ungefiltert aussagekräftig). */
        val withoutPromise: Long,
        val asOf: Timestamp?, val sourceEvents: Long, val hasData: Boolean, val versionsJson: String,
    )

    fun counts(j: JdbcOperations, tenant: TenantId, p: ReportingPeriod, f: MetricFilter, source: ArrivalToleranceSource, bind: Boolean): Counts {
        if (bind) bindMissing(j, tenant, p, f, source)
        return j.query(COUNTS, { rs, _ ->
            Counts(rs.getLong("assessable"), rs.getLong("assessable_unfiltered"), rs.getLong("bound"), rs.getLong("on_time"), rs.getLong("bound_unfiltered"), rs.getLong("late_seconds"), rs.getLong("without_promise"),
                rs.getTimestamp("as_of"), rs.getLong("source_events"), rs.getBoolean("has_data"), rs.getString("versions"))
        }, Timestamp.from(p.from), Timestamp.from(p.to), f.serviceId?.toString(), f.serviceId?.toString(), Timestamp.from(p.from), Timestamp.from(p.to)).single()
    }

    private class Missing(val booking: UUID, val version: Long, val service: UUID?, val boundAt: Instant)

    private fun bindMissing(j: JdbcOperations, tenant: TenantId, p: ReportingPeriod, f: MetricFilter, source: ArrivalToleranceSource) {
        val missing = j.query(MISSING, { rs, _ -> Missing(rs.getObject("booking_id", UUID::class.java), rs.getLong("promise_version"), rs.getObject("service_id", UUID::class.java), rs.getTimestamp("bound_at").toInstant()) },
            Timestamp.from(p.from), Timestamp.from(p.to), f.serviceId?.toString(), f.serviceId?.toString())
        for (m in missing) {
            val t = source.resolve(j, tenant, m.service, m.boundAt) ?: continue
            j.update(INSERT_BINDING, m.booking, m.version, m.service, Timestamp.from(m.boundAt),
                t.tolerance.minutes, t.tolerance.level, t.tolerance.configRevisionId, t.tolerance.configRevision, t.grace.minutes, t.grace.level, t.grace.configRevisionId, t.grace.configRevision)
        }
    }

    companion object {
        val METRICS = setOf("arrival_on_time", "mean_arrival_delay")

        /** Höchste Zusageversion der Ankunft je Buchung und früheste Ist-Ankunft (dieselbe Regel wie `time_fact_delay`). */
        private const val BASE = """
            promised AS (SELECT DISTINCT ON (f.booking_id) f.booking_id, f.promise_version, f.value_at AS target_at, f.service_id, coalesce(f.promised_at, f.recorded_at) AS bound_at
                         FROM taktovia.time_fact f WHERE f.stop_event = 'ARRIVAL' AND f.time_kind = 'PROMISED' ORDER BY f.booking_id, f.promise_version DESC, f.seq DESC),
            actual AS (SELECT DISTINCT ON (f.booking_id) f.booking_id, f.value_at AS actual_at FROM taktovia.time_fact f
                       WHERE f.stop_event = 'ARRIVAL' AND f.time_kind = 'ACTUAL' ORDER BY f.booking_id, f.value_at, f.seq),
            q AS (SELECT p.booking_id, p.promise_version, p.service_id, p.bound_at, round(extract(epoch FROM (a.actual_at - p.target_at)))::bigint AS delay_s
                  FROM actual a JOIN promised p ON p.booking_id = a.booking_id WHERE a.actual_at >= ? AND a.actual_at < ?),
            sel AS (SELECT q.* FROM q WHERE CAST(? AS uuid) IS NULL OR q.service_id = CAST(? AS uuid))"""

        /** Ankünfte im Zeitraum ohne Bindung dieser Zusageversion. */
        const val MISSING = "WITH$BASE SELECT s.booking_id, s.promise_version, s.service_id, s.bound_at FROM sel s " +
            "WHERE NOT EXISTS (SELECT 1 FROM taktovia.arrival_tolerance_binding b WHERE b.booking_id = s.booking_id AND b.promise_version = s.promise_version) ORDER BY s.bound_at, s.booking_id"

        const val INSERT_BINDING = "INSERT INTO taktovia.arrival_tolerance_binding(tenant_id, booking_id, promise_version, service_id, bound_at, tolerance_min, tolerance_level, tolerance_revision_id, tolerance_revision, " +
            "grace_min, grace_level, grace_revision_id, grace_revision) VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING"

        /**
         * Zähler, Datenstand und Revisionen in einem Statement. Datenstand = jüngste Aufnahmezeit der Zeitfakten; die Zahl der Quellzeilen zählt Zeitfakten **und** Bindungen (beide nur angehängt), damit derselbe
         * Stand genau einen Snapshot ergibt und jede neue Zeile einen neuen. Die beiden Parameter stehen je mit Ebene, Konfigurationsrevision und Wert in `versions`.
         */
        const val COUNTS = """
            WITH$BASE,
            j AS (SELECT s.delay_s, b.applied_min, b.tolerance_min, b.tolerance_level, b.tolerance_revision_id, b.tolerance_revision, b.grace_min, b.grace_level, b.grace_revision_id, b.grace_revision FROM sel s
                  LEFT JOIN taktovia.arrival_tolerance_binding b ON b.booking_id = s.booking_id AND b.promise_version = s.promise_version),
            vt AS (SELECT 'on_time_arrival_tolerance_min' AS parameter, tolerance_level AS level, tolerance_revision_id AS rid, tolerance_revision AS rev, tolerance_min AS val, count(*) AS n FROM j WHERE applied_min IS NOT NULL GROUP BY 1, 2, 3, 4, 5),
            vg AS (SELECT 'arrival_grace_min' AS parameter, grace_level AS level, grace_revision_id AS rid, grace_revision AS rev, grace_min AS val, count(*) AS n FROM j WHERE applied_min IS NOT NULL GROUP BY 1, 2, 3, 4, 5),
            v AS (SELECT * FROM vt UNION ALL SELECT * FROM vg)
            SELECT (SELECT count(*) FROM j) AS assessable, (SELECT count(*) FROM q) AS assessable_unfiltered,
                   (SELECT count(*) FROM j WHERE applied_min IS NOT NULL) AS bound,
                   (SELECT count(*) FROM j WHERE applied_min IS NOT NULL AND delay_s <= applied_min * 60) AS on_time,
                   (SELECT count(*) FROM q JOIN taktovia.arrival_tolerance_binding b ON b.booking_id = q.booking_id AND b.promise_version = q.promise_version) AS bound_unfiltered,
                   (SELECT coalesce(sum(greatest(delay_s, 0)), 0) FROM j) AS late_seconds,
                   (SELECT count(*) FROM actual a WHERE a.actual_at >= ? AND a.actual_at < ? AND NOT EXISTS (SELECT 1 FROM promised p WHERE p.booking_id = a.booking_id)) AS without_promise,
                   (SELECT max(recorded_at) FROM taktovia.time_fact) AS as_of,
                   (SELECT count(*) FROM taktovia.time_fact) + (SELECT count(*) FROM taktovia.arrival_tolerance_binding) AS source_events,
                   EXISTS (SELECT 1 FROM taktovia.time_fact) AS has_data,
                   coalesce((SELECT jsonb_agg(jsonb_build_object('parameter', parameter, 'level', level, 'config_revision_id', rid, 'config_revision', rev, 'value', val, 'bookings', n)
                             ORDER BY parameter, level, rev, val)::text FROM v), '[]') AS versions"""
    }
}

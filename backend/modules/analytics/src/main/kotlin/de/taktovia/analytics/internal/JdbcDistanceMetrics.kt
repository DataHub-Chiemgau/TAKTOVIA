package de.taktovia.analytics.internal

import de.taktovia.analytics.api.MetricDefinition
import de.taktovia.analytics.api.MetricFilter
import de.taktovia.analytics.api.MetricReason
import de.taktovia.analytics.api.MetricState
import de.taktovia.analytics.api.MetricValue
import de.taktovia.analytics.api.ReportingPeriod
import de.taktovia.platform.money.MoneyRounding
import org.springframework.jdbc.core.JdbcOperations
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.security.MessageDigest
import java.sql.Date
import java.sql.Timestamp
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * Kilometer-, Betriebsstunden-, Fahrgastkilometer- (WP-M4-56, `LH-6.14-ANA-002`, `LH-I.6-KPI-013`) und Auslastungskennzahlen (WP-M4-57, `LH-I.6-KPI-012`) auf der Kilometerklassifikation (WP-M4-42).
 *
 * Alle Summen und der Datenstand entstehen in **einem** SQL-Statement ([READ], ein Lesestand): die Rechnung ist eine reine Funktion dieser Zeilen (ganzzahlig, Meter und Millisekunden, Rundung über
 * [MoneyRounding]). Eingang ist je Fahrzeug und Betriebstag (UTC) der jüngste Klassifikationslauf; ein später eintreffender Lauf (neue Positionen, neue Fahrerereignisse, neue Kapazität) ergibt einen neuen
 * Datenstand und damit einen neuen Snapshot, ein früherer Snapshot bleibt unverändert. Was keiner Kategorie zuordenbar ist, steht als `UNASSIGNED` in der Zerlegung (`components`) und senkt die
 * Vollständigkeit: es wird nie auf andere Kategorien verteilt und nie ein scheingenauer Wert ausgewiesen.
 *
 * Begriffe: Fahrzeugkilometer gesamt = alle Kategorien; Fahrzeugkilometer im Betriebsdienst = Fahrgast + Leer + nicht zuordenbar (Überführung und Werkstattfahrt getrennt); Betriebszeit = Fahrgast, Leer
 * und Überführung. Auslastung: angebotene Plätze = Sitzplätze des Fahrzeugs (Lauf) auf den Abschnitten Fahrgast und Leer, belegte Plätze = Fahrgäste an Bord; Rollstuhlplätze werden nicht den Sitzplätzen
 * zugeschlagen (Offener Punkt 31).
 */
internal class JdbcDistanceMetrics(private val mapper: JsonMapper) {

    private class Read(
        val km: Map<String, Long>, val ms: Map<String, Long>, val paxMeters: Long, val paxMs: Long, val seatMeters: Long, val seatMs: Long, val occMetersKnown: Long, val passengerMeters: Long,
        val runs: Int, val runsIncomplete: Int, val capRuns: Int, val seatRuns: Int, val asOf: Instant?, val sourceEvents: Long,
    )

    /** Wert (Liste mit einem Element) der Kennzahl [d]; schreibt den Snapshot. */
    fun evaluate(j: JdbcOperations, d: MetricDefinition, period: ReportingPeriod, filter: MetricFilter): MetricValue {
        val r = read(j, period, filter.vehicleId)
        fun k(c: String) = r.km.getValue(c)
        val totalKm = r.km.values.sum(); val serviceKm = k("PASSENGER") + k("EMPTY") + k("UNASSIGNED")
        val components = r.km.mapValues { BigDecimal(it.value).movePointLeft(3) }
        fun result(state: MetricState, reason: MetricReason?, value: BigDecimal?, num: BigDecimal?, den: BigDecimal?, cov: BigDecimal?, comp: Map<String, BigDecimal> = components): MetricValue {
            val id = store(j, d, period, filter, state, reason, value, num, den, cov, r, comp)
            return MetricValue(d, state, reason, value, num, den, cov, filter, period, r.asOf, r.sourceEvents, id, components = comp)
        }
        fun finish(value: BigDecimal, num: BigDecimal, den: BigDecimal?, coverage: BigDecimal): MetricValue =
            if (coverage.compareTo(BigDecimal.ONE) < 0) result(MetricState.PROVISIONAL, null, value, num, den, coverage) else result(MetricState.OK, null, value, num, den, BigDecimal.ONE.setScale(4))
        if (r.runs == 0) return result(MetricState.NOT_COMPUTABLE, MetricReason.NO_DATA, null, null, null, null, emptyMap())   // keine Klassifikation: kein Wert, nie 0

        // Vollständigkeit: Anteil der Läufe mit vollständigen Fahrerereignissen mal Anteil der zugeordneten Strecke (km) bzw. Zeit (h); abgerundet, eine Lücke ergibt nie 100 %
        fun covOf(assigned: Long, total: Long): BigDecimal = coverage(r.runs - r.runsIncomplete to r.runs.toLong(), assigned to total)
        val kmCov = covOf(totalKm - k("UNASSIGNED"), totalKm)
        val km3 = { m: Long -> BigDecimal(m).movePointLeft(3) }
        return when (d.metricId) {
            "vehicle_km" -> finish(km3(totalKm), km3(totalKm), null, kmCov)
            "productive_km" -> finish(km3(k("PASSENGER")), km3(k("PASSENGER")), km3(serviceKm), kmCov)
            "empty_km_rate" ->
                if (serviceKm == 0L) result(MetricState.NOT_COMPUTABLE, MetricReason.ZERO_DENOMINATOR, null, km3(k("EMPTY")), BigDecimal.ZERO, null)
                else finish(MoneyRounding.ratioHalfUp(BigDecimal(k("EMPTY")), BigDecimal(serviceKm), 6), km3(k("EMPTY")), km3(serviceKm), kmCov)
            "passenger_km" -> {
                // Läufe ohne Besetzungsabschnitte, aber mit Fahrgastabschnitten: Besetzung unbekannt, Vollständigkeit sinkt
                val cov = covOf(r.occMetersKnown, k("PASSENGER").coerceAtLeast(r.occMetersKnown)).let { occ -> minOf(occ, kmCov) }
                finish(km3(r.paxMeters), km3(r.paxMeters), null, cov)
            }
            "operating_hours" -> {
                val operating = r.ms.getValue("PASSENGER") + r.ms.getValue("EMPTY") + r.ms.getValue("TRANSFER")
                val unassigned = r.ms.getValue("UNASSIGNED"); val totalMs = r.ms.values.sum()
                val hours = MoneyRounding.ratioHalfUp(BigDecimal(operating), BigDecimal(MS_PER_HOUR), 4)
                finish(hours, hours, null, covOf(totalMs - unassigned, totalMs))
            }
            "capacity_utilization_distance_weighted" ->
                if (r.seatMeters == 0L) result(MetricState.NOT_COMPUTABLE, MetricReason.ZERO_DENOMINATOR, null, null, BigDecimal.ZERO, null)   // keine verfügbare Kapazität: nie 0 oder 100 %
                else finish(MoneyRounding.ratioHalfUp(BigDecimal(r.passengerMeters), BigDecimal(r.seatMeters), 6), km3(r.passengerMeters), km3(r.seatMeters), seatCoverage(r))
            "seat_time_utilization" ->
                if (r.seatMs == 0L) result(MetricState.NOT_COMPUTABLE, MetricReason.ZERO_DENOMINATOR, null, null, BigDecimal.ZERO, null)
                else finish(MoneyRounding.ratioHalfUp(BigDecimal(r.paxMs), BigDecimal(r.seatMs), 6), MoneyRounding.ratioHalfUp(BigDecimal(r.paxMs), BigDecimal(MS_PER_MINUTE), 4),
                    MoneyRounding.ratioHalfUp(BigDecimal(r.seatMs), BigDecimal(MS_PER_MINUTE), 4), seatCoverage(r))
            else -> error("keine Kilometerkennzahl: ${d.metricId}")
        }
    }

    /** Vollständigkeit der Auslastung: Ereignisse vollständig, Strecke zugeordnet, Läufe mit Betriebsabschnitten haben eine Kapazität und Fahrgastabschnitte eine Besetzung. */
    private fun seatCoverage(r: Read): BigDecimal {
        val total = r.km.values.sum()
        return coverage(r.runs - r.runsIncomplete to r.runs.toLong(), total - r.km.getValue("UNASSIGNED") to total, r.capRuns to r.seatRuns.toLong(),
            minOf(r.occMetersKnown, r.km.getValue("PASSENGER")) to r.km.getValue("PASSENGER"))
    }

    /** Produkt der Anteile (Zähler zu Nenner); Anteile ohne Nenner zählen als vollständig. Abgerundet auf vier Stellen über [MoneyRounding]: eine Lücke ergibt nie 100 %. */
    private fun coverage(vararg shares: Pair<Number, Number>): BigDecimal {
        var num = BigDecimal.ONE; var den = BigDecimal.ONE
        for ((n, d) in shares) if (d.toLong() > 0) { num = num.multiply(BigDecimal(n.toLong())); den = den.multiply(BigDecimal(d.toLong())) }
        return if (num >= den) BigDecimal.ONE.setScale(4) else MoneyRounding.ratioDown(num, den, 4)
    }

    private fun read(j: JdbcOperations, p: ReportingPeriod, vehicleId: UUID?): Read {
        val dayFrom = Date.valueOf(p.from.atOffset(ZoneOffset.UTC).toLocalDate().minusDays(1)); val dayTo = Date.valueOf(p.to.atOffset(ZoneOffset.UTC).toLocalDate().plusDays(1))
        val from = Timestamp.from(p.from); val to = Timestamp.from(p.to); val v = vehicleId?.toString()
        return j.query(READ, { rs, _ ->
            val node = mapper.readTree(rs.getString("agg"))
            val cats = listOf("PASSENGER", "EMPTY", "TRANSFER", "WORKSHOP", "UNASSIGNED")
            Read(cats.associateWith { node["km"][it].asLong() }, cats.associateWith { node["ms"][it].asLong() }, node["pax_m"].asLong(), node["pax_ms"].asLong(), node["seat_m"].asLong(), node["seat_ms"].asLong(),
                node["occ_known_m"].asLong(), node["occupied_m"].asLong(), node["runs"].asInt(), node["runs_incomplete"].asInt(), node["cap_runs"].asInt(), node["seat_runs"].asInt(),
                rs.getTimestamp("as_of")?.toInstant(), rs.getLong("source_events"))
        }, dayFrom, dayTo, v, v, from, to, from, to).single()
    }

    private fun store(j: JdbcOperations, d: MetricDefinition, p: ReportingPeriod, f: MetricFilter, state: MetricState, reason: MetricReason?, value: BigDecimal?, num: BigDecimal?, den: BigDecimal?, cov: BigDecimal?, read: Read, comp: Map<String, BigDecimal>): UUID {
        val filterJson = mapper.writeValueAsString(f.toMap()); val hash = MessageDigest.getInstance("SHA-256").digest(filterJson.toByteArray()).joinToString("") { "%02x".format(it) }
        val compJson = if (comp.isEmpty()) null else mapper.writeValueAsString(comp.mapValues { it.value.toPlainString() })
        val inserted = j.query(INSERT_SNAPSHOT, { rs, _ -> rs.getObject(1, UUID::class.java) }, UUID.randomUUID(), d.metricId, d.version, d.valueKind.name, filterJson, hash, Timestamp.from(p.from), Timestamp.from(p.to),
            p.zone.id, state.name, reason?.name, value, num, den, cov, read.asOf?.let(Timestamp::from), read.sourceEvents, compJson).firstOrNull()
        return inserted ?: j.queryForObject(SELECT_SNAPSHOT, UUID::class.java, d.metricId, d.version, hash, Timestamp.from(p.from), Timestamp.from(p.to), p.zone.id, read.asOf?.let(Timestamp::from), read.sourceEvents)!!
    }

    companion object {
        const val MS_PER_HOUR = 3_600_000L
        const val MS_PER_MINUTE = 60_000L
        val IDS = setOf("vehicle_km", "operating_hours", "empty_km_rate", "productive_km", "passenger_km", "seat_time_utilization", "capacity_utilization_distance_weighted")

        /**
         * Ein Statement, ein Lesestand. `runs`: jüngster Klassifikationslauf je Fahrzeug und Betriebstag (Datenstand, dann Entstehung). `seg`/`occ`: Abschnitte und Besetzungsabschnitte dieser Läufe, nach ihrem
         * Beginn im Berichtszeitraum. Angebotene Plätze nur auf den Abschnitten Fahrgast und Leer von Läufen mit bekannter Kapazität; belegte Plätze nur dort, wo auch Kapazität bekannt ist.
         * `used`: Läufe mit mindestens einem Abschnitt im Zeitraum. Datenstand = jüngste Entstehung eines dieser Läufe; `source_events` = Läufe + Abschnitte + Besetzungsabschnitte.
         */
        const val READ = """
            WITH runs AS (
              SELECT DISTINCT ON (r.vehicle_id, r.service_day) r.id, r.created_at, r.events_complete, r.capacity_seats
              FROM taktovia.vehicle_km_run r WHERE r.service_day >= ? AND r.service_day <= ? AND (CAST(? AS uuid) IS NULL OR r.vehicle_id = CAST(? AS uuid))
              ORDER BY r.vehicle_id, r.service_day, r.data_as_of DESC, r.created_at DESC),
            seg AS (
              SELECT s.run_id, s.category, s.km_thousandths AS m, CAST(round(extract(epoch FROM s.period_to - s.period_from) * 1000) AS bigint) AS ms, r.capacity_seats
              FROM taktovia.vehicle_km_segment s JOIN runs r ON r.id = s.run_id WHERE s.period_from >= ? AND s.period_from < ?),
            occ AS (
              SELECT o.passengers, o.km_thousandths AS m, CAST(round(extract(epoch FROM o.period_to - o.period_from) * 1000) AS bigint) AS ms, r.capacity_seats
              FROM taktovia.vehicle_km_occupancy o JOIN runs r ON r.id = o.run_id WHERE o.period_from >= ? AND o.period_from < ?),
            used AS (SELECT r.* FROM runs r WHERE EXISTS (SELECT 1 FROM seg WHERE seg.run_id = r.id))
            SELECT CAST(jsonb_build_object(
                     'km', jsonb_build_object('PASSENGER', coalesce((SELECT sum(m) FROM seg WHERE category = 'PASSENGER'), 0), 'EMPTY', coalesce((SELECT sum(m) FROM seg WHERE category = 'EMPTY'), 0),
                           'TRANSFER', coalesce((SELECT sum(m) FROM seg WHERE category = 'TRANSFER'), 0), 'WORKSHOP', coalesce((SELECT sum(m) FROM seg WHERE category = 'WORKSHOP'), 0),
                           'UNASSIGNED', coalesce((SELECT sum(m) FROM seg WHERE category = 'UNASSIGNED'), 0)),
                     'ms', jsonb_build_object('PASSENGER', coalesce((SELECT sum(ms) FROM seg WHERE category = 'PASSENGER'), 0), 'EMPTY', coalesce((SELECT sum(ms) FROM seg WHERE category = 'EMPTY'), 0),
                           'TRANSFER', coalesce((SELECT sum(ms) FROM seg WHERE category = 'TRANSFER'), 0), 'WORKSHOP', coalesce((SELECT sum(ms) FROM seg WHERE category = 'WORKSHOP'), 0),
                           'UNASSIGNED', coalesce((SELECT sum(ms) FROM seg WHERE category = 'UNASSIGNED'), 0)),
                     'pax_m', coalesce((SELECT sum(passengers * m) FROM occ), 0),
                     'pax_ms', coalesce((SELECT sum(passengers::bigint * ms) FROM occ WHERE capacity_seats IS NOT NULL), 0),
                     'occupied_m', coalesce((SELECT sum(passengers::bigint * m) FROM occ WHERE capacity_seats IS NOT NULL), 0),
                     'occ_known_m', coalesce((SELECT sum(m) FROM occ), 0),
                     'seat_m', coalesce((SELECT sum(capacity_seats::bigint * m) FROM seg WHERE category IN ('PASSENGER', 'EMPTY') AND capacity_seats IS NOT NULL), 0),
                     'seat_ms', coalesce((SELECT sum(capacity_seats::bigint * ms) FROM seg WHERE category IN ('PASSENGER', 'EMPTY') AND capacity_seats IS NOT NULL), 0),
                     'runs', (SELECT count(*) FROM used), 'runs_incomplete', (SELECT count(*) FROM used WHERE NOT events_complete),
                     'seat_runs', (SELECT count(DISTINCT run_id) FROM seg WHERE category IN ('PASSENGER', 'EMPTY')),
                     'cap_runs', (SELECT count(DISTINCT run_id) FROM seg WHERE category IN ('PASSENGER', 'EMPTY') AND capacity_seats IS NOT NULL)) AS text) AS agg,
                   (SELECT max(created_at) FROM used) AS as_of,
                   (SELECT count(*) FROM used) + (SELECT count(*) FROM seg) + (SELECT count(*) FROM occ) AS source_events"""

        const val INSERT_SNAPSHOT = """
            INSERT INTO taktovia.aggregate_snapshot(tenant_id, id, metric_id, definition_version, value_kind, filter, filter_hash, period_from, period_to, reporting_timezone, state, reason, value, numerator,
                                                    denominator, coverage, data_as_of, source_events, components)
            VALUES (taktovia.current_tenant(), ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb)) ON CONFLICT DO NOTHING RETURNING id"""
        const val SELECT_SNAPSHOT = """
            SELECT id FROM taktovia.aggregate_snapshot WHERE metric_id = ? AND definition_version = ? AND filter_hash = ? AND period_from = ? AND period_to = ? AND reporting_timezone = ?
              AND coalesce(data_as_of, '-infinity'::timestamptz) = coalesce(CAST(? AS timestamptz), '-infinity'::timestamptz) AND source_events = ?"""
    }
}

package de.taktovia.analytics.internal

import de.taktovia.analytics.api.EnergyKind
import de.taktovia.analytics.api.ExclusionReason
import de.taktovia.analytics.api.MetricApproximation
import de.taktovia.analytics.api.MetricDefinition
import de.taktovia.analytics.api.MetricExclusion
import de.taktovia.analytics.api.MetricFilter
import de.taktovia.analytics.api.MetricReason
import de.taktovia.analytics.api.MetricState
import de.taktovia.analytics.api.MetricValue
import de.taktovia.analytics.api.ReportingPeriod
import de.taktovia.platform.money.MoneyRounding
import org.springframework.jdbc.core.JdbcOperations
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.math.BigInteger
import java.security.MessageDigest
import java.sql.Date
import java.sql.Timestamp
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * Energiekosten je 100 km und je Betriebsstunde (WP-M4-43, `LH-I.2-FCO-004`). Alle Eingaben (Tankvorgänge mit Belegzustand, Kostenereignis, Stornierung, offene Hinweise, Kilometerabschnitte) und der Datenstand
 * werden in **einem** SQL-Statement gelesen ([READ]): READ COMMITTED gilt je Statement, also gehört jedes Ergebnis genau einem Datenstand; die Rechnung selbst ist eine reine Funktion dieser Zeilen
 * (exakt, BigInteger/BigDecimal, Rundung über [MoneyRounding]) und damit auf demselben Datenstand wiederholbar. Es geht nur ein, was freigegeben und gebucht ist (Beleg `POSTED`, ein ursprüngliches
 * Ist-Kostenereignis, nicht storniert, kein offener Hinweis); alles andere steht mit Grund in der Ausschlussliste. Fehlende Energiedaten sind nie null Kosten: fehlende Vorgänge senken den Abdeckungsgrad
 * (`PROVISIONAL`), ohne jeden eingegangenen Vorgang gibt es keinen Wert (`NO_DATA`). Kraftstoff (Liter) und Strom (kWh) sowie Währungen werden nie zusammengefasst, sondern je Energieart und Währung getrennt
 * ausgewiesen. Die Kilometer eines Fahrzeugs lassen sich nicht auf Energiearten aufteilen: ein Fahrzeug mit Vorgängen beider Energiearten (Plug-in-Hybrid) wird mit Grund ausgeschlossen (Offener Punkt 40).
 */
internal class JdbcEnergyCosts(private val mapper: JsonMapper) {

    private class DocRow(
        val documentId: UUID, val vehicleId: UUID?, val energy: EnergyKind?, val quantityMissing: Boolean, val state: String, val hasEvent: Boolean, val currency: String?, val cost: Long,
        val reversed: Boolean, val openHint: Boolean,
    )

    private class KmRow(val vehicleId: UUID, val km: Long, val operatingMs: Long, val unassignedMs: Long, val runs: Int, val runsIncomplete: Int)

    /** Gelesener Lesestand: Zeilen, Datenstand ([asOf]) und Zahl der gelesenen Quellzeilen ([sourceEvents]). */
    private class Read(val docs: List<DocRow>, val km: Map<UUID, KmRow>, val asOf: Instant?, val sourceEvents: Long)

    /** Je Energieart (nach [wanted], sonst beide) und Währung ein Wert; ohne Währung in den Daten ein Wert ohne Währung (`NO_DATA`). Schreibt je Wert einen Snapshot. */
    fun evaluate(j: JdbcOperations, d: MetricDefinition, period: ReportingPeriod, filter: MetricFilter): List<MetricValue> {
        val read = read(j, period, filter.vehicleId, filter.vehicleType)
        val kinds = filter.energy?.let { listOf(it) } ?: EnergyKind.entries
        return kinds.flatMap { kind -> forKind(j, d, period, filter, kind, read) }
    }

    private fun forKind(j: JdbcOperations, d: MetricDefinition, period: ReportingPeriod, filter: MetricFilter, kind: EnergyKind, read: Read): List<MetricValue> {
        // Fahrzeuge mit Vorgängen beider Energiearten: Kilometer nicht trennbar
        val energiesByVehicle = read.docs.filter { it.vehicleId != null && it.energy != null }.groupBy { it.vehicleId!! }.mapValues { e -> e.value.map { it.energy }.toSet() }
        val mixed = energiesByVehicle.filterValues { it.size > 1 }.keys
        val mine = read.docs.filter { it.energy == kind || it.energy == null }   // ohne Energieart: Vorgang betrifft jede Energieart (fehlt)
        val exclusions = ArrayList<MetricExclusion>(); val eligible = ArrayList<DocRow>()
        fun exclude(r: DocRow, reason: ExclusionReason) { exclusions += MetricExclusion(r.documentId, r.vehicleId, reason) }
        for (r in mine) when {
            r.energy == null -> exclude(r, ExclusionReason.MISSING_ENERGY_TYPE)
            r.vehicleId == null -> exclude(r, ExclusionReason.MISSING_VEHICLE)
            r.vehicleId in mixed -> exclude(r, ExclusionReason.MIXED_ENERGY_VEHICLE)
            r.hasEvent && r.reversed -> exclude(r, ExclusionReason.REVERSED)
            r.state != POSTED || !r.hasEvent -> exclude(r, ExclusionReason.NOT_RELEASED)
            r.openHint -> exclude(r, ExclusionReason.OPEN_HINT)
            r.quantityMissing -> exclude(r, ExclusionReason.MISSING_QUANTITY)
            else -> eligible += r
        }
        // Währungen werden nie addiert: je Währung ein Wert (mit Währungsfilter nur diese, die anderen stehen als OTHER_CURRENCY in der Ausschlussliste)
        val currencies = eligible.mapNotNull { it.currency }.toSortedSet()
        val shown = filter.currency?.let { c -> sortedSetOf(c) } ?: currencies.toSortedSet()
        if (shown.isEmpty()) return listOf(finish(j, d, period, filter.copy(energy = kind), read, exclusions, emptyList(), null))
        return shown.map { cur ->
            val rows = eligible.filter { it.currency == cur }
            val other = if (filter.currency != null) eligible.filter { it.currency != cur }.map { MetricExclusion(it.documentId, it.vehicleId, ExclusionReason.OTHER_CURRENCY) } else emptyList()
            finish(j, d, period, filter.copy(energy = kind, currency = cur), read, (exclusions + other).sortedWith(EXCLUSION_ORDER), rows, cur)
        }
    }

    private fun finish(j: JdbcOperations, d: MetricDefinition, period: ReportingPeriod, filter: MetricFilter, read: Read, exclusions: List<MetricExclusion>, rows: List<DocRow>, currency: String?): MetricValue {
        val perHour = d.metricId == HOUR_ID
        val sorted = exclusions.sortedWith(EXCLUSION_ORDER)
        fun result(state: MetricState, reason: MetricReason?, value: BigDecimal?, num: BigDecimal?, den: BigDecimal?, cov: BigDecimal?): MetricValue {
            val id = store(j, d, period, filter, state, reason, value, num, den, cov, read, sorted)
            return MetricValue(d, state, reason, value, num, den, cov, filter, period, read.asOf, read.sourceEvents, id, sorted, MetricApproximation.ACQUISITION_BASIS_P1)
        }
        if (rows.isEmpty()) return result(MetricState.NOT_COMPUTABLE, MetricReason.NO_DATA, null, null, null, null)   // „keine plausibilisierten Tankvorgänge“, nie 0 Kosten
        val vehicles = rows.mapNotNull { it.vehicleId }.toSet()
        val cost = rows.fold(BigInteger.ZERO) { a, r -> a + BigInteger.valueOf(r.cost) }
        val kms = vehicles.mapNotNull { read.km[it] }
        val kmThousandths = kms.sumOf { it.km }; val operatingMs = kms.sumOf { it.operatingMs }
        // Abdeckungsgrad: Anteil eingegangener Vorgänge (fehlende zählen, stornierte und andere Währungen nicht) mal Anteil der Fahrzeuge mit vollständiger Nennerquelle
        val missing = sorted.count { it.reason.countsAgainstCoverage }
        val docCoverage = BigDecimal(rows.size) to BigDecimal(rows.size + missing)
        val complete = vehicles.count { v -> read.km[v]?.let { k -> k.runsIncomplete == 0 && k.runs > 0 && (if (perHour) k.unassignedMs == 0L else true) && (if (perHour) k.operatingMs > 0 else k.km > 0) } == true }
        val coverage = if (missing == 0 && complete == vehicles.size) BigDecimal.ONE.setScale(4)
        else MoneyRounding.ratioDown(docCoverage.first * BigDecimal(complete), docCoverage.second * BigDecimal(vehicles.size), 4)
        val denominator: BigDecimal = if (perHour) MoneyRounding.ratioHalfUp(BigDecimal(operatingMs), BigDecimal(MS_PER_HOUR), 4) else BigDecimal(kmThousandths).movePointLeft(3)
        val denominatorUnits = if (perHour) operatingMs else kmThousandths
        val num = BigDecimal(cost)
        if (denominatorUnits <= 0L) return result(MetricState.NOT_COMPUTABLE, MetricReason.ZERO_DENOMINATOR, null, num, BigDecimal.ZERO, null)   // nie 0 Kosten je Einheit
        // Wert = Kostenbasis x 100 / km bzw. x 3600 / Stunden, auf ganze Minor Units kaufmännisch gerundet (HALF_UP); exakt in ganzen Zahlen (km in Metern, Zeit in Millisekunden)
        val value = BigDecimal(MoneyRounding.halfUpDiv(if (perHour) cost * BigInteger.valueOf(MS_PER_HOUR) else cost * BigInteger.valueOf(100_000L), BigInteger.valueOf(denominatorUnits)))
        val state = if (coverage.compareTo(BigDecimal.ONE) < 0) MetricState.PROVISIONAL else MetricState.OK
        return result(state, null, value, num, denominator, coverage)
    }

    // ---- Lesestand ---------------------------------------------------------------------------------------------------------------------------

    private fun read(j: JdbcOperations, p: ReportingPeriod, vehicleId: UUID?, vehicleType: String?): Read {
        val dayFrom = Date.valueOf(p.from.atOffset(ZoneOffset.UTC).toLocalDate().minusDays(1)); val dayTo = Date.valueOf(p.to.atOffset(ZoneOffset.UTC).toLocalDate().plusDays(1))
        val from = Timestamp.from(p.from); val to = Timestamp.from(p.to); val v = vehicleId?.toString()
        return j.query(READ, { rs, _ ->
            val docs = buildList { for (n in mapper.readTree(rs.getString("docs"))) add(doc(n)) }
            val km = buildList { for (n in mapper.readTree(rs.getString("km"))) add(km(n)) }.associateBy { it.vehicleId }
            Read(docs, km, rs.getTimestamp("as_of")?.toInstant(), rs.getLong("source_events"))
        }, from, to, v, v, vehicleType, vehicleType, dayFrom, dayTo, from, to).single()
    }

    private fun doc(n: JsonNode) = DocRow(
        UUID.fromString(n["document_id"].asString()), n["vehicle_id"]?.takeIf { !it.isNull }?.asString()?.let(UUID::fromString), n["energy"]?.takeIf { !it.isNull }?.asString()?.let { EnergyKind.valueOf(it) },
        n["quantity_missing"].asBoolean(), n["state"].asString(), n["event_id"] != null && !n["event_id"].isNull, (n["event_currency"]?.takeIf { !it.isNull } ?: n["tx_currency"]?.takeIf { !it.isNull })?.asString()?.trim(),
        n["cost"]?.takeIf { !it.isNull }?.asLong() ?: 0L, n["reversed"].asBoolean(), n["open_hint"].asBoolean(),
    )

    private fun km(n: JsonNode) = KmRow(UUID.fromString(n["vehicle_id"].asString()), n["km"].asLong(), n["operating_ms"]?.takeIf { !it.isNull }?.asLong() ?: 0L, n["unassigned_ms"]?.takeIf { !it.isNull }?.asLong() ?: 0L,
        n["runs"].asInt(), n["runs_incomplete"].asInt())

    // ---- Snapshot ----------------------------------------------------------------------------------------------------------------------------

    private fun store(j: JdbcOperations, d: MetricDefinition, p: ReportingPeriod, f: MetricFilter, state: MetricState, reason: MetricReason?, value: BigDecimal?, num: BigDecimal?, den: BigDecimal?, cov: BigDecimal?, read: Read, ex: List<MetricExclusion>): UUID {
        val filterJson = mapper.writeValueAsString(f.toMap()); val hash = MessageDigest.getInstance("SHA-256").digest(filterJson.toByteArray()).joinToString("") { "%02x".format(it) }
        val exJson = mapper.writeValueAsString(ex.map { linkedMapOf("document_id" to it.documentId.toString(), "vehicle_id" to it.vehicleId?.toString(), "reason" to it.reason.name) })
        val inserted = j.query(INSERT_SNAPSHOT, { rs, _ -> rs.getObject(1, UUID::class.java) }, UUID.randomUUID(), d.metricId, d.version, d.valueKind.name, filterJson, hash, Timestamp.from(p.from), Timestamp.from(p.to),
            p.zone.id, state.name, reason?.name, value, num, den, cov, read.asOf?.let(Timestamp::from), read.sourceEvents, exJson, MetricApproximation.ACQUISITION_BASIS_P1.name).firstOrNull()
        return inserted ?: j.queryForObject(SELECT_SNAPSHOT, UUID::class.java, d.metricId, d.version, hash, Timestamp.from(p.from), Timestamp.from(p.to), p.zone.id, read.asOf?.let(Timestamp::from), read.sourceEvents)!!
    }

    private companion object {
        const val POSTED = "POSTED"
        const val HOUR_ID = "energy_cost_per_operating_hour"
        const val MS_PER_HOUR = 3_600_000L
        val EXCLUSION_ORDER = compareBy<MetricExclusion>({ it.reason.ordinal }, { it.documentId })

        /**
         * Ein Statement, ein Lesestand. `tx`: Tankvorgänge mit Belegzustand im Zeitraum (Tankzeit, sonst Belegeingang). `docs`: dazu das ursprüngliche Ist-Kostenereignis, Stornierungen (Gegenereignisse mindern die Kostenbasis, ein vollständig aufgehobener Vorgang zählt nicht), offener Hinweis.
         * `runs`/`seg`/`kmv`: jüngster Kilometerlauf je Fahrzeug und Tag, Abschnitte nach ihrem Beginn im Zeitraum, je Fahrzeug Kilometer, Betriebszeit (Fahrgast, Leer, Überführung) und nicht zugeordnete Zeit.
         * Datenstand = jüngster Buchungs-, Stornierungs-, Erfassungs- oder Laufzeitpunkt der gelesenen Zeilen; `source_events` = Zahl dieser Zeilen.
         */
        const val READ = """
            WITH tx AS (
              SELECT f.tenant_id, f.document_id, f.vehicle_id, f.recorded_at, d.state, d.revision, f.currency AS tx_currency, (f.quantity IS NULL) AS quantity_missing,
                     CASE WHEN f.fuel_type IS NULL THEN NULL WHEN f.fuel_type = 'ELECTRIC' THEN 'ELECTRIC' ELSE 'FUEL' END AS energy
              FROM taktovia.fuel_transaction f JOIN taktovia.expense_document d ON d.tenant_id = f.tenant_id AND d.id = f.document_id
              WHERE coalesce(f.fueled_at, d.original_received_at) >= ? AND coalesce(f.fueled_at, d.original_received_at) < ?
                AND (CAST(? AS uuid) IS NULL OR f.vehicle_id = CAST(? AS uuid))
                AND (CAST(? AS text) IS NULL OR f.vehicle_id IN (SELECT vh.id FROM taktovia.vehicle vh WHERE vh.vehicle_type = CAST(? AS text)))),
            docs AS (
              SELECT t.document_id, t.vehicle_id, t.energy, t.quantity_missing, t.state, t.tx_currency, t.recorded_at, ev.id AS event_id, ev.currency AS event_currency,
                     ev.cost_basis_minor + rv.basis AS cost, ev.booked_at, rv.n AS reversal_n, rv.booked_at AS reversed_at, (rv.n > 0 AND ev.cost_basis_minor + rv.basis = 0) AS reversed,
                     EXISTS (SELECT 1 FROM taktovia.expense_hint h WHERE h.tenant_id = t.tenant_id AND h.document_id = t.document_id AND h.document_revision = t.revision
                               AND NOT EXISTS (SELECT 1 FROM taktovia.expense_hint_ack a WHERE a.tenant_id = h.tenant_id AND a.hint_id = h.id)) AS open_hint
              FROM tx t LEFT JOIN taktovia.economic_event ev ON ev.tenant_id = t.tenant_id AND ev.document_id = t.document_id AND ev.reverses_id IS NULL AND ev.scenario = 'ACTUAL'
              LEFT JOIN LATERAL (SELECT count(*) AS n, coalesce(sum(r.cost_basis_minor), 0) AS basis, max(r.booked_at) AS booked_at FROM taktovia.economic_event r
                                 WHERE r.tenant_id = ev.tenant_id AND r.reverses_id = ev.id) rv ON true),
            runs AS (
              SELECT DISTINCT ON (r.vehicle_id, r.service_day) r.id, r.vehicle_id, r.data_as_of, r.events_complete
              FROM taktovia.vehicle_km_run r WHERE r.vehicle_id IN (SELECT vehicle_id FROM tx WHERE vehicle_id IS NOT NULL) AND r.service_day >= ? AND r.service_day <= ?
              ORDER BY r.vehicle_id, r.service_day, r.data_as_of DESC, r.created_at DESC),
            seg AS (
              SELECT s.vehicle_id, s.run_id, s.km_thousandths, s.category, CAST(round(extract(epoch FROM s.period_to - s.period_from) * 1000) AS bigint) AS ms
              FROM taktovia.vehicle_km_segment s WHERE s.run_id IN (SELECT id FROM runs) AND s.period_from >= ? AND s.period_from < ?),
            kmv AS (
              SELECT seg.vehicle_id, sum(seg.km_thousandths) AS km, coalesce(sum(seg.ms) FILTER (WHERE seg.category IN ('PASSENGER', 'EMPTY', 'TRANSFER')), 0) AS operating_ms,
                     coalesce(sum(seg.ms) FILTER (WHERE seg.category = 'UNASSIGNED'), 0) AS unassigned_ms, count(DISTINCT seg.run_id) AS runs,
                     count(DISTINCT seg.run_id) FILTER (WHERE NOT runs.events_complete) AS runs_incomplete, max(runs.data_as_of) AS as_of
              FROM seg JOIN runs ON runs.id = seg.run_id GROUP BY seg.vehicle_id)
            SELECT CAST((SELECT coalesce(jsonb_agg(to_jsonb(d) - 'recorded_at' - 'booked_at' - 'reversed_at' - 'reversal_n'), '[]'::jsonb) FROM docs d) AS text) AS docs,
                   CAST((SELECT coalesce(jsonb_agg(to_jsonb(k) - 'as_of'), '[]'::jsonb) FROM kmv k) AS text) AS km,
                   greatest((SELECT max(booked_at) FROM docs), (SELECT max(reversed_at) FROM docs), (SELECT max(recorded_at) FROM docs), (SELECT max(as_of) FROM kmv)) AS as_of,
                   (SELECT count(*) FROM docs) + (SELECT count(event_id) FROM docs) + (SELECT coalesce(sum(reversal_n), 0) FROM docs) + (SELECT coalesce(sum(runs), 0) FROM kmv) AS source_events"""

        const val INSERT_SNAPSHOT = """
            INSERT INTO taktovia.aggregate_snapshot(tenant_id, id, metric_id, definition_version, value_kind, filter, filter_hash, period_from, period_to, reporting_timezone, state, reason, value, numerator,
                                                    denominator, coverage, data_as_of, source_events, exclusions, approximation)
            VALUES (taktovia.current_tenant(), ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?) ON CONFLICT DO NOTHING RETURNING id"""
        const val SELECT_SNAPSHOT = """
            SELECT id FROM taktovia.aggregate_snapshot WHERE metric_id = ? AND definition_version = ? AND filter_hash = ? AND period_from = ? AND period_to = ? AND reporting_timezone = ?
              AND coalesce(data_as_of, '-infinity'::timestamptz) = coalesce(CAST(? AS timestamptz), '-infinity'::timestamptz) AND source_events = ?"""
    }
}

package de.taktovia.analytics.internal

import de.taktovia.analytics.api.DefinitionChange
import de.taktovia.analytics.api.FactSource
import de.taktovia.analytics.api.MetricDefinition
import de.taktovia.analytics.api.MetricDimensions
import de.taktovia.analytics.api.MetricFilter
import de.taktovia.analytics.api.MetricQuery
import de.taktovia.analytics.api.MetricReason
import de.taktovia.analytics.api.MetricState
import de.taktovia.analytics.api.MetricValue
import de.taktovia.analytics.api.MetricValueKind
import de.taktovia.analytics.api.Metrics
import de.taktovia.analytics.api.RejectionReport
import de.taktovia.analytics.api.RejectionRow
import de.taktovia.analytics.api.ReportingPeriod
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcOperations
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.sql.Timestamp
import java.util.UUID

/**
 * Kennzahlregister und Versuchskennzahlen auf PostgreSQL (WP-M4-46 bis -50). Werte, Zähler und Datenstand entstehen in **einem** SQL-Statement ([COUNTS], [REJECTIONS]): READ COMMITTED gilt je Statement,
 * also sieht jede Antwort Fakten und Datenstand im selben Lesestand, auch während die Projektion läuft. Die Kennzahlen eines Aufrufs teilen sich dieses eine Statement. Alle Werte sind gebunden.
 */
internal class JdbcMetrics(private val tx: TenantTransactions, override val smallGroupThreshold: Int) : Metrics {
    private val mapper = JsonMapper.builder().build()

    // ---- Definitionen -------------------------------------------------------------------------------------------------------------------

    override fun definitions(tenant: TenantId): List<MetricDefinition> = tx.run(tenant) { j -> seed(j); latest(j) }

    override fun definition(tenant: TenantId, metricId: String, version: Int?): MetricDefinition = tx.run(tenant) { j -> seed(j); one(j, metricId, version) }

    private fun seed(j: JdbcOperations) { j.queryForObject("SELECT taktovia.seed_metric_definitions()", Int::class.java) }

    private fun latest(j: JdbcOperations): List<MetricDefinition> = j.query(DEF_LATEST, DEF_MAPPER)

    private fun one(j: JdbcOperations, metricId: String, version: Int?): MetricDefinition =
        (if (version == null) latest(j).firstOrNull { it.metricId == metricId }
         else j.query(DEF_ONE, DEF_MAPPER, metricId, version).firstOrNull()) ?: throw ProblemException.notFound()

    // ---- Auswertung ---------------------------------------------------------------------------------------------------------------------

    override fun evaluate(tenant: TenantId, query: MetricQuery): List<MetricValue> {
        if (query.metricId == null && query.version != null) throw ProblemException(ErrorCode.BAD_REQUEST, "Eine Version gehört zu einer Kennzahl.", listOf(Violation("/\$query/version", ViolationCode.INVALID_VALUE)))
        return tx.run(tenant) { j ->
            seed(j)
            val defs = if (query.metricId != null) listOf(one(j, query.metricId, query.version)) else latest(j)
            defs.forEach { requireDimensions(it, query.filter) }
            val c = counts(j, query.period, query.filter)
            defs.map { d -> value(j, d, query, c) }
        }
    }

    /** Eine unzulässige Filterdimension ist ein Eingabefehler (400), nie ein stillschweigend ignorierter Filter. */
    private fun requireDimensions(d: MetricDefinition, f: MetricFilter) {
        val bad = f.dimensions().filter { it !in d.filterDimensions }
        if (bad.isNotEmpty()) throw ProblemException(ErrorCode.BAD_REQUEST, "Filterdimension für die Kennzahl ${d.metricId} nicht zulässig.", bad.map { Violation("/\$query/$it", ViolationCode.NOT_IN_ENUM) })
    }

    private class Counts(
        val requests: Long, val technical: Long, val byMetric: Map<String, Long>, val unfiltered: Long, val factsTotal: Long,
        val asOf: Timestamp?, val incomplete: Boolean, val gaps: Int, val events: Long, val hasData: Boolean,
    )

    private fun counts(j: JdbcOperations, p: ReportingPeriod, f: MetricFilter): Counts = j.query(COUNTS, { rs, _ ->
        Counts(rs.getLong("requests"), rs.getLong("technical"), mapOf("CAPACITY" to rs.getLong("capacity"), "PERSONNEL" to rs.getLong("personnel"), "EQUIPMENT" to rs.getLong("equipment"),
            "AREA" to rs.getLong("area"), "TIME_WINDOW" to rs.getLong("time_window"), "PARTNER" to rs.getLong("partner")), rs.getLong("requests_unfiltered"), rs.getLong("facts_total"),
            rs.getTimestamp("processed_until"), rs.getString("completeness") == "INCOMPLETE", rs.getInt("gap_count"), rs.getLong("event_count"), rs.getBoolean("has_data"))
    }, Timestamp.from(p.from), Timestamp.from(p.to), f.serviceId?.toString(), f.serviceId?.toString(), f.channel, f.channel).single()

    private fun coverage(c: Counts): BigDecimal? =
        if (!c.incomplete) BigDecimal.ONE.setScale(4) else if (c.factsTotal <= 0) BigDecimal.ZERO.setScale(4)
        else BigDecimal(maxOf(0L, c.factsTotal - c.gaps)).divide(BigDecimal(c.factsTotal), 4, RoundingMode.DOWN)   // abgerundet: eine Lücke ergibt nie 100 %

    private fun value(j: JdbcOperations, d: MetricDefinition, q: MetricQuery, c: Counts): MetricValue {
        fun result(state: MetricState, reason: MetricReason?, value: BigDecimal?, num: BigDecimal?, den: BigDecimal?, persist: Boolean = true): MetricValue {
            val cov = if (state == MetricState.OK || state == MetricState.PROVISIONAL) coverage(c) else null
            val asOf = c.asOf?.toInstant()
            val id = if (persist) store(j, d, q, state, reason, value, num, den, cov, c) else null
            return MetricValue(d, state, reason, value, num, den, cov, q.filter, q.period, asOf, c.events, id)
        }
        val calc = calculation(d, c) ?: return result(MetricState.NOT_COMPUTABLE, d.notExecutableReason ?: MetricReason.NOT_EXECUTABLE_IN_THIS_RELEASE, null, null, null, persist = false)
        if (!c.hasData) return result(MetricState.NOT_COMPUTABLE, MetricReason.NO_DATA, null, null, null)
        // kleine Gruppen: weder die Gruppe selbst noch ihr Komplement (Differenzbildung benachbarter Filter) darf unter der Schwelle liegen
        if (q.filter.dimensions().isNotEmpty()) {
            val complement = c.unfiltered - c.requests
            if ((c.requests in 1 until smallGroupThreshold) || (complement in 1 until smallGroupThreshold)) return result(MetricState.NOT_COMPUTABLE, MetricReason.SMALL_GROUP, null, null, null)
        }
        val num = BigDecimal(calc.numerator); val den = calc.denominator?.let { BigDecimal(it) }
        val state = if (c.incomplete) MetricState.PROVISIONAL else MetricState.OK
        if (d.valueKind == MetricValueKind.RATIO) {
            if (den == null) return result(MetricState.NOT_COMPUTABLE, MetricReason.MISSING_DENOMINATOR, null, null, null)
            if (den.signum() == 0) return result(MetricState.NOT_COMPUTABLE, MetricReason.ZERO_DENOMINATOR, null, null, den)   // nie 0 oder 100 %
            return result(state, null, num.divide(den, 6, RoundingMode.HALF_UP), num, den)
        }
        return result(state, null, num, num, den)
    }

    private class Calc(val numerator: Long, val denominator: Long?)

    /** Zähler und Nenner je ausführbarer Kennzahl aus den Zählern des einen Lesestands; `null` = in diesem Stand nicht ausführbar. */
    private fun calculation(d: MetricDefinition, c: Counts): Calc? {
        if (!d.executable) return null
        return when (d.metricId) {
            "requests" -> Calc(c.requests, null)
            "technical_request_errors" -> Calc(c.technical, c.requests)
            "capacity_rejections" -> Calc(c.byMetric.getValue("CAPACITY"), c.requests)
            "personnel_rejections" -> Calc(c.byMetric.getValue("PERSONNEL"), c.requests)
            "equipment_rejections" -> Calc(c.byMetric.getValue("EQUIPMENT"), c.requests)
            "area_rejections" -> Calc(c.byMetric.getValue("AREA"), c.requests)
            "time_window_rejections" -> Calc(c.byMetric.getValue("TIME_WINDOW"), c.requests)
            else -> null
        }
    }

    /** Hält die Auswertung als Snapshot mit Definitionsversion fest (derselbe Stand genau einmal; parallele Abfragen schreiben nicht doppelt). */
    private fun store(j: JdbcOperations, d: MetricDefinition, q: MetricQuery, state: MetricState, reason: MetricReason?, value: BigDecimal?, num: BigDecimal?, den: BigDecimal?, cov: BigDecimal?, c: Counts): UUID {
        val filterJson = mapper.writeValueAsString(q.filter.toMap()); val hash = sha256(filterJson)
        val dbState = if (state == MetricState.NOT_COMPUTABLE && reason == MetricReason.SMALL_GROUP) "SUPPRESSED" else state.name
        val id = UUID.randomUUID(); val asOf = c.asOf
        val inserted = j.query(INSERT_SNAPSHOT, { rs, _ -> rs.getObject(1, UUID::class.java) }, id, d.metricId, d.version, d.valueKind.name, filterJson, hash, Timestamp.from(q.period.from), Timestamp.from(q.period.to),
            q.period.zone.id, dbState, reason?.name, value, num, den, cov, asOf, c.events).firstOrNull()
        return inserted ?: j.queryForObject(SELECT_SNAPSHOT, UUID::class.java, d.metricId, d.version, hash, Timestamp.from(q.period.from), Timestamp.from(q.period.to), q.period.zone.id, asOf, c.events)!!
    }

    private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    // ---- Ablehnungsstatistik -------------------------------------------------------------------------------------------------------------

    override fun rejections(tenant: TenantId, period: ReportingPeriod, filter: MetricFilter, reasonCode: String?): RejectionReport = tx.run(tenant, readOnly = true) { j ->
        if (reasonCode != null && j.queryForObject("SELECT count(*) FROM taktovia.rejection_reason WHERE code = ?", Int::class.java, reasonCode) == 0)
            throw ProblemException(ErrorCode.BAD_REQUEST, "Unbekannter Ablehnungsgrund.", listOf(Violation("/\$query/reason_code", ViolationCode.NOT_IN_ENUM)))
        class Row(val row: RejectionRow, val attempts: Long, val technical: Long, val factsTotal: Long, val asOf: Timestamp?, val incomplete: Boolean, val gaps: Int, val events: Long, val hasData: Boolean)
        val rows = j.query(REJECTIONS, { rs, _ ->
            @Suppress("UNCHECKED_CAST") val texts = (mapper.readTree(rs.getString("texts")).properties().associate { it.key to it.value.asString() })
            Row(RejectionRow(rs.getString("code"), rs.getString("reason_group"), rs.getString("metric"), rs.getString("label"), texts, rs.getInt("priority"), rs.getLong("primary_count"), rs.getLong("secondary_count")),
                rs.getLong("attempts"), rs.getLong("technical"), rs.getLong("facts_total"), rs.getTimestamp("processed_until"), rs.getString("completeness") == "INCOMPLETE", rs.getInt("gap_count"),
                rs.getLong("event_count"), rs.getBoolean("has_data"))
        }, Timestamp.from(period.from), Timestamp.from(period.to), filter.serviceId?.toString(), filter.serviceId?.toString(), filter.channel, filter.channel)
        val head = rows.first()
        val shown = rows.map { it.row }.filter { (it.primaryCount > 0 || it.secondaryCount > 0) && (reasonCode == null || it.code == reasonCode) }
        val c = Counts(head.attempts, head.technical, emptyMap(), head.attempts, head.factsTotal, head.asOf, head.incomplete, head.gaps, head.events, head.hasData)
        val state = when { !head.hasData -> MetricState.NOT_COMPUTABLE; head.incomplete -> MetricState.PROVISIONAL; else -> MetricState.OK }
        RejectionReport(period, filter, reasonCode, head.attempts, rows.sumOf { it.row.primaryCount }, head.technical, shown, state, if (head.hasData) coverage(c) else null, head.asOf?.toInstant(), head.events)
    }

    // ---- Änderung von Definitionen ----------------------------------------------------------------------------------------------------------

    override fun propose(tenant: TenantId, metricId: String, change: DefinitionChange): Int = tx.run(tenant) { j ->
        seed(j)
        val dims = change.filterDimensions?.also { l -> require(MetricDimensions.ALL.containsAll(l)) { "Filterdimension unbekannt" } }
        try {
            j.query(PROPOSE, { rs, _ -> rs.getInt(1) }, change.name, change.numerator, change.denominator, change.exclusions, change.timeBasis, if (dims != null) 1 else 0, dims?.let { l -> "{" + l.joinToString(",") + "}" },
                metricId).firstOrNull() ?: throw ProblemException.notFound()
        } catch (e: DataAccessException) { throw DbErrorMapperBridge.map(e) }
    }

    override fun approve(tenant: TenantId, metricId: String, version: Int, approvedBy: String) {
        val n = try {
            tx.run(tenant) { j -> j.update(APPROVE, approvedBy, metricId, version) }
        } catch (e: DataAccessException) {
            throw ProblemException(ErrorCode.VALIDATION_FAILED, "Die Definition kann nicht freigegeben werden: Pflichtfelder fehlen oder eine neuere Version ist bereits freigegeben.", cause = e)
        }
        if (n == 0) throw ProblemException.notFound()
    }

    private companion object {
        const val DEF_COLUMNS = "SELECT d.metric_id, d.version, d.name, d.value_kind, d.unit, d.numerator_definition, d.denominator_definition, d.exclusions, d.time_basis, d.period_basis, " +
            "array_to_string(d.filter_dimensions, ',') AS dims, d.source, d.executable, d.not_executable_reason, d.approved_at"
        const val DEF_LATEST = DEF_COLUMNS + " FROM taktovia.metric_definition_revision d WHERE d.state = 'APPROVED' AND d.version = " +
            "(SELECT max(m.version) FROM taktovia.metric_definition_revision m WHERE m.metric_id = d.metric_id AND m.state = 'APPROVED') ORDER BY d.metric_id"
        const val DEF_ONE = DEF_COLUMNS + " FROM taktovia.metric_definition_revision d WHERE d.metric_id = ? AND d.version = ? AND d.state = 'APPROVED'"
        val DEF_MAPPER = org.springframework.jdbc.core.RowMapper { rs, _ ->
            MetricDefinition(rs.getString("metric_id"), rs.getInt("version"), rs.getString("name"), MetricValueKind.valueOf(rs.getString("value_kind")), rs.getString("unit"), rs.getString("numerator_definition"),
                rs.getString("denominator_definition"), rs.getString("exclusions"), rs.getString("time_basis"), rs.getString("period_basis"),
                rs.getString("dims").split(',').filter { it.isNotEmpty() }, rs.getString("source")?.let { FactSource.valueOf(it) }, rs.getBoolean("executable"),
                rs.getString("not_executable_reason")?.let { MetricReason.valueOf(it) }, rs.getTimestamp("approved_at")?.toInstant())
        }

        /** Alle Zähler der Versuchskennzahlen und der Datenstand der Quelle ATTEMPT in einem Statement (ein Lesestand). `base` = Zeitraum ohne Dimensionsfilter, `a` = mit Filter. */
        const val COUNTS = """
            WITH wm AS (SELECT processed_until, completeness, gap_count, event_count FROM taktovia.dataset_watermark WHERE source = 'ATTEMPT'),
            base AS (SELECT f.service_id, f.channel, f.outcome, f.primary_reason FROM taktovia.fact_attempt f WHERE NOT f.is_test AND f.requested_at >= ? AND f.requested_at < ?),
            a AS (SELECT b.* FROM base b WHERE (CAST(? AS uuid) IS NULL OR b.service_id = CAST(? AS uuid)) AND (CAST(? AS text) IS NULL OR b.channel = CAST(? AS text))),
            cnt AS (SELECT count(*) AS requests,
                           count(*) FILTER (WHERE a.outcome = 'TECHNICAL_ERROR') AS technical,
                           count(*) FILTER (WHERE a.outcome = 'REJECTED' AND r.metric = 'CAPACITY') AS capacity,
                           count(*) FILTER (WHERE a.outcome = 'REJECTED' AND r.metric = 'PERSONNEL') AS personnel,
                           count(*) FILTER (WHERE a.outcome = 'REJECTED' AND r.metric = 'EQUIPMENT') AS equipment,
                           count(*) FILTER (WHERE a.outcome = 'REJECTED' AND r.metric = 'AREA') AS area,
                           count(*) FILTER (WHERE a.outcome = 'REJECTED' AND r.metric = 'TIME_WINDOW') AS time_window,
                           count(*) FILTER (WHERE a.outcome = 'REJECTED' AND r.metric = 'PARTNER') AS partner
                    FROM a LEFT JOIN taktovia.rejection_reason r ON r.code = a.primary_reason)
            SELECT cnt.*, (SELECT count(*) FROM base) AS requests_unfiltered, (SELECT count(*) FROM taktovia.fact_attempt) AS facts_total,
                   wm.processed_until, wm.completeness, coalesce(wm.gap_count, 0) AS gap_count, coalesce(wm.event_count, 0) AS event_count, (wm.event_count IS NOT NULL) AS has_data
            FROM cnt LEFT JOIN wm ON true"""

        /** Ablehnungen je Katalogcode: Primär- und Sekundärzähler, Versuchs- und Fehlerzahl, Datenstand; eine Zeile je Katalogcode im selben Lesestand. */
        const val REJECTIONS = """
            WITH wm AS (SELECT processed_until, completeness, gap_count, event_count FROM taktovia.dataset_watermark WHERE source = 'ATTEMPT'),
            a AS (SELECT f.outcome, f.primary_reason, f.secondary_reasons FROM taktovia.fact_attempt f WHERE NOT f.is_test AND f.requested_at >= ? AND f.requested_at < ?
                  AND (CAST(? AS uuid) IS NULL OR f.service_id = CAST(? AS uuid)) AND (CAST(? AS text) IS NULL OR f.channel = CAST(? AS text))),
            prim AS (SELECT primary_reason AS code, count(*) AS n FROM a WHERE outcome = 'REJECTED' AND primary_reason IS NOT NULL GROUP BY primary_reason),
            sec AS (SELECT s AS code, count(*) AS n FROM a CROSS JOIN LATERAL unnest(a.secondary_reasons) AS s WHERE a.outcome = 'REJECTED' GROUP BY s)
            SELECT r.code, r.reason_group, r.metric, r.label, r.texts::text AS texts, r.priority, coalesce(prim.n, 0) AS primary_count, coalesce(sec.n, 0) AS secondary_count,
                   (SELECT count(*) FROM a) AS attempts, (SELECT count(*) FROM a WHERE a.outcome = 'TECHNICAL_ERROR') AS technical, (SELECT count(*) FROM taktovia.fact_attempt) AS facts_total,
                   wm.processed_until, wm.completeness, coalesce(wm.gap_count, 0) AS gap_count, coalesce(wm.event_count, 0) AS event_count, (wm.event_count IS NOT NULL) AS has_data
            FROM taktovia.rejection_reason r LEFT JOIN prim ON prim.code = r.code LEFT JOIN sec ON sec.code = r.code LEFT JOIN wm ON true ORDER BY r.priority"""

        const val INSERT_SNAPSHOT = """
            INSERT INTO taktovia.aggregate_snapshot(tenant_id, id, metric_id, definition_version, value_kind, filter, filter_hash, period_from, period_to, reporting_timezone, state, reason, value, numerator,
                                                    denominator, coverage, data_as_of, source_events)
            VALUES (taktovia.current_tenant(), ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING RETURNING id"""
        const val SELECT_SNAPSHOT = """
            SELECT id FROM taktovia.aggregate_snapshot WHERE metric_id = ? AND definition_version = ? AND filter_hash = ? AND period_from = ? AND period_to = ? AND reporting_timezone = ?
              AND coalesce(data_as_of, '-infinity'::timestamptz) = coalesce(CAST(? AS timestamptz), '-infinity'::timestamptz) AND source_events = ?"""

        const val PROPOSE = """
            INSERT INTO taktovia.metric_definition_revision(tenant_id, metric_id, version, name, value_kind, unit, numerator_definition, denominator_definition, exclusions, time_basis, period_basis,
                                                            filter_dimensions, source, executable, not_executable_reason, state)
            SELECT m.tenant_id, m.metric_id, NULL, coalesce(?, m.name), m.value_kind, m.unit, coalesce(?, m.numerator_definition), coalesce(?, m.denominator_definition), coalesce(?, m.exclusions),
                   coalesce(?, m.time_basis), m.period_basis, CASE WHEN ? = 1 THEN CAST(? AS text[]) ELSE m.filter_dimensions END, m.source, m.executable, m.not_executable_reason, 'DRAFT'
            FROM taktovia.metric_definition_revision m WHERE m.metric_id = ? ORDER BY m.version DESC LIMIT 1 RETURNING version"""
        const val APPROVE = "UPDATE taktovia.metric_definition_revision SET state = 'APPROVED', approved_by = ? WHERE metric_id = ? AND version = ? AND state = 'DRAFT'"

    }
}

/** Abbildung eines Datenbankfehlers beim Anlegen eines Entwurfs: Eingabefehler. */
private object DbErrorMapperBridge {
    fun map(e: DataAccessException) = ProblemException(ErrorCode.VALIDATION_FAILED, "Die Änderung der Definition ist ungültig.", cause = e)
}

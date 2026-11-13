package de.taktovia.analytics.internal

import de.taktovia.analytics.api.Completeness
import de.taktovia.analytics.api.KmCategory
import de.taktovia.analytics.api.KmClassification
import de.taktovia.analytics.api.KmDay
import de.taktovia.analytics.api.KmRunView
import de.taktovia.analytics.api.KmSummary
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import org.springframework.jdbc.core.JdbcOperations
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Klassifikation der Fahrzeugkilometer auf PostgreSQL (WP-M4-42). Gelesen wird nur lesend: `position_sample`, `staff_shift`, `vehicle_run`, `route_stop` der führenden Module, `fact_stop_event`
 * und `dataset_watermark` der eigenen Faktenbasis; geschrieben werden nur `vehicle_km_run` und `vehicle_km_segment` (append-only). Alle Eingaben werden je Statement einmal gelesen, daraus entsteht
 * der Fingerabdruck des Eingangszustands (`input_sha256`); derselbe Zustand ergibt keinen zweiten Lauf, ein anderer (nachträgliche Positionen, neue Planrevision, neue Ereignisse) einen neuen mit
 * neuem Datenstand. Parallele Läufe für dasselbe Fahrzeug und denselben Tag werden per Advisory-Lock und `UNIQUE (…, input_sha256)` serialisiert.
 */
internal class JdbcKmClassification(private val ctxTx: ContextTransactions, private val tx: TenantTransactions, @Suppress("unused") private val clock: Clock) : KmClassification {

    override fun classify(tenant: TenantId, vehicleId: UUID, serviceDay: LocalDate): KmRunView = tx.run(tenant) { j -> doClassify(j, vehicleId, serviceDay) }

    override fun classifyDay(tenant: TenantId, serviceDay: LocalDate): List<KmRunView> {
        val start = Timestamp.from(serviceDay.atStartOfDay(ZoneOffset.UTC).toInstant()); val end = Timestamp.from(serviceDay.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant())
        val vehicles = tx.run(tenant, readOnly = true) { j -> j.query("SELECT DISTINCT vehicle_id FROM taktovia.position_sample WHERE measured_at >= ? AND measured_at < ? ORDER BY vehicle_id", { rs, _ -> rs.getObject(1, UUID::class.java) }, start, end) }
        return vehicles.map { classify(tenant, it, serviceDay) }
    }

    private fun doClassify(j: JdbcOperations, vehicleId: UUID, day: LocalDate): KmRunView {
        if (j.queryForObject("SELECT count(*) FROM taktovia.vehicle WHERE id = ?", Int::class.java, vehicleId) == 0) throw ProblemException.notFound()
        j.query("SELECT pg_advisory_xact_lock(hashtextextended(taktovia.current_tenant()::text || ':km:' || ? || ':' || ?, 0))", { _, _ -> 0 }, vehicleId.toString(), day.toString())
        val dayStart = day.atStartOfDay(ZoneOffset.UTC).toInstant(); val dayEnd = dayStart.plus(1, ChronoUnit.DAYS)
        val ts0 = Timestamp.from(dayStart); val ts1 = Timestamp.from(dayEnd)

        val points = j.query(
            "WITH s AS (SELECT DISTINCT ON (measured_at) id, measured_at, received_at, position FROM taktovia.position_sample WHERE vehicle_id = ? AND measured_at >= ? AND measured_at < ? ORDER BY measured_at, id) " +
                "SELECT id, measured_at, received_at, CAST(round(ST_Distance(position, lag(position) OVER w)) AS bigint) FROM s WINDOW w AS (ORDER BY measured_at) ORDER BY measured_at",
            { rs, _ -> KmPoint(rs.getObject(1, UUID::class.java), rs.getTimestamp(2).toInstant(), rs.getTimestamp(3).toInstant(), rs.getObject(4)?.let { rs.getLong(4) }) }, vehicleId, ts0, ts1)

        // Betriebsdienste des Fahrzeugs am Tag; Fahrtenplandeckung = nicht stornierter Umlauf mit mindestens einem Stopp in der jüngsten Planrevision
        data class ShiftRow(val shift: KmShift, val planRevision: Long, val planCreatedAt: Instant?)
        val shiftRows = j.query(
            "SELECT s.id, s.starts_at, s.ends_at, s.shift_function, r.revision, " +
                "(SELECT max(rs.created_at) FROM taktovia.route_stop rs WHERE rs.tenant_id = r.tenant_id AND rs.run_id = r.id AND rs.run_revision = r.revision), " +
                "(SELECT count(*) FROM taktovia.route_stop rs WHERE rs.tenant_id = r.tenant_id AND rs.run_id = r.id AND rs.run_revision = r.revision) " +
                "FROM taktovia.staff_shift s LEFT JOIN taktovia.vehicle_run r ON r.tenant_id = s.tenant_id AND r.shift_id = s.id AND r.state <> 'CANCELED' " +
                "WHERE s.vehicle_id = ? AND s.state = 'PUBLISHED' AND s.starts_at < ? AND s.ends_at > ? ORDER BY s.starts_at, s.id",
            { rs, _ -> ShiftRow(KmShift(rs.getObject(1, UUID::class.java), rs.getTimestamp(2).toInstant(), rs.getTimestamp(3).toInstant(), rs.getString(4), rs.getLong(7) > 0), rs.getLong(5), rs.getTimestamp(6)?.toInstant()) },
            vehicleId, ts1, ts0)
        val shifts = shiftRows.map { it.shift }

        // Fahrerereignisse Einstieg (CHECK_IN) und Ausstieg (CHECK_OUT) je Buchung
        data class Ev(val id: UUID, val booking: UUID, val type: String, val at: Instant)
        val events = j.query(
            "SELECT event_id, booking_id, event_type, effective_at FROM taktovia.fact_stop_event WHERE vehicle_id = ? AND outcome = 'APPLIED' AND event_type IN ('CHECK_IN', 'CHECK_OUT') " +
                "AND booking_id IS NOT NULL AND effective_at >= ? AND effective_at < ? ORDER BY effective_at, event_id",
            { rs, _ -> Ev(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getString(3), rs.getTimestamp(4).toInstant()) }, vehicleId, ts0, ts1)
        val occupancy = ArrayList<KmOccupancy>(); var matched = true
        events.groupBy { it.booking }.forEach { (booking, list) ->
            var open: Instant? = null
            for (e in list) {
                if (e.type == "CHECK_IN") { if (open != null) matched = false else open = e.at }
                else if (open != null) { occupancy += KmOccupancy(booking, open, e.at); open = null } else matched = false
            }
            if (open != null) { occupancy += KmOccupancy(booking, open, null); matched = false }
        }
        val watermarkIncomplete = j.queryForObject("SELECT count(*) FROM taktovia.dataset_watermark WHERE source = 'DRIVER_EVENT' AND completeness = 'INCOMPLETE'", Int::class.java) != 0
        val eventsComplete = matched && !watermarkIncomplete

        val segments = KmClassifier.classify(points, shifts, occupancy)
        val total = segments.sumOf { it.meters }
        val dataAsOf = (points.map { it.receivedAt } + shiftRows.mapNotNull { it.planCreatedAt }).maxOrNull() ?: dayStart

        val canon = StringBuilder()
        fun feed(s: String) { canon.append(s).append('\n') }
        feed("v${KmClassification.RULE_VERSION}|$vehicleId|$day|complete=$eventsComplete")
        points.forEach { feed("p|${it.id}|${it.at}|${it.receivedAt}|${it.distanceFromPrevM}") }
        shiftRows.forEach { feed("s|${it.shift.id}|${it.shift.from}|${it.shift.to}|${it.shift.function}|${it.shift.hasPlan}|${it.planRevision}") }
        events.forEach { feed("e|${it.id}|${it.type}|${it.at}") }
        val sha = MessageDigest.getInstance("SHA-256").digest(canon.toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        val existing = j.query("SELECT id FROM taktovia.vehicle_km_run WHERE vehicle_id = ? AND service_day = ? AND input_sha256 = ?", { rs, _ -> rs.getObject(1, UUID::class.java) }, vehicleId, java.sql.Date.valueOf(day), sha).firstOrNull()
        if (existing != null) return view(j, existing, created = false)

        val runId = UUID.randomUUID()
        j.update(
            "INSERT INTO taktovia.vehicle_km_run(tenant_id, id, vehicle_id, service_day, input_sha256, data_as_of, rule_version, position_count, events_complete, total_km_thousandths) " +
                "VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?, ?, ?, ?)", runId, vehicleId, java.sql.Date.valueOf(day), sha, Timestamp.from(dataAsOf), KmClassification.RULE_VERSION, points.size, eventsComplete, total)
        segments.forEach { s ->
            j.update(
                "INSERT INTO taktovia.vehicle_km_segment(tenant_id, id, run_id, vehicle_id, service_day, period_from, period_to, category, km_thousandths, source, rule_version) " +
                    "VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?, ?, ?, 'POSITION', ?)", UUID.randomUUID(), runId, vehicleId, java.sql.Date.valueOf(day), Timestamp.from(s.from), Timestamp.from(s.to), s.category.name, s.meters, KmClassification.RULE_VERSION)
        }
        return view(j, runId, created = true)
    }

    private fun view(j: JdbcOperations, runId: UUID, created: Boolean): KmRunView {
        val r = j.query("SELECT vehicle_id, service_day, data_as_of, rule_version, position_count, events_complete, total_km_thousandths FROM taktovia.vehicle_km_run WHERE id = ?",
            { rs, _ -> arrayOf(rs.getObject(1, UUID::class.java), rs.getDate(2).toLocalDate(), rs.getTimestamp(3).toInstant(), rs.getInt(4), rs.getInt(5), rs.getBoolean(6), rs.getLong(7)) }, runId).single()
        return KmRunView(runId, r[0] as UUID, r[1] as LocalDate, r[2] as Instant, r[3] as Int, r[4] as Int, r[5] as Boolean, r[6] as Long, categories(j, runId), created)
    }

    private fun categories(j: JdbcOperations, runId: UUID): Map<KmCategory, Long> {
        val sums = j.query("SELECT category, sum(km_thousandths) FROM taktovia.vehicle_km_segment WHERE run_id = ? GROUP BY category", { rs, _ -> KmCategory.valueOf(rs.getString(1)) to rs.getLong(2) }, runId).toMap()
        return KmCategory.entries.associateWith { sums[it] ?: 0L }
    }

    override fun summary(ctx: RequestContext, vehicleId: UUID?, from: LocalDate, to: LocalDate): KmSummary {
        if (!ctx.hasScope(KmClassification.SCOPE_READ)) throw ProblemException(ErrorCode.FORBIDDEN)
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) + 1 > KmClassification.MAX_DAYS) throw ProblemException.invalid(Violation("/\$query/to", ViolationCode.INTERVAL_INVALID))
        return ctxTx.run(ctx, readOnly = true) { j ->
            if (vehicleId != null && j.queryForObject("SELECT count(*) FROM taktovia.vehicle WHERE id = ?", Int::class.java, vehicleId) == 0) throw ProblemException.notFound()
            // jüngster Lauf je Fahrzeug und Tag (Datenstand, dann Entstehung); die Summen je Kategorie kommen aus den Abschnitten dieser Läufe
            val days = j.query(
                "SELECT DISTINCT ON (vehicle_id, service_day) id, vehicle_id, service_day, data_as_of, total_km_thousandths, events_complete FROM taktovia.vehicle_km_run " +
                    "WHERE service_day >= ? AND service_day <= ? AND (CAST(? AS uuid) IS NULL OR vehicle_id = CAST(? AS uuid)) ORDER BY vehicle_id, service_day, data_as_of DESC, created_at DESC",
                { rs, _ -> arrayOf<Any>(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getDate(3).toLocalDate(), rs.getTimestamp(4).toInstant(), rs.getLong(5), rs.getBoolean(6)) },
                java.sql.Date.valueOf(from), java.sql.Date.valueOf(to), vehicleId, vehicleId,
            ).map { KmDay(it[2] as LocalDate, it[1] as UUID, it[0] as UUID, it[3] as Instant, it[4] as Long, categories(j, it[0] as UUID), it[5] as Boolean) }.sortedWith(compareBy({ it.serviceDay }, { it.vehicleId }))
            val byCat = KmCategory.entries.associateWith { c -> days.sumOf { it.byCategory.getValue(c) } }
            KmSummary(KmClassification.RULE_VERSION, from, to, vehicleId, days.maxOfOrNull { it.dataAsOf }, if (days.all { it.eventsComplete }) Completeness.COMPLETE else Completeness.INCOMPLETE,
                days.sumOf { it.totalKmThousandths }, byCat, days)
        }
    }
}

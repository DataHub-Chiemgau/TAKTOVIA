package de.taktovia.bookingdispatch.internal

import de.taktovia.bookingdispatch.api.BookingSearchFilter
import de.taktovia.bookingdispatch.api.BookingSearchRow
import de.taktovia.bookingdispatch.api.OperatorBookingSearch
import de.taktovia.bookingdispatch.api.OperatorFlagView
import de.taktovia.platform.authz.ServiceScopeAuthorizer
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.platform.statemachine.BookingState
import org.springframework.jdbc.core.JdbcOperations
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Auftragsliste mit Suche (WP-M3-37) aus `booking`, `capacity_hold`, `vehicle_run` und `vehicle` (Fahrzeug des Umlaufs). **Eine** feste Abfrage: jeder Filter ist ein gebundener Parameter mit
 * `IS NULL`-Ausschluss, nie ein zusammengesetztes SQL. Der Dienstscope kommt frisch aus den Mitgliedschaften des Kontexts ([ServiceScopeAuthorizer.currentServices]) in derselben Transaktion;
 * der Mandant aus RLS. Nichts, was die Filterwerte enthält, wird protokolliert.
 */
internal class JdbcOperatorBookingSearch(private val ctxTx: ContextTransactions) : OperatorBookingSearch {
    private val json = JsonMapper.builder().build()

    override fun search(ctx: RequestContext, filter: BookingSearchFilter, limit: Int, after: Pair<Instant, UUID>?): List<BookingSearchRow> {
        if (ctx.actor.type != ActorType.HUMAN || "passenger" in ctx.roles) throw ProblemException(ErrorCode.FORBIDDEN)
        val passengerData = ctx.roles.any { it in OperatorBookingSearch.PASSENGER_DATA_ROLES }
        if (filter.passengerRef != null && !passengerData) throw ProblemException(ErrorCode.FORBIDDEN, "Die Fahrgastsuche setzt das Recht auf Fahrgastdaten voraus.")
        if (filter.state != null && BookingState.entries.none { it.name == filter.state }) throw ProblemException.invalid(Violation("/\$query/state", ViolationCode.NOT_IN_ENUM))
        if (filter.from != null && filter.to != null && !filter.to.isAfter(filter.from)) throw ProblemException.invalid(Violation("/\$query/to", ViolationCode.INTERVAL_INVALID))
        require(limit in 1..MAX_LIMIT) { "limit" }
        val assistance = ctx.roles.any { it in ASSISTANCE_ROLES }
        return ctxTx.run(ctx, readOnly = true) { j ->
            var services = ServiceScopeAuthorizer.currentServices(j, ctx)
            filter.serviceId?.let { s -> services = services.filter { it == s }.toSet() }
            if (services.isEmpty()) return@run emptyList()
            val scope = services.joinToString(",", "{", "}")   // nur UUIDs aus dem Scope der Mitgliedschaften
            val number = filter.bookingNumber?.trim()?.uppercase()
            val rows = j.query(
                "SELECT b.id, b.booking_no, b.service_id, b.state, b.revision, b.created_at, b.promise_snapshot::text, b.passenger_id, r.vehicle_id, v.vehicle_label FROM taktovia.booking b " +
                    "LEFT JOIN taktovia.capacity_hold h ON h.tenant_id = b.tenant_id AND h.id = b.hold_id " +
                    "LEFT JOIN taktovia.vehicle_run r ON r.tenant_id = h.tenant_id AND r.id = h.run_id " +
                    "LEFT JOIN taktovia.vehicle v ON v.tenant_id = r.tenant_id AND v.id = r.vehicle_id " +
                    "WHERE b.booking_no IS NOT NULL AND b.service_id = ANY(CAST(? AS uuid[])) " +
                    "AND (CAST(? AS text) IS NULL OR b.booking_no = CAST(? AS text)) " +
                    "AND (CAST(? AS uuid) IS NULL OR b.passenger_id = CAST(? AS uuid)) " +
                    "AND (CAST(? AS uuid) IS NULL OR r.vehicle_id = CAST(? AS uuid)) " +
                    "AND (CAST(? AS text) IS NULL OR b.state = CAST(? AS text)) " +
                    "AND (CAST(? AS timestamptz) IS NULL OR CAST(b.promise_snapshot ->> 'arrival_latest' AS timestamptz) >= CAST(? AS timestamptz)) " +
                    "AND (CAST(? AS timestamptz) IS NULL OR CAST(b.promise_snapshot ->> 'pickup_earliest' AS timestamptz) < CAST(? AS timestamptz)) " +
                    "AND (CAST(? AS timestamptz) IS NULL OR (b.created_at, b.id) > (CAST(? AS timestamptz), CAST(? AS uuid))) " +
                    "ORDER BY b.created_at, b.id LIMIT ?",
                { rs, _ -> Base(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getObject(3, UUID::class.java), rs.getString(4), rs.getLong(5), rs.getTimestamp(6).toInstant(), rs.getString(7), rs.getObject(8, UUID::class.java), rs.getObject(9, UUID::class.java), rs.getString(10)) },
                scope, number, number, filter.passengerRef?.toString(), filter.passengerRef?.toString(), filter.vehicleId?.toString(), filter.vehicleId?.toString(), filter.state, filter.state,
                filter.from?.let { Timestamp.from(it) }, filter.from?.let { Timestamp.from(it) }, filter.to?.let { Timestamp.from(it) }, filter.to?.let { Timestamp.from(it) },
                after?.let { Timestamp.from(it.first) }, after?.let { Timestamp.from(it.first) }, after?.second?.toString(), limit + 1,
            )
            val codes = if (assistance) assistanceCodes(j, rows.map { it.id }) else emptyMap()
            val flags = flagsOf(j, rows.map { it.id }, scope)
            rows.map { b -> row(b, passengerData, if (assistance) codes[b.id].orEmpty() else null, flags[b.id].orEmpty()) }
        }
    }

    private class Base(val id: UUID, val no: String, val serviceId: UUID, val state: String, val revision: Long, val createdAt: Instant, val snapshot: String, val passengerId: UUID?, val vehicleId: UUID?, val vehicleLabel: String?)

    private fun row(b: Base, passengerData: Boolean, codes: List<String>?, flags: List<OperatorFlagView>): BookingSearchRow {
        val node = json.readTree(b.snapshot)
        fun at(f: String) = OffsetDateTime.parse(node[f].asString()).toInstant()
        val vector = node["resource_vector"]?.let { ResourceVectorLite.of(it.toString()) }
        return BookingSearchRow(b.id, b.no, b.serviceId, b.state, b.revision, b.createdAt, at("pickup_earliest"), at("pickup_latest"), at("arrival_earliest"), at("arrival_latest"),
            vector?.first ?: 0, vector?.second ?: 0, b.vehicleId, b.vehicleLabel, if (passengerData) b.passengerId else null, codes, flags)
    }

    /** Offene Hinweise (Verspätung, ausstehende Information, WP-M3-42) je Buchung: nur Hinweise im Dienstscope; die Zeile nennt Art, Ursache und Betroffenenzahl, nie Namen. */
    private fun flagsOf(j: JdbcOperations, ids: List<UUID>, scope: String): Map<UUID, List<OperatorFlagView>> {
        if (ids.isEmpty()) return emptyMap()
        class F(val view: OperatorFlagView, val bookings: List<UUID>)
        val found = j.query(
            "SELECT id, kind, cause, affected_count, opened_at, threshold_min, affected_bookings FROM taktovia.operator_flag WHERE state = 'OPEN' AND affected_bookings && CAST(? AS uuid[]) " +
                "AND (service_id IS NULL OR service_id = ANY(CAST(? AS uuid[]))) ORDER BY opened_at, id",
            { rs, _ -> F(OperatorFlagView(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getTimestamp(5).toInstant(), rs.getObject(6) as Int?), (rs.getArray(7).array as Array<*>).map { it as UUID }) },
            ids.joinToString(",", "{", "}"), scope,
        )
        val out = HashMap<UUID, MutableList<OperatorFlagView>>()
        for (f in found) for (b in f.bookings) if (b in ids) out.getOrPut(b) { mutableListOf() }.add(f.view)
        return out
    }

    /** Unterstützungsmerkmale je Buchung, Vereinigung über alle Fahrgastzeilen (nur für Rollen mit Feldfreigabe gelesen); ohne Eintrag eine leere Liste. */
    private fun assistanceCodes(j: JdbcOperations, ids: List<UUID>): Map<UUID, List<String>> {
        if (ids.isEmpty()) return emptyMap()
        class Entry(val id: UUID, val codes: String?)
        return j.query(
            "SELECT t.booking_id, string_agg(DISTINCT c.code, ',' ORDER BY c.code) FROM taktovia.passenger_trip t LEFT JOIN LATERAL unnest(t.assistance_codes) AS c(code) ON true " +
                "WHERE t.booking_id = ANY(CAST(? AS uuid[])) GROUP BY t.booking_id",
            { rs, _ -> Entry(rs.getObject(1, UUID::class.java), rs.getString(2)) }, ids.joinToString(",", "{", "}"),
        ).associate { it.id to it.codes.orEmpty().split(',').filter { c -> c.isNotBlank() } }
    }

    private companion object {
        const val MAX_LIMIT = 101
        val ASSISTANCE_ROLES = setOf("dispatcher", "callcenter")
    }
}

/** Personen- und Rollstuhlplätze aus dem Ressourcenvektor der Zusage (`seats` ohne Rollstuhl, `wheelchair`): Summe = Personen, wie [de.taktovia.bookingdispatch.api.BookingDetail.passengers]. */
internal object ResourceVectorLite {
    fun of(text: String): Pair<Int, Int> {
        val v = de.taktovia.bookingdispatch.api.ResourceVector.parse(text)
        return (v.seats + v.wheelchair) to v.wheelchair
    }
}

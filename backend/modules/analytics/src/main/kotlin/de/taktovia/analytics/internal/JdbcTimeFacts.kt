package de.taktovia.analytics.internal

import de.taktovia.analytics.api.ConsumeOutcome
import de.taktovia.analytics.api.Delay
import de.taktovia.analytics.api.NotAssessable
import de.taktovia.analytics.api.RecordOutcome
import de.taktovia.analytics.api.StopEvent
import de.taktovia.analytics.api.StopTimes
import de.taktovia.analytics.api.TimeFactCommand
import de.taktovia.analytics.api.TimeFactView
import de.taktovia.analytics.api.TimeFacts
import de.taktovia.analytics.api.TimeKind
import de.taktovia.analytics.api.TripTimes
import de.taktovia.platform.outbox.EnvelopeAddressing
import de.taktovia.platform.outbox.EventEnvelope
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import org.springframework.jdbc.core.JdbcOperations
import tools.jackson.databind.JsonNode
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * Zeitfakten auf PostgreSQL (WP-M4-52). Jede Version einer Zeitart wird mit `ON CONFLICT DO NOTHING` auf den Eindeutigkeitsschlüssel angehängt: parallele Verarbeiter und Wiederholungen
 * erzeugen jede Revision genau einmal, ohne Sperren und ohne je eine bestehende Zeile zu ändern. Die Verspätung rechnet die SQL-Funktion `time_fact_delay` (eine Regel, eine Stelle).
 */
internal class JdbcTimeFacts(private val tx: TenantTransactions) : TimeFacts {
    override fun consume(workerTenant: TenantId, envelopeJson: String): ConsumeOutcome {
        val e = try { EventEnvelope.fromJson(envelopeJson) } catch (ex: IllegalArgumentException) { return ConsumeOutcome.REJECTED_SCHEMA }
        if (e.major != 1) return ConsumeOutcome.REJECTED_SCHEMA
        val recipient = try { EnvelopeAddressing.requireAddressed(e) } catch (ex: IllegalArgumentException) { return ConsumeOutcome.REJECTED_TENANT }
        if (recipient != workerTenant.value || e.ownerTenantId != workerTenant.value) return ConsumeOutcome.REJECTED_TENANT
        val commands = try {
            when (e.eventType) {
                "booking.confirmed" -> fromConfirmed(e)
                "dispatch.run_revised" -> fromRunRevised(e)
                "driver.event_recorded" -> fromDriverEvent(e)
                else -> return ConsumeOutcome.UNSUPPORTED_EVENT
            }
        } catch (ex: IllegalArgumentException) { return ConsumeOutcome.REJECTED_SCHEMA } catch (ex: java.time.format.DateTimeParseException) { return ConsumeOutcome.REJECTED_SCHEMA }
        if (commands.isEmpty()) return ConsumeOutcome.UNSUPPORTED_EVENT
        val outcomes = tx.run(workerTenant) { j -> commands.map { append(j, it) } }
        return when {
            RecordOutcome.CONFLICT in outcomes -> ConsumeOutcome.REJECTED_PAYLOAD_MISMATCH
            RecordOutcome.CREATED in outcomes -> ConsumeOutcome.PROCESSED
            else -> ConsumeOutcome.DUPLICATE
        }
    }

    private fun fromConfirmed(e: EventEnvelope): List<TimeFactCommand> {
        val p = e.payload
        val booking = uuid(p, "booking_id") ?: throw IllegalArgumentException("booking_id")
        val pickupLatest = instant(p, "pickup_latest") ?: throw IllegalArgumentException("pickup_latest")
        val pickupEarliest = instant(p, "pickup_earliest") ?: throw IllegalArgumentException("pickup_earliest")
        val version = e.aggregateRevision
        return listOfNotNull(
            TimeFactCommand(booking, StopEvent.PICKUP, TimeKind.PROMISED, pickupLatest, "BOOKING_CONFIRMED", booking, windowEarliest = pickupEarliest, promiseVersion = version),
            instant(p, "arrival_latest")?.let { TimeFactCommand(booking, StopEvent.ARRIVAL, TimeKind.PROMISED, it, "BOOKING_CONFIRMED", booking, promiseVersion = version) },
        )
    }

    private fun fromRunRevised(e: EventEnvelope): List<TimeFactCommand> {
        val p = e.payload
        val run = uuid(p, "run_id") ?: throw IllegalArgumentException("run_id")
        val revision = p.get("revision")?.asLong() ?: throw IllegalArgumentException("revision")
        return p.get("booking_changes")?.takeIf { it.isArray }?.flatMap { c ->
            if (c.get("kind")?.asString() == "REMOVED") return@flatMap emptyList()
            val booking = uuid(c, "booking_id") ?: throw IllegalArgumentException("booking_id")
            listOfNotNull(
                instant(c, "pickup_after")?.let { TimeFactCommand(booking, StopEvent.PICKUP, TimeKind.PLANNED, it, "PLAN_REVISION", run, planRevision = revision) },
                instant(c, "dropoff_after")?.let { TimeFactCommand(booking, StopEvent.ARRIVAL, TimeKind.PLANNED, it, "PLAN_REVISION", run, planRevision = revision) },
            )
        } ?: emptyList()
    }

    /** Ist-Abholung = Einstieg (`CHECK_IN`), Ist-Ankunft = Ausstieg (`CHECK_OUT`), nur wirksame Ereignisse (`APPLIED`). Serverzeit = Empfangszeit, sonst Zeit der Ereignishülle; offline ist die Ereigniszeit die Gerätezeit. */
    private fun fromDriverEvent(e: EventEnvelope): List<TimeFactCommand> {
        val p = e.payload
        if (p.get("outcome")?.asString() != "APPLIED") return emptyList()
        val stop = when (p.get("event_type")?.asString()) { "CHECK_IN" -> StopEvent.PICKUP; "CHECK_OUT" -> StopEvent.ARRIVAL; else -> return emptyList() }
        val booking = uuid(p, "booking_id") ?: throw IllegalArgumentException("booking_id")
        val effective = instant(p, "effective_at") ?: throw IllegalArgumentException("effective_at")
        val offline = p.get("offline_recorded")?.asBoolean() ?: false
        return listOf(TimeFactCommand(booking, stop, TimeKind.ACTUAL, effective, "DRIVER_EVENT", e.eventId, routeStopId = uuid(p, "route_stop_id"),
            deviceTime = if (offline) effective else null, serverTime = instant(p, "received_at") ?: e.recordedAt, offlineRecorded = offline))
    }

    override fun record(tenant: TenantId, command: TimeFactCommand): RecordOutcome = tx.run(tenant) { j -> append(j, command) }

    private fun versionKey(c: TimeFactCommand): String = when (c.kind) {
        TimeKind.PROMISED -> "P${c.promiseVersion}"
        TimeKind.PLANNED -> "R${c.planRevision}:${c.sourceRef}"
        TimeKind.FORECAST -> "F${c.forecastVersion}:${c.sourceRef}"
        TimeKind.ANNOUNCED -> "N${c.promiseVersion ?: 0}:${c.sourceRef}"
        TimeKind.ACTUAL -> "E:${c.sourceRef}"
    }

    private fun append(j: JdbcOperations, c: TimeFactCommand): RecordOutcome {
        val key = versionKey(c)
        val n = j.update(
            "INSERT INTO taktovia.time_fact(tenant_id, booking_id, stop_event, time_kind, value_at, window_earliest, source, source_ref, route_stop_id, version_key, promise_version, plan_revision, forecast_version, device_time, server_time, offline_recorded) " +
                "VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (tenant_id, booking_id, stop_event, time_kind, version_key) DO NOTHING",
            c.bookingId, c.stopEvent.name, c.kind.name, ts(c.value), c.windowEarliest?.let(::ts), c.source, c.sourceRef, c.routeStopId, key, c.promiseVersion, c.planRevision, c.forecastVersion,
            c.deviceTime?.let(::ts), c.serverTime?.let(::ts), c.offlineRecorded)
        if (n == 1) return RecordOutcome.CREATED
        val same = j.queryForObject(
            "SELECT count(*) FROM taktovia.time_fact WHERE booking_id = ? AND stop_event = ? AND time_kind = ? AND version_key = ? AND value_at = ? AND window_earliest IS NOT DISTINCT FROM ?",
            Int::class.java, c.bookingId, c.stopEvent.name, c.kind.name, key, ts(c.value), c.windowEarliest?.let(::ts))!!
        return if (same == 1) RecordOutcome.DUPLICATE else RecordOutcome.CONFLICT
    }

    override fun forBooking(tenant: TenantId, bookingId: UUID): TripTimes = tx.run(tenant, readOnly = true) { j ->
        val facts = j.query(
            "SELECT stop_event, time_kind, value_at, window_earliest, source, source_ref, route_stop_id, promise_version, plan_revision, forecast_version, device_time, server_time, offline_recorded, recorded_at " +
                "FROM taktovia.time_fact WHERE booking_id = ? ORDER BY seq",
            { rs, _ ->
                StopEvent.valueOf(rs.getString(1)) to TimeFactView(TimeKind.valueOf(rs.getString(2)), rs.getTimestamp(3).toInstant(), rs.getTimestamp(4)?.toInstant(), rs.getString(5), rs.getObject(6, UUID::class.java),
                    rs.getObject(7, UUID::class.java), rs.getObject(8) as Long?, rs.getObject(9) as Long?, rs.getObject(10) as Long?, rs.getTimestamp(11)?.toInstant(), rs.getTimestamp(12)?.toInstant(), rs.getBoolean(13), rs.getTimestamp(14).toInstant())
            }, bookingId)
        val delays = j.query("SELECT stop_event, promise_version, target_at, actual_at, delay_seconds, not_assessable_reason FROM taktovia.time_fact_delay(?)",
            { rs, _ -> StopEvent.valueOf(rs.getString(1)) to Delay(rs.getObject(5) as Long?, rs.getString(6)?.let(NotAssessable::valueOf), rs.getObject(2) as Long?, rs.getTimestamp(3)?.toInstant(), rs.getTimestamp(4)?.toInstant()) }, bookingId).toMap()
        fun stop(s: StopEvent): StopTimes {
            val mine = facts.filter { it.first == s }.map { it.second }
            fun of(k: TimeKind) = mine.filter { it.kind == k }
            return StopTimes(s, of(TimeKind.PLANNED), of(TimeKind.ANNOUNCED), of(TimeKind.PROMISED), of(TimeKind.FORECAST), of(TimeKind.ACTUAL), delays[s] ?: Delay(null, NotAssessable.NO_PROMISE, null, null, null))
        }
        TripTimes(bookingId, stop(StopEvent.PICKUP), stop(StopEvent.ARRIVAL))
    }

    private fun ts(i: Instant) = Timestamp.from(i)
    private fun text(p: JsonNode, k: String): String? = p.get(k)?.takeIf { !it.isNull }?.asString()
    private fun uuid(p: JsonNode, k: String): UUID? = text(p, k)?.let(UUID::fromString)
    private fun instant(p: JsonNode, k: String): Instant? = text(p, k)?.let { java.time.OffsetDateTime.parse(it).toInstant() }
}

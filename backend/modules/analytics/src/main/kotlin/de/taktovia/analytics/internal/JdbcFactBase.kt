package de.taktovia.analytics.internal

import de.taktovia.analytics.api.AttemptReconciliation
import de.taktovia.analytics.api.Completeness
import de.taktovia.analytics.api.ConsumeOutcome
import de.taktovia.analytics.api.FactBase
import de.taktovia.analytics.api.FactSource
import de.taktovia.analytics.api.Watermark
import de.taktovia.platform.outbox.EnvelopeAddressing
import de.taktovia.platform.outbox.EventEnvelope
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import org.springframework.jdbc.core.JdbcOperations
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID

/**
 * Faktenbasis auf PostgreSQL (WP-M4-45). Je Ereignis eine Transaktion im Mandantenkontext des Workers: Sperre auf den Fakt (Buchung bzw. Aggregat) und auf die `event_id`, Eingang mit
 * `ON CONFLICT DO NOTHING` (Dedupe), danach Ableitung des betroffenen Fakts **aus allen Eingangszeilen** und Neuberechnung des Datenstands der Quelle. Weil jede Ableitung die ganze Menge
 * liest, ist das Ergebnis unabhängig von Reihenfolge und Wiederholung; die Sperre sorgt dafür, dass zwei Worker, die Ereignisse desselben Fakts gleichzeitig verarbeiten, nacheinander laufen
 * und der zweite den Eingang des ersten sieht.
 */
internal class JdbcFactBase(private val tx: TenantTransactions, private val clock: Clock) : FactBase {
    private val mapper = JsonMapper.builder().build()

    override fun consume(workerTenant: TenantId, envelopeJson: String): ConsumeOutcome {
        val e = try { EventEnvelope.fromJson(envelopeJson) } catch (ex: IllegalArgumentException) { return ConsumeOutcome.REJECTED_SCHEMA }
        if (e.major != 1) return ConsumeOutcome.REJECTED_SCHEMA
        // Tenantkontext: Empfänger und Eigentümer müssen der Mandant des Workers sein (keine Kooperationsfreigabe in P1); ein fremdes Ereignis wird verworfen, bevor etwas gespeichert wird
        val recipient = try { EnvelopeAddressing.requireAddressed(e) } catch (ex: IllegalArgumentException) { return ConsumeOutcome.REJECTED_TENANT }
        if (recipient != workerTenant.value || e.ownerTenantId != workerTenant.value) return ConsumeOutcome.REJECTED_TENANT
        val source = Source.of(e.eventType) ?: return ConsumeOutcome.UNSUPPORTED_EVENT
        val payload = reduce(source, e) ?: return ConsumeOutcome.REJECTED_SCHEMA
        return tx.run(workerTenant) { j -> process(j, e, source, payload) }
    }

    /** Nutzlast auf die erlaubten Fachfelder reduzieren; `null`, wenn ein Pflichtfeld des Fakts fehlt. */
    private fun reduce(source: Source, e: EventEnvelope): JsonNode? {
        val allowed = source.events.getValue(e.eventType)
        val out = mapper.createObjectNode()
        allowed.forEach { k -> e.payload.get(k)?.takeIf { !it.isNull }?.let { out.set(k, it) } }
        val keyField = when (e.eventType) { "booking.attempt_recorded" -> "attempt_id"; "booking.offer_created" -> "offer_id"; "driver.event_recorded" -> "event_id"; else -> null }
        if (keyField != null && !out.has(keyField)) return null
        if (e.eventType.startsWith("booking.c") && !out.has("booking_id")) return null
        if (e.eventType.startsWith("fleet.") && !out.has("vehicle_id")) return null
        return out
    }

    private fun process(j: JdbcOperations, e: EventEnvelope, source: Source, payload: JsonNode): ConsumeOutcome {
        val hash = EventEnvelope.sha256(payload)
        // Ablehnungsgründe gegen den Katalog prüfen, bevor etwas gespeichert wird (ein unbekannter Grund des Buchungskerns erreicht weder Eingang noch Fakten)
        if (source == Source.ATTEMPT && !reasonsValid(j, payload)) return ConsumeOutcome.REJECTED_REASON
        // Sperren in fester Reihenfolge: zuerst das Ereignis, dann der Fakt (Buchung bzw. Aggregat)
        lock(j, "event:${e.eventId}")
        j.query("SELECT payload_sha256 FROM taktovia.analytics_event_inbox WHERE event_id = ?", { rs, _ -> rs.getString(1) }, e.eventId).firstOrNull()?.let {
            return if (it == hash) ConsumeOutcome.DUPLICATE else ConsumeOutcome.REJECTED_PAYLOAD_MISMATCH
        }
        val bookingId = bookingOf(e.eventType, payload)
        lock(j, "fact:${bookingId ?: e.aggregateId}")
        val n = j.update(
            "INSERT INTO taktovia.analytics_event_inbox(tenant_id, event_id, source, event_type, aggregate_id, aggregate_revision, occurred_at, recorded_at, payload_sha256, payload, received_at) " +
                "VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?) ON CONFLICT (tenant_id, event_id) DO NOTHING",
            e.eventId, source.name, e.eventType, e.aggregateId, e.aggregateRevision, Timestamp.from(e.occurredAt), Timestamp.from(e.recordedAt), hash, mapper.writeValueAsString(payload), Timestamp.from(clock.instant()))
        if (n == 0) return ConsumeOutcome.DUPLICATE
        project(j, source, e.eventType, e.aggregateId, e.eventId, payload)
        updateWatermark(j, source)
        return ConsumeOutcome.PROCESSED
    }

    /**
     * Ablehnung (WP-M4-48): genau dann `REJECTED`, wenn ein Primärgrund vorliegt; Primär- und Nebengründe sind gültige, fachlich zählende Katalogcodes (technische Fehler nie), Nebengründe ohne Wiederholung,
     * nicht der Primärgrund und nur mit Primärgrund. Dieselben Regeln erzwingt die Datenbank an `fact_attempt` als zweite Verteidigungslinie.
     */
    private fun reasonsValid(j: JdbcOperations, p: JsonNode): Boolean {
        val primary = text(p, "primary_reason")
        val secondary = strings(p, "secondary_reasons")
        if ((text(p, "outcome") == "REJECTED") != (primary != null)) return false
        if (secondary.isNotEmpty() && primary == null) return false
        if (secondary.distinct().size != secondary.size || (primary != null && primary in secondary)) return false
        val codes = listOfNotNull(primary) + secondary
        if (codes.isEmpty()) return true
        if (codes.any { !CODE.matches(it) }) return false
        val at = ts(p, "requested_at") ?: return false
        val known = j.query("SELECT code FROM taktovia.rejection_reason WHERE code = ANY (CAST(? AS text[])) AND counts_as_rejection AND valid_from <= ? AND (valid_to IS NULL OR valid_to > ?)",
            { rs, _ -> rs.getString(1) }, pgArray(codes), at, at).toSet()
        return known.containsAll(codes)
    }

    private fun strings(p: JsonNode, k: String): List<String> {
        val n = p.get(k)
        return if (n == null || !n.isArray) emptyList() else (0 until n.size()).map { n.get(it).asString() }
    }

    private fun pgArray(values: List<String>): String = values.joinToString(",", "{", "}")

    private fun lock(j: JdbcOperations, key: String) {
        j.query("SELECT pg_advisory_xact_lock(hashtextextended(taktovia.current_tenant()::text || ':analytics:' || ?, 0))", { _, _ -> 0 }, key)
    }

    private fun bookingOf(eventType: String, payload: JsonNode): UUID? =
        if (eventType.startsWith("booking.c") || eventType == "driver.event_recorded") payload.get("booking_id")?.asString()?.let(UUID::fromString) else null

    // ---- Ableitung der Fakten aus der Menge der Eingangszeilen ---------------------------------------------------------------------------

    private class Row(val eventType: String, val rev: Long, val occurredAt: java.time.Instant, val eventId: UUID, val p: JsonNode)

    /** Eingangszeilen eines Aggregats in der festen Ordnung (Revision, Ereigniszeit, event_id): die Ableitung hängt nie von der Ankunftsreihenfolge ab. */
    private fun rows(j: JdbcOperations, source: Source, aggregateId: UUID): List<Row> =
        j.query("SELECT event_type, aggregate_revision, occurred_at, event_id, payload::text FROM taktovia.analytics_event_inbox WHERE source = ? AND aggregate_id = ? ORDER BY aggregate_revision, occurred_at, event_id",
            { rs, _ -> Row(rs.getString(1), rs.getLong(2), rs.getTimestamp(3).toInstant(), rs.getObject(4, UUID::class.java), mapper.readTree(rs.getString(5))) }, source.name, aggregateId)

    private fun project(j: JdbcOperations, source: Source, eventType: String, aggregateId: UUID, eventId: UUID, payload: JsonNode) {
        when (source) {
            Source.ATTEMPT -> attempt(j, aggregateId)
            Source.OFFER -> offer(j, aggregateId)
            Source.BOOKING -> booking(j, UUID.fromString(payload.get("booking_id").asString()))
            Source.DRIVER_EVENT -> { stopEvent(j, eventId, payload); payload.get("booking_id")?.asString()?.let { booking(j, UUID.fromString(it)) } }
            Source.VEHICLE_STATE -> vehicleState(j, eventType, eventId, payload, rows(j, source, aggregateId).first { it.eventId == eventId }.occurredAt)
        }
    }

    private fun attempt(j: JdbcOperations, id: UUID) {
        val p = rows(j, Source.ATTEMPT, id).lastOrNull()?.p ?: return    // höchste Revision gewinnt
        val secondary = strings(p, "secondary_reasons")
        j.update("INSERT INTO taktovia.fact_attempt(tenant_id, attempt_id, service_id, outcome, primary_reason, secondary_reasons, is_test, time_mode, requested_at, passengers, wheelchair, coarse_origin, coarse_destination, channel) " +
            "VALUES (taktovia.current_tenant(), ?, ?, ?, ?, CAST(? AS text[]), ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (tenant_id, attempt_id) DO UPDATE SET service_id = EXCLUDED.service_id, outcome = EXCLUDED.outcome, " +
            "primary_reason = EXCLUDED.primary_reason, secondary_reasons = EXCLUDED.secondary_reasons, is_test = EXCLUDED.is_test, " +
            "time_mode = EXCLUDED.time_mode, requested_at = EXCLUDED.requested_at, passengers = EXCLUDED.passengers, wheelchair = EXCLUDED.wheelchair, coarse_origin = EXCLUDED.coarse_origin, " +
            "coarse_destination = EXCLUDED.coarse_destination, channel = EXCLUDED.channel",
            id, uuid(p, "service_id"), text(p, "outcome"), text(p, "primary_reason"), pgArray(secondary), p.get("is_test")?.asBoolean() ?: false, text(p, "time_mode"), ts(p, "requested_at"), p.get("passengers")?.asInt() ?: 0,
            p.get("wheelchair")?.asInt(), text(p, "coarse_origin"), text(p, "coarse_destination"), text(p, "channel"))
    }

    private fun offer(j: JdbcOperations, id: UUID) {
        val r = rows(j, Source.OFFER, id).lastOrNull() ?: return
        val p = r.p
        j.update("INSERT INTO taktovia.fact_offer(tenant_id, offer_id, attempt_id, service_id, created_at, expires_at, pickup_earliest, pickup_latest, gross_minor, currency) " +
            "VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (tenant_id, offer_id) DO UPDATE SET attempt_id = EXCLUDED.attempt_id, service_id = EXCLUDED.service_id, created_at = EXCLUDED.created_at, " +
            "expires_at = EXCLUDED.expires_at, pickup_earliest = EXCLUDED.pickup_earliest, pickup_latest = EXCLUDED.pickup_latest, gross_minor = EXCLUDED.gross_minor, currency = EXCLUDED.currency",
            id, uuid(p, "attempt_id"), uuid(p, "service_id"), Timestamp.from(r.occurredAt), ts(p, "expires_at"), ts(p, "pickup_earliest"), ts(p, "pickup_latest"), p.get("gross_minor").asLong(), text(p, "currency"))
    }

    private fun stopEvent(j: JdbcOperations, eventId: UUID, p: JsonNode) {
        val occurred = j.queryForObject("SELECT occurred_at FROM taktovia.analytics_event_inbox WHERE event_id = ?", java.sql.Timestamp::class.java, eventId)!!
        j.update("INSERT INTO taktovia.fact_stop_event(tenant_id, event_id, shift_id, vehicle_id, booking_id, route_stop_id, event_type, outcome, reason_code, effective_at, occurred_at, offline_recorded, no_show_wait_seconds, no_show_grace_min) " +
            "VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (tenant_id, event_id) DO NOTHING",
            eventId, uuid(p, "shift_id"), uuid(p, "vehicle_id"), p.get("booking_id")?.asString()?.let(UUID::fromString), p.get("route_stop_id")?.asString()?.let(UUID::fromString), text(p, "event_type"), text(p, "outcome"), text(p, "reason_code"),
            ts(p, "effective_at") ?: occurred, occurred, p.get("offline_recorded")?.asBoolean() ?: false, p.get("no_show_wait_seconds")?.asInt(), p.get("no_show_grace_min")?.asInt())
    }

    private fun vehicleState(j: JdbcOperations, eventType: String, eventId: UUID, p: JsonNode, occurred: java.time.Instant) {
        val (kind, ref) = when (eventType) {
            "fleet.vehicle_blocked" -> "BLOCKED" to uuid(p, "block_id"); "fleet.vehicle_block_lifted" -> "BLOCK_LIFTED" to uuid(p, "block_id")
            "fleet.release_issued" -> "RELEASE_ISSUED" to uuid(p, "release_id"); else -> "RELEASE_REVOKED" to uuid(p, "release_id")
        }
        j.update("INSERT INTO taktovia.fact_vehicle_state(tenant_id, event_id, vehicle_id, kind, ref_id, effective_at, safety_revision) VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?) ON CONFLICT (tenant_id, event_id) DO NOTHING",
            eventId, uuid(p, "vehicle_id"), kind, ref, Timestamp.from(occurred), p.get("safety_revision")?.asLong())
    }

    /** Buchungsfakt: aus Bestätigung, Storno (Eingangszeilen der Buchung) und den wirksamen Fahrerereignissen der Buchung; fehlende Teile bleiben leer, bis sie eintreffen. */
    private fun booking(j: JdbcOperations, bookingId: UUID) {
        val rs = rows(j, Source.BOOKING, bookingId)
        val confirmed = rs.firstOrNull { it.eventType == "booking.confirmed" }; val cancelled = rs.firstOrNull { it.eventType == "booking.cancelled" }
        data class Stop(val type: String, val at: Timestamp, val wait: Int?)
        val stops = j.query("SELECT event_type, effective_at, no_show_wait_seconds FROM taktovia.fact_stop_event WHERE booking_id = ? AND outcome = 'APPLIED' ORDER BY effective_at, event_id",
            { r, _ -> Stop(r.getString(1), r.getTimestamp(2), r.getObject(3) as Int?) }, bookingId)
        val boarded = stops.firstOrNull { it.type == "CHECK_IN" }?.at; val alighted = stops.firstOrNull { it.type == "CHECK_OUT" }?.at; val noShow = stops.firstOrNull { it.type == "NO_SHOW" }
        if (confirmed == null && cancelled == null && stops.isEmpty()) return
        val state = when { noShow != null -> "NO_SHOW"; alighted != null -> "COMPLETED"; cancelled != null -> "CANCELED"; boarded != null -> "BOARDED"; confirmed != null -> "CONFIRMED"; else -> "UNKNOWN" }
        val c = confirmed?.p
        j.update("INSERT INTO taktovia.fact_booking(tenant_id, booking_id, service_id, confirmed_at, pickup_earliest, pickup_latest, gross_minor, currency, cancelled_at, cancel_reason, cause_class, boarded_at, alighted_at, no_show_at, no_show_wait_seconds, state) " +
            "VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (tenant_id, booking_id) DO UPDATE SET service_id = EXCLUDED.service_id, confirmed_at = EXCLUDED.confirmed_at, " +
            "pickup_earliest = EXCLUDED.pickup_earliest, pickup_latest = EXCLUDED.pickup_latest, gross_minor = EXCLUDED.gross_minor, currency = EXCLUDED.currency, cancelled_at = EXCLUDED.cancelled_at, " +
            "cancel_reason = EXCLUDED.cancel_reason, cause_class = EXCLUDED.cause_class, boarded_at = EXCLUDED.boarded_at, alighted_at = EXCLUDED.alighted_at, no_show_at = EXCLUDED.no_show_at, " +
            "no_show_wait_seconds = EXCLUDED.no_show_wait_seconds, state = EXCLUDED.state",
            bookingId, c?.let { uuid(it, "service_id") }, confirmed?.let { Timestamp.from(it.occurredAt) }, c?.let { ts(it, "pickup_earliest") }, c?.let { ts(it, "pickup_latest") }, c?.get("gross_minor")?.asLong(), c?.let { text(it, "currency") },
            cancelled?.let { Timestamp.from(it.occurredAt) }, cancelled?.let { text(it.p, "reason_code") ?: text(it.p, "reason") }, cancelled?.let { text(it.p, "cause_class") }, boarded, alighted, noShow?.at, noShow?.wait, state)
    }

    private fun uuid(p: JsonNode, k: String): UUID? = p.get(k)?.takeIf { !it.isNull }?.asString()?.let(UUID::fromString)
    private fun text(p: JsonNode, k: String): String? = p.get(k)?.takeIf { !it.isNull }?.asString()
    private fun ts(p: JsonNode, k: String): Timestamp? = text(p, k)?.let { Timestamp.from(java.time.Instant.parse(it)) }

    // ---- Datenstand ---------------------------------------------------------------------------------------------------------------------

    /** Datenstand der Quelle aus der Menge der Eingangszeilen (Ereigniszeit, Zähler, Revisionslücken): deterministisch, nur `updated_at` hängt vom Zeitpunkt der Verarbeitung ab. */
    private fun updateWatermark(j: JdbcOperations, source: Source) {
        val s = j.queryForObject("SELECT max(occurred_at), count(*) FROM taktovia.analytics_event_inbox WHERE source = ?", { rs, _ -> rs.getTimestamp(1) to rs.getLong(2) }, source.name)!!
        val gaps = if (!source.contiguous) 0 else j.queryForObject(
            "SELECT count(*) FROM (SELECT aggregate_id FROM taktovia.analytics_event_inbox WHERE source = ? GROUP BY aggregate_id HAVING min(aggregate_revision) > 1 OR max(aggregate_revision) <> count(DISTINCT aggregate_revision)) g",
            Int::class.java, source.name)!!
        j.update("INSERT INTO taktovia.dataset_watermark(tenant_id, source, processed_until, completeness, gap_count, event_count, updated_at) VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?) " +
            "ON CONFLICT (tenant_id, source) DO UPDATE SET processed_until = EXCLUDED.processed_until, completeness = EXCLUDED.completeness, gap_count = EXCLUDED.gap_count, event_count = EXCLUDED.event_count, updated_at = EXCLUDED.updated_at",
            source.name, s.first, if (gaps > 0) "INCOMPLETE" else "COMPLETE", gaps, s.second, Timestamp.from(clock.instant()))
    }

    override fun rebuild(tenant: TenantId) {
        tx.run(tenant) { j ->
            lock(j, "rebuild")
            j.update("DELETE FROM taktovia.fact_attempt"); j.update("DELETE FROM taktovia.fact_offer"); j.update("DELETE FROM taktovia.fact_booking"); j.update("DELETE FROM taktovia.fact_stop_event")
            j.update("DELETE FROM taktovia.fact_vehicle_state"); j.update("DELETE FROM taktovia.dataset_watermark")
            data class In(val source: Source, val eventType: String, val aggregateId: UUID, val eventId: UUID, val payload: JsonNode)
            val all = j.query("SELECT source, event_type, aggregate_id, event_id, payload::text FROM taktovia.analytics_event_inbox ORDER BY occurred_at, aggregate_revision, event_id",
                { rs, _ -> In(Source.valueOf(rs.getString(1)), rs.getString(2), rs.getObject(3, UUID::class.java), rs.getObject(4, UUID::class.java), mapper.readTree(rs.getString(5))) })
            // Einzel-Fakten (je Ereignis) zuerst, dann die zusammengesetzten Buchungsfakten, die sie lesen
            all.filter { it.source == Source.DRIVER_EVENT }.forEach { stopEvent(j, it.eventId, it.payload) }
            all.filter { it.source == Source.VEHICLE_STATE }.forEach { project(j, it.source, it.eventType, it.aggregateId, it.eventId, it.payload) }
            all.filter { it.source == Source.ATTEMPT }.map { it.aggregateId }.distinct().forEach { attempt(j, it) }
            all.filter { it.source == Source.OFFER }.map { it.aggregateId }.distinct().forEach { offer(j, it) }
            all.mapNotNull { bookingOf(it.eventType, it.payload) }.distinct().forEach { booking(j, it) }
            all.map { it.source }.distinct().forEach { updateWatermark(j, it) }
        }
    }

    override fun watermarks(tenant: TenantId): List<Watermark> = tx.run(tenant, readOnly = true) { j ->
        j.query("SELECT source, processed_until, completeness, gap_count, event_count, updated_at FROM taktovia.dataset_watermark ORDER BY source",
            { rs, _ -> Watermark(FactSource.valueOf(rs.getString(1)), rs.getTimestamp(2)?.toInstant(), Completeness.valueOf(rs.getString(3)), rs.getInt(4), rs.getLong(5), rs.getTimestamp(6).toInstant()) })
    }

    override fun reconcileAttempts(tenant: TenantId): AttemptReconciliation = tx.run(tenant, readOnly = true) { j ->
        val missingFacts = mutableListOf<UUID>(); val missingSource = mutableListOf<UUID>(); val mismatched = mutableListOf<UUID>()
        j.query(RECONCILE, { rs, _ ->
            val id = rs.getObject("id", UUID::class.java)
            when (rs.getString("kind")) { "MISSING_IN_FACTS" -> missingFacts += id; "MISSING_IN_SOURCE" -> missingSource += id; else -> mismatched += id }
        })
        val counts = j.queryForObject(RECONCILE_COUNTS, { rs, _ -> rs.getLong(1) to rs.getLong(2) })!!
        AttemptReconciliation(counts.first, counts.second, missingFacts, missingSource, mismatched)
    }

    private companion object {
        val CODE = Regex("^[A-Z][A-Z0-9_]{1,39}$")

        /** Versuche der Quelle gegen die Fakten: fehlende Fakten, fehlende Quellen, abweichendes Ergebnis oder Primärgrund (Ergebnis `OFFERED` entspricht dem Faktenergebnis `RECORDED`). */
        const val RECONCILE = """
            SELECT coalesce(t.id, f.attempt_id) AS id,
                   CASE WHEN f.attempt_id IS NULL THEN 'MISSING_IN_FACTS' WHEN t.id IS NULL THEN 'MISSING_IN_SOURCE' ELSE 'MISMATCH' END AS kind
            FROM taktovia.travel_attempt t FULL OUTER JOIN taktovia.fact_attempt f ON f.tenant_id = t.tenant_id AND f.attempt_id = t.id
            WHERE f.attempt_id IS NULL OR t.id IS NULL
               OR (CASE t.outcome WHEN 'OFFERED' THEN 'RECORDED' ELSE t.outcome END) IS DISTINCT FROM f.outcome OR t.primary_rejection IS DISTINCT FROM f.primary_reason
            ORDER BY 1"""
        const val RECONCILE_COUNTS = "SELECT (SELECT count(*) FROM taktovia.travel_attempt), (SELECT count(*) FROM taktovia.fact_attempt)"
    }
}

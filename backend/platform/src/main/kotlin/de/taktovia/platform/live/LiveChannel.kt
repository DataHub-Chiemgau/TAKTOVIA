package de.taktovia.platform.live

import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

/**
 * Position im Live-Kanal (WP-M3-35, Detailvertrag m3-live-alarm.md): `(tx, seq)` der Tabelle `live_event`, Textform `<tx>.<seq>`. Der Cursor ist monoton in der Auslieferungsreihenfolge:
 * ausgeliefert werden nur Zeilen unterhalb des Snapshot-Horizonts, ein später sichtbar werdender Eintrag liegt daher nie hinter einem bereits ausgelieferten Cursor.
 */
data class LiveCursor(val tx: Long, val seq: Long) : Comparable<LiveCursor> {
    override fun compareTo(other: LiveCursor) = compareValuesBy(this, other, { it.tx }, { it.seq })
    override fun toString() = "$tx.$seq"

    companion object {
        val ZERO = LiveCursor(0, 0)
        private val FORMAT = Regex("^(0|[1-9][0-9]{0,17})\\.(0|[1-9][0-9]{0,17})$")

        /** `null` bei jeder Abweichung vom Format (der Aufrufer antwortet 422). */
        fun parse(text: String): LiveCursor? = FORMAT.matchEntire(text)?.let { LiveCursor(it.groupValues[1].toLong(), it.groupValues[2].toLong()) }
    }
}

/**
 * Nachricht des Kanals: **nur** Referenzen (Aggregat, Revision, Dienst, Schicht) und Positivliste skalarer Statusfelder; nie Rohpayloads, Namen, Adressen oder Koordinaten
 * (WP-M3-35 Kriterium 5). Die Projektion erzeugt der Datenbanktrigger `outbox_live`; dieser Typ ist die Hülle der Auslieferung.
 */
data class LiveEvent(
    val cursor: LiveCursor, val eventId: UUID, val type: String, val topic: String, val aggregateId: UUID, val aggregateRevision: Long,
    val serviceId: UUID?, val shiftId: UUID?, val status: Map<String, Any?>, val occurredAt: Instant,
) {
    fun envelope(): Map<String, Any?> = linkedMapOf(
        "event_id" to eventId.toString(), "cursor" to cursor.toString(), "type" to type, "topic" to topic, "aggregate_id" to aggregateId.toString(), "aggregate_revision" to aggregateRevision,
        "service_id" to serviceId?.toString(), "shift_id" to shiftId?.toString(), "status" to status, "occurred_at" to occurredAt.toString(),
    )
}

/**
 * Berechtigung eines Abonnements **für diesen Augenblick**: sie wird bei jedem Abruf neu bestimmt (Rechteentzug wirkt sofort). [services] sind die erlaubten Dienste (Ereignisse ohne
 * Dienstbezug gelten mandantenweit, solange [serviceScoped] gilt); [eventTypes] begrenzt die Ereignistypen (Fahrerkanal), [shiftIds] binden Ereignisse mit Schichtbezug an die eigenen Schichten.
 */
data class LiveGrant(
    val services: Set<UUID> = emptySet(), val serviceScoped: Boolean = true, val eventTypes: Set<String>? = null, val shiftIds: Set<UUID>? = null,
) {
    fun permits(e: LiveEvent): Boolean {
        if (eventTypes != null && e.type !in eventTypes) return false
        if (shiftIds != null && (e.shiftId == null || e.shiftId !in shiftIds)) return false
        if (serviceScoped && e.serviceId != null && e.serviceId !in services) return false
        return true
    }
}

/** Seite des Kanals: [nextCursor] rückt über alle gelesenen Zeilen vor, auch über solche, die der Berechtigte nicht sehen darf (sie werden nie ausgeliefert, nur übersprungen). */
data class LivePage(val events: List<LiveEvent>, val nextCursor: LiveCursor, val hasMore: Boolean)

/** Lesesicht auf `live_event` (Mandant per RLS und ausdrücklich begrenzt). */
class LiveFeed(private val tx: TenantTransactions) {
    private val json = JsonMapper.builder().build()

    /** Aktueller Horizont: der Stand, ab dem ein neuer Abonnent ohne Cursor beginnt (nichts aus der Vergangenheit; Zustand holt der Client über seine Listen). */
    fun horizon(tenant: TenantId): LiveCursor = tx.run(tenant, readOnly = true) { j ->
        j.query("SELECT tx, seq FROM taktovia.live_event WHERE tenant_id = taktovia.current_tenant() AND tx < (pg_snapshot_xmin(pg_current_snapshot())::text)::bigint ORDER BY tx DESC, seq DESC LIMIT 1",
            { rs, _ -> LiveCursor(rs.getLong(1), rs.getLong(2)) }).firstOrNull() ?: LiveCursor((xmin(j) - 1).coerceAtLeast(0), 0)
    }

    private fun xmin(j: org.springframework.jdbc.core.JdbcOperations): Long = j.queryForObject("SELECT (pg_snapshot_xmin(pg_current_snapshot())::text)::bigint", Long::class.java)!!

    fun read(tenant: TenantId, after: LiveCursor, grant: LiveGrant, limit: Int): LivePage {
        require(limit in 1..MAX_PAGE) { "limit außerhalb 1..$MAX_PAGE" }
        return tx.run(tenant, readOnly = true) { j ->
            var last = after
            val rows = j.query(
                "SELECT tx, seq, event_id, event_type, topic, aggregate_id, aggregate_revision, service_id, shift_id, status::text, occurred_at FROM taktovia.live_event " +
                    "WHERE tenant_id = taktovia.current_tenant() AND (tx, seq) > (?, ?) AND tx < (pg_snapshot_xmin(pg_current_snapshot())::text)::bigint ORDER BY tx, seq LIMIT ?",
                { rs, _ ->
                    LiveEvent(LiveCursor(rs.getLong(1), rs.getLong(2)), rs.getObject(3, UUID::class.java), rs.getString(4), rs.getString(5), rs.getObject(6, UUID::class.java), rs.getLong(7),
                        rs.getObject(8, UUID::class.java), rs.getObject(9, UUID::class.java), status(rs.getString(10)), rs.getTimestamp(11).toInstant())
                }, after.tx, after.seq, limit + 1)
            val page = rows.take(limit)
            page.forEach { last = it.cursor }
            LivePage(page.filter { grant.permits(it) }, last, rows.size > limit)
        }
    }

    /** Bereinigung des Kanalpuffers (Aufbewahrung in Stunden); liefert die Zahl der gelöschten Zeilen. */
    fun prune(tenant: TenantId, olderThan: Instant): Int = tx.run(tenant) { j ->
        j.update("DELETE FROM taktovia.live_event WHERE tenant_id = taktovia.current_tenant() AND created_at < ?", java.sql.Timestamp.from(olderThan))
    }

    @Suppress("UNCHECKED_CAST")
    private fun status(text: String): Map<String, Any?> = json.readValue(text, Map::class.java) as Map<String, Any?>

    companion object { const val MAX_PAGE = 200 }
}

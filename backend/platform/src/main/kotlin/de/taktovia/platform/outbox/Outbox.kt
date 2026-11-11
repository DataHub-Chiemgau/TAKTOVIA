package de.taktovia.platform.outbox

import de.taktovia.platform.tenant.TenantId
import org.springframework.jdbc.core.JdbcOperations
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** Zu schreibendes Ereignis eines Moduls. Eigentümer ist immer der Mandant der laufenden Transaktion. */
data class OutboxEvent(
    val aggregateId: UUID,
    val aggregateRevision: Long,
    val eventType: String,
    val payload: Map<String, Any?>,
    val occurredAt: Instant,
    val schemaVersion: String = "1.0",
    val recipient: TenantId? = null,
    val correlationId: UUID? = null,
    val eventId: UUID = UUID.randomUUID(),
)

/**
 * Outbox-API für Module (WP-M1-14): schreibt Ereignis in derselben Transaktion wie Aggregat und Revision (Leitlinie 3). Rollback der Fachtransaktion
 * hinterlässt keinen Eintrag; je (Aggregat, Revision, Ereignistyp) gibt es höchstens einen (UNIQUE, ein zweiter Versuch wirft
 * `DuplicateKeyException`). Die Zustellung übernimmt [OutboxRelay], nie das Modul.
 */
class Outbox {
    private val mapper = JsonMapper.builder().build()

    fun append(jdbc: JdbcOperations, e: OutboxEvent): UUID {
        require(Regex("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+").matches(e.eventType)) { "ungültiger Ereignistyp" }
        require(e.aggregateRevision >= 1) { "aggregateRevision >= 1" }
        jdbc.update(
            "INSERT INTO taktovia.outbox(tenant_id,event_id,aggregate_id,aggregate_revision,event_type,schema_version,occurred_at,payload,recipient_tenant_id,correlation_id) " +
                "VALUES (taktovia.current_tenant(),?,?,?,?,?,?,?::jsonb,COALESCE(?, taktovia.current_tenant()),?)",
            e.eventId, e.aggregateId, e.aggregateRevision, e.eventType, e.schemaVersion, Timestamp.from(e.occurredAt), mapper.writeValueAsString(e.payload),
            e.recipient?.value, e.correlationId,
        )
        return e.eventId
    }
}

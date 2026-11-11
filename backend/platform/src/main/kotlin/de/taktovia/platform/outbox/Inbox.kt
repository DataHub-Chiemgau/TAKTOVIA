package de.taktovia.platform.outbox

import de.taktovia.platform.audit.ActorRef
import de.taktovia.platform.audit.AuditActions
import de.taktovia.platform.audit.ObjectRef
import de.taktovia.platform.audit.SecurityEvent
import de.taktovia.platform.audit.SecurityEventPort
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcOperations
import tools.jackson.databind.JsonNode
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID

enum class InboxOutcome { PROCESSED, DUPLICATE, STALE, GAP_SNAPSHOT, REJECTED_NO_RECIPIENT, REJECTED_RECIPIENT, REJECTED_PAYLOAD_MISMATCH, REJECTED_SCHEMA }

/** Stand eines Aggregats beim Eigentümer (autorisierter Snapshotabruf, Kap. 01 §1.5). */
data class AggregateSnapshot(val aggregateId: UUID, val revision: Long, val state: JsonNode)

/** Autorisierter Snapshotabruf beim Eigentümer. Wirft, wenn der Snapshot (noch) nicht verfügbar ist; die Nachricht wird dann erneut zugestellt. */
fun interface SnapshotFetcher { fun fetch(recipient: TenantId, owner: UUID, aggregateId: UUID): AggregateSnapshot }

interface InboxHandler {
    /** Fachliche Wirkung; läuft in derselben Transaktion wie der Inbox-Eintrag und im Kontext des Empfängermandanten (`jdbc`). */
    fun handle(jdbc: JdbcOperations, event: EventEnvelope)
    /** Setzt den Aggregatzustand auf den abgerufenen Snapshot (Lückenschluss). Nie für ältere Revisionen als der bekannte Stand. */
    fun applySnapshot(jdbc: JdbcOperations, snapshot: AggregateSnapshot)
}

class SnapshotNotYetAvailableException(message: String) : RuntimeException(message)

/**
 * Inbox-Baustein (WP-M1-14): Dedupe `(consumer, event_id)`, Payloadhash, Aggregatrevisionsprüfung und Verarbeitung ausschließlich im Kontext des
 * adressierten Empfängermandanten. Wirkung und Inbox-Eintrag teilen eine Transaktion, parallele Verarbeitung desselben Aggregats wird per
 * transaktionslokalem Advisory-Lock serialisiert, damit eine Wirkung genau einmal eintritt.
 * Revisionsregel: erste bekannte Revision wird angenommen; `last + 1` wird verarbeitet; `<= last` überschreibt nie (STALE) und löst Nachlesen aus;
 * `> last + 1` ist eine Lücke und wird per Snapshot geschlossen. Gleiche `event_id` mit anderem Payloadhash wird nicht verarbeitet und als
 * Sicherheitsereignis gemeldet. (Die Inbox-Zeilen dürfen nicht vor Ablauf der Aggregatlebensdauer aufgeräumt werden: sie tragen die letzte bekannte Revision.)
 */
class InboxProcessor(
    private val tx: TenantTransactions,
    private val security: SecurityEventPort,
    private val snapshots: SnapshotFetcher,
    private val policy: RecipientPolicy = RecipientPolicy.OWNER_ONLY,
    private val clock: Clock = Clock.systemUTC(),
    private val supportedMajor: Int = 1,
) {
    private val log = LoggerFactory.getLogger("taktovia.inbox")

    fun process(consumer: String, json: String, handler: InboxHandler): InboxOutcome {
        require(Regex("[a-z][a-z0-9_.-]{1,62}").matches(consumer)) { "ungültiger Consumername" }
        val e = try { EventEnvelope.fromJson(json) } catch (ex: IllegalArgumentException) { log.warn("Ereignis verworfen: Schema ({})", ex.message); return InboxOutcome.REJECTED_SCHEMA }
        if (e.major != supportedMajor) return InboxOutcome.REJECTED_SCHEMA
        val recipient = e.recipientTenantId
        if (recipient == null) { reportOnce(TenantId(e.ownerTenantId), AuditActions.SECURITY_RECIPIENT_MISMATCH, e, "no_recipient"); return InboxOutcome.REJECTED_NO_RECIPIENT }
        if (!policy.allowed(e.ownerTenantId, recipient)) { reportOnce(TenantId(e.ownerTenantId), AuditActions.SECURITY_RECIPIENT_MISMATCH, e, "recipient_not_allowed"); return InboxOutcome.REJECTED_RECIPIENT }
        val outcome = tx.run(TenantId(recipient)) { j -> processInTenant(j, TenantId(recipient), consumer, e, handler) }
        if (outcome == InboxOutcome.REJECTED_PAYLOAD_MISMATCH) reportOnce(TenantId(recipient), AuditActions.SECURITY_PAYLOAD_HASH_MISMATCH, e, "payload_hash_mismatch")
        return outcome
    }

    private fun processInTenant(j: JdbcOperations, recipient: TenantId, consumer: String, e: EventEnvelope, handler: InboxHandler): InboxOutcome {
        j.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", { _, _ -> 0 }, "$recipient|$consumer|${e.aggregateId}")
        val computed = EventEnvelope.sha256(e.payload)
        if (e.payloadSha256.isNotEmpty() && e.payloadSha256 != computed) return InboxOutcome.REJECTED_PAYLOAD_MISMATCH
        j.query("SELECT payload_sha256 FROM taktovia.inbox WHERE consumer = ? AND event_id = ?", { rs, _ -> rs.getString(1) }, consumer, e.eventId).firstOrNull()?.let {
            return if (it == computed) InboxOutcome.DUPLICATE else InboxOutcome.REJECTED_PAYLOAD_MISMATCH
        }
        val last = j.queryForObject("SELECT max(aggregate_revision) FROM taktovia.inbox WHERE consumer = ? AND aggregate_id = ? AND outcome <> 'STALE'",
            Long::class.javaObjectType, consumer, e.aggregateId)
        when {
            last == null || e.aggregateRevision == last + 1 -> {
                handler.handle(j, e); insert(j, consumer, e, computed, e.aggregateRevision, "PROCESSED"); return InboxOutcome.PROCESSED
            }
            e.aggregateRevision <= last -> {   // veraltet: nie den Zustand zurücksetzen; Nachlesen klärt, ob zwischenzeitlich mehr bekannt ist
                val snap = snapshots.fetch(recipient, e.ownerTenantId, e.aggregateId)
                if (snap.revision > last) { handler.applySnapshot(j, snap); insert(j, consumer, e, computed, snap.revision, "GAP_SNAPSHOT"); return InboxOutcome.GAP_SNAPSHOT }
                insert(j, consumer, e, computed, e.aggregateRevision, "STALE"); return InboxOutcome.STALE
            }
            else -> {   // Lücke
                val snap = snapshots.fetch(recipient, e.ownerTenantId, e.aggregateId)
                if (snap.revision < e.aggregateRevision) throw SnapshotNotYetAvailableException("Snapshot älter als Ereignis")
                handler.applySnapshot(j, snap); insert(j, consumer, e, computed, snap.revision, "GAP_SNAPSHOT"); return InboxOutcome.GAP_SNAPSHOT
            }
        }
    }

    private fun insert(j: JdbcOperations, consumer: String, e: EventEnvelope, sha: String, revision: Long, outcome: String) {
        j.update("INSERT INTO taktovia.inbox(tenant_id,consumer,event_id,payload_sha256,received_at,aggregate_id,aggregate_revision,event_type,occurred_at,outcome) " +
            "VALUES (taktovia.current_tenant(),?,?,?,?,?,?,?,?,?)", consumer, e.eventId, sha, Timestamp.from(clock.instant()), e.aggregateId, revision, e.eventType,
            Timestamp.from(e.occurredAt), outcome)
    }

    /** Sicherheitsereignis in eigener, bereits von der Verarbeitung getrennter Transaktion; Fehler beim Melden verhindern die Ablehnung nicht. */
    private fun reportOnce(tenant: TenantId, action: String, e: EventEnvelope, reason: String) {
        val ev = SecurityEvent(ActorRef.service("inbox"), action, ObjectRef("event", e.eventId), e.correlationId ?: UUID.randomUUID(),
            mapOf("event_type" to e.eventType, "reason_code" to reason, "aggregate_revision" to e.aggregateRevision))
        try { tx.run(tenant) { j -> security.report(j, ev) } } catch (ex: Exception) {
            log.warn("Sicherheitsereignis ohne Mandanten-Audit ({}): {}", action, ex.javaClass.simpleName); security.reportWithoutTenant(ev)
        }
    }
}

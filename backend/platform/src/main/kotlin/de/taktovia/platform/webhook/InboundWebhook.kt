package de.taktovia.platform.webhook

import de.taktovia.platform.audit.ActorRef
import de.taktovia.platform.audit.AuditActions
import de.taktovia.platform.audit.ObjectRef
import de.taktovia.platform.audit.SecurityEvent
import de.taktovia.platform.audit.SecurityEventPort
import de.taktovia.platform.outbox.EventEnvelope
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcOperations
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Ein Schlüssel einer Eingangsquelle. Mandant und Quelle folgen **aus dem Schlüssel**, nie aus dem Payload (Rotation: alter Schlüssel mit [validUntil]). */
class InboundKey(val tenant: TenantId, val source: String, val keyId: String, secret: ByteArray, val validUntil: Instant? = null) {
    private val secret: ByteArray = secret.copyOf()
    fun secret(): ByteArray = secret.copyOf()
    override fun toString() = "InboundKey(source=$source, kid=$keyId)"   // nie der Wert
}

/** Schlüsselquelle des Eingangs (Secret-Verwaltung der Umgebung; hier austauschbar). */
fun interface InboundKeys { fun lookup(keyId: String): InboundKey? }

class StaticInboundKeys(private val keys: List<InboundKey>) : InboundKeys {
    override fun lookup(keyId: String) = keys.firstOrNull { it.keyId == keyId }
}

/** Eingangsereignis nach erfolgreicher Prüfung. [data] ist der unveränderte Nutzdatenteil des Absenders. */
data class InboundEvent(val eventId: UUID, val eventType: String, val occurredAt: Instant, val aggregateId: UUID, val aggregateRevision: Long, val data: JsonNode)

/** Fachliche Wirkung des Eingangs; läuft in **derselben** Transaktion wie der Inbox-Eintrag und im Mandanten des Schlüssels. */
fun interface InboundHandler { fun handle(jdbc: JdbcOperations, tenant: TenantId, source: String, event: InboundEvent) }

enum class Rejection { TOO_LARGE, SIGNATURE_INVALID, TIMESTAMP_OUT_OF_WINDOW, MALFORMED, PAYLOAD_CONFLICT }

sealed interface InboundResult {
    /** 202: Inbox-Commit erfolgt, Wirkung ausgeführt. */
    data class Accepted(val eventId: UUID) : InboundResult
    /** 200 `ALREADY_RECEIVED`: gleiche ID, gleicher Payload. */
    data class AlreadyReceived(val eventId: UUID) : InboundResult
    /** Abgewiesen ohne Inbox-Eintrag (bei [Rejection.PAYLOAD_CONFLICT]: Quarantäne, kein Eintrag in der Inbox). */
    data class Rejected(val reason: Rejection) : InboundResult
}

data class InboundConfig(val maxBodyBytes: Int = 256 * 1024, val tolerance: Duration = Duration.ofSeconds(300))

/**
 * Eingangsfilter für signierte Ereignisse (WP-M1-16, Kap. 05 §5.6): (1) maximale Körpergröße vor jeder Berechnung, (2) Signaturformat, (3) Zeitfenster ±300 s,
 * (4) Schlüssel per `kid` (alter und neuer Schlüssel nebeneinander, der alte bis [InboundKey.validUntil]) und Quellenbindung, (5) konstantzeitliche
 * HMAC-Prüfung über den **exakten Rohbody**, (6) Inhaltsprüfung, (7) Inbox-Commit mit Wirkung in einer Transaktion. Quittung: 202 nach Commit, 200
 * `ALREADY_RECEIVED` bei Wiederholung, bei gleicher ID mit anderem Payload 409, Sicherheitsereignis `security.event.payload_hash_mismatch` und Quarantäne
 * (nur Hashes). Unbekannte Schlüssel und falsche Signaturen sind nicht unterscheidbar ([Rejection.SIGNATURE_INVALID]). Ohne gültige Signatur entsteht nie ein Eintrag.
 */
class InboundWebhookReceiver(
    private val tx: TenantTransactions,
    private val keys: InboundKeys,
    private val security: SecurityEventPort,
    private val handler: InboundHandler,
    private val config: InboundConfig = InboundConfig(),
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger("taktovia.webhook.inbound")
    private val mapper = JsonMapper.builder().build()

    fun receive(source: String, signatureHeader: String?, rawBody: ByteArray): InboundResult {
        if (rawBody.size > config.maxBodyBytes) return InboundResult.Rejected(Rejection.TOO_LARGE)
        val sig = WebhookSignature.parse(signatureHeader) ?: return InboundResult.Rejected(Rejection.SIGNATURE_INVALID)
        val now = clock.instant()
        if (Math.abs(now.epochSecond - sig.timestamp.epochSecond) > config.tolerance.seconds) return InboundResult.Rejected(Rejection.TIMESTAMP_OUT_OF_WINDOW)   // Sekundengenauigkeit wie der Zeitstempel selbst
        val key = keys.lookup(sig.keyId)?.takeIf { it.source == source && (it.validUntil == null || !it.validUntil.isBefore(now)) }
            ?: return InboundResult.Rejected(Rejection.SIGNATURE_INVALID)
        if (!WebhookSignature.matches(WebhookSignature.hmacHex(key.secret(), sig.timestamp, rawBody), sig.signature)) return InboundResult.Rejected(Rejection.SIGNATURE_INVALID)
        val event = parse(rawBody) ?: return InboundResult.Rejected(Rejection.MALFORMED)
        val sha = EventEnvelope.sha256(mapper.readTree(rawBody))
        val consumer = "webhook.$source"
        return tx.run(key.tenant) { j ->
            val inserted = j.update(
                "INSERT INTO taktovia.inbox(tenant_id,consumer,event_id,payload_sha256,received_at,aggregate_id,aggregate_revision,event_type,occurred_at,outcome) " +
                    "VALUES (taktovia.current_tenant(),?,?,?,?,?,?,?,?,'PROCESSED') ON CONFLICT (tenant_id, consumer, event_id) DO NOTHING",
                consumer, event.eventId, sha, Timestamp.from(now), event.aggregateId, event.aggregateRevision, event.eventType, Timestamp.from(event.occurredAt))
            if (inserted == 1) {
                handler.handle(j, key.tenant, source, event)
                InboundResult.Accepted(event.eventId)
            } else {
                val original = j.queryForObject("SELECT payload_sha256 FROM taktovia.inbox WHERE consumer = ? AND event_id = ?", String::class.java, consumer, event.eventId)!!.trim()
                if (original == sha) InboundResult.AlreadyReceived(event.eventId)
                else {
                    j.update("INSERT INTO taktovia.webhook_quarantine(tenant_id,id,source,event_id,payload_sha256,original_sha256,reason,received_at) VALUES (taktovia.current_tenant(),?,?,?,?,?,'payload_hash_mismatch',?)",
                        UUID.randomUUID(), source, event.eventId, sha, original, Timestamp.from(now))
                    security.report(j, SecurityEvent(ActorRef.system("webhook-inbound"), AuditActions.SECURITY_PAYLOAD_HASH_MISMATCH, ObjectRef("event", event.eventId), UUID.randomUUID(),
                        mapOf("event_type" to event.eventType, "reason_code" to "payload_hash_mismatch", "source" to source)))
                    log.warn("Eingangsereignis mit abweichendem Payload quarantäniert source={}", source)
                    InboundResult.Rejected(Rejection.PAYLOAD_CONFLICT)
                }
            }
        }
    }

    /** Pflichtfelder `event_id` (UUID), `event_type`, `occurred_at`; optional `aggregate_id`, `aggregate_revision` (sonst Ereignis-ID und 1) und `data` (Objekt). */
    private fun parse(raw: ByteArray): InboundEvent? = try {
        val o = mapper.readTree(raw)
        if (!o.isObject) null else {
            fun text(f: String) = o.get(f)?.takeIf { it.isString }?.asString()
            val id = UUID.fromString(text("event_id") ?: return null)
            val type = text("event_type")?.takeIf { Regex("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+").matches(it) && it.length <= 128 } ?: return null
            val at = Instant.parse(text("occurred_at") ?: return null)
            val agg = text("aggregate_id")?.let { UUID.fromString(it) } ?: id
            val rev = o.get("aggregate_revision")?.let { if (it.canConvertToLong() && it.asLong() >= 1) it.asLong() else return null } ?: 1L
            val data = o.get("data")?.also { if (!it.isObject) return null } ?: mapper.createObjectNode()
            InboundEvent(id, type, at, agg, rev, data)
        }
    } catch (_: Exception) { null }
}

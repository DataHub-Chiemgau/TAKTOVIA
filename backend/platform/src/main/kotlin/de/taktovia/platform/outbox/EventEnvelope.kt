package de.taktovia.platform.outbox

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * Ereignishülle (WP-M1-14, Kap. 01 §1.5, Kap. 02 §4.3, Kap. 05 §5.6). Schema: api/events/platform.eventenvelope.v1.schema.json.
 *
 * Getrennt geführt: Ereigniszeit [occurredAt] (fachlich), Aufzeichnungszeit [recordedAt] (Eigentümer), Empfangszeit (Inbox `received_at`)
 * und Aggregatversion [aggregateRevision]. Eigentümer und Empfänger sind explizit; ohne Empfänger wird nicht zugestellt.
 * Versionierungsregel: `schema_version` = `<major>.<minor>`. Minor = abwärtskompatible Ergänzung (Consumer derselben Major lesen weiter),
 * eine neue Major ist ein neuer Ereignistyp-Schema-Datei `.v<N>` und wird von Consumern mit anderer Major nicht verarbeitet.
 */
data class EventEnvelope(
    val eventId: UUID,
    val eventType: String,
    val schemaVersion: String,
    val aggregateId: UUID,
    val aggregateRevision: Long,
    val occurredAt: Instant,
    val recordedAt: Instant,
    val ownerTenantId: UUID,
    val recipientTenantId: UUID?,
    val correlationId: UUID?,
    val payload: JsonNode,
    val payloadSha256: String = sha256(payload),
) {
    fun toJson(): String = mapper.writeValueAsString(mapper.createObjectNode().also { o ->
        o.put("event_id", eventId.toString()); o.put("event_type", eventType); o.put("schema_version", schemaVersion)
        o.put("aggregate_id", aggregateId.toString()); o.put("aggregate_revision", aggregateRevision)
        o.put("occurred_at", occurredAt.toString()); o.put("recorded_at", recordedAt.toString())
        o.put("owner_tenant_id", ownerTenantId.toString())
        if (recipientTenantId != null) o.put("recipient_tenant_id", recipientTenantId.toString())
        if (correlationId != null) o.put("correlation_id", correlationId.toString())
        o.set("payload", payload); o.put("payload_sha256", payloadSha256)
    })

    val major: Int get() = schemaVersion.substringBefore('.').toInt()

    companion object {
        private val mapper: JsonMapper = JsonMapper.builder().build()

        /** Wirft [IllegalArgumentException] bei fehlenden Pflichtfeldern oder falschen Typen. Der Empfänger ist optional (Prüfung im Inbox-Baustein). */
        fun fromJson(json: String): EventEnvelope {
            val o = try { mapper.readTree(json) } catch (e: Exception) { throw IllegalArgumentException("Ereignishülle ist kein gültiges JSON") }
            require(o.isObject) { "Ereignishülle ist kein Objekt" }
            fun text(f: String) = o.get(f)?.takeIf { it.isString }?.asString() ?: throw IllegalArgumentException("Feld $f fehlt")
            fun uuid(f: String) = try { UUID.fromString(text(f)) } catch (e: IllegalArgumentException) { throw IllegalArgumentException("Feld $f ist keine UUID") }
            val payload = o.get("payload")?.takeIf { it.isObject } ?: throw IllegalArgumentException("Feld payload fehlt")
            val rev = o.get("aggregate_revision")?.takeIf { it.canConvertToLong() }?.asLong() ?: throw IllegalArgumentException("Feld aggregate_revision fehlt")
            require(rev >= 1) { "aggregate_revision muss >= 1 sein" }
            val sv = text("schema_version"); require(Regex("[0-9]+\\.[0-9]+").matches(sv)) { "schema_version ungültig" }
            return EventEnvelope(
                uuid("event_id"), text("event_type"), sv, uuid("aggregate_id"), rev,
                try { Instant.parse(text("occurred_at")) } catch (e: Exception) { throw IllegalArgumentException("occurred_at ungültig") },
                try { Instant.parse(text("recorded_at")) } catch (e: Exception) { throw IllegalArgumentException("recorded_at ungültig") },
                uuid("owner_tenant_id"), o.get("recipient_tenant_id")?.let { uuid("recipient_tenant_id") }, o.get("correlation_id")?.let { uuid("correlation_id") },
                payload, o.get("payload_sha256")?.takeIf { it.isString }?.asString() ?: "",
            )
        }

        fun parsePayload(json: String): JsonNode = mapper.readTree(json)

        /** Kanonische Darstellung: Schlüssel sortiert, kompakt. Grundlage des Payloadhashs (unabhängig von Formatierung und jsonb-Normalisierung). */
        fun canonical(n: JsonNode): String = StringBuilder().also { canonical(n, it) }.toString()

        private fun canonical(n: JsonNode, sb: StringBuilder) {
            when {
                n.isObject -> { sb.append('{'); n.propertyNames().sorted().forEachIndexed { i, k -> if (i > 0) sb.append(','); sb.append(mapper.writeValueAsString(k)).append(':'); canonical(n.get(k), sb) }; sb.append('}') }
                n.isArray -> { sb.append('['); n.forEachIndexed { i, e -> if (i > 0) sb.append(','); canonical(e, sb) }; sb.append(']') }
                n.isNumber -> sb.append(n.decimalValue().stripTrailingZeros().toPlainString())
                else -> sb.append(mapper.writeValueAsString(n))
            }
        }

        fun sha256(payload: JsonNode): String =
            MessageDigest.getInstance("SHA-256").digest(canonical(payload).toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

/** Zulässige Empfängeradressierung: nur Eigentümer selbst, bis Kooperationsfreigaben (`cooperation_grant`, M6) weitere Empfänger erlauben. */
fun interface RecipientPolicy {
    fun allowed(owner: UUID, recipient: UUID): Boolean

    companion object { val OWNER_ONLY = RecipientPolicy { owner, recipient -> owner == recipient } }
}

/**
 * Adressierungsprüfung der Ereignishülle (WP-M1-11, LH-6.1-SYS-001): jedes Ereignis trägt den **Eigentümer** (`owner_tenant_id`) und eine **explizite Empfängeradressierung**;
 * ohne Empfänger oder mit einem Empfänger, den die [RecipientPolicy] für den Eigentümer nicht erlaubt, wird nicht zugestellt. [deliverableTo] ist die Zustellprüfung je Empfänger:
 * ein Ereignis des Mandanten A erreicht nie einen Empfänger des Mandanten B, solange keine Kooperationsfreigabe (M6) das erlaubt.
 */
object EnvelopeAddressing {
    /** Wirft [IllegalArgumentException] (ohne Inhalte), wenn die Hülle nicht zustellbar adressiert ist. */
    fun requireAddressed(e: EventEnvelope, policy: RecipientPolicy = RecipientPolicy.OWNER_ONLY): UUID {
        val recipient = e.recipientTenantId ?: throw IllegalArgumentException("Ereignishülle ohne Empfängeradressierung")
        require(policy.allowed(e.ownerTenantId, recipient)) { "Empfänger für diesen Eigentümer nicht zulässig" }
        return recipient
    }

    /** `true`, wenn [receiver] der adressierte und zulässige Empfänger ist. */
    fun deliverableTo(e: EventEnvelope, receiver: UUID, policy: RecipientPolicy = RecipientPolicy.OWNER_ONLY): Boolean =
        e.recipientTenantId == receiver && policy.allowed(e.ownerTenantId, receiver)
}

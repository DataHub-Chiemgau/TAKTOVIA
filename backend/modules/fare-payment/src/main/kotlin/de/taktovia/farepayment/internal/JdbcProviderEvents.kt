package de.taktovia.farepayment.internal

import de.taktovia.farepayment.api.PROVIDER_EVENT_RECEIVED
import de.taktovia.farepayment.api.ProviderEventConfig
import de.taktovia.farepayment.api.ProviderEventKind
import de.taktovia.farepayment.api.ProviderEventKeys
import de.taktovia.farepayment.api.ProviderEventRejection
import de.taktovia.farepayment.api.ProviderEventResult
import de.taktovia.farepayment.api.ProviderEvents
import de.taktovia.farepayment.api.providerEventObjectId
import de.taktovia.platform.audit.ActorRef
import de.taktovia.platform.audit.AuditActions
import de.taktovia.platform.audit.ObjectRef
import de.taktovia.platform.audit.SecurityEvent
import de.taktovia.platform.audit.SecurityEventPort
import de.taktovia.platform.outbox.EventEnvelope
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.outbox.OutboxEvent
import de.taktovia.platform.tenant.TenantTransactions
import de.taktovia.platform.webhook.WebhookSignature
import org.slf4j.LoggerFactory
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID

internal class JdbcProviderEvents(
    private val tx: TenantTransactions, private val keys: ProviderEventKeys, private val security: SecurityEventPort, private val outbox: Outbox,
    private val config: ProviderEventConfig, private val clock: Clock, private val payments: de.taktovia.farepayment.api.Payments? = null,
) : ProviderEvents {
    private val log = LoggerFactory.getLogger("taktovia.payment.provider-events")
    private val mapper = JsonMapper.builder().build()

    /** Signaturkopf `t=<Unix-Sekunden>,v1=<64 hex>` (Kap. 05 §5.7, eigene PSP-Signatur, nicht der Partner-HMAC). */
    private val signatureFormat = Regex("^t=(\\d{1,12}),v1=([0-9a-f]{64})$")

    private class Parsed(val eventId: String, val type: String, val sequence: Long?, val createdAt: Instant, val ref: String, val state: String,
                         val authorized: Long?, val captured: Long?, val refunded: Long?, val currency: String?, val dispute: String?)

    override fun receive(provider: String, signatureHeader: String?, timestampHeader: String?, rawBody: ByteArray): ProviderEventResult {
        if (rawBody.size > config.maxBodyBytes) return reject(ProviderEventRejection.TOO_LARGE)
        val m = signatureHeader?.trim()?.let { signatureFormat.matchEntire(it) } ?: return reject(ProviderEventRejection.SIGNATURE_INVALID)
        val t = m.groupValues[1].toLong()
        // der Zeitstempel steht im Header und in der Signatur; weichen sie voneinander ab, gilt die Nachricht als manipuliert
        if (timestampHeader?.trim() != t.toString()) return reject(ProviderEventRejection.SIGNATURE_INVALID)
        val now = clock.instant()
        if (Math.abs(now.epochSecond - t) > config.tolerance.seconds) return reject(ProviderEventRejection.TIMESTAMP_OUT_OF_WINDOW)
        val ts = Instant.ofEpochSecond(t)
        // gegen alle Schlüssel prüfen, ohne beim ersten Treffer abzubrechen (konstante Laufzeit je Schlüsselmenge); unbekannter Provider und falsche Signatur sind nicht unterscheidbar
        var matched: de.taktovia.farepayment.api.ProviderEventKey? = null
        for (k in keys.candidates(provider)) {
            if (k.provider != provider || (k.validUntil != null && k.validUntil.isBefore(now))) continue
            if (WebhookSignature.matches(WebhookSignature.hmacHex(k.secret(), ts, rawBody), m.groupValues[2]) && matched == null) matched = k
        }
        val key = matched ?: return reject(ProviderEventRejection.SIGNATURE_INVALID)
        val root = try { mapper.readTree(rawBody) } catch (_: Exception) { return reject(ProviderEventRejection.MALFORMED) }
        val e = parse(provider, root) ?: return reject(ProviderEventRejection.MALFORMED)
        val sha = EventEnvelope.sha256(root)
        val kind = ProviderEventKind.of(e.type)
        return tx.run(key.tenant) { j ->
            val id = UUID.randomUUID()
            val inserted = j.update(
                "INSERT INTO taktovia.payment_event(tenant_id,id,provider,event_id,payload_sha256,event_type,internal_kind,provider_sequence,provider_created_at,provider_ref,reported_state," +
                    "authorized_minor,captured_minor,refunded_minor,currency,dispute_state,received_at) " +
                    "VALUES (taktovia.current_tenant(),?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT (tenant_id, provider, event_id) DO NOTHING",
                id, provider, e.eventId, sha, e.type, kind.name, e.sequence, Timestamp.from(e.createdAt), e.ref, e.state, e.authorized, e.captured, e.refunded, e.currency, e.dispute, Timestamp.from(now))
            if (inserted == 1) {
                // die fachliche Wirkung (internes Ereignis für den Zahlungsautomaten, WP-M2-30) in derselben Transaktion: genau einmal je Ereignis-ID
                if (kind != ProviderEventKind.UNMAPPED) outbox.append(j, OutboxEvent(id, 1, PROVIDER_EVENT_RECEIVED, payload(provider, e, kind), now))
                // Zahlungsautomat: ein Abgleich der Zahlung (Statusabfrage beim PSP); der Payload selbst löst nie einen Übergang aus (Leitlinie 6)
                if (kind != ProviderEventKind.UNMAPPED) payments?.reactToProviderEventIn(j, provider, e.ref)
                log.info("PSP-Ereignis gespeichert provider={} kind={}", provider, kind)
                ProviderEventResult.Accepted(e.eventId)
            } else {
                val original = j.queryForObject("SELECT payload_sha256 FROM taktovia.payment_event WHERE provider = ? AND event_id = ?", String::class.java, provider, e.eventId)!!.trim()
                if (original == sha) ProviderEventResult.AlreadyReceived(e.eventId)
                else {
                    security.report(j, SecurityEvent(ActorRef.system("psp-events"), AuditActions.SECURITY_PAYLOAD_HASH_MISMATCH, ObjectRef("event", providerEventObjectId(provider, e.eventId)),
                        UUID.randomUUID(), mapOf("provider" to provider, "event_type" to e.type, "reason_code" to "payload_hash_mismatch")))
                    log.warn("PSP-Ereignis mit abweichendem Payload abgewiesen provider={}", provider)
                    ProviderEventResult.Rejected(ProviderEventRejection.PAYLOAD_CONFLICT)
                }
            }
        }
    }

    private fun reject(r: ProviderEventRejection) = ProviderEventResult.Rejected(r)

    private fun payload(provider: String, e: Parsed, kind: ProviderEventKind): Map<String, Any?> = buildMap {
        put("provider", provider); put("event_id", e.eventId); put("kind", kind.name); put("provider_ref", e.ref); put("reported_state", e.state)
        e.authorized?.let { put("authorized_minor", it) }; e.captured?.let { put("captured_minor", it) }; e.refunded?.let { put("refunded_minor", it) }
        e.currency?.let { put("currency", it) }; e.dispute?.let { put("dispute_state", it) }
    }

    /**
     * Inhalt nach `api/payments.yaml#ProviderEvent`: Pflichtfelder `event_id`, `type`, `created_at`, `data`; `data.provider_ref` und `data.state` sind für die Abstimmung Pflicht,
     * Beträge ganzzahlig in kleinster Einheit (nie Gleitkomma, Text oder negativ). Das optionale Feld `provider` muss dem Pfad entsprechen. Ein `tenant_id` im Payload hat keine
     * Wirkung: der Mandant folgt allein aus dem Schlüssel (Feld auf oberster Ebene: ungültig, in `data`: wird nicht gelesen).
     */
    private fun parse(provider: String, o: JsonNode): Parsed? = try {
        if (!o.isObject) return null
        val allowed = setOf("event_id", "sequence", "type", "created_at", "data", "provider")
        if (o.propertyNames().any { it !in allowed }) return null
        fun text(n: JsonNode, f: String) = n.get(f)?.takeIf { it.isString }?.asString()
        o.get("provider")?.let { if (!it.isString || it.asString() != provider) return null }
        val id = text(o, "event_id")?.takeIf { it.length in 1..128 } ?: return null
        val type = text(o, "type")?.takeIf { Regex("payment\\.[a-z_.]+").matches(it) && it.length <= 64 } ?: return null
        val createdAt = Instant.parse(text(o, "created_at") ?: return null)
        val seq = o.get("sequence")?.let { if (it.isIntegralNumber && it.canConvertToLong() && it.asLong() >= 1) it.asLong() else return null }
        val d = o.get("data")?.takeIf { it.isObject } ?: return null
        val ref = text(d, "provider_ref")?.takeIf { it.length in 1..128 } ?: return null
        val state = text(d, "state")?.takeIf { it in STATES } ?: return null
        // Fehler in lokalen Funktionen als Ausnahme (ein `return null` dort verließe nur die lokale Funktion): der äußere catch macht daraus "ungültig"
        fun minor(f: String): Long? = d.get(f)?.takeIf { !it.isNull }?.let { if (it.isIntegralNumber && it.canConvertToLong() && it.asLong() in 0..MAX_MINOR) it.asLong() else throw IllegalArgumentException(f) }
        val currency = d.get("currency")?.takeIf { !it.isNull }?.let { if (it.isString && Regex("[A-Z]{3}").matches(it.asString())) it.asString() else throw IllegalArgumentException("currency") }
        val dispute = d.get("dispute_state")?.takeIf { !it.isNull }?.let { if (it.isString && it.asString() in setOf("OPEN", "WON", "LOST")) it.asString() else throw IllegalArgumentException("dispute_state") }
        Parsed(id, type, seq, createdAt, ref, state, minor("authorized_minor"), minor("captured_minor"), minor("refunded_minor"), currency, dispute)
    } catch (_: Exception) { null }

    private companion object {
        const val MAX_MINOR = 1_000_000_000_000L
        val STATES = setOf("CREATED", "REQUIRES_ACTION", "AUTHORIZED", "CAPTURE_PENDING", "CAPTURED", "AUTHORIZATION_FAILED", "VOID_PENDING", "VOIDED", "REFUND_PENDING", "PARTIALLY_REFUNDED", "REFUNDED")
    }
}

package de.taktovia.platform.webhook

import de.taktovia.platform.alert.OperatorAlertPort
import de.taktovia.platform.alert.OperatorWarning
import de.taktovia.platform.alert.Severity
import de.taktovia.platform.net.SafeTarget
import de.taktovia.platform.net.SsrfGuard
import de.taktovia.platform.outbox.RetrySchedule
import de.taktovia.platform.secret.SecretRef
import de.taktovia.platform.secret.SecretResolver
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

data class TransportResponse(val status: Int, val retryAfter: String?)

/** Sendet eine Webhook-Anfrage an ein **bereits geprüftes** Ziel und verbindet ausschließlich mit dessen Adressen. Wirft [IOException] bei Verbindungsfehlern. */
fun interface WebhookTransport {
    fun post(target: SafeTarget, headers: Map<String, String>, body: ByteArray, timeout: Duration): TransportResponse
}

/**
 * Minimaler HTTP/1.1-Client mit festgelegter Zieladresse (kein erneutes Auflösen: DNS-Rebinding zwischen Prüfung und Verbindung ist ausgeschlossen).
 * TLS mit SNI und Hostnamenprüfung gegen den **Namen** der URL; Weiterleitungen werden nie verfolgt (3xx ist ein Fehlversuch); die Antwort wird nur bis
 * zum Statuskopf gelesen. Klartext-HTTP nur, wenn das Ziel es erlaubt (`SsrfGuard(allowHttp)`, nur Tests).
 */
class PinnedHttpTransport(private val sslFactory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory) : WebhookTransport {
    override fun post(target: SafeTarget, headers: Map<String, String>, body: ByteArray, timeout: Duration): TransportResponse {
        val millis = timeout.toMillis().toInt()
        var last: IOException? = null
        for (address in target.addresses) {   // jede geprüfte Adresse einzeln, nie eine andere
            try {
                Socket().use { plain ->
                    plain.connect(InetSocketAddress(address, target.port), millis); plain.soTimeout = millis
                    val socket: Socket = if (target.uri.scheme.equals("https", true)) {
                        (sslFactory.createSocket(plain, target.uri.host, target.port, true) as SSLSocket).also {
                            it.sslParameters = SSLParameters().also { p -> p.endpointIdentificationAlgorithm = "HTTPS"; p.serverNames = listOf(javax.net.ssl.SNIHostName(target.uri.host)) }
                            it.startHandshake()
                        }
                    } else plain
                    val path = (target.uri.rawPath.ifEmpty { "/" }) + (target.uri.rawQuery?.let { "?$it" } ?: "")
                    val head = StringBuilder("POST $path HTTP/1.1\r\nHost: ${target.uri.host}${if (target.uri.port != -1) ":${target.uri.port}" else ""}\r\nConnection: close\r\nContent-Length: ${body.size}\r\n")
                    headers.forEach { (k, v) -> require(k.none { it == '\r' || it == '\n' } && v.none { it == '\r' || it == '\n' }); head.append(k).append(": ").append(v).append("\r\n") }
                    head.append("\r\n")
                    val out = socket.getOutputStream(); out.write(head.toString().toByteArray(Charsets.US_ASCII)); out.write(body); out.flush()
                    return readStatus(socket.getInputStream())
                }
            } catch (e: IOException) { last = e }
        }
        throw last ?: IOException("keine Adresse")
    }

    private fun readStatus(ins: java.io.InputStream): TransportResponse {
        val buf = ByteArrayOutputStream(); var prev = 0; var n = 0
        while (n < 16 * 1024) {   // Kopf bis zur Leerzeile, höchstens 16 KiB
            val b = ins.read(); if (b < 0) break
            buf.write(b); n++
            if (b == '\n'.code && prev == '\n'.code) break
            if (b != '\r'.code) prev = b
        }
        val lines = buf.toString(Charsets.ISO_8859_1).split("\r\n", "\n")
        val status = Regex("^HTTP/1\\.[01] (\\d{3})").find(lines.firstOrNull().orEmpty())?.groupValues?.get(1)?.toInt() ?: throw IOException("ungültige Antwort")
        val retryAfter = lines.drop(1).firstOrNull { it.startsWith("retry-after:", ignoreCase = true) }?.substringAfter(':')?.trim()
        return TransportResponse(status, retryAfter)
    }
}

/** Planungsdefaults (OP-17): Retry-Staffel des Relays (Kap. 05 §5.6), nach 12 Fehlversuchen Dead-Letter, Zeitlimit je Zustellung 10 s. */
data class DeliveryConfig(val batchSize: Int = 50, val lease: Duration = Duration.ofSeconds(60), val maxAttempts: Int = 12, val timeout: Duration = Duration.ofSeconds(10), val maxRetryAfter: Duration = Duration.ofHours(24))

data class DeliveryStats(val delivered: Int = 0, val retried: Int = 0, val deadLettered: Int = 0) {
    operator fun plus(o: DeliveryStats) = DeliveryStats(delivered + o.delivered, retried + o.retried, deadLettered + o.deadLettered)
}

/**
 * Fan-out: legt für jedes **aktive** Abonnement des Mandanten je passendem Ereignis der Outbox genau eine Zustellung an (`UNIQUE (Abonnement, Ereignis)`,
 * wiederholbar). Zugestellt wird nur, was an den Mandanten selbst adressiert ist (`recipient_tenant_id = tenant_id`) und nach dem Anlegen des Abonnements
 * entstand. Der Payload ist die **minimierte Ereignishülle ohne Nutzdaten**; der Empfänger ruft Details über die autorisierte API ab.
 */
class WebhookFanout(private val tx: TenantTransactions) {
    fun fanOut(tenant: TenantId, limit: Int = 500): Int = tx.run(tenant) { j ->
        j.update(
            """INSERT INTO taktovia.webhook_delivery(tenant_id,id,subscription_id,event_id,event_type,body)
               SELECT o.tenant_id, gen_random_uuid(), s.id, o.event_id, o.event_type,
                      jsonb_build_object('event_id', o.event_id, 'event_type', o.event_type, 'schema_version', o.schema_version, 'aggregate_id', o.aggregate_id,
                                         'aggregate_revision', o.aggregate_revision, 'occurred_at', to_char(o.occurred_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'))
                 FROM taktovia.outbox o
                 JOIN taktovia.webhook_subscription s ON s.tenant_id = o.tenant_id AND s.active AND o.event_type = ANY (s.event_types)
                WHERE o.recipient_tenant_id = o.tenant_id AND o.recorded_at >= s.created_at AND o.dead_lettered_at IS NULL
                  AND NOT EXISTS (SELECT 1 FROM taktovia.webhook_delivery d WHERE d.subscription_id = s.id AND d.event_id = o.event_id)
                ORDER BY o.recorded_at LIMIT ?
               ON CONFLICT DO NOTHING""", limit)
    }
}

/**
 * Zustellworker (WP-M1-16): beansprucht fällige Zustellungen (`FOR UPDATE SKIP LOCKED`, Lease), prüft das Ziel **unmittelbar vor jedem Aufruf** erneut
 * (SSRF, DNS-Rebinding), signiert den exakten Body mit dem aktuellen Wert der Secret-Referenz (Rotation wirkt ohne Neustart; `kid` nennt den Schlüssel),
 * sendet **außerhalb** jeder Datenbanktransaktion und folgt nie einer Weiterleitung. Fehlversuche folgen der Staffel des Relays, `429`/`503` mit `Retry-After` verlängern
 * die Wartezeit (höchstens 24 h). Nach [DeliveryConfig.maxAttempts] Fehlversuchen: Dead-Letter und genau eine Betreiberwarnung (WP-M1-47), kein stiller Verlust.
 * Fehlercodes sind technisch (`http_5xx`, `target_not_allowed` …), nie Meldungstext oder Antwortinhalt.
 */
class WebhookDeliveryWorker(
    private val tx: TenantTransactions,
    private val transport: WebhookTransport,
    private val secrets: SecretResolver,
    private val ssrf: SsrfGuard,
    private val alerts: OperatorAlertPort,
    private val clock: Clock = Clock.systemUTC(),
    private val config: DeliveryConfig = DeliveryConfig(),
    private val jitter: () -> Double = { Math.random() },
) {
    private val log = LoggerFactory.getLogger("taktovia.webhook.delivery")
    private class Claimed(val id: UUID, val subscriptionId: UUID, val eventId: UUID, val eventType: String, val body: String, val attempts: Int)
    private class Sub(val url: String, val secretRef: String, val active: Boolean)

    fun runTenant(tenant: TenantId): DeliveryStats {
        var stats = DeliveryStats()
        while (true) {
            val claimed = claim(tenant)
            if (claimed.isEmpty()) return stats
            for (c in claimed) stats += deliver(tenant, c)
            if (claimed.size < config.batchSize) return stats
        }
    }

    private fun claim(tenant: TenantId): List<Claimed> {
        val now = clock.instant(); val ts = Timestamp.from(now)
        return tx.run(tenant) { j ->
            j.query("""UPDATE taktovia.webhook_delivery d SET claimed_until = ?, attempts = d.attempts + 1
                        WHERE d.id IN (SELECT id FROM taktovia.webhook_delivery WHERE state = 'PENDING' AND (next_attempt_at IS NULL OR next_attempt_at <= ?)
                                          AND (claimed_until IS NULL OR claimed_until <= ?) ORDER BY created_at, id LIMIT ? FOR UPDATE SKIP LOCKED)
                        RETURNING d.id, d.subscription_id, d.event_id, d.event_type, d.body::text, d.attempts""",
                { rs, _ -> Claimed(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getObject(3, UUID::class.java), rs.getString(4), rs.getString(5), rs.getInt(6)) },
                Timestamp.from(now.plus(config.lease)), ts, ts, config.batchSize)
        }
    }

    private fun deliver(tenant: TenantId, c: Claimed): DeliveryStats {
        val sub = tx.run(tenant, readOnly = true) { j ->
            j.query("SELECT url, secret_ref, active FROM taktovia.webhook_subscription WHERE id = ?", { rs, _ -> Sub(rs.getString(1), rs.getString(2), rs.getBoolean(3)) }, c.subscriptionId).firstOrNull()
        }
        if (sub == null || !sub.active) {   // deaktiviert, bevor zugestellt wurde: nicht mehr senden
            tx.run(tenant) { j -> j.update("UPDATE taktovia.webhook_delivery SET state = 'CANCELLED', claimed_until = NULL WHERE id = ? AND state = 'PENDING'", c.id) }
            return DeliveryStats()
        }
        val target = ssrf.resolve(sub.url).getOrElse { return fail(tenant, c, "target_not_allowed", null, null) }
        val secret = try { secrets.resolve(SecretRef.parse(sub.secretRef)).reveal().toByteArray() } catch (_: Exception) { return fail(tenant, c, "secret_unavailable", null, null) }
        val body = c.body.toByteArray(Charsets.UTF_8)
        val now = clock.instant()
        val headers = linkedMapOf("Content-Type" to "application/json", "User-Agent" to "Taktovia-Webhooks/1", WebhookSignature.HEADER to WebhookSignature.header(secret, WebhookSignature.keyId(secret), now, body),
            "Taktovia-Event-Id" to c.eventId.toString(), "Taktovia-Event-Type" to c.eventType, "Taktovia-Delivery-Attempt" to c.attempts.toString())
        val response = try { transport.post(target, headers, body, config.timeout) } catch (e: IOException) { return fail(tenant, c, "connection_failed", null, null) }
        return when (response.status) {
            in 200..299 -> {
                tx.run(tenant) { j -> j.update("UPDATE taktovia.webhook_delivery SET state = 'DELIVERED', delivered_at = ?, claimed_until = NULL, next_attempt_at = NULL, last_status = ?, last_error = NULL WHERE id = ? AND state = 'PENDING'",
                    Timestamp.from(clock.instant()), response.status, c.id) }
                DeliveryStats(delivered = 1)
            }
            in 300..399 -> fail(tenant, c, "redirect_not_followed", response.status, null)
            429, 503 -> fail(tenant, c, "http_${response.status}", response.status, retryAfter(response.retryAfter))
            else -> fail(tenant, c, if (response.status in 400..499) "http_4xx" else "http_5xx", response.status, null)
        }
    }

    /** `Retry-After`: Sekunden oder HTTP-Datum; ungültig = keine Angabe. */
    private fun retryAfter(v: String?): Duration? {
        if (v == null) return null
        v.trim().toLongOrNull()?.let { return Duration.ofSeconds(it.coerceAtLeast(0)) }
        return runCatching { Duration.between(clock.instant(), ZonedDateTime.parse(v.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).coerceAtLeast(Duration.ZERO) }.getOrNull()
    }

    private fun fail(tenant: TenantId, c: Claimed, code: String, status: Int?, retryAfter: Duration?): DeliveryStats {
        if (c.attempts >= config.maxAttempts) {
            val moved = tx.run(tenant) { j ->
                j.update("UPDATE taktovia.webhook_delivery SET state = 'DEAD', dead_lettered_at = ?, claimed_until = NULL, next_attempt_at = NULL, last_status = ?, last_error = ? WHERE id = ? AND state = 'PENDING'",
                    Timestamp.from(clock.instant()), status, code, c.id)
            }
            if (moved == 1) alerts.report(OperatorWarning("webhook-delivery", "dead_letter", Severity.CRITICAL, UUID.randomUUID(), tenant.value,
                mapOf("event_type" to c.eventType, "attempts" to c.attempts.toString(), "reason_code" to code)))   // genau eine Warnung je Zustellung
            log.warn("Webhook-Zustellung Dead-Letter delivery={} reason={}", c.id, code)
            return DeliveryStats(deadLettered = 1)
        }
        val wait = maxOf(RetrySchedule.delay(c.attempts, jitter), retryAfter ?: Duration.ZERO).coerceAtMost(config.maxRetryAfter)
        tx.run(tenant) { j ->
            j.update("UPDATE taktovia.webhook_delivery SET next_attempt_at = ?, claimed_until = NULL, last_status = ?, last_error = ? WHERE id = ? AND state = 'PENDING'",
                Timestamp.from(clock.instant().plus(wait)), status, code, c.id)
        }
        return DeliveryStats(retried = 1)
    }

    private fun Duration.coerceAtMost(max: Duration) = if (this > max) max else this
    private fun Duration.coerceAtLeast(min: Duration) = if (this < min) min else this
}

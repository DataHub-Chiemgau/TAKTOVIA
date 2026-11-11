package de.taktovia.adapters.psp.internal

import de.taktovia.adapters.psp.api.PspAdapter
import de.taktovia.adapters.psp.api.PspAuthorize
import de.taktovia.adapters.psp.api.PspCapture
import de.taktovia.adapters.psp.api.PspCommandRef
import de.taktovia.adapters.psp.api.PspErrorClass
import de.taktovia.adapters.psp.api.PspOperation
import de.taktovia.adapters.psp.api.PspOperationRecord
import de.taktovia.adapters.psp.api.PspOutcome
import de.taktovia.adapters.psp.api.PspPayment
import de.taktovia.adapters.psp.api.PspPaymentState
import de.taktovia.adapters.psp.api.PspRefund
import de.taktovia.adapters.psp.api.PspVoid
import de.taktovia.platform.tenant.ExternalCallGuard
import org.slf4j.LoggerFactory
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.net.ConnectException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration

/**
 * HTTP/JSON-Adapter des PSP-Ports. Jeder schreibende Aufruf trägt die Kommandoreferenz als `Idempotency-Key` **und** `operation_ref`; die Referenz gehört dem Aufrufer und ändert sich beim Retry nicht.
 * Fehlerklassen: 2xx Erfolg; 429/503 und verweigerte Verbindung `Unavailable` (kein Effekt); 402/422 `DECLINED` bzw. `REJECTED`, 409 `CONFLICT`, übrige 4xx `REJECTED` (definitiv, kein Effekt);
 * Timeout, abgebrochene Verbindung nach dem Senden und übrige 5xx `Unknown`. Der Aufruf läuft **nie** in einer offenen Datenbanktransaktion ([ExternalCallGuard]).
 */
internal class HttpPspAdapter(override val provider: String, baseUrl: URI, private val timeout: Duration, private val apiKey: () -> String?) : PspAdapter {
    private val log = LoggerFactory.getLogger("taktovia.adapters.psp")
    private val mapper = JsonMapper.builder().build()
    private val base = baseUrl.toString().trimEnd('/')
    private val client = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build()

    override fun authorize(cmd: PspAuthorize) = call(PspOperation.AUTHORIZE, "POST", "/psp/v1/payments", cmd.commandRef,
        mapOf("merchant_ref" to cmd.merchantRef, "operation_ref" to cmd.commandRef.value, "amount_minor" to cmd.amountMinor, "currency" to cmd.currency, "payment_method" to cmd.paymentMethodRef))

    override fun capture(cmd: PspCapture) = call(PspOperation.CAPTURE, "POST", "/psp/v1/payments/${enc(cmd.paymentId)}/capture", cmd.commandRef,
        buildMap { put("operation_ref", cmd.commandRef.value); cmd.amountMinor?.let { put("amount_minor", it) } })

    override fun void(cmd: PspVoid) = call(PspOperation.VOID, "POST", "/psp/v1/payments/${enc(cmd.paymentId)}/void", cmd.commandRef, mapOf("operation_ref" to cmd.commandRef.value))

    override fun refund(cmd: PspRefund) = call(PspOperation.REFUND, "POST", "/psp/v1/payments/${enc(cmd.paymentId)}/refund", cmd.commandRef,
        mapOf("operation_ref" to cmd.commandRef.value, "amount_minor" to cmd.amountMinor))

    override fun status(paymentIdOrProviderRef: String) = call(PspOperation.STATUS, "GET", "/psp/v1/payments/${enc(paymentIdOrProviderRef)}", null, null)
    override fun statusByMerchantRef(merchantRef: String) = call(PspOperation.STATUS, "GET", "/psp/v1/payments?merchant_ref=${enc(merchantRef)}", null, null)
    override fun statusByCommandRef(ref: PspCommandRef) = call(PspOperation.STATUS, "GET", "/psp/v1/payments?operation_ref=${enc(ref.value)}", null, null)

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)

    private fun call(op: PspOperation, method: String, path: String, ref: PspCommandRef?, body: Map<String, Any?>?): PspOutcome {
        ExternalCallGuard.requireNone("PSP-Aufruf ${op.name}")   // Leitlinie 3: vor dem Senden, nie in einer offenen Transaktion
        val b = HttpRequest.newBuilder(URI("$base$path")).timeout(timeout).header("Accept", "application/json")
        apiKey()?.let { b.header("Authorization", "Bearer $it") }
        if (ref != null) b.header("Idempotency-Key", ref.value)
        if (body != null) b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))) else b.method(method, HttpRequest.BodyPublishers.noBody())
        val response = try {
            client.send(b.build(), HttpResponse.BodyHandlers.ofString())
        } catch (e: HttpTimeoutException) {
            return unknown(op, ref, "timeout")
        } catch (e: ConnectException) {
            log.warn("PSP nicht erreichbar op={}", op); return PspOutcome.Unavailable(null)
        } catch (e: IOException) {
            return unknown(op, ref, "connection_lost")   // nach dem Senden abgebrochen: Wirkung offen
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt(); return unknown(op, ref, "interrupted")
        }
        val status = response.statusCode()
        val json = runCatching { mapper.readTree(response.body()) }.getOrNull()
        val code = json?.get("code")?.takeIf { it.isString }?.asString()?.take(64) ?: "http_$status"
        return when {
            status in 200..299 -> json?.let { runCatching { PspOutcome.Ok(payment(it, response.headers().firstValue("Idempotent-Replayed").isPresent)) }.getOrNull() } ?: unknown(op, ref, "malformed_response")
            status == 429 || status == 503 -> PspOutcome.Unavailable(response.headers().firstValue("Retry-After").map { runCatching { Duration.ofSeconds(it.toLong()) }.getOrNull() }.orElse(null))
            status == 409 -> PspOutcome.Failed(PspErrorClass.CONFLICT, code, status)
            status == 402 || code == "DECLINED_BY_PROVIDER" -> PspOutcome.Failed(PspErrorClass.DECLINED, code, status)
            status in 400..499 -> PspOutcome.Failed(PspErrorClass.REJECTED, code, status)
            else -> unknown(op, ref, "http_$status")
        }
    }

    private fun unknown(op: PspOperation, ref: PspCommandRef?, reason: String): PspOutcome {
        log.warn("PSP-Ergebnis unbestimmt op={} reason={} ref={}", op, reason, ref)   // nur Referenz und Grund, nie Zahlungsdaten
        return PspOutcome.Unknown(ref, reason)
    }

    private fun payment(j: JsonNode, replayed: Boolean): PspPayment {
        fun s(f: String) = j.get(f)?.takeIf { it.isString }?.asString() ?: throw IllegalArgumentException(f)
        fun m(f: String) = j.get(f)?.takeIf { it.isIntegralNumber && it.canConvertToLong() }?.asLong()?.takeIf { it >= 0 } ?: throw IllegalArgumentException(f)   // nie Gleitkomma
        // unbekannte Operationsarten des PSP werden übergangen (der Abgleich sucht nur nach den eigenen Arten)
        val ops = j.get("operations")?.values()?.mapNotNull { o ->
            val kind = PspOperation.entries.firstOrNull { it.name.equals(o.get("kind")?.asString(), ignoreCase = true) } ?: return@mapNotNull null
            PspOperationRecord(kind, o.get("operation_ref")?.takeIf { it.isString }?.asString(), o.get("amount_minor")?.takeIf { it.isIntegralNumber }?.asLong(), o.get("result")?.takeIf { it.isString }?.asString())
        }.orEmpty()
        return PspPayment(s("payment_id"), s("provider_ref"), s("merchant_ref"), PspPaymentState.valueOf(s("state")), s("currency"), m("authorized_minor"), m("captured_minor"), m("refunded_minor"),
            j.get("dispute_state")?.takeIf { it.isString }?.asString(), ops, replayed)
    }
}

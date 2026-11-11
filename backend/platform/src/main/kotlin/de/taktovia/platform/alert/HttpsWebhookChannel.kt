package de.taktovia.platform.alert

import de.taktovia.platform.secret.SecretRef
import de.taktovia.platform.secret.SecretResolver
import de.taktovia.platform.transport.Endpoint
import de.taktovia.platform.transport.TransportPolicy
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Empfängerkanal als HTTPS-Webhook. Die Empfängeradresse (kann ein Token enthalten) kommt ausschließlich aus einer Secret-Referenz
 * (`alert_channel_credential`) und wird weder protokolliert noch in Fehlermeldungen ausgegeben. Klartext-URLs werden abgewiesen.
 */
class HttpsWebhookChannel(
    private val target: SecretRef, private val resolver: SecretResolver,
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
) : AlertChannel {
    override fun deliver(notification: AlertNotification) {
        val url = resolver.resolve(target).reveal().trim()
        check(TransportPolicy.violations(Endpoint.HTTP, "alert-channel", url).isEmpty()) { "Empfängerkanal nicht per TLS konfiguriert" }
        val body = OperatorAlertService.json("operator_alert", "dedup_key" to notification.dedupKey, "source" to notification.source, "kind" to notification.kind,
            "severity" to notification.severity.name.lowercase(), "correlation_id" to notification.correlationId, "tenant_ref" to notification.tenantRef,
            "triggered_at" to notification.triggeredAt, "reason" to notification.reason)
        val res = try {
            client.send(HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.discarding())
        } catch (e: java.io.IOException) { throw IllegalStateException("Empfängerkanal nicht erreichbar (${e.javaClass.simpleName})") }
        check(res.statusCode() in 200..299) { "Empfängerkanal antwortete ${res.statusCode()}" }
    }
}

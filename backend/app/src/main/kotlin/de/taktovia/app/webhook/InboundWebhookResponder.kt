package de.taktovia.app.webhook

import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.webhook.InboundResult
import de.taktovia.platform.webhook.InboundWebhookReceiver
import de.taktovia.platform.webhook.Rejection
import de.taktovia.platform.webhook.WebhookSignature
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity

/**
 * HTTP-Anbindung des Eingangsfilters für signierte Ereignisse (WP-M1-16, Kap. 05 §5.6), wiederverwendbar für Eingangsendpunkte späterer Meilensteine
 * (Partner M6, PSP M2). Liest den **exakten Rohbody** (die Eingabeprüfung reicht die Bytes unverändert weiter) und den Header `Taktovia-Signature`; Quittung:
 * 202 `ACCEPTED` nach Inbox-Commit, 200 `ALREADY_RECEIVED`, 401 ohne Details bei Signatur- oder Zeitfensterfehler, 413 zu groß, 400 formal ungültig,
 * 409 bei gleicher Ereignis-ID mit anderem Payload (Sicherheitsereignis und Quarantäne sind dann erfolgt).
 */
class InboundWebhookResponder(private val receiver: InboundWebhookReceiver) {
    fun handle(source: String, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        val raw = request.inputStream.readAllBytes()
        return when (val r = receiver.receive(source, request.getHeader(WebhookSignature.HEADER), raw)) {
            is InboundResult.Accepted -> ResponseEntity.status(HttpStatus.ACCEPTED).body(linkedMapOf("status" to "ACCEPTED", "event_id" to r.eventId.toString()))
            is InboundResult.AlreadyReceived -> ResponseEntity.ok(linkedMapOf("status" to "ALREADY_RECEIVED", "event_id" to r.eventId.toString()))
            is InboundResult.Rejected -> throw when (r.reason) {
                Rejection.SIGNATURE_INVALID, Rejection.TIMESTAMP_OUT_OF_WINDOW -> ProblemException(ErrorCode.UNAUTHENTICATED, "Signatur ungültig.")
                Rejection.TOO_LARGE -> ProblemException(ErrorCode.PAYLOAD_TOO_LARGE, "Die Anfrage überschreitet die zulässige Größe.")
                Rejection.MALFORMED -> ProblemException(ErrorCode.BAD_REQUEST, "Das Ereignis ist formal ungültig.")
                Rejection.PAYLOAD_CONFLICT -> ProblemException(ErrorCode.CONFLICT, "Die Ereignis-ID wurde bereits mit anderem Inhalt empfangen.")
            }
        }
    }
}

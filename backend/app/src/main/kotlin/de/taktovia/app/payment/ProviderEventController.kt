package de.taktovia.app.payment

import de.taktovia.farepayment.api.ProviderEventRejection
import de.taktovia.farepayment.api.ProviderEventResult
import de.taktovia.farepayment.api.ProviderEvents
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

/**
 * `POST /v1/payments/provider-events/{provider}` (WP-M2-29, Kap. 05 §5.7): signierte PSP-Benachrichtigung. **Kein Bearer-Token**: die Anfrage ist durch die PSP-spezifische
 * Signatur (`X-Provider-Signature`, nicht der Partner-HMAC) authentisiert, den Mandanten bestimmt allein der Signaturschlüssel des Providers. 202 erst nach dauerhaftem Commit,
 * 200 `ALREADY_RECEIVED` bei Wiederholung, 401 ohne Details bei Signatur-, Zeitfenster- oder Providerfehler, 409 bei gleicher Ereignis-ID mit anderem Payload (Sicherheitsereignis ist geschrieben),
 * 413 zu groß, 422 inhaltlich ungültig. Liest den **exakten Rohbody** (die Eingabeprüfung reicht die Bytes unverändert weiter).
 */
@RestController
class ProviderEventController(private val events: ProviderEvents) {
    @PostMapping("/v1/payments/provider-events/{provider}")
    fun receive(@PathVariable provider: String, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        val raw = request.inputStream.readAllBytes()
        return when (val r = events.receive(provider, request.getHeader("X-Provider-Signature"), request.getHeader("X-Provider-Timestamp"), raw)) {
            is ProviderEventResult.Accepted -> ResponseEntity.status(HttpStatus.ACCEPTED).body(linkedMapOf("event_id" to r.eventId, "status" to "ACCEPTED"))
            is ProviderEventResult.AlreadyReceived -> ResponseEntity.ok(linkedMapOf("event_id" to r.eventId, "status" to "ALREADY_RECEIVED"))
            is ProviderEventResult.Rejected -> throw when (r.reason) {
                ProviderEventRejection.SIGNATURE_INVALID, ProviderEventRejection.TIMESTAMP_OUT_OF_WINDOW -> ProblemException(ErrorCode.UNAUTHENTICATED, "Signatur ungültig.")
                ProviderEventRejection.TOO_LARGE -> ProblemException(ErrorCode.PAYLOAD_TOO_LARGE, "Die Anfrage überschreitet die zulässige Größe.")
                ProviderEventRejection.MALFORMED -> ProblemException(ErrorCode.VALIDATION_FAILED, "Das Ereignis ist inhaltlich ungültig.")
                ProviderEventRejection.PAYLOAD_CONFLICT -> ProblemException(ErrorCode.CONFLICT, "Die Ereignis-ID wurde bereits mit anderem Inhalt empfangen.")
            }
        }
    }
}

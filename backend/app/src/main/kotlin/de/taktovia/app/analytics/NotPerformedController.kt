package de.taktovia.app.analytics

import com.fasterxml.jackson.annotation.JsonValue
import de.taktovia.analytics.api.AnalyticsApi
import de.taktovia.analytics.api.CorrectCause
import de.taktovia.analytics.api.NotPerformedTrip
import de.taktovia.app.identity.RequestContexts
import de.taktovia.platform.api.Revisioned
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

class NotPerformedTripResource(@get:JsonValue val data: Map<String, Any?>, override val revision: Long) : Revisioned

/** Wire-Darstellung einer nicht durchgeführten Fahrt (`NotPerformedTrip` in `api/analytics.yaml`): Kennungen, Codes, Zeiten und Korrekturvermerke; keine Personendaten, keine Fahrerzuordnung. */
internal object NotPerformedJson {
    fun trip(t: NotPerformedTrip): Map<String, Any?> = linkedMapOf(
        "booking_id" to t.bookingId.toString(), "service_id" to t.serviceId?.toString(), "confirmed_at" to t.confirmedAt.toString(), "not_performed_at" to t.notPerformedAt.toString(), "cause_state" to t.state.name,
        "cause" to t.cause, "category" to t.category?.name, "derived_cause" to t.derivedCause, "revision" to t.revision,
        "corrections" to t.corrections.map { c ->
            linkedMapOf("correction_no" to c.no, "old_cause" to c.oldCause, "old_category" to c.oldCategory?.name, "new_cause" to c.newCause, "new_category" to c.newCategory.name, "reason" to c.reason, "actor" to c.actor, "at" to c.at.toString())
        },
    )
}

/**
 * Korrektur der Ursache einer nicht durchgeführten Fahrt (WP-M4-55, KPI-009): `POST /v1/analytics/not-performed/{booking_id}/corrections`. Rolle Disposition oder Administration mit Scope `analytics:write` (403 sonst,
 * Arbeitsannahme bis zur Festlegung der Rollen, OP 30); ohne Grund 422; Fahrt eines fremden Mandanten 404. Der alte Wert bleibt als Vermerk mit Akteur und Zeit. `expected_revision` ist optional: mit Angabe
 * weist die Versionsprüfung eine veraltete Korrektur mit 412 ab, ohne werden gleichzeitige Korrekturen der Reihe nach vermerkt (beide Vermerke bleiben).
 */
@RestController
class NotPerformedController(private val analytics: AnalyticsApi) {

    @PostMapping("/v1/analytics/not-performed/{booking_id}/corrections")
    fun correct(@PathVariable("booking_id") bookingId: String, @RequestBody body: Map<String, Any?>, @RequestHeader("Idempotency-Key") key: String, request: HttpServletRequest): NotPerformedTripResource {
        val ctx = RequestContexts.require(request)
        val id = runCatching { UUID.fromString(bookingId) }.getOrNull() ?: throw ProblemException.notFound()
        fun str(f: String): String = when (val v = body[f]) { null -> throw ProblemException.invalid(Violation("/$f", ViolationCode.REQUIRED)); is String -> v; else -> throw ProblemException.invalid(Violation("/$f", ViolationCode.TYPE_MISMATCH)) }
        val expected = when (val e = body["expected_revision"]) { null -> null; is Number -> e.toLong(); else -> throw ProblemException.invalid(Violation("/expected_revision", ViolationCode.TYPE_MISMATCH)) }
        val t = analytics.notPerformed.correct(ctx, id, CorrectCause(str("new_cause"), str("reason"), expected))
        return NotPerformedTripResource(NotPerformedJson.trip(t), t.revision)
    }
}

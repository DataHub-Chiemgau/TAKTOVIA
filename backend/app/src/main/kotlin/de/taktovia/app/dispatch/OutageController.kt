package de.taktovia.app.dispatch

import com.fasterxml.jackson.annotation.JsonValue
import de.taktovia.app.identity.RequestContexts
import de.taktovia.bookingdispatch.api.CancelOutageCommand
import de.taktovia.bookingdispatch.api.OutageBookingView
import de.taktovia.bookingdispatch.api.OutageOverview
import de.taktovia.bookingdispatch.api.OutageReplacement
import de.taktovia.bookingdispatch.api.RebookOutageCommand
import de.taktovia.platform.api.Etag
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

class OutageBookingResource(@get:JsonValue val data: Map<String, Any?>, override val revision: Long) : Revisioned

/** Wire-Darstellung der Störungsansicht (`IncidentOutage` in `api/fleet-safety.yaml`): nur Kennungen, Codes und Zeiten, keine Fahrgastdaten. */
internal object OutageJson {
    fun of(o: OutageOverview): Map<String, Any?> = linkedMapOf(
        "bookings" to o.bookings.map { booking(it) },
        "boarded_booking_ids" to o.boarded.map { it.bookingId.toString() },
        "open_count" to o.open.size,
    )

    fun booking(b: OutageBookingView): Map<String, Any?> = linkedMapOf(
        "id" to b.id.toString(), "booking_id" to b.bookingId.toString(), "booking_number" to b.bookingNumber, "run_id" to b.runId.toString(), "vehicle_id" to b.vehicleId.toString(), "state" to b.state.name,
        "issue_code" to b.issueCode, "decision" to b.decision, "decision_reason" to b.decisionReason, "decided_by" to b.decidedBy, "decided_at" to b.decidedAt?.toString(), "replacement_run_id" to b.replacementRunId?.toString(),
        "passenger_notice_requested" to b.passengerNoticeRequested, "revision" to b.revision, "passengers" to b.passengers, "wheelchair_spaces" to b.wheelchairSpaces,
        "pickup_window" to if (b.pickupEarliest != null && b.pickupLatest != null) linkedMapOf("earliest_at" to b.pickupEarliest.toString(), "latest_at" to b.pickupLatest.toString()) else null,
        "proposals" to b.proposals.map { p ->
            linkedMapOf("id" to p.id.toString(), "vehicle_id" to p.vehicleId.toString(), "run_id" to p.runId.toString(), "run_revision" to p.runRevision, "pickup_at" to p.pickupAt.toString(), "dropoff_at" to p.dropoffAt.toString(),
                "window_effect" to p.windowEffect.name, "window_violations" to p.windowViolations)
        },
        "actions" to b.actions,
    )
}

/**
 * Aktionen der Störungsansicht (WP-M3-54, UI-07): „Ersatzoptionen prüfen“, Umbuchung, Betreiberstorno, „Fahrgast informieren“. Rechte (`operator:write`, Disposition) aus der Rechtematrix im Filter, Rolle und
 * Dienstscope noch einmal im Dienst (fremder Incident 404). `If-Match` (428/412) bezieht sich auf die **betroffene Buchung** (ihre Revision steht als `revision` in der Störungsansicht und als `ETag` der Antwort);
 * `Idempotency-Key` ist Pflicht. Unzulässig (bereits entschieden): 409 `INVALID_STATE_TRANSITION`; eingestiegener Fahrgast: 409 `CONFLICT` (`PASSENGER_ONBOARD`).
 */
@RestController
class OutageController(private val outage: OutageReplacement) {

    private fun id(raw: String): UUID = runCatching { UUID.fromString(raw) }.getOrNull() ?: throw ProblemException.notFound()

    private fun reason(body: Map<String, Any?>): String = when (val r = body["reason_code"]) {
        null -> throw ProblemException.invalid(Violation("/reason_code", ViolationCode.REQUIRED))
        is String -> r
        else -> throw ProblemException.invalid(Violation("/reason_code", ViolationCode.TYPE_MISMATCH))
    }

    @PostMapping("/v1/operator/incidents/{incident_id}/outage/check")
    fun check(@PathVariable("incident_id") incident: String, request: HttpServletRequest): Map<String, Any?> =
        OutageJson.of(outage.checkOptions(RequestContexts.require(request), id(incident)))

    @PostMapping("/v1/operator/incidents/{incident_id}/outage/bookings/{booking_id}/rebook")
    fun rebook(
        @PathVariable("incident_id") incident: String, @PathVariable("booking_id") booking: String, @RequestBody body: Map<String, Any?>, @RequestHeader("If-Match", required = false) ifMatch: String?,
        request: HttpServletRequest,
    ): OutageBookingResource {
        val ctx = RequestContexts.require(request)
        val proposal = (body["proposal_id"] as? String)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: throw ProblemException.invalid(Violation("/proposal_id", if (body.containsKey("proposal_id")) ViolationCode.INVALID_FORMAT else ViolationCode.REQUIRED))
        val accept = when (val a = body["accept_window_effect"]) { null -> false; is Boolean -> a; else -> throw ProblemException.invalid(Violation("/accept_window_effect", ViolationCode.TYPE_MISMATCH)) }
        val r = outage.rebook(ctx, id(incident), id(booking), RebookOutageCommand(Etag.parseIfMatch(ifMatch), proposal, reason(body), accept))
        return OutageBookingResource(OutageJson.booking(r), r.revision)
    }

    @PostMapping("/v1/operator/incidents/{incident_id}/outage/bookings/{booking_id}/cancel")
    fun cancel(
        @PathVariable("incident_id") incident: String, @PathVariable("booking_id") booking: String, @RequestBody body: Map<String, Any?>, @RequestHeader("If-Match", required = false) ifMatch: String?,
        request: HttpServletRequest,
    ): OutageBookingResource {
        val r = outage.cancel(RequestContexts.require(request), id(incident), id(booking), CancelOutageCommand(Etag.parseIfMatch(ifMatch), reason(body)))
        return OutageBookingResource(OutageJson.booking(r), r.revision)
    }

    @PostMapping("/v1/operator/incidents/{incident_id}/outage/bookings/{booking_id}/inform")
    fun inform(
        @PathVariable("incident_id") incident: String, @PathVariable("booking_id") booking: String, @RequestHeader("If-Match", required = false) ifMatch: String?,
        request: HttpServletRequest,
    ): OutageBookingResource {
        val r = outage.inform(RequestContexts.require(request), id(incident), id(booking), Etag.parseIfMatch(ifMatch))
        return OutageBookingResource(OutageJson.booking(r), r.revision)
    }
}

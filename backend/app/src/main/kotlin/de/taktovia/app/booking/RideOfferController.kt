package de.taktovia.app.booking

import de.taktovia.app.identity.RequestContexts
import de.taktovia.bookingdispatch.api.AssistanceCode
import de.taktovia.bookingdispatch.api.EquipmentCode
import de.taktovia.bookingdispatch.api.RideOfferCommand
import de.taktovia.bookingdispatch.api.RideOfferProposal
import de.taktovia.bookingdispatch.api.RideOfferRequests
import de.taktovia.bookingdispatch.api.RidePlace
import de.taktovia.bookingdispatch.api.RideTimeKind
import de.taktovia.bookingdispatch.api.RideTimeWindow
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * `POST /v1/ride-offers` (WP-M2-11 bis -13): Anfrage erfassen, Zeitfenster bilden, Zulässigkeit prüfen. Die Eingabe ist durch das Vertragsschema geprüft (422 am Rand); eine fachliche
 * Ablehnung ist 200 mit leerer Liste und normiertem Grund. Mandant und Akteur stammen aus dem Kontext, nie aus dem Body; der `Idempotency-Key` ist Pflicht (Filter) und
 * dient zugleich als Wiederholungsschutz des Versuchs.
 */
@RestController
class RideOfferController(private val rides: RideOfferRequests) {

    @PostMapping("/v1/ride-offers")
    @Suppress("UNCHECKED_CAST")
    fun create(@RequestBody body: Map<String, Any?>, @RequestHeader("Idempotency-Key") key: String, request: HttpServletRequest): Map<String, Any?> {
        val desired = body["desired"] as Map<String, Any?>
        val cmd = RideOfferCommand(
            serviceId = UUID.fromString(body["service_id"] as String), origin = place(body["origin"] as Map<String, Any?>), destination = place(body["destination"] as Map<String, Any?>),
            timeKind = RideTimeKind.valueOf(body["time_kind"] as String), desiredAt = desired["desired_at"] as String?, timeZone = desired["time_zone"] as String?,
            passengers = (body["passengers"] as Number).toInt(), idempotencyKey = key, wheelchairSpaces = (body["wheelchair_spaces"] as Number?)?.toInt() ?: 0,
            equipment = (body["equipment"] as List<String>?).orEmpty().map { EquipmentCode.valueOf(it) }.toSet(),
            assistanceCodes = (body["assistance_codes"] as List<String>?).orEmpty().map { AssistanceCode.valueOf(it) }.toSet(), channel = body["channel"] as String? ?: "WEB",
        )
        val r = rides.request(RequestContexts.require(request), cmd)
        return linkedMapOf(
            "attempt_id" to r.attemptId.toString(), "offers" to r.offers.map { proposal(it) },
            "rejection" to r.rejection?.let { rej ->
                linkedMapOf("code" to rej.primary.name, "secondary_codes" to rej.secondary.map { it.name }, "text" to rej.primary.text, "next_action" to rej.primary.nextAction)
            },
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun place(m: Map<String, Any?>): RidePlace {
        val p = m["point"] as Map<String, Any?>
        return RidePlace((p["longitude"] as Number).toDouble(), (p["latitude"] as Number).toDouble(), (m["stop_id"] as String?)?.let(UUID::fromString))
    }

    private fun window(w: RideTimeWindow) = linkedMapOf("earliest_at" to w.earliestAt.toString(), "latest_at" to w.latestAt.toString())

    private fun proposal(o: RideOfferProposal) = linkedMapOf(
        "service_id" to o.serviceId.toString(), "booking_kind" to o.bookingKind.name, "time_kind" to o.timeKind.name, "pickup_window" to window(o.pickupWindow),
        "arrival_window" to window(o.arrivalWindow), "arrival_grace_min" to o.arrivalGraceMin, "direct_seconds" to o.directSeconds,
    )
}

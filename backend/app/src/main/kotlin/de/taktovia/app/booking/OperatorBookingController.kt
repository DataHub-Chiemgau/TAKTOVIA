package de.taktovia.app.booking

import de.taktovia.app.identity.RequestContexts
import de.taktovia.bookingdispatch.api.BookingSearchFilter
import de.taktovia.bookingdispatch.api.BookingSearchRow
import de.taktovia.bookingdispatch.api.OperatorBookingSearch
import de.taktovia.platform.api.CursorCodec
import de.taktovia.platform.api.CursorPosition
import de.taktovia.platform.api.CursorScope
import de.taktovia.platform.api.Paging
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.platform.time.Rfc3339
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/**
 * Auftragsliste der Leitstelle mit Suche, `GET /v1/operator/bookings` (WP-M3-37, LH-6.7-OPS-002). Fünf Suchfelder (`booking_number`, Zeitraum `from`/`to`, `passenger`, `vehicle_id`, `state`), mehrere
 * wirken als Schnittmenge; Antwort `{items, next_cursor, as_of}`. `limit` 1 bis 100, ein ungültiger oder fremder Cursor ist 400 `INVALID_CURSOR`; der Cursor ist an den Filter gebunden (Fingerabdruck,
 * ohne die Suchbegriffe). `passenger` ist der exakte Fahrgastverweis (Konto-ID), nur für Rollen mit Recht auf Fahrgastdaten (sonst 403); die Suche über Name oder Kontakt ist offen (OP-26). Die Filterwerte
 * werden weder protokolliert noch in Metriken geführt (Telemetrie klassifiziert nur nach Pfadpräfix). Keine Treffer: 200 mit leerer Liste und `as_of`.
 */
@RestController
class OperatorBookingController(private val search: OperatorBookingSearch, private val codec: CursorCodec) {

    @GetMapping("/v1/operator/bookings")
    fun list(
        @RequestParam(name = "booking_number", required = false) bookingNumber: String?, @RequestParam(required = false) from: String?, @RequestParam(required = false) to: String?,
        @RequestParam(required = false) passenger: String?, @RequestParam(name = "vehicle_id", required = false) vehicleId: String?, @RequestParam(required = false) state: String?,
        @RequestParam(name = "service_id", required = false) serviceId: String?, @RequestParam(required = false) limit: String?, @RequestParam(required = false) cursor: String?,
        request: HttpServletRequest,
    ): Map<String, Any?> {
        val ctx = RequestContexts.require(request)
        val lim = Paging.limit(limit)
        val v = mutableListOf<Violation>()
        fun uuid(name: String, raw: String?): UUID? = raw?.let { r -> runCatching { UUID.fromString(r) }.getOrNull() ?: run { v += Violation("/\$query/$name", ViolationCode.INVALID_FORMAT); null } }
        fun instant(name: String, raw: String?): Instant? = raw?.let { r -> Rfc3339.parse(r, "/\$query/$name").let { c -> if (c.ok) c.value!!.toInstant() else { v += c.violations; null } } }
        val number = bookingNumber?.also { if (it.isBlank() || it.length > 32) v += Violation("/\$query/booking_number", if (it.isBlank()) ViolationCode.TOO_SHORT else ViolationCode.TOO_LONG) }
        val filter = BookingSearchFilter(number, instant("from", from), instant("to", to), uuid("passenger", passenger), uuid("vehicle_id", vehicleId), state, uuid("service_id", serviceId))
        if (v.isNotEmpty()) throw ProblemException.invalid(*v.toTypedArray())
        val scope = CursorScope(ctx.tenant.toString(), ctx.actor.subject, "operator-bookings:" + filter.fingerprint())
        val pos = Paging.position(cursor, codec, scope)
        val rows = search.search(ctx, filter, lim, pos?.let { it.createdAt to it.id })
        return Paging.page(rows, lim, { CursorPosition(it.createdAt, it.bookingId) }, codec, scope, Instant.now()).toMap { render(it) }
    }

    companion object {
        /** Trefferzeile `OperatorBookingRow`: Fahrgastverweis und Unterstützungsmerkmale nur, wenn die Rolle sie sehen darf (sonst fehlt das Feld). */
        fun render(r: BookingSearchRow): Map<String, Any?> = linkedMapOf<String, Any?>(
            "id" to r.bookingId.toString(), "booking_number" to r.bookingNumber, "service_id" to r.serviceId.toString(), "state" to r.state, "revision" to r.revision, "created_at" to r.createdAt.toString(),
            "pickup_window" to linkedMapOf("earliest_at" to r.pickupEarliest.toString(), "latest_at" to r.pickupLatest.toString()),
            "arrival_window" to linkedMapOf("earliest_at" to r.arrivalEarliest.toString(), "latest_at" to r.arrivalLatest.toString()),
            "passengers" to r.passengers, "wheelchair_spaces" to r.wheelchairSpaces, "vehicle_id" to r.vehicleId?.toString(), "vehicle_label" to r.vehicleLabel,
        ).also { m ->
            r.passengerRef?.let { m["passenger_ref"] = it.toString() }
            r.assistanceCodes?.let { m["assistance_codes"] = it }
            m["flags"] = r.flags.map { f -> linkedMapOf("id" to f.id.toString(), "kind" to f.kind, "cause" to f.cause, "affected_count" to f.affectedCount, "opened_at" to f.openedAt.toString(), "threshold_min" to f.thresholdMin) }
        }
    }
}

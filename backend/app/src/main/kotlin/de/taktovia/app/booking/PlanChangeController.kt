package de.taktovia.app.booking

import de.taktovia.app.identity.RequestContexts
import de.taktovia.bookingdispatch.api.BookingPlanChange
import de.taktovia.bookingdispatch.api.DriverPlanChange
import de.taktovia.bookingdispatch.api.DriverPlanSnapshot
import de.taktovia.bookingdispatch.api.PassengerPlanChange
import de.taktovia.bookingdispatch.api.PassengerPlanSnapshot
import de.taktovia.bookingdispatch.api.PlanChangeFeed
import de.taktovia.bookingdispatch.api.PlanChanges
import de.taktovia.bookingdispatch.api.PlanChannel
import de.taktovia.platform.api.Paging
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Änderungsstrom der Planrevisionen (WP-M2-28, LH-6.5-OPT-010): je Empfängerkanal ein Strom (`/v1/plan-changes` Fahrgast, `/v1/driver/plan-changes` Fahrer) und ein Snapshot zum
 * Schließen von Lücken. Mandant und Empfänger stammen nur aus dem Kontext; die Kanalbindung prüft das Modul (andere Rolle: 403). Das Protokoll ist ein abfragbarer, wiederaufnehmbarer
 * Strom über einen monotonen Cursor; SSE oder WebSocket (OP-25) sind ein Transport darüber.
 */
@RestController
class PlanChangeController(private val changes: PlanChanges) {

    @GetMapping("/v1/plan-changes")
    fun passengerFeed(@RequestParam(required = false) after: String?, @RequestParam(required = false) limit: String?, request: HttpServletRequest): Map<String, Any?> =
        render(changes.feed(RequestContexts.require(request), PlanChannel.PASSENGER, cursor(after), Paging.limit(limit)))

    @GetMapping("/v1/plan-changes/snapshot")
    fun passengerSnapshot(request: HttpServletRequest): Map<String, Any?> {
        val s = changes.snapshot(RequestContexts.require(request), PlanChannel.PASSENGER) as PassengerPlanSnapshot
        return linkedMapOf("as_of" to s.asOf.toString(), "cursor" to s.cursor, "bookings" to s.bookings.map {
            linkedMapOf("booking_id" to it.bookingId.toString(), "booking_revision" to it.bookingRevision, "sequence" to it.sequence, "pickup_at" to it.pickupAt?.toString(), "dropoff_at" to it.dropoffAt?.toString())
        })
    }

    @GetMapping("/v1/driver/plan-changes")
    fun driverFeed(@RequestParam(required = false) after: String?, @RequestParam(required = false) limit: String?, request: HttpServletRequest): Map<String, Any?> =
        render(changes.feed(RequestContexts.require(request), PlanChannel.DRIVER, cursor(after), Paging.limit(limit)))

    @GetMapping("/v1/driver/plan-changes/snapshot")
    fun driverSnapshot(request: HttpServletRequest): Map<String, Any?> {
        val s = changes.snapshot(RequestContexts.require(request), PlanChannel.DRIVER) as DriverPlanSnapshot
        return linkedMapOf("as_of" to s.asOf.toString(), "cursor" to s.cursor, "runs" to s.runs.map {
            linkedMapOf("shift_id" to it.shiftId.toString(), "run_id" to it.runId.toString(), "revision" to it.revision, "state" to it.state, "stop_count" to it.stopCount)
        })
    }

    private fun render(f: PlanChangeFeed): Map<String, Any?> = linkedMapOf("items" to f.items.map {
        when (it) {
            is PassengerPlanChange -> linkedMapOf("cursor" to it.cursor, "cause" to it.cause.name, "recorded_at" to it.recordedAt.toString(), "bookings" to it.bookings.map(::render))
            is DriverPlanChange -> linkedMapOf("cursor" to it.cursor, "cause" to it.cause.name, "recorded_at" to it.recordedAt.toString(), "shift_id" to it.shiftId.toString(), "run_id" to it.runId.toString(),
                "revision" to it.revision, "previous_revision" to it.previousRevision, "booking_ids" to it.bookingIds.map { id -> id.toString() })
        }
    }, "next_cursor" to f.nextCursor, "has_more" to f.hasMore)

    private fun render(b: BookingPlanChange): Map<String, Any?> = linkedMapOf(
        "booking_id" to b.bookingId.toString(), "kind" to b.kind.name, "sequence" to b.sequence, "pickup_before" to b.pickupBefore?.toString(), "pickup_after" to b.pickupAfter?.toString(),
        "dropoff_before" to b.dropoffBefore?.toString(), "dropoff_after" to b.dropoffAfter?.toString(),
    )

    private fun cursor(v: String?): Long = if (v == null) 0L else v.toLongOrNull()?.takeIf { it in 0..MAX_SAFE } ?: throw ProblemException.invalid(Violation("/\$query/after", ViolationCode.INVALID_FORMAT))


    private companion object { const val MAX_SAFE = 9007199254740991L }
}

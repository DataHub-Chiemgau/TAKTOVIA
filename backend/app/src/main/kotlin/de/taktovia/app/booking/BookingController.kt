package de.taktovia.app.booking

import com.fasterxml.jackson.annotation.JsonValue
import de.taktovia.app.identity.RequestContexts
import de.taktovia.bookingdispatch.api.BookingArea
import de.taktovia.bookingdispatch.api.BookingDetail
import de.taktovia.bookingdispatch.api.BookingTracking
import de.taktovia.bookingdispatch.api.TrackedStop
import de.taktovia.bookingdispatch.api.Bookings
import de.taktovia.bookingdispatch.api.BookingReason
import de.taktovia.bookingdispatch.api.CancelBooking
import de.taktovia.bookingdispatch.api.ConfirmBooking
import de.taktovia.bookingdispatch.api.HistoryView
import de.taktovia.app.payment.PaymentController
import de.taktovia.farepayment.api.Payments
import de.taktovia.platform.api.CursorCodec
import de.taktovia.platform.api.CursorPosition
import de.taktovia.platform.api.CursorScope
import de.taktovia.platform.api.Etag
import de.taktovia.platform.api.Paging
import de.taktovia.platform.api.Revisioned
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.time.Instant
import java.util.UUID

/** Antwort mit Ressourcenrevision (der Antwortfilter setzt daraus den ETag). */
class BookingResource(@get:JsonValue val data: Map<String, Any?>, override val revision: Long) : Revisioned

/**
 * `POST /v1/bookings` (Confirm, WP-M2-23), `GET /v1/bookings/{id}`, `GET /v1/bookings` (eigene bzw. Dienstscope-Buchungen, Cursor). Mandant und Akteur stammen aus dem Kontext, nie aus dem Body.
 * `If-Match` ist die Angebotsrevision (ETag der Hold-Antwort): fehlt er 428, ist er veraltet 412. Der `Idempotency-Key` ist Pflicht (Filter): Wiederholung liefert das gespeicherte Ergebnis.
 */
@RestController
class BookingController(private val bookings: Bookings, private val codec: CursorCodec, private val payments: Payments) {

    @PostMapping("/v1/bookings")
    @Suppress("UNCHECKED_CAST")
    fun confirm(@RequestBody body: Map<String, Any?>, @RequestHeader("If-Match", required = false) ifMatch: String?, @RequestHeader("Idempotency-Key") key: String, request: HttpServletRequest): ResponseEntity<BookingResource> {
        val expected = Etag.parseIfMatch(ifMatch)
        val max = body["max_gross"] as Map<String, Any?>
        val r = bookings.confirm(
            RequestContexts.require(request),
            ConfirmBooking(UUID.fromString(body["hold_id"] as String), expected, (max["amount_minor"] as Number).toLong(), max["currency"] as String, body["payment_reference"] as String?),
        )
        return ResponseEntity.status(HttpStatus.CREATED).location(URI.create("/v1/bookings/${r.booking.id}")).body(BookingResource(render(r.booking), r.booking.revision))
    }

    @GetMapping("/v1/bookings/{booking_id}")
    fun get(@PathVariable("booking_id") id: UUID, @RequestHeader("If-Match", required = false) ifMatch: String?, request: HttpServletRequest): BookingResource {
        val ctx = RequestContexts.require(request)
        val t = bookings.tracking(ctx, id)
        val b = t.booking
        // Ein Client, der seinen Stand mitsendet, bekommt bei veralteter Revision 412 mit dem aktuellen ETag (WP-M2-28)
        if (ifMatch != null && Etag.parseIfMatch(ifMatch) != b.revision)
            throw ProblemException(ErrorCode.REVISION_MISMATCH, "Die Buchung hat die Revision ${b.revision}; ohne If-Match neu lesen.", headers = mapOf("ETag" to Etag.format(b.revision)))
        return BookingResource(withTracking(withPayment(render(b), payments.forBooking(ctx, id), b.promise.exponent), t), b.revision)
    }

    @GetMapping("/v1/bookings")
    fun list(@RequestParam(required = false) limit: String?, @RequestParam(required = false) cursor: String?, @RequestParam(required = false) bereich: String?, request: HttpServletRequest): Map<String, Any?> {
        val ctx = RequestContexts.require(request)
        val area = bereich?.let { BookingArea.parse(it) ?: throw ProblemException.invalid(Violation("/\$query/bereich", ViolationCode.NOT_IN_ENUM)) }
        // Der Cursor gehört zur Sicht (Bereich): ein Cursor einer anderen Sicht wird abgewiesen (422)
        val scope = CursorScope(ctx.tenant.toString(), ctx.actor.subject, "bookings" + (area?.let { ":" + it.code } ?: ""))
        val lim = Paging.limit(limit); val pos = Paging.position(cursor, codec, scope)
        val rows = bookings.list(ctx, lim, pos?.let { it.createdAt to it.id }, area)
        return Paging.page(rows, lim, { CursorPosition(it.createdAt, it.id) }, codec, scope, Instant.now()).toMap { render(it) }
    }

    /**
     * `POST /v1/bookings/{id}/cancel` (WP-M2-26/-27): Storno mit Grundcode; `If-Match` (428/412) und `Idempotency-Key` sind Pflicht. Fahrgäste dürfen nur `PASSENGER_REQUEST` setzen (403)
     * und nur innerhalb der Frist des Dienstes (409 `CANCEL_DEADLINE_EXPIRED`); Betrieb im Dienstscope storniert mit jedem zulässigen Grund (fremder Dienst 404).
     */
    @PostMapping("/v1/bookings/{booking_id}/cancel")
    fun cancel(@PathVariable("booking_id") id: UUID, @RequestBody body: Map<String, Any?>, @RequestHeader("If-Match", required = false) ifMatch: String?, @RequestHeader("Idempotency-Key") key: String, request: HttpServletRequest): BookingResource {
        val expected = Etag.parseIfMatch(ifMatch)
        val reason = BookingReason.parse(body["reason"] as String) ?: throw ProblemException.invalid(Violation("/reason", ViolationCode.NOT_IN_ENUM))
        val ctx = RequestContexts.require(request)
        val r = bookings.cancel(ctx, id, CancelBooking(expected, reason))
        // Erstattungshinweis (WP-M2-31): was beauftragt ist, nie „erstattet“ vor der PSP-Bestätigung; dazu der Zahlungsblock (Stand der Zahlung, getrennt vom Buchungsstatus)
        val body = withPayment(render(r.booking), payments.forBooking(ctx, id), r.booking.promise.exponent)
        val withHint = r.refundHint?.let { h -> LinkedHashMap(body).also { it["refund_hint"] = linkedMapOf("status" to h.status, "amount" to money(h.amountMinor, h.currency, r.booking.promise.exponent)) } } ?: body
        return BookingResource(withHint, r.booking.revision)
    }

    /**
     * `GET /v1/bookings/{id}/history` (WP-M2-25): Fahrgäste sehen die reduzierte Sicht ihrer eigenen Buchung, Betriebspersonal im Dienstscope und Prüfer standardmäßig die Prüfersicht;
     * `view=audit` für Fahrgäste ist 403, ein unbekannter Wert 422.
     */
    @GetMapping("/v1/bookings/{booking_id}/history")
    fun history(@PathVariable("booking_id") id: UUID, @RequestParam(required = false) view: String?, request: HttpServletRequest): Map<String, Any?> {
        val ctx = RequestContexts.require(request)
        val v = when (view) {
            null -> if ("passenger" in ctx.roles) HistoryView.PASSENGER else HistoryView.AUDIT
            "reduced" -> HistoryView.PASSENGER
            "audit" -> HistoryView.AUDIT
            else -> throw ProblemException.invalid(Violation("/\$query/view", ViolationCode.NOT_IN_ENUM))
        }
        val items = bookings.history(ctx, id, v).map { e ->
            val m = linkedMapOf<String, Any?>(
                "revision" to e.revision, "from_state" to e.fromState, "to_state" to e.toState, "reason" to e.reason.name, "reason_text" to e.reasonText,
                "occurred_at" to e.occurredAt.toString(), "actor_kind" to e.actorKind,
            )
            e.actorRef?.let { m["actor_ref"] = it }; e.correlationId?.let { m["correlation_id"] = it.toString() }
            e.links?.let { l ->
                m["links"] = linkedMapOf<String, Any?>().also { o ->
                    l.attemptId?.let { o["attempt_id"] = it.toString() }; l.offerId?.let { o["offer_id"] = it.toString() }; l.holdId?.let { o["hold_id"] = it.toString() }
                    l.quoteId?.let { o["quote_id"] = it.toString() }; l.paymentIntentId?.let { o["payment_intent_id"] = it.toString() }
                }
            }
            m
        }
        return linkedMapOf("items" to items, "view" to if (v == HistoryView.AUDIT) "audit" else "reduced")
    }

    /** Tracking-Felder von UI-04 (WP-M2-43/-46): Treffpunkt, Plan-ETA getrennt von der Zusage, Fahrzeugidentifikation, Stornoregel, Datenstand. */
    private fun withTracking(booking: Map<String, Any?>, t: BookingTracking): Map<String, Any?> = LinkedHashMap(booking).also { m ->
        t.pickupStop?.let { m["pickup_stop"] = stop(it) }; t.dropoffStop?.let { m["dropoff_stop"] = stop(it) }
        t.eta?.let { e ->
            m["eta"] = linkedMapOf("pickup_at" to e.pickupAt.toString(), "dropoff_at" to e.dropoffAt?.toString(), "previous_pickup_at" to e.previousPickupAt?.toString(), "plan_sequence" to e.planSequence,
                "plan_revision" to e.planRevision, "data_as_of" to e.dataAsOf.toString(), "source" to e.source)
        }
        t.vehicle?.let { m["vehicle"] = linkedMapOf("label" to it.label, "vehicle_class" to it.vehicleClass) }
        t.cancellation?.let { c -> m["cancellation"] = linkedMapOf("allowed" to c.allowed, "deadline_at" to c.deadlineAt.toString(), "deadline_min" to c.deadlineMin, "deadline_defaulted" to c.deadlineDefaulted) }
        m["data_as_of"] = t.dataAsOf.toString()
    }

    private fun stop(s: TrackedStop): Map<String, Any?> = linkedMapOf(
        "stop_id" to s.stopId.toString(), "revision_id" to s.revisionId.toString(), "revision" to s.revision, "name" to s.name,
        "point" to linkedMapOf("latitude" to s.latitude, "longitude" to s.longitude), "accessibility" to JSON.readValue(s.accessibilityJson, Map::class.java),
    )

    /** Zahlungsblock (WP-M2-30): getrennt vom Buchungsstatus gelesen und angefügt; fehlt die Zahlung, fehlt der Block. */
    private fun withPayment(booking: Map<String, Any?>, p: de.taktovia.farepayment.api.PaymentView?, exponent: Int): Map<String, Any?> =
        if (p == null) booking else LinkedHashMap(booking).also { it["payment"] = PaymentController.render(p, exponent) }

    companion object {
        private val JSON = tools.jackson.databind.json.JsonMapper.builder().build()

        fun money(minor: Long, currency: String, exponent: Int) = linkedMapOf("amount_minor" to minor, "currency" to currency, "exponent" to exponent)

        fun render(b: BookingDetail): Map<String, Any?> {
            val p = b.promise
            val promise = linkedMapOf<String, Any?>(
                "pickup_earliest" to p.pickupEarliest.toString(), "pickup_latest" to p.pickupLatest.toString(), "arrival_earliest" to p.arrivalEarliest.toString(), "arrival_latest" to p.arrivalLatest.toString(),
                "gross" to money(p.grossMinor, p.currency, p.exponent), "cancellation_policy_ref" to p.cancellationPolicyRef,
            )
            p.vehicleClass?.let { promise["vehicle_class"] = it }
            p.resourceVector?.let { promise["resource_vector"] = it.toMap() }
            val m = linkedMapOf<String, Any?>(
                "id" to b.id.toString(), "booking_number" to b.bookingNumber, "service_id" to b.serviceId.toString(), "hold_id" to b.holdId.toString(), "state" to b.state,
                "created_at" to b.createdAt.toString(), "passengers" to b.passengers, "wheelchair_spaces" to b.wheelchairSpaces,
            )
            b.assistanceCodes?.let { m["assistance_codes"] = it }
            b.cancelReason?.let { m["cancel_reason"] = it.name }; b.cancelCause?.let { m["cancel_cause_class"] = it.name }
            m["bereich"] = b.area.code
            m["promise"] = promise; m["revision"] = b.revision
            return m
        }
    }
}

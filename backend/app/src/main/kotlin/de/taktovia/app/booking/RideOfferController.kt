package de.taktovia.app.booking

import de.taktovia.app.identity.RequestContexts
import de.taktovia.bookingdispatch.api.AssistanceCode
import de.taktovia.bookingdispatch.api.HoldRideOffer
import de.taktovia.bookingdispatch.api.HoldSummary
import de.taktovia.bookingdispatch.api.IssuedOffer
import de.taktovia.bookingdispatch.api.OfferStop
import de.taktovia.bookingdispatch.api.ResourceVector
import de.taktovia.bookingdispatch.api.RideOfferHold
import de.taktovia.bookingdispatch.api.RideOfferView
import de.taktovia.bookingdispatch.api.RideOffers
import de.taktovia.bookingdispatch.api.EquipmentCode
import de.taktovia.bookingdispatch.api.RideOfferCommand
import de.taktovia.bookingdispatch.api.RideOfferProposal
import de.taktovia.bookingdispatch.api.RideOfferRequests
import de.taktovia.bookingdispatch.api.RidePlace
import de.taktovia.bookingdispatch.api.RideTimeKind
import de.taktovia.bookingdispatch.api.RideTimeWindow
import de.taktovia.bookingdispatch.api.RejectionCode
import de.taktovia.bookingdispatch.api.WalkOrigin
import de.taktovia.bookingdispatch.api.WalkPlan
import de.taktovia.bookingdispatch.api.WalkSnapshot
import de.taktovia.bookingdispatch.api.WalkingRoutes
import de.taktovia.farepayment.api.QuoteView
import de.taktovia.platform.api.Etag
import de.taktovia.platform.api.Revisioned
import com.fasterxml.jackson.annotation.JsonValue
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import java.net.URI
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Antwort mit Ressourcenrevision (der Antwortfilter setzt daraus den ETag). */
class RideOfferResource(@get:JsonValue val data: Map<String, Any?>, override val revision: Long) : Revisioned

/**
 * `POST /v1/ride-offers` (WP-M2-11 bis -13), `GET /v1/ride-offers/{id}` (WP-M2-21), `POST /v1/ride-offers/{id}/hold` und `.../hold/release` (WP-M2-22). Die Folgebefehle verlangen `If-Match`
 * (428 ohne, 412 veraltet) und `Idempotency-Key` (Filter, Wiederholung liefert das gespeicherte Ergebnis). Anfrage:: Anfrage erfassen, Zeitfenster bilden, Zulässigkeit prüfen. Die Eingabe ist durch das Vertragsschema geprüft (422 am Rand); eine fachliche
 * Ablehnung ist 200 mit leerer Liste und normiertem Grund. Mandant und Akteur stammen aus dem Kontext, nie aus dem Body; der `Idempotency-Key` ist Pflicht (Filter) und
 * dient zugleich als Wiederholungsschutz des Versuchs.
 */
@RestController
class RideOfferController(private val rides: RideOfferRequests, private val offers: RideOffers, private val walking: WalkingRoutes) {

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
        val ctx = RequestContexts.require(request)
        val r = rides.request(ctx, cmd)
        // Fußweg zum Abholhaltepunkt (WP-M2-44): Weg als Snapshot des Angebots; ein Weg über `stop_walk_max_m` wird nicht angeboten (das Angebot entfällt)
        val origin = WalkOrigin(cmd.origin.point(), cmd.origin.stopId)
        val walks = r.offers.mapNotNull { p -> p.offer?.takeIf { it.pickupStop != null }?.let { o -> walking.plan(ctx, o.id, origin)?.let { plan -> p to plan } } }.toMap()
        val offered = r.offers.filter { p -> p.offer?.pickupStop == null || walks[p] is WalkPlan.Offered }
        val stored = walks.mapValues { (_, plan) -> (plan as? WalkPlan.Offered)?.let { walking.store(ctx, it.snapshot) } }
        val rejection = r.rejection ?: if (offered.isEmpty() && r.offers.isNotEmpty()) de.taktovia.bookingdispatch.api.RideRejection(RejectionCode.NO_STOP_IN_RANGE) else null
        return linkedMapOf(
            "attempt_id" to r.attemptId.toString(), "offers" to offered.map { proposal(it, stored[it]) },
            "rejection" to rejection?.let { rej ->
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

    private fun proposal(o: RideOfferProposal, walk: WalkSnapshot?): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>(
            "service_id" to o.serviceId.toString(), "booking_kind" to o.bookingKind.name, "time_kind" to o.timeKind.name, "pickup_window" to window(o.pickupWindow),
            "arrival_window" to window(o.arrivalWindow), "arrival_grace_min" to o.arrivalGraceMin, "direct_seconds" to o.directSeconds,
        )
        o.offer?.let { i -> offerBlock(i, m) }
        walk?.let { m["walk"] = walkBlock(it) }
        return m
    }

    /** Angebotsobjekt und Preisblock (WP-M2-09/-21): ID, Ablauf, Endpreis mit Komponenten, Haltepunkte, Revision. */
    private fun offerBlock(i: IssuedOffer, m: MutableMap<String, Any?>) {
        m["id"] = i.id.toString(); m["expires_at"] = i.expiresAt.toString(); m["gross"] = money(i.quote); m["fare_components"] = components(i.quote)
        m["tariff_revision"] = i.quote.tariffRevisionNo; m["cancellation_policy_ref"] = i.cancellationPolicyRef; m["revision"] = i.revision
        i.pickupStop?.let { m["pickup_stop"] = stop(it) }; i.dropoffStop?.let { m["dropoff_stop"] = stop(it) }
        i.runRevision?.let { m["run_revision"] = it }
    }

    private fun money(q: QuoteView) = linkedMapOf("amount_minor" to q.payAtPlatformMinor, "currency" to q.currency, "exponent" to q.exponent)
    private fun components(q: QuoteView) = q.components.map { c -> linkedMapOf("code" to c.code.name, "amount" to linkedMapOf("amount_minor" to c.amountMinor, "currency" to q.currency, "exponent" to q.exponent), "tariff_revision" to q.tariffRevisionNo.toString(), "description" to describe(c.code)) }
    private fun describe(c: de.taktovia.farepayment.api.FareComponentCode) = when (c) {
        de.taktovia.farepayment.api.FareComponentCode.TRANSPORT -> "Fahrpreis (netto)"
        de.taktovia.farepayment.api.FareComponentCode.SURCHARGE -> "Buchungsgebühr (netto)"
        de.taktovia.farepayment.api.FareComponentCode.TAX -> "Umsatzsteuer (enthalten)"
        de.taktovia.farepayment.api.FareComponentCode.DISCOUNT -> "Rabatt"
        de.taktovia.farepayment.api.FareComponentCode.SUBSIDY -> "Zuschuss"
        de.taktovia.farepayment.api.FareComponentCode.COMMISSION -> "Provision"
    }

    private fun stop(s: OfferStop) = linkedMapOf("stop_id" to s.stopId.toString(), "revision_id" to s.revisionId.toString())

    // ---- Angebotsobjekt, Hold, Freigabe ---------------------------------------------------------------------------------------------

    @GetMapping("/v1/ride-offers/{ride_offer_id}")
    fun get(@PathVariable("ride_offer_id") id: UUID, request: HttpServletRequest): RideOfferResource {
        val ctx = RequestContexts.require(request)
        val v = offers.get(ctx, id)
        val m = LinkedHashMap(view(v)); walking.forOffer(ctx, id)?.let { m["walk"] = walkBlock(it) }
        return RideOfferResource(m, v.revision)
    }

    @PostMapping("/v1/ride-offers/{ride_offer_id}/hold")
    @Suppress("UNCHECKED_CAST")
    fun hold(@PathVariable("ride_offer_id") id: UUID, @RequestHeader("If-Match", required = false) ifMatch: String?, @RequestHeader("Idempotency-Key") key: String,
             @RequestBody body: Map<String, Any?>, request: HttpServletRequest): ResponseEntity<RideOfferResource> {
        val expected = Etag.parseIfMatch(ifMatch)
        val max = body["max_gross"] as Map<String, Any?>
        val r = offers.hold(RequestContexts.require(request), id, HoldRideOffer(expected, key, (max["amount_minor"] as Number).toLong(), max["currency"] as String))
        return ResponseEntity.status(HttpStatus.CREATED).location(URI.create("/v1/ride-offers/$id")).body(RideOfferResource(holdBlock(r.hold), r.offerRevision))
    }

    @PostMapping("/v1/ride-offers/{ride_offer_id}/hold/release")
    fun release(@PathVariable("ride_offer_id") id: UUID, @RequestHeader("If-Match", required = false) ifMatch: String?, request: HttpServletRequest): RideOfferResource {
        val r: RideOfferHold = offers.release(RequestContexts.require(request), id, Etag.parseIfMatch(ifMatch))
        return RideOfferResource(holdBlock(r.hold), r.offerRevision)
    }

    private fun holdBlock(h: HoldSummary): Map<String, Any?> = linkedMapOf("id" to h.id.toString(), "state" to h.state, "expires_at" to h.expiresAt.toString(), "capacity_vector" to vector(h.vector))

    private fun vector(v: ResourceVector): Map<String, Any?> = v.toMap()

    private fun view(v: RideOfferView): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>(
            "id" to v.id.toString(), "attempt_id" to v.attemptId.toString(), "service_id" to v.serviceId.toString(), "state" to v.state.name,
            "pickup_window" to window(v.pickupWindow), "arrival_window" to window(v.arrivalWindow), "expires_at" to v.expiresAt.toString(),
        )
        v.runRevision?.let { m["run_revision"] = it }
        m["gross"] = money(v.quote); m["fare_components"] = components(v.quote); m["tariff_revision"] = v.quote.tariffRevisionNo; m["cancellation_policy_ref"] = v.cancellationPolicyRef
        m["demand"] = vector(v.demand); m["hold"] = v.hold?.let { holdBlock(it) }; m["revision"] = v.revision
        v.pickupStop?.let { m["pickup_stop"] = stop(it) }; v.dropoffStop?.let { m["dropoff_stop"] = stop(it) }
        return m
    }

    companion object {
        /** Fußwegblock (Schema `RideWalkRoute`) von Angebot und Buchung: Geometrie als Punktliste, nummerierte Wegschritte, Rückfallgrund. */
        fun walkBlock(w: WalkSnapshot): Map<String, Any?> = linkedMapOf(
            "status" to w.status.name, "snapshot_id" to w.id.toString(), "distance_m" to w.distanceM, "duration_s" to w.durationS, "profile" to w.profile, "map_version" to w.mapVersion,
            "fallback_reason" to w.fallbackReason, "geometry" to w.geometry.map { linkedMapOf("latitude" to it.latitude, "longitude" to it.longitude) },
            "steps" to w.steps.map { linkedMapOf("seq" to it.seq, "maneuver" to it.maneuver.name, "distance_m" to it.distanceM, "duration_s" to it.durationS, "instruction" to it.instruction) },
            "pickup_stop_name" to w.pickupStopName, "computed_at" to w.computedAt.toString(),
        )
    }
}

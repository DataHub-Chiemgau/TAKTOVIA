package de.taktovia.bookingdispatch.internal

import de.taktovia.bookingdispatch.api.RejectionCode
import de.taktovia.bookingdispatch.api.RidePlace
import de.taktovia.platform.context.RequestContext
import de.taktovia.servicegeo.api.GeoPoint
import de.taktovia.servicegeo.api.RelationEnd
import de.taktovia.servicegeo.api.RelationRejection
import de.taktovia.servicegeo.api.ServicegeoApi
import de.taktovia.servicegeo.api.StopFilter
import de.taktovia.servicegeo.api.StopView
import de.taktovia.servicegeo.api.ZoneRole
import java.time.Instant
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Vorprüfung vor der Fahrzeugsuche (WP-M2-12, LH-6.5-OPT-001; Kap. 01 §1.4 Schritt 1 und 2) über die Fachoperationen von `service-geo`. **Lesend und ohne Kapazitätswirkung:**
 * kein Routing-Aufruf, kein Hold, keine Planrevision. Alle Prüfungen laufen immer (nicht nur bis zum ersten Fehler), damit Sekundärgründe vollständig sind; welcher Grund
 * der primäre ist, entscheidet allein die Priorität im Katalog ([RejectionCode.decide]).
 *
 * Reihenfolge und Zuordnung (Vorschlag, OP-14): (1) Betriebszeit zu Abholbeginn **und** Ankunftsende -> `OUTSIDE_HOURS`; (2) Start/Ziel im zum Fahrtzeitpunkt wirksamen Gebiet und
 * (3) Relationsregel in **einer** Momentaufnahme ([de.taktovia.servicegeo.api.RelationRules.check]) -> `OUTSIDE_AREA`/`RULE_FORBIDDEN`; (4) buchbare Haltepunkte in `stop_walk_max_m`
 * -> `NO_STOP_IN_RANGE`; bei Rollstuhlbedarf nur Haltepunkte mit `wheelchair_boarding = YES` (`UNVERIFIED` ist nicht `YES`, OP-41) -> `NO_ACCESSIBLE_STOP`.
 * Ein fester Haltepunkt der Anfrage (`stop_id`) hat Fußweg 0 und muss zum Zeitpunkt buchbar sein.
 */
internal class GeoEligibilityCheck(private val geo: ServicegeoApi) : EligibilityCheck {

    override fun check(ctx: RequestContext, request: EligibilityRequest): EligibilityResult {
        val causes = mutableListOf<RejectionCode>()
        val s = request.serviceId; val at = request.pickup.earliestAt

        // (1) Betriebszeit
        val opensAtPickup = geo.availability.at(ctx, s, request.pickup.earliestAt).inOperation
        val opensAtArrival = geo.availability.at(ctx, s, request.arrival.latestAt).inOperation
        if (!opensAtPickup || !opensAtArrival) causes += RejectionCode.OUTSIDE_HOURS

        // (2) + (3) Gebiet und Relationsregel, ein Snapshot
        val relation = geo.relations.check(ctx, s, request.origin.point(), RelationEnd(point = request.destination.point(), stopId = request.destination.stopId), at)
        if (!relation.admissible) causes += when (relation.reason) {
            RelationRejection.OUTSIDE_AREA -> RejectionCode.OUTSIDE_AREA
            RelationRejection.RULE_FORBIDDEN, null -> RejectionCode.RULE_FORBIDDEN
        }

        // (4) Haltepunkte in Reichweite, bei Rollstuhlbedarf nur geeignete
        val pickup = stopFor(ctx, s, request.origin, at, request)
        val dropoff = stopFor(ctx, s, request.destination, request.arrival.latestAt, request)
        causes += pickup.causes; causes += dropoff.causes

        return EligibilityResult(causes.distinct(), pickup.stop?.stopId, coarse(ctx, s, request.origin.point(), at, ZoneRole.ORIGIN), coarse(ctx, s, request.destination.point(), at, ZoneRole.DESTINATION))
    }

    private class StopChoice(val stop: StopView?, val causes: List<RejectionCode>)

    private fun stopFor(ctx: RequestContext, serviceId: UUID, place: RidePlace, at: Instant, r: EligibilityRequest): StopChoice {
        val candidates: List<StopView> =
            if (place.stopId != null) listOfNotNull(geo.stops.bookable(ctx, serviceId, place.stopId, at))
            else geo.stops.list(ctx, StopFilter(serviceId = serviceId, at = at, near = place.point(), nearRadiusMeters = r.params.stopWalkMaxM), MAX_STOPS, null)
        if (candidates.isEmpty()) return StopChoice(null, listOf(RejectionCode.NO_STOP_IN_RANGE))
        val suitable = if (r.needsWheelchair) candidates.filter { wheelchairSuitable(it) } else candidates
        if (suitable.isEmpty()) return StopChoice(null, listOf(RejectionCode.NO_ACCESSIBLE_STOP))
        return StopChoice(suitable.minWith(compareBy({ meters(place.point(), it.point) }, { it.stopId })), emptyList())
    }

    /** „Geeignet“ ist ausschließlich `wheelchair_boarding = YES`; `NO` und `UNVERIFIED` werden nie angeboten (OP-41, AT-04 Teil „Stopp zulässig“). */
    private fun wheelchairSuitable(s: StopView) = s.accessibility["wheelchair_boarding"] == "YES"

    /** Gröbste Ortsangabe für `travel_attempt` (OP-13, Arbeitsannahme): Kennung des wirksamen Bediengebiets oder `outside`, nie Koordinate oder Adresse. */
    private fun coarse(ctx: RequestContext, serviceId: UUID, p: GeoPoint, at: Instant, role: ZoneRole): String =
        geo.areas.covering(ctx, serviceId, p, at, role)?.let { "area:${it.id}" } ?: "outside"

    private fun meters(a: GeoPoint, b: GeoPoint): Double {
        val r = 6_371_000.0
        val p1 = Math.toRadians(a.latitude); val p2 = Math.toRadians(b.latitude)
        val dp = p2 - p1; val dl = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * r * atan2(sqrt(h), sqrt(1 - h))
    }

    private companion object { const val MAX_STOPS = 200 }
}

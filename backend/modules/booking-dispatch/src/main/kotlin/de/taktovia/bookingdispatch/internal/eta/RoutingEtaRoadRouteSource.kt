package de.taktovia.bookingdispatch.internal.eta

import de.taktovia.bookingdispatch.api.EtaRoadDataVersionException
import de.taktovia.bookingdispatch.api.EtaRoadRoute
import de.taktovia.bookingdispatch.api.EtaRoadRouteSource
import de.taktovia.bookingdispatch.api.RoutingPort
import de.taktovia.platform.context.RequestContext
import java.time.Instant

/**
 * Straßendatenquelle auf dem Routingport (WP-M2-10): Zeit und Strecke des Anbieters, Kartendatenversion als Straßendatenversion. Der Worker liefert keine Geschwindigkeitsattribute,
 * deshalb ist `segments` `null` (die Prognose bleibt fail-closed, bis D-09/der Anbieter sie liefert). Eine angeforderte, vom Anbieter nicht gelieferte Version ist ein Versionsfehler.
 */
internal class RoutingEtaRoadRouteSource(private val routing: RoutingPort) : EtaRoadRouteSource {
    override fun route(ctx: RequestContext, originStop: String, destinationStop: String, vehicleProfile: String, at: Instant, roadDataVersion: String?): EtaRoadRoute {
        val r = routing.route(ctx, originStop, destinationStop, vehicleProfile)
        if (roadDataVersion != null && r.mapVersion != roadDataVersion) throw EtaRoadDataVersionException("Straßendatenversion ${r.mapVersion} statt $roadDataVersion")
        return EtaRoadRoute(r.mapVersion, r.timeSeconds, r.distanceMeters, null, r.provider)
    }
}

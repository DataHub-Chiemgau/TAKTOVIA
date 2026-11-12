package de.taktovia.bookingdispatch.internal.eta

import de.taktovia.bookingdispatch.api.EtaFallbackReason
import de.taktovia.bookingdispatch.api.EtaModelRevisionView
import de.taktovia.bookingdispatch.api.EtaModelRevisions
import de.taktovia.bookingdispatch.api.EtaReferenceModel
import de.taktovia.bookingdispatch.api.EtaReferenceRequest
import de.taktovia.bookingdispatch.api.EtaReferenceTime
import de.taktovia.bookingdispatch.api.EtaRoadDataVersionException
import de.taktovia.bookingdispatch.api.EtaRoadRouteSource
import de.taktovia.bookingdispatch.api.EtaSpeedRules
import de.taktovia.bookingdispatch.api.RoutingException
import de.taktovia.bookingdispatch.api.RoutingFailure
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.tenant.ExternalCallGuard

/**
 * Fahrerneutrale Referenzfahrzeit = rechtmäßige Routingzeit des Routingadapters, nie unter der rechtmäßigen Mindestfahrzeit der Routendaten (liefert der Anbieter eine kürzere Zeit
 * als Länge/Limit erlaubt, gilt die Mindestfahrzeit). Reine Funktion von Strecke, Zeit, Fahrzeugprofil, Straßendatenversion und Modellrevision: kein Zugriff auf Fahrerdaten, keine Uhr,
 * kein Zufall. Der Routingaufruf läuft außerhalb jeder Datenbanktransaktion (Leitlinie 3); die Modellrevision wird zuvor in eigener Lesetransaktion gelesen.
 */
internal class DefaultEtaReferenceModel(private val revisions: EtaModelRevisions, private val roads: EtaRoadRouteSource) : EtaReferenceModel {
    override fun reference(ctx: RequestContext, request: EtaReferenceRequest): EtaReferenceTime {
        val model = revisions.current(ctx) ?: return fallback(null, EtaFallbackReason.NO_APPROVED_MODEL, request.roadDataVersion)
        return compute(ctx, model, request)
    }

    /** Referenzzeit mit einer bereits gelesenen Modellrevision (die Prognose liest Revision und Konfiguration einmal, damit jede Prognose genau eine Version trägt). */
    fun compute(ctx: RequestContext, model: EtaModelRevisionView, request: EtaReferenceRequest): EtaReferenceTime {
        ExternalCallGuard.requireNone("Routingaufruf der Referenzfahrzeit")
        val route = try {
            roads.route(ctx, request.originStop, request.destinationStop, request.vehicleProfile, request.at, request.roadDataVersion)
        } catch (e: EtaRoadDataVersionException) {
            return fallback(model, EtaFallbackReason.ROAD_DATA_VERSION_MISMATCH, request.roadDataVersion)
        } catch (e: RoutingException) {
            return fallback(model, when (e.failure) {
                RoutingFailure.NO_ROUTE -> EtaFallbackReason.NO_ROUTE
                RoutingFailure.NOT_A_STOP, RoutingFailure.UNKNOWN_PROFILE -> EtaFallbackReason.ROUTING_REQUEST_INVALID
                RoutingFailure.PROVIDER_TIMEOUT -> EtaFallbackReason.ROUTING_TIMEOUT
                RoutingFailure.PROVIDER_UNAVAILABLE -> EtaFallbackReason.ROUTING_UNAVAILABLE
            }, request.roadDataVersion)
        }
        if (request.roadDataVersion != null && route.roadDataVersion != request.roadDataVersion) return fallback(model, EtaFallbackReason.ROAD_DATA_VERSION_MISMATCH, route.roadDataVersion)
        if (model.roadDataVersion != null && model.roadDataVersion != route.roadDataVersion) return fallback(model, EtaFallbackReason.ROAD_DATA_VERSION_NOT_APPROVED, route.roadDataVersion)
        if (route.timeSeconds < 0 || route.distanceMeters < 0) return fallback(model, EtaFallbackReason.ROUTE_DATA_INCONSISTENT, route.roadDataVersion)
        val legalMin = route.segments?.let { EtaSpeedRules.legalMinimumSeconds(it) }
        return EtaReferenceTime(
            timeSeconds = maxOf(route.timeSeconds, legalMin ?: 0), distanceMeters = route.distanceMeters, legalMinimumSeconds = legalMin, modelVersion = model.version,
            roadDataVersion = route.roadDataVersion, provider = route.provider, segments = route.segments, fallbackReason = null,
        )
    }

    private fun fallback(model: EtaModelRevisionView?, reason: EtaFallbackReason, roadDataVersion: String?) =
        EtaReferenceTime(null, null, null, model?.version, roadDataVersion, null, null, reason)
}

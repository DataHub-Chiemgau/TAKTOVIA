package de.taktovia.bookingdispatch.internal

import de.taktovia.bookingdispatch.api.CandidateRejection
import de.taktovia.bookingdispatch.api.CandidateRequest
import de.taktovia.bookingdispatch.api.CandidateSearch
import de.taktovia.bookingdispatch.api.ExcludedVehicle
import de.taktovia.bookingdispatch.api.ExclusionReason
import de.taktovia.bookingdispatch.api.PlannedPositionSource
import de.taktovia.bookingdispatch.api.RejectionCategory
import de.taktovia.bookingdispatch.api.ResourceAvailability
import de.taktovia.bookingdispatch.api.ResourceVector
import de.taktovia.bookingdispatch.api.RoutingException
import de.taktovia.bookingdispatch.api.RoutingFailure
import de.taktovia.bookingdispatch.api.RoutingPort
import de.taktovia.bookingdispatch.api.Unusable
import de.taktovia.bookingdispatch.api.VehicleAvailability
import de.taktovia.bookingdispatch.api.VehicleCandidate
import de.taktovia.bookingdispatch.api.VehicleCandidates
import de.taktovia.platform.context.RequestContext
import java.time.Duration

internal class VehicleCandidateFilter(
    private val resources: ResourceAvailability, private val routing: RoutingPort?, private val positions: PlannedPositionSource,
) : VehicleCandidates {

    override fun find(ctx: RequestContext, request: CandidateRequest): CandidateSearch {
        val snap = resources.usableVehicles(ctx, request.serviceId, request.window, request.locations)
        val excluded = snap.excluded.map { ExcludedVehicle(it.vehicleId, it.reason.toExclusion()) }.toMutableList()
        val candidates = mutableListOf<VehicleCandidate>()
        for (v in snap.vehicles) {
            val capacity = ResourceVector.parse(v.capacityConfig, "/capacity_config")
            val reason = capacityReason(capacity, request.demand)
            if (reason != null) { excluded += ExcludedVehicle(v.vehicleId, reason); continue }
            val pos = positions.positionOf(ctx, v.vehicleId, v.shiftId, request.window.from)
            if (pos == null) { excluded += ExcludedVehicle(v.vehicleId, ExclusionReason.POSITION_UNKNOWN); continue }
            val seconds = travelSeconds(ctx, pos.stopId, request)
            val arrival = seconds?.let { pos.availableAt.plus(Duration.ofSeconds(it.toLong())) }
            // erreichbar: Ankunft spätestens zum spätesten Abholzeitpunkt und innerhalb der Schicht
            if (seconds == null || arrival!!.isAfter(request.pickupLatest) || arrival.isAfter(v.shiftEndsAt)) excluded += ExcludedVehicle(v.vehicleId, ExclusionReason.PICKUP_UNREACHABLE)
            else candidates += VehicleCandidate(v, pos, seconds, arrival)
        }
        candidates.sortWith(compareBy({ it.estimatedArrival }, { it.resource.vehicleId }))
        excluded.sortBy { it.vehicleId }
        return CandidateSearch(candidates, excluded, if (candidates.isEmpty()) rejection(excluded) else null)
    }

    /** Wie im Hold (WP-M2-14): fehlende Ausstattung oder fehlende Dimension Rollstuhl ist ein Ausstattungsmangel, zu kleine Zähler sind Kapazität. */
    private fun capacityReason(capacity: ResourceVector, demand: ResourceVector): ExclusionReason? = when {
        !capacity.equipment.containsAll(demand.equipment) || (demand.wheelchair > 0 && capacity.wheelchair == 0) -> ExclusionReason.EQUIPMENT_MISSING
        !capacity.covers(demand) -> ExclusionReason.CAPACITY_INSUFFICIENT
        else -> null
    }

    /** `null` = keine Route. Gleicher Haltepunkt: 0 s. Andere Routingfehler sind technisch und werden weitergereicht (OPT-011). */
    private fun travelSeconds(ctx: RequestContext, from: String, r: CandidateRequest): Int? {
        if (from == r.pickupStop) return 0
        val port = routing ?: throw IllegalStateException("Routing nicht konfiguriert: Erreichbarkeit nicht prüfbar")
        return try { port.route(ctx, from, r.pickupStop, r.profile).timeSeconds } catch (e: RoutingException) { if (e.failure == RoutingFailure.NO_ROUTE) null else throw e }
    }

    private fun rejection(excluded: List<ExcludedVehicle>): CandidateRejection {
        val ordered = excluded.map { it.reason }.sortedByDescending { it.depth }
        // Poolleer: kein Fahrzeug überhaupt, Kapazitätsablehnung
        val primary = ordered.firstOrNull()?.category ?: RejectionCategory.CAPACITY
        return CandidateRejection(primary, ordered.map { it.category }.filter { it != primary }.distinct())
    }

    private fun Unusable.toExclusion() = when (this) {
        Unusable.VEHICLE_BLOCKED, Unusable.VEHICLE_INACTIVE -> ExclusionReason.VEHICLE_BLOCKED
        Unusable.NO_SHIFT -> ExclusionReason.NO_SHIFT
        Unusable.NO_VALID_RELEASE -> ExclusionReason.NO_VALID_RELEASE
        // vom Leseport für Poolmitglieder nicht geliefert; konservativ als nicht einsetzbar
        Unusable.OUTSIDE_SERVICE_AREA -> ExclusionReason.OUTSIDE_SERVICE_AREA
        Unusable.NOT_IN_POOL, Unusable.UNKNOWN_VEHICLE -> ExclusionReason.VEHICLE_BLOCKED
    }
}

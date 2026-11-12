package de.taktovia.bookingdispatch.internal

import de.taktovia.bookingdispatch.api.CandidateGate
import de.taktovia.bookingdispatch.api.CandidateGateResult
import de.taktovia.bookingdispatch.api.CandidateRequest
import de.taktovia.bookingdispatch.api.CandidateSearchRequest
import de.taktovia.bookingdispatch.api.RejectionCategory
import de.taktovia.bookingdispatch.api.RejectionCode
import de.taktovia.bookingdispatch.api.TimeWindow
import de.taktovia.bookingdispatch.api.VehicleCandidates
import de.taktovia.platform.context.RequestContext

/**
 * Tor zur Kandidatensuche über den Fahrzeugkandidatenfilter (WP-M2-15). Die Kategorien der Filter-Ablehnung werden auf die Katalogcodes abgebildet (Ausstattung, Personal,
 * Kapazität; Primärgrund und Nebengründe des Filters bleiben als Ursachen erhalten). Routingfehler außer „keine Route“ sind technisch und werden als Ausnahme weitergereicht.
 * Ohne festen Abholhaltepunkt ist die Erreichbarkeit nicht prüfbar: die Anfrage erreicht die Suche (sie entscheidet später die Einfügeheuristik).
 */
internal class VehicleCandidateGate(private val candidates: VehicleCandidates) : CandidateGate {
    override fun check(ctx: RequestContext, request: CandidateSearchRequest): CandidateGateResult {
        val stop = request.pickupStopId ?: return CandidateGateResult.Reached
        val window = TimeWindow(request.pickupWindow.earliestAt, request.arrivalWindow.latestAt)
        val search = candidates.find(ctx, CandidateRequest(request.serviceId, window, request.demand, stop.toString(), request.pickupWindow.latestAt))
        if (search.candidates.isNotEmpty()) return CandidateGateResult.Reached
        val rejection = search.rejection ?: return CandidateGateResult.Reached
        return CandidateGateResult.NoCandidate((listOf(rejection.primary) + rejection.secondary).map { it.toCode() })
    }

    private fun RejectionCategory.toCode() = when (this) {
        RejectionCategory.EQUIPMENT -> RejectionCode.EQUIPMENT_MISSING
        RejectionCategory.PERSONNEL -> RejectionCode.NO_STAFF
        RejectionCategory.CAPACITY -> RejectionCode.NO_CAPACITY
    }
}

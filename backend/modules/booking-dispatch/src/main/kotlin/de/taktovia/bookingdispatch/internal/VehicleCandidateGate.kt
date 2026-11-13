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
        search.rejection ?: return CandidateGateResult.Reached
        return CandidateGateResult.NoCandidate(causes(search))
    }

    /**
     * Zuordnungsregel Kandidatenfilter -> Ablehnungscode (WP-M4-49): je ausgeschlossenem Fahrzeug der Code seines Ausschlussgrunds, ohne Wiederholung; leerer Pool = [RejectionCode.NO_VEHICLE] („kein Fahrzeug“), gesperrt, zu kleine Zähler oder nicht erreichbar = [RejectionCode.NO_CAPACITY] („keine Kapazität“), Schicht oder Freigabe fehlt = [RejectionCode.NO_STAFF],
     * Schicht endet vor der Abholung = [RejectionCode.SHIFT_ENDED], Ausstattung = [RejectionCode.EQUIPMENT_MISSING]. Den Primärgrund wählt allein die Priorität im Katalog ([RejectionCode.decide]).
     */
    private fun causes(search: de.taktovia.bookingdispatch.api.CandidateSearch): List<RejectionCode> {
        val rejection = search.rejection!!
        if (search.excluded.isEmpty()) {
            // leerer Pool: der Filter meldet Kapazität ohne Nebengründe; das ist „kein Fahrzeug“. Sonst gilt die Kategorie der Filter-Ablehnung (Stand WP-M2-13).
            if (rejection.primary == RejectionCategory.CAPACITY && rejection.secondary.isEmpty()) return listOf(RejectionCode.NO_VEHICLE)
            return (listOf(rejection.primary) + rejection.secondary).map { it.toCode() }
        }
        return search.excluded.map { it.reason.toCode() }.distinct()
    }

    private fun RejectionCategory.toCode() = when (this) {
        RejectionCategory.EQUIPMENT -> RejectionCode.EQUIPMENT_MISSING
        RejectionCategory.PERSONNEL -> RejectionCode.NO_STAFF
        RejectionCategory.CAPACITY -> RejectionCode.NO_CAPACITY
    }

    private fun de.taktovia.bookingdispatch.api.ExclusionReason.toCode() = when (this) {
        de.taktovia.bookingdispatch.api.ExclusionReason.NO_SHIFT, de.taktovia.bookingdispatch.api.ExclusionReason.NO_VALID_RELEASE, de.taktovia.bookingdispatch.api.ExclusionReason.SERVICE_STATUS -> RejectionCode.NO_STAFF
        de.taktovia.bookingdispatch.api.ExclusionReason.SHIFT_ENDED_IN_WINDOW -> RejectionCode.SHIFT_ENDED
        de.taktovia.bookingdispatch.api.ExclusionReason.EQUIPMENT_MISSING -> RejectionCode.EQUIPMENT_MISSING
        de.taktovia.bookingdispatch.api.ExclusionReason.VEHICLE_BLOCKED, de.taktovia.bookingdispatch.api.ExclusionReason.CAPACITY_INSUFFICIENT, de.taktovia.bookingdispatch.api.ExclusionReason.POSITION_UNKNOWN,
        de.taktovia.bookingdispatch.api.ExclusionReason.OUTSIDE_SERVICE_AREA, de.taktovia.bookingdispatch.api.ExclusionReason.PICKUP_UNREACHABLE -> RejectionCode.NO_CAPACITY
    }
}

package de.taktovia.bookingdispatch.internal

import de.taktovia.bookingdispatch.api.RejectionCode
import de.taktovia.bookingdispatch.api.RideOfferParameters
import de.taktovia.bookingdispatch.api.RidePlace
import de.taktovia.bookingdispatch.api.RideTimeWindow
import de.taktovia.platform.context.RequestContext
import java.util.UUID

/** Eingang der Vorprüfung: Dienst, Orte, die Zeitfenster der Anfrage und der Rollstuhlbedarf. */
internal class EligibilityRequest(
    val serviceId: UUID, val origin: RidePlace, val destination: RidePlace, val pickup: RideTimeWindow, val arrival: RideTimeWindow,
    val needsWheelchair: Boolean, val params: RideOfferParameters,
)

/**
 * Ergebnis: alle festgestellten Ursachen (leer = zulässig), der gewählte Abholhaltepunkt und die gröbsten Gebietskennungen für `travel_attempt` (nie Koordinaten oder Adressen).
 */
internal class EligibilityResult(val causes: List<RejectionCode>, val pickupStopId: UUID? = null, val coarseOrigin: String? = null, val coarseDestination: String? = null)

internal fun interface EligibilityCheck {
    fun check(ctx: RequestContext, request: EligibilityRequest): EligibilityResult
}

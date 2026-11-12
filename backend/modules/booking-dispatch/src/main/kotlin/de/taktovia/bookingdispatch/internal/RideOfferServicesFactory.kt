package de.taktovia.bookingdispatch.internal

import de.taktovia.bookingdispatch.api.CandidateGate
import de.taktovia.bookingdispatch.api.DirectTravelTime
import de.taktovia.bookingdispatch.api.RideOfferRequests
import de.taktovia.config.api.EffectiveConfig
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.outbox.Outbox
import de.taktovia.servicegeo.api.ServicegeoApi
import java.time.Clock

/** Nur innerhalb des Moduls `booking-dispatch` sichtbar (durch Architekturtest erzwungen). */
internal object RideOfferServicesFactory {
    fun create(ctxTx: ContextTransactions, outbox: Outbox, geo: ServicegeoApi, config: EffectiveConfig, clock: Clock, travelTime: DirectTravelTime, gate: CandidateGate): RideOfferRequests =
        JdbcRideOfferRequests(ctxTx, outbox, geo, config, clock, travelTime, gate, GeoEligibilityCheck(geo))
}

package de.taktovia.bookingdispatch.internal

import de.taktovia.bookingdispatch.api.CandidateGate
import de.taktovia.bookingdispatch.api.DirectDistance
import de.taktovia.bookingdispatch.api.DirectTravelTime
import de.taktovia.bookingdispatch.api.HoldExpiry
import de.taktovia.bookingdispatch.api.OfferPolicy
import de.taktovia.bookingdispatch.api.RideOfferRequests
import de.taktovia.bookingdispatch.api.RideOffers
import de.taktovia.config.api.EffectiveConfig
import de.taktovia.farepayment.api.FareQuotes
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.tenant.TenantTransactions
import de.taktovia.servicegeo.api.ServicegeoApi
import java.time.Clock
import java.time.Duration

/** Nur innerhalb des Moduls `booking-dispatch` sichtbar (durch Architekturtest erzwungen). */
internal object RideOfferServicesFactory {
    fun create(
        ctxTx: ContextTransactions, outbox: Outbox, geo: ServicegeoApi, config: EffectiveConfig, clock: Clock, travelTime: DirectTravelTime, gate: CandidateGate,
        quotes: FareQuotes?, policy: OfferPolicy, distance: DirectDistance,
    ): RideOfferRequests = JdbcRideOfferRequests(ctxTx, outbox, geo, config, clock, travelTime, gate, GeoEligibilityCheck(geo), quotes, policy, distance)

    private fun holds(ctxTx: ContextTransactions, outbox: Outbox, clock: Clock, staffing: de.taktovia.platform.readports.ShiftStaffingReadPort? = null) =
        JdbcCapacityHolds(ctxTx, JdbcResourceAvailability(ctxTx, clock, staffing), outbox, clock, JdbcRunPlans(ctxTx, outbox, clock))

    fun offers(ctxTx: ContextTransactions, outbox: Outbox, quotes: FareQuotes, config: EffectiveConfig?, clock: Clock, holdTtl: Duration, staffing: de.taktovia.platform.readports.ShiftStaffingReadPort? = null): RideOffers =
        JdbcRideOffers(ctxTx, outbox, quotes, holds(ctxTx, outbox, clock, staffing), config, clock, holdTtl)

    fun expiry(tx: TenantTransactions, outbox: Outbox, clock: Clock): HoldExpiry {
        // der Job braucht nur die Lock-und-Ablauf-Logik des Holds, keinen Leseport: Einsatzprüfung und Planung sind hier unbenutzt
        val ctxTx = ContextTransactions(tx, object : de.taktovia.platform.context.ContextRevalidator {
            override fun revalidate(jdbc: org.springframework.jdbc.core.JdbcOperations, ctx: de.taktovia.platform.context.RequestContext, lock: Boolean) = Unit
        })
        return JdbcHoldExpiry(tx, holds(ctxTx, outbox, clock))
    }
}

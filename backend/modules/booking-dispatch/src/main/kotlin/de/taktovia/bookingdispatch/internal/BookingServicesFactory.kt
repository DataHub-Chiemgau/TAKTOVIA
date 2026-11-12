package de.taktovia.bookingdispatch.internal

import de.taktovia.bookingdispatch.api.BookingdispatchApi
import de.taktovia.bookingdispatch.api.Bookings
import de.taktovia.config.api.EffectiveConfig
import de.taktovia.config.api.ImpactProvider
import de.taktovia.farepayment.api.FareQuotes
import de.taktovia.journeyfederation.api.JourneyShells
import de.taktovia.bookingdispatch.api.CapacityHolds
import de.taktovia.bookingdispatch.api.ConfirmedRideAudit
import de.taktovia.bookingdispatch.api.InsertionService
import de.taktovia.bookingdispatch.api.InsertionWorker
import de.taktovia.bookingdispatch.api.PlannedPositionSource
import de.taktovia.bookingdispatch.api.ResourceAvailability
import de.taktovia.bookingdispatch.api.VehicleCandidates
import de.taktovia.bookingdispatch.api.RoutingPort
import de.taktovia.bookingdispatch.api.RunPlans
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.outbox.Outbox
import java.time.Clock
import java.time.Duration

/** Nur innerhalb des Moduls `booking-dispatch` sichtbar (durch Architekturtest erzwungen). */
internal object BookingServicesFactory {
    fun bookings(ctxTx: ContextTransactions, outbox: Outbox, quotes: FareQuotes, clock: Clock, config: EffectiveConfig?): Bookings {
        val journeys = JourneyShells.create()
        val plans = JdbcRunPlans(ctxTx, outbox, clock)
        val holds = JdbcCapacityHolds(ctxTx, JdbcResourceAvailability(ctxTx, clock), outbox, clock, plans, journeys)
        return JdbcBookings(ctxTx, quotes, holds, BookingTransitions(outbox, clock, journeys), plans, outbox, config, clock)
    }

    fun bookingImpactProvider(): ImpactProvider = BookingImpactProvider()

    fun create(ctxTx: ContextTransactions, clock: Clock, outbox: Outbox, routingWorkerUrl: String?, routingBudgetMillis: Long, plannedPositions: PlannedPositionSource, insertionWorker: InsertionWorker? = null): BookingdispatchApi {
        val resources = JdbcResourceAvailability(ctxTx, clock)
        val routingPort: RoutingPort? = routingWorkerUrl?.let { CachingRoutingPort(ctxTx, HttpRoutingClient(it, Duration.ofMillis(routingBudgetMillis))) }
        val runPlans = JdbcRunPlans(ctxTx, outbox, clock)
        val insertionService: InsertionService? = routingPort?.let { rp ->
            (insertionWorker ?: routingWorkerUrl?.let { HttpInsertionClient(it) })?.let { InsertionService(rp, it) }
        }
        return object : BookingdispatchApi {
            override val resources: ResourceAvailability = resources
            override val routing: RoutingPort? = routingPort
            override val holds: CapacityHolds = JdbcCapacityHolds(ctxTx, resources, outbox, clock, runPlans)
            override val candidates: VehicleCandidates = VehicleCandidateFilter(resources, routingPort, plannedPositions)
            override val runs: RunPlans = runPlans
            override val insertion: InsertionService? = insertionService
            override val audit: ConfirmedRideAudit = JdbcConfirmedRideAudit(ctxTx, runPlans)
            override val tripPlans: de.taktovia.platform.readports.TripPlanReadPort = JdbcTripPlanReadPort(ctxTx)
        }
    }
}

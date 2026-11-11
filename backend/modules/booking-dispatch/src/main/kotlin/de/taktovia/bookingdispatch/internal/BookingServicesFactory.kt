package de.taktovia.bookingdispatch.internal

import de.taktovia.bookingdispatch.api.BookingdispatchApi
import de.taktovia.bookingdispatch.api.CapacityHolds
import de.taktovia.bookingdispatch.api.ResourceAvailability
import de.taktovia.bookingdispatch.api.RoutingPort
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.outbox.Outbox
import java.time.Clock
import java.time.Duration

/** Nur innerhalb des Moduls `booking-dispatch` sichtbar (durch Architekturtest erzwungen). */
internal object BookingServicesFactory {
    fun create(ctxTx: ContextTransactions, clock: Clock, outbox: Outbox, routingWorkerUrl: String?, routingBudgetMillis: Long): BookingdispatchApi {
        val resources = JdbcResourceAvailability(ctxTx, clock)
        val routingPort: RoutingPort? = routingWorkerUrl?.let { CachingRoutingPort(ctxTx, HttpRoutingClient(it, Duration.ofMillis(routingBudgetMillis))) }
        return object : BookingdispatchApi {
            override val resources: ResourceAvailability = resources
            override val routing: RoutingPort? = routingPort
            override val holds: CapacityHolds = JdbcCapacityHolds(ctxTx, resources, outbox, clock)
        }
    }
}

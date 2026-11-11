package de.taktovia.bookingdispatch.internal

import de.taktovia.bookingdispatch.api.BookingdispatchApi
import de.taktovia.bookingdispatch.api.ResourceAvailability
import de.taktovia.platform.context.ContextTransactions
import java.time.Clock

/** Nur innerhalb des Moduls `booking-dispatch` sichtbar (durch Architekturtest erzwungen). */
internal object BookingServicesFactory {
    fun create(ctxTx: ContextTransactions, clock: Clock): BookingdispatchApi {
        val resources = JdbcResourceAvailability(ctxTx, clock)
        return object : BookingdispatchApi { override val resources: ResourceAvailability = resources }
    }
}

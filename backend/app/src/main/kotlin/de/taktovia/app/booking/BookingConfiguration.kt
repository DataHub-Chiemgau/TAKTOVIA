package de.taktovia.app.booking

import de.taktovia.bookingdispatch.api.CandidateGate
import de.taktovia.bookingdispatch.api.RideOfferRequests
import de.taktovia.config.api.ConfigApi
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.outbox.Outbox
import de.taktovia.servicegeo.api.ServicegeoApi
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Verdrahtung der Anfrageerfassung (WP-M2-11 bis -13). Das Tor zur Kandidatensuche reicht zulässige Anfragen durch, bis die Positionsquelle des Fahrtenplans und die
 * Einfügeheuristik an die Angebotsbildung angeschlossen sind (WP-M2-17 bis -21); `CandidateGate.overVehicleCandidates` ist dafür fertig und getestet.
 */
@Configuration
class BookingConfiguration {
    @Bean fun rideOfferRequests(ctxTx: ContextTransactions, outbox: Outbox, geo: ServicegeoApi, config: ConfigApi): RideOfferRequests =
        RideOfferRequests.create(ctxTx, outbox, geo, config.effective, gate = CandidateGate.PASS_THROUGH)
}

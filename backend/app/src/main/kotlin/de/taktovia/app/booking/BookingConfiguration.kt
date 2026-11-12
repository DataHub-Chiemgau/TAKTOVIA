package de.taktovia.app.booking

import de.taktovia.bookingdispatch.api.Bookings
import de.taktovia.bookingdispatch.api.CandidateGate
import de.taktovia.config.api.ImpactProvider
import de.taktovia.bookingdispatch.api.HoldExpiry
import de.taktovia.bookingdispatch.api.RideOfferRequests
import de.taktovia.bookingdispatch.api.RideOffers
import de.taktovia.config.api.ConfigApi
import de.taktovia.farepayment.api.FareQuotes
import de.taktovia.farepayment.api.Tariffs
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.outbox.TenantSource
import de.taktovia.platform.tenant.TenantTransactions
import de.taktovia.servicegeo.api.ServicegeoApi
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Verdrahtung von Anfrageerfassung, Preisstand, Angebotsobjekt und Hold (WP-M2-09, -11 bis -13, -21, -22). Das Tor zur Kandidatensuche reicht zulässige Anfragen durch, bis die Positionsquelle
 * des Fahrtenplans und die Einfügeheuristik an die Angebotsbildung angeschlossen sind (WP-M2-17 bis -19): solche Angebote tragen Preis und Ablauf, aber **keine** Bindung an eine Planrevision;
 * ein Hold darauf ergibt `409 CAPACITY_UNAVAILABLE`. `CandidateGate.overVehicleCandidates` und `CandidateGateResult.Assigned` sind dafür fertig und getestet.
 */
@Configuration
class BookingConfiguration {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean fun fareQuotes(ctxTx: ContextTransactions, tariffs: Tariffs): FareQuotes = FareQuotes.create(ctxTx, tariffs)

    @Bean fun rideOfferRequests(ctxTx: ContextTransactions, outbox: Outbox, geo: ServicegeoApi, config: ConfigApi, quotes: FareQuotes): RideOfferRequests =
        RideOfferRequests.create(ctxTx, outbox, geo, config.effective, gate = CandidateGate.PASS_THROUGH, quotes = quotes)

    /** Buchungsbestätigung und Lesesichten (WP-M2-23); der ImpactProvider „Buchungen“ wird von `ConfigApi` für die Wirkungsanalyse gesammelt. */
    @Bean fun bookings(ctxTx: ContextTransactions, outbox: Outbox, quotes: FareQuotes, config: ConfigApi, payments: de.taktovia.farepayment.api.Payments): Bookings =
        Bookings.create(ctxTx, outbox, quotes, config = config.effective, cancellation = de.taktovia.app.payment.PaymentCancellationListener(payments))   // Void/Erstattung nach Storno (WP-M2-31)

    @Bean fun bookingImpactProvider(): ImpactProvider = Bookings.impactProvider()

    @Bean fun rideOffers(ctxTx: ContextTransactions, outbox: Outbox, quotes: FareQuotes, config: ConfigApi): RideOffers = RideOffers.create(ctxTx, outbox, quotes, config.effective)

    /**
     * Expiry-Job der Holds (WP-M2-22): läuft nur mit `taktovia.booking.hold-expiry.enabled=true` und einer [TenantSource]-Bean des Betriebs (Mandantenkontext je Lauf); der Takt ist
     * `taktovia.booking.hold-expiry.poll-seconds` (Standard 10). Die Prüfung bei jedem Zugriff bleibt maßgeblich, der Job ist nur die Aufräumhilfe.
     */
    @Bean fun holdExpiry(tx: TenantTransactions, outbox: Outbox, tenants: ObjectProvider<TenantSource>, env: Environment): HoldExpiry {
        val job = HoldExpiry.create(tx, outbox)
        val source = tenants.getIfAvailable()
        if (source != null && env.getProperty("taktovia.booking.hold-expiry.enabled", Boolean::class.java, false)) {
            val poll = env.getProperty("taktovia.booking.hold-expiry.poll-seconds", Long::class.java, 10)
            Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "hold-expiry").also { it.isDaemon = true } }.scheduleWithFixedDelay({
                runCatching { source.tenants().distinct().forEach { t -> job.runOnce(t) } }.onFailure { log.warn("Hold-Ablauf: Lauf fehlgeschlagen ({})", it.javaClass.simpleName) }
            }, poll, poll, TimeUnit.SECONDS)
        }
        return job
    }
}

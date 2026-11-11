// Abhängigkeiten: nur platform (siehe Wurzel-Build). Andere Module ausschließlich über deren api-Paket.
package de.taktovia.bookingdispatch.api

import de.taktovia.bookingdispatch.internal.BookingServicesFactory
import de.taktovia.platform.context.ContextTransactions
import java.time.Clock

/** Öffentliche Ports des Moduls `booking-dispatch`; wächst mit den Arbeitspaketen (bisher: Ressourcen-Leseport, WP-M2-02). */
interface BookingdispatchApi {
    /** Leseport auf Fahrzeuge, Schichten, Freigaben und Fahrzeugpool (Daten von `fleet-safety`/`staff-time`; dieses Modul schreibt sie nie). */
    val resources: ResourceAvailability

    /** Fahrzeiten, Distanzen und Wege über den Routing-Worker mit mandantengebundenem Cache; nur vorhanden, wenn ein Worker konfiguriert ist (WP-M2-10). */
    val routing: RoutingPort?

    companion object {
        fun create(ctxTx: ContextTransactions, clock: Clock = Clock.systemUTC(), routingWorkerUrl: String? = null, routingBudgetMillis: Long = 900): BookingdispatchApi =
            BookingServicesFactory.create(ctxTx, clock, routingWorkerUrl, routingBudgetMillis)
    }
}

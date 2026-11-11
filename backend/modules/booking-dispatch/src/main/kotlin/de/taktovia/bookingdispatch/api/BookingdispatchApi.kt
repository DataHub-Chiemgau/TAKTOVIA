// Abhängigkeiten: nur platform (siehe Wurzel-Build). Andere Module ausschließlich über deren api-Paket.
package de.taktovia.bookingdispatch.api

import de.taktovia.bookingdispatch.internal.BookingServicesFactory
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.outbox.Outbox
import java.time.Clock

/** Öffentliche Ports des Moduls `booking-dispatch`; wächst mit den Arbeitspaketen (bisher: Ressourcen-Leseport, WP-M2-02). */
interface BookingdispatchApi {
    /** Leseport auf Fahrzeuge, Schichten, Freigaben und Fahrzeugpool (Daten von `fleet-safety`/`staff-time`; dieses Modul schreibt sie nie). */
    val resources: ResourceAvailability

    /** Fahrzeiten, Distanzen und Wege über den Routing-Worker mit mandantengebundenem Cache; nur vorhanden, wenn ein Worker konfiguriert ist (WP-M2-10). */
    val routing: RoutingPort?

    /** Kapazitätshold und Bestätigung mit Ressourcenvektor (Kernel, WP-M2-14; Vervollständigung in WP-M2-22/-23). */
    val holds: CapacityHolds

    companion object {
        fun create(ctxTx: ContextTransactions, clock: Clock = Clock.systemUTC(), outbox: Outbox = Outbox(), routingWorkerUrl: String? = null, routingBudgetMillis: Long = 900): BookingdispatchApi =
            BookingServicesFactory.create(ctxTx, clock, outbox, routingWorkerUrl, routingBudgetMillis)
    }
}

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

    /** Fahrzeugkandidatenfilter nach Dienstpool, Dienstzeit, Freigabe, Ausstattung/Kapazität und Erreichbarkeit (WP-M2-15). */
    val candidates: VehicleCandidates

    /** Fahrtenplan (Aggregat RunPlanRevision, WP-M2-16): Umlauf je Schicht, Stoppfolgen je Revision, Routeninvarianten; lesende Schnittstelle für M3. */
    val runs: RunPlans

    /** Einfügeheuristik mit Prüfung im Kern (WP-M2-17 bis -19); nur vorhanden, wenn ein Routing-Worker konfiguriert ist. */
    val insertion: InsertionService?

    /** Automatischer Prüfer harter Grenzen und zugesagter Fenster über bestätigte Buchungen und alle Planrevisionen (WP-M2-18/-19). */
    val audit: ConfirmedRideAudit

    /** Leseport Fahrtenplan (Planzeiten, Kilometer) für M4 (WP-M4-02). */
    val tripPlans: de.taktovia.platform.readports.TripPlanReadPort

    companion object {
        fun create(ctxTx: ContextTransactions, clock: Clock = Clock.systemUTC(), outbox: Outbox = Outbox(), routingWorkerUrl: String? = null, routingBudgetMillis: Long = 900,
            plannedPositions: PlannedPositionSource = PlannedPositionSource { _, _, _, _ -> null }, insertionWorker: InsertionWorker? = null,
        ): BookingdispatchApi =
            BookingServicesFactory.create(ctxTx, clock, outbox, routingWorkerUrl, routingBudgetMillis, plannedPositions, insertionWorker)
    }
}

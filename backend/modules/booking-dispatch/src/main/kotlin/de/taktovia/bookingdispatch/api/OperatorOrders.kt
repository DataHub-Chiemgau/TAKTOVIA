package de.taktovia.bookingdispatch.api

import de.taktovia.platform.context.RequestContext
import java.time.Instant
import java.util.UUID

/** Phase eines Auftrags in der Leitstellenansicht: [RUNNING] = Fahrgast ist eingestiegen (Umlauf läuft), [NEXT] = noch ausstehender Einstieg. */
enum class OrderPhase { RUNNING, NEXT }

/** Ein Auftrag des aktuellen Umlaufs eines Fahrzeugs: Buchung, Buchungsnummer, Zustand und geplanter Einstieg. Keine Fahrgastdaten (WP-M3-36, OPS-001). */
data class OperatorOrderView(val bookingId: UUID, val bookingNo: String?, val state: String, val plannedPickupAt: Instant, val phase: OrderPhase)

/** Aktueller (nicht abgeschlossener) Umlauf eines Fahrzeugs mit seinen nächsten Aufträgen (höchstens [OperatorOrders.MAX_ORDERS], nach geplantem Einstieg). */
data class OperatorRunSummary(val runId: UUID, val runState: String, val planRevision: Long, val orders: List<OperatorOrderView>)

/**
 * Leseport „Aufträge je Fahrzeug“ für die Leitstelle (WP-M3-36): je Fahrzeug der jüngste nicht abgeschlossene Umlauf (aus `vehicle_run`, WP-M2-16) und die Buchungen, die im Plan der aktuellen
 * Revision einsteigen (nicht storniert, nicht abgeschlossen). Nur lesend, im Mandanten des Kontexts (RLS); Fahrzeuge ohne Umlauf fehlen in der Antwort. Die Dienstberechtigung entscheidet der Aufrufer
 * über die Fahrzeugliste (nur dort bereits berechtigte Fahrzeuge werden abgefragt).
 */
interface OperatorOrders {
    fun forVehicles(ctx: RequestContext, vehicleIds: Collection<UUID>): Map<UUID, OperatorRunSummary>

    companion object {
        const val MAX_ORDERS = 5

        fun create(ctxTx: de.taktovia.platform.context.ContextTransactions): OperatorOrders = de.taktovia.bookingdispatch.internal.BookingServicesFactory.operatorOrders(ctxTx)
    }
}

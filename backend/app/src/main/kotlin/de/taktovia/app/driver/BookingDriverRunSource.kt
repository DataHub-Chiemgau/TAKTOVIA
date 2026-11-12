package de.taktovia.app.driver

import de.taktovia.bookingdispatch.api.DriverRunPlans
import de.taktovia.bookingdispatch.api.DriverStopKind
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.stafftime.api.DriverAssistance
import de.taktovia.stafftime.api.DriverResourceNeed
import de.taktovia.stafftime.api.DriverRunProjection
import de.taktovia.stafftime.api.DriverRunSource
import de.taktovia.stafftime.api.DriverRunStopView
import de.taktovia.stafftime.api.ExpectedPassenger
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Stoppliste des Fahrers aus der Fahrtenplan-Lesesicht von `booking-dispatch` (WP-M3-26): bildet die Modul-API des Fahrtenplans ([DriverRunPlans]) auf die Projektion von `staff-time`
 * ab. Hier wird nichts hinzugefügt, nur umgeformt: Feldminimierung und Freigabe der Unterstützungsmerkmale entscheidet `staff-time` (WP-M3-32), der Plan liefert sie getrennt je Stopp.
 */
@Configuration
class BookingDriverRunSource {
    @Bean fun driverRunPlans(ctxTx: ContextTransactions): DriverRunPlans = DriverRunPlans.create(ctxTx)

    @Bean fun driverRunSource(plans: DriverRunPlans): DriverRunSource = DriverRunSource { ctx, shiftId, vehicleId ->
        plans.currentPlan(ctx, shiftId, vehicleId)?.let { plan ->
            DriverRunProjection(plan.planRevision, plan.stops.map { s ->
                DriverRunStopView(
                    s.routeStopId, s.seq + 1, when (s.kind) { DriverStopKind.PICKUP -> "PICKUP"; DriverStopKind.DROPOFF -> "DROPOFF"; DriverStopKind.PICKUP_DROPOFF -> "PICKUP_DROPOFF" },
                    s.plannedAt, s.forecastAt, s.stopName, s.longitude, s.latitude, s.boardingPersons + s.alightingPersons, (s.boardingBookingIds + s.alightingBookingIds).distinct(), null, emptyList(),
                    s.stopRevisionId, s.boardingPersons, s.alightingPersons, s.boardingNeed?.let { n -> DriverResourceNeed(n.seats, n.wheelchair, n.equipment.map { it.name }.sorted()) },
                    s.assistance?.let { a -> DriverAssistance(a.wheelchairRequired, a.boardingAssist, a.alightingAssist) },
                    s.boardingRefs.map { ExpectedPassenger(it.bookingId, it.bookingRef, it.persons) },
                )
            }, plan.runState.name)
        }
    }
}

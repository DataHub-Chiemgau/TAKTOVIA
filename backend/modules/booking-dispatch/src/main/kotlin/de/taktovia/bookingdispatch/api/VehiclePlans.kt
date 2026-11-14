package de.taktovia.bookingdispatch.api

import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Fahrzeugplan der Leitstelle (WP-M3-38, LH-6.7-OPS-003): je Fahrzeug die Stoppreihenfolge der **aktuellen Planrevision** mit Sollzeit, Prognose und Zusagefenster sowie die aktuelle Besetzung.
 * Die drei Zeitangaben sind getrennte Felder und werden nie vermischt: [VehiclePlanStop.scheduledAt] ist die Sollzeit der Planrevision, [VehiclePlanStop.forecast] die Prognose (oder `null` =
 * „keine Prognose“, nie eine erfundene Zeit), [VehiclePlanStop.promiseWindows] die verbindlichen Zusagefenster der bestätigten Buchungen.
 */
enum class PromiseWindowKind { PICKUP, DROPOFF }

/** Zusagefenster einer bestätigten Buchung an diesem Stopp (Abholfenster am Einstieg, Ankunftsfenster am Ausstieg); unveränderlich aus der Zusage. */
data class PlanPromiseWindow(val bookingId: UUID, val kind: PromiseWindowKind, val earliest: Instant, val latest: Instant)

/** Prognose für einen Stopp: Fenster [earliest]..[latest] und ihre Grundlage ([EtaBasis.PLAN_TIME] = Planzeit aus dem Routing, solange das ETA-Grundmodell nicht die Quelle ist, OP-20). */
data class PlanForecast(val earliest: Instant, val latest: Instant, val basis: EtaBasis)

/** Stopp der Planrevision; [sequence] ist die 1-basierte Position (wie der Planvergleich der Umplanung, WP-M3-40). */
data class VehiclePlanStop(
    val routeStopId: UUID, val sequence: Int, val stopRevisionId: UUID, val stopName: String, val kind: DriverStopKind, val scheduledAt: Instant, val forecast: PlanForecast?,
    val promiseWindows: List<PlanPromiseWindow>, val boardingBookingIds: List<UUID>, val alightingBookingIds: List<UUID>,
)

/** Zähler der Ressourcendimensionen Sitz und Rollstuhlplatz (Ausstattung ist keine verbrauchbare Größe). */
data class DimensionCounts(val seats: Int, val wheelchair: Int)

/** Offener Klärfall eines Fahrerereignisses (`REVIEW_REQUIRED`): fließt **nicht** in die Besetzung ein, wird aber ausgewiesen. */
data class PlanReviewCase(val eventId: UUID, val eventType: String, val bookingId: UUID?, val reasonCode: String, val occurredAt: Instant)

/** Herkunft und Stand der Prognosezeiten (Datenstand = Messzeit der letzten Position; `null` ohne Positionsbezug). */
data class PlanForecastInfo(val source: String, val dataStand: Instant?, val fallbackReason: String?, val modelVersion: Int?, val configVersion: String?)

/**
 * Plan eines Fahrzeugs. Ohne nicht abgeschlossenen Umlauf ist es der **Leerzustand**: [runId] und [planRevision] fehlen, [stops] ist leer, die Besetzung null.
 * [occupancy] = Differenz bestätigter (`APPLIED`) Einstiege und Ausstiege je Dimension aus den Fahrerereignissen der Schicht des Umlaufs.
 */
data class VehiclePlan(
    val vehicleId: UUID, val runId: UUID?, val runState: RunState?, val planRevision: Long?, val asOf: Instant, val stops: List<VehiclePlanStop>, val capacity: DimensionCounts?,
    val occupancy: DimensionCounts, val reviewRequired: List<PlanReviewCase>, val forecastInfo: PlanForecastInfo,
) { val empty: Boolean get() = runId == null }

/**
 * Leseport Fahrzeugplan (WP-M3-38). Nur lesend im Mandanten des Kontexts (RLS); ein Fahrzeug eines fremden Mandanten ist 404. **Die Dienstberechtigung entscheidet der Aufrufer** über die
 * Leitstellenfahrzeugsicht (`OperatorVehicles.get`, nicht berechtigt = 404), bevor er diesen Port fragt. Eine Antwort entsteht in **einer** Lesetransaktion: Revision, Stopps, Fenster und
 * Besetzung gehören zusammen.
 */
interface VehiclePlans {
    fun plan(ctx: RequestContext, vehicleId: UUID): VehiclePlan

    companion object {
        /** [eta]: gesetzt = Prognosezeiten aus dem Leseport „ETA je Stopp“ (WP-M4-66); ohne gilt die Planzeit aus dem Routing bzw. „keine Prognose“ (OP-20). */
        fun create(ctxTx: ContextTransactions, eta: EtaStopEtaPort? = null, vehicleProfile: String = "car", clock: Clock = Clock.systemUTC()): VehiclePlans =
            de.taktovia.bookingdispatch.internal.JdbcVehiclePlans(ctxTx, eta, vehicleProfile, clock)
    }
}

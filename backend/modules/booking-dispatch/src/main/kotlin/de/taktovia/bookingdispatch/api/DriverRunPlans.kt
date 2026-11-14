package de.taktovia.bookingdispatch.api

import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import java.time.Instant
import java.util.UUID

/**
 * Fahrerprojektion des aktuellen Umlaufs einer Schicht (WP-M3-26, LH-6.6-DRV-002; Unterstützungsmerkmale WP-M3-32, LH-6.6-DRV-008): Lesesicht auf `vehicle_run`, `route_stop`,
 * `stop_revision` und `passenger_trip` über die Modul-API, mit **Feldminimierung**: je Stopp nur Haltepunkt (Name, Koordinate, Revision), Zeiten, Personenzahl, Ressourcenbedarf und
 * (optional) die Unterstützungsmerkmale; nie Kontakt-, Zahlungs- oder Diagnosedaten. Reihenfolge = `route_stop`-Sequenz der aktuellen Planrevision. Der Aufrufer (`staff-time` über die
 * Anwendung) hat die Schicht als eigene, angetretene und freigegebene Schicht geprüft; dieser Port prüft die Rolle `driver` und den Mandanten (RLS), nicht die Fahrerzuordnung.
 */
enum class DriverStopKind { PICKUP, DROPOFF, PICKUP_DROPOFF }

/** Unterstützungsmerkmale an einem Stopp (Katalog der transportrelevanten Merkmale ohne Diagnose, Detailvertrag LH-6.6-DRV-008); der Wert steht nie in `toString`. */
class StopAssistance(val wheelchairRequired: Boolean, val boardingAssist: Boolean, val alightingAssist: Boolean) {
    val any: Boolean get() = wheelchairRequired || boardingAssist || alightingAssist
    override fun toString() = "StopAssistance(redacted)"
    override fun equals(other: Any?) = other is StopAssistance && other.wheelchairRequired == wheelchairRequired && other.boardingAssist == boardingAssist && other.alightingAssist == alightingAssist
    override fun hashCode() = listOf(wheelchairRequired, boardingAssist, alightingAssist).hashCode()
}

/**
 * Ein Stopp der Fahrerprojektion. [seq] ist die 0-basierte Position der Planrevision. [forecastAt] ist die Prognosezeit: bis zum ETA-Grundmodell als Laufzeitquelle (OP-20) die Planzeit
 * aus dem Routing, und `null` („keine Prognose“), wenn die Fahrzeit zum Stopp nicht aus dem Routing stammt; es wird nie eine Zeit erfunden.
 */
/** Einsteigende Buchung eines Stopps für die Fahrgastliste (WP-M3-30): Buchungsnummer als Kürzel und Personenzahl, nie Name, Kontakt oder Zahlung. */
data class StopBookingRef(val bookingId: UUID, val bookingRef: String?, val persons: Int)

data class DriverPlanStop(
    val routeStopId: UUID, val seq: Int, val stopRevisionId: UUID, val kind: DriverStopKind, val plannedAt: Instant, val forecastAt: Instant?, val stopName: String,
    val longitude: Double, val latitude: Double, val boardingBookingIds: List<UUID>, val alightingBookingIds: List<UUID>, val boardingPersons: Int, val alightingPersons: Int,
    val boardingNeed: ResourceVector?, val assistance: StopAssistance?, val boardingRefs: List<StopBookingRef> = emptyList(),
)

/**
 * Herkunft der Prognosezeiten der Stoppliste (WP-M4-66): [source] `PLAN_TIME` = Planzeit aus dem Routing (kein Grundmodell angeschlossen), `ETA_MODEL` = Leseport „ETA je Stopp“; [dataStand] die Messzeit der letzten Position,
 * auf der die Prognose beruht; [fallbackReason] gesetzt, wenn Positionsdaten fehlen oder älter als `eta_max_data_age_sec` sind (dann Rückfall laut `eta_fallback_policy` statt einer scheinbar aktuellen Prognose).
 */
data class DriverForecastInfo(val source: String, val dataStand: Instant?, val fallbackReason: String?, val modelVersion: Int?, val configVersion: String?)

data class DriverPlanView(
    val runId: UUID, val shiftId: UUID, val vehicleId: UUID, val planRevision: Long, val runState: RunState, val stops: List<DriverPlanStop>,
    val forecast: DriverForecastInfo = DriverForecastInfo("PLAN_TIME", null, null, null, null),
)

interface DriverRunPlans {
    /** Aktueller Plan des Umlaufs der Schicht; `null`, wenn es (noch) keinen Umlauf gibt. Ein Umlauf ohne Stopps hat eine leere Stoppliste. Konsistent: Revision und Stopps stammen aus einer Planrevision. */
    fun currentPlan(ctx: RequestContext, shiftId: UUID, vehicleId: UUID): DriverPlanView?

    /**
     * Plan einer **früheren oder aktuellen Revision** des Umlaufs der Schicht (WP-M3-27: Vergleichsbasis der Änderungsanzeige und Überwachung unbestätigter Revisionen): die Stopps sind je
     * Revision unveränderlich (`route_stop`), die Revision ist also jederzeit lesbar. Ohne Personenkontext, nur für den serverseitigen Vergleich in der Modul-Anwendung, nie für die Auslieferung;
     * `null`, wenn es keinen Umlauf oder die Revision nicht gibt.
     */
    fun planAt(tenant: de.taktovia.platform.tenant.TenantId, shiftId: UUID, vehicleId: UUID, revision: Long): DriverPlanView?

    companion object {
        /** [eta]: gesetzt = Prognosezeit je Stopp aus dem Leseport „ETA je Stopp“ (WP-M4-66); ohne gilt die Planzeit aus dem Routing (OP-20). */
        fun create(ctxTx: de.taktovia.platform.context.ContextTransactions, eta: EtaStopEtaPort? = null, vehicleProfile: String = "car", clock: java.time.Clock = java.time.Clock.systemUTC()): DriverRunPlans =
            de.taktovia.bookingdispatch.internal.JdbcDriverRunPlans(ctxTx, eta, vehicleProfile, clock)
    }
}

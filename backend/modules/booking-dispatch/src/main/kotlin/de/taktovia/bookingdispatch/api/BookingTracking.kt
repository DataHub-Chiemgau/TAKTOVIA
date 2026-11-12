package de.taktovia.bookingdispatch.api

import org.springframework.jdbc.core.JdbcOperations
import java.time.Instant
import java.util.UUID

/**
 * Bereich der Buchungsübersicht UI-04 (WP-M2-45, LH-6.3-PAX-008). Jede Buchung liegt in **genau einem** Bereich; die Zuordnung ist die reine Funktion [BookingAreas.of] an genau einer
 * Stelle im Backend, Oberfläche und API liefern dieselbe Einteilung (Feld `bereich` der Buchung, Filter `bereich` der Liste).
 */
enum class BookingArea(val code: String) {
    UPCOMING("bevorstehend"), RUNNING("laufend"), PAST("vergangen"), CANCELED("storniert");

    companion object {
        fun parse(code: String?): BookingArea? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Ausführungszustand einer bestätigten Buchung (M3: Check-in bis Fahrtende). In M2 gibt es dafür nur Testereignisse (Offener Punkt 37, Vollabnahme mit WP-M3-29); die Quelle ist
 * [ExecutionStateSource], der Standard liefert nichts.
 */
enum class ExecutionState { IN_PROGRESS, COMPLETED, NO_SHOW }

/** Quelle der Ausführungszustände (Fahrerereignisse ab M3, in M2 Testereignisse); liest in der laufenden Transaktion. */
fun interface ExecutionStateSource {
    fun of(j: JdbcOperations, bookingIds: Collection<UUID>): Map<UUID, ExecutionState>

    companion object { val NONE = ExecutionStateSource { _, _ -> emptyMap() } }
}

/**
 * Zuordnung `(booking.state, Ausführungszustand, Zeit) -> Bereich` (Arbeitsstand, Detailvertrag LH-6.3-PAX-008):
 * storniert in jedem Stornozustand (auch Fahrzeugausfall, gescheitert) -> storniert; abgeschlossen, No-Show und abgelaufen (spätestes Ankunftsende vorbei) -> vergangen;
 * Ausführung begonnen -> laufend; sonst (`CONFIRMING`, `CONFIRMED`, `RECONCILING` vor dem Ende der zugesagten Ankunft) -> bevorstehend. No-Show und abgelaufen sind im Pflichtenheft nicht
 * festgelegt: Arbeitsannahme vergangen.
 */
object BookingAreas {
    private val CANCELED_STATES = setOf("CANCELED", "CANCEL_PENDING", "COMPENSATING", "FAILED")

    fun of(state: String, arrivalLatest: Instant, now: Instant, execution: ExecutionState? = null): BookingArea = when {
        state in CANCELED_STATES -> BookingArea.CANCELED
        state == "COMPLETED" || execution == ExecutionState.COMPLETED || execution == ExecutionState.NO_SHOW -> BookingArea.PAST
        execution == ExecutionState.IN_PROGRESS -> BookingArea.RUNNING
        arrivalLatest.isBefore(now) -> BookingArea.PAST
        else -> BookingArea.UPCOMING
    }
}

/** Haltepunkt der Buchung (unveränderliche Revision zum Zeitpunkt des Angebots): Name, Lage und Zugänglichkeitsangaben (`stop_revision.accessibility` als JSON). */
data class TrackedStop(val stopId: UUID, val revisionId: UUID, val revision: Long, val name: String, val longitude: Double, val latitude: Double, val accessibilityJson: String)

/**
 * Aktuelle ETA aus der Planrevision (Plan-ETA, WP-M2-43): [pickupAt] ist die geplante Ankunftszeit am Einstiegsstopp, [previousPickupAt] der Wert vor der letzten Änderung (nur bei einer
 * Zeitänderung), [planSequence] die Zahl der Planänderungen der Buchung, [dataAsOf] der Zeitpunkt der Planänderung, [source] `PLAN` (Prognose-ETA ist M4, WP-M4-62).
 */
data class BookingEta(val pickupAt: Instant, val dropoffAt: Instant?, val previousPickupAt: Instant?, val planSequence: Long, val planRevision: Long, val dataAsOf: Instant, val source: String = "PLAN")

/** Fahrzeugidentifikation für Fahrgäste (Arbeitsannahme zu Offener Punkt 35): Bezeichnung und Fahrzeugklasse, nie Halter, Fahrer oder Wartungsdaten. */
data class VehicleIdentification(val label: String, val vehicleClass: String?)

/** Stornoregel der Buchung für die Oberfläche: Frist (spätester Zeitpunkt des Fahrgaststornos), ob die Aktion jetzt angeboten wird, und ob die Frist ein Planungsdefault ist. */
data class CancellationOption(val allowed: Boolean, val deadlineAt: Instant, val deadlineMin: Int, val deadlineDefaulted: Boolean)

/** Lesesicht für UI-04 (WP-M2-43/-44/-45/-46): Buchung, Bereich, Haltepunkte, Plan-ETA, Fahrzeugidentifikation (sobald die Buchung im Plan steht) und Stornoregel. */
data class BookingTracking(
    val booking: BookingDetail, val pickupStop: TrackedStop?, val dropoffStop: TrackedStop?, val eta: BookingEta?, val vehicle: VehicleIdentification?, val cancellation: CancellationOption?,
    val offerId: UUID?, val dataAsOf: Instant,
)

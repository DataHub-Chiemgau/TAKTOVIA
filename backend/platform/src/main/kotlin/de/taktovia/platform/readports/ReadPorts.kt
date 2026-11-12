package de.taktovia.platform.readports

import de.taktovia.platform.context.RequestContext
import java.time.Instant
import java.util.UUID

/**
 * Leseports der Module Personal/Zeit, Belege/Kosten und Kennzahlen (M4) auf Daten aus Buchung/Disposition (M2), Fahrt/Fahrer (M3) und Flotte (WP-M4-02). **Schmale Lesesichten**
 * ohne Fremdtabellenzugriff: der Eigentümer der Daten implementiert den Port in seinem Modul; solange die Quelle fehlt (M2/M3 noch nicht geliefert), ersetzt ihn der Stub
 * [de.taktovia.platform.readports.stub.FixtureReadPorts] aus den Referenzdaten (`tests/acceptance/fixtures/m4_referenz`). Stub und echte Implementierung bestehen denselben
 * Vertragstest (`ReadPortContractTest` im Backend).
 *
 * **Verträge aller Ports:** (1) Es werden ausschließlich Daten des Mandanten von [RequestContext.tenant] geliefert; die Kennung eines fremden Mandanten ergibt `null` bzw. eine leere
 * Liste, nie einen Fehler, der die Existenz verrät. (2) Lesen ändert nichts. (3) Listen sind stabil nach ID sortiert (Textform, entspricht der Byte-Reihenfolge der Datenbank). (4) Keine Klarnamen, Kontaktdaten oder exakten Adressen: die Sichten
 * enthalten nur, was M4 für Planung, Kosten und Kennzahlen braucht.
 */

/** Ein Stopp der aktuellen Planrevision; [boardingBookings]/[alightingBookings] sind die Buchungen, die dort ein- bzw. aussteigen; Fahrzeit und Strecke zum Folgestopp (letzter Stopp: `null`). */
data class TripStopView(
    val seq: Int, val stopRevisionId: UUID, val plannedAt: Instant, val boardingBookings: List<UUID>, val alightingBookings: List<UUID>, val legDurationSec: Int?, val legDistanceM: Int?,
)

/** Aktueller Fahrtenplan eines Umlaufs (Revision, Stoppfolge mit Planzeiten). */
data class TripPlanView(val runId: UUID, val vehicleId: UUID, val shiftId: UUID, val revision: Long, val state: String, val stops: List<TripStopView>) {
    val totalDistanceM: Long get() = stops.sumOf { (it.legDistanceM ?: 0).toLong() }
    val totalDurationSec: Long get() = stops.sumOf { (it.legDurationSec ?: 0).toLong() }
}

/** Fahrtenplan (Planzeiten, Kilometer) aus `vehicle_run`/`route_stop` (Modul `booking-dispatch`, WP-M2-16). */
interface TripPlanReadPort {
    fun currentPlan(ctx: RequestContext, runId: UUID): TripPlanView?
    fun currentPlanOfShift(ctx: RequestContext, shiftId: UUID): TripPlanView?
}

/** Zusage einer Buchung (Snapshot-Fenster) für Soll/Ist-Vergleiche; kein Fahrgastbezug. */
data class BookingPromiseView(
    val bookingId: UUID, val serviceId: UUID, val state: String, val revision: Long, val pickupEarliest: Instant, val pickupLatest: Instant,
    val arrivalEarliest: Instant, val arrivalLatest: Instant, val passengers: Int?,
)

/** Buchung mit Zusagesnapshot (Quelle: WP-M2-23; bis dahin Stub). */
interface BookingReadPort {
    fun promise(ctx: RequestContext, bookingId: UUID): BookingPromiseView?
}

/** Fahrerereignis: Ist-Abholung (`CHECK_IN`), Ist-Ankunft (`ARRIVAL`), `NO_SHOW` (Quelle: WP-M3-29; bis dahin Stub). */
data class DriverEventView(val eventId: UUID, val shiftId: UUID, val bookingId: UUID?, val type: String, val occurredAt: Instant)

interface DriverEventReadPort {
    fun eventsOfShift(ctx: RequestContext, shiftId: UUID): List<DriverEventView>
}

data class QualificationView(val code: String, val revision: Long, val validFrom: Instant, val validTo: Instant?)

/** Fahrer ohne Klarname und Kontakt (nie Teil einer Projektion), mit Qualifikationsrevisionen. */
data class DriverView(val id: UUID, val status: String, val qualifications: List<QualificationView>)

/** Fahrerstamm und Qualifikationen (Modul `staff-time`, WP-M3-03/-05). */
interface DriverReadPort {
    fun driver(ctx: RequestContext, driverId: UUID): DriverView?
    fun drivers(ctx: RequestContext): List<DriverView>
}

data class VehicleView(val id: UUID, val label: String?, val seats: Int, val wheelchair: Int, val equipment: Set<String>, val active: Boolean)

/** Rollenzuordnung am Fahrzeug (Eigentümer, Betreiber, …) mit Gültigkeit `[validFrom, validTo)`. */
data class VehicleRoleView(val vehicleId: UUID, val organizationId: UUID, val role: String, val validFrom: Instant, val validTo: Instant?)

/** Fahrzeugstamm und Rollen (Modul `fleet-safety`, WP-M3-03, WP-M1-34). */
interface FleetReadPort {
    fun vehicle(ctx: RequestContext, vehicleId: UUID): VehicleView?
    fun vehicles(ctx: RequestContext): List<VehicleView>
    fun roles(ctx: RequestContext, vehicleId: UUID): List<VehicleRoleView>
}

data class PositionView(val vehicleId: UUID, val shiftId: UUID, val longitude: Double, val latitude: Double, val measuredAt: Instant)

/** Jüngste Position je Fahrzeug (Quelle: WP-M3-34; bis dahin Stub). */
interface PositionReadPort {
    fun latest(ctx: RequestContext, vehicleId: UUID): PositionView?
}

/** Grund, aus dem ein Fahrzeug im Zeitraum nicht personell besetzt ist (WP-M4-17); eine Entwurfsschicht ist für Leser des Ports nicht sichtbar und ergibt [NO_SHIFT]. */
enum class StaffingGap {
    /** Keine verbindliche Schicht mit diesem Fahrzeug (offene Fahrerschicht trägt kein Fahrzeug, Entwurf nicht sichtbar). */
    NO_SHIFT,
    /** Es gibt eine verbindliche Schicht des Fahrzeugs, sie deckt den angefragten Zeitraum aber nicht vollständig. */
    OUTSIDE_PERIOD,
    /** Die Schicht verlangt eine Qualifikation, die der zugeordnete Fahrer zu Schichtbeginn nicht (freigegeben und gültig) hat. */
    QUALIFICATION_MISSING,
}

/**
 * Besetzung eines Fahrzeugs im Zeitraum `[from, to]` (WP-M4-17, LH-6.23-SCH-007). [staffed] gilt nur, wenn eine **veröffentlichte** (oder bereits angetretene) Schicht mit zugeordnetem Fahrer
 * den Zeitraum vollständig abdeckt; dann sind [shiftId], [shiftRevision], [driverRef], [rosterRevision], [depotId], [serviceId] (geplanter Betrieb) und, wenn die Schicht eine Qualifikation verlangt,
 * [qualificationCode] und [qualificationRevision] gesetzt, sonst [gap]. „Besetzt“ ersetzt keine technische Freigabe (`resource_release`).
 */
data class ShiftStaffingView(
    val vehicleId: UUID, val from: Instant, val to: Instant, val staffed: Boolean, val gap: StaffingGap?,
    val shiftId: UUID? = null, val shiftRevision: Long? = null, val driverRef: UUID? = null, val rosterRevision: Long? = null, val depotId: UUID? = null, val serviceId: UUID? = null,
    val qualificationCode: String? = null, val qualificationRevision: Long? = null, val startsAt: Instant? = null, val endsAt: Instant? = null,
)

/**
 * Leseport „Schichtbesetzung“ des Moduls `staff-time` für Disposition und Einsatzfreigabe (WP-M4-17). Nur lesend, nur im Mandanten des Kontexts bzw. der übergebenen Transaktion (RLS); ein Fahrzeug eines
 * fremden Mandanten liefert [StaffingGap.NO_SHIFT]. Die Variante mit [JdbcOperations] läuft in der Transaktion des Aufrufers (Hold: erneute Prüfung vor dem Commit), jeder Aufruf ist **ein** Statement und
 * liefert damit nie einen Zwischenstand einer parallelen Zuweisung. Der Port erteilt keine Freigabe.
 */
interface ShiftStaffingReadPort {
    fun staffing(ctx: RequestContext, vehicleId: UUID, from: Instant, to: Instant): ShiftStaffingView
    fun staffing(j: org.springframework.jdbc.core.JdbcOperations, vehicleId: UUID, from: Instant, to: Instant): ShiftStaffingView
    /** Besetzung mehrerer Fahrzeuge in einem Statement; [vehicleIds] ohne Eintrag ergeben keine Zeile (nicht im Ergebnis). */
    fun staffing(j: org.springframework.jdbc.core.JdbcOperations, vehicleIds: Collection<UUID>, from: Instant, to: Instant): Map<UUID, ShiftStaffingView>
}

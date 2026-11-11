package de.taktovia.bookingdispatch.api

import de.taktovia.platform.context.RequestContext
import java.time.Instant
import java.util.UUID

/** Halboffenes Zeitfenster `[from, to)`. */
data class TimeWindow(val from: Instant, val to: Instant) {
    init { require(to.isAfter(from)) { "Zeitfenster: Ende muss nach dem Beginn liegen" } }
}

/** Warum ein Fahrzeug für Dienst und Zeitfenster nicht einsetzbar ist. Die Reihenfolge der Prüfung ist die Reihenfolge dieser Aufzählung. */
enum class Unusable {
    /** Fahrzeug unbekannt oder (durch RLS) nicht im Mandanten sichtbar; beides ist von außen nicht unterscheidbar. */
    UNKNOWN_VEHICLE,
    /** Fahrzeug gehört im Fenster nicht zum Pool des Dienstes (OP-43). */
    NOT_IN_POOL,
    /** `active_block = true`: Sperre des Fahrzeugs (Sicherheit). */
    VEHICLE_BLOCKED,
    /** Keine veröffentlichte Schicht, die das ganze Fenster deckt. */
    NO_SHIFT,
    /** Keine gültige Einsatzfreigabe: fehlt, widerrufen, vor Fensterende abgelaufen oder gegen eine veraltete `safety_revision` ausgestellt. Ohne Freigabe nie einsetzbar. */
    NO_VALID_RELEASE,
}

/**
 * Sicht auf ein einsetzbares Fahrzeug. [capacityConfig] ist der unveränderte JSON-Text von `vehicle.capacity_config` (Schema `resource-vector.v1`;
 * `ResourceVector.parse` liefert das Wertobjekt). [safetyRevision] ist der Stand, gegen den die Freigabe gilt.
 */
data class VehicleResource(
    val vehicleId: UUID, val capacityConfig: String, val safetyRevision: Long,
    val shiftId: UUID, val shiftStartsAt: Instant, val shiftEndsAt: Instant, val releaseId: UUID, val releaseValidUntil: Instant,
)

sealed interface VehicleAvailability {
    data class Usable(val resource: VehicleResource) : VehicleAvailability
    data class NotUsable(val vehicleId: UUID, val reason: Unusable) : VehicleAvailability
}

/**
 * Ergebnis einer Abfrage. [vehicles] sind die einsetzbaren Fahrzeuge des Dienst-Pools, [excluded] die Poolmitglieder mit Grund. [source] benennt die Herkunft der Daten
 * (bis M3/M4: `FIXTURE`); [asOf] ist die Uhrzeit der Abfrage (injizierte Uhr).
 */
data class AvailabilitySnapshot(
    val asOf: Instant, val source: String, val serviceId: UUID, val window: TimeWindow,
    val vehicles: List<VehicleResource>, val excluded: List<VehicleAvailability.NotUsable>,
)

/**
 * Leseport `ResourceAvailability` (WP-M2-02). Schnittstellenvertrag:
 * - **Felder:** siehe [VehicleResource]; Kapazität nur als Rohwert der Konfiguration, keine Belegung (die kommt aus `capacity_hold`).
 * - **Konsistenzzusage:** jede Abfrage ist ein einziges SQL-Statement und sieht damit einen in sich konsistenten Datenstand (READ COMMITTED: ein Snapshot je Statement) im
 *   Mandanten des Kontexts; zwischen zwei Abfragen kann sich der Stand ändern, die Prüfung im Hold (WP-M2-22) liest deshalb unter Sperre erneut.
 * - **Fehlende Freigabe:** „nicht einsetzbar“ ([Unusable.NO_VALID_RELEASE]), nie „unbekannt“ oder „einsetzbar“.
 * - **Mandant:** nur der Mandant des [RequestContext]; fremde Fahrzeuge sind [Unusable.UNKNOWN_VEHICLE].
 * - **Schreiben:** nie. Die Tabellen gehören `fleet-safety`/`staff-time`.
 */
interface ResourceAvailability {
    /** Einsetzbare Fahrzeuge des Pools von [serviceId], die im ganzen [window] eine deckende Schicht und eine gültige Freigabe haben, sortiert nach Fahrzeug-ID. */
    fun usableVehicles(ctx: RequestContext, serviceId: UUID, window: TimeWindow): AvailabilitySnapshot

    /** Einsetzbarkeit eines einzelnen Fahrzeugs für den Dienst und das Fenster. */
    fun check(ctx: RequestContext, vehicleId: UUID, serviceId: UUID, window: TimeWindow): VehicleAvailability
}

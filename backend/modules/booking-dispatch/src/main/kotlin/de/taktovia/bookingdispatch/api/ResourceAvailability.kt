package de.taktovia.bookingdispatch.api

import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.geo.GeoPoint
import org.springframework.jdbc.core.JdbcOperations
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
    /** Fahrzeug gehört zum Fensterbeginn nicht zum Pool des Dienstes (OP-43): nie zugeordnet, noch nicht begonnen oder abgelaufen (`valid_to` exklusiv), WP-M3-06. */
    NOT_IN_POOL,
    /** Zuordnung gültig, aber mit Gebietsbezug und ein übergebener Ort liegt nicht im zum Fensterbeginn gültigen Gebiet oder es wurde kein Ort übergeben (nicht prüfbar: fail closed), WP-M3-06. */
    OUTSIDE_SERVICE_AREA,
    /** Fahrzeug deaktiviert (`vehicle.active = false`, Fahrzeugstamm WP-M3-03): wird nicht neu disponiert, bleibt in Historie und Nachweisen lesbar. */
    VEHICLE_INACTIVE,
    /** `active_block = true`: Sperre des Fahrzeugs (Sicherheit). */
    VEHICLE_BLOCKED,
    /** Keine veröffentlichte Schicht, die das ganze Fenster deckt. */
    NO_SHIFT,
    /** Wie [NO_SHIFT], aber eine veröffentlichte Schicht mit Fahrer begann vor dem Fenster und endet **im** Fenster (WP-M4-49 „Schichtende“); nur in [ResourceAvailability.usableVehicles] unterschieden. */
    SHIFT_ENDED_IN_WINDOW,
    /** Keine gültige Einsatzfreigabe: fehlt, widerrufen, vor Fensterende abgelaufen oder gegen eine veraltete `safety_revision` ausgestellt. Ohne Freigabe nie einsetzbar. */
    NO_VALID_RELEASE,
    /**
     * Dienststatus der Schicht erlaubt keine neue Zuweisung (WP-M3-31, DRV-007): Pause, Störung, Ende oder noch nicht bereitgemeldet. Schichten ohne Statuszeile (Altbestand, vor dem ersten
     * Statuswechsel) sind davon nicht betroffen; mit der ersten Statusmeldung gilt der Automat. Geprüft nach der Freigabe.
     */
    SERVICE_STATUS_NOT_DISPATCHABLE,
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
    /**
     * Einsetzbare Fahrzeuge des Pools von [serviceId], die im ganzen [window] eine deckende Schicht und eine gültige Freigabe haben, sortiert nach Fahrzeug-ID. [locations]: Orte der Fahrt
     * (Abholung, Ziel), die im Bediengebiet der Dienstzuordnung liegen müssen, soweit die Zuordnung ein Gebiet hat (WP-M3-06; welche Orte maßgeblich sind, legt der Aufrufer fest, OP-25).
     */
    fun usableVehicles(ctx: RequestContext, serviceId: UUID, window: TimeWindow, locations: List<GeoPoint> = emptyList()): AvailabilitySnapshot

    /** Einsetzbarkeit eines einzelnen Fahrzeugs für den Dienst und das Fenster. */
    fun check(ctx: RequestContext, vehicleId: UUID, serviceId: UUID, window: TimeWindow, locations: List<GeoPoint> = emptyList()): VehicleAvailability

    /** Wie [check] innerhalb einer laufenden Transaktion des Aufrufers (Hold prüft unter dem Kapazitätslock erneut, Kap. 01 §1.4 Schritt 5); der Mandant ist der der Transaktion. */
    fun check(jdbc: JdbcOperations, vehicleId: UUID, serviceId: UUID, window: TimeWindow, locations: List<GeoPoint> = emptyList()): VehicleAvailability

    /**
     * Nur die zeitliche Zuordnung (WP-M3-06): ist das Fahrzeug dem Dienst zum Zeitpunkt [at] zugeordnet? Der Zusage-Commit (Bestätigung) prüft das unter dem Lock erneut, damit nach dem
     * Ende einer Zuordnung keine neue Zusage entsteht. Läuft in der Transaktion des Aufrufers.
     */
    fun isAssigned(jdbc: JdbcOperations, vehicleId: UUID, serviceId: UUID, at: Instant): Boolean

    /**
     * Dienststatus (WP-M3-31): erlaubt der aktuelle Dienststatus der Schicht des Fahrzeugs, die zum Zeitpunkt [at] läuft, eine neue Zusage? `false` bei Pause, Störung, Ende oder noch nicht bereitgemeldet; ohne
     * Statuszeile `true`. Der Zusage-Commit prüft das unter der Fahrzeugsperre erneut (der Statuswechsel sperrt dieselbe Fahrzeugzeile), damit nach einer Störung keine Zusage mehr entsteht.
     */
    fun isStatusDispatchable(jdbc: JdbcOperations, vehicleId: UUID, at: Instant): Boolean
}

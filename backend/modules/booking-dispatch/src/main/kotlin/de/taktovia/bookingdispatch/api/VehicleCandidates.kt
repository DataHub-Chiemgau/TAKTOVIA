package de.taktovia.bookingdispatch.api

import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.geo.GeoPoint
import java.time.Instant
import java.util.UUID

/** Kennzahl-Kategorie einer Ablehnung (Datenbasis der Kennzahlen LH-K.1-Zeile-03/-04/-05). */
enum class RejectionCategory(val metric: String) {
    EQUIPMENT("Ausstattungsablehnungen"), PERSONNEL("Personalmangelablehnungen"), CAPACITY("Kapazitätsablehnungen"),
}

/**
 * Grund, aus dem ein Poolfahrzeug kein Kandidat ist (WP-M2-15). [depth] ist die Filterstufe: je größer, desto weiter ist das Fahrzeug im Filter gekommen. Die Stufe bestimmt
 * den Primärgrund einer Ablehnung (das am weitesten gekommene Fahrzeug nennt den engsten verbleibenden Mangel). Zuordnung zur Kategorie ist Arbeitsannahme (OP-14, Ablehnungscodes).
 */
enum class ExclusionReason(val category: RejectionCategory, val depth: Int) {
    /** Fahrzeugsperre (`active_block`). */
    VEHICLE_BLOCKED(RejectionCategory.CAPACITY, 1),
    /** Keine Schicht deckt das Fenster: Personalmangel. */
    NO_SHIFT(RejectionCategory.PERSONNEL, 2),
    /** Keine gültige, nicht widerrufene Einsatzfreigabe: niemand einsatzbereit (Freigabe verbindet Fahrzeug und Fahrerqualifikation). */
    NO_VALID_RELEASE(RejectionCategory.PERSONNEL, 3),
    /** Dienststatus der Schicht erlaubt keine neue Zuweisung: Pause, Störung, Ende oder nicht bereitgemeldet (WP-M3-31, DRV-007); der Fahrer ist nicht einsetzbar (Personalmangel, Arbeitsannahme OP-14). */
    SERVICE_STATUS(RejectionCategory.PERSONNEL, 3),
    /** Ausstattungsmerkmal fehlt oder die Dimension Rollstuhlplatz existiert am Fahrzeug nicht (wie im Hold, WP-M2-14). */
    EQUIPMENT_MISSING(RejectionCategory.EQUIPMENT, 4),
    /** Zähler der Fahrzeugkapazität (Sitze, Rollstuhlplätze) kleiner als der Bedarf. */
    CAPACITY_INSUFFICIENT(RejectionCategory.CAPACITY, 4),
    /** Geplanter Standort unbekannt (kein Fahrtenplan/Fixture): Erreichbarkeit nicht belegbar, also kein Kandidat. */
    POSITION_UNKNOWN(RejectionCategory.CAPACITY, 5),
    /** Zuordnung mit Gebietsbezug: ein Ort der Fahrt liegt außerhalb des Gebiets oder es wurde kein Ort übergeben (WP-M3-06). */
    OUTSIDE_SERVICE_AREA(RejectionCategory.CAPACITY, 1),
    /** Abholpunkt vom geplanten Standort aus nicht rechtzeitig oder gar nicht erreichbar. */
    PICKUP_UNREACHABLE(RejectionCategory.CAPACITY, 5),
}

/** Geplante Position eines Fahrzeugs laut Fahrtenplan: Haltepunkt, an dem es ab [availableAt] frei ist (M2: kein Live-Standort, OP-16). */
data class PlannedPosition(val stopId: String, val availableAt: Instant)

/**
 * Quelle der geplanten Position (Standortdefinition M2): letzter geplanter Haltepunkt des Fahrzeugs vor dem Abholfenster aus dem Fahrtenplan (`vehicle_run`/`route_stop`,
 * WP-M2-16) bzw. in M2 eine Fixture. Livepositionen (`position_sample`) liefert erst M3 (WP-M3-34) über dieselbe Schnittstelle. `null` = unbekannt.
 */
fun interface PlannedPositionSource {
    fun positionOf(ctx: RequestContext, vehicleId: UUID, shiftId: UUID, before: Instant): PlannedPosition?
}

/** Fixture-Quelle: feste Zuordnung Fahrzeug zu Position (nur M2-Nachweis). */
class FixturePlannedPositions(private val positions: Map<UUID, PlannedPosition>) : PlannedPositionSource {
    override fun positionOf(ctx: RequestContext, vehicleId: UUID, shiftId: UUID, before: Instant): PlannedPosition? = positions[vehicleId]
}

/**
 * Anfrage an den Kandidatenfilter: [window] ist der Einsatzzeitraum (Schicht und Freigabe müssen ihn ganz decken), [demand] der Bedarf als Ressourcenvektor,
 * [pickupStop] der Abholpunkt, [pickupLatest] der späteste Abholzeitpunkt (innerhalb von [window]); [profile] das Fahrzeugprofil des Routings.
 */
data class CandidateRequest(
    val serviceId: UUID, val window: TimeWindow, val demand: ResourceVector, val pickupStop: String, val pickupLatest: Instant, val profile: String = "car",
    /** Orte der Fahrt (Abholung, Ziel) für die Gebietsprüfung der Dienstzuordnung (WP-M3-06, OP-25). */
    val locations: List<GeoPoint> = emptyList(),
) {
    init { require(!pickupLatest.isBefore(window.from) && !pickupLatest.isAfter(window.to)) { "Abholzeitpunkt muss im Zeitfenster liegen" } }
}

data class VehicleCandidate(val resource: VehicleResource, val position: PlannedPosition, val travelSeconds: Int, val estimatedArrival: Instant)

data class ExcludedVehicle(val vehicleId: UUID, val reason: ExclusionReason)

/** Ablehnung ohne Kandidat: [primary] aus dem am weitesten gekommenen Fahrzeug, [secondary] die übrigen Kategorien (nach Stufe absteigend, ohne Wiederholung). */
data class CandidateRejection(val primary: RejectionCategory, val secondary: List<RejectionCategory>)

/** Ergebnis: [candidates] sortiert nach frühester Ankunft, dann Fahrzeug-ID; [rejection] genau dann gesetzt, wenn keine Kandidaten bleiben. */
data class CandidateSearch(val candidates: List<VehicleCandidate>, val excluded: List<ExcludedVehicle>, val rejection: CandidateRejection?)

/**
 * Fahrzeugkandidatenfilter (WP-M2-15, LH-6.5-OPT-002), rein lesend. Stufen: (1) Fahrzeug im Pool des Dienstes (Mandant per RLS), (2) Schicht deckt das Fenster, (3) nicht gesperrt und
 * freigegeben (Leseport [ResourceAvailability]), (4) Ressourcenvektor deckt den Bedarf je Dimension, (5) Abholpunkt vom geplanten Standort im Fenster erreichbar (Fahrzeit aus [RoutingPort]).
 * Routingfehler außer „keine Route“ sind technische Fehler und werden weitergereicht (nie ein geschätzter Kandidat). Die verbindliche Prüfung unter Sperre bleibt im Hold (WP-M2-22);
 * die Auslastung durch bestehende Belegung prüft der Filter nicht.
 */
interface VehicleCandidates {
    fun find(ctx: RequestContext, request: CandidateRequest): CandidateSearch
}

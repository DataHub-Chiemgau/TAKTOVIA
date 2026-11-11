package de.taktovia.bookingdispatch.api

import de.taktovia.platform.context.RequestContext
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Normierter Ablehnungsgrund (Kategorien des Ereignisses `booking.attempt_recorded`, Kap. 01 §1.4). Ausstattungsablehnungen zählen für die Kennzahl `Ausstattungsablehnungen`. */
enum class RejectionReason(val primaryReason: String) {
    EQUIPMENT_MISSING("EQUIPMENT_MISSING"), NO_CAPACITY_SEATS("NO_CAPACITY"), NO_CAPACITY_WHEELCHAIR("NO_CAPACITY"),
    RESOURCE_UNUSABLE("NO_CAPACITY"), RUN_NOT_ACTIVE("NO_CAPACITY"), REVISION_CONFLICT("NO_CAPACITY"),
}

/**
 * Befehl „Kapazität halten“ (Kernel von WP-M2-22; hier mit dem Ressourcenvektor aus WP-M2-14). Abschnitte sind die halboffenen Ganzzahlintervalle `[intervalFrom, intervalTo)`
 * des Fahrtenplans; [window] ist die Zeitspanne für die Einsatzprüfung über den Leseport.
 */
data class PlaceHold(
    val runId: UUID, val expectedRunRevision: Long, val serviceId: UUID, val window: TimeWindow, val intervalFrom: Int, val intervalTo: Int,
    val demand: ResourceVector, val reserve: ReserveRule = ReserveRule.NONE, val reference: String, val ttl: Duration = Duration.ofSeconds(180),
)

data class HoldView(val id: UUID, val runId: UUID, val runRevision: Long, val intervalFrom: Int, val intervalTo: Int, val vector: ResourceVector, val expiresAt: Instant, val state: String)

sealed interface HoldOutcome {
    data class Placed(val hold: HoldView, val replayed: Boolean) : HoldOutcome
    data class Rejected(val reason: RejectionReason, val dimension: Dimension?) : HoldOutcome
}

/** Kernfelder der Zusage (Schema `promise-snapshot.v1`); der Ressourcenvektor wird beim Bestätigen aus dem Hold übernommen. */
data class PromiseCore(
    val pickupEarliest: String, val pickupLatest: String, val arrivalEarliest: String, val arrivalLatest: String,
    val grossMinor: Long, val currency: String, val cancellationPolicyRef: String, val vehicleClass: String,
)

data class BookingView(val id: UUID, val holdId: UUID, val state: String, val revision: Long, val promiseSnapshot: String, val passengerTrips: List<Pair<Int, String>>)

/**
 * Kapazitätshold und Bestätigung (Kernel; WP-M2-22/-23 vervollständigen Ablauf, Zustandsautomat, Ablauf-Job und API). Beides läuft in genau einer Transaktion unter dem Zeilenlock
 * des Fahrtenplans (`vehicle_run`, stabile Reihenfolge): Belegung je Abschnitt und Dimension wird unter dem Lock geprüft, Hold/Buchung und Outbox atomar geschrieben.
 */
interface CapacityHolds {
    /** Hält die Kapazität; eine Ablehnung ist ein Ergebnis ([HoldOutcome.Rejected]), kein Fehler. Gleiche [PlaceHold.reference] liefert denselben Hold (Wiederholung). */
    fun place(ctx: RequestContext, cmd: PlaceHold): HoldOutcome

    /** Wandelt einen gültigen Hold in eine Buchung (`CONFIRMING`) mit unveränderlicher Zusage inklusive Ressourcenvektor; abgelaufen: 409 `HOLD_EXPIRED`. Wiederholung findet dieselbe Buchung. */
    fun confirm(ctx: RequestContext, holdId: UUID, serviceId: UUID, promise: PromiseCore, demand: BookingDemand): BookingView
}

package de.taktovia.stafftime.api

import de.taktovia.platform.tenant.TenantId
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Fahrzeug im angetretenen Dienst, dessen letzte gemessene Position (oder, ohne Messung, der Dienstantritt) länger zurückliegt als die Schwelle (WP-M3-42). Nur Kennungen und Zeiten. */
data class StaleVehicle(val vehicleId: UUID, val shiftId: UUID, val since: Instant)

/** Offener Klärfall eines Fahrerereignisses (Ausgang `REVIEW_REQUIRED`, WP-M3-29) im laufenden Dienst: das früheste Ereignis, nach dem kein Ereignis der Schicht mehr regulär angewendet wurde. */
data class OpenReview(val vehicleId: UUID, val shiftId: UUID, val reasonCode: String, val since: Instant)

/**
 * Meldungen des Fahrerbetriebs für die Überfälligkeitsauswertung der Leitstelle (WP-M3-42): Betriebsaufträge je Mandant ohne Personenkontext, nur lesend. `booking-dispatch` setzt aus ihnen die
 * Hinweise „Information ausstehend“ zusammen und liest die Tabellen dieses Moduls nie selbst.
 */
interface OperationalSignals {
    /** Angetretene Schichten, deren Fahrzeug seit mehr als [olderThan] keine Position gemeldet hat (Messzeit, nicht Empfangszeit). */
    fun staleVehicles(tenant: TenantId, olderThan: Duration): List<StaleVehicle>

    /** Klärfälle im laufenden Dienst, die seit mehr als [olderThan] offen sind. */
    fun openReviews(tenant: TenantId, olderThan: Duration): List<OpenReview>
}

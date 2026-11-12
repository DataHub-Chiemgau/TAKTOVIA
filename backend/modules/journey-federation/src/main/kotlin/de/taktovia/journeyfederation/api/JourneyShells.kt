package de.taktovia.journeyfederation.api

import de.taktovia.journeyfederation.internal.JdbcJourneyShells
import org.springframework.jdbc.core.JdbcOperations
import java.time.Instant
import java.util.UUID

/**
 * Journey-Hülle einer Einbetreiberbuchung (WP-M2-23, M2 OP-3, `vertraege/m2-detailvertraege.md` §5): jede lokale Buchung legt bei der Bestätigung eine `journey` mit genau einem
 * `journey_leg` an, weil `payment_intent.journey_id` und `operation.journey_id` Pflicht-Fremdschlüssel auf `journey` sind und der föderierte Fall (M6) denselben Kern benutzt.
 * Besitzer von `journey` und `journey_leg` ist dieses Modul; `booking-dispatch` legt sie nur über diesen Port an und schreibt nie in die Tabellen.
 */
data class SingleLegJourney(
    val passengerId: UUID, val quoteId: UUID, val currency: String, val state: String,
    /** Referenz des Segments (`journey_leg.federation_ref`, eindeutig je Mandant): die Buchung führt dieselbe Referenz. */
    val legRef: String, val operatorRef: String, val serviceRef: String,
    val pickupEarliest: Instant, val pickupLatest: Instant, val arrivalEarliest: Instant, val arrivalLatest: Instant,
    val grossMinor: Long, val promiseRevision: Long, val originRef: String, val destinationRef: String,
)

/** IDs der angelegten Hülle. */
data class JourneyShell(val journeyId: UUID, val legId: UUID)

/** Hülle in der laufenden Transaktion des Aufrufers (Mandantenkontext gesetzt); alles in derselben Transaktion wie die Buchung. */
interface JourneyShells {
    fun createSingleLeg(j: JdbcOperations, spec: SingleLegJourney): JourneyShell

    /** Spiegelt den Zustand der Buchung auf Journey und Segment (die Hülle hat in P1 keinen eigenen Ablauf). */
    fun mirrorState(j: JdbcOperations, journeyId: UUID, state: String)

    /**
     * Ausführungszustand des Segments der Journey (`journey_leg.execution_state`, WP-M3-29): `PLANNED`, `BOARDED`, `ALIGHTED`, `NO_SHOW`; `null`, wenn es kein Segment gibt. Mit
     * [forUpdate] unter Zeilensperre gelesen.
     */
    fun executionState(j: JdbcOperations, journeyId: UUID, forUpdate: Boolean = false): String?

    /** Setzt den Ausführungszustand; zulässig sind nur `PLANNED -> BOARDED | NO_SHOW` und `BOARDED -> ALIGHTED` (Datenbankwächter, sonst SQLSTATE 23514). */
    fun setExecutionState(j: JdbcOperations, journeyId: UUID, to: String)

    companion object { fun create(): JourneyShells = JdbcJourneyShells() }
}

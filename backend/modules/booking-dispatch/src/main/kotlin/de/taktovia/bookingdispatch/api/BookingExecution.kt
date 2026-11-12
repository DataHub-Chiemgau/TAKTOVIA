package de.taktovia.bookingdispatch.api

import de.taktovia.bookingdispatch.internal.JdbcBookingExecution
import de.taktovia.journeyfederation.api.JourneyShells
import de.taktovia.platform.outbox.Outbox
import org.springframework.jdbc.core.JdbcOperations
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Art der Ausführungsmeldung eines Fahrers am Stopp (WP-M3-29): Ankunft, Einstieg, Ausstieg, Nicht-Erscheinen. */
enum class ExecutionKind { ARRIVAL, BOARDED, ALIGHTED, NO_SHOW }

/**
 * Meldung eines Fahrerereignisses an die Ausführung. [bookingId] ist bei [ExecutionKind.ARRIVAL] leer (eine Ankunft kann mehrere oder keine Buchung betreffen). [eventId] wird als Korrelation
 * in die Statushistorie der Buchung geschrieben (Rückverfolgung zum Fahrerereignis); [at] ist die plausibilisierte Ereigniszeit.
 */
data class ExecutionRequest(val shiftId: UUID, val vehicleId: UUID, val routeStopId: UUID, val bookingId: UUID?, val kind: ExecutionKind, val at: Instant, val eventId: UUID)

/** Ausgang einer Ausführungsmeldung; kein Ausgang außer [Applied] verändert Buchung oder Fahrt. */
sealed interface ExecutionResult {
    /** Wirksam: Zustand der Buchung und des Fahrtsegments danach (`null` bei Ankunft). */
    data class Applied(val bookingState: String?, val legState: String?) : ExecutionResult

    /** Nicht wirksam, Klärfall der Leitstelle: [reasonCode] ist `OUTSIDE_STOP`, `INVALID_ORDER`, `STALE_PLAN` oder `BOOKING_CANCELED`. */
    data class Review(val reasonCode: String) : ExecutionResult

    /** Schicht/Fahrzeug ohne Umlauf, Stopp nicht im Umlauf oder Buchung nicht im Plan des Umlaufs: einheitlich „nicht gefunden“ (kein Hinweis auf fremde Objekte). */
    data object NotFound : ExecutionResult
}

/**
 * Wirkung der Fahrerereignisse auf Buchung und Fahrt (WP-M3-29, LH-6.6-DRV-005), einziger Schreibweg für `journey_leg.execution_state` und die Ausführungsübergänge der Buchung.
 * Läuft **in der Transaktion des Aufrufers** (`staff-time`, Mandantenkontext gesetzt): Statuswirkung, Historie und Ereignis entstehen gemeinsam mit dem Fahrerereignis oder gar nicht.
 *
 * Übergänge (Planungsannahme, der Fahrtautomat ist nicht spezifiziert, M3 OP-21; `vertraege/zustaende/fahrerereignis.md`): Einstieg `PLANNED -> BOARDED` (Buchung bleibt `CONFIRMED`),
 * Ausstieg `BOARDED -> ALIGHTED` und Buchung `CONFIRMED -> COMPLETED` (Grund `TRIP_COMPLETED`), Nicht-Erscheinen `PLANNED -> NO_SHOW` und Buchung `CONFIRMED -> CANCEL_PENDING -> CANCELED`
 * (Grund `NO_SHOW`, Verursacher Fahrgast, **keine** Gebühr: BKG-015 ist P2). Die Buchung wird vor jeder Prüfung unter Zeilensperre gelesen: ein paralleler Storno oder ein bereits geändertes
 * Segment ergibt einen Klärfall, nie einen Zustandsrücksprung.
 */
interface BookingExecution {
    fun apply(j: JdbcOperations, r: ExecutionRequest): ExecutionResult

    /** Dienst der Buchung (für die Auflösung des Dienstparameters `no_show_grace_min`), `null` wenn die Buchung nicht existiert. */
    fun serviceOf(j: JdbcOperations, bookingId: UUID): UUID?

    companion object {
        fun create(outbox: Outbox, clock: Clock = Clock.systemUTC()): BookingExecution = JdbcBookingExecution(outbox, clock, JourneyShells.create())
    }
}

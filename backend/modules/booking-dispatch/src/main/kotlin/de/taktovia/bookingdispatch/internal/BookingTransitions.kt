package de.taktovia.bookingdispatch.internal

import de.taktovia.journeyfederation.api.JourneyShells
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.statemachine.BookingState
import de.taktovia.platform.statemachine.StateMachines
import de.taktovia.platform.statemachine.StateTransitioner
import de.taktovia.platform.statemachine.Transition
import org.springframework.jdbc.core.JdbcOperations
import java.time.Clock
import java.util.UUID

/**
 * Einziger Weg, den Zustand einer Buchung zu ändern (Automat `booking`, WP-M1-17): Zeile sperren, Revision prüfen (CAS), Übergang gegen die Tabelle prüfen, Zustand und Revision schreiben,
 * Ereignis `booking.state_changed` in dieselbe Transaktion legen und den Zustand auf die Journey-Hülle spiegeln.
 */
internal class BookingTransitions(outbox: Outbox, clock: Clock, private val journeys: JourneyShells) {
    private val transitioner = StateTransitioner(outbox, clock)

    fun apply(j: JdbcOperations, bookingId: UUID, to: BookingState, expectedRevision: Long?, correlationId: UUID?): Transition<BookingState> {
        val t = transitioner.transition(j, StateMachines.BOOKING, bookingId, to, expectedRevision, correlationId)
        j.query("SELECT journey_id FROM taktovia.booking WHERE id = ?", { rs, _ -> rs.getObject(1, UUID::class.java) }, bookingId).firstOrNull()?.let { journeys.mirrorState(j, it, to.name) }
        return t
    }
}

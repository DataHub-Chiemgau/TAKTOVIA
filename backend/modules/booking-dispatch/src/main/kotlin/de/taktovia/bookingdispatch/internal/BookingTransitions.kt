package de.taktovia.bookingdispatch.internal

import de.taktovia.bookingdispatch.api.BookingReason
import de.taktovia.journeyfederation.api.JourneyShells
import de.taktovia.platform.approval.FourEyes
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.platform.statemachine.BookingState
import de.taktovia.platform.statemachine.StateMachines
import de.taktovia.platform.statemachine.StateTransitioner
import de.taktovia.platform.statemachine.Transition
import org.springframework.jdbc.core.JdbcOperations
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID

/**
 * Einziger Weg, den Zustand einer Buchung zu ändern (Automat `booking`, WP-M1-17): Zeile sperren, Revision prüfen (CAS), Übergang gegen die Tabelle prüfen, Zustand und Revision schreiben,
 * Ereignis `booking.state_changed` und **Historieneintrag mit Grund** (WP-M2-25) in dieselbe Transaktion legen und den Zustand auf die Journey-Hülle spiegeln. Die Datenbank prüft beim Commit,
 * dass Einträge und Revision deckungsgleich sind.
 */
internal class BookingTransitions(outbox: Outbox, private val clock: Clock, private val journeys: JourneyShells) {
    private val transitioner = StateTransitioner(outbox, clock)

    fun apply(j: JdbcOperations, ctx: RequestContext, bookingId: UUID, to: BookingState, reason: BookingReason, expectedRevision: Long?): Transition<BookingState> {
        if (to !in reason.targets) throw ProblemException.invalid(Violation("/reason", ViolationCode.NOT_IN_ENUM))   // der Grund passt nicht zum Zielzustand
        val t = transitioner.transition(j, StateMachines.BOOKING, bookingId, to, expectedRevision ?: (currentRevision(j, bookingId) ?: throw ProblemException.notFound()), ctx.correlationId)
        record(j, ctx, bookingId, t.from.name, t.to.name, reason, t.revision)
        // Stornogrund und Verursacherklasse: genau einmal beim Eintritt in den Storno- bzw. Kompensationspfad (danach unveränderlich, auch per SQL)
        if (reason.cause != null && (to == BookingState.CANCEL_PENDING || to == BookingState.COMPENSATING))
            j.update("UPDATE taktovia.booking SET cancel_reason_code = ?, cancel_cause_class = ? WHERE id = ? AND cancel_reason_code IS NULL", reason.name, reason.cause.name, bookingId)
        j.query("SELECT journey_id FROM taktovia.booking WHERE id = ?", { rs, _ -> rs.getObject(1, UUID::class.java) }, bookingId).firstOrNull()?.let { journeys.mirrorState(j, it, to.name) }
        return t
    }

    /** Erster Eintrag der Historie (Anlage der Buchung, Revision 1, ohne Vorzustand). */
    fun recordCreated(j: JdbcOperations, ctx: RequestContext, bookingId: UUID, state: BookingState) = record(j, ctx, bookingId, null, state.name, BookingReason.HOLD_CONFIRMED, 1)

    private fun currentRevision(j: JdbcOperations, bookingId: UUID): Long? =
        j.query("SELECT revision FROM taktovia.booking WHERE id = ? FOR UPDATE", { rs, _ -> rs.getLong(1) }, bookingId).firstOrNull()   // ohne If-Match: unter der Sperre den aktuellen Stand lesen

    private fun record(j: JdbcOperations, ctx: RequestContext, bookingId: UUID, from: String?, to: String, reason: BookingReason, revision: Long) {
        val actorKind = when { ctx.actor.type == ActorType.SERVICE -> "SYSTEM"; "passenger" in ctx.roles -> "PASSENGER"; else -> "OPERATOR" }
        j.update(
            "INSERT INTO taktovia.booking_status_history(tenant_id, id, booking_id, from_state, to_state, reason_code, actor_ref, actor_kind, occurred_at, booking_revision, correlation_id, attempt_id, offer_id, hold_id, quote_id) " +
                "SELECT taktovia.current_tenant(), ?, b.id, ?, ?, ?, ?, ?, ?, ?, ?, o.attempt_id, o.id, b.hold_id, o.quote_id FROM taktovia.booking b " +
                "LEFT JOIN taktovia.ride_offer o ON o.tenant_id = b.tenant_id AND o.hold_id = b.hold_id WHERE b.id = ?",
            UUID.randomUUID(), from, to, reason.name, FourEyes.refOf(ctx).value, actorKind, Timestamp.from(clock.instant()), revision, ctx.correlationId, bookingId,
        )
    }
}

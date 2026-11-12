package de.taktovia.stafftime.internal

import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.outbox.EventEnvelope
import de.taktovia.platform.outbox.AggregateSnapshot
import de.taktovia.platform.outbox.InboxHandler
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.outbox.OutboxEvent
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.stafftime.api.BOARDING_CODE_TRIGGER_EVENT
import de.taktovia.stafftime.api.BoardingCodeSink
import de.taktovia.stafftime.api.BoardingCodeValue
import de.taktovia.stafftime.api.BoardingCodes
import de.taktovia.stafftime.api.IssuedBoardingCode
import de.taktovia.stafftime.api.StafftimeSettings
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcOperations
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Ausstellung und Prüfung des Boarding-Codes (WP-M3-30). Der Klartext existiert nur im Rückgabewert der Ausstellung und in der Eingabe der Prüfung. */
internal class JdbcBoardingCodes(
    private val ctxTx: ContextTransactions, private val outbox: Outbox, private val clock: Clock, private val settings: StafftimeSettings,
) : BoardingCodes {

    override fun issue(ctx: RequestContext, bookingId: UUID): IssuedBoardingCode {
        if ("dispatcher" !in ctx.roles) throw ProblemException(ErrorCode.FORBIDDEN)
        return ctxTx.run(ctx) { j ->
            try { issueIn(j, bookingId, "user:" + ctx.actor.subject) ?: throw ProblemException(ErrorCode.CONFLICT, "Für diese Buchung wurde bereits ein Boarding-Code ausgestellt.") }
            catch (e: org.springframework.dao.DataIntegrityViolationException) {
                if (e.cause?.message?.contains("foreign key") == true) throw ProblemException.notFound() else throw e   // Buchung nicht im Mandanten: nicht auffindbar
            }
        }
    }

    override fun issueIn(j: JdbcOperations, bookingId: UUID, createdBy: String): IssuedBoardingCode? {
        val code = BoardingCodeHash.newCode(); val validUntil = clock.instant().plus(settings.boardingCodeValidity)
        val n = j.update(
            "INSERT INTO taktovia.boarding_code(tenant_id, id, booking_id, code_hash, valid_until, created_by) VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?) ON CONFLICT (tenant_id, booking_id) DO NOTHING",
            UUID.randomUUID(), bookingId, BoardingCodeHash.hash(code, settings.boardingCodePepper), Timestamp.from(validUntil), createdBy)
        return if (n == 1) IssuedBoardingCode(bookingId, BoardingCodeValue(code), validUntil) else null
    }

    override fun eventConsumer(sink: BoardingCodeSink?): InboxHandler = object : InboxHandler {
        override fun handle(jdbc: JdbcOperations, event: EventEnvelope) {
            if (event.eventType != BOARDING_CODE_TRIGGER_EVENT) return
            val bookingId = UUID.fromString(event.payload.get("booking_id").asString())
            issueIn(jdbc, bookingId, "service:boarding-code")?.let { sink?.deliver(jdbc, it) }
        }
        override fun applySnapshot(jdbc: JdbcOperations, snapshot: AggregateSnapshot) {}   // der Code folgt keinem Aggregatzustand: ein Lückenschluss braucht nichts
    }

    // ---- Prüfung beim Check-in (aufgerufen vom Ereigniseingang in dessen Transaktion) ---------------------------------------------------

    /** Ergebnis der Prüfung; nur [Valid] erlaubt den Check-in. [ledger] ist der Eintrag, der beim Check-in entwertet wird. */
    internal sealed interface Check {
        data class Valid(val codeId: UUID) : Check
        data object Invalid : Check
        data object Used : Check
        data object Locked : Check
    }

    /**
     * Prüft den Code gegen die Buchung unter Zeilensperre (zwei Geräte mit demselben Code laufen nacheinander). Ein falscher Code erhöht den Fehlversuchszähler (und sperrt bei Erreichen
     * der Grenze endgültig, mit Hinweisereignis an die Leitstelle); ein richtiger Code ändert nichts, er wird erst mit [consume] entwertet, wenn der Check-in tatsächlich wirkt.
     */
    fun verify(j: JdbcOperations, bookingId: UUID, code: BoardingCodeValue, now: Instant): Check {
        val row = j.query("SELECT id, code_hash, valid_until, failed_attempts, used_at, locked_at FROM taktovia.boarding_code WHERE booking_id = ? FOR UPDATE", { rs, _ ->
            Row(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getTimestamp(3).toInstant(), rs.getInt(4), rs.getTimestamp(5) != null, rs.getTimestamp(6) != null)
        }, bookingId).firstOrNull() ?: return Check.Invalid          // die Buchung hat keinen Code: wie ein falscher Code, ohne Zähler
        if (row.locked || row.attempts >= settings.boardingCodeMaxAttempts) return Check.Locked
        if (row.used) return Check.Used
        if (!row.validUntil.isAfter(now)) return Check.Invalid
        if (BoardingCodeHash.verify(code.value, settings.boardingCodePepper, row.hash)) return Check.Valid(row.id)
        val attempts = row.attempts + 1; val lock = attempts >= settings.boardingCodeMaxAttempts
        j.update("UPDATE taktovia.boarding_code SET failed_attempts = ?, locked_at = CASE WHEN ? THEN ? ELSE locked_at END WHERE id = ?", attempts, lock, Timestamp.from(now), row.id)
        if (lock) outbox.append(j, OutboxEvent(bookingId, 1, "driver.boarding_code_locked", linkedMapOf("booking_id" to bookingId.toString(), "failed_attempts" to attempts, "locked_at" to now.toString()), clock.instant()))
        return Check.Invalid
    }

    /** Entwertet den Code beim wirksamen Check-in (einmalig, durch den Datenbankwächter endgültig). */
    fun consume(j: JdbcOperations, codeId: UUID, eventId: UUID, now: Instant) {
        j.update("UPDATE taktovia.boarding_code SET used_at = ?, used_event_id = ? WHERE id = ? AND used_at IS NULL", Timestamp.from(now), eventId, codeId)
    }

    private class Row(val id: UUID, val hash: String, val validUntil: Instant, val attempts: Int, val used: Boolean, val locked: Boolean)
}

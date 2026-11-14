package de.taktovia.bookingdispatch.internal

import de.taktovia.platform.approval.FourEyes
import de.taktovia.platform.audit.AuditEntry
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.audit.ObjectRef
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import org.springframework.jdbc.core.JdbcOperations
import java.time.Clock
import java.util.UUID

/**
 * Änderungsprotokoll der Leitstelle (WP-M3-39): `booking_change` (append-only, Akteur, Grundcode, Freitext, alte und neue Zusage) und der Auditeintrag mit demselben Akteur, beides in der
 * Transaktion der Änderung. Der Akteur ist der Disponent (`user:<subject>`), nie der Fahrgast; ohne Auditport (Tests einzelner Dienste) entfällt nur der Auditeintrag, nie das Änderungsprotokoll.
 */
internal class BookingChanges(private val audit: AuditPort?, @Suppress("unused") private val clock: Clock) {

    /** Grundtext: höchstens 500 Zeichen (Spalte), kein Steuerzeichen; leerer Text gilt als nicht angegeben. */
    fun text(raw: String?, pointer: String): String? {
        val t = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (t.length > 500) throw de.taktovia.platform.problem.ProblemException.invalid(Violation(pointer, ViolationCode.TOO_LONG))
        if (t.any { it.isISOControl() && it != '\n' }) throw de.taktovia.platform.problem.ProblemException.invalid(Violation(pointer, ViolationCode.INVALID_VALUE))
        return t
    }

    fun record(j: JdbcOperations, ctx: RequestContext, bookingId: UUID, kind: String, reasonCode: String, reasonText: String?, oldPromise: String?, newPromise: String?, newBookingId: UUID?, consent: String?): UUID {
        val id = UUID.randomUUID()
        j.update(
            "INSERT INTO taktovia.booking_change(tenant_id, id, booking_id, change_kind, actor, reason, reason_code, old_promise, new_promise, new_booking_id, consent) " +
                "VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, CAST(? AS jsonb))",
            id, bookingId, kind, FourEyes.refOf(ctx).value, reasonText, reasonCode, oldPromise, newPromise, newBookingId, consent,
        )
        return id
    }

    fun audit(j: JdbcOperations, ctx: RequestContext, action: String, bookingId: UUID, details: Map<String, Any?>) {
        audit?.record(j, AuditEntry(FourEyes.refOf(ctx), action, ObjectRef("booking", bookingId), ctx.correlationId, details))
    }
}

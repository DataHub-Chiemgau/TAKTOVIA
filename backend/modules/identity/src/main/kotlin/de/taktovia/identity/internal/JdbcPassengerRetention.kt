package de.taktovia.identity.internal

import de.taktovia.identity.api.ArchivedPassenger
import de.taktovia.identity.api.ContactChannel
import de.taktovia.identity.api.PassengerArchive
import de.taktovia.platform.audit.ActorRef
import de.taktovia.platform.audit.AuditActions
import de.taktovia.platform.audit.AuditEntry
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.audit.AuditRequired
import de.taktovia.platform.audit.ObjectRef
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.retention.ApplyResult
import de.taktovia.platform.retention.ArchiveAccess
import de.taktovia.platform.retention.Candidate
import de.taktovia.platform.retention.RetentionAction
import de.taktovia.platform.retention.RetentionTarget
import de.taktovia.platform.retention.RuleSnapshot
import org.springframework.jdbc.core.JdbcOperations
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Löschziel `LK_PII` für das Fahrgastkonto. Fällig ist ein Konto, wenn `retention_until` plus Nachlauffrist der Regel erreicht ist; ein archiviertes Konto wird nach
 * `archive_until` anonymisiert. Das Leeren der Personenspalten (auch der Ciphertext-Spalten, für die die Runtime-Rolle kein Recht hat) erledigt der Datenbank-Trigger
 * `passenger_private_erasure` atomar beim Setzen von `erased_at`; danach kann keine Personenangabe zurückkehren.
 */
internal class JdbcPassengerRetentionTarget : RetentionTarget {
    override val dataClass = "LK_PII"
    override val objectType = "passenger"
    override val supportedActions = setOf(RetentionAction.DELETE, RetentionAction.ANONYMIZE, RetentionAction.ARCHIVE)

    override fun candidates(jdbc: JdbcOperations, rule: RuleSnapshot, now: Instant, after: UUID?, limit: Int): List<Candidate> {
        val ts = Timestamp.from(now)
        return jdbc.query(
            "SELECT id, archived_at IS NOT NULL FROM taktovia.passenger_private WHERE erased_at IS NULL " +
                "AND ((archived_at IS NULL AND retention_until + make_interval(days => ?) <= ?) OR (archived_at IS NOT NULL AND archive_until <= ?)) " +
                "AND (CAST(? AS uuid) IS NULL OR id > CAST(? AS uuid)) ORDER BY id LIMIT ?",
            { rs, _ -> Candidate(rs.getObject(1, UUID::class.java), if (rs.getBoolean(2)) RetentionAction.ANONYMIZE else rule.action) },
            rule.retentionDays, ts, ts, after, after, limit,
        )
    }

    override fun apply(jdbc: JdbcOperations, rule: RuleSnapshot, candidate: Candidate, now: Instant): ApplyResult {
        val id = candidate.id
        return when (candidate.action) {
            RetentionAction.ARCHIVE -> {
                val until = now.plus(rule.archiveDays!!.toLong(), ChronoUnit.DAYS)
                jdbc.update("UPDATE taktovia.passenger_private SET archived_at = ?, archive_until = ? WHERE id = ? AND erased_at IS NULL AND archived_at IS NULL", Timestamp.from(now), Timestamp.from(until), id)
                ApplyResult(RetentionAction.ARCHIVE, until)
            }
            RetentionAction.DELETE, RetentionAction.ANONYMIZE -> {
                // Zeile in den Löschzustand (Trigger leert die Personenspalten), danach die Kanäle, Aufgaben und Präferenzen des Fahrgasts entfernen.
                jdbc.update("UPDATE taktovia.passenger_private SET erased_at = ? WHERE id = ? AND erased_at IS NULL", Timestamp.from(now), id)
                jdbc.update("DELETE FROM taktovia.passenger_preference WHERE passenger_id = ?", id)
                jdbc.update("DELETE FROM taktovia.contact_verification WHERE passenger_id = ?", id)
                jdbc.update("DELETE FROM taktovia.passenger_contact WHERE passenger_id = ?", id)
                ApplyResult(RetentionAction.ANONYMIZE)
            }
        }
    }

    /** Objekte des Fahrgasts im Objektspeicher (z. B. hochgeladene Unterlagen) liegen unter `passenger/<id>/`. */
    override fun objectPrefixes(id: UUID) = listOf("passenger/$id/")
}

internal class JdbcPassengerArchive(private val ctxTx: ContextTransactions, private val audit: AuditPort) : PassengerArchive {
    override fun read(ctx: RequestContext, passengerId: UUID): ArchivedPassenger {
        if (!ctx.hasScope("retention:read")) throw ProblemException(ErrorCode.FORBIDDEN)
        return ctxTx.run(ctx) { j -> doRead(j, ActorRef((if (ctx.actor.type == ActorType.SERVICE) "service:" else "user:") + ctx.actor.subject), ctx, passengerId) }
    }

    @AuditRequired
    private fun doRead(j: JdbcOperations, actor: ActorRef, ctx: RequestContext, id: UUID): ArchivedPassenger {
        ArchiveAccess.enable(j)
        val row = j.query(
            "SELECT archived_at, archive_until, retention_until FROM taktovia.passenger_private WHERE id = ? AND erased_at IS NULL AND archived_at IS NOT NULL AND archive_until > clock_timestamp()",
            { rs, _ -> Triple(rs.getTimestamp(1).toInstant(), rs.getTimestamp(2).toInstant(), rs.getTimestamp(3).toInstant()) }, id,
        ).firstOrNull() ?: throw ProblemException.notFound()
        val channels = j.query("SELECT channel FROM taktovia.passenger_contact WHERE passenger_id = ?", { rs, _ -> ContactChannel.valueOf(rs.getString(1)) }, id).toSet()
        audit.record(j, AuditEntry(actor, AuditActions.RETENTION_ARCHIVE_READ, ObjectRef("passenger", id), ctx.correlationId, mapOf("archive_until" to row.second.toString())))
        return ArchivedPassenger(id, row.first, row.second, row.third, channels)
    }
}

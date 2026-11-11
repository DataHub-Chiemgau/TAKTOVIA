package de.taktovia.communicationsupport.internal

import de.taktovia.communicationsupport.api.EnqueueResult
import de.taktovia.communicationsupport.api.FailureReason
import de.taktovia.communicationsupport.api.NotificationChannel
import de.taktovia.communicationsupport.api.NotificationEventTypes
import de.taktovia.communicationsupport.api.NotificationRequest
import de.taktovia.communicationsupport.api.NotificationService
import de.taktovia.communicationsupport.api.NotificationState
import de.taktovia.communicationsupport.api.NotificationView
import de.taktovia.communicationsupport.api.Placeholder
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import tools.jackson.databind.json.JsonMapper
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * Auftragsanlage und Lesen (WP-M2-32). Anlage: in der Transaktion des Aufrufers, einmalig je (Ereignis, Empfänger, Kanal) über den Unique-Schlüssel
 * (`ON CONFLICT DO NOTHING`: ein paralleler zweiter Aufruf wartet auf den ersten und findet dann dessen Auftrag). Kontaktdaten und Inhalte werden nie gespeichert;
 * [NotificationRequest.params] sind die typisierten Platzhalterwerte (nur Schlüssel des Katalogs).
 */
internal class JdbcNotifications(private val ctxTx: ContextTransactions) : NotificationService {
    private val mapper = JsonMapper.builder().build()

    override fun send(jdbc: JdbcOperations, request: NotificationRequest): EnqueueResult {
        validate(request)
        val id = UUID.randomUUID()
        val inserted = try {
            jdbc.query(
                "INSERT INTO taktovia.notification(tenant_id, id, event_id, recipient_ref, channel, event_type, service_id, params) " +
                    "VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?, CAST(? AS jsonb)) ON CONFLICT (tenant_id, event_id, recipient_ref, channel) DO NOTHING RETURNING id",
                { rs, _ -> rs.getObject(1, UUID::class.java) },
                id, request.eventId, request.recipientRef, request.channel.name, request.eventType, request.serviceId, mapper.writeValueAsString(request.params),
            ).firstOrNull()
        } catch (e: DataIntegrityViolationException) {
            if (sqlState(e) == "23503") throw ProblemException.notFound()   // Empfänger oder Dienst im Mandanten unbekannt (fremder Mandant ist ununterscheidbar)
            throw e
        }
        if (inserted != null) return EnqueueResult(inserted, true)
        val existing = jdbc.queryForObject("SELECT id FROM taktovia.notification WHERE event_id = ? AND recipient_ref = ? AND channel = ?", UUID::class.java,
            request.eventId, request.recipientRef, request.channel.name)!!
        return EnqueueResult(existing, false)
    }

    private fun validate(r: NotificationRequest) {
        if (!NotificationEventTypes.isKnown(r.eventType)) throw ProblemException.invalid(Violation("/event_type", ViolationCode.NOT_IN_ENUM))
        for ((k, v) in r.params) {
            if (Placeholder.byKey(k) == null) throw ProblemException.invalid(Violation("/params/$k", ViolationCode.UNKNOWN_FIELD))
            if (v.length > 200) throw ProblemException.invalid(Violation("/params/$k", ViolationCode.TOO_LONG))
        }
    }

    override fun list(ctx: RequestContext, state: NotificationState?, limit: Int, after: Pair<Instant, UUID>?): List<NotificationView> = ctxTx.run(ctx, readOnly = true) { j ->
        val afterTs = after?.first?.let(Timestamp::from)
        j.query(
            "SELECT $COLUMNS FROM taktovia.notification n WHERE (CAST(? AS text) IS NULL OR n.state = CAST(? AS text)) " +
                "AND (CAST(? AS timestamptz) IS NULL OR (n.created_at, n.id) > (CAST(? AS timestamptz), CAST(? AS uuid))) ORDER BY n.created_at, n.id LIMIT ?",
            MAPPER, state?.name, state?.name, afterTs, afterTs, after?.second, limit + 1,
        )
    }

    override fun get(ctx: RequestContext, id: UUID): NotificationView = ctxTx.run(ctx, readOnly = true) { j ->
        j.query("SELECT $COLUMNS FROM taktovia.notification n WHERE n.id = ?", MAPPER, id).firstOrNull()   // RLS: fremder Mandant ist nicht sichtbar
    } ?: throw ProblemException.notFound()

    private fun sqlState(e: Throwable): String? = generateSequence(e) { it.cause }.filterIsInstance<SQLException>().firstOrNull()?.sqlState

    private companion object {
        const val COLUMNS = "n.id, n.event_id, n.channel, n.event_type, n.state, n.attempt_count, n.failure_reason, n.created_at, n.revision"
        val MAPPER = RowMapper { rs, _ ->
            NotificationView(
                rs.getObject("id", UUID::class.java), rs.getObject("event_id", UUID::class.java), NotificationChannel.valueOf(rs.getString("channel")), rs.getString("event_type"),
                NotificationState.valueOf(rs.getString("state")), rs.getInt("attempt_count"), rs.getString("failure_reason")?.let { FailureReason.valueOf(it) },
                rs.getTimestamp("created_at").toInstant(), rs.getLong("revision"),
            )
        }
    }
}

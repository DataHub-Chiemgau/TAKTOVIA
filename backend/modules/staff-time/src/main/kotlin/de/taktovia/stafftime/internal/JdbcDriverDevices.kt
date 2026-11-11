package de.taktovia.stafftime.internal

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
import de.taktovia.stafftime.api.DevicePlatform
import de.taktovia.stafftime.api.DriverDeviceView
import de.taktovia.stafftime.api.DriverDevices
import de.taktovia.stafftime.api.RegisterDriverDevice
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import java.util.UUID

/**
 * Geräte-/Sitzungsbindung (WP-M3-24). Serialisierung über die Gerätezeile (`FOR UPDATE`); die Datenbank sichert Identität und Endgültigkeit der Beendigung (Trigger).
 * Die Sitzung ist die IdP-Sitzungs-ID des Tokens; ohne Sitzungskennung (Dienstkonten) gibt es keine Bindung.
 */
internal class JdbcDriverDevices(private val ctxTx: ContextTransactions, private val audit: AuditPort) : DriverDevices {

    override fun register(ctx: RequestContext, cmd: RegisterDriverDevice): DriverDeviceView {
        val session = requireHumanSession(ctx)
        return ctxTx.run(ctx) { j -> doRegister(j, ctx, session, cmd) }
    }

    override fun revoke(ctx: RequestContext, deviceId: UUID, expectedRevision: Long) {
        requireHumanSession(ctx)
        ctxTx.run(ctx) { j -> doRevoke(j, ctx, deviceId, expectedRevision) }
    }

    @AuditRequired
    private fun doRegister(j: JdbcOperations, ctx: RequestContext, session: String, cmd: RegisterDriverDevice): DriverDeviceView {
        // atomar anlegen; unterlegene parallele Erstregistrierung fällt in den Pfad „bereits vorhanden“ (kein Fehler)
        val created = j.query(
            "INSERT INTO taktovia.driver_device AS d (tenant_id, id, issuer, subject, idp_session_id, platform, app_version, push_token_ref) VALUES (taktovia.current_tenant(),?,?,?,?,?,?,?) " +
                "ON CONFLICT (tenant_id, id) DO NOTHING RETURNING $RETURNING",
            ROW, cmd.deviceId, ctx.actor.issuer, ctx.actor.subject, session, cmd.platform.name, cmd.appVersion, cmd.pushTokenRef,
        ).firstOrNull()
        if (created != null) {
            audit.record(j, entry(ctx, created.row, "registered"))
            return created.row.view(session)
        }
        val existing = j.query("SELECT $COLUMNS FROM taktovia.driver_device d WHERE d.id = ? FOR UPDATE", ROW, cmd.deviceId).single()
        // fremdes Konto: das Gerät ist nicht übernehmbar (Konflikt, ohne Auskunft über das andere Konto); beendete Bindung ist endgültig
        if (existing.issuer != ctx.actor.issuer || existing.subject != ctx.actor.subject || existing.row.revokedAt != null)
            throw ProblemException(ErrorCode.CONFLICT, "Das Gerät kann nicht an diese Sitzung gebunden werden; bitte die App neu einrichten.")
        if (existing.session == session) return existing.row.view(session)   // wiederholt: unverändert
        val v = j.query("UPDATE taktovia.driver_device d SET idp_session_id = ?, app_version = ?, push_token_ref = ? WHERE d.id = ? RETURNING $RETURNING", ROW, session, cmd.appVersion, cmd.pushTokenRef, cmd.deviceId).single()
        audit.record(j, entry(ctx, v.row, "rebound"))
        return v.row.view(session)
    }

    @AuditRequired
    private fun doRevoke(j: JdbcOperations, ctx: RequestContext, deviceId: UUID, expectedRevision: Long) {
        val d = j.query("SELECT $COLUMNS FROM taktovia.driver_device d WHERE d.id = ? FOR UPDATE", ROW, deviceId).firstOrNull()
        if (d == null || d.issuer != ctx.actor.issuer || d.subject != ctx.actor.subject) throw ProblemException.notFound()   // fremdes Gerät: nicht auffindbar
        if (d.row.revision != expectedRevision) throw ProblemException(ErrorCode.REVISION_MISMATCH)
        if (d.row.revokedAt != null) return   // wiederholt: bereits beendet
        val v = j.query("UPDATE taktovia.driver_device d SET revoked_at = clock_timestamp() WHERE d.id = ? RETURNING $RETURNING", ROW, deviceId).single()
        audit.record(j, entry(ctx, v.row, "revoked"))
    }

    private fun entry(ctx: RequestContext, d: Row, verb: String) =
        AuditEntry(ActorRef("user:" + ctx.actor.subject), if (verb == "revoked") AuditActions.DRIVER_DEVICE_REVOKED else AuditActions.DRIVER_DEVICE_REGISTERED,
            ObjectRef("driver_device", d.deviceId), ctx.correlationId, mapOf("platform" to d.platform.name, "change" to verb, "revision" to d.revision))

    private fun requireHumanSession(ctx: RequestContext): String {
        if (ctx.actor.type != ActorType.HUMAN) throw ProblemException(ErrorCode.FORBIDDEN)
        return ctx.actor.sessionId ?: throw ProblemException(ErrorCode.FORBIDDEN)
    }

    private class Row(val deviceId: UUID, val platform: DevicePlatform, val appVersion: String, val registeredAt: java.time.Instant, val revokedAt: java.time.Instant?, val revision: Long) {
        fun view(session: String) = DriverDeviceView(deviceId, sessionRef(session), platform, appVersion, registeredAt, revokedAt, revision)
    }
    private class Existing(val row: Row, val issuer: String, val subject: String, val session: String)

    private companion object {
        const val COLUMNS = "d.id, d.platform, d.app_version, d.registered_at, d.revoked_at, d.revision, d.issuer, d.subject, d.idp_session_id"
        const val RETURNING = COLUMNS
        /** Stabile Kennung der gebundenen Sitzung für die Antwort (kein Rückschluss auf die IdP-Sitzungs-ID). */
        fun sessionRef(session: String): UUID = UUID.nameUUIDFromBytes(session.toByteArray())
        val ROW = RowMapper { rs, _ ->
            Existing(Row(rs.getObject("id", UUID::class.java), DevicePlatform.valueOf(rs.getString("platform")), rs.getString("app_version"), rs.getTimestamp("registered_at").toInstant(),
                rs.getTimestamp("revoked_at")?.toInstant(), rs.getLong("revision")), rs.getString("issuer"), rs.getString("subject"), rs.getString("idp_session_id"))
        }
    }
}

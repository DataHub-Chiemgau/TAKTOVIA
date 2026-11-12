package de.taktovia.stafftime.internal

import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import org.springframework.jdbc.core.JdbcOperations
import java.util.UUID

/** Prüfung des Fahrer-Geräts für Dienstantritt (WP-M3-25) und Positionserfassung (WP-M3-34). */
internal object DeviceGuard {
    /** Das Gerät muss dem Konto gehören (fremd oder unbekannt: 404), nicht beendet und an die aktuelle Sitzung gebunden sein (sonst 403). */
    fun requireBound(j: JdbcOperations, ctx: RequestContext, deviceId: UUID): UUID {
        val session = ctx.actor.sessionId ?: throw ProblemException(ErrorCode.FORBIDDEN)
        val d = j.query("SELECT issuer, subject, idp_session_id, revoked_at IS NOT NULL FROM taktovia.driver_device WHERE id = ?",
            { rs, _ -> arrayOf<Any?>(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4)) }, deviceId).firstOrNull()
        if (d == null || d[0] != ctx.actor.issuer || d[1] != ctx.actor.subject) throw ProblemException.notFound()
        if (d[3] == true || d[2] != session) throw ProblemException(ErrorCode.FORBIDDEN)
        return deviceId
    }
}

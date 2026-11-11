package de.taktovia.servicegeo.internal

import de.taktovia.platform.audit.ActorRef
import de.taktovia.platform.audit.AuditEntry
import de.taktovia.platform.audit.ObjectRef
import de.taktovia.platform.authz.ScopedObject
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import org.springframework.jdbc.core.JdbcOperations
import java.util.UUID

/** Gemeinsame Bausteine der Geo-Repositories (WP-M2-03 bis -06): Akteur, Audit, Fehlerabbildung, Dienstberechtigung. */
internal object GeoSupport {
    fun actor(ctx: RequestContext) = ActorRef((if (ctx.actor.type == ActorType.SERVICE) "service:" else "user:") + ctx.actor.subject)

    fun entry(ctx: RequestContext, action: String, type: String, id: UUID, revision: Long, change: String, changes: Map<String, Any?>) =
        AuditEntry(actor(ctx), action, ObjectRef(type, id), ctx.correlationId, changes + mapOf("change" to change, "revision" to revision))

    fun throwIf(v: List<Violation>) { if (v.isNotEmpty()) throw ProblemException.invalid(*v.toTypedArray()) }

    fun revisionMismatch() = ProblemException(ErrorCode.REVISION_MISMATCH, "Die Ressource wurde zwischenzeitlich geändert; aktuelle Revision lesen.")

    /**
     * Zeitzone des Dienstes im Mandanten (RLS: fremde und unbekannte Dienste liefern keine Zeile, 404). Mit [lock] `FOR UPDATE`: serialisiert gleichzeitige Änderungen
     * am Dienst (Scope-Sperre der Überlappungsprüfung). Die Dienstberechtigung prüft der aufrufende Port mit [needsServiceScope] und dem ObjectAuthorizer.
     */
    fun loadService(j: JdbcOperations, id: UUID, lock: Boolean): String {
        val sql = "SELECT s.timezone FROM taktovia.service s WHERE s.id = ?" + if (lock) " FOR UPDATE OF s" else ""
        return j.query(sql, { rs, _ -> rs.getString(1) }, id).singleOrNull() ?: throw ProblemException.notFound()   // sql-lint: ok fester Sperrzusatz
    }

    /** Verwaltende Rollen (`services:write`) wirken mandantenweit; alle anderen nur auf Dienste ihres Dienstscopes (sonst 404, fail-closed). */
    fun needsServiceScope(ctx: RequestContext) = !ctx.hasScope("services:write")

    fun scoped(ctx: RequestContext, serviceId: UUID) = ScopedObject("service", serviceId, ctx.tenant, serviceId)
}

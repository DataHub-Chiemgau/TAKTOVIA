package de.taktovia.stafftime.internal

import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ProblemException
import org.springframework.jdbc.core.JdbcOperations
import java.util.UUID

/** Wodurch ein Depotrecht entsteht: [DEPOT] eigene Vergabe für dieses Depot, [CROSS_DEPOT] allein das ausdrückliche depotübergreifende Recht (auditpflichtig bei Bearbeitung). */
internal enum class DepotAccess { DEPOT, CROSS_DEPOT }

/**
 * Depotbindung der Dienstpläne (WP-M4-16): zweite Schicht hinter der Mandantentrennung durch RLS. Liest die Vergaben der Person in der **laufenden** Fachtransaktion; schreibend mit
 * `FOR SHARE`, damit ein gleichzeitiger Entzug auf das Ende der Transaktion wartet und ein bereits committeter Entzug gesehen wird (nächster Schreibbefehl scheitert). Leerer Scope = kein Recht.
 * Nicht sichtbare Dienstpläne verhalten sich wie nicht vorhandene: 404, nie 403 (kein Hinweis auf Existenz).
 */
internal object DepotGuard {
    private const val ACCESS = "SELECT g.scope_kind FROM taktovia.roster_depot_grant g WHERE g.subject = ? AND g.revoked_at IS NULL AND (g.scope_kind = 'ALL_DEPOTS' OR g.depot_id = ?) " +
        "ORDER BY CASE WHEN g.scope_kind = 'DEPOT' THEN 0 ELSE 1 END"

    fun subject(ctx: RequestContext) = ctx.actor.subject.lowercase()

    /** `null` = kein Recht. */
    fun access(j: JdbcOperations, ctx: RequestContext, depotId: UUID, forWrite: Boolean): DepotAccess? {
        val kinds = if (forWrite) j.query("$ACCESS FOR SHARE", { rs, _ -> rs.getString(1) }, subject(ctx), depotId) else j.query(ACCESS, { rs, _ -> rs.getString(1) }, subject(ctx), depotId)
        return when (kinds.firstOrNull()) { "DEPOT" -> DepotAccess.DEPOT; "ALL_DEPOTS" -> DepotAccess.CROSS_DEPOT; else -> null }
    }

    fun require(j: JdbcOperations, ctx: RequestContext, depotId: UUID, forWrite: Boolean): DepotAccess = access(j, ctx, depotId, forWrite) ?: throw ProblemException.notFound()

    /** Die Depots, die die Person sehen darf; `null` = alle Depots des Mandanten (ausdrückliches depotübergreifendes Recht), leere Menge = keine. */
    fun visible(j: JdbcOperations, ctx: RequestContext): Set<UUID>? {
        val rows = j.query("SELECT g.scope_kind, g.depot_id FROM taktovia.roster_depot_grant g WHERE g.subject = ? AND g.revoked_at IS NULL", { rs, _ -> rs.getString(1) to rs.getObject(2, UUID::class.java) }, subject(ctx))
        return if (rows.any { it.first == "ALL_DEPOTS" }) null else rows.mapNotNull { it.second }.toSet()
    }

    /** Depot des Dienstplans (egal welche Revision, nur sichtbare Zeilen); `null`, wenn es keinen sichtbaren Dienstplan gibt. */
    fun rosterDepot(j: JdbcOperations, rosterId: UUID): UUID? =
        j.query("SELECT r.depot_id FROM taktovia.roster_revision r WHERE r.roster_id = ? LIMIT 1", { rs, _ -> rs.getObject(1, UUID::class.java) }, rosterId).firstOrNull()

    /** Prüfung für einen Dienstplan: nicht vorhanden oder ohne Depotrecht = 404. */
    fun requireForRoster(j: JdbcOperations, ctx: RequestContext, rosterId: UUID, forWrite: Boolean): Pair<UUID, DepotAccess> {
        val depot = rosterDepot(j, rosterId) ?: throw ProblemException.notFound()
        return depot to require(j, ctx, depot, forWrite)
    }
}

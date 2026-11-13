package de.taktovia.platform.authz

import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.Purpose
import de.taktovia.platform.context.RequestContext
import org.springframework.jdbc.core.JdbcOperations
import java.util.UUID

/** Standardumsetzung von [ObjectAuthorizer] (zustandslos, ohne Modulabhängigkeit). */
object ServiceScopeAuthorizer : ObjectAuthorizer {

    override fun canRead(ctx: RequestContext, obj: ScopedObject, purpose: Purpose) = decide(ctx, obj, ctx.services)
    override fun canWrite(ctx: RequestContext, obj: ScopedObject, purpose: Purpose) = decide(ctx, obj, ctx.services)
    override fun canRead(jdbc: JdbcOperations, ctx: RequestContext, obj: ScopedObject, purpose: Purpose) = decide(ctx, obj, currentScope(jdbc, ctx, lock = false))
    override fun canWrite(jdbc: JdbcOperations, ctx: RequestContext, obj: ScopedObject, purpose: Purpose) = decide(ctx, obj, currentScope(jdbc, ctx, lock = true))

    /**
     * Aktueller Dienstscope in der laufenden Transaktion (ohne Sperre): für lesende Listen, die ab dem Commit eines Scope-Entzugs keine Objekte des entzogenen Dienstes mehr zeigen dürfen
     * (WP-M3-36). Konten ohne Mitgliedschaft entscheiden aus der Momentaufnahme des Kontexts.
     */
    fun currentServices(jdbc: JdbcOperations, ctx: RequestContext): Set<UUID> = currentScope(jdbc, ctx, lock = false)

    override fun effectiveServices(ctx: RequestContext, requested: Set<UUID>?, tenantWide: Boolean): Set<UUID>? = when {
        tenantWide -> requested
        requested == null -> ctx.services
        else -> requested intersect ctx.services
    }

    private fun decide(ctx: RequestContext, obj: ScopedObject, scope: Set<UUID>): Boolean = when {
        obj.tenant != ctx.tenant -> false                 // fremder Mandant: unabhängig vom Dienstscope
        obj.serviceId == null -> true                     // ohne Dienstbezug gilt nur die Mandantengrenze
        else -> obj.serviceId in scope                    // leerer/fehlender Scope: kein Dienstzugriff (fail-closed)
    }

    /**
     * Aktueller Dienstscope der Mitgliedschaften des Kontexts (Vereinigung der ausdrücklichen Listen; `NULL` zählt nicht). Nur aktive Mitgliedschaften des Mandanten;
     * schreibend `FOR SHARE`, damit ein gleichzeitiger Entzug (UPDATE) auf das Ende der Änderung wartet. Konten ohne Mitgliedschaft (Dienstkonten) entscheiden
     * aus der Momentaufnahme des Kontexts.
     */
    private fun currentScope(j: JdbcOperations, ctx: RequestContext, lock: Boolean): Set<UUID> {
        if (ctx.actor.type != ActorType.HUMAN || ctx.membershipIds.isEmpty()) return ctx.services
        val ids = ctx.membershipIds.joinToString(",", "{", "}")   // nur UUIDs, nie Fremdtext
        val rows = if (lock) j.query(
            "SELECT m.service_scope FROM taktovia.membership m WHERE m.id = ANY(CAST(? AS uuid[])) AND m.revoked_at IS NULL AND m.valid_from <= clock_timestamp() " +
                "AND (m.valid_to IS NULL OR m.valid_to > clock_timestamp()) FOR SHARE OF m", { rs, _ -> scopeOf(rs.getArray(1)) }, ids)
        else j.query(
            "SELECT m.service_scope FROM taktovia.membership m WHERE m.id = ANY(CAST(? AS uuid[])) AND m.revoked_at IS NULL AND m.valid_from <= clock_timestamp() " +
                "AND (m.valid_to IS NULL OR m.valid_to > clock_timestamp())", { rs, _ -> scopeOf(rs.getArray(1)) }, ids)
        return rows.flatten().toSet()
    }

    private fun scopeOf(a: java.sql.Array?): List<UUID> = a?.let { (it.array as Array<*>).map { v -> v as UUID } } ?: emptyList()
}

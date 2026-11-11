package de.taktovia.identity.internal

import de.taktovia.identity.api.MembershipLookup
import de.taktovia.identity.api.SubjectRef
import de.taktovia.identity.api.TenantMembership
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import java.util.UUID

/** SQL-Bedingung „Mitgliedschaft aktiv“ (Alias `m`); `clock_timestamp()`, damit eine frisch erteilte Mitgliedschaft sofort gilt. */
internal const val ACTIVE_MEMBERSHIP = "m.revoked_at IS NULL AND m.valid_from <= clock_timestamp() AND (m.valid_to IS NULL OR m.valid_to > clock_timestamp())"

internal class JdbcMembershipLookup(private val tx: TenantTransactions) : MembershipLookup {
    override fun selectableTenants(who: SubjectRef): List<TenantMembership> {
        return candidates(who).mapNotNull { activeIn(who, TenantId(it)) }.toList()
    }

    /** Kandidaten aus dem subjektgebundenen Verzeichnisindex (OP-06), nach Mandanten-ID sortiert; Wahrheit prüft [activeIn]. */
    private fun candidates(who: SubjectRef): Sequence<UUID> = tx.runForSubject(who.issuer, who.subject) { j ->
        j.queryForList("SELECT tenant_id FROM taktovia.membership_subject_index ORDER BY tenant_id LIMIT ?", UUID::class.java, MAX_TENANTS)
    }.filterNotNull().asSequence()

    override fun defaultTenant(who: SubjectRef): TenantMembership? = candidates(who).firstNotNullOfOrNull { activeIn(who, TenantId(it)) }

    override fun activeIn(who: SubjectRef, tenant: TenantId): TenantMembership? = tx.run(tenant, readOnly = true) { j ->
        val rows = j.query(
            "SELECT m.id, m.role_code, m.service_scope, t.name FROM taktovia.membership m JOIN taktovia.tenant t ON t.id = m.tenant_id " +
                "WHERE m.issuer = ? AND m.subject = ? AND t.status = 'ACTIVE' AND $ACTIVE_MEMBERSHIP ORDER BY m.role_code",
            { rs, _ ->
                val scope = rs.getArray("service_scope")?.let { a -> (a.array as Array<*>).map { it as UUID } }
                Row(rs.getObject("id", UUID::class.java), rs.getString("role_code"), scope, rs.getString("name"))
            },
            who.issuer, who.subject,
        )
        if (rows.isEmpty()) null else {
            val lists = rows.mapNotNull { it.scope }
            TenantMembership(tenant, rows.first().tenantName, rows.map { it.role }.toSet(), if (lists.isEmpty()) null else lists.flatten().toSet(), rows.map { it.id }.toSet())
        }
    }

    private class Row(val id: UUID, val role: String, val scope: List<UUID>?, val tenantName: String)

    companion object { const val MAX_TENANTS = 100 }
}

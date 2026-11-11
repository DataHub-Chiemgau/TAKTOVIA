package de.taktovia.identity.internal

import de.taktovia.identity.api.IdentityApi
import de.taktovia.identity.api.MembershipAdmin
import de.taktovia.identity.api.MembershipLookup
import de.taktovia.identity.api.ScopePolicy
import de.taktovia.identity.api.TenantSwitch
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.audit.SecurityEventPort
import de.taktovia.platform.context.ContextRevalidator
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.tenant.TenantTransactions
import java.time.Clock

/** Nur innerhalb des Moduls `identity` sichtbar (durch Architekturtest erzwungen). */
internal object IdentityServicesFactory {
    fun create(tx: TenantTransactions, audit: AuditPort, security: SecurityEventPort, outbox: Outbox, clock: Clock, scopePolicy: ScopePolicy?): IdentityApi {
        val lookup = JdbcMembershipLookup(tx)
        val revalidator = JdbcContextRevalidator()
        val ctxTx = de.taktovia.platform.context.ContextTransactions(tx, revalidator)
        val policy = scopePolicy ?: BuiltinScopePolicy
        return object : IdentityApi {
            override val lookup: MembershipLookup = lookup
            override val tenantSwitch: TenantSwitch = JdbcTenantSwitch(tx, lookup, audit, security, clock)
            override val admin: MembershipAdmin = JdbcMembershipAdmin(ctxTx, audit, outbox, clock)
            override val revalidator: ContextRevalidator = revalidator
            override val scopePolicy: ScopePolicy = policy
        }
    }
}

/**
 * Vorläufige Abbildung (WP-M1-06) bis zur Rechtematrix (WP-M1-07): jede bekannte Rolle darf Sitzung lesen und Mandanten wechseln,
 * der Administrator zusätzlich Mitgliedschaften verwalten. Wird durch die matrixgetriebene Richtlinie ersetzt.
 */
internal object BuiltinScopePolicy : ScopePolicy {
    private val ROLES = setOf("passenger", "driver", "callcenter", "support", "dispatcher", "planner", "tenant_admin", "finance", "staff_planning", "workshop")
    override fun scopesFor(roles: Set<String>): Set<String> = buildSet {
        if (roles.any { it in ROLES }) { add("me:read"); add("tenant:switch") }
        if ("tenant_admin" in roles) { add("memberships:read"); add("memberships:write") }
    }
}

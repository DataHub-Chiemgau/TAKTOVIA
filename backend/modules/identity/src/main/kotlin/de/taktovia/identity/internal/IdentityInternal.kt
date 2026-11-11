package de.taktovia.identity.internal

import de.taktovia.identity.api.Authorizer
import de.taktovia.identity.api.IdentityApi
import de.taktovia.identity.api.MatrixAuthorizer
import de.taktovia.identity.api.RightsMatrix
import de.taktovia.identity.api.RightsMatrixRollout
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
    fun create(tx: TenantTransactions, audit: AuditPort, security: SecurityEventPort, outbox: Outbox, clock: Clock, rights: RightsMatrix): IdentityApi {
        val lookup = JdbcMembershipLookup(tx)
        val revalidator = JdbcContextRevalidator()
        val ctxTx = de.taktovia.platform.context.ContextTransactions(tx, revalidator)
        val policy = ScopePolicy { roles -> rights.scopesFor(roles) }
        return object : IdentityApi {
            override val lookup: MembershipLookup = lookup
            override val tenantSwitch: TenantSwitch = JdbcTenantSwitch(tx, lookup, audit, security, clock)
            override val admin: MembershipAdmin = JdbcMembershipAdmin(ctxTx, audit, outbox, clock)
            override val revalidator: ContextRevalidator = revalidator
            override val scopePolicy: ScopePolicy = policy
            override val rights: RightsMatrix = rights
            override val authorizer: Authorizer = MatrixAuthorizer(rights)
            override val rollout: RightsMatrixRollout = JdbcRightsMatrixRollout(tx, rights)
        }
    }
}

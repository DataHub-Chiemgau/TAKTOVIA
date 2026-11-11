package de.taktovia.identity.internal

import de.taktovia.identity.api.Authorizer
import de.taktovia.identity.api.IdentityApi
import de.taktovia.identity.api.MatrixAuthorizer
import de.taktovia.identity.api.RightsMatrix
import de.taktovia.identity.api.RightsMatrixRollout
import de.taktovia.identity.api.MembershipAdmin
import de.taktovia.identity.api.MembershipLookup
import de.taktovia.identity.api.OrganizationAdmin
import de.taktovia.identity.api.PlatformAdmin
import de.taktovia.identity.api.IdpAdmin
import de.taktovia.identity.api.ScopePolicy
import de.taktovia.identity.api.SessionAdmin
import de.taktovia.identity.api.SessionGate
import de.taktovia.identity.api.SessionSettings
import de.taktovia.identity.api.TenantSwitch
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.audit.SecurityEventPort
import de.taktovia.platform.cache.SessionBlockCache
import de.taktovia.platform.context.ContextRevalidator
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.tenant.TenantTransactions
import java.time.Clock

/** Nur innerhalb des Moduls `identity` sichtbar (durch Architekturtest erzwungen). */
internal object IdentityServicesFactory {
    fun create(
        tx: TenantTransactions, audit: AuditPort, security: SecurityEventPort, outbox: Outbox, clock: Clock, rights: RightsMatrix,
        idp: IdpAdmin, blockCache: SessionBlockCache, sessionSettings: SessionSettings,
    ): IdentityApi {
        val lookup = JdbcMembershipLookup(tx)
        val revalidator = JdbcContextRevalidator()
        val ctxTx = de.taktovia.platform.context.ContextTransactions(tx, revalidator)
        val policy = ScopePolicy { roles -> rights.scopesFor(roles) }
        return object : IdentityApi {
            override val lookup: MembershipLookup = lookup
            override val tenantSwitch: TenantSwitch = JdbcTenantSwitch(tx, lookup, audit, security, clock)
            private val membershipAdmin = JdbcMembershipAdmin(ctxTx, audit, outbox, clock)
            override val admin: MembershipAdmin = membershipAdmin
            override val revalidator: ContextRevalidator = revalidator
            override val scopePolicy: ScopePolicy = policy
            override val rights: RightsMatrix = rights
            override val authorizer: Authorizer = MatrixAuthorizer(rights)
            override val rollout: RightsMatrixRollout = JdbcRightsMatrixRollout(tx, rights)
            override val organizations: OrganizationAdmin = JdbcOrganizationAdmin(ctxTx, audit, outbox, clock)
            override val platform: PlatformAdmin = JdbcPlatformAdmin(tx, audit, outbox, clock, membershipAdmin)
            override val sessionGate: SessionGate = JdbcSessionGate(tx, blockCache, sessionSettings)
            override val sessions: SessionAdmin = JdbcSessionAdmin(tx, ctxTx, lookup, audit, idp, blockCache, sessionSettings)
        }
    }
}

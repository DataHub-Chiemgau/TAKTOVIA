package de.taktovia.app.identity

import de.taktovia.app.security.AuthenticatedPrincipal
import de.taktovia.identity.api.IdentityApi
import de.taktovia.identity.api.SubjectRef
import de.taktovia.identity.api.SwitchOutcome
import de.taktovia.identity.api.TenantMembership
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.context.TenantSelectionRequest
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.tenant.TenantId
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Anfrage-DTO des Tenantwechsels: `tenant_id` ist die **gewünschte Auswahl**, kein Nachweis (siehe [TenantSelectionRequest]). */
@TenantSelectionRequest
class TenantSwitchRequest(val tenantId: UUID, val serviceId: UUID?) {
    companion object {
        fun from(body: Map<String, Any?>) = TenantSwitchRequest(UUID.fromString(body["tenant_id"] as String), (body["service_id"] as String?)?.let(UUID::fromString))
    }
}

/**
 * `/v1/me` und serverseitiger Tenantwechsel (WP-M1-06). Der Mandant steht im [de.taktovia.platform.context.RequestContext], den der
 * Filter aus der Mitgliedschaft gebildet hat; das Antwortobjekt nennt nur Mandanten des angemeldeten Subjects.
 */
@RestController
class MeController(private val identity: IdentityApi, private val tickets: ContextTicketCodec) {

    @GetMapping("/v1/me")
    fun getMe(request: HttpServletRequest): Map<String, Any?> {
        val ctx = RequestContexts.require(request)
        val p = AuthenticatedPrincipal.of(request) ?: throw ProblemException(ErrorCode.UNAUTHENTICATED)
        val tenants = identity.lookup.selectableTenants(SubjectRef(ctx.actor.issuer, ctx.actor.subject))
        val active = tenants.firstOrNull { it.tenant == ctx.tenant } ?: throw ProblemException(ErrorCode.FORBIDDEN)  // zwischenzeitlich entzogen
        return me(ctx, p, active, ctx.roles, ctx.scopes, ctx.services, tenants)
    }

    @PostMapping("/v1/me/tenant-switch")
    fun switchTenant(@RequestBody body: Map<String, Any?>, request: HttpServletRequest): Map<String, Any?> {
        val ctx = RequestContexts.require(request)
        val p = AuthenticatedPrincipal.of(request) ?: throw ProblemException(ErrorCode.UNAUTHENTICATED)
        val wish = TenantSwitchRequest.from(body)
        val outcome = identity.tenantSwitch.switch(ctx, TenantId(wish.tenantId), wish.serviceId)
        // Verweigerung: einheitlich 403 ohne Hinweis, ob der Mandant existiert oder der Dienst fehlt
        val ok = outcome as? SwitchOutcome.Switched ?: throw ProblemException(ErrorCode.FORBIDDEN)
        val m = ok.membership
        val services: Set<UUID> = ok.service?.let { setOf(it) } ?: m.serviceScope ?: emptySet()
        val tenants = identity.lookup.selectableTenants(SubjectRef(ctx.actor.issuer, ctx.actor.subject))
        val (ticket, expires) = tickets.issue(p, ctx.actor.issuer, m.tenant, ok.service)
        return me(ctx, p, m, m.roles, identity.scopePolicy.scopesFor(m.roles), services, tenants) + mapOf("context_token" to ticket, "context_expires_at" to expires.toString())
    }

    private fun me(ctx: RequestContext, p: AuthenticatedPrincipal, active: TenantMembership, roles: Set<String>, scopes: Set<String>, services: Set<UUID>, tenants: List<TenantMembership>): Map<String, Any?> = linkedMapOf(
        "subject" to ctx.actor.subject, "issuer" to ctx.actor.issuer, "tenant_id" to active.tenant.toString(), "tenant_name" to active.tenantName,
        "roles" to roles.sorted(), "scopes" to scopes.sorted(), "service_scope" to if (services.isEmpty()) null else services.map { it.toString() }.sorted(),
        "session_id" to sessionUuid(p.sessionId), "mfa" to p.multiFactor,
        "tenants" to tenants.map { mapOf("tenant_id" to it.tenant.toString(), "name" to it.tenantName) },
    )

    private fun sessionUuid(sid: String?): String = sid?.let { runCatching { UUID.fromString(it) }.getOrNull() ?: UUID.nameUUIDFromBytes(it.toByteArray()) }?.toString()
        ?: throw ProblemException(ErrorCode.UNAUTHENTICATED)
}

package de.taktovia.app.identity

import de.taktovia.app.openapi.ApiOperation
import de.taktovia.app.openapi.OpenApiRegistry
import de.taktovia.app.security.AuthenticatedPrincipal
import de.taktovia.app.security.IdpProperties
import de.taktovia.app.security.PrincipalType
import de.taktovia.app.web.Correlation
import de.taktovia.app.web.ProblemWriter
import de.taktovia.identity.api.Decision
import de.taktovia.identity.api.IdentityApi
import de.taktovia.identity.api.SubjectRef
import de.taktovia.identity.api.TenantMembership
import de.taktovia.platform.audit.ActorRef
import de.taktovia.platform.audit.AuditActions
import de.taktovia.platform.audit.ObjectRef
import de.taktovia.platform.audit.SecurityEvent
import de.taktovia.platform.audit.SecurityEventPort
import de.taktovia.platform.context.Actor
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/** Meldet den Versuch, einen anderen als den aktiven Mandanten über Header, Pfad, Query oder Body anzusprechen (Sicherheitsereignis). */
class TenantMismatchReporter(private val tx: TenantTransactions, private val events: SecurityEventPort) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Schreibt `security.tenant.mismatch` im **aktiven** Mandanten des Akteurs; der angesprochene Fremdwert wird nie gespeichert. */
    fun report(ctx: RequestContext, surface: String) {
        val event = SecurityEvent(ActorRef("user:" + ctx.actor.subject), AuditActions.SECURITY_TENANT_MISMATCH, ObjectRef("tenant", ctx.tenant.value), ctx.correlationId, mapOf("surface" to surface))
        runCatching { tx.run(ctx.tenant) { j -> events.report(j, event) } }
            .onFailure { log.warn("Sicherheitsereignis tenant.mismatch nicht geschrieben ({})", it.javaClass.simpleName); events.reportWithoutTenant(event) }
    }
}

/**
 * Bildet je Anfrage den unveränderlichen [RequestContext] (WP-M1-06, LH-I.4-MUL-006, LH-6.17-INT-002), nach der Token-Prüfung
 * ([de.taktovia.app.security.AuthenticationFilter]) und vor der Eingabeprüfung:
 *  1. Der aktive Mandant ergibt sich nur aus einer **aktuell gültigen Mitgliedschaft** des Tokens (Issuer + Subject): entweder aus der
 *     serverseitig signierten Kontextbindung eines Tenantwechsels (`X-Taktovia-Context`) oder, ohne Bindung, dem Standardmandanten
 *     (kleinste Mandanten-ID unter den aktiven Mitgliedschaften). Keine Mitgliedschaft: 403.
 *  2. Tenant-Angaben in Header, Pfad oder Query, die vom aktiven Mandanten abweichen, erzeugen keine Rechte: 404 wie „nicht vorhanden“
 *     plus Sicherheitsereignis. (Der Body wird beim Lesen in der Eingabeprüfung geprüft, siehe [TenantClaimInspector].)
 *  3. Funktionsscopes folgen aus den Rollen der Mitgliedschaft über die Rechtematrix; die zentrale Autorisierung ([de.taktovia.identity.api.Authorizer])
 *     verweigert Operationen ohne Matrixeintrag, ohne erlaubte Rolle oder ohne passenden Scope mit 403 ohne Details.
 * Dienstkonten haben keine Mitgliedschaft; ihr Mandant ist die Bindung im Token (höchstens einer), ihre Scopes sind schon geprüft.
 * Vertragsoperationen aus Zusatzverträgen ([excludedFiles], nur Tests) und ausdrücklich ausgenommene ([EXEMPT]) tragen keinen Kontext.
 */
class RequestContextFilter(
    private val identity: IdentityApi, private val registry: OpenApiRegistry, private val props: IdpProperties,
    private val tickets: ContextTicketCodec, private val tx: TenantTransactions, private val events: SecurityEventPort,
    private val mismatch: TenantMismatchReporter, private val problems: ProblemWriter, private val excludedFiles: Set<String>,
) : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = request.requestURI
        if (!path.startsWith("/v1/")) return true
        val op = registry.match(request.method, path) ?: return true
        return op.file in excludedFiles || op.operationId in EXEMPT
    }

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val op = registry.match(request.method, request.requestURI)!!
        val principal = AuthenticatedPrincipal.of(request)
        try {
            if (principal == null) throw ProblemException(ErrorCode.UNAUTHENTICATED, "Anmeldung fehlt oder ist ungültig.")
            val ctx = when (principal.type) { PrincipalType.HUMAN -> humanContext(request, principal, op); PrincipalType.SERVICE -> serviceContext(request, principal) }
            // Zentrale Autorisierung (WP-M1-07): Rechtematrix + Funktionsscope; ohne Eintrag oder Scope deny-by-default, ohne Details in der Antwort
            (identity.authorizer.decide(ctx, op.operationId, op.scopes) as? Decision.Denied)?.let { denial ->
                denied(ctx, op, denial.reason)
                throw ProblemException(ErrorCode.FORBIDDEN)
            }
            claimedTenants(request, op).firstOrNull { !it.second.equals(ctx.tenant.toString(), ignoreCase = true) }?.let { (surface, _) ->
                mismatch.report(ctx, surface)
                throw ProblemException.notFound()
            }
            request.setAttribute(RequestContexts.ATTRIBUTE, ctx)
        } catch (e: ProblemException) {
            return problems.write(request, response, e)
        }
        chain.doFilter(request, response)
    }

    private fun humanContext(request: HttpServletRequest, p: AuthenticatedPrincipal, op: ApiOperation): RequestContext {
        val who = SubjectRef(p.issuer, p.subject.toString())
        val ticket = request.getHeader(CONTEXT_HEADER)
        val selection = try { ticket?.let { tickets.verify(it, p, p.issuer) } } catch (e: ContextTicketException) {
            if (!e.expired) log.warn("Kontextbindung abgewiesen correlation_id={}", Correlation.of(request))
            throw ProblemException(ErrorCode.FORBIDDEN)
        }
        val membership: TenantMembership = (if (selection != null) identity.lookup.activeIn(who, selection.tenant) else identity.lookup.defaultTenant(who))
            ?: run { events.reportWithoutTenant(denialEvent(p, op, "no_active_membership", Correlation.of(request))); throw ProblemException(ErrorCode.FORBIDDEN) }
        val services: Set<UUID> = when {
            selection?.service != null -> if (membership.serviceScope?.contains(selection.service) == true) setOf(selection.service) else throw ProblemException(ErrorCode.FORBIDDEN)
            else -> membership.serviceScope ?: emptySet()
        }
        return RequestContext(
            Actor(ActorType.HUMAN, p.issuer, p.subject.toString(), p.sessionId, p.multiFactor), membership.tenant, membership.roles,
            identity.scopePolicy.scopesFor(membership.roles), services, Correlation.of(request), membershipIds = membership.membershipIds,
        )
    }

    private fun serviceContext(request: HttpServletRequest, p: AuthenticatedPrincipal): RequestContext {
        val tenant = p.tenantIds.singleOrNull() ?: throw ProblemException(ErrorCode.FORBIDDEN)
        val ceiling = props.serviceClients[p.clientId] ?: emptySet()
        return RequestContext(Actor(ActorType.SERVICE, p.issuer, p.subject.toString(), null, p.multiFactor), TenantId(tenant), emptySet(), p.scopes intersect ceiling, emptySet(), Correlation.of(request))
    }

    private fun denied(ctx: RequestContext, op: ApiOperation, reason: String) {
        val event = denialEvent(ctx, op, reason)
        runCatching { tx.run(ctx.tenant) { j -> events.report(j, event) } }
            .onFailure { log.warn("Sicherheitsereignis access.denied nicht geschrieben ({})", it.javaClass.simpleName); events.reportWithoutTenant(event) }
    }

    private fun denialEvent(ctx: RequestContext, op: ApiOperation, reason: String) = SecurityEvent(
        ActorRef((if (ctx.actor.type == ActorType.SERVICE) "service:" else "user:") + ctx.actor.subject), AuditActions.SECURITY_ACCESS_DENIED, ObjectRef("tenant", ctx.tenant.value),
        ctx.correlationId, mapOf("reason" to reason, "operation_id" to op.operationId),
    )

    private fun denialEvent(p: AuthenticatedPrincipal, op: ApiOperation, reason: String, correlation: UUID) = SecurityEvent(
        ActorRef("user:" + p.subject), AuditActions.SECURITY_ACCESS_DENIED, ObjectRef("user", p.subject), correlation, mapOf("reason" to reason, "operation_id" to op.operationId),
    )

    /** Tenant-Angaben der Anfrage (Fläche, Wert) aus Header, Pfad und Query; Wirkung haben sie nie. */
    private fun claimedTenants(request: HttpServletRequest, op: ApiOperation): List<Pair<String, String>> = buildList {
        request.headerNames.toList().filter { HEADER_NAME.matches(it) }.forEach { h -> request.getHeaders(h).toList().forEach { add("header" to it) } }
        op.pathValues(request.requestURI).filterKeys { it.equals("tenant_id", true) }.values.forEach { add("path" to it) }
        request.queryString?.split('&')?.forEach { pair ->
            val kv = pair.split('=', limit = 2)
            val name = runCatching { java.net.URLDecoder.decode(kv[0], Charsets.UTF_8) }.getOrDefault("")
            if (QUERY_NAME.matches(name)) add("query" to runCatching { java.net.URLDecoder.decode(kv.getOrElse(1) { "" }, Charsets.UTF_8) }.getOrDefault(""))
        }
    }

    companion object {
        const val CONTEXT_HEADER = "X-Taktovia-Context"
        /**
         * Operationen mit eigener Authentisierung ohne Menschen-/Dienstkontext. Leer: auch die Partnersignatur-Operation (`receivePartnerEvent`)
         * ist bis M6 für jede Anmeldung verweigert (Matrixzeile ohne erlaubte Rolle); M6 trägt hier ausdrücklich ein, was eine eigene Prüfung hat.
         */
        val EXEMPT: Set<String> = emptySet()
        private val HEADER_NAME = Regex("(?i)^(x-)?tenant(-?id)?$")
        private val QUERY_NAME = Regex("(?i)^tenant_?id$")
    }
}

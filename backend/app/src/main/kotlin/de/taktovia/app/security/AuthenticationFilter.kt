package de.taktovia.app.security

import de.taktovia.app.openapi.OpenApiRegistry
import de.taktovia.app.web.Correlation
import de.taktovia.app.web.ProblemWriter
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.web.filter.OncePerRequestFilter

/** Meldet eine wegen fehlendem zweiten Faktor abgewiesene Anfrage über den Sicherheitsereignis-Port (WP-M1-46). */
fun interface MfaDenialReporter {
    fun report(principal: AuthenticatedPrincipal, request: HttpServletRequest, privilegedVia: String)
}

/**
 * Ressourcenserver für alle Anfragen außer dem Health-Endpunkt (WP-M1-04):
 *  1. Bearer-Token prüfen ([TokenVerifier]); jeder Mangel ergibt 401, ein nicht erreichbarer IdP ohne verwertbare Schlüssel 503.
 *  2. Dienstkonten (Client Credentials): nur freigegebene Adapter, nur Operationen, deren OpenAPI-Scope im Token **und** in der Obergrenze
 *     des Adapters steht; keine Menschen-Endpunkte (Pfade unter `/v1/me` und `/v1/sessions`) und keine Mandantenwahl; sonst 403.
 *  3. Menschen mit privilegierter Rolle (Token-Rollen oder [PrivilegedRoleDirectory]) brauchen `acr=loa2`; sonst 403 mit
 *     `WWW-Authenticate: Bearer error="insufficient_user_authentication"` (Step-up nach RFC 9470) und Sicherheitsereignis.
 * Rechte je Rolle, Mandantenwahl und Sitzungssperre folgen mit WP-M1-06, -07 und -10; hier wird nur authentisiert.
 * Authentisierungsstärke kommt ausschließlich aus dem signierten Token, nie aus Headern oder Hinweisclaims.
 */
class AuthenticationFilter(
    private val verifier: TokenVerifier,
    private val props: IdpProperties,
    private val registry: OpenApiRegistry,
    private val directory: PrivilegedRoleDirectory,
    private val reporter: MfaDenialReporter,
    private val problems: ProblemWriter,
) : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val p = request.requestURI
        if (p == "/actuator/health" || p.startsWith("/actuator/health/")) return true
        // Operationen mit eigener Nachrichtensignatur (PSP-Ereignisse, WP-M2-29): kein Bearer-Token, die Operation prüft die Signatur selbst und fail-closed
        return p.startsWith("/v1/") && registry.match(request.method, p)?.signatureAuthenticated == true
    }

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val headers = request.getHeaders("Authorization").toList()
        if (headers.isEmpty()) return unauthenticated(request, response, "no_credentials", withError = false)
        val token = headers.singleOrNull()?.let { h -> if (h.length > 7 && h.regionMatches(0, "Bearer ", 0, 7, ignoreCase = true)) h.substring(7).trim() else null }
        if (token.isNullOrEmpty()) return unauthenticated(request, response, "no_bearer_token")

        val principal = try {
            verifier.verify(token)
        } catch (e: TokenRejectedException) {
            return unauthenticated(request, response, e.reason)
        } catch (e: KeysUnavailableException) {
            log.error("Tokenprüfung nicht möglich: {}", e.message)
            response.setHeader("Retry-After", "5")
            return problems.write(request, response, ProblemException(ErrorCode.SERVICE_UNAVAILABLE, "Die Anmeldung kann gerade nicht geprüft werden.", headers = mapOf("Retry-After" to "5")))
        }

        when (principal.type) {
            PrincipalType.SERVICE -> serviceDenial(principal, request)?.let { return forbidden(request, response, principal, it) }
            PrincipalType.HUMAN -> {
                val viaToken = principal.roles.any { it in props.privilegedRoles }
                val viaDirectory = !viaToken && directory.rolesOf(principal.subject).any { it in props.privilegedRoles }
                if ((viaToken || viaDirectory) && !principal.multiFactor) {
                    runCatching { reporter.report(principal, request, if (viaToken) "token" else "directory") }
                        .onFailure { log.error("Sicherheitsereignis zur fehlenden Mehrfaktor-Authentisierung nicht protokolliert ({})", it.javaClass.simpleName) }
                    response.setHeader("WWW-Authenticate", """Bearer error="insufficient_user_authentication", error_description="a second factor is required", acr_values="${AuthenticatedPrincipal.ACR_MFA}"""")
                    return problems.write(request, response, ProblemException(ErrorCode.FORBIDDEN, "Für dieses Konto ist ein zweiter Faktor erforderlich."))
                }
            }
        }
        request.setAttribute(AuthenticatedPrincipal.ATTRIBUTE, principal)
        chain.doFilter(request, response)
    }

    /** Grund der Ablehnung eines Dienstkontos (nur fürs Log) oder `null`. */
    private fun serviceDenial(p: AuthenticatedPrincipal, request: HttpServletRequest): String? {
        val ceiling = props.serviceClients[p.clientId] ?: return "service_client_not_registered"
        val path = request.requestURI
        if (HUMAN_ONLY.any { it.matches(path) }) return "human_only_endpoint"
        if (request.getHeader("X-Tenant-Id") != null || request.getParameter("tenant_id") != null) return "tenant_switch_attempt"
        val required = registry.match(request.method, path)?.scopes?.takeIf { it.isNotEmpty() } ?: return "no_scope_defined_for_operation"
        val effective = p.scopes intersect ceiling
        return if (effective.containsAll(required)) null else "scope_missing"
    }

    private fun unauthenticated(request: HttpServletRequest, response: HttpServletResponse, reason: String, withError: Boolean = true) {
        if (reason != "no_credentials") log.warn("Token abgewiesen: {}", reason)
        response.setHeader("WWW-Authenticate", if (withError) """Bearer realm="taktovia", error="invalid_token"""" else """Bearer realm="taktovia"""")
        problems.write(request, response, ProblemException(ErrorCode.UNAUTHENTICATED, "Anmeldung fehlt oder ist ungültig."))
    }

    private fun forbidden(request: HttpServletRequest, response: HttpServletResponse, p: AuthenticatedPrincipal, reason: String) {
        log.warn("Dienstkonto abgewiesen: client={} grund={} correlation_id={}", p.clientId, reason, Correlation.of(request))
        problems.write(request, response, ProblemException(ErrorCode.FORBIDDEN, "Der Zugriff ist für dieses Konto nicht zulässig."))
    }

    companion object {
        /** Endpunkte, die eine Menschen-Sitzung voraussetzen: Konto, Mandantenwechsel, Sitzungen. */
        private val HUMAN_ONLY = listOf(Regex("^/v1/me(/.*)?$"), Regex("^/v1/sessions(/.*)?$"), Regex("^/v1/account-(locks|recoveries)(/.*)?$"))
    }
}

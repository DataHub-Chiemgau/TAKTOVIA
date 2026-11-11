package de.taktovia.app.web

import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter

/**
 * CORS-Whitelist und CSRF-Schutz für Browserflüsse (WP-M1-38). Die API nutzt Bearer-Token im `Authorization`-Header, nie
 * Cookies als Anmeldung; ohne ambient authority gibt es keine klassische CSRF-Angriffsfläche. Trotzdem gilt serverseitig:
 *  - Anfragen mit `Origin` aus einer nicht freigegebenen Herkunft (auch `null`, nie `*`) werden mit 403 abgewiesen, Preflight eingeschlossen;
 *  - zustandsändernde Anfragen eines Browsers, die als `cross-site` oder `same-site` gekennzeichnet sind (Fetch-Metadata) und keinen
 *    freigegebenen `Origin` tragen, werden abgewiesen;
 *  - zustandsändernde Anfragen verlangen `Content-Type: application/json` (siehe RequestValidationFilter), den ein fremdes Formular nicht senden kann.
 * Freigegebene Herkunft: `taktovia.security.cors.allowed-origins` (kommagetrennt, exakte Schreibweise `https://host[:port]`).
 */
class OriginPolicyFilter(allowedOrigins: Collection<String>, private val problems: ProblemWriter) : OncePerRequestFilter() {
    private val allowed = allowedOrigins.map { normalize(it) ?: error("ungültige CORS-Herkunft: $it") }.toSet()
    private val allowedMethods = "GET, POST, PUT, PATCH, DELETE"
    private val allowedHeaders = setOf("authorization", "content-type", "if-match", "idempotency-key", "traceparent", "accept", "x-taktovia-context")
    private val safe = setOf("GET", "HEAD", "OPTIONS")

    override fun shouldNotFilter(request: HttpServletRequest) = !request.requestURI.startsWith("/v1/")

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val origin = request.getHeader("Origin")
        val site = request.getHeader("Sec-Fetch-Site")
        if (origin == null) {
            if (request.method.uppercase() !in safe && site != null && site != "same-origin" && site != "none") return deny(request, response)
            return chain.doFilter(request, response)
        }
        val norm = normalize(origin)
        if (norm == null || norm !in allowed) return deny(request, response)
        val preflight = request.method.equals("OPTIONS", true) && request.getHeader("Access-Control-Request-Method") != null
        if (preflight) {
            val requested = request.getHeader("Access-Control-Request-Headers")?.split(',')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() } ?: emptyList()
            if (requested.any { it !in allowedHeaders } || request.getHeader("Access-Control-Request-Method").uppercase() !in allowedMethods.split(", ")) return deny(request, response)
        }
        response.addHeader("Vary", "Origin")
        response.setHeader("Access-Control-Allow-Origin", norm)
        response.setHeader("Access-Control-Expose-Headers", "ETag, Location, Retry-After, X-Correlation-Id")
        if (preflight) {
            response.status = HttpServletResponse.SC_NO_CONTENT
            response.setHeader("Access-Control-Allow-Methods", allowedMethods)
            response.setHeader("Access-Control-Allow-Headers", "Authorization, Content-Type, If-Match, Idempotency-Key, traceparent, Accept, X-Taktovia-Context")
            response.setHeader("Access-Control-Max-Age", "600")
            return
        }
        chain.doFilter(request, response)
    }

    private fun deny(request: HttpServletRequest, response: HttpServletResponse) {
        problems.write(request, response, ProblemException(ErrorCode.FORBIDDEN, "Die Herkunft der Anfrage ist nicht zugelassen."))
    }

    private fun normalize(o: String): String? {
        val m = Regex("^(https?)://([A-Za-z0-9.-]+)(:[0-9]{1,5})?$").matchEntire(o.trim()) ?: return null
        return "${m.groupValues[1].lowercase()}://${m.groupValues[2].lowercase()}${m.groupValues[3]}"
    }
}

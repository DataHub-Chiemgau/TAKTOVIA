package de.taktovia.app.web

import de.taktovia.platform.api.RateLimiter
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter

/** Quote je Mandant und Akteur auf `/v1/…`; Überschreitung ergibt 429 mit `Retry-After` (Kap. 05 §5.1, WP-M1-02). */
class RateLimitFilter(
    private val limiter: RateLimiter,
    private val identity: () -> RequestIdentityResolver,
    private val problems: ProblemWriter,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest) = !request.requestURI.startsWith("/v1/")

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val id = identity()
        val d = limiter.check(id.tenant(request), id.actor(request))
        if (!d.allowed) {
            problems.write(request, response, ProblemException(ErrorCode.RATE_LIMITED, "Die Quote ist ausgeschöpft; nach Ablauf von Retry-After erneut senden.",
                headers = mapOf("Retry-After" to d.retryAfterSeconds.toString())))
            return
        }
        chain.doFilter(request, response)
    }
}

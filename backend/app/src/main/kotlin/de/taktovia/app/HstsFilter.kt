package de.taktovia.app

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/** HSTS auf jeder über TLS bediente Antwort (LH-6.18-SEC-002). Hinter einem Gateway setzt dieses den Header; hier gilt `X-Forwarded-Proto: https` als TLS. */
@Component
class HstsFilter : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        if (request.isSecure || request.getHeader("X-Forwarded-Proto") == "https") response.setHeader(HEADER, VALUE)
        chain.doFilter(request, response)
    }
    companion object { const val HEADER = "Strict-Transport-Security"; const val VALUE = "max-age=31536000; includeSubDomains" }
}

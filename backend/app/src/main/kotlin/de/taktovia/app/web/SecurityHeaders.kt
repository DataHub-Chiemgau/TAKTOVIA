package de.taktovia.app.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Sicherheitsheader jeder API-Antwort (WP-M1-38, Kap. 06/08): die Antworten sind reine Daten (JSON), sie dürfen weder
 * gerendert, eingebettet noch zwischengespeichert werden. Die Liste ist die Pflicht-Header-Liste des Detailvertrags
 * `LH-6.18-SEC-009`; `SecurityHeadersTest` prüft sie an echten Antworten, auch an Fehlerantworten des Containers.
 */
object SecurityHeaders {
    val REQUIRED: Map<String, String> = linkedMapOf(
        "X-Content-Type-Options" to "nosniff",
        "Cache-Control" to "no-store",
        "Content-Security-Policy" to "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'",
        "X-Frame-Options" to "DENY",
        "Referrer-Policy" to "no-referrer",
        "Strict-Transport-Security" to "max-age=63072000; includeSubDomains",
        "Permissions-Policy" to "geolocation=(), camera=(), microphone=(), payment=()",
        "Cross-Origin-Resource-Policy" to "same-site",
    )

    fun apply(response: HttpServletResponse) = REQUIRED.forEach { (k, v) -> response.setHeader(k, v) }
}

class SecurityHeadersFilter : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        SecurityHeaders.apply(response)
        chain.doFilter(request, response)
    }
}

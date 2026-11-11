package de.taktovia.app.web

import de.taktovia.platform.api.Traceparent
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Setzt je Anfrage die Correlation-ID: Trace-ID eines gültigen W3C-`traceparent`, sonst eine neue UUID (Kap. 05 §5.1).
 * Sie steht im Header `X-Correlation-Id`, im Logkontext und in jeder Fehlerantwort als `correlation_id`.
 */
class CorrelationFilter : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val id = Traceparent.correlationId(request.getHeader("traceparent"))
        request.setAttribute(Correlation.ATTRIBUTE, id)
        response.setHeader(Correlation.HEADER, id.toString())
        MDC.put("correlation_id", id.toString())
        try { chain.doFilter(request, response) } finally { MDC.remove("correlation_id") }
    }
}

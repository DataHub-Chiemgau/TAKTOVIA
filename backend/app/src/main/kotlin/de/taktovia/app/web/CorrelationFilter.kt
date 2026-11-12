package de.taktovia.app.web

import de.taktovia.platform.api.Traceparent
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import jakarta.servlet.http.HttpServletRequestWrapper
import org.springframework.web.filter.OncePerRequestFilter
import java.util.Collections
import java.util.concurrent.ThreadLocalRandom

/**
 * Setzt je Anfrage die Correlation-ID: Trace-ID eines gültigen W3C-`traceparent`, sonst eine neue UUID (Kap. 05 §5.1).
 * Sie steht im Header `X-Correlation-Id`, im Logkontext und in jeder Fehlerantwort als `correlation_id`.
 */
class CorrelationFilter : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val header = request.getHeader("traceparent")
        val id = Traceparent.correlationId(header)
        // Ungültiger oder fehlender Header: die neue Correlation-ID wird als Trace-ID in einen gültigen Header gelegt, damit der Trace
        // des Tracing-Systems dieselbe ID trägt wie Fehlerantwort und Log (WP-M5-03). Ein gültiger Header bleibt unverändert.
        val req = if (Traceparent.parse(header) == null) TraceparentOverride(request, "00-" + id.toString().replace("-", "") + "-" + randomSpanId() + "-01") else request
        request.setAttribute(Correlation.ATTRIBUTE, id)
        response.setHeader(Correlation.HEADER, id.toString())
        MDC.put("correlation_id", id.toString())
        try { chain.doFilter(req, response) } finally { MDC.remove("correlation_id") }
    }
}

private fun randomSpanId(): String = "%016x".format(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE))

/** Liefert für `traceparent` den ersetzten Wert (nur für diese Anfrage). */
private class TraceparentOverride(request: HttpServletRequest, private val value: String) : HttpServletRequestWrapper(request) {
    override fun getHeader(name: String): String? = if (name.equals("traceparent", true)) value else super.getHeader(name)
    override fun getHeaders(name: String): java.util.Enumeration<String> = if (name.equals("traceparent", true)) Collections.enumeration(listOf(value)) else super.getHeaders(name)
}

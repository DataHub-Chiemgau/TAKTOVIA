package de.taktovia.app.observability

import de.taktovia.platform.telemetry.PlatformMetrics
import de.taktovia.platform.telemetry.RouteClass
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Duration

/** Serverseitige Latenz je Endpunktklasse (WP-M5-03). Der Pfad wird nur klassifiziert, nie als Label verwendet. Die Messung stört die Anfrage nie. */
class HttpMetricsFilter(private val metrics: PlatformMetrics) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val t0 = System.nanoTime()
        var failed = true
        try { chain.doFilter(request, response); failed = false }
        finally {
            metrics.httpRequest(RouteClass.of(request.requestURI), request.method, if (failed) 500 else response.status, Duration.ofNanos(System.nanoTime() - t0))
        }
    }
}

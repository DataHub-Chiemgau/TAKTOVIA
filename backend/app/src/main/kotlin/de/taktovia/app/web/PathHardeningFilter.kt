package de.taktovia.app.web

import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Pfadmanipulation (WP-M1-38): Anfragen mit Pfadparametern (`;`), kodierten Trennzeichen oder Punkten (`%2e`, `%2f`, `%5c`, `%00`),
 * Rückwärts-Pfaden (`..`), doppelten Schrägstrichen oder Backslash werden abgewiesen, bevor Spring sie normalisiert. Sonst würde
 * `/v1/me;x=y` oder `/v1/%2e/me` am Vertragsabgleich vorbei an einen Handler gelangen.
 */
class PathHardeningFilter(private val problems: ProblemWriter) : OncePerRequestFilter() {
    private val encoded = Regex("(?i)%(2e|2f|5c|00|3b|25|0d|0a)")

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val uri = request.requestURI ?: ""
        val bad = uri.contains(';') || uri.contains('\\') || uri.contains("//") || encoded.containsMatchIn(uri) ||
            uri.split('/').any { it == "." || it == ".." } || uri.any { it.code < 0x20 || it.code == 0x7f }
        if (bad) { problems.write(request, response, ProblemException(ErrorCode.BAD_REQUEST, "Der Pfad enthält unzulässige Zeichen.")); return }
        chain.doFilter(request, response)
    }
}

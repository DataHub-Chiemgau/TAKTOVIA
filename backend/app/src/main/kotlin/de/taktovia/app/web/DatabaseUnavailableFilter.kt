package de.taktovia.app.web

import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.web.filter.OncePerRequestFilter
import java.sql.SQLException

/**
 * Fail-closed bei nicht verfügbarer Datenbank (WP-M5-16, Fault-Injection): Kann ein **Filter** (Mitgliedschaftsprüfung, Idempotenzregister) keine Verbindung öffnen (Datenbank weg, Verbindungsabriss,
 * Pool erschöpft), liegt die Ausnahme außerhalb der Fehlerbehandlung der Controller und würde als Containerfehler `500` ohne Problem-Body enden. Dieser Filter macht daraus `503 SERVICE_UNAVAILABLE`
 * (`application/problem+json`, `Retry-After`), nie eine Antwort mit Daten und nie ohne Mandanten- und Sitzungskontext: die Anfrage wird abgebrochen, bevor ein Fachaufruf stattfand.
 * Erkannt werden nur Verbindungsfehler (keine Fachfehler): Transaktion/Verbindung nicht zu öffnen, SQL-Zustände der Klassen 08, 53, 57.
 */
class DatabaseUnavailableFilter(private val problems: ProblemWriter) : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        try {
            chain.doFilter(request, response)
        } catch (e: Exception) {
            val cause = connectionFailure(e) ?: throw e
            if (response.isCommitted) throw e
            log.error("Datenbank nicht verfügbar: {} correlation_id={}", cause.javaClass.simpleName, Correlation.of(request))
            problems.write(request, response, ProblemException(ErrorCode.SERVICE_UNAVAILABLE, headers = mapOf("Retry-After" to "5"), cause = cause))
        }
    }

    private fun connectionFailure(e: Throwable): Throwable? {
        var t: Throwable? = e
        var depth = 0
        while (t != null && depth++ < 12) {
            when {
                t is org.springframework.transaction.CannotCreateTransactionException || t is org.springframework.jdbc.CannotGetJdbcConnectionException ||
                    t is org.springframework.dao.DataAccessResourceFailureException || t is java.sql.SQLTransientConnectionException -> return t
                t is SQLException && (t.sqlState?.let { it.startsWith("08") || it.startsWith("53") || it.startsWith("57") } == true) -> return t
            }
            t = t.cause?.takeIf { it !== t }
        }
        return null
    }
}

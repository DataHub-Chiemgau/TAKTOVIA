package de.taktovia.app.web

import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.Problem
import org.apache.catalina.connector.Request
import org.apache.catalina.connector.Response
import org.apache.catalina.valves.ErrorReportValve
import org.springframework.boot.tomcat.TomcatContextCustomizer
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** Abbildung eines HTTP-Status auf einen Problem-Code und die Serialisierung ohne Fachlogik (für Container-Fehler). */
object ContainerProblems {
    private val json = JsonMapper.builder().build()
    /** Codes, die nur durch Fachlogik entstehen und nicht allein aus dem Status folgen. */
    private val SPECIFIC = setOf(ErrorCode.INVALID_CURSOR, ErrorCode.COOPERATION_NOT_ALLOWED, ErrorCode.IDEMPOTENCY_CONFLICT, ErrorCode.OPERATION_IN_PROGRESS, ErrorCode.OFFER_EXPIRED,
        ErrorCode.HOLD_EXPIRED, ErrorCode.REQUOTE_REQUIRED, ErrorCode.CAPACITY_UNAVAILABLE, ErrorCode.IMMUTABLE_RECORD, ErrorCode.PROVIDER_UNAVAILABLE, ErrorCode.UNCONFIRMED_FIELDS)

    fun codeFor(status: Int): ErrorCode = ErrorCode.entries.firstOrNull { it.status == status && it !in SPECIFIC }
        ?: when (status) { in 400..499 -> ErrorCode.BAD_REQUEST; 503 -> ErrorCode.SERVICE_UNAVAILABLE; else -> ErrorCode.INTERNAL_ERROR }

    fun body(status: Int, correlationId: UUID): Pair<ErrorCode, String> {
        val code = codeFor(status)
        return code to json.writeValueAsString(Problem(code, correlationId, if (status in 400..499) "Die Anfrage ist ungültig." else null).toMap())
    }
}

/**
 * Ersetzt die HTML-Fehlerseite des Tomcat (`ErrorReportValve`): Fehler, die der Container vor jedem Filter erkennt (ungültige
 * Header, kodierte Schrägstriche im Pfad, zu große Header), erscheinen als `application/problem+json` mit den Sicherheitsheadern
 * und ohne Containertext, Version oder Stack (WP-M1-38).
 */
class ProblemErrorReportValve : ErrorReportValve() {
    override fun report(request: Request, response: Response, throwable: Throwable?) {
        val status = response.status
        if (status < 400 || response.isCommitted || response.getReporter() == null) return
        val correlation = request.getAttribute(Correlation.ATTRIBUTE) as? UUID ?: UUID.randomUUID()
        val (code, text) = ContainerProblems.body(status, correlation)
        try {
            response.reset()
            response.status = code.status
            SecurityHeaders.REQUIRED.forEach { (k, v) -> response.setHeader(k, v) }
            response.setHeader(Correlation.HEADER, correlation.toString())
            response.contentType = ProblemWriter.MEDIA_TYPE
            response.characterEncoding = "UTF-8"
            response.getReporter().write(text)
            response.finishResponse()
        } catch (_: Exception) { /* Antwort ist bereits abgebrochen */ }
    }
}

/** Hängt das [ProblemErrorReportValve] an den Host des eingebetteten Tomcat. */
class ProblemErrorValveCustomizer : TomcatContextCustomizer {
    override fun customize(context: org.apache.catalina.Context) {
        (context.parent as? org.apache.catalina.core.StandardHost)?.errorReportValveClass = ProblemErrorReportValve::class.java.name
    }
}

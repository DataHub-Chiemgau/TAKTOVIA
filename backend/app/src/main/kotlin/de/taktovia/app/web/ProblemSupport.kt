package de.taktovia.app.web

import de.taktovia.platform.problem.Problem
import de.taktovia.platform.problem.ProblemException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** Zugriff auf die Correlation-ID der aktuellen Anfrage (gesetzt vom [CorrelationFilter]). */
object Correlation {
    const val ATTRIBUTE = "taktovia.correlationId"
    const val HEADER = "X-Correlation-Id"
    fun of(request: HttpServletRequest): UUID = request.getAttribute(ATTRIBUTE) as? UUID ?: UUID.randomUUID()
}

/** Schreibt Fehler als `application/problem+json` nach Schema `Problem` (`api/common.yaml`), auch aus Filtern. */
class ProblemWriter(private val json: JsonMapper) {
    companion object { const val MEDIA_TYPE = "application/problem+json" }

    fun body(request: HttpServletRequest, e: ProblemException): String =
        json.writeValueAsString(Problem(e.code, Correlation.of(request), e.detail, e.violations).toMap())

    fun write(request: HttpServletRequest, response: HttpServletResponse, e: ProblemException) {
        response.reset()
        response.status = e.code.status
        response.contentType = MEDIA_TYPE
        response.characterEncoding = "UTF-8"
        response.setHeader(Correlation.HEADER, Correlation.of(request).toString())
        e.headers.forEach { (k, v) -> response.setHeader(k, v) }
        response.writer.write(body(request, e))
        response.writer.flush()
    }
}

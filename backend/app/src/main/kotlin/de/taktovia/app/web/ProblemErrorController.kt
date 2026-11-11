package de.taktovia.app.web

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.webmvc.error.ErrorController
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.RequestMapping

/**
 * Fehler, die der Servlet-Container selbst erkennt (ungültige Anfragezeile, Header-Injektion, zu große Header, ungültige
 * Zeichen) erscheinen ebenfalls als `application/problem+json` ohne Containertext und mit den Sicherheitsheadern
 * (ersetzt den `BasicErrorController`). Der Text des Containers wird nie ausgegeben.
 */
@Controller
class ProblemErrorController(private val problems: ProblemWriter) : ErrorController {
    @RequestMapping("/error")
    fun error(request: HttpServletRequest, response: HttpServletResponse): ResponseEntity<String> {
        val status = (request.getAttribute("jakarta.servlet.error.status_code") as? Int) ?: 500
        val (code, body) = ContainerProblems.body(status, Correlation.of(request))
        SecurityHeaders.apply(response)
        val headers = HttpHeaders()
        headers.contentType = MediaType.parseMediaType(ProblemWriter.MEDIA_TYPE)
        headers.set(Correlation.HEADER, Correlation.of(request).toString())
        return ResponseEntity.status(code.status).headers(headers).body(body)
    }
}

package de.taktovia.app.web

import de.taktovia.platform.problem.DbErrorMapper
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.core.annotation.Order
import org.springframework.core.Ordered
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.HttpMediaTypeNotAcceptableException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.NoHandlerFoundException
import org.springframework.web.servlet.resource.NoResourceFoundException
import java.sql.SQLException

/**
 * Globaler Fehlerabbildner (Kap. 05 §5.1, WP-M1-02): jede Ausnahme wird zu `application/problem+json` mit stabilem
 * `code`. Stacktraces, Datenbankmeldungen und Exception-Texte gelangen nie in die Antwort; sie stehen nur im Log, versehen
 * mit der Correlation-ID. Unbekannte und nicht sichtbare Ressourcen ergeben einheitlich 404 `NOT_FOUND`.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class GlobalProblemAdvice(private val problems: ProblemWriter) {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(Exception::class)
    fun handle(e: Exception, request: HttpServletRequest): ResponseEntity<String> {
        val p = map(e, request)
        if (p.code == ErrorCode.INTERNAL_ERROR || p.code == ErrorCode.SERVICE_UNAVAILABLE)
            log.error("Fehler {} correlation_id={}", p.code, Correlation.of(request), e)
        val headers = HttpHeaders()
        headers.contentType = MediaType.parseMediaType(ProblemWriter.MEDIA_TYPE)
        headers.set(Correlation.HEADER, Correlation.of(request).toString())
        p.headers.forEach { (k, v) -> headers.set(k, v) }
        return ResponseEntity.status(p.code.status).headers(headers).body(problems.body(request, p))
    }

    private fun map(e: Exception, request: HttpServletRequest): ProblemException = when (e) {
        is ProblemException -> e
        is NoResourceFoundException, is NoHandlerFoundException -> ProblemException(ErrorCode.NOT_FOUND)
        is HttpRequestMethodNotSupportedException ->
            ProblemException(ErrorCode.METHOD_NOT_ALLOWED, headers = e.supportedMethods?.let { mapOf("Allow" to it.joinToString(", ")) } ?: emptyMap())
        is HttpMediaTypeNotSupportedException -> ProblemException(ErrorCode.UNSUPPORTED_MEDIA_TYPE, "Schreibende Anfragen verlangen Content-Type: application/json (UTF-8).")
        is HttpMediaTypeNotAcceptableException -> ProblemException(ErrorCode.NOT_ACCEPTABLE)
        is HttpMessageNotReadableException -> ProblemException(ErrorCode.BAD_REQUEST, "Der Body ist nicht lesbar.")
        is MissingServletRequestParameterException ->
            ProblemException.invalid(Violation("/\$query/${e.parameterName}", ViolationCode.REQUIRED))
        is MethodArgumentTypeMismatchException ->
            ProblemException.invalid(Violation("/\$query/${e.name}", ViolationCode.TYPE_MISMATCH))
        is DataAccessException, is SQLException -> DbErrorMapper.map(e)
        else -> DbErrorMapper.map(e).takeIf { it.code != ErrorCode.INTERNAL_ERROR } ?: ProblemException(ErrorCode.INTERNAL_ERROR, cause = e)
    }
}

package de.taktovia.app.web

import de.taktovia.platform.api.Etag
import de.taktovia.platform.api.Revisioned
import org.springframework.core.MethodParameter
import org.springframework.http.MediaType
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice

/** Setzt auf Antworten mit [Revisioned] den starken ETag `"<revision>"` (Kap. 05 §5.1); `If-Match` prüft der Schreibpfad atomar. */
@RestControllerAdvice
class EtagAdvice : ResponseBodyAdvice<Any> {
    override fun supports(returnType: MethodParameter, converterType: Class<out HttpMessageConverter<*>>) = true

    override fun beforeBodyWrite(
        body: Any?, returnType: MethodParameter, selectedContentType: MediaType,
        selectedConverterType: Class<out HttpMessageConverter<*>>, request: ServerHttpRequest, response: ServerHttpResponse,
    ): Any? {
        if (body is Revisioned) response.headers.eTag = Etag.format(body.revision)
        return body
    }
}

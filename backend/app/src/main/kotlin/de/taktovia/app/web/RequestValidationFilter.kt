package de.taktovia.app.web

import com.networknt.schema.Error
import de.taktovia.app.openapi.OpenApiRegistry
import de.taktovia.app.openapi.SchemaViolations
import de.taktovia.platform.api.Etag
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.core.exc.StreamConstraintsException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayInputStream
import java.io.BufferedReader
import java.io.InputStreamReader

/** Grenzen für Anfragen (Planungsdefaults, OP-20: vor Produktivschaltung festzulegen). */
data class RequestLimits(val maxBodyBytes: Int = 1_048_576, val maxJsonDepth: Int = 32)

/**
 * Serverseitige Eingabeprüfung auf `/v1/…` (Kap. 05 §5.1/§5.8, WP-M1-02, Härtung WP-M1-38): strikter Content-Type,
 * Größen- und Tiefenlimit, Validierung des Bodys gegen das OpenAPI-/JSON-Schema der Operation (`additionalProperties:false`
 * gegen Mass Assignment), `If-Match` (428/422). Der Body wird einmal gelesen und dem Controller unverändert weitergereicht.
 */
class RequestValidationFilter(
    private val registry: OpenApiRegistry,
    private val limits: RequestLimits,
    private val problems: ProblemWriter,
    private val json: JsonMapper,
) : OncePerRequestFilter() {
    private val bodyMethods = setOf("POST", "PUT", "PATCH")

    override fun shouldNotFilter(request: HttpServletRequest) = !request.requestURI.startsWith("/v1/")

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val wrapped: HttpServletRequest
        try { wrapped = validate(request) } catch (e: ProblemException) { problems.write(request, response, e); return }
        chain.doFilter(wrapped, response)
    }

    private fun validate(request: HttpServletRequest): HttpServletRequest {
        val method = request.method.uppercase()
        val op = registry.match(method, request.requestURI)
        if (method !in bodyMethods) return request.also { if (method != "GET" && method != "HEAD") checkIfMatch(it, op?.requiredParameters) }

        val declared = request.contentLengthLong
        if (declared > limits.maxBodyBytes) throw tooLarge()
        val bytes = readLimited(request)
        val hasBody = bytes.isNotEmpty()
        if (hasBody) {
            val ct = request.contentType?.let { runCatching { MediaType.parseMediaType(it) }.getOrNull() }
            if (ct == null || !(ct.type == "application" && ct.subtype == "json") || (ct.charset != null && ct.charset!!.name() != "UTF-8"))
                throw ProblemException(ErrorCode.UNSUPPORTED_MEDIA_TYPE, "Schreibende Anfragen verlangen Content-Type: application/json (UTF-8).")
        }
        // Reihenfolge: zuerst Vorbedingung If-Match (428), dann Body-Validierung (422).
        checkIfMatch(request, op?.requiredParameters)
        if (op?.requestSchemaLocation != null) {
            if (!hasBody) {
                if (op.requestBodyRequired) throw ProblemException.invalid(Violation("/", ViolationCode.REQUIRED))
            } else {
                val node = parse(bytes)
                val errors: List<Error> = registry.requestSchema(op)!!.validate(node)
                val violations = SchemaViolations.from(errors)
                if (violations.isNotEmpty()) throw ProblemException(ErrorCode.VALIDATION_FAILED, "Mindestens ein Feld ist ungültig.", violations.take(100))
            }
        } else if (hasBody) {
            parse(bytes) // auch ohne Schema muss der Body gültiges JSON in den Grenzen sein
        }
        return CachedBodyRequest(request, bytes)
    }

    private fun checkIfMatch(request: HttpServletRequest, required: Set<String>?) {
        val header = request.getHeader("If-Match")
        if (required?.contains("IfMatch") == true) Etag.parseIfMatch(header)
        else if (header != null) Etag.parseIfMatch(header)
    }

    private fun parse(bytes: ByteArray): JsonNode = try { json.readTree(bytes) } catch (e: StreamConstraintsException) {
        throw ProblemException(ErrorCode.PAYLOAD_TOO_LARGE, "Die Verschachtelung oder ein Wert der Anfrage überschreitet die Grenzen.")
    } catch (e: RuntimeException) {
        throw ProblemException(ErrorCode.BAD_REQUEST, "Der Body ist kein gültiges JSON.")
    }

    private fun readLimited(request: HttpServletRequest): ByteArray {
        val max = limits.maxBodyBytes
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        request.inputStream.use { ins ->
            while (true) {
                val n = ins.read(buf); if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > max) throw tooLarge()
            }
        }
        return out.toByteArray()
    }

    private fun tooLarge() = ProblemException(ErrorCode.PAYLOAD_TOO_LARGE, "Die Anfrage überschreitet die zulässige Größe von ${limits.maxBodyBytes} Byte.")
}

/** Reicht den bereits gelesenen Body an Spring weiter. */
class CachedBodyRequest(request: HttpServletRequest, private val body: ByteArray) : HttpServletRequestWrapper(request) {
    override fun getInputStream(): ServletInputStream {
        val ins = ByteArrayInputStream(body)
        return object : ServletInputStream() {
            override fun read() = ins.read()
            override fun read(b: ByteArray, off: Int, len: Int) = ins.read(b, off, len)
            override fun isFinished() = ins.available() == 0
            override fun isReady() = true
            override fun setReadListener(l: ReadListener?) { throw UnsupportedOperationException("synchron") }
        }
    }
    override fun getReader() = BufferedReader(InputStreamReader(inputStream, characterEncoding ?: "UTF-8"))
    override fun getContentLength() = body.size
    override fun getContentLengthLong() = body.size.toLong()
}

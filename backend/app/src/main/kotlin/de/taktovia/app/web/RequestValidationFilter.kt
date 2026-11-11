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

/** Zusätzliche Prüfung des geparsten Bodys vor der Schema-Validierung (z. B. Tenant-Angaben, WP-M1-06); wirft [ProblemException]. */
fun interface RequestBodyInspector {
    fun inspect(request: HttpServletRequest, op: de.taktovia.app.openapi.ApiOperation?, body: JsonNode)
}

/** Grenzen für Anfragen (Planungsdefaults, OP-20: vor Produktivschaltung festzulegen). */
data class RequestLimits(val maxBodyBytes: Int = 1_048_576, val maxJsonDepth: Int = 32)

/**
 * Serverseitige Eingabeprüfung auf `/v1/…` (Kap. 05 §5.1/§5.8, WP-M1-02, Härtung WP-M1-38): strikter Content-Type,
 * Größen- und Tiefenlimit, Validierung des Bodys gegen das OpenAPI-/JSON-Schema der Operation (`additionalProperties:false`
 * gegen Mass Assignment), `If-Match` (428/422). Der Body wird einmal gelesen und dem Controller unverändert weitergereicht.
 */
private val SAFE_NAME = Regex("^[A-Za-z0-9_.-]{1,64}$")

class RequestValidationFilter(
    private val registry: OpenApiRegistry,
    private val limits: RequestLimits,
    private val problems: ProblemWriter,
    private val json: JsonMapper,
    private val inspectors: List<RequestBodyInspector> = emptyList(),
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
        if (op != null) validateParameters(request, op)
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
                inspectors.forEach { it.inspect(request, op, node) }
                controlCharacters(node)?.let { throw ProblemException.invalid(Violation(it, ViolationCode.INVALID_VALUE)) }
                val errors: List<Error> = registry.requestSchema(op)!!.validate(node)
                val violations = SchemaViolations.from(errors)
                if (violations.isNotEmpty()) throw ProblemException(ErrorCode.VALIDATION_FAILED, "Mindestens ein Feld ist ungültig.", violations.take(100))
            }
        } else if (hasBody) {
            inspectors.forEach { it.inspect(request, op, parse(bytes)) }
            controlCharacters(parse(bytes))?.let { throw ProblemException.invalid(Violation(it, ViolationCode.INVALID_VALUE)) } // auch ohne Schema: gültiges JSON in den Grenzen
        }
        return CachedBodyRequest(request, bytes)
    }

    /**
     * Pfad-, Query- und Header-Parameter gegen den Vertrag (WP-M1-38): jede Pfadvariable und jeder Query-Parameter hat sein
     * Schema (UUID, Zahl, Aufzählung …); unbekannte und doppelte Query-Parameter (HTTP Parameter Pollution) werden abgewiesen,
     * ebenso Steuerzeichen. Der Name eines unbekannten Parameters wird nie in die Antwort übernommen.
     */
    private fun validateParameters(request: HttpServletRequest, op: de.taktovia.app.openapi.ApiOperation) {
        val violations = mutableListOf<Violation>()
        for ((name, raw) in op.pathValues(request.requestURI)) {
            val spec = op.paramSpecs.firstOrNull { it.location == "path" && it.name == name } ?: continue
            val value = try { java.net.URLDecoder.decode(raw, Charsets.UTF_8) } catch (_: IllegalArgumentException) { violations += Violation("/\$path/$name", ViolationCode.INVALID_FORMAT); continue }
            checkValue(spec, value, "/\$path/$name", violations)
        }
        val seen = HashMap<String, MutableList<String>>()
        val query = request.queryString
        if (!query.isNullOrEmpty()) {
            if (query.length > 4096) throw ProblemException(ErrorCode.BAD_REQUEST, "Die Query ist zu lang.")
            for (pair in query.split('&').filter { it.isNotEmpty() }) {
                val kv = pair.split('=', limit = 2)
                try {
                    seen.getOrPut(java.net.URLDecoder.decode(kv[0], Charsets.UTF_8)) { mutableListOf() }.add(java.net.URLDecoder.decode(kv.getOrElse(1) { "" }, Charsets.UTF_8))
                } catch (_: IllegalArgumentException) { throw ProblemException(ErrorCode.BAD_REQUEST, "Die Query ist nicht korrekt kodiert.") }
            }
        }
        val declared = op.paramSpecs.filter { it.location == "query" }.associateBy { it.name }
        for ((name, values) in seen) {
            val spec = declared[name]
            val pointer = if (SAFE_NAME.matches(name)) "/\$query/$name" else "/\$query"
            when {
                spec == null -> violations += Violation(pointer, ViolationCode.UNKNOWN_FIELD)
                values.size > 1 -> violations += Violation(pointer, ViolationCode.NOT_UNIQUE)
                else -> checkValue(spec, values[0], pointer, violations)
            }
        }
        for (spec in declared.values) if (spec.required && spec.name !in seen) violations += Violation("/\$query/${spec.name}", ViolationCode.REQUIRED)
        for (spec in op.paramSpecs.filter { it.location == "header" && it.name != "If-Match" && it.name != "traceparent" }) {
            val v = request.getHeader(spec.name) ?: continue
            checkValue(spec, v, "/\$header/${spec.name}", violations)
        }
        if (violations.isNotEmpty()) throw ProblemException(ErrorCode.VALIDATION_FAILED, "Mindestens ein Parameter ist ungültig.", violations.take(100))
    }

    private fun checkValue(spec: de.taktovia.app.openapi.ParamSpec, value: String, pointer: String, out: MutableList<Violation>) {
        if (value.any { it.code < 0x20 || it.code == 0x7f }) { out += Violation(pointer, ViolationCode.INVALID_VALUE); return }
        val schema = registry.paramSchema(spec) ?: return
        val node: JsonNode = when (spec.type) {
            "integer", "number" -> value.toBigDecimalOrNull()?.let { json.valueToTree<JsonNode>(it) } ?: json.valueToTree<JsonNode>(value)
            "boolean" -> if (value == "true" || value == "false") json.valueToTree<JsonNode>(value.toBoolean()) else json.valueToTree<JsonNode>(value)
            else -> json.valueToTree<JsonNode>(value)
        }
        SchemaViolations.from(schema.validate(node)).firstOrNull()?.let { out += Violation(pointer, it.code) }
    }

    private fun checkIfMatch(request: HttpServletRequest, required: Set<String>?) {
        val header = request.getHeader("If-Match")
        if (required?.contains("IfMatch") == true) Etag.parseIfMatch(header)
        else if (header != null) Etag.parseIfMatch(header)
    }

    /** Steuerzeichen (außer Tab, Zeilenumbruch) in Werten oder Schlüsseln, z. B. NUL: JSON-Pointer des ersten Fundes oder `null`. */
    private fun controlCharacters(n: JsonNode, pointer: String = ""): String? {
        fun bad(s: String) = s.any { (it.code < 0x20 && it != '\t' && it != '\n' && it != '\r') || it.code == 0x7f }
        when {
            n.isString -> if (bad(n.asString())) return pointer.ifEmpty { "/" }
            n.isObject -> for ((k, v) in n.properties()) {
                if (bad(k)) return pointer.ifEmpty { "/" }
                controlCharacters(v, "$pointer/${k.replace("~", "~0").replace("/", "~1")}")?.let { return it }
            }
            n.isArray -> n.values().forEachIndexed { i, v -> controlCharacters(v, "$pointer/$i")?.let { return it } }
        }
        return null
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

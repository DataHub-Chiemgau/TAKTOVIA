package de.taktovia.app.web

import de.taktovia.app.identity.RequestContexts
import de.taktovia.app.openapi.OpenApiRegistry
import de.taktovia.platform.idempotency.IdempotencyRegistry
import de.taktovia.platform.idempotency.IdempotencyResult
import de.taktovia.platform.idempotency.IdempotencyService
import de.taktovia.platform.idempotency.IdempotentRequest
import de.taktovia.platform.idempotency.RequestFingerprint
import de.taktovia.platform.idempotency.StoredResponse
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.platform.tenant.TenantId
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.ContentCachingResponseWrapper
import java.util.UUID

/**
 * Idempotenzfilter (WP-M1-13, LH-6.17-INT-004) für alle schreibenden Operationen, die `Idempotency-Key` verlangen (Vertrag `IdempotencyKey`).
 * Stellung in der Kette: **nach** Authentisierung, Mandantenkontext und Autorisierung (jede Wiederholung wird neu authentisiert und neu
 * autorisiert, ein Rechteentzug wirkt sofort) und nach der Eingabeprüfung; **vor** dem Controller, dessen ETag-/Fristprüfung bei einer
 * Wiederholung deshalb nicht erneut läuft. Scope `(Akteur, Mandant, operationId, Schlüssel)`, Fingerprint aus [RequestFingerprint].
 *
 * Erstausführung: Antwort wird erfasst, bei 2xx gespeichert und erst danach ausgeliefert. Wiederholung: gespeicherte Antwort (Status, Location, ETag,
 * Body) mit `Idempotent-Replayed: true`. Anderer Fingerprint: 409 `IDEMPOTENCY_CONFLICT`. Laufende Erstverarbeitung: Wartezeit, dann 409
 * `OPERATION_IN_PROGRESS` mit `Retry-After`. Der Controller darf die entstandene `operation` über das Anfrageattribut [OPERATION_ID_ATTRIBUTE] melden.
 */
class IdempotencyFilter(
    private val registry: OpenApiRegistry,
    private val service: IdempotencyService,
    private val fallbackIdentity: () -> RequestIdentityResolver,
    private val problems: ProblemWriter,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        if (!request.requestURI.startsWith("/v1/") || request.method.uppercase() !in WRITE_METHODS) return true
        val op = registry.match(request.method, request.requestURI) ?: return true
        return "IdempotencyKey" !in op.requiredParameters
    }

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val op = registry.match(request.method, request.requestURI)!!
        val (tenant, principal) = identify(request) ?: return chain.doFilter(request, response)   // ohne Akteur gibt es nichts zu deduplizieren (die Authentisierung weist solche Anfragen vorher ab)
        val key = request.getHeader(HEADER)
        val body = request.inputStream.readAllBytes()
        val cached = CachedBodyRequest(request, body)
        try {
            if (key == null) throw ProblemException.invalid(Violation("/\$header/$HEADER", ViolationCode.REQUIRED))
            val fingerprint = RequestFingerprint.of(request.method, op.operationId, request.requestURI, request.queryString, mapOf("If-Match" to request.getHeader("If-Match")), body)
            var wrapper: ContentCachingResponseWrapper? = null
            val result = try {
                service.execute(IdempotentRequest(tenant, principal, op.operationId, key, fingerprint)) {
                    val w = ContentCachingResponseWrapper(response).also { wrapper = it }
                    chain.doFilter(cached, w)
                    capture(request, w)
                }
            } catch (e: IllegalArgumentException) { throw ProblemException.invalid(Violation("/\$header/$HEADER", ViolationCode.INVALID_FORMAT)) }
            when (result) {
                is IdempotencyResult.Executed -> wrapper!!.copyBodyToResponse()   // erst nach dem Speichern: was der Client sieht, ist wiederholbar
                is IdempotencyResult.Replayed -> replay(response, result.response)
                IdempotencyResult.Conflict -> throw ProblemException(ErrorCode.IDEMPOTENCY_CONFLICT, "Der Idempotency-Key wurde bereits mit einer anderen Anfrage verwendet.")
                is IdempotencyResult.InProgress -> throw ProblemException(ErrorCode.OPERATION_IN_PROGRESS, "Die Anfrage mit diesem Idempotency-Key wird noch verarbeitet.",
                    headers = mapOf("Retry-After" to result.retryAfterSeconds.toString()))
            }
        } catch (e: ProblemException) {
            problems.write(request, response, e)
        }
    }

    private fun capture(request: HttpServletRequest, w: ContentCachingResponseWrapper): StoredResponse {
        val headers = IdempotencyRegistry.STORED_HEADERS.mapNotNull { name -> (if (name == "Content-Type") w.contentType else w.getHeader(name))?.let { name to it } }.toMap()
        val bytes = w.contentAsByteArray
        return StoredResponse(w.status, headers, if (bytes.isEmpty()) null else String(bytes, Charsets.UTF_8), request.getAttribute(OPERATION_ID_ATTRIBUTE) as? UUID)
    }

    private fun replay(response: HttpServletResponse, stored: StoredResponse) {
        response.status = stored.status
        stored.headers.filterKeys { it != "Content-Type" }.forEach { (k, v) -> response.setHeader(k, v) }
        response.setHeader(REPLAY_HEADER, "true")
        stored.body?.let { body ->
            response.contentType = stored.headers["Content-Type"] ?: "application/json"
            response.characterEncoding = "UTF-8"
            val bytes = body.toByteArray(Charsets.UTF_8)
            response.setContentLength(bytes.size)
            response.outputStream.write(bytes)
        }
    }

    /** Mandant und Akteur: aus dem geprüften Kontext der Anfrage; nur ohne Kontext (Prüf-Endpunkte der Tests) aus der Prüfidentität. */
    private fun identify(request: HttpServletRequest): Pair<TenantId, String>? {
        RequestContexts.of(request)?.let { return it.tenant to "${it.actor.issuer}|${it.actor.subject}" }
        val resolver = fallbackIdentity()
        val tenant = runCatching { TenantId.parse(resolver.tenant(request)) }.getOrNull() ?: return null
        return tenant to "probe|${resolver.actor(request)}"
    }

    companion object {
        const val HEADER = "Idempotency-Key"
        const val REPLAY_HEADER = "Idempotent-Replayed"
        const val OPERATION_ID_ATTRIBUTE = "taktovia.idempotency.operationId"
        private val WRITE_METHODS = setOf("POST", "PUT", "PATCH", "DELETE")
    }
}

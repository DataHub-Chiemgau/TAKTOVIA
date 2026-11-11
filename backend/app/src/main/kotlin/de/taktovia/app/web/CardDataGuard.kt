package de.taktovia.app.web

import de.taktovia.app.identity.RequestContexts
import de.taktovia.app.openapi.ApiOperation
import de.taktovia.platform.carddata.CardDataFilter
import de.taktovia.platform.carddata.CardDataFinding
import de.taktovia.platform.carddata.CardDataIncidents
import de.taktovia.platform.carddata.CardDataInput
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.JsonNode

/** Abbildung einer Fundstelle des Kartendatenfilters auf einen Violation-Pointer (Kap. 05 §5.1); nie mit Teilen des Wertes. */
internal fun CardDataFinding.toViolation(): Violation {
    val pointer = when {
        location.startsWith("query:") -> location.removePrefix("query:").let { if (it == "*") "/\$query" else "/\$query/$it" }
        location.startsWith("header:") -> location.removePrefix("header:").let { if (it == "*") "/\$header" else "/\$header/$it" }
        else -> location.removePrefix("body:")
    }
    return Violation(pointer, ViolationCode.INVALID_VALUE)
}

private fun reject(request: HttpServletRequest, incidents: CardDataIncidents, findings: List<CardDataFinding>): Nothing {
    incidents.report(RequestContexts.of(request)?.tenant, Correlation.of(request), findings)
    throw ProblemException.invalid(*findings.map { it.toViolation() }.take(20).toTypedArray())
}

/**
 * WP-M1-37 (SEC-008): weist Anfragen mit kartennummernähnlichen Mustern in Query oder Headern ab (422, Fundstelle ohne Wert). Läuft früh in der
 * Kette, damit der Wert weder Quote, Authentifizierung noch Fachlogik erreicht. Der Body wird vom [CardDataBodyInspector] in der Eingabeprüfung geprüft.
 */
class CardDataRequestFilter(private val problems: ProblemWriter, private val incidents: CardDataIncidents) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        // Signaturheader tragen Hexwerte eines HMAC (Ausgabe einer Hashfunktion, keine Zahlungsdaten): eine zufällige Ziffernfolge mit gültiger Luhn-Summe würde sonst eine
        // rechtmäßige Nachricht abweisen (WP-M2-29). Alle übrigen Header bleiben geprüft; der Body (die eigentliche Nutzlast) ebenfalls.
        val headers = request.headerNames.toList().filter { it.lowercase() !in SIGNATURE_HEADERS }.associateWith { n -> request.getHeaders(n).toList() }
        val findings = CardDataFilter.screen(CardDataInput(query = request.queryString, headers = headers))
        if (findings.isNotEmpty()) {
            try { reject(request, incidents, findings) } catch (e: ProblemException) { problems.write(request, response, e); return }
        }
        chain.doFilter(request, response)
    }
}

private val SIGNATURE_HEADERS = setOf("x-provider-signature", "taktovia-signature")

/** Prüft den geparsten JSON-Body vor der Schema-Validierung (Hook [RequestBodyInspector]). */
class CardDataBodyInspector(private val incidents: CardDataIncidents) : RequestBodyInspector {
    override fun inspect(request: HttpServletRequest, op: ApiOperation?, body: JsonNode) {
        val findings = CardDataFilter.screen(CardDataInput(body = body))
        if (findings.isNotEmpty()) reject(request, incidents, findings)
    }
}

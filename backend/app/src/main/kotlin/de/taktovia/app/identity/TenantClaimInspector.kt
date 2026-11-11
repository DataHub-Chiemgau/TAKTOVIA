package de.taktovia.app.identity

import de.taktovia.app.openapi.ApiOperation
import de.taktovia.app.web.RequestBodyInspector
import de.taktovia.platform.problem.ProblemException
import jakarta.servlet.http.HttpServletRequest
import tools.jackson.databind.JsonNode

/**
 * Body-Fläche der Mandantenmanipulation (LH-I.4-MUL-006): trägt ein Anfrage-Body ein `tenant_id`, das vom aktiven Mandanten abweicht,
 * entsteht kein Recht und keine Information: Antwort 404 wie „nicht vorhanden“ plus Sicherheitsereignis. Einzige Ausnahme sind
 * Operationen der Mandantenauswahl ([SELECTION_OPERATIONS]); dort ist `tenant_id` die gewünschte Auswahl, die die Mitgliedschaftsprüfung
 * entscheidet, nie ein Nachweis.
 */
class TenantClaimInspector(private val mismatch: TenantMismatchReporter) : RequestBodyInspector {
    override fun inspect(request: HttpServletRequest, op: ApiOperation?, body: JsonNode) {
        val ctx = RequestContexts.of(request) ?: return
        if (op?.operationId in SELECTION_OPERATIONS) return
        if (foreignTenant(body, ctx.tenant.toString(), 0)) {
            mismatch.report(ctx, "body")
            throw ProblemException.notFound()
        }
    }

    private fun foreignTenant(n: JsonNode, active: String, depth: Int): Boolean {
        if (depth > 40) return false   // tiefer ist durch das Tiefenlimit der Eingabeprüfung ohnehin abgewiesen
        return when {
            n.isObject -> n.properties().any { (k, v) -> (NAME.matches(k) && !(v.isString && v.asString().equals(active, ignoreCase = true))) || foreignTenant(v, active, depth + 1) }
            n.isArray -> n.values().any { foreignTenant(it, active, depth + 1) }
            else -> false
        }
    }

    companion object {
        val SELECTION_OPERATIONS = setOf("switchTenant")
        private val NAME = Regex("(?i)^tenant_?id$")
    }
}

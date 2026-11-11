package de.taktovia.app.structure

import de.taktovia.app.identity.RequestContexts
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.servicegeo.api.ModelRules
import de.taktovia.servicegeo.api.ServicegeoApi
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.UUID

/** Dienstkonfiguration und Angebotsmodelle (WP-M1-25, UI-17 Basis): wirksame Konfiguration eines Dienstes und die Regeltabelle der Modelle. Schreiben geschieht über Konfigurationsrevisionen (`/v1/config/revisions`). */
@RestController
class ServiceConfigurationController(private val geo: ServicegeoApi) {

    @GetMapping("/v1/services/{service_id}/configuration")
    fun configuration(@PathVariable("service_id") id: UUID, @RequestParam(required = false) at: String?, request: HttpServletRequest): Map<String, Any?> {
        val v = geo.configurations.effective(RequestContexts.require(request), id, at?.let(::instant))
        return linkedMapOf(
            "service_id" to v.serviceId.toString(), "as_of" to v.asOf.toString(), "offer_model" to v.model?.name, "model_rules" to v.rules?.let(::rulesJson),
            "parameters" to v.parameters, "revision_id" to v.revisionId?.toString(), "effective_from" to v.effectiveFrom?.toString(),
        )
    }

    @GetMapping("/v1/service-offer-models")
    fun models(request: HttpServletRequest): Map<String, Any?> {
        RequestContexts.require(request)
        return linkedMapOf("items" to geo.configurations.modelRules().map(::rulesJson))
    }

    private fun rulesJson(r: ModelRules): Map<String, Any?> = linkedMapOf(
        "model" to r.model.name, "label" to r.model.label, "required" to r.required.sorted(), "forbidden" to r.forbidden.sorted(), "optional" to r.optional.sorted(),
    )

    private fun instant(v: String): Instant = try { OffsetDateTime.parse(v).toInstant() } catch (_: DateTimeParseException) {
        throw ProblemException.invalid(Violation("/\$query/at", if (Regex(".*(Z|[+-][0-9]{2}:[0-9]{2})$").matches(v)) ViolationCode.INVALID_FORMAT else ViolationCode.OFFSET_REQUIRED))
    }
}

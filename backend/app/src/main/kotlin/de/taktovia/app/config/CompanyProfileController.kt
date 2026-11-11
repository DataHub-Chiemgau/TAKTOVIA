package de.taktovia.app.config

import com.fasterxml.jackson.annotation.JsonValue
import de.taktovia.app.identity.RequestContexts
import de.taktovia.config.api.CompanyProfileInput
import de.taktovia.config.api.CompanyProfileView
import de.taktovia.config.api.ConfigApi
import de.taktovia.platform.api.Etag
import de.taktovia.platform.api.Revisioned
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

class CompanyProfileResource(@get:JsonValue val data: Map<String, Any?>, override val revision: Long) : Revisioned

/** Anfrage-DTO „Unternehmensprofil ändern“; der Mandant steht nicht darin, er folgt aus dem Kontext. */
class CompanyProfileUpdateRequest(val input: CompanyProfileInput) {
    companion object {
        fun from(b: Map<String, Any?>) = CompanyProfileUpdateRequest(CompanyProfileInput(
            b["legal_name"] as String, b["contact_email"] as String, b["default_currency"] as String,
            b["address"] as String?, b["contact_phone"] as String?, b["billing_address"] as String?, b["billing_email"] as String?,
            b["operating_mode"] as String?, b["legal_notice"] as String?,
        ))
    }
}

/** Unternehmensprofil des aktiven Mandanten (WP-M1-24, UI-18 Basis); Rechte `company:read`/`company:write` aus der Rechtematrix (Filter). */
@RestController
class CompanyProfileController(private val config: ConfigApi) {

    @GetMapping("/v1/company-profile")
    fun get(request: HttpServletRequest): CompanyProfileResource = resource(config.companyProfile.get(RequestContexts.require(request)))

    @PutMapping("/v1/company-profile")
    fun update(@RequestHeader("If-Match", required = false) ifMatch: String?, @RequestBody body: Map<String, Any?>, request: HttpServletRequest): CompanyProfileResource =
        resource(config.companyProfile.update(RequestContexts.require(request), Etag.parseIfMatch(ifMatch), CompanyProfileUpdateRequest.from(body).input))

    private fun resource(v: CompanyProfileView): CompanyProfileResource {
        val i = v.input
        val data = linkedMapOf<String, Any?>(
            "legal_name" to i.legalName, "contact_email" to i.contactEmail, "contact_phone" to i.contactPhone, "address" to i.address,
            "billing_address" to i.billingAddress, "billing_email" to i.billingEmail, "operating_mode" to i.operatingMode, "legal_notice" to i.legalNotice,
            "default_currency" to i.defaultCurrency, "revision" to v.revision,
        ).filterValues { it != null }
        return CompanyProfileResource(data, v.revision)
    }
}

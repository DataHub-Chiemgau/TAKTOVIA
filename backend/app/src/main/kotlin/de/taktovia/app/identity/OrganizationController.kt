package de.taktovia.app.identity

import com.fasterxml.jackson.annotation.JsonValue
import de.taktovia.identity.api.CreateLegalEntity
import de.taktovia.identity.api.CreateOrganization
import de.taktovia.identity.api.IdentityApi
import de.taktovia.identity.api.LegalEntityView
import de.taktovia.identity.api.OrganizationView
import de.taktovia.identity.api.UpdateLegalEntity
import de.taktovia.identity.api.UpdateOrganization
import de.taktovia.platform.api.CursorCodec
import de.taktovia.platform.api.CursorPosition
import de.taktovia.platform.api.CursorScope
import de.taktovia.platform.api.Etag
import de.taktovia.platform.api.Paging
import de.taktovia.platform.api.Revisioned
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

class OrganizationResource(@get:JsonValue val data: Map<String, Any?>, override val revision: Long) : Revisioned

/** Anfrage-DTOs der Stammdatenverwaltung; der Mandant steht nie darin, er folgt aus dem Kontext (RequestContextRules). */
class LegalEntityCreateRequest(val name: String, val country: String, val taxReferenceToken: String?) {
    fun toCommand() = CreateLegalEntity(name, country, taxReferenceToken)
    companion object { fun from(b: Map<String, Any?>) = LegalEntityCreateRequest(b["name"] as String, b["country"] as String, b["tax_reference_token"] as String?) }
}

class LegalEntityUpdateRequest(val name: String, val country: String) {
    fun toCommand() = UpdateLegalEntity(name, country)
    companion object { fun from(b: Map<String, Any?>) = LegalEntityUpdateRequest(b["name"] as String, b["country"] as String) }
}

private fun instant(v: Any?): Instant? = (v as String?)?.let { OffsetDateTime.parse(it).toInstant() }

class OrganizationCreateRequest(val legalEntityId: UUID, val name: String, val federationRef: String, val roles: List<String>, val validFrom: Instant?, val validTo: Instant?) {
    fun toCommand() = CreateOrganization(legalEntityId, name, federationRef, roles, validFrom, validTo)
    companion object {
        fun from(b: Map<String, Any?>) = OrganizationCreateRequest(UUID.fromString(b["legal_entity_id"] as String), b["name"] as String, b["federation_ref"] as String,
            (b["roles"] as List<*>).map { it as String }, instant(b["roles_valid_from"]), instant(b["roles_valid_to"]))
    }
}

class OrganizationUpdateRequest(val legalEntityId: UUID, val name: String, val roles: List<String>, val validFrom: Instant?, val validTo: Instant?) {
    fun toCommand() = UpdateOrganization(legalEntityId, name, roles, validFrom, validTo)
    companion object {
        fun from(b: Map<String, Any?>) = OrganizationUpdateRequest(UUID.fromString(b["legal_entity_id"] as String), b["name"] as String,
            (b["roles"] as List<*>).map { it as String }, instant(b["roles_valid_from"]), instant(b["roles_valid_to"]))
    }
}

/** Rechtsträger und Organisationen des aktiven Mandanten (WP-M1-05, UI-18 Basis). Rechte folgen aus der Rechtematrix (Filter), Daten aus RLS. */
@RestController
class OrganizationController(private val identity: IdentityApi, private val codec: CursorCodec) {

    // ---- Rechtsträger ----

    @GetMapping("/v1/legal-entities")
    fun listLegalEntities(@RequestParam(required = false) limit: String?, @RequestParam(required = false) cursor: String?, request: HttpServletRequest): Map<String, Any?> {
        val ctx = RequestContexts.require(request)
        val scope = CursorScope(ctx.tenant.toString(), ctx.actor.subject, "legal-entities")
        val lim = Paging.limit(limit)
        val rows = identity.organizations.listLegalEntities(ctx, lim, Paging.position(cursor, codec, scope)?.let { it.createdAt to it.id })
        return Paging.page(rows, lim, { CursorPosition(it.createdAt, it.id) }, codec, scope, Instant.now()).toMap { json(it) }
    }

    @PostMapping("/v1/legal-entities")
    fun createLegalEntity(@RequestBody body: Map<String, Any?>, request: HttpServletRequest): ResponseEntity<OrganizationResource> {
        val v = identity.organizations.createLegalEntity(RequestContexts.require(request), LegalEntityCreateRequest.from(body).toCommand())
        return ResponseEntity.status(HttpStatus.CREATED).location(URI.create("/v1/legal-entities/${v.id}")).body(OrganizationResource(json(v), v.revision))
    }

    @PutMapping("/v1/legal-entities/{legal_entity_id}")
    fun updateLegalEntity(@PathVariable("legal_entity_id") id: UUID, @RequestHeader("If-Match", required = false) ifMatch: String?, @RequestBody body: Map<String, Any?>, request: HttpServletRequest): OrganizationResource {
        val v = identity.organizations.updateLegalEntity(RequestContexts.require(request), id, Etag.parseIfMatch(ifMatch), LegalEntityUpdateRequest.from(body).toCommand())
        return OrganizationResource(json(v), v.revision)
    }

    // ---- Organisationen ----

    @GetMapping("/v1/organizations")
    fun listOrganizations(@RequestParam(required = false) limit: String?, @RequestParam(required = false) cursor: String?, request: HttpServletRequest): Map<String, Any?> {
        val ctx = RequestContexts.require(request)
        val scope = CursorScope(ctx.tenant.toString(), ctx.actor.subject, "organizations")
        val lim = Paging.limit(limit)
        val rows = identity.organizations.listOrganizations(ctx, lim, Paging.position(cursor, codec, scope)?.let { it.createdAt to it.id })
        return Paging.page(rows, lim, { CursorPosition(it.createdAt, it.id) }, codec, scope, Instant.now()).toMap { json(it) }
    }

    @PostMapping("/v1/organizations")
    fun createOrganization(@RequestBody body: Map<String, Any?>, request: HttpServletRequest): ResponseEntity<OrganizationResource> {
        val v = identity.organizations.createOrganization(RequestContexts.require(request), OrganizationCreateRequest.from(body).toCommand())
        return ResponseEntity.status(HttpStatus.CREATED).location(URI.create("/v1/organizations/${v.id}")).body(OrganizationResource(json(v), v.revision))
    }

    @GetMapping("/v1/organizations/{organization_id}")
    fun getOrganization(@PathVariable("organization_id") id: UUID, request: HttpServletRequest): OrganizationResource {
        val v = identity.organizations.getOrganization(RequestContexts.require(request), id)
        return OrganizationResource(json(v), v.revision)
    }

    @PutMapping("/v1/organizations/{organization_id}")
    fun updateOrganization(@PathVariable("organization_id") id: UUID, @RequestHeader("If-Match", required = false) ifMatch: String?, @RequestBody body: Map<String, Any?>, request: HttpServletRequest): OrganizationResource {
        val v = identity.organizations.updateOrganization(RequestContexts.require(request), id, Etag.parseIfMatch(ifMatch), OrganizationUpdateRequest.from(body).toCommand())
        return OrganizationResource(json(v), v.revision)
    }

    private fun json(v: LegalEntityView): Map<String, Any?> = linkedMapOf("id" to v.id.toString(), "name" to v.name, "country" to v.country, "revision" to v.revision)

    private fun json(v: OrganizationView): Map<String, Any?> = linkedMapOf(
        "id" to v.id.toString(), "legal_entity_id" to v.legalEntityId.toString(), "name" to v.name, "federation_ref" to v.federationRef, "roles" to v.roles,
        "roles_valid_from" to v.rolesValidFrom.toString(), "roles_valid_to" to v.rolesValidTo?.toString(), "revision" to v.revision,
    )
}

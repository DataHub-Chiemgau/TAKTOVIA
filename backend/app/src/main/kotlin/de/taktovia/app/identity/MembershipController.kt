package de.taktovia.app.identity

import com.fasterxml.jackson.annotation.JsonValue
import de.taktovia.identity.api.GrantMembership
import de.taktovia.identity.api.IdentityApi
import de.taktovia.identity.api.MembershipView
import de.taktovia.platform.api.CursorCodec
import de.taktovia.platform.api.CursorPosition
import de.taktovia.platform.api.CursorScope
import de.taktovia.platform.api.Etag
import de.taktovia.platform.api.Paging
import de.taktovia.platform.api.Revisioned
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.time.Instant
import java.util.UUID

class MembershipResource(@get:JsonValue val data: Map<String, Any?>, override val revision: Long) : Revisioned

/** Anfrage-DTO „Mitgliedschaft erteilen“; der Mandant steht nicht darin, er folgt aus dem Kontext. */
class MembershipCreateRequest(val issuer: String, val subject: String, val roleCode: String, val serviceScope: List<UUID>?, val validFrom: Instant?, val validTo: Instant?) {
    fun toCommand() = GrantMembership(issuer, subject, roleCode, serviceScope, validFrom, validTo)

    companion object {
        fun from(b: Map<String, Any?>) = MembershipCreateRequest(
            b["issuer"] as String, b["subject"] as String, b["role_code"] as String,
            (b["service_scope"] as List<*>?)?.map { UUID.fromString(it as String) },
            (b["valid_from"] as String?)?.let { java.time.OffsetDateTime.parse(it).toInstant() }, (b["valid_to"] as String?)?.let { java.time.OffsetDateTime.parse(it).toInstant() },
        )
    }
}

/** Verwaltung von Mitgliedschaften (WP-M1-06, UI-18 Basis): erteilen, entziehen, auflisten im Mandanten des Kontexts. */
@RestController
class MembershipController(private val identity: IdentityApi, private val codec: CursorCodec) {

    @GetMapping("/v1/memberships")
    fun list(@RequestParam(required = false) limit: String?, @RequestParam(required = false) cursor: String?, request: HttpServletRequest): Map<String, Any?> {
        val ctx = RequestContexts.require(request)
        val scope = CursorScope(ctx.tenant.toString(), ctx.actor.subject, "memberships")
        val lim = Paging.limit(limit)
        val pos = Paging.position(cursor, codec, scope)
        val rows = identity.admin.list(ctx, lim, pos?.let { it.createdAt to it.id })
        return Paging.page(rows, lim, { CursorPosition(it.createdAt, it.id) }, codec, scope, Instant.now()).toMap { json(it) }
    }

    @PostMapping("/v1/memberships")
    fun create(@RequestBody body: Map<String, Any?>, request: HttpServletRequest): ResponseEntity<MembershipResource> {
        val ctx = RequestContexts.require(request)
        val view = identity.admin.grant(ctx, MembershipCreateRequest.from(body).toCommand())
        return ResponseEntity.status(HttpStatus.CREATED).location(URI.create("/v1/memberships/${view.id}")).body(MembershipResource(json(view), view.revision))
    }

    @DeleteMapping("/v1/memberships/{membership_id}")
    fun revoke(@PathVariable("membership_id") id: UUID, @RequestHeader("If-Match", required = false) ifMatch: String?, request: HttpServletRequest): ResponseEntity<Void> {
        val ctx = RequestContexts.require(request)
        identity.admin.revoke(ctx, id, Etag.parseIfMatch(ifMatch))
        return ResponseEntity.noContent().build()
    }

    private fun json(v: MembershipView): Map<String, Any?> = linkedMapOf(
        "id" to v.id.toString(), "issuer" to v.issuer, "subject" to v.subject, "role_code" to v.roleCode,
        "service_scope" to v.serviceScope?.map { it.toString() }, "valid_from" to v.validFrom.toString(), "valid_to" to v.validTo?.toString(),
        "revoked_at" to v.revokedAt?.toString(), "revision" to v.revision,
    )
}

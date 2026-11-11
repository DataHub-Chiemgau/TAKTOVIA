package de.taktovia.app.identity

import com.fasterxml.jackson.annotation.JsonValue
import de.taktovia.identity.api.DriverReleaseView
import de.taktovia.identity.api.DriverReleases
import de.taktovia.identity.api.GrantDriverRelease
import de.taktovia.platform.api.Etag
import de.taktovia.platform.api.Revisioned
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import jakarta.servlet.http.HttpServletRequest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
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
import java.time.OffsetDateTime
import java.util.UUID

class DriverReleaseResource(@get:JsonValue val data: Map<String, Any?>, override val revision: Long) : Revisioned

/** Verdrahtung der Fahrerfreigabe (WP-M1-35). */
@Configuration
class DriverReleaseConfiguration {
    @Bean fun driverReleases(ctxTx: ContextTransactions, audit: AuditPort, outbox: Outbox): DriverReleases = DriverReleases.create(ctxTx, audit, outbox)
}

/**
 * Zeitlich begrenzte Fahrerfreigabe (WP-M1-35, LH-I.4-MUL-005). Erteilen und Widerrufen im Eigentümerkontext (Scope `driver-releases:write`, Rolle Personalplanung),
 * Projektion lesen im Empfängerkontext (`driver-releases:read`). Der Mandant folgt immer aus dem geprüften Kontext, nie aus Pfad oder Body; der Empfängermandant steht
 * als Ziel im Body, begründet aber keinen Zugriff.
 */
@RestController
class DriverReleaseController(private val releases: DriverReleases) {

    @PostMapping("/v1/driver-releases")
    fun create(@RequestBody body: Map<String, Any?>, request: HttpServletRequest): ResponseEntity<DriverReleaseResource> {
        val cmd = try {
            @Suppress("UNCHECKED_CAST")
            GrantDriverRelease(UUID.fromString(body["driver_id"] as String), UUID.fromString(body["recipient_tenant_id"] as String),
                (body["service_ids"] as List<String>).map(UUID::fromString), OffsetDateTime.parse(body["valid_from"] as String).toInstant(), OffsetDateTime.parse(body["valid_to"] as String).toInstant())
        } catch (e: RuntimeException) { throw ProblemException.invalid(Violation("/", ViolationCode.INVALID_FORMAT)) }
        val v = releases.grant(RequestContexts.require(request), cmd)
        return ResponseEntity.status(HttpStatus.CREATED).location(URI.create("/v1/driver-releases/${v.id}")).body(DriverReleaseResource(json(v), v.revision))
    }

    @DeleteMapping("/v1/driver-releases/{release_id}")
    fun revoke(@PathVariable("release_id") id: UUID, @RequestHeader("If-Match", required = false) ifMatch: String?, request: HttpServletRequest): ResponseEntity<Void> {
        releases.revoke(RequestContexts.require(request), id, Etag.parseIfMatch(ifMatch))
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/v1/driver-releases/{release_id}/projection")
    fun projection(@PathVariable("release_id") id: UUID, @RequestParam("service_id") serviceId: String, request: HttpServletRequest): Map<String, Any?> {
        val service = runCatching { UUID.fromString(serviceId) }.getOrNull() ?: throw ProblemException.invalid(Violation("/\$query/service_id", ViolationCode.INVALID_FORMAT))
        return releases.projection(RequestContexts.require(request), id, service).toMap()
    }

    private fun json(v: DriverReleaseView): Map<String, Any?> = linkedMapOf(
        "id" to v.id.toString(), "driver_id" to v.driverId.toString(), "recipient_tenant_id" to v.recipientTenant.toString(), "service_ids" to v.serviceIds.map { it.toString() },
        "valid_from" to v.validFrom.toString(), "valid_to" to v.validTo.toString(), "revoked_at" to v.revokedAt?.toString(), "revision" to v.revision,
    )
}

package de.taktovia.app.identity

import com.fasterxml.jackson.annotation.JsonValue
import de.taktovia.app.security.AuthenticatedPrincipal
import de.taktovia.identity.api.ContactChannel
import de.taktovia.identity.api.ContactInput
import de.taktovia.identity.api.PassengerApi
import de.taktovia.identity.api.PassengerSummary
import de.taktovia.identity.api.RegistrationCaller
import de.taktovia.platform.api.CursorCodec
import de.taktovia.platform.api.CursorPosition
import de.taktovia.platform.api.CursorScope
import de.taktovia.platform.api.Paging
import de.taktovia.platform.api.Revisioned
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.fieldprotection.FieldAccess
import de.taktovia.platform.fieldprotection.FieldAccessException
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
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

class PassengerResource(@get:JsonValue val data: Map<String, Any?>, override val revision: Long) : Revisioned

/** Anfrage-DTO „Fahrgast registrieren“; der Mandant steht nicht darin, er folgt aus dem Kontext (Token-Hinweis des Vertriebskontexts, siehe [EnrollmentPolicy]). */
class PassengerRegistrationRequest(val contact: ContactInput, val locale: String) {
    companion object {
        fun from(b: Map<String, Any?>): PassengerRegistrationRequest {
            val c = b["contact"] as Map<*, *>
            return PassengerRegistrationRequest(ContactInput(ContactChannel.valueOf(c["channel"] as String), c["value"] as String), b["locale"] as String)
        }
    }
}

/**
 * Fahrgastkonto (WP-M1-28, -29): Registrierung des geprüften Kontos, minimierte Betreiberliste, eigenes Konto (Objektbindung) und das geschützte
 * Unterstützungsmerkmal mit Feldrecht und Zweck (WP-M1-31, einziger Ausgabepfad).
 */
@RestController
class PassengerController(private val passengerApi: org.springframework.beans.factory.ObjectProvider<PassengerApi>, private val fieldAccessProvider: org.springframework.beans.factory.ObjectProvider<FieldAccess>, private val codec: CursorCodec) {
    private val passengers get() = passengerApi.getObject()
    private val fieldAccess get() = fieldAccessProvider.getObject()

    @PostMapping("/v1/passengers")
    fun register(@RequestBody body: Map<String, Any?>, request: HttpServletRequest): ResponseEntity<PassengerResource> {
        val ctx = RequestContexts.require(request)
        val p = AuthenticatedPrincipal.of(request) ?: throw ProblemException(ErrorCode.UNAUTHENTICATED)
        // Registriert wird das angemeldete Konto selbst (Anlage durch das Callcenter folgt mit M7): nur reine Fahrgastrolle bzw. noch keine Mitgliedschaft
        if (ctx.actor.type != ActorType.HUMAN || ctx.roles != setOf("passenger")) throw ProblemException(ErrorCode.FORBIDDEN)
        val req = PassengerRegistrationRequest.from(body)
        val r = passengers.accounts.register(RegistrationCaller(p.issuer, p.subject.toString(), ctx.correlationId), ctx.tenant, req.locale, req.contact)
        return ResponseEntity.status(HttpStatus.CREATED).location(URI.create("/v1/passengers/${r.passenger.id}")).body(resource(r.passenger))
    }

    @GetMapping("/v1/passengers")
    fun list(@RequestParam(required = false) limit: String?, @RequestParam(required = false) cursor: String?, request: HttpServletRequest): Map<String, Any?> {
        val ctx = RequestContexts.require(request)
        val scope = CursorScope(ctx.tenant.toString(), ctx.actor.subject, "passengers")
        val lim = Paging.limit(limit)
        val pos = Paging.position(cursor, codec, scope)
        val rows = passengers.accounts.list(ctx, lim, pos?.let { it.createdAt to it.id })
        return Paging.page(rows, lim, { CursorPosition(it.createdAt, it.id) }, codec, scope, Instant.now()).toMap { json(it) }
    }

    /** Objektbindung: nur das eigene Konto; jede andere Kennung (auch eines Fahrgasts desselben Mandanten) ist 404. */
    @GetMapping("/v1/passengers/{passenger_id}")
    fun get(@PathVariable("passenger_id") id: UUID, request: HttpServletRequest): PassengerResource {
        val own = passengers.accounts.own(RequestContexts.require(request))
        if (own == null || own.id != id) throw ProblemException.notFound()
        return resource(own)
    }

    @GetMapping("/v1/passengers/{passenger_id}/support-needs")
    fun supportNeeds(@PathVariable("passenger_id") id: UUID, @RequestHeader("X-Access-Purpose", required = false) purpose: String?, request: HttpServletRequest): Map<String, Any?> {
        val ctx = RequestContexts.require(request)
        val needs = try { fieldAccess.readSupportNeeds(ctx, id, purpose) } catch (e: FieldAccessException) {
            throw when (e.kind) {
                FieldAccessException.Kind.PURPOSE_REQUIRED -> ProblemException.invalid(Violation("/\$header/X-Access-Purpose", ViolationCode.REQUIRED))
                FieldAccessException.Kind.DENIED, FieldAccessException.Kind.NOT_FOUND -> ProblemException.notFound()   // nicht unterscheidbar
            }
        }
        return linkedMapOf("passenger_id" to id.toString(), "codes" to (needs?.codes?.sorted() ?: emptyList()))
    }

    private fun resource(p: PassengerSummary) = PassengerResource(json(p), p.revision)
    private fun json(p: PassengerSummary): Map<String, Any?> = linkedMapOf("id" to p.id.toString(), "status" to p.status, "locale" to p.locale, "created_at" to p.createdAt.toString(), "revision" to p.revision)
}

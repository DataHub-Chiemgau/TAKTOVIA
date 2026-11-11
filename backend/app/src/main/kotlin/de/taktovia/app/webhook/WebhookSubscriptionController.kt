package de.taktovia.app.webhook

import com.fasterxml.jackson.annotation.JsonValue
import de.taktovia.app.identity.RequestContexts
import de.taktovia.platform.api.CursorCodec
import de.taktovia.platform.api.CursorPosition
import de.taktovia.platform.api.CursorScope
import de.taktovia.platform.api.Etag
import de.taktovia.platform.api.Paging
import de.taktovia.platform.api.Revisioned
import de.taktovia.platform.audit.ActorRef
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.webhook.WebhookSubscription
import de.taktovia.platform.webhook.WebhookSubscriptionService
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

class WebhookSubscriptionResource(@get:JsonValue val data: Map<String, Any?>, override val revision: Long) : Revisioned

/**
 * `/v1/webhook-subscriptions` (WP-M1-16): Anlegen, Lesen, Auflisten, Deaktivieren im Mandanten des Kontexts. Rollen und Scopes (`webhooks:read`/`webhooks:write`)
 * entscheidet die zentrale Autorisierung vor dem Controller (Rechtematrix: Administrator). Ziel-URL: https mit öffentlicher Adresse, sonst 422 `TARGET_NOT_ALLOWED`.
 * Die Antwort nennt nur die Secret-Referenz, nie einen Wert. Anlegen und Deaktivieren sind auditiert (im Dienst, in derselben Transaktion).
 */
@RestController
class WebhookSubscriptionController(private val tx: ContextTransactions, private val service: WebhookSubscriptionService, private val codec: CursorCodec) {
    private fun actor(ctx: RequestContext) = ActorRef((if (ctx.actor.type == ActorType.SERVICE) "service:" else "user:") + ctx.actor.subject)

    @PostMapping("/v1/webhook-subscriptions")
    fun create(@RequestBody body: Map<String, Any?>, request: HttpServletRequest): ResponseEntity<WebhookSubscriptionResource> {
        val ctx = RequestContexts.require(request)
        @Suppress("UNCHECKED_CAST") val types = body["event_types"] as List<String>
        val s = tx.run(ctx) { j -> service.create(j, actor(ctx), ctx.correlationId, body["url"] as String, types, body["secret_ref"] as String?) }
        return ResponseEntity.status(HttpStatus.CREATED).location(URI.create("/v1/webhook-subscriptions/${s.id}")).body(WebhookSubscriptionResource(json(s), s.revision))
    }

    @GetMapping("/v1/webhook-subscriptions")
    fun list(@RequestParam(required = false) limit: String?, @RequestParam(required = false) cursor: String?, request: HttpServletRequest): Map<String, Any?> {
        val ctx = RequestContexts.require(request)
        val scope = CursorScope(ctx.tenant.toString(), ctx.actor.subject, "webhook-subscriptions")
        val lim = Paging.limit(limit); val pos = Paging.position(cursor, codec, scope)
        val rows = tx.run(ctx, readOnly = true) { j -> service.list(j, lim, pos) }
        return Paging.page(rows, lim, { CursorPosition(it.createdAt, it.id) }, codec, scope, Instant.now()).toMap { json(it) }
    }

    @GetMapping("/v1/webhook-subscriptions/{subscription_id}")
    fun get(@PathVariable("subscription_id") id: UUID, request: HttpServletRequest): WebhookSubscriptionResource {
        val ctx = RequestContexts.require(request)
        val s = tx.run(ctx, readOnly = true) { j -> service.get(j, id) } ?: throw ProblemException.notFound()
        return WebhookSubscriptionResource(json(s), s.revision)
    }

    @PostMapping("/v1/webhook-subscriptions/{subscription_id}/deactivate")
    fun deactivate(@PathVariable("subscription_id") id: UUID, @RequestHeader("If-Match", required = false) ifMatch: String?, request: HttpServletRequest): WebhookSubscriptionResource {
        val ctx = RequestContexts.require(request)
        val expected = Etag.parseIfMatch(ifMatch)
        val s = tx.run(ctx) { j -> service.deactivate(j, actor(ctx), ctx.correlationId, id, expected) }
        return WebhookSubscriptionResource(json(s), s.revision)
    }

    private fun json(s: WebhookSubscription): Map<String, Any?> = linkedMapOf("id" to s.id.toString(), "url" to s.url, "event_types" to s.eventTypes, "active" to s.active, "secret_ref" to s.secretRef, "revision" to s.revision)
}

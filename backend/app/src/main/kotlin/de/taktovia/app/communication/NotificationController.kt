package de.taktovia.app.communication

import com.fasterxml.jackson.annotation.JsonValue
import de.taktovia.app.identity.RequestContexts
import de.taktovia.communicationsupport.api.CommunicationsupportApi
import de.taktovia.communicationsupport.api.NotificationState
import de.taktovia.communicationsupport.api.NotificationView
import de.taktovia.platform.api.CursorCodec
import de.taktovia.platform.api.CursorPosition
import de.taktovia.platform.api.CursorScope
import de.taktovia.platform.api.Paging
import de.taktovia.platform.api.Revisioned
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

class NotificationResource(@get:JsonValue val data: Map<String, Any?>, override val revision: Long) : Revisioned

/**
 * Lesen von Nachrichtenaufträgen und Zustellstatus (WP-M2-32, `api/notifications.yaml`). Nur lesend und nur für berechtigte Rollen (`notifications:read` aus der
 * Rechtematrix im Filter); der Mandant folgt aus dem Kontext, ein fremder Auftrag ist ununterscheidbar von einem unbekannten (404). Weder Inhalt noch Kontaktdaten.
 */
@RestController
class NotificationController(private val communication: CommunicationsupportApi, private val codec: CursorCodec) {

    @GetMapping("/v1/notifications")
    fun list(
        @RequestParam(required = false) limit: String?, @RequestParam(required = false) cursor: String?, @RequestParam(required = false) state: String?, request: HttpServletRequest,
    ): Map<String, Any?> {
        val ctx = RequestContexts.require(request)
        val wanted = state?.let { s -> NotificationState.entries.firstOrNull { it.name == s } ?: throw ProblemException.invalid(Violation("/\$query/state", ViolationCode.NOT_IN_ENUM)) }
        // Der Cursor gilt für genau diesen Filter: wer ihn ändert, kann ihn nicht weiterverwenden.
        val scope = CursorScope(ctx.tenant.toString(), ctx.actor.subject, "notifications|${wanted ?: "-"}")
        val lim = Paging.limit(limit)
        val rows = communication.notifications.list(ctx, wanted, lim, Paging.position(cursor, codec, scope)?.let { it.createdAt to it.id })
        return Paging.page(rows, lim, { CursorPosition(it.createdAt, it.id) }, codec, scope, Instant.now()).toMap { json(it) }
    }

    @GetMapping("/v1/notifications/{notification_id}")
    fun get(@PathVariable("notification_id") id: UUID, request: HttpServletRequest): NotificationResource {
        val v = communication.notifications.get(RequestContexts.require(request), id)
        return NotificationResource(json(v), v.revision)
    }

    private fun json(v: NotificationView): Map<String, Any?> = linkedMapOf(
        "id" to v.id.toString(), "event_id" to v.eventId.toString(), "channel" to v.channel.name, "template" to v.template, "state" to v.state.name,
        "attempts" to v.attempts, "last_failure" to v.lastFailure?.name, "created_at" to v.createdAt.toString(), "revision" to v.revision,
    )
}

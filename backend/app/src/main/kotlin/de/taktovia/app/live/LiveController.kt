package de.taktovia.app.live

import de.taktovia.app.identity.RequestContexts
import de.taktovia.app.security.AuthenticatedPrincipal
import de.taktovia.identity.api.IdentityApi
import de.taktovia.identity.api.SessionRef
import de.taktovia.identity.api.SessionVerdict
import de.taktovia.identity.api.SubjectRef
import de.taktovia.platform.api.Paging
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.ContextRevokedException
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.live.LiveCursor
import de.taktovia.platform.live.LiveDecision
import de.taktovia.platform.live.LiveFeed
import de.taktovia.platform.live.LiveGrant
import de.taktovia.platform.live.LiveSettings
import de.taktovia.platform.live.LiveStream
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.stafftime.api.StafftimeApi
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Semaphore

/**
 * Live-Aktualisierungskanal für Leitstelle und Fahrer (WP-M3-35, OP-05): Server-Sent Events über dasselbe Protokoll wie der Änderungsstrom der Planrevisionen (WP-M2-28) – ein
 * Cursor, Revisionen je Aggregat, Nachladen über Abruf. Mandant und Empfänger stammen nur aus dem Kontext; die Berechtigung (Sitzung, Mitgliedschaft, Rolle, Dienste bzw. eigene
 * Schichten) wird **bei jedem Abruf der Datenbank** neu bestimmt: Sitzungssperre und Entzug beenden den Strom, entzogene Dienste liefern keine Ereignisse mehr. Quelle ist
 * ausschließlich die Projektion `live_event` (Referenzen und Statusfelder, nie Rohpayloads).
 *
 * Die Verbindung ist bewusst begrenzt ([LiveSettings.maxDuration]) und belegt einen Anfrage-Thread, begrenzt durch [LiveSettings.maxConnections] (darüber: 503 mit `Retry-After`).
 */
@RestController
class LiveController(
    private val feed: LiveFeed, private val stream: LiveStream, private val identity: IdentityApi, private val staff: StafftimeApi, private val settings: LiveSettings,
) {
    private val slots = Semaphore(settings.maxConnections)

    @GetMapping("/v1/operator/live/events")
    fun operatorStream(
        @RequestParam(required = false) after: String?, @RequestHeader("Last-Event-ID", required = false) lastEventId: String?, @RequestParam("wait_seconds", required = false) wait: String?,
        request: HttpServletRequest, response: HttpServletResponse,
    ) {
        val ctx = human(RequestContexts.require(request))
        val principal = AuthenticatedPrincipal.of(request) ?: throw ProblemException(ErrorCode.UNAUTHENTICATED)
        val session = SessionRef(principal.issuer, principal.subject.toString(), principal.sessionId ?: throw ProblemException(ErrorCode.UNAUTHENTICATED), principal.issuedAt)
        serve(ctx, cursor(after ?: lastEventId, "/\$query/after"), seconds(wait), response) { operatorGrant(ctx, session) }
    }

    @GetMapping("/v1/driver/live/events")
    fun driverStream(
        @RequestParam(required = false) after: String?, @RequestHeader("Last-Event-ID", required = false) lastEventId: String?, @RequestParam("wait_seconds", required = false) wait: String?,
        request: HttpServletRequest, response: HttpServletResponse,
    ) {
        val ctx = human(RequestContexts.require(request))
        val principal = AuthenticatedPrincipal.of(request) ?: throw ProblemException(ErrorCode.UNAUTHENTICATED)
        val session = SessionRef(principal.issuer, principal.subject.toString(), principal.sessionId ?: throw ProblemException(ErrorCode.UNAUTHENTICATED), principal.issuedAt)
        serve(ctx, cursor(after ?: lastEventId, "/\$query/after"), seconds(wait), response) { driverGrant(ctx, session) }
    }

    /** Rückfall auf Polling (OP-05): dieselbe Berechtigung und Hülle wie im Strom. */
    @GetMapping("/v1/operator/live/feed")
    fun operatorFeed(@RequestParam(required = false) after: String?, @RequestParam(required = false) limit: String?, request: HttpServletRequest): Map<String, Any?> {
        val ctx = human(RequestContexts.require(request))
        val principal = AuthenticatedPrincipal.of(request) ?: throw ProblemException(ErrorCode.UNAUTHENTICATED)
        val session = SessionRef(principal.issuer, principal.subject.toString(), principal.sessionId ?: throw ProblemException(ErrorCode.UNAUTHENTICATED), principal.issuedAt)
        val n = Paging.limit(limit); val from = cursor(after, "/\$query/after")
        val now = Instant.now()
        if (from == null) return linkedMapOf("items" to emptyList<Any>(), "next_cursor" to feed.horizon(ctx.tenant).toString(), "has_more" to false, "as_of" to now.toString())
        val grant = (operatorGrant(ctx, session) as? LiveDecision.Granted)?.grant ?: throw ProblemException(ErrorCode.FORBIDDEN)
        val page = feed.read(ctx.tenant, from, grant, n.coerceAtMost(LiveFeed.MAX_PAGE))
        return linkedMapOf("items" to page.events.map { it.envelope() }, "next_cursor" to page.nextCursor.toString(), "has_more" to page.hasMore, "as_of" to now.toString())
    }

    private fun serve(ctx: RequestContext, start: LiveCursor?, wait: Duration, response: HttpServletResponse, authorize: () -> LiveDecision) {
        if (!slots.tryAcquire()) throw ProblemException(ErrorCode.SERVICE_UNAVAILABLE, "Zu viele offene Live-Verbindungen.", headers = mapOf("Retry-After" to "5"))
        try {
            // Beginn nur mit gültiger Berechtigung: sonst normale Fehlerantwort statt eines Stroms mit sofortigem Ende
            when (val d = authorize()) {
                is LiveDecision.Ended -> throw ProblemException(if (d.reason == "session_revoked") ErrorCode.UNAUTHENTICATED else ErrorCode.FORBIDDEN)
                is LiveDecision.Granted -> Unit
            }
            response.status = 200
            response.contentType = "text/event-stream;charset=UTF-8"
            response.setHeader("Cache-Control", "no-store")
            response.setHeader("X-Accel-Buffering", "no")
            val out = response.outputStream
            try {
                stream.run(ctx.tenant, start, wait, authorize) { frame -> out.write(frame.toByteArray(Charsets.UTF_8)); out.flush() }
            } catch (e: IOException) {
                // Client ist gegangen: kein Fehler, die Verbindung endet
            }
        } finally { slots.release() }
    }

    private fun operatorGrant(ctx: RequestContext, session: SessionRef): LiveDecision {
        sessionEnded(ctx, session)?.let { return it }
        val m = identity.lookup.activeIn(SubjectRef(ctx.actor.issuer, ctx.actor.subject), ctx.tenant) ?: return LiveDecision.Ended("membership_revoked")
        if ("operator:read" !in identity.scopePolicy.scopesFor(m.roles)) return LiveDecision.Ended("membership_revoked")
        // eine bei der Anfrage gewählte Dienstbegrenzung bleibt bestehen; nur ihr Schnitt mit dem heutigen Dienstscope gilt
        return LiveDecision.Granted(LiveGrant(services = ctx.services intersect (m.serviceScope ?: emptySet())))
    }

    private fun driverGrant(ctx: RequestContext, session: SessionRef): LiveDecision {
        sessionEnded(ctx, session)?.let { return it }
        val m = identity.lookup.activeIn(SubjectRef(ctx.actor.issuer, ctx.actor.subject), ctx.tenant) ?: return LiveDecision.Ended("membership_revoked")
        if ("driver" !in m.roles) return LiveDecision.Ended("membership_revoked")
        val shifts = try { staff.driverShifts.liveShiftIds(ctx) } catch (e: ContextRevokedException) { return LiveDecision.Ended("membership_revoked") }
        return LiveDecision.Granted(LiveGrant(serviceScoped = false, eventTypes = setOf("dispatch.run_revised"), shiftIds = shifts))
    }

    private fun sessionEnded(ctx: RequestContext, session: SessionRef): LiveDecision.Ended? = when (identity.sessionGate.check(ctx.tenant, session)) {
        SessionVerdict.ACTIVE -> null
        else -> LiveDecision.Ended("session_revoked")
    }

    private fun human(ctx: RequestContext): RequestContext = if (ctx.actor.type == ActorType.HUMAN) ctx else throw ProblemException(ErrorCode.FORBIDDEN)

    private fun cursor(v: String?, pointer: String): LiveCursor? = if (v == null) null else LiveCursor.parse(v) ?: throw ProblemException.invalid(Violation(pointer, ViolationCode.INVALID_FORMAT))

    private fun seconds(v: String?): Duration {
        if (v == null) return Duration.ofSeconds(DEFAULT_WAIT)
        val n = v.toLongOrNull()?.takeIf { it in 0..MAX_WAIT } ?: throw ProblemException.invalid(Violation("/\$query/wait_seconds", ViolationCode.INVALID_FORMAT))
        return Duration.ofSeconds(n)
    }

    private companion object { const val DEFAULT_WAIT = 25L; const val MAX_WAIT = 55L }
}

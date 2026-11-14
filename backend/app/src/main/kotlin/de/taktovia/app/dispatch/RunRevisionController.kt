package de.taktovia.app.dispatch

import com.fasterxml.jackson.annotation.JsonValue
import de.taktovia.app.identity.RequestContexts
import de.taktovia.bookingdispatch.api.PlanCommit
import de.taktovia.bookingdispatch.api.ReplanCommand
import de.taktovia.bookingdispatch.api.ReplanException
import de.taktovia.bookingdispatch.api.ReplanRule
import de.taktovia.bookingdispatch.api.RunAccess
import de.taktovia.bookingdispatch.api.RunRevisionRecord
import de.taktovia.bookingdispatch.api.StopChange
import de.taktovia.bookingdispatch.api.StopChangeKind
import de.taktovia.config.api.ConfigApi
import de.taktovia.fleetsafety.api.FleetsafetyApi
import de.taktovia.platform.api.CursorCodec
import de.taktovia.platform.api.CursorPosition
import de.taktovia.platform.api.CursorScope
import de.taktovia.platform.api.Etag
import de.taktovia.platform.api.Paging
import de.taktovia.platform.api.Revisioned
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import jakarta.servlet.http.HttpServletRequest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
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

/** Antwort mit Planrevision (der Antwortfilter setzt daraus den ETag). */
class RunRevisionResource(@get:JsonValue val data: Map<String, Any?>, override val revision: Long) : Revisioned

@Configuration
class DispatchConfiguration {
    /** Manuelle Umplanung (WP-M3-40): Plan-CAS, Begründung, Sicherheits- und Zusageprüfung im Commit. */
    @Bean fun planCommit(ctxTx: ContextTransactions, outbox: Outbox, audit: AuditPort, config: ConfigApi, staffing: de.taktovia.platform.readports.ShiftStaffingReadPort): PlanCommit =
        PlanCommit.create(ctxTx, outbox, audit, config.effective, staffing = staffing)
}

/**
 * `POST /v1/runs/{run_id}/revisions` und `GET /v1/runs/{run_id}/revisions` (WP-M3-40, LH-6.7-OPS-003-Enabler, Kap. 06 §6.5). Die Dienstberechtigung entscheidet die Leitstellenfahrzeugsicht: ein Umlauf, dessen Fahrzeug
 * für den Aufrufer nicht sichtbar ist (fremder Dienst, fremder Mandant), ist 404; das gilt auch für das Quellfahrzeug einer Umbuchung. `If-Match` (428/412) und `Idempotency-Key` sind Pflicht; die Antwort trägt
 * die neue Planrevision als `ETag`. Mit `compare_only` liefert der Aufruf nur den Planvergleich (200, `committed=false`).
 */
@RestController
class RunRevisionController(private val commit: PlanCommit, private val fleet: FleetsafetyApi, private val codec: CursorCodec) {

    private fun access(request: HttpServletRequest): RunAccess = RunAccess { ctx, vehicleId -> fleet.operatorVehicles.get(ctx, vehicleId) }   // nicht sichtbar = 404

    @PostMapping("/v1/runs/{run_id}/revisions")
    @Suppress("UNCHECKED_CAST")
    fun create(
        @PathVariable("run_id") runId: String, @RequestBody body: Map<String, Any?>, @RequestHeader("If-Match", required = false) ifMatch: String?, @RequestHeader("Idempotency-Key") key: String, request: HttpServletRequest,
    ): ResponseEntity<RunRevisionResource> {
        val ctx = RequestContexts.require(request)
        val id = runCatching { UUID.fromString(runId) }.getOrNull() ?: throw ProblemException.notFound()
        val expected = Etag.parseIfMatch(ifMatch)
        val cmd = parse(body)
        // base_plan_revision ist die gelesene Planrevision; sie muss mit If-Match übereinstimmen (ein Client, der beides sendet, meint dieselbe Revision)
        if (cmd.baseRevision != expected) throw ProblemException.invalid(Violation("/base_plan_revision", ViolationCode.INVALID_VALUE, "base_plan_revision und If-Match nennen verschiedene Planrevisionen."))
        val r = commit.replan(ctx, id, cmd, access(request))
        val resource = RunRevisionResource(render(r), r.planRevision)
        return if (r.committed) ResponseEntity.status(HttpStatus.CREATED).location(URI.create("/v1/runs/$id/revisions?limit=1")).body(resource)
        else ResponseEntity.ok(resource)
    }

    @GetMapping("/v1/runs/{run_id}/revisions")
    fun list(@PathVariable("run_id") runId: String, @RequestParam(required = false) limit: String?, @RequestParam(required = false) cursor: String?, request: HttpServletRequest): Map<String, Any?> {
        val ctx = RequestContexts.require(request)
        val id = runCatching { UUID.fromString(runId) }.getOrNull() ?: throw ProblemException.notFound()
        val scope = CursorScope(ctx.tenant.toString(), ctx.actor.subject, "run-revisions:$id")
        val lim = Paging.limit(limit)
        val before = Paging.position(cursor, codec, scope)?.id?.leastSignificantBits
        val rows = commit.revisions(ctx, id, lim + 1, before, access(request))
        val page = Paging.page(rows, lim, { CursorPosition(it.createdAt, UUID(0, it.planRevision)) }, codec, scope, Instant.now())
        return linkedMapOf("items" to page.items.map { render(it) }, "next_cursor" to page.nextCursor, "as_of" to page.asOf.toString())
    }

    // ---- Abbildung ------------------------------------------------------------------------------------------------------------------

    @Suppress("UNCHECKED_CAST")
    private fun parse(body: Map<String, Any?>): ReplanCommand {
        fun bad(pointer: String, code: ViolationCode = ViolationCode.TYPE_MISMATCH): Nothing = throw ProblemException.invalid(Violation(pointer, code))
        val reason = body["reason"] as? String ?: bad("/reason", if (body.containsKey("reason")) ViolationCode.TYPE_MISMATCH else ViolationCode.REQUIRED)
        val base = (body["base_plan_revision"] as? Number)?.toLong() ?: bad("/base_plan_revision", if (body.containsKey("base_plan_revision")) ViolationCode.TYPE_MISMATCH else ViolationCode.REQUIRED)
        val raw = body["changes"] as? List<Any?> ?: bad("/changes", if (body.containsKey("changes")) ViolationCode.TYPE_MISMATCH else ViolationCode.REQUIRED)
        val changes = raw.mapIndexed { i, any ->
            val m = any as? Map<String, Any?> ?: bad("/changes/$i")
            val kind = StopChangeKind.entries.firstOrNull { it.name == m["change"] } ?: bad("/changes/$i/change", ViolationCode.NOT_IN_ENUM)
            val stop = (m["route_stop_id"] as? String)?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: bad("/changes/$i/route_stop_id", ViolationCode.INVALID_FORMAT)
            val seq = (m["new_sequence"] as? Number)?.toInt()
            val booking = (m["booking_id"] as? String)?.let { runCatching { UUID.fromString(it) }.getOrNull() ?: bad("/changes/$i/booking_id", ViolationCode.INVALID_FORMAT) }
            StopChange(kind, stop, seq, booking)
        }
        val exception = (body["exception"] as? Map<String, Any?>)?.let { e ->
            val rules = (e["rules"] as? List<Any?> ?: bad("/exception/rules")).mapIndexed { i, r -> ReplanRule.entries.firstOrNull { it.name == r } ?: bad("/exception/rules/$i", ViolationCode.NOT_IN_ENUM) }.toSet()
            ReplanException(rules, e["justification"] as? String ?: bad("/exception/justification", ViolationCode.REQUIRED))
        }
        return ReplanCommand(changes, reason, base, body["compare_only"] == true, exception)
    }

    private fun render(r: RunRevisionRecord): Map<String, Any?> = linkedMapOf(
        "run_id" to r.runId.toString(), "plan_revision" to r.planRevision, "previous_revision" to r.previousRevision, "cause" to r.cause.name, "reason" to r.reason,
        "created_at" to r.createdAt.toString(), "created_by" to r.createdBy,
        "diff" to r.diff.map { d -> linkedMapOf("route_stop_id" to d.routeStopId.toString(), "kind" to d.kind.name, "old_sequence" to d.oldSequence, "new_sequence" to d.newSequence) },
        "committed" to r.committed,
        "impacts" to r.impacts.map { i ->
            linkedMapOf("booking_id" to i.bookingId.toString(), "kind" to i.kind.name, "pickup_before" to i.pickupBefore?.toString(), "pickup_after" to i.pickupAfter?.toString(),
                "dropoff_before" to i.dropoffBefore?.toString(), "dropoff_after" to i.dropoffAfter?.toString(), "window_violations" to i.windowViolations.map { it.name })
        },
        "exception" to r.exception?.let { e -> linkedMapOf("rules" to e.rules.map { it.name }.sorted(), "justification" to e.justification) },
    )
}

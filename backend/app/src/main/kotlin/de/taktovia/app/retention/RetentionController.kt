package de.taktovia.app.retention

import com.fasterxml.jackson.annotation.JsonValue
import de.taktovia.app.identity.RequestContexts
import de.taktovia.platform.api.CursorCodec
import de.taktovia.platform.api.CursorPosition
import de.taktovia.platform.api.CursorScope
import de.taktovia.platform.api.Etag
import de.taktovia.platform.api.Paging
import de.taktovia.platform.api.Revisioned
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.platform.retention.ErasureJobView
import de.taktovia.platform.retention.JournalEntryView
import de.taktovia.platform.retention.RetentionAction
import de.taktovia.platform.retention.RetentionRuleView
import de.taktovia.platform.retention.RetentionRules
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
import java.util.UUID

class RetentionRuleResource(@get:JsonValue val data: Map<String, Any?>, override val revision: Long) : Revisioned
class ErasureJobResource(@get:JsonValue val data: Map<String, Any?>, override val revision: Long) : Revisioned

/**
 * Aufbewahrungsregeln und Löschläufe (WP-M1-33, UI-20 Basis). Rechte (`retention:read`, `retention:write`) aus der Rechtematrix im Filter; der Mandant folgt aus dem
 * Kontext. Fachlogik (Vier-Augen, Freigabe, Lauf) in [RetentionRules] (Plattform); der Lauf selbst läuft über die operation `RETENTION_RUN`.
 */
@RestController
class RetentionController(private val rules: RetentionRules, private val codec: CursorCodec) {

    @GetMapping("/v1/retention-rules")
    fun list(@RequestParam(required = false) limit: String?, @RequestParam(required = false) cursor: String?, request: HttpServletRequest): Map<String, Any?> {
        val ctx = RequestContexts.require(request)
        val scope = CursorScope(ctx.tenant.toString(), ctx.actor.subject, "retention-rules")
        val lim = Paging.limit(limit)
        val rows = rules.list(ctx, lim, Paging.position(cursor, codec, scope)?.let { it.createdAt to it.id })
        return Paging.page(rows, lim, { CursorPosition(it.createdAt, it.id) }, codec, scope, Instant.now()).toMap { json(it) }
    }

    @PostMapping("/v1/retention-rules")
    fun create(@RequestBody body: Map<String, Any?>, request: HttpServletRequest): ResponseEntity<RetentionRuleResource> {
        val v = rules.create(RequestContexts.require(request), body["data_category"] as String, intOf(body["retention_days"]), action(body["action"]), (body["archive_days"] as Number?)?.toInt())
        return ResponseEntity.status(HttpStatus.CREATED).location(URI.create("/v1/retention-rules/${v.id}")).body(resource(v))
    }

    @PutMapping("/v1/retention-rules/{rule_id}")
    fun update(@PathVariable("rule_id") ruleId: UUID, @RequestHeader("If-Match", required = false) ifMatch: String?, @RequestBody body: Map<String, Any?>, request: HttpServletRequest): RetentionRuleResource =
        resource(rules.update(RequestContexts.require(request), ruleId, Etag.parseIfMatch(ifMatch), intOf(body["retention_days"]), action(body["action"]), (body["archive_days"] as Number?)?.toInt()))

    @PostMapping("/v1/retention-rules/{rule_id}/approvals")
    fun approve(@PathVariable("rule_id") ruleId: UUID, @RequestHeader("If-Match", required = false) ifMatch: String?, request: HttpServletRequest): RetentionRuleResource =
        resource(rules.approve(RequestContexts.require(request), ruleId, Etag.parseIfMatch(ifMatch)))

    @GetMapping("/v1/erasure-jobs")
    fun jobs(@RequestParam(required = false) limit: String?, @RequestParam(required = false) cursor: String?, request: HttpServletRequest): Map<String, Any?> {
        val ctx = RequestContexts.require(request)
        val scope = CursorScope(ctx.tenant.toString(), ctx.actor.subject, "erasure-jobs")
        val lim = Paging.limit(limit)
        val rows = rules.jobs(ctx, lim, Paging.position(cursor, codec, scope)?.let { it.createdAt to it.id })
        return Paging.page(rows, lim, { CursorPosition(it.createdAt, it.id) }, codec, scope, Instant.now()).toMap { jobJson(it) }
    }

    @PostMapping("/v1/erasure-jobs")
    fun start(@RequestBody body: Map<String, Any?>, request: HttpServletRequest): ResponseEntity<ErasureJobResource> {
        val v = rules.requestRun(RequestContexts.require(request), UUID.fromString(body["rule_id"] as String))
        return ResponseEntity.status(HttpStatus.CREATED).location(URI.create("/v1/erasure-jobs")).body(ErasureJobResource(jobJson(v), v.revision))
    }

    @GetMapping("/v1/erasure-journal")
    fun journal(
        @RequestParam(required = false) limit: String?, @RequestParam(required = false) cursor: String?, @RequestParam(name = "job_id", required = false) jobId: String?, request: HttpServletRequest,
    ): Map<String, Any?> {
        val ctx = RequestContexts.require(request)
        val job = jobId?.let { runCatching { UUID.fromString(it) }.getOrNull() ?: throw ProblemException.invalid(Violation("/\$query/job_id", ViolationCode.INVALID_FORMAT)) }
        val scope = CursorScope(ctx.tenant.toString(), ctx.actor.subject, "erasure-journal|${job ?: "-"}")
        val lim = Paging.limit(limit)
        val rows = rules.journal(ctx, job, lim, Paging.position(cursor, codec, scope)?.let { it.createdAt to it.id })
        return Paging.page(rows, lim, { CursorPosition(it.appliedAt, it.id) }, codec, scope, Instant.now()).toMap { journalJson(it) }
    }

    private fun action(v: Any?) = RetentionAction.valueOf(v as String)
    private fun intOf(v: Any?) = (v as Number).toInt()

    private fun resource(v: RetentionRuleView) = RetentionRuleResource(json(v), v.revision)
    private fun json(v: RetentionRuleView): Map<String, Any?> = linkedMapOf(
        "id" to v.id.toString(), "data_category" to v.dataClass, "retention_days" to v.retentionDays, "action" to v.action.name, "archive_days" to v.archiveDays,
        "status" to v.status.name, "revision" to v.revision,
    )

    private fun jobJson(v: ErasureJobView): Map<String, Any?> = linkedMapOf(
        "id" to v.id.toString(), "rule_id" to v.ruleId.toString(), "data_category" to v.dataClass, "action" to v.action.name, "status" to v.status.name,
        "processed_count" to v.processedCount, "skipped_count" to v.skippedCount, "result_code" to v.resultCode, "operation_id" to v.operationId?.toString(),
        "created_at" to v.createdAt.toString(), "finished_at" to v.finishedAt?.toString(), "revision" to v.revision,
    )

    private fun journalJson(v: JournalEntryView): Map<String, Any?> = linkedMapOf(
        "id" to v.id.toString(), "job_id" to v.jobId.toString(), "data_category" to v.dataClass, "object_ref" to v.objectRef, "action" to v.action.name, "applied_at" to v.appliedAt.toString(),
    )
}

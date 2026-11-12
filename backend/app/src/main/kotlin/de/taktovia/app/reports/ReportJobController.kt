package de.taktovia.app.reports

import de.taktovia.analytics.api.AnalyticsApi
import de.taktovia.analytics.api.CreateExport
import de.taktovia.analytics.api.ExportJobView
import de.taktovia.analytics.api.ExportKind
import de.taktovia.analytics.api.ExportScope
import de.taktovia.app.identity.RequestContexts
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Exportaufträge (WP-M5-19, Kap. 05 §5.7 `/v1/reports/jobs`, Modul `analytics`). Rechte (`reports:write|read`) aus der Rechtematrix im Filter, das Fachrecht je Art im Dienst. Der Mandant steht nie im Body
 * (unbekannte Felder 422). Anlegen: 202 (neu) oder 200 (Wiederholung desselben Schlüssels und Umfangs). Lesen: Status und Nachweise; mit `download_token` die Exportdatei (`no-store`).
 */
@RestController
class ReportJobController(private val analytics: AnalyticsApi) {

    @PostMapping("/v1/reports/jobs")
    fun create(@RequestBody body: Map<String, Any?>, @RequestHeader("Idempotency-Key") key: String, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        val ctx = RequestContexts.require(request)
        val violations = body.keys.filter { it != "kind" && it != "scope" }.map { Violation("/$it", ViolationCode.UNKNOWN_FIELD) }.toMutableList()
        val kind = ExportKind.parse(body["kind"] as? String)
        if (kind == null) violations += Violation("/kind", if (body["kind"] == null) ViolationCode.REQUIRED else ViolationCode.NOT_IN_ENUM)
        val (scope, scopeViolations) = if (kind != null) ExportScope.parse(kind, body["scope"]) else (null to emptyList())
        violations += scopeViolations
        if (violations.isNotEmpty() || kind == null || scope == null) throw ProblemException.invalid(*violations.toTypedArray())
        val created = analytics.reportJobs.create(ctx, CreateExport(kind, scope), key)
        return ResponseEntity.status(if (created.replayed) HttpStatus.OK else HttpStatus.ACCEPTED).body(json(created.job))
    }

    @GetMapping("/v1/reports/jobs/{jobId}")
    fun get(@PathVariable jobId: String, @RequestParam(name = "download_token", required = false) token: String?, request: HttpServletRequest): ResponseEntity<*> {
        val ctx = RequestContexts.require(request)
        val id = runCatching { UUID.fromString(jobId) }.getOrNull() ?: throw ProblemException.invalid(Violation("/\$path/job_id", ViolationCode.INVALID_FORMAT))
        if (token == null) return ResponseEntity.ok(json(analytics.reportJobs.get(ctx, id)))
        val file = analytics.reportJobs.download(ctx, id, token)
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.taktovia.export+json"))
            .header("Content-Disposition", "attachment; filename=\"export-$id.json\"").header("Cache-Control", "no-store").header("X-Export-SHA256", file.sha256).body(file.content)
    }

    private fun json(j: ExportJobView): Map<String, Any?> = linkedMapOf(
        "job_id" to j.id.toString(), "kind" to j.kind.code, "scope" to j.scope.toMap(), "state" to j.state.name, "as_of" to j.asOf.toString(), "created_at" to j.createdAt.toString(),
        "finished_at" to j.finishedAt?.toString(), "categories" to j.categories?.map { linkedMapOf("code" to it.code, "status" to it.status, "rows" to it.rows) }, "result_sha256" to j.resultSha256,
        "result_bytes" to j.resultBytes, "failure_code" to j.failureCode, "download_url" to j.downloadToken?.let { "/v1/reports/jobs/${j.id}?download_token=$it" }, "download_expires_at" to j.downloadExpiresAt?.toString(),
    )
}

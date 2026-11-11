package de.taktovia.app.config

import com.fasterxml.jackson.annotation.JsonValue
import de.taktovia.app.identity.RequestContexts
import de.taktovia.config.api.ConfigApi
import de.taktovia.config.api.ConfigRevisionView
import de.taktovia.config.api.CreateDraft
import de.taktovia.config.api.EffectiveConfigView
import de.taktovia.config.api.EffectiveParameter
import de.taktovia.config.api.EffectiveRequest
import de.taktovia.config.api.PublishCommand
import de.taktovia.config.api.RollbackCommand
import de.taktovia.config.api.EffectiveStatus
import de.taktovia.config.api.ImpactReportView
import de.taktovia.config.api.ParameterDefinition
import de.taktovia.config.api.RevisionFilter
import de.taktovia.config.api.RevisionState
import de.taktovia.config.api.ScopeKind
import de.taktovia.config.api.UpdateDraft
import de.taktovia.platform.api.CursorCodec
import de.taktovia.platform.api.CursorPosition
import de.taktovia.platform.api.CursorScope
import de.taktovia.platform.api.Etag
import de.taktovia.platform.api.Paging
import de.taktovia.platform.api.Revisioned
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.UUID

/** Revisionsressource; der starke ETag ist die Zeilenversion des Entwurfs (`If-Match` beim Ändern), nicht die Revisionsnummer des Scopes. */
class ConfigRevisionResource(@get:JsonValue val data: Map<String, Any?>, override val revision: Long) : Revisioned

/**
 * Konfigurationsrevisionen und Parameterkatalog (WP-M1-19, WP-M1-20; UI-17 Basis). Der Mandant folgt ausschließlich aus dem geprüften Kontext;
 * Anfrage-Bodies enthalten weder `tenant_id` noch `created_by` (der Ersteller ist der Principal). Der Katalog hat nur eine lesende Route.
 */
@RestController
@RequestMapping("/v1/config")
class ConfigController(private val config: ConfigApi, private val codec: CursorCodec) {

    @GetMapping("/parameters")
    fun parameters(request: HttpServletRequest): Map<String, Any?> {
        val catalog = config.parameters.catalog(RequestContexts.require(request))
        return linkedMapOf("schema_version" to catalog.schemaVersion, "valid_from" to catalog.validFrom.toString(), "items" to catalog.parameters.map(::parameterJson))
    }

    /** Wirksame Konfiguration nach Präzedenzkette (WP-M1-21): der Kontext aus Region, Dienst und Fahrzeug, der Zeitpunkt `at`; der Mandant folgt aus dem Kontext. */
    @GetMapping("/effective")
    fun effective(
        @RequestParam(required = false) parameter: String?, @RequestParam(name = "region_id", required = false) regionId: String?,
        @RequestParam(name = "service_id", required = false) serviceId: String?, @RequestParam(name = "vehicle_id", required = false) vehicleId: String?,
        @RequestParam(required = false) at: String?, request: HttpServletRequest,
    ): Map<String, Any?> {
        val view = config.effective.resolve(RequestContexts.require(request), EffectiveRequest(
            parameter = parameter, regionId = regionId?.let { uuidOf(it, "region_id") }, serviceId = serviceId?.let { uuidOf(it, "service_id") },
            vehicleId = vehicleId?.let { uuidOf(it, "vehicle_id") }, at = at?.let { instantOf(it, "at") }))
        return effectiveJson(view)
    }

    private fun effectiveJson(v: EffectiveConfigView): Map<String, Any?> {
        val withValue = v.items.filter { it.status == EffectiveStatus.EFFECTIVE }
        return linkedMapOf(
            "as_of" to v.asOf.toString(), "values" to withValue.associate { it.parameter to it.value }, "revision_ids" to withValue.mapNotNull { it.revisionId?.toString() }.distinct(),
            "schema_version" to v.schemaVersion, "items" to v.items.map(::effectiveParameterJson),
        )
    }

    private fun effectiveParameterJson(p: EffectiveParameter): Map<String, Any?> = linkedMapOf(
        "parameter" to p.parameter, "unit" to p.unit, "status" to p.status.name, "value" to p.value, "level" to p.level?.name, "revision_id" to p.revisionId?.toString(), "revision" to p.revision,
        "scope_id" to p.scopeId?.toString(), "effective_from" to p.effectiveFrom?.toString(), "effective_to" to p.effectiveTo?.toString(), "schema_version" to p.schemaVersion,
        "rule" to linkedMapOf("code" to p.rule.code, "description" to p.rule.description),
        "precedence" to p.precedence.map { e ->
            linkedMapOf(
                "level" to e.level.name, "outcome" to e.outcome.name, "scope_id" to e.scopeId?.toString(), "value" to e.value, "revision_id" to e.revisionId?.toString(), "revision" to e.revision,
                "violation" to e.violation, "proposal" to e.proposal?.let { linkedMapOf("revision_id" to it.revisionId.toString(), "state" to it.state.name, "value" to it.value) },
            )
        },
    )

    @GetMapping("/revisions")
    fun list(
        @RequestParam(required = false) limit: String?, @RequestParam(required = false) cursor: String?,
        @RequestParam(name = "scope_kind", required = false) scopeKind: String?, @RequestParam(name = "scope_id", required = false) scopeId: String?,
        @RequestParam(required = false) state: String?, @RequestParam(name = "as_of", required = false) asOf: String?, request: HttpServletRequest,
    ): Map<String, Any?> {
        val ctx = RequestContexts.require(request)
        val kind = scopeKind?.let { enumOf<ScopeKind>(it, "scope_kind") }
        val id = scopeId?.let { uuidOf(it, "scope_id") }
        val superseded = state == "SUPERSEDED"
        val st = state?.takeIf { !superseded }?.let { enumOf<RevisionState>(it, "state") }
        if (asOf != null) {
            // Rekonstruktion: die zum Zeitpunkt wirksame publizierte Revision des Scopes (höchstens ein Eintrag)
            val at = instantOf(asOf, "as_of")
            val missing = listOfNotNull(if (kind == null) Violation("/\$query/scope_kind", ViolationCode.REQUIRED) else null, if (id == null) Violation("/\$query/scope_id", ViolationCode.REQUIRED) else null)
            if (missing.isNotEmpty()) throw ProblemException.invalid(*missing.toTypedArray())
            val found = config.revisions.asOf(ctx, kind!!, id!!, at)
            return linkedMapOf("items" to listOfNotNull(found?.let(::revisionJson)), "next_cursor" to null, "as_of" to at.toString())
        }
        val scope = CursorScope(ctx.tenant.toString(), ctx.actor.subject, "config-revisions:$kind:$id:$st:$superseded")
        val lim = Paging.limit(limit)
        val pos = Paging.position(cursor, codec, scope)
        val rows = config.revisions.list(ctx, RevisionFilter(kind, id, st, superseded), lim, pos?.let { it.createdAt to it.id })
        return Paging.page(rows, lim, { CursorPosition(it.createdAt, it.id) }, codec, scope, Instant.now()).toMap { revisionJson(it) }
    }

    @PostMapping("/revisions")
    fun create(@RequestBody body: Map<String, Any?>, request: HttpServletRequest): ResponseEntity<ConfigRevisionResource> {
        val ctx = RequestContexts.require(request)
        val view = config.revisions.createDraft(ctx, CreateDraft(
            scopeKind = enumOf<ScopeKind>(body["scope_kind"] as String, "scope_kind"), scopeId = UUID.fromString(body["scope_id"] as String),
            schemaVersion = body["schema_version"] as String, values = objectOf(body["values"]), effectiveFrom = OffsetDateTime.parse(body["effective_from"] as String).toInstant(),
            effectiveTo = (body["effective_to"] as String?)?.let { OffsetDateTime.parse(it).toInstant() }, reason = body["reason"] as String?,
        ))
        return ResponseEntity.status(HttpStatus.CREATED).location(URI.create("/v1/config/revisions/${view.id}")).body(resource(view))
    }

    @GetMapping("/revisions/{revision_id}")
    fun get(@PathVariable("revision_id") id: UUID, request: HttpServletRequest): ConfigRevisionResource =
        resource(config.revisions.get(RequestContexts.require(request), id))

    @PatchMapping("/revisions/{revision_id}")
    fun update(
        @PathVariable("revision_id") id: UUID, @RequestHeader("If-Match", required = false) ifMatch: String?, @RequestBody body: Map<String, Any?>, request: HttpServletRequest,
    ): ConfigRevisionResource {
        val ctx = RequestContexts.require(request)
        val expected = Etag.parseIfMatch(ifMatch)
        val cmd = UpdateDraft(
            values = if (body.containsKey("values")) objectOf(body["values"]) else null,
            effectiveFrom = (body["effective_from"] as String?)?.let { OffsetDateTime.parse(it).toInstant() },
            effectiveTo = (body["effective_to"] as String?)?.let { OffsetDateTime.parse(it).toInstant() }, effectiveToSet = body.containsKey("effective_to"),
            reason = body["reason"] as String?,
        )
        return resource(config.revisions.updateDraft(ctx, id, expected, cmd))
    }

    /** Validierung (WP-M1-22): ein Entwurf wird gegen Schema-Version und Modellregeln geprüft; gültig = VALIDATED (200), ungültig = Entwurf mit Feldverletzungen (200, valid=false). */
    @PostMapping("/revisions/{revision_id}/validate")
    fun validate(@PathVariable("revision_id") id: UUID, @RequestHeader("If-Match", required = false) ifMatch: String?, request: HttpServletRequest): ConfigRevisionResource {
        val r = config.publication.validate(RequestContexts.require(request), id, Etag.parseIfMatch(ifMatch))
        return ConfigRevisionResource(linkedMapOf(
            "revision_id" to id.toString(), "valid" to r.valid, "state" to stateOf(r.revision), "row_version" to r.revision.rowVersion, "audit_event_id" to r.auditEventId.toString(),
            "violations" to r.violations.map { linkedMapOf("pointer" to it.pointer, "code" to it.code.name, "message" to it.message) },
        ), r.revision.rowVersion)
    }

    /** Vier-Augen-Freigabe (WP-M1-22): Prüfer ≠ Ersteller. */
    @PostMapping("/revisions/{revision_id}/approve")
    fun approve(@PathVariable("revision_id") id: UUID, @RequestHeader("If-Match", required = false) ifMatch: String?, request: HttpServletRequest): ConfigRevisionResource =
        resource(config.publication.approve(RequestContexts.require(request), id, Etag.parseIfMatch(ifMatch)))

    /** Publikation (`publishConfig`, WP-M1-22): `Idempotency-Key` und `If-Match` verlangt die Filterkette (428/412), Wiederholung liefert dasselbe Ergebnis. */
    @PostMapping("/revisions/{revision_id}/publish")
    fun publish(
        @PathVariable("revision_id") id: UUID, @RequestHeader("If-Match", required = false) ifMatch: String?, @RequestBody body: Map<String, Any?>, request: HttpServletRequest,
    ): ConfigRevisionResource {
        val ctx = RequestContexts.require(request)
        val expected = Etag.parseIfMatch(ifMatch)
        val r = config.publication.publish(ctx, id, expected, PublishCommand(
            effectiveFrom = instantBody(body["effective_from"], "effective_from"), reason = body["reason"] as String?,
            impactReportId = (body["impact_report_id"] as String?)?.let { uuidBody(it, "impact_report_id") }))
        return ConfigRevisionResource(linkedMapOf(
            "resource_id" to r.revision.id.toString(), "revision" to r.revision.rowVersion, "state" to "PUBLISHED", "effective_from" to r.revision.effectiveFrom.toString(),
            "audit_event_id" to r.auditEventId.toString(),
        ), r.revision.rowVersion)
    }

    /** Rücknahme einer fehlerhaften publizierten Revision als neue Revision (WP-M1-22); `If-Match` = Zeilenversion der fehlerhaften Revision. */
    @PostMapping("/revisions/{revision_id}/rollback")
    fun rollback(
        @PathVariable("revision_id") id: UUID, @RequestHeader("If-Match", required = false) ifMatch: String?, @RequestBody body: Map<String, Any?>, request: HttpServletRequest,
    ): ResponseEntity<ConfigRevisionResource> {
        val ctx = RequestContexts.require(request)
        val created = config.publication.rollback(ctx, id, Etag.parseIfMatch(ifMatch), RollbackCommand(
            effectiveFrom = instantBody(body["effective_from"], "effective_from"), reason = body["reason"] as String?,
            targetRevisionId = (body["target_revision_id"] as String?)?.let { uuidBody(it, "target_revision_id") }))
        return ResponseEntity.status(HttpStatus.CREATED).location(URI.create("/v1/config/revisions/${created.id}")).body(resource(created))
    }

    /** Wirkungsanalyse für den Revisionsstand im `If-Match` (WP-M1-23); erfordert `config:write`, die Matrix prüft die Operation `requestConfigImpact`. */
    @PostMapping("/revisions/{revision_id}/impact")
    fun requestImpact(@PathVariable("revision_id") id: UUID, @RequestHeader("If-Match", required = false) ifMatch: String?, request: HttpServletRequest): Map<String, Any?> =
        impactJson(config.impact.analyze(RequestContexts.require(request), id, Etag.parseIfMatch(ifMatch)))

    /** Gespeicherten Wirkungsbericht über seine ID lesen (WP-M1-23). */
    @GetMapping("/impact-reports/{impact_report_id}")
    fun getImpactReport(@PathVariable("impact_report_id") id: UUID, request: HttpServletRequest): Map<String, Any?> =
        impactJson(config.impact.get(RequestContexts.require(request), id))

    // ---- Darstellung ------------------------------------------------------------------------------------------------------------------------

    /** „Abgelöst“ wird nicht gespeichert, sondern abgeleitet: publiziert und das Intervall ist durch einen Nachfolger geschlossen und beendet. */
    private fun stateOf(v: ConfigRevisionView): String =
        if (v.state == RevisionState.PUBLISHED && v.effectiveTo != null && !v.effectiveTo!!.isAfter(Instant.now())) "SUPERSEDED" else v.state.name

    private fun instantBody(v: Any?, name: String): Instant = try { OffsetDateTime.parse(v as String).toInstant() } catch (_: Exception) {
        throw ProblemException.invalid(Violation("/$name", ViolationCode.INVALID_FORMAT))
    }

    private fun uuidBody(v: String, name: String): UUID = try { UUID.fromString(v) } catch (_: IllegalArgumentException) { throw ProblemException.invalid(Violation("/$name", ViolationCode.INVALID_FORMAT)) }

    private fun resource(v: ConfigRevisionView) = ConfigRevisionResource(revisionJson(v), v.rowVersion)

    private fun revisionJson(v: ConfigRevisionView): Map<String, Any?> = linkedMapOf(
        "id" to v.id.toString(), "scope_kind" to v.scopeKind.name, "scope_id" to v.scopeId.toString(), "revision" to v.revision, "row_version" to v.rowVersion, "state" to stateOf(v),
        "schema_version" to v.schemaVersion, "values" to v.values, "effective_from" to v.effectiveFrom.toString(), "effective_to" to v.effectiveTo?.toString(),
        "created_by" to v.createdBy, "approved_by" to v.approvedBy, "reason" to v.reason, "created_at" to v.createdAt.toString(), "published_at" to v.publishedAt?.toString(),
        "validated_by" to v.validatedBy, "validated_at" to v.validatedAt?.toString(), "approved_at" to v.approvedAt?.toString(), "published_by" to v.publishedBy,
        "rolled_back_from" to v.rolledBackFrom?.toString(),
        "change_log" to linkedMapOf(
            "changed_by" to v.changeLog.changedBy, "changed_at" to v.changeLog.changedAt.toString(), "reason" to v.changeLog.reason,
            "effective_from" to v.changeLog.effectiveFrom.toString(), "effective_to" to v.changeLog.effectiveTo?.toString(),
            "predecessor_id" to v.changeLog.predecessorId?.toString(), "predecessor_revision" to v.changeLog.predecessorRevision,
            "changes" to v.changeLog.changes.map { linkedMapOf("pointer" to it.pointer, "kind" to it.kind.name, "old_value" to it.oldValue, "new_value" to it.newValue) },
        ),
    )

    private fun impactJson(r: ImpactReportView): Map<String, Any?> = linkedMapOf(
        "id" to r.id.toString(), "revision_id" to r.revisionId.toString(), "revision_row_version" to r.revisionRowVersion, "values_hash" to r.valuesHash,
        "generated_at" to r.createdAt.toString(), "created_by" to r.createdBy, "highest_severity" to r.highestSeverity.name, "conflict_count" to r.conflictCount,
        "affected" to r.affected.map { a ->
            linkedMapOf("kind" to a.kind, "severity" to a.severity.name, "count" to a.count, "object_refs" to a.refs.map { linkedMapOf("kind" to it.kind, "id" to it.id.toString()) })
        },
    )

    private fun parameterJson(p: ParameterDefinition): Map<String, Any?> = linkedMapOf(
        "name" to p.name, "type" to p.type, "unit" to p.unit, "minimum" to p.minimum, "maximum" to p.maximum, "default" to p.default,
        "allowed_levels" to p.allowedLevels.map { it.name }.sorted(), "security_class" to p.securityClass, "limit_source" to p.limitSource,
        "description" to p.description, "schema" to p.schema, "impact_required" to p.impactRequired, "impact_hint" to p.impactHint,
    )

    @Suppress("UNCHECKED_CAST")
    private fun objectOf(v: Any?): Map<String, Any?> = (v as? Map<String, Any?>) ?: throw ProblemException.invalid(Violation("/values", ViolationCode.TYPE_MISMATCH))

    private inline fun <reified E : Enum<E>> enumOf(v: String, name: String): E =
        enumValues<E>().firstOrNull { it.name == v } ?: throw ProblemException.invalid(Violation("/\$query/$name", ViolationCode.NOT_IN_ENUM))

    private fun uuidOf(v: String, name: String): UUID = try { UUID.fromString(v) } catch (_: IllegalArgumentException) { throw ProblemException.invalid(Violation("/\$query/$name", ViolationCode.INVALID_FORMAT)) }

    private fun instantOf(v: String, name: String): Instant = try { OffsetDateTime.parse(v).toInstant() } catch (_: DateTimeParseException) {
        throw ProblemException.invalid(Violation("/\$query/$name", if (Regex(".*(Z|[+-][0-9]{2}:[0-9]{2})$").matches(v)) ViolationCode.INVALID_FORMAT else ViolationCode.OFFSET_REQUIRED))
    }
}

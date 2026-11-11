package de.taktovia.config.internal

import de.taktovia.config.api.ChangeLogEntry
import de.taktovia.config.api.ConfigRevisionView
import de.taktovia.config.api.ConfigRevisions
import de.taktovia.config.api.CreateDraft
import de.taktovia.config.api.RevalidationReport
import de.taktovia.config.api.RevisionFilter
import de.taktovia.config.api.RevisionState
import de.taktovia.config.api.ScopeKind
import de.taktovia.config.api.UpdateDraft
import de.taktovia.config.api.ValidationOutcome
import de.taktovia.platform.api.Etag
import de.taktovia.platform.audit.ActorRef
import de.taktovia.platform.audit.AuditActions
import de.taktovia.platform.audit.AuditEntry
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.audit.AuditRequired
import de.taktovia.platform.audit.ObjectRef
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.outbox.OutboxEvent
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Konfigurationsrevisionen (WP-M1-20): Entwurf anlegen und ändern, lesen, auflisten, As-of-Rekonstruktion. Jede Fachänderung geschieht in **einer**
 * Transaktion mit Audit und (beim Anlegen) Outbox-Ereignis. Werte werden vor dem Schreiben gegen das `policy_schema` geprüft (WP-M1-19); die
 * Datenbank prüft sie beim Schreiben noch einmal. Publizierte Zeilen sind in der Datenbank unveränderlich (V1200).
 */
internal class JdbcConfigRevisions(
    private val ctxTx: ContextTransactions, private val catalogs: PolicyCatalogRepository, private val audit: AuditPort,
    private val outbox: Outbox, private val clock: Clock,
) : ConfigRevisions {
    private val mapper = JsonMapper.builder().build()

    override fun createDraft(ctx: RequestContext, cmd: CreateDraft): ConfigRevisionView {
        if (cmd.scopeKind == ScopeKind.TENANT && cmd.scopeId != ctx.tenant.value) throw ProblemException.notFound()   // fremder Scope: wie nicht vorhanden
        try {
            return ctxTx.run(ctx) { j -> doCreate(j, ctx, cmd) }
        } catch (e: DuplicateKeyException) {
            // Gleichzeitig wurde dieselbe Revisionsnummer im Scope vergeben (UNIQUE tenant_id, scope_kind, scope_id, revision): genau eine gewinnt.
            throw ProblemException(ErrorCode.CONFLICT, "Gleichzeitig wurde eine Revision für diesen Scope angelegt; erneut senden.", cause = e)
        }
    }

    @AuditRequired
    private fun doCreate(j: JdbcOperations, ctx: RequestContext, cmd: CreateDraft): ConfigRevisionView {
        val normalized = checkDraft(j, cmd.scopeKind, cmd.schemaVersion, cmd.values, cmd.reason, cmd.effectiveFrom, cmd.effectiveTo, requireCurrentVersion = true)
        val id = UUID.randomUUID()
        val actor = actorOf(ctx)
        val row = insertDraft(j, id, cmd.scopeKind, cmd.scopeId, null, cmd.schemaVersion, normalized, cmd.effectiveFrom, cmd.effectiveTo, actor.value, cmd.reason!!.trim())
        audit.record(j, AuditEntry(actor, AuditActions.CONFIG_DRAFT_CREATED, ObjectRef("config_revision", id), ctx.correlationId, mapOf(
            "scope_kind" to cmd.scopeKind.name, "scope_id" to cmd.scopeId.toString(), "revision" to row.revision, "schema_version" to cmd.schemaVersion,
            "effective_from" to cmd.effectiveFrom.toString(), "changed_keys" to normalized.keys.toList())))
        outbox.append(j, OutboxEvent(id, row.rowVersion, "config.revision_created", mapOf(
            "revision_id" to id.toString(), "scope_kind" to cmd.scopeKind.name, "scope_id" to cmd.scopeId.toString(), "revision" to row.revision,
            "state" to row.state.name, "schema_version" to cmd.schemaVersion, "effective_from" to cmd.effectiveFrom.toString(), "effective_to" to cmd.effectiveTo?.toString(),
        ), clock.instant(), correlationId = ctx.correlationId))
        return view(j, row)
    }

    /**
     * Legt die Zeile an. Mit [revision] `null` die nächste Nummer im Scope (höchste + 1); eine feste Nummer ist für Tests der Eindeutigkeit gedacht.
     * Zwei gleichzeitige Anfragen mit derselben Nummer: der Unique-Index lässt genau eine zu.
     */
    internal fun insertDraft(
        j: JdbcOperations, id: UUID, scope: ScopeKind, scopeId: UUID, revision: Long?, schemaVersion: String, values: Map<String, Any?>,
        from: Instant, to: Instant?, createdBy: String, reason: String,
    ): Row = j.query( // sql-lint: ok COLUMNS ist eine feste Konstante, Werte gebunden
        "INSERT INTO taktovia.config_revision AS n (tenant_id, id, scope_kind, scope_id, revision, schema_version, values_json, effective_from, effective_to, created_by, reason) " +
            "SELECT taktovia.current_tenant(), ?, ?, ?, COALESCE(CAST(? AS bigint), COALESCE(MAX(r.revision), 0) + 1), ?, ?::jsonb, ?, ?, ?, ? " +
            "FROM taktovia.config_revision r WHERE r.scope_kind = ? AND r.scope_id = ? RETURNING ${COLUMNS.replace("r.", "n.")}",
        ROW, id, scope.name, scopeId, revision, schemaVersion, mapper.writeValueAsString(values), Timestamp.from(from), to?.let(Timestamp::from), createdBy, reason,
        scope.name, scopeId,
    ).single()

    override fun updateDraft(ctx: RequestContext, id: UUID, expectedRowVersion: Long, cmd: UpdateDraft): ConfigRevisionView =
        ctxTx.run(ctx) { j -> doUpdate(j, ctx, id, expectedRowVersion, cmd) }

    @AuditRequired
    private fun doUpdate(j: JdbcOperations, ctx: RequestContext, id: UUID, expected: Long, cmd: UpdateDraft): ConfigRevisionView {
        if (cmd.values == null && cmd.effectiveFrom == null && !cmd.effectiveToSet && cmd.reason == null)
            throw ProblemException.invalid(Violation("/", ViolationCode.REQUIRED, "Mindestens ein zu ändernder Wert ist nötig"))
        // Zeile sperren: gleichzeitige Änderungen mit demselben ETag laufen nacheinander, die zweite sieht die erhöhte Zeilenversion (412).
        val current = j.query("SELECT $COLUMNS FROM taktovia.config_revision r WHERE r.id = ? FOR UPDATE", ROW, id).firstOrNull() ?: throw ProblemException.notFound()
        if (current.rowVersion != expected) throw ProblemException(ErrorCode.REVISION_MISMATCH, "Die Revision wurde zwischenzeitlich geändert; aktuelle Fassung lesen.")
        when (current.state) {
            RevisionState.DRAFT -> Unit
            RevisionState.PUBLISHED -> throw ProblemException(ErrorCode.IMMUTABLE_RECORD, "Eine publizierte Revision ist unveränderlich; eine Änderung ist eine neue Revision.")
            else -> throw ProblemException(ErrorCode.INVALID_STATE_TRANSITION, "Nur ein Entwurf kann geändert werden.")
        }
        val values = cmd.values ?: current.values
        val from = cmd.effectiveFrom ?: current.effectiveFrom
        val to = if (cmd.effectiveToSet) cmd.effectiveTo else current.effectiveTo
        val reason = cmd.reason ?: current.reason
        // Beim Ändern gilt die jetzt gültige Schema-Version; der Entwurf wird auf sie umgestellt, wenn er gültig ist.
        val version = catalogs.currentVersion(j, clock.instant()) ?: current.schemaVersion
        val normalized = checkDraft(j, current.scopeKind, version, values, reason, from, to, requireCurrentVersion = false)
        val updated = j.query(
            "UPDATE taktovia.config_revision r SET values_json = ?::jsonb, schema_version = ?, effective_from = ?, effective_to = ?, reason = ? " +
                "WHERE r.id = ? AND r.row_version = ? RETURNING $COLUMNS",
            ROW, mapper.writeValueAsString(normalized), version, Timestamp.from(from), to?.let(Timestamp::from), reason.trim(), id, expected,
        ).single()
        val changed = ConfigDiff.between(mapper.valueToTree(current.values), mapper.valueToTree(normalized)).map { it.pointer }
        audit.record(j, AuditEntry(actorOf(ctx), AuditActions.CONFIG_DRAFT_CHANGED, ObjectRef("config_revision", id), ctx.correlationId, mapOf(
            "scope_kind" to current.scopeKind.name, "scope_id" to current.scopeId.toString(), "revision" to current.revision, "row_version" to updated.rowVersion,
            "schema_version" to version, "effective_from" to from.toString(), "changed_keys" to changed)))
        return view(j, updated)
    }

    override fun get(ctx: RequestContext, id: UUID): ConfigRevisionView = ctxTx.run(ctx, readOnly = true) { j ->
        view(j, j.query("SELECT $COLUMNS FROM taktovia.config_revision r WHERE r.id = ?", ROW, id).firstOrNull() ?: throw ProblemException.notFound())
    }

    override fun list(ctx: RequestContext, filter: RevisionFilter, limit: Int, after: Pair<Instant, UUID>?): List<ConfigRevisionView> = ctxTx.run(ctx, readOnly = true) { j ->
        val where = mutableListOf("TRUE"); val args = mutableListOf<Any?>()
        filter.scopeKind?.let { where += "r.scope_kind = ?"; args += it.name }
        filter.scopeId?.let { where += "r.scope_id = ?"; args += it }
        filter.state?.let { where += "r.state = ?"; args += it.name }
        after?.let { where += "(r.created_at, r.id) > (?, ?)"; args += Timestamp.from(it.first); args += it.second }
        // Die Bedingungen sind feste Texte; Werte sind gebunden.
        val sql = "SELECT $COLUMNS FROM taktovia.config_revision r WHERE ${where.joinToString(" AND ")} ORDER BY r.created_at, r.id LIMIT ?" // sql-lint: ok feste Bedingungstexte, Werte gebunden
        j.query(sql, ROW, *args.toTypedArray(), limit + 1).map { view(j, it) } // sql-lint: ok feste Bedingungstexte (Zeile 136), Werte gebunden
    }

    override fun asOf(ctx: RequestContext, scopeKind: ScopeKind, scopeId: UUID, at: Instant): ConfigRevisionView? = ctxTx.run(ctx, readOnly = true) { j ->
        val ts = Timestamp.from(at)
        j.query(
            "SELECT $COLUMNS FROM taktovia.config_revision r WHERE r.scope_kind = ? AND r.scope_id = ? AND r.state = 'PUBLISHED' " +
                "AND r.effective_from <= ? AND (r.effective_to IS NULL OR r.effective_to > ?) ORDER BY r.effective_from DESC, r.revision DESC LIMIT 1",
            ROW, scopeKind.name, scopeId, ts, ts,
        ).firstOrNull()?.let { view(j, it) }
    }

    override fun revalidate(ctx: RequestContext, id: UUID): RevalidationReport = ctxTx.run(ctx, readOnly = true) { j ->
        val row = j.query("SELECT $COLUMNS FROM taktovia.config_revision r WHERE r.id = ?", ROW, id).firstOrNull() ?: throw ProblemException.notFound()
        val version = catalogs.currentVersion(j, clock.instant()) ?: throw ProblemException(ErrorCode.SERVICE_UNAVAILABLE, "Kein Parameterkatalog gültig.")
        val catalog = catalogs.load(j, version) ?: throw ProblemException(ErrorCode.SERVICE_UNAVAILABLE, "Parameterkatalog nicht ladbar.")
        when (val o = ParameterValidator(catalog).validate(row.scopeKind, row.values)) {
            is ValidationOutcome.Valid -> RevalidationReport(id, version, true, emptyList())
            is ValidationOutcome.Invalid -> RevalidationReport(id, version, false, o.violations)
        }
    }

    // ---- Prüfungen und Abbildung --------------------------------------------------------------------------------------------------------------

    /** Prüft Pflichtangaben, Wirksamkeit und Werte; sammelt alle Feldverletzungen (422) und liefert die normalisierten Werte. */
    private fun checkDraft(
        j: JdbcOperations, scope: ScopeKind, schemaVersion: String, values: Map<String, Any?>, reason: String?, from: Instant, to: Instant?,
        requireCurrentVersion: Boolean,
    ): Map<String, Any?> {
        val violations = mutableListOf<Violation>()
        when {
            reason == null || reason.isBlank() -> violations += Violation("/reason", ViolationCode.REQUIRED)
            reason.trim().length < 3 -> violations += Violation("/reason", ViolationCode.TOO_SHORT)
            reason.trim().length > 1000 -> violations += Violation("/reason", ViolationCode.TOO_LONG)
        }
        if (to != null && !to.isAfter(from)) violations += Violation("/effective_to", ViolationCode.INTERVAL_INVALID)
        val current = catalogs.currentVersion(j, clock.instant())
        var normalized: Map<String, Any?> = values
        if (requireCurrentVersion && schemaVersion != current) {
            violations += Violation("/schema_version", ViolationCode.INVALID_VALUE)   // nur die jetzt gültige Version ist für neue Entwürfe zulässig
        } else {
            val catalog = catalogs.load(j, schemaVersion)
            if (catalog == null) violations += Violation("/schema_version", ViolationCode.INVALID_VALUE)
            else when (val o = ParameterValidator(catalog).validate(scope, values)) {
                is ValidationOutcome.Valid -> normalized = o.values
                is ValidationOutcome.Invalid -> violations += o.violations.map { it.copy(pointer = "/values" + it.pointer) }
            }
            if (catalog != null && scope == ScopeKind.SERVICE && "service_config" !in values)
                violations += Violation("/values/service_config", ViolationCode.REQUIRED)   // jede Dienstrevision ist eine vollständige Dienstkonfiguration (SYS-002)
        }
        if (violations.isNotEmpty()) throw ProblemException.invalid(*violations.toTypedArray())
        return normalized
    }

    private fun actorOf(ctx: RequestContext) = ActorRef((if (ctx.actor.type == ActorType.SERVICE) "service:" else "user:") + ctx.actor.subject)

    private fun view(j: JdbcOperations, row: Row): ConfigRevisionView {
        // Vorgänger: die höchste publizierte Revision des Scopes mit kleinerer Nummer.
        val pred = j.query(
            "SELECT $COLUMNS FROM taktovia.config_revision r WHERE r.scope_kind = ? AND r.scope_id = ? AND r.revision < ? AND r.state = 'PUBLISHED' ORDER BY r.revision DESC LIMIT 1",
            ROW, row.scopeKind.name, row.scopeId, row.revision,
        ).firstOrNull()
        val changes = ConfigDiff.between(pred?.let { mapper.valueToTree<JsonNode>(it.values) }, mapper.valueToTree(row.values))
        return ConfigRevisionView(
            row.id, row.scopeKind, row.scopeId, row.revision, row.state, row.schemaVersion, row.values, row.effectiveFrom, row.effectiveTo, row.createdBy, row.createdAt,
            row.approvedBy, row.publishedAt, row.reason, row.rowVersion,
            ChangeLogEntry(row.createdBy, row.createdAt, row.reason, row.effectiveFrom, row.effectiveTo, pred?.id, pred?.revision, changes),
        )
    }

    internal class Row(
        val id: UUID, val scopeKind: ScopeKind, val scopeId: UUID, val revision: Long, val state: RevisionState, val schemaVersion: String,
        val values: Map<String, Any?>, val effectiveFrom: Instant, val effectiveTo: Instant?, val createdBy: String, val createdAt: Instant,
        val approvedBy: String?, val publishedAt: Instant?, val reason: String, val rowVersion: Long,
    )

    private val ROW = RowMapper { rs, _ ->
        Row(
            rs.getObject("id", UUID::class.java), ScopeKind.valueOf(rs.getString("scope_kind")), rs.getObject("scope_id", UUID::class.java), rs.getLong("revision"),
            RevisionState.valueOf(rs.getString("state")), rs.getString("schema_version"), mapper.readValue(rs.getString("values_json"), Map::class.java).toStringKeyed(),
            rs.getTimestamp("effective_from").toInstant(), rs.getTimestamp("effective_to")?.toInstant(), rs.getString("created_by"), rs.getTimestamp("created_at").toInstant(),
            rs.getString("approved_by"), rs.getTimestamp("published_at")?.toInstant(), rs.getString("reason"), rs.getLong("row_version"),
        )
    }

    private companion object {
        const val COLUMNS = "r.id, r.scope_kind, r.scope_id, r.revision, r.state, r.schema_version, r.values_json::text AS values_json, r.effective_from, r.effective_to, " +
            "r.created_by, r.created_at, r.approved_by, r.published_at, r.reason, r.row_version"
    }
}

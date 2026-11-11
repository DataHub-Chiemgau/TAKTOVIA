package de.taktovia.config.internal

import de.taktovia.config.api.ConfigRevisionView
import de.taktovia.config.api.ImpactAffected
import de.taktovia.config.api.ImpactAnalysis
import de.taktovia.config.api.ImpactFinding
import de.taktovia.config.api.ImpactObjectRef
import de.taktovia.config.api.ImpactProvider
import de.taktovia.config.api.ImpactReportRef
import de.taktovia.config.api.ImpactReportView
import de.taktovia.config.api.ImpactSeverity
import de.taktovia.config.api.ImpactSubject
import de.taktovia.config.api.RevisionState
import de.taktovia.platform.audit.ActorRef
import de.taktovia.platform.audit.AuditActions
import de.taktovia.platform.audit.AuditEntry
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.audit.AuditRequired
import de.taktovia.platform.audit.ObjectRef
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.outbox.EventEnvelope
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.util.UUID

/**
 * Wirkungsanalyse (WP-M1-23). Ablauf von [analyze]: eine Transaktion, die die Revisionszeile `FOR SHARE` sperrt (kein gleichzeitiges Ändern des
 * Entwurfs), die Zeilenversion gegen `If-Match` prüft, alle Anbieter in **dieser** Transaktion befragt (konsistenter Stand, Mandantenkontext durch RLS) und
 * den Bericht unveränderlich ablegt. Regel „neue betroffene Objekte zwischen Bericht und Publikation“: die Revisionsbindung deckt Änderungen an der
 * Revision ab (Hash der Werte); Änderungen an den **betroffenen Objekten** (neue Buchungen) deckt [confirmForPublication] ab, indem er die Anbieter erneut
 * befragt und das Ergebnis über `affected_digest` mit dem Bericht vergleicht; Abweichung ist 409, ein neuer Bericht nötig.
 */
internal class JdbcImpactAnalysis(
    private val ctxTx: ContextTransactions, private val revisions: JdbcConfigRevisions, private val audit: AuditPort,
    private val providers: List<ImpactProvider>, private val clock: Clock,
) : ImpactAnalysis {
    private val mapper = JsonMapper.builder().build()
    private val log = LoggerFactory.getLogger(JdbcImpactAnalysis::class.java)

    override fun analyze(ctx: RequestContext, revisionId: UUID, expectedRowVersion: Long): ImpactReportView {
        if (!ctx.hasScope("config:write")) throw ProblemException(ErrorCode.FORBIDDEN, "Für die Wirkungsanalyse ist das Konfigurationsrecht nötig.")
        return ctxTx.run(ctx) { j -> doAnalyze(j, ctx, revisionId, expectedRowVersion) }
    }

    @AuditRequired
    private fun doAnalyze(j: JdbcOperations, ctx: RequestContext, revisionId: UUID, expected: Long): ImpactReportView {
        val rev = revisions.getShared(j, revisionId) ?: throw ProblemException.notFound()
        if (rev.rowVersion != expected) throw ProblemException(ErrorCode.REVISION_MISMATCH, "Die Revision wurde zwischenzeitlich geändert; aktuelle Fassung lesen.")
        if (rev.state == RevisionState.PUBLISHED) throw ProblemException(ErrorCode.IMMUTABLE_RECORD, "Eine publizierte Revision braucht keine Wirkungsanalyse.")
        val affected = collect(j, ctx, rev)
        val highest = affected.maxOfOrNull { it.severity } ?: ImpactSeverity.NONE
        val conflicts = affected.filter { it.severity != ImpactSeverity.NONE }.sumOf { it.count }
        val id = UUID.randomUUID()
        val actor = actorOf(ctx)
        val hash = hashOf(rev)
        val created = j.query(
            "INSERT INTO taktovia.impact_report AS r (tenant_id, id, config_revision_id, revision_row_version, values_hash, schema_version, affected_digest, highest_severity, " +
                "conflict_count, affected, created_by) VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?) RETURNING $COLUMNS",
            ROW, id, revisionId, rev.rowVersion, hash, rev.schemaVersion, digestOf(affected), highest.name, conflicts, mapper.writeValueAsString(affected.map(::affectedJson)), actor.value,
        ).single()
        audit.record(j, AuditEntry(actor, AuditActions.CONFIG_IMPACT_ANALYZED, ObjectRef("impact_report", id), ctx.correlationId, mapOf(
            "revision_id" to revisionId.toString(), "revision" to rev.revision, "values_hash" to hash, "highest_severity" to highest.name, "conflict_count" to conflicts,
            "kinds" to affected.map { it.kind })))
        return created
    }

    override fun get(ctx: RequestContext, reportId: UUID): ImpactReportView = ctxTx.run(ctx, readOnly = true) { j ->
        j.query("SELECT $COLUMNS FROM taktovia.impact_report r WHERE r.id = ?", ROW, reportId).firstOrNull() ?: throw ProblemException.notFound()
    }

    override fun confirmForPublication(j: JdbcOperations, ctx: RequestContext, revisionId: UUID, reportId: UUID?): ImpactReportRef {
        if (reportId == null) throw ProblemException.invalid(Violation("/impact_report_id", ViolationCode.REQUIRED, "Ohne Wirkungsbericht keine Publikation"))
        val rev = revisions.getShared(j, revisionId) ?: throw ProblemException.notFound()
        // Fremder oder unbekannter Bericht: für den Aufrufer nicht unterscheidbar (RLS blendet fremde Mandanten aus).
        val report = j.query("SELECT $COLUMNS FROM taktovia.impact_report r WHERE r.id = ?", ROW, reportId).firstOrNull()
            ?: throw ProblemException.invalid(Violation("/impact_report_id", ViolationCode.INVALID_VALUE, "Wirkungsbericht unbekannt"))
        if (report.revisionId != revisionId) throw ProblemException(ErrorCode.CONFLICT, "Der Wirkungsbericht gehört zu einer anderen Revision.")
        if (report.valuesHash != hashOf(rev))
            throw ProblemException(ErrorCode.CONFLICT, "Die Revision wurde nach der Wirkungsanalyse geändert; Analyse wiederholen.")
        val current = collect(j, ctx, rev)
        if (digestOf(current) != digestOf(report.affected))
            throw ProblemException(ErrorCode.CONFLICT, "Seit der Wirkungsanalyse sind andere Objekte betroffen; Analyse wiederholen.")
        bind(j, ctx, rev, report)
        return ImpactReportRef(report.id, revisionId, report.valuesHash, report.highestSeverity, report.conflictCount)
    }

    @AuditRequired
    private fun bind(j: JdbcOperations, ctx: RequestContext, rev: ConfigRevisionView, report: ImpactReportView) {
        audit.record(j, AuditEntry(actorOf(ctx), AuditActions.CONFIG_IMPACT_BOUND, ObjectRef("config_revision", rev.id), ctx.correlationId, mapOf(
            "impact_report_id" to report.id.toString(), "values_hash" to report.valuesHash, "highest_severity" to report.highestSeverity.name, "conflict_count" to report.conflictCount)))
    }

    // ---- Anbieter ---------------------------------------------------------------------------------------------------------------------------

    /** Befragt alle Anbieter, prüft die Befunde (Format, keine Personendaten) und fasst gleiche Art und Schwere zusammen. Ein Anbieterfehler bricht ab (503). */
    private fun collect(j: JdbcOperations, ctx: RequestContext, rev: ConfigRevisionView): List<ImpactAffected> {
        val subject = ImpactSubject(rev, rev.changeLog.changes.filter { ImpactRequiredParameters.isImpactRequired(it.pointer) })
        val findings = providers.flatMap { p ->
            try { p.analyze(j, ctx, subject).also { it.forEach(::check) } } catch (e: ProblemException) { throw e } catch (e: Exception) {
                log.warn("Wirkungsanbieter {} ausgefallen: {}", p.name, e.javaClass.simpleName)
                throw ProblemException(ErrorCode.SERVICE_UNAVAILABLE, "Ein Fachmodul konnte die Wirkung nicht bestimmen; kein Bericht erzeugt.", cause = e)
            }
        }
        val merged = findings.groupBy { it.kind to it.severity }.map { (key, list) ->
            val refs = list.flatMap { it.refs }.distinct().sortedWith(compareBy({ it.kind }, { it.id })).take(MAX_REFS)
            ImpactAffected(key.first, key.second, list.sumOf { it.count }, refs)
        }.sortedWith(compareBy({ it.kind }, { it.severity }))
        if (merged.size > MAX_AFFECTED) throw ProblemException(ErrorCode.SERVICE_UNAVAILABLE, "Zu viele verschiedene Befunde.")
        return merged
    }

    private fun check(f: ImpactFinding) {
        require(KIND.matches(f.kind)) { "Befundart ungültig" }
        require(f.count >= f.refs.size && f.count in 0..MAX_COUNT) { "Anzahl passt nicht zu den Verweisen" }
        f.refs.forEach { require(KIND.matches(it.kind)) { "Verweisart ungültig" } }
    }

    // ---- Bindung ----------------------------------------------------------------------------------------------------------------------------

    /** SHA-256 über die geprüften Werte: Scope, Schema-Version, Werte und Gültigkeit in kanonischer Form (der Grund und Zeilenversion zählen nicht). */
    private fun hashOf(rev: ConfigRevisionView): String = EventEnvelope.sha256(mapper.valueToTree(linkedMapOf(
        "scope_kind" to rev.scopeKind.name, "scope_id" to rev.scopeId.toString(), "schema_version" to rev.schemaVersion, "values" to rev.values,
        "effective_from" to rev.effectiveFrom.toString(), "effective_to" to rev.effectiveTo?.toString(),
    )))

    private fun digestOf(affected: List<ImpactAffected>): String = EventEnvelope.sha256(mapper.valueToTree(affected.map(::affectedJson)))

    private fun affectedJson(a: ImpactAffected): Map<String, Any?> = linkedMapOf(
        "kind" to a.kind, "severity" to a.severity.name, "count" to a.count, "object_refs" to a.refs.map { linkedMapOf("kind" to it.kind, "id" to it.id.toString()) },
    )

    private fun actorOf(ctx: RequestContext) = ActorRef((if (ctx.actor.type == ActorType.SERVICE) "service:" else "user:") + ctx.actor.subject)

    private val ROW = RowMapper { rs, _ ->
        val items = mapper.readTree(rs.getString("affected")).values().map { n ->
            ImpactAffected(n.get("kind").asString(), ImpactSeverity.valueOf(n.get("severity").asString()), n.get("count").asLong(),
                n.get("object_refs").values().map { r -> ImpactObjectRef(r.get("kind").asString(), UUID.fromString(r.get("id").asString())) })
        }
        ImpactReportView(
            rs.getObject("id", UUID::class.java), rs.getObject("config_revision_id", UUID::class.java), rs.getLong("revision_row_version"), rs.getString("values_hash").trim(),
            rs.getString("schema_version"), ImpactSeverity.valueOf(rs.getString("highest_severity")), rs.getLong("conflict_count"), items, rs.getString("created_by"),
            rs.getTimestamp("created_at").toInstant(),
        )
    }

    private companion object {
        const val MAX_REFS = 100
        const val MAX_AFFECTED = 100
        const val MAX_COUNT = 9_007_199_254_740_991L
        val KIND = Regex("[A-Z][A-Z0-9_]{1,63}")
        const val COLUMNS = "r.id, r.config_revision_id, r.revision_row_version, r.values_hash, r.schema_version, r.affected_digest, r.highest_severity, r.conflict_count, " +
            "r.affected::text AS affected, r.created_by, r.created_at"
    }
}

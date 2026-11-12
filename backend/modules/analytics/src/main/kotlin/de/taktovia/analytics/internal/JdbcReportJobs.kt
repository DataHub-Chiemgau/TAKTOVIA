package de.taktovia.analytics.internal

import de.taktovia.analytics.api.CategoryResult
import de.taktovia.analytics.api.CreateExport
import de.taktovia.analytics.api.ExportCategories
import de.taktovia.analytics.api.ExportCreated
import de.taktovia.analytics.api.ExportFile
import de.taktovia.analytics.api.ExportJobView
import de.taktovia.analytics.api.ExportKind
import de.taktovia.analytics.api.ExportScope
import de.taktovia.analytics.api.ExportState
import de.taktovia.analytics.api.ReportJobs
import de.taktovia.platform.audit.ActorRef
import de.taktovia.platform.audit.AuditActions
import de.taktovia.platform.audit.AuditEntry
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.audit.AuditRequired
import de.taktovia.platform.audit.ObjectRef
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import org.springframework.jdbc.core.JdbcOperations
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Exportaufträge (WP-M5-19). Anlegen ist idempotent (eindeutiger Schlüssel je Auftraggeber, gleicher Umfang = derselbe Auftrag, anderer Umfang = Konflikt); die Verarbeitung ist ein getrennter
 * Betriebsschritt ([runPending]: Auftrag übernehmen, Ergebnis in einem Statement sammeln, Ergebnis einmal schreiben). Rechte: `reports:write` zum Anlegen, `reports:read` zum Lesen und Abrufen, je Art das
 * Fachrecht (Empfängerrechte, zum Auftragszeitpunkt festgehalten). Fremde Aufträge und Objekte sind 404.
 */
internal class JdbcReportJobs(
    private val ctxTx: ContextTransactions, private val tx: TenantTransactions, private val audit: AuditPort, private val tokens: DownloadTokens, private val clock: Clock,
    private val ttl: Duration, private val checklist: List<String>,
) : ReportJobs {

    override fun create(ctx: RequestContext, cmd: CreateExport, idempotencyKey: String): ExportCreated {
        if (ctx.actor.type != ActorType.HUMAN && ctx.actor.type != ActorType.SERVICE || !ctx.hasScope(SCOPE_WRITE)) throw ProblemException(ErrorCode.FORBIDDEN)
        if (idempotencyKey.length !in 1..255) throw ProblemException.invalid(Violation("/\$header/Idempotency-Key", ViolationCode.INVALID_FORMAT))
        val needed = ExportCategories.SCOPE_OF[cmd.kind.code]
        if (needed != null && !ctx.hasScope(needed)) throw ProblemException(ErrorCode.FORBIDDEN)
        return ctxTx.run(ctx) { j -> doCreate(j, ctx, cmd, idempotencyKey) }
    }

    @AuditRequired
    private fun doCreate(j: JdbcOperations, ctx: RequestContext, cmd: CreateExport, key: String): ExportCreated {
        references(j, cmd.scope)
        val actor = requester(ctx)
        val hash = requestHash(cmd)
        val id = UUID.randomUUID(); val asOf = Timestamp.from(clock.instant())
        val scopes = ctx.scopes.sorted().toTypedArray(); val roles = ctx.roles.sorted().toTypedArray()
        val inserted = j.query(
            "INSERT INTO taktovia.export_job (tenant_id, id, kind, scope, requested_by, requester_scopes, requester_roles, idempotency_key, request_hash, as_of) " +
                "VALUES (taktovia.current_tenant(), ?, ?, CAST(? AS jsonb), ?, CAST(? AS text[]), CAST(? AS text[]), ?, ?, ?) ON CONFLICT (tenant_id, requested_by, idempotency_key) DO NOTHING RETURNING id",
            { rs, _ -> rs.getObject(1, UUID::class.java) }, id, cmd.kind.code, scopeJson(cmd.scope), actor, pgArray(scopes), pgArray(roles), key, hash, asOf,
        ).firstOrNull()
        if (inserted == null) {
            val existing = j.query("SELECT id, request_hash FROM taktovia.export_job WHERE requested_by = ? AND idempotency_key = ?", { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getString(2) }, actor, key).single()
            if (existing.second != hash) throw ProblemException(ErrorCode.IDEMPOTENCY_CONFLICT, "Der Idempotency-Key wurde mit anderem Exportumfang verwendet.")
            return ExportCreated(load(j, existing.first, actor, issue = false), replayed = true)
        }
        audit.record(j, AuditEntry(ActorRef(actor), AuditActions.REPORT_EXPORT_REQUESTED, ObjectRef("export_job", id), ctx.correlationId,
            mapOf("kind" to cmd.kind.code, "scope_keys" to cmd.scope.toMap().keys.sorted().joinToString(","), "as_of" to asOf.toInstant().toString())))
        return ExportCreated(load(j, id, actor, issue = false), replayed = false)
    }

    override fun get(ctx: RequestContext, jobId: UUID): ExportJobView {
        if (!ctx.hasScope(SCOPE_READ)) throw ProblemException(ErrorCode.FORBIDDEN)
        return ctxTx.run(ctx, readOnly = true) { j -> load(j, jobId, requester(ctx), issue = true) }
    }

    override fun download(ctx: RequestContext, jobId: UUID, token: String): ExportFile {
        if (!ctx.hasScope(SCOPE_READ)) throw ProblemException(ErrorCode.FORBIDDEN)
        // Die Verweigerung wird im Sicherheitsprotokoll festgehalten (eigene Transaktion, die committet), die Ablehnung folgt erst danach: ein Fehler im Block würde das Protokoll zurückrollen.
        return when (val r = ctxTx.run(ctx) { j -> doDownload(j, ctx, jobId, token) }) { is Download.Granted -> r.file; Download.Denied -> throw ProblemException.notFound() }
    }

    private sealed interface Download { class Granted(val file: ExportFile) : Download; object Denied : Download }

    @AuditRequired
    private fun doDownload(j: JdbcOperations, ctx: RequestContext, jobId: UUID, token: String): Download {
        val actor = requester(ctx)
        val row = j.query("SELECT kind, state, result::text, result_sha256, result_bytes FROM taktovia.export_job WHERE id = ? AND requested_by = ?",
            { rs, _ -> arrayOf<Any?>(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5)) }, jobId, actor).firstOrNull()
        if (row == null || !tokens.valid(token, ctx.tenant.value, jobId, clock.instant()) || row[1] != "SUCCEEDED") {
            audit.record(j, AuditEntry(ActorRef(actor), AuditActions.SECURITY_EXPORT_DOWNLOAD_DENIED, ObjectRef("export_job", jobId), ctx.correlationId,
                mapOf("reason" to if (row == null) "NOT_VISIBLE" else if (row[1] != "SUCCEEDED") "NOT_READY" else "LINK_INVALID_OR_EXPIRED")))
            return Download.Denied
        }
        audit.record(j, AuditEntry(ActorRef(actor), AuditActions.REPORT_EXPORT_DOWNLOADED, ObjectRef("export_job", jobId), ctx.correlationId,
            mapOf("kind" to row[0], "result_sha256" to row[3], "result_bytes" to row[4])))
        return Download.Granted(ExportFile(jobId, ExportKind.parse(row[0] as String)!!, row[3] as String, row[2] as String))
    }

    override fun runPending(tenant: TenantId, limit: Int): Int {
        var done = 0
        while (done < limit) {
            val claimed = tx.run(tenant) { j -> claim(j) } ?: break
            val outcome = runCatching { tx.run(tenant, readOnly = true) { j -> collect(j, claimed) } }
            tx.run(tenant) { j -> finish(j, claimed, outcome) }
            done++
        }
        return done
    }

    private class Claimed(val id: UUID, val kind: ExportKind, val scope: ExportScope, val asOf: Instant, val scopes: Set<String>, val roles: Set<String>)
    private class Collected(val sha: String, val bytes: Int, val content: String, val categories: List<CategoryResult>)

    private fun claim(j: JdbcOperations): Claimed? {
        val c = j.query(
            "SELECT id, kind, scope ->> 'vehicle_id', scope ->> 'from', scope ->> 'to', scope ->> 'person_ref', scope ->> 'depot_id', as_of, array_to_string(requester_scopes, ','), array_to_string(requester_roles, ',') " +
                "FROM taktovia.export_job WHERE state = 'QUEUED' ORDER BY created_at, id LIMIT 1 FOR UPDATE SKIP LOCKED",
            { rs, _ ->
                Claimed(rs.getObject(1, UUID::class.java), ExportKind.parse(rs.getString(2))!!,
                    ExportScope(rs.getString(3)?.let(UUID::fromString), rs.getString(4)?.let { java.time.OffsetDateTime.parse(it).toInstant() }, rs.getString(5)?.let { java.time.OffsetDateTime.parse(it).toInstant() },
                        rs.getString(6), rs.getString(7)?.let(UUID::fromString)), rs.getTimestamp(8).toInstant(), rs.getString(9).split(',').filter { it.isNotEmpty() }.toSet(), rs.getString(10).split(',').filter { it.isNotEmpty() }.toSet())
            },
        ).firstOrNull() ?: return null
        j.update("UPDATE taktovia.export_job SET state = 'RUNNING', started_at = clock_timestamp() WHERE id = ? AND state = 'QUEUED'", c.id)
        return c
    }

    /** Kategorien, die der Auftraggeber bekommt, und ihr Status. Einzelarten liefern nur ihre Kategorie, PBEFG_DATA die Kategorien der Freigabecheckliste. */
    private fun plan(c: Claimed): List<Pair<String, String>> {
        val wanted = if (c.kind == ExportKind.PBEFG_DATA) checklist else listOf(c.kind.code)
        return wanted.map { cat ->
            val needed = ExportCategories.SCOPE_OF[cat]
            cat to when { cat !in ExportCategories.SUPPORTED -> ExportCategories.MISSING; needed != null && needed !in c.scopes -> ExportCategories.NOT_AUTHORIZED; else -> ExportCategories.PRESENT }
        }
    }

    private fun collect(j: JdbcOperations, c: Claimed): Collected {
        val plan = plan(c).toMap()
        fun on(cat: String) = plan[cat] == ExportCategories.PRESENT
        val sc = c.scope
        val row = j.query(ExportQueries.COLLECT, { rs, _ ->
            arrayOf<Any?>(rs.getString(1), rs.getObject(2) as Int?, rs.getObject(3) as Int?, rs.getObject(4) as Int?, rs.getObject(5) as Int?, rs.getObject(6) as Int?)
        }, Timestamp.from(c.asOf), sc.vehicleId, sc.from?.let(Timestamp::from), sc.to?.let(Timestamp::from), sc.personRef, sc.depotId,
            on(ExportCategories.INSPECTION_PROOF), on(ExportCategories.DEFECT_CASE), "defects:history" in c.scopes, on(ExportCategories.TIME_PROOF), on(ExportCategories.ROSTER_ARCHIVE), on(ExportCategories.VEHICLE_REGISTER),
            c.roles.any { it in CONFIDENTIAL_ROLES }).single()
        val counts = mapOf(ExportCategories.VEHICLE_REGISTER to row[1], ExportCategories.INSPECTION_PROOF to row[2], ExportCategories.DEFECT_CASE to row[3], ExportCategories.TIME_PROOF to row[4], ExportCategories.ROSTER_ARCHIVE to row[5])
        val categories = plan(c).map { (cat, status) -> CategoryResult(cat, status, if (status == ExportCategories.PRESENT) (counts[cat] as Int?) ?: 0 else 0) }
        // Kopf mit Art, Datenstand und Kategorien; der Datenteil ist der Text des Statements (gültiges JSON). Der Text wird von der Datenbank normalisiert, damit Hash und gespeicherter Inhalt übereinstimmen.
        val head = "{\"schema_version\":\"1.0\",\"kind\":\"${c.kind.code}\",\"as_of\":\"${c.asOf}\",\"categories\":[" +
            categories.joinToString(",") { "{\"code\":\"${it.code}\",\"status\":\"${it.status}\",\"rows\":${it.rows}}" } + "],\"data\":" + (row[0] as String) + "}"
        val normalized = j.queryForObject("SELECT CAST(CAST(? AS jsonb) AS text)", String::class.java, head)!!
        val bytes = normalized.toByteArray(Charsets.UTF_8)
        return Collected(sha256(bytes), bytes.size, normalized, categories)
    }

    private fun finish(j: JdbcOperations, c: Claimed, outcome: Result<Collected>) {
        val r = outcome.getOrNull()
        if (r == null) {
            val code = (outcome.exceptionOrNull()?.javaClass?.simpleName ?: "ERROR").take(100)
            j.update("UPDATE taktovia.export_job SET state = 'FAILED', finished_at = clock_timestamp(), failure_code = ? WHERE id = ?", code, c.id)
            return
        }
        val cats = r.categories.joinToString(",", "[", "]") { "{\"code\":\"${it.code}\",\"status\":\"${it.status}\",\"rows\":${it.rows}}" }
        j.update("UPDATE taktovia.export_job SET state = 'SUCCEEDED', finished_at = clock_timestamp(), result = CAST(? AS jsonb), result_sha256 = ?, result_bytes = ?, categories = CAST(? AS jsonb) WHERE id = ?",
            r.content, r.sha, r.bytes, cats, c.id)
    }

    /** Das Objekt des Auftrags (Fahrzeug, Depot) muss im Mandanten des Kontexts existieren; ein fremdes oder unbekanntes ist 404 ohne Hinweis. */
    private fun references(j: JdbcOperations, s: ExportScope) {
        s.vehicleId?.let { if (j.queryForObject("SELECT count(*) FROM taktovia.vehicle WHERE id = ?", Int::class.java, it) == 0) throw ProblemException.notFound() }
        s.depotId?.let { if (j.queryForObject("SELECT count(*) FROM taktovia.depot WHERE id = ?", Int::class.java, it) == 0) throw ProblemException.notFound() }
    }

    private fun load(j: JdbcOperations, id: UUID, actor: String, issue: Boolean): ExportJobView {
        val v = j.query(
            "SELECT id, kind, scope ->> 'vehicle_id', scope ->> 'from', scope ->> 'to', scope ->> 'person_ref', scope ->> 'depot_id', state, as_of, created_at, finished_at, result_sha256, result_bytes, failure_code, revision " +
                "FROM taktovia.export_job WHERE id = ? AND requested_by = ?",
            { rs, _ ->
                val state = ExportState.valueOf(rs.getString(8))
                ExportJobView(rs.getObject(1, UUID::class.java), ExportKind.parse(rs.getString(2))!!,
                    ExportScope(rs.getString(3)?.let(UUID::fromString), rs.getString(4)?.let { java.time.OffsetDateTime.parse(it).toInstant() }, rs.getString(5)?.let { java.time.OffsetDateTime.parse(it).toInstant() },
                        rs.getString(6), rs.getString(7)?.let(UUID::fromString)), state, rs.getTimestamp(9).toInstant(), rs.getTimestamp(10).toInstant(), rs.getTimestamp(11)?.toInstant(),
                    rs.getString(12), rs.getObject(13) as Int?, null, rs.getString(14), rs.getLong(15))
            }, id, actor).firstOrNull() ?: throw ProblemException.notFound()
        val cats = if (v.state == ExportState.SUCCEEDED) j.query("SELECT c ->> 'code', c ->> 'status', (c ->> 'rows')::int FROM taktovia.export_job x, jsonb_array_elements(x.categories) c WHERE x.id = ?",
            { rs, _ -> CategoryResult(rs.getString(1), rs.getString(2), rs.getInt(3)) }, id) else null
        if (!(issue && v.state == ExportState.SUCCEEDED)) return v.copy(categories = cats)
        val exp = clock.instant().plus(ttl)
        return v.copy(categories = cats, downloadToken = tokens.issue(currentTenant(j), id, exp), downloadExpiresAt = exp)
    }

    private fun currentTenant(j: JdbcOperations): UUID = j.queryForObject("SELECT taktovia.current_tenant()", UUID::class.java)!!
    private fun requester(ctx: RequestContext) = (if (ctx.actor.type == ActorType.SERVICE) "service:" else "user:") + ctx.actor.subject.lowercase()
    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun pgArray(v: Array<String>) = v.joinToString(",", "{", "}") { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" }
    private fun scopeJson(s: ExportScope) = s.toMap().entries.sortedBy { it.key }.joinToString(",", "{", "}") { "\"${it.key}\":\"${it.value.toString().replace("\\", "\\\\").replace("\"", "\\\"")}\"" }
    private fun requestHash(c: CreateExport) = sha256((c.kind.code + "|" + c.scope.toMap().entries.sortedBy { it.key }.joinToString("|") { "${it.key}=${it.value}" }).toByteArray(Charsets.UTF_8))

    private companion object {
        const val SCOPE_WRITE = "reports:write"
        const val SCOPE_READ = "reports:read"
        /** Rollen mit Einsicht in vertrauliche Schadenakteninhalte (wie `DefectConfidentiality`: Werkstatt und Mandantenadministration, Planungsannahme DMG-011). */
        val CONFIDENTIAL_ROLES = setOf("workshop", "tenant_admin")
    }
}

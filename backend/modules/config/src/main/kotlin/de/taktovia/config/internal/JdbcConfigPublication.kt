package de.taktovia.config.internal

import de.taktovia.config.api.ConfigPublication
import de.taktovia.config.api.ConfigRevisionView
import de.taktovia.config.api.ImpactAnalysis
import de.taktovia.config.api.PublicationResult
import de.taktovia.config.api.PublishCommand
import de.taktovia.config.api.RevisionState
import de.taktovia.config.api.RevisionValidator
import de.taktovia.config.api.RollbackCommand
import de.taktovia.config.api.ScopeKind
import de.taktovia.config.api.ValidationOutcome
import de.taktovia.config.api.ValidationResult
import de.taktovia.platform.approval.FourEyes
import de.taktovia.platform.audit.AuditActions
import de.taktovia.platform.audit.AuditEntry
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.audit.AuditRequired
import de.taktovia.platform.audit.ObjectRef
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.outbox.OutboxEvent
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcOperations
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Validierung, Vier-Augen-Freigabe, Publikation und Rollback (WP-M1-22, LH-I.7-CFG-006). Jeder Befehl ist **eine** Transaktion im Mandanten des Kontexts mit
 * Zeile, Audit und (Publikation) Outbox-Ereignis. Alle Befehle sperren die Revisionszeile (`FOR UPDATE`): gleichzeitige Befehle auf dieselbe Revision laufen nacheinander
 * und der zweite sieht die erhöhte Zeilenversion (412). Befehle, die ein Wirksamkeitsintervall betreffen (Publikation, Rücknahme), sperren zuvor den **Scope**
 * (`pg_advisory_xact_lock` auf Mandant, Art und Kennung): zwei Publikationen desselben Scopes laufen nacheinander, die zweite prüft gegen den Stand der ersten.
 * Die Reihenfolge ist immer Scope-Sperre, dann Zeilensperre (keine Verklemmung). Den Rest sichert die Datenbank (Exclusion Constraint `config_revision_published_no_overlap`,
 * Vier-Augen-CHECK, Wächter für publizierte Zeilen).
 */
internal class JdbcConfigPublication(
    private val ctxTx: ContextTransactions, private val revisions: JdbcConfigRevisions, private val catalogs: PolicyCatalogRepository, private val impact: ImpactAnalysis,
    private val audit: AuditPort, private val outbox: Outbox, private val validators: List<RevisionValidator>, private val clock: Clock,
) : ConfigPublication {
    private val log = LoggerFactory.getLogger(JdbcConfigPublication::class.java)

    // ---- Validierung --------------------------------------------------------------------------------------------------------------------------

    override fun validate(ctx: RequestContext, id: UUID, expectedRowVersion: Long): ValidationResult {
        requireScope(ctx, "config:write")
        return ctxTx.run(ctx) { j -> doValidate(j, ctx, id, expectedRowVersion) }
    }

    @AuditRequired
    private fun doValidate(j: JdbcOperations, ctx: RequestContext, id: UUID, expected: Long): ValidationResult {
        val row = lock(j, id)
        checkVersion(row, expected)
        requireState(row, RevisionState.DRAFT)
        val version = catalogs.currentVersion(j, clock.instant()) ?: throw ProblemException(ErrorCode.SERVICE_UNAVAILABLE, "Kein Parameterkatalog gültig.")
        val violations = check(j, ctx, revisions.view(j, row), version)
        val actor = FourEyes.refOf(ctx)
        if (violations.isNotEmpty()) {
            val auditId = audit.record(j, AuditEntry(actor, AuditActions.CONFIG_VALIDATED, ObjectRef("config_revision", id), ctx.correlationId, auditBase(row) + mapOf(
                "valid" to false, "violation_pointers" to violations.map { it.pointer }.take(50), "violation_codes" to violations.map { it.code.name }.distinct())))
            return ValidationResult(revisions.view(j, row), violations, auditId)
        }
        // Die Prüfung galt der jetzt gültigen Schema-Version: der Entwurf wird auf sie festgelegt (wie beim Ändern); die Datenbank prüft die Werte dabei noch einmal.
        val updated = j.query( // sql-lint: ok COLUMNS ist eine feste Konstante, Werte gebunden
            "UPDATE taktovia.config_revision r SET state = 'VALIDATED', schema_version = ?, validated_by = ?, validated_at = ? WHERE r.id = ? AND r.row_version = ? RETURNING ${JdbcConfigRevisions.COLUMNS}",
            revisions.ROW, version, actor.value, Timestamp.from(clock.instant()), id, expected,
        ).single()
        val auditId = audit.record(j, AuditEntry(actor, AuditActions.CONFIG_VALIDATED, ObjectRef("config_revision", id), ctx.correlationId, auditBase(updated) + mapOf(
            "valid" to true, "schema_version" to version, "reason" to updated.reason)))
        return ValidationResult(revisions.view(j, updated), emptyList(), auditId)
    }

    // ---- Freigabe -----------------------------------------------------------------------------------------------------------------------------

    override fun approve(ctx: RequestContext, id: UUID, expectedRowVersion: Long): ConfigRevisionView {
        requireScope(ctx, "config:publish")
        return ctxTx.run(ctx) { j -> doApprove(j, ctx, id, expectedRowVersion) }
    }

    @AuditRequired
    private fun doApprove(j: JdbcOperations, ctx: RequestContext, id: UUID, expected: Long): ConfigRevisionView {
        val row = lock(j, id)
        checkVersion(row, expected)
        requireState(row, RevisionState.VALIDATED)
        val reviewer = FourEyes.requireDistinctReviewer(ctx, row.createdBy)   // Prüfer ≠ Ersteller, nur Menschen; die Datenbank prüft es noch einmal
        val updated = j.query( // sql-lint: ok COLUMNS ist eine feste Konstante, Werte gebunden
            "UPDATE taktovia.config_revision r SET state = 'APPROVED', approved_by = ?, approved_at = ? WHERE r.id = ? AND r.row_version = ? RETURNING ${JdbcConfigRevisions.COLUMNS}",
            revisions.ROW, reviewer.value, Timestamp.from(clock.instant()), id, expected,
        ).single()
        audit.record(j, AuditEntry(reviewer, AuditActions.CONFIG_APPROVED, ObjectRef("config_revision", id), ctx.correlationId, auditBase(updated) + mapOf(
            "created_by" to updated.createdBy, "effective_from" to updated.effectiveFrom.toString(), "reason" to updated.reason)))
        return revisions.view(j, updated)
    }

    // ---- Publikation --------------------------------------------------------------------------------------------------------------------------

    override fun publish(ctx: RequestContext, id: UUID, expectedRowVersion: Long, cmd: PublishCommand): PublicationResult {
        requireScope(ctx, "config:publish")
        val violations = mutableListOf<Violation>()
        when {
            cmd.reason == null || cmd.reason.isBlank() -> violations += Violation("/reason", ViolationCode.REQUIRED)
            cmd.reason.trim().length < 3 -> violations += Violation("/reason", ViolationCode.TOO_SHORT)
            cmd.reason.trim().length > 1000 -> violations += Violation("/reason", ViolationCode.TOO_LONG)
        }
        if (violations.isNotEmpty()) throw ProblemException.invalid(*violations.toTypedArray())
        return ctxTx.run(ctx) { j -> doPublish(j, ctx, id, expectedRowVersion, cmd) }
    }

    @AuditRequired
    private fun doPublish(j: JdbcOperations, ctx: RequestContext, id: UUID, expected: Long, cmd: PublishCommand): PublicationResult {
        // Scope-Sperre vor der Zeilensperre: dafür den Scope zunächst ohne Sperre lesen.
        val head = j.query("SELECT r.scope_kind, r.scope_id FROM taktovia.config_revision r WHERE r.id = ?", { rs, _ -> rs.getString(1) to rs.getObject(2, UUID::class.java) }, id)
            .firstOrNull() ?: throw ProblemException.notFound()
        lockScope(j, ctx, head.first, head.second)
        val row = lock(j, id)
        checkVersion(row, expected)
        when (row.state) {
            RevisionState.APPROVED -> Unit
            RevisionState.PUBLISHED -> throw ProblemException(ErrorCode.INVALID_STATE_TRANSITION, "Die Revision ist bereits publiziert.")
            RevisionState.DRAFT -> throw ProblemException(ErrorCode.INVALID_STATE_TRANSITION, "Die Revision ist nicht validiert und nicht freigegeben.")
            RevisionState.VALIDATED -> throw ProblemException(ErrorCode.INVALID_STATE_TRANSITION, "Die Revision ist nicht durch eine zweite Person freigegeben.")
        }
        // Die Freigabe gilt für genau diesen Stand einschließlich Beginn; das Datum der Anfrage bestätigt ihn nur.
        val now = clock.instant()
        val dateViolations = mutableListOf<Violation>()
        if (cmd.effectiveFrom != row.effectiveFrom) dateViolations += Violation("/effective_from", ViolationCode.INVALID_VALUE, "Beginn weicht von der freigegebenen Revision ab")
        else if (cmd.effectiveFrom.isBefore(now.minus(PAST_TOLERANCE))) dateViolations += Violation("/effective_from", ViolationCode.INVALID_VALUE, "Beginn liegt in der Vergangenheit; rückwirkende Änderungen sind ausgeschlossen")
        if (dateViolations.isNotEmpty()) throw ProblemException.invalid(*dateViolations.toTypedArray())

        // Erneute Prüfung zum Publikationszeitpunkt (Schema-Version und Modellregeln können sich seit der Validierung geändert haben); unzulässige Sicherheitswerte blockieren hier.
        val view = revisions.view(j, row)
        val version = catalogs.currentVersion(j, now) ?: throw ProblemException(ErrorCode.SERVICE_UNAVAILABLE, "Kein Parameterkatalog gültig.")
        val found = check(j, ctx, view, version)
        if (found.isNotEmpty()) throw ProblemException.invalid(*found.toTypedArray())
        if (version != row.schemaVersion) throw ProblemException(ErrorCode.CONFLICT, "Der Parameterkatalog wurde seit der Validierung geändert; Revision erneut validieren.")

        // Wirkungsbericht zum unveränderten Revisionsstand (sperrt die Zeile zusätzlich FOR SHARE; WP-M1-23)
        val report = impact.confirmForPublication(j, ctx, id, cmd.impactReportId)

        // Zeitintervallprüfung unter der Scope-Sperre
        val to = row.effectiveTo
        val published = j.query(
            "SELECT r.id, r.effective_from, r.effective_to FROM taktovia.config_revision r WHERE r.scope_kind = ? AND r.scope_id = ? AND r.state = 'PUBLISHED' AND r.id <> ? ORDER BY r.effective_from",
            { rs, _ -> Span(rs.getObject(1, UUID::class.java), rs.getTimestamp(2).toInstant(), rs.getTimestamp(3)?.toInstant()) }, row.scopeKind.name, row.scopeId, id)
        // Überlappung: eine später (oder gleichzeitig) beginnende publizierte Revision, die in dieses Intervall hineinreicht, oder eine früher beginnende mit **festem** Ende nach
        // unserem Beginn. Nur die früher beginnende, **offene** Revision ist der Vorgänger: ihr Ende wird auf unseren Beginn gesetzt.
        val clash = published.firstOrNull { p ->
            if (!p.from.isBefore(row.effectiveFrom)) to == null || to.isAfter(p.from) else p.to != null && p.to.isAfter(row.effectiveFrom)
        }
        if (clash != null) throw ProblemException(ErrorCode.CONFLICT, "Für diesen Scope ist bereits eine Revision mit überlappendem Wirksamkeitsintervall publiziert; Stand neu lesen.")
        val predecessor = published.firstOrNull { it.from.isBefore(row.effectiveFrom) && it.to == null }
        // Nachfolger schließt das Vorgängerintervall: die einzige Änderung einer publizierten Zeile (Wächter der Datenbank prüft: Beginn dieser freigegebenen Revision).
        predecessor?.let {
            j.update("UPDATE taktovia.config_revision SET effective_to = ? WHERE id = ?", Timestamp.from(row.effectiveFrom), it.id)
        }
        val actor = FourEyes.refOf(ctx)
        val updated = j.query( // sql-lint: ok COLUMNS ist eine feste Konstante, Werte gebunden
            "UPDATE taktovia.config_revision r SET state = 'PUBLISHED', published_at = ?, published_by = ?, publication_reason = ? WHERE r.id = ? AND r.row_version = ? RETURNING ${JdbcConfigRevisions.COLUMNS}",
            revisions.ROW, Timestamp.from(now), actor.value, cmd.reason!!.trim(), id, expected,
        ).single()
        val auditId = audit.record(j, AuditEntry(actor, AuditActions.CONFIG_PUBLISHED, ObjectRef("config_revision", id), ctx.correlationId, auditBase(updated) + mapOf(
            "reason" to cmd.reason.trim(), "effective_from" to updated.effectiveFrom.toString(), "effective_to" to updated.effectiveTo?.toString(), "approved_by" to updated.approvedBy,
            "created_by" to updated.createdBy, "impact_report_id" to report.id.toString(), "impact_severity" to report.highestSeverity.name, "closed_predecessor_id" to predecessor?.id?.toString(),
            "rolled_back_from" to updated.rolledBackFrom?.toString())))
        val payload = mapOf(
            "revision_id" to id.toString(), "scope_kind" to updated.scopeKind.name, "scope_id" to updated.scopeId.toString(), "revision" to updated.revision, "state" to "PUBLISHED",
            "schema_version" to updated.schemaVersion, "effective_from" to updated.effectiveFrom.toString(), "effective_to" to updated.effectiveTo?.toString(),
            "audit_event_id" to auditId.toString(), "predecessor_id" to predecessor?.id?.toString(), "rolled_back_from" to updated.rolledBackFrom?.toString(),
        )
        outbox.append(j, OutboxEvent(id, updated.rowVersion, "config.revision_published", payload, now, correlationId = ctx.correlationId))
        if (updated.rolledBackFrom != null) outbox.append(j, OutboxEvent(id, updated.rowVersion, "config.revision_rolled_back", payload, now, correlationId = ctx.correlationId))
        return PublicationResult(revisions.view(j, updated), auditId, predecessor?.id)
    }

    // ---- Rollback -----------------------------------------------------------------------------------------------------------------------------

    override fun rollback(ctx: RequestContext, id: UUID, expectedRowVersion: Long, cmd: RollbackCommand): ConfigRevisionView {
        requireScope(ctx, "config:publish")
        val violations = mutableListOf<Violation>()
        when {
            cmd.reason == null || cmd.reason.isBlank() -> violations += Violation("/reason", ViolationCode.REQUIRED)
            cmd.reason.trim().length < 3 -> violations += Violation("/reason", ViolationCode.TOO_SHORT)
            cmd.reason.trim().length > 1000 -> violations += Violation("/reason", ViolationCode.TOO_LONG)
        }
        if (violations.isNotEmpty()) throw ProblemException.invalid(*violations.toTypedArray())
        return ctxTx.run(ctx) { j -> doRollback(j, ctx, id, expectedRowVersion, cmd) }
    }

    @AuditRequired
    private fun doRollback(j: JdbcOperations, ctx: RequestContext, id: UUID, expected: Long, cmd: RollbackCommand): ConfigRevisionView {
        val head = j.query("SELECT r.scope_kind, r.scope_id FROM taktovia.config_revision r WHERE r.id = ?", { rs, _ -> rs.getString(1) to rs.getObject(2, UUID::class.java) }, id)
            .firstOrNull() ?: throw ProblemException.notFound()
        lockScope(j, ctx, head.first, head.second)
        val faulty = lock(j, id)
        checkVersion(row = faulty, expected = expected)
        if (faulty.state != RevisionState.PUBLISHED) throw ProblemException(ErrorCode.INVALID_STATE_TRANSITION, "Nur eine publizierte Revision kann zurückgenommen werden.")
        // Zielrevision: die gewählte oder die Vorgängerrevision (höchste publizierte Revision desselben Scopes mit kleinerer Nummer); sie muss publiziert und im selben Scope sein.
        val target = if (cmd.targetRevisionId != null)
            j.query("SELECT ${JdbcConfigRevisions.COLUMNS} FROM taktovia.config_revision r WHERE r.id = ?", revisions.ROW, cmd.targetRevisionId).firstOrNull() // sql-lint: ok COLUMNS ist eine feste Konstante, Werte gebunden
                ?.takeIf { it.scopeKind == faulty.scopeKind && it.scopeId == faulty.scopeId && it.state == RevisionState.PUBLISHED && it.id != faulty.id }
                ?: throw ProblemException.invalid(Violation("/target_revision_id", ViolationCode.INVALID_VALUE, "Zielrevision unbekannt oder nicht publiziert im selben Scope"))
        else j.query( // sql-lint: ok COLUMNS ist eine feste Konstante, Werte gebunden
            "SELECT ${JdbcConfigRevisions.COLUMNS} FROM taktovia.config_revision r WHERE r.scope_kind = ? AND r.scope_id = ? AND r.state = 'PUBLISHED' AND r.revision < ? ORDER BY r.revision DESC LIMIT 1",
            revisions.ROW, faulty.scopeKind.name, faulty.scopeId, faulty.revision,
        ).firstOrNull() ?: throw ProblemException(ErrorCode.CONFLICT, "Es gibt keine frühere publizierte Revision, auf die zurückgegangen werden könnte.")
        val actor = FourEyes.refOf(ctx)
        val newId = UUID.randomUUID()
        val created = revisions.insertDraft(j, newId, faulty.scopeKind, faulty.scopeId, null, target.schemaVersion, target.values, cmd.effectiveFrom, null, actor.value,
            cmd.reason!!.trim(), rolledBackFrom = faulty.id)
        audit.record(j, AuditEntry(actor, AuditActions.CONFIG_REVERTED, ObjectRef("config_revision", newId), ctx.correlationId, mapOf(
            "scope_kind" to faulty.scopeKind.name, "scope_id" to faulty.scopeId.toString(), "revision" to created.revision, "rolled_back_from" to faulty.id.toString(),
            "rolled_back_revision" to faulty.revision, "restored_from" to target.id.toString(), "restored_revision" to target.revision, "reason" to cmd.reason.trim(),
            "effective_from" to cmd.effectiveFrom.toString())))
        return revisions.view(j, created)
    }

    // ---- gemeinsam ----------------------------------------------------------------------------------------------------------------------------

    /** Katalog-, Dienstkonfigurations- und Modulregeln; sammelt alle Verletzungen mit Pointer relativ zur Revision (`/values/...`). */
    private fun check(j: JdbcOperations, ctx: RequestContext, view: ConfigRevisionView, version: String): List<Violation> {
        val catalog = catalogs.load(j, version) ?: throw ProblemException(ErrorCode.SERVICE_UNAVAILABLE, "Parameterkatalog nicht ladbar.")
        val out = mutableListOf<Violation>()
        when (val o = ParameterValidator(catalog).validate(view.scopeKind, view.values)) {
            is ValidationOutcome.Valid -> Unit
            is ValidationOutcome.Invalid -> out += o.violations.map { it.copy(pointer = "/values" + it.pointer) }
        }
        if (view.scopeKind == ScopeKind.SERVICE && "service_config" !in view.values) out += Violation("/values/service_config", ViolationCode.REQUIRED)
        // Modulregeln nur auf gültigen Werten: sonst folgen Folgefehler aus bereits gemeldeten Verletzungen. Ein Fehler einer Regel verhindert die Freigabe (fail-closed, 503).
        if (out.isEmpty()) for (v in validators) {
            try { out += v.check(j, ctx, view) } catch (e: ProblemException) { throw e } catch (e: Exception) {
                log.warn("Revisionsregel {} ausgefallen: {}", v.name, e.javaClass.simpleName)
                throw ProblemException(ErrorCode.SERVICE_UNAVAILABLE, "Eine fachliche Prüfregel konnte nicht ausgeführt werden; keine Freigabe.", cause = e)
            }
        }
        return out
    }

    private fun requireScope(ctx: RequestContext, scope: String) {
        if (!ctx.hasScope(scope)) throw ProblemException(ErrorCode.FORBIDDEN, "Für diese Aktion ist das Recht $scope nötig.")
    }

    private fun lock(j: JdbcOperations, id: UUID): JdbcConfigRevisions.Row =
        j.query("SELECT ${JdbcConfigRevisions.COLUMNS} FROM taktovia.config_revision r WHERE r.id = ? FOR UPDATE", revisions.ROW, id).firstOrNull() ?: throw ProblemException.notFound() // sql-lint: ok COLUMNS ist eine feste Konstante, Werte gebunden

    private fun checkVersion(row: JdbcConfigRevisions.Row, expected: Long) {
        if (row.rowVersion != expected) throw ProblemException(ErrorCode.REVISION_MISMATCH, "Die Revision wurde zwischenzeitlich geändert; aktuelle Fassung lesen.")
    }

    private fun requireState(row: JdbcConfigRevisions.Row, state: RevisionState) {
        if (row.state == state) return
        if (row.state == RevisionState.PUBLISHED) throw ProblemException(ErrorCode.IMMUTABLE_RECORD, "Eine publizierte Revision ist unveränderlich.")
        throw ProblemException(ErrorCode.INVALID_STATE_TRANSITION, "Der Zustandsübergang ist nicht erlaubt.")
    }

    /** Sperrt den Scope bis zum Ende der Transaktion; der Schlüssel enthält den Mandanten (zwei Mandanten mit gleicher `scope_id` sperren sich nicht). */
    private fun lockScope(j: JdbcOperations, ctx: RequestContext, kind: String, scopeId: UUID) {
        j.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", { _, _ -> 1 }, "config_scope:${ctx.tenant.value}:$kind:$scopeId")
    }

    private fun auditBase(r: JdbcConfigRevisions.Row): Map<String, Any?> = mapOf(
        "scope_kind" to r.scopeKind.name, "scope_id" to r.scopeId.toString(), "revision" to r.revision, "row_version" to r.rowVersion, "state" to r.state.name)

    private class Span(val id: UUID, val from: Instant, val to: Instant?)

    internal companion object {
        /** Toleranz gegen Uhrabweichung und Laufzeit: ein Beginn „jetzt“ aus dem Client ist zulässig; ein früherer Beginn wäre rückwirkend (OP-19, Festlegung für den Bau). */
        val PAST_TOLERANCE: Duration = Duration.ofSeconds(60)
    }
}

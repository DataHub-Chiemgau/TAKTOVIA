package de.taktovia.fleetsafety.internal

import de.taktovia.fleetsafety.api.AmendmentKind
import de.taktovia.fleetsafety.api.AnswerInput
import de.taktovia.fleetsafety.api.CreateInspectionAmendment
import de.taktovia.fleetsafety.api.InspectionAmendmentView
import de.taktovia.fleetsafety.api.SaveInspectionDraft
import de.taktovia.fleetsafety.api.ChecklistAnswer
import de.taktovia.fleetsafety.api.ChecklistAnswerStatus
import de.taktovia.fleetsafety.api.ChecklistAnswerValidator
import de.taktovia.fleetsafety.api.ChecklistTemplates
import de.taktovia.fleetsafety.api.InspectionOutcome
import de.taktovia.fleetsafety.api.InspectionOutcomeRule
import de.taktovia.fleetsafety.api.IssueRelease
import de.taktovia.fleetsafety.api.ReleaseBlocker
import de.taktovia.fleetsafety.api.ReleaseOutcome
import de.taktovia.fleetsafety.api.SubmitInspection
import de.taktovia.fleetsafety.api.InspectionAnswerView
import de.taktovia.fleetsafety.api.InspectionCatalog
import de.taktovia.fleetsafety.api.InspectionReleaseView
import de.taktovia.fleetsafety.api.InspectionTemplateView
import de.taktovia.fleetsafety.api.InspectionView
import de.taktovia.fleetsafety.api.Inspections
import de.taktovia.fleetsafety.api.StartInspection
import de.taktovia.fleetsafety.api.StartedInspection
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
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Prüflauf-Start (WP-M3-13, siehe [Inspections]). Eine Transaktion je Aufruf. Vorlagenwahl und Anlage laufen unter einer Lesesperre (`FOR SHARE`) auf den gewählten Revisionszeilen:
 * eine gleichzeitige Freigabe (Ablösung per `UPDATE`) wartet bis zum Ende des Starts oder wird vom Start gesehen; der Lauf hält immer genau eine vollständige, unveränderliche
 * Revision. Doppelstarts entscheidet der Teil-Unique-Index `inspection_one_open` über `ON CONFLICT DO NOTHING`, ohne die Transaktion zu verlieren.
 */
internal class JdbcInspections(
    private val ctxTx: ContextTransactions, private val audit: AuditPort, private val outbox: Outbox, private val checklists: ChecklistTemplates, private val clock: Clock,
    private val readiness: JdbcReadiness,
) : Inspections {

    private sealed interface Attempt {
        class Done(val result: StartedInspection) : Attempt
        class Unavailable(val reason: String) : Attempt
    }

    override fun start(ctx: RequestContext, cmd: StartInspection): StartedInspection {
        if (InspectionRoles.STARTERS.none { it in ctx.roles }) throw ProblemException(ErrorCode.FORBIDDEN, "Einen Prüflauf starten Fahrer und Werkstatt.")
        return when (val a = ctxTx.run(ctx) { j -> doStart(j, ctx, cmd) }) {
            is Attempt.Done -> a.result
            is Attempt.Unavailable -> throw ProblemException(ErrorCode.CONFLICT,
                (if (a.reason == REASON_AMBIGUOUS) InspectionCatalog.TEMPLATE_AMBIGUOUS else InspectionCatalog.TEMPLATE_UNAVAILABLE) +
                    ": Für dieses Fahrzeug und diesen Dienst gibt es keine eindeutig freigegebene Prüfvorlage; die Leitstelle wurde informiert, die Einsatzfreigabe bleibt aus.")
        }
    }

    @AuditRequired
    private fun doStart(j: JdbcOperations, ctx: RequestContext, cmd: StartInspection): Attempt {
        val now = clock.instant()
        if (cmd.clientStartedAt.isAfter(now.plusSeconds(InspectionCatalog.FUTURE_TOLERANCE_SECONDS))) throw ProblemException.invalid(Violation("/client_started_at", ViolationCode.INVALID_VALUE))
        // Nur die eigene Schicht (fremd, fremder Mandant oder nicht zugewiesen: 404 ohne Hinweis auf die Existenz); Lesesperre gegen gleichzeitige Änderungen der Schicht
        val shift = j.query(
            "SELECT s.id, s.vehicle_id, s.driver_ref, s.state, s.ends_at FROM taktovia.staff_shift s WHERE s.id = ? AND $OWN_SHIFT FOR SHARE OF s",
            { rs, _ -> Shift(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getObject(3, UUID::class.java), rs.getString(4), rs.getTimestamp(5).toInstant()) },
            cmd.shiftId, *ShiftScope.args(ctx.actor.issuer, ctx.actor.subject),
        ).firstOrNull() ?: throw ProblemException.notFound()
        if (cmd.vehicleId != shift.vehicleId) throw ProblemException.invalid(Violation("/vehicle_id", ViolationCode.INVALID_VALUE))
        // „nach Anmeldung“: nur eine angetretene, noch laufende Schicht (WP-M3-25: Zustand STARTED)
        if (shift.state != "STARTED" || !now.isBefore(shift.endsAt))
            throw ProblemException(ErrorCode.INVALID_STATE_TRANSITION, "Ein Prüflauf beginnt erst nach der Dienstanmeldung und nur innerhalb der laufenden Schicht.")
        openRun(j, shift.id, shift.vehicleId, cmd.kind)?.let { return Attempt.Done(StartedInspection(load(j, ctx, it)!!, created = false)) }

        // Vorlagenwahl allein durch den Server: Fahrzeugklasse und Bedienart des Fahrzeugs -> genau eine freigegebene Revision (kein Clientwert fließt ein)
        val pair = j.query("SELECT vehicle_type, operation_kind FROM taktovia.vehicle WHERE id = ?", { rs, _ -> rs.getString(1) to rs.getString(2) }, shift.vehicleId).firstOrNull() ?: throw ProblemException.notFound()
        val candidates: List<UUID> = pair.first?.let { cls -> selectPublished(j, cls, pair.second) } ?: emptyList()
        if (candidates.size != 1) {
            val reason = if (candidates.isEmpty()) REASON_NONE else REASON_AMBIGUOUS
            notify(j, ctx, shift, pair, reason, candidates.size, now)
            return Attempt.Unavailable(reason)
        }

        val id = UUID.randomUUID()
        val inserted = j.update(
            "INSERT INTO taktovia.inspection_run (tenant_id, id, shift_id, vehicle_id, driver_ref, template_revision_id, kind, started_at, created_by) " +
                "VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (tenant_id, shift_id, vehicle_id, kind) WHERE status = 'IN_PROGRESS' DO NOTHING",
            id, shift.id, shift.vehicleId, shift.driverRef, candidates.single(), cmd.kind, Timestamp.from(cmd.clientStartedAt), actor(ctx).value,
        )
        if (inserted == 0) {   // gleichzeitiger Doppelstart (anderes Gerät, anderer Schlüssel): der andere hat gewonnen, dessen Lauf wird geliefert
            val existing = openRun(j, shift.id, shift.vehicleId, cmd.kind) ?: throw ProblemException(ErrorCode.CONFLICT, "Der Prüflauf konnte nicht angelegt werden; Start wiederholen.")
            return Attempt.Done(StartedInspection(load(j, ctx, existing)!!, created = false))
        }
        audit.record(j, AuditEntry(actor(ctx), AuditActions.INSPECTION_STARTED, ObjectRef("inspection", id), ctx.correlationId,
            mapOf("shift_id" to shift.id.toString(), "vehicle_id" to shift.vehicleId.toString(), "kind" to cmd.kind, "template_revision_id" to candidates.single().toString())))
        return Attempt.Done(StartedInspection(load(j, ctx, id)!!, created = true))
    }

    /** Hinweis an die Leitstelle: Audit und Ereignis in derselben Transaktion; ohne Freitext, ohne Personen. */
    private fun notify(j: JdbcOperations, ctx: RequestContext, shift: Shift, pair: Pair<String?, String?>, reason: String, count: Int, now: Instant) {
        audit.record(j, AuditEntry(actor(ctx), AuditActions.INSPECTION_TEMPLATE_UNAVAILABLE, ObjectRef("staff_shift", shift.id), ctx.correlationId,
            mapOf("vehicle_id" to shift.vehicleId.toString(), "reason" to reason, "candidate_count" to count)))
        outbox.append(j, OutboxEvent(UUID.randomUUID(), 1, "inspection.template_unavailable", linkedMapOf(
            "shift_id" to shift.id.toString(), "vehicle_id" to shift.vehicleId.toString(), "vehicle_class" to pair.first, "service_mode" to pair.second,
            "reason" to reason, "candidate_count" to count), now, correlationId = ctx.correlationId))
    }

    /**
     * Freigegebene Revisionen für das Paar unter Lesesperre. Wartet eine gleichzeitige Freigabe (Ablösung der alten, Veröffentlichung der neuen Revision in einer Transaktion), liefert die
     * erste Abfrage nach deren Ende leer, weil ihr Stichtag vor der neuen Revision lag; deshalb wird bei leerem Ergebnis mit neuem Stichtag wiederholt (höchstens [SELECT_ATTEMPTS] Versuche).
     */
    private fun selectPublished(j: JdbcOperations, vehicleClass: String, serviceMode: String?): List<UUID> {
        repeat(SELECT_ATTEMPTS) {
            val found = j.query("SELECT r.id FROM taktovia.checklist_template_revision r WHERE r.state = 'PUBLISHED' AND r.vehicle_class = ? AND r.service_mode = ? ORDER BY r.id FOR SHARE OF r",
                { rs, _ -> rs.getObject(1, UUID::class.java) }, vehicleClass, serviceMode)
            if (found.isNotEmpty()) return found
        }
        return emptyList()
    }

    private fun openRun(j: JdbcOperations, shiftId: UUID, vehicleId: UUID, kind: String): UUID? =
        j.query("SELECT id FROM taktovia.inspection_run WHERE shift_id = ? AND vehicle_id = ? AND kind = ? AND status = 'IN_PROGRESS'", { rs, _ -> rs.getObject(1, UUID::class.java) }, shiftId, vehicleId, kind).firstOrNull()

    // ------------------------------------------------------------------------------------------------ Abschluss (WP-M3-14)

    override fun submit(ctx: RequestContext, id: UUID, expectedRevision: Long, cmd: SubmitInspection): InspectionView {
        if (InspectionRoles.STARTERS.none { it in ctx.roles }) throw ProblemException(ErrorCode.FORBIDDEN, "Einen Prüflauf schließen Fahrer und Werkstatt ab.")
        try {
            return ctxTx.run(ctx) { j -> doSubmit(j, ctx, id, expectedRevision, cmd) }
        } catch (e: DataIntegrityViolationException) {
            // Altschäden, die erst nach der Vorprüfung entstanden sind: der Wächter der Datenbank hält den Abschluss auf
            if (generateSequence<Throwable>(e) { it.cause }.any { it.message?.contains("KNOWN_DEFECTS_UNACKNOWLEDGED") == true })
                throw ProblemException(ErrorCode.CONFLICT, "KNOWN_DEFECTS_UNACKNOWLEDGED: Offene Altschäden des Fahrzeugs sind nicht quittiert.", cause = e)
            throw e
        }
    }

    @AuditRequired
    private fun doSubmit(j: JdbcOperations, ctx: RequestContext, id: UUID, expected: Long, cmd: SubmitInspection): InspectionView {
        val now = clock.instant()
        if (cmd.clientSubmittedAt.isAfter(now.plusSeconds(InspectionCatalog.FUTURE_TOLERANCE_SECONDS))) throw ProblemException.invalid(Violation("/client_submitted_at", ViolationCode.INVALID_VALUE))
        // Zeilensperre des Laufs: zwei gleichzeitige Abschlüsse werden nacheinander entschieden, der zweite sieht den Abschluss. Fahrer nur eigener Schichten, sonst 404.
        val run = lockRun(j, ctx, id) ?: throw ProblemException.notFound()
        if (run.status != "IN_PROGRESS") throw ProblemException(
            if (run.status == "COMPLETED") ErrorCode.IMMUTABLE_RECORD else ErrorCode.INVALID_STATE_TRANSITION,
            if (run.status == "COMPLETED") "Der Prüfnachweis ist abgeschlossen und unveränderlich; Korrekturen sind Nachträge." else "Der Prüflauf ist nicht mehr offen (${run.status}).")
        if (run.revision != expected) throw ProblemException(ErrorCode.REVISION_MISMATCH, "Der Prüflauf wurde zwischenzeitlich geändert; aktuelle Revision lesen.")
        // Sperrreihenfolge: Lauf, dann Fahrzeugzeile (die Freigabe und die Sperre desselben Fahrzeugs entscheiden sich nacheinander)
        val safetyRevision = j.query("SELECT safety_revision FROM taktovia.vehicle WHERE id = ? FOR UPDATE", { rs, _ -> rs.getLong(1) }, run.vehicleId).firstOrNull() ?: throw ProblemException.notFound()

        val steps = checklists.steps(j, run.templateRevisionId)
        val stepIds = j.query("SELECT step_code, id FROM taktovia.checklist_step WHERE template_revision_id = ?", { rs, _ -> rs.getString(1) to rs.getObject(2, UUID::class.java) }, run.templateRevisionId).toMap()
        // Entwurf und Abschlussantworten zusammen: die Abschlussantwort gewinnt, ein gespeicherter Entwurf geht nie still verloren
        val drafts = j.query("SELECT step_code, answer, number_value, note, answered_at FROM taktovia.inspection_answer WHERE inspection_id = ?",
            { rs, _ -> AnswerInput(rs.getString(1), rs.getString(2), rs.getBigDecimal(3), rs.getString(4), rs.getTimestamp(5).toInstant()) }, id)
        val submitted = cmd.answers.map { it.stepCode }.toSet()
        val merged = drafts.filter { it.stepCode !in submitted } + cmd.answers
        val violations = ChecklistAnswerValidator.validate(steps.map { it.rule() }, merged.map { ChecklistAnswer(it.stepCode, it.answer, it.numberValue, it.note) }, complete = true).toMutableList()
        // Mangel ohne dokumentierte Beschreibung (CHK-006): Verweis auf den Punkt
        merged.filter { it.answer == ChecklistAnswerStatus.DEFECT && it.note.isNullOrBlank() && violations.none { v -> v.pointer == "/answers/${it.stepCode}/note" } }
            .forEach { violations += Violation("/answers/${it.stepCode}/note", ViolationCode.REQUIRED) }
        if (violations.isNotEmpty()) throw ProblemException.invalid(*violations.toTypedArray())
        // Altschäden quittiert (WP-M3-21, DMG-012): Vorprüfung mit klarer Fehlermeldung, die Datenbank hält es zusätzlich
        if (run.kind == "PRE_SHIFT" || run.kind == "VEHICLE_CHANGE") {
            val open = j.queryForObject("SELECT count(*) FROM taktovia.inspection_known_defects(taktovia.current_tenant(), ?) k WHERE NOT EXISTS (SELECT 1 FROM taktovia.known_defect_acknowledgement a WHERE a.inspection_id = ? AND a.defect_id = k)", Int::class.java, id, id)!!
            if (open > 0) throw ProblemException(ErrorCode.CONFLICT, "KNOWN_DEFECTS_UNACKNOWLEDGED: Offene Altschäden des Fahrzeugs sind nicht quittiert.")
        }
        val outcome = InspectionOutcomeRule.evaluate(steps, merged)
        val actor = actor(ctx)
        for (a in cmd.answers) {
            j.update(
                "INSERT INTO taktovia.inspection_answer (tenant_id, id, inspection_id, step_code, step_id, answer, number_value, note, answered_at, created_by) VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                    "ON CONFLICT (tenant_id, inspection_id, step_code) DO UPDATE SET step_id = EXCLUDED.step_id, answer = EXCLUDED.answer, number_value = EXCLUDED.number_value, note = EXCLUDED.note, " +
                    "answered_at = EXCLUDED.answered_at, revision = taktovia.inspection_answer.revision + 1",
                UUID.randomUUID(), id, a.stepCode, stepIds[a.stepCode], a.answer, a.numberValue, a.note?.trim()?.takeIf { it.isNotEmpty() }, Timestamp.from(a.answeredAt), actor.value,
            )
        }
        // Ergebnis, Zeitstempel (Gerät: completed_at, Server: received_at) und Prüfer in einem Update; Abschluss ist ab hier unveränderlich (Wächter)
        val updated = j.update(
            "UPDATE taktovia.inspection_run SET status = 'COMPLETED', outcome = ?, completed_at = ?, received_at = clock_timestamp(), safety_revision = ?, completed_by = ?, revision = revision + 1 WHERE id = ? AND revision = ? AND status = 'IN_PROGRESS'",
            outcome.name, Timestamp.from(cmd.clientSubmittedAt), safetyRevision, actor.value, id, expected,
        )
        if (updated != 1) throw ProblemException(ErrorCode.REVISION_MISMATCH, "Der Prüflauf wurde zwischenzeitlich geändert; aktuelle Revision lesen.")
        // Einsatzfreigabe im selben Commit, nur bei bestandener Prüfung (ein kritischer Mangel erteilt keine); fehlende Voraussetzungen bleiben als Liste der Antwort sichtbar
        var releaseId: UUID? = null
        if (outcome == InspectionOutcome.PASSED || outcome == InspectionOutcome.PASSED_RESTRICTED) {
            val r = readiness.issueReleaseIn(j, actor, ctx.correlationId, IssueRelease(run.vehicleId, run.shiftId, id))
            if (r is ReleaseOutcome.Issued) releaseId = r.release.id
        }
        val defectSteps = merged.count { it.answer == ChecklistAnswerStatus.DEFECT }
        audit.record(j, AuditEntry(actor, AuditActions.INSPECTION_COMPLETED, ObjectRef("inspection", id), ctx.correlationId, linkedMapOf(
            "shift_id" to run.shiftId.toString(), "vehicle_id" to run.vehicleId.toString(), "kind" to run.kind, "template_revision_id" to run.templateRevisionId.toString(),
            "outcome" to outcome.name, "answer_count" to merged.size, "defect_step_count" to defectSteps, "release_id" to releaseId?.toString(), "safety_revision" to safetyRevision)))
        outbox.append(j, OutboxEvent(id, expected + 1, "inspection.completed", linkedMapOf(
            "inspection_id" to id.toString(), "vehicle_id" to run.vehicleId.toString(), "shift_id" to run.shiftId.toString(), "kind" to run.kind, "outcome" to outcome.name,
            "defect_step_count" to defectSteps, "template_revision_id" to run.templateRevisionId.toString()), now, correlationId = ctx.correlationId))
        return load(j, ctx, id)!!
    }

    private class Locked(val shiftId: UUID, val vehicleId: UUID, val templateRevisionId: UUID, val kind: String, val status: String, val revision: Long, val startedAt: Instant)

    // ------------------------------------------------------------------------------------------------ Entwurf und Nachträge (WP-M3-15)

    override fun saveDraft(ctx: RequestContext, id: UUID, expectedRevision: Long, cmd: SaveInspectionDraft): InspectionView {
        if (InspectionRoles.STARTERS.none { it in ctx.roles }) throw ProblemException(ErrorCode.FORBIDDEN, "Den Entwurf einer Prüfung speichern Fahrer und Werkstatt.")
        return ctxTx.run(ctx) { j -> doSaveDraft(j, ctx, id, expectedRevision, cmd) }
    }

    private fun doSaveDraft(j: JdbcOperations, ctx: RequestContext, id: UUID, expected: Long, cmd: SaveInspectionDraft): InspectionView {
        val run = lockRun(j, ctx, id) ?: throw ProblemException.notFound()
        if (run.status != "IN_PROGRESS") throw ProblemException(
            if (run.status == "COMPLETED") ErrorCode.IMMUTABLE_RECORD else ErrorCode.INVALID_STATE_TRANSITION,
            if (run.status == "COMPLETED") "Der Prüfnachweis ist abgeschlossen; eine Änderung ist nur als Nachtrag möglich." else "Der Prüflauf ist nicht mehr offen (${run.status}).")
        if (run.revision != expected) throw ProblemException(ErrorCode.REVISION_MISMATCH, "Der Prüflauf wurde zwischenzeitlich geändert; aktuelle Revision lesen.")
        val steps = checklists.steps(j, run.templateRevisionId)
        ChecklistAnswerValidator.require(steps.map { it.rule() }, cmd.answers.map { ChecklistAnswer(it.stepCode, it.answer, it.numberValue, it.note) }, complete = false)
        val stepIds = j.query("SELECT step_code, id FROM taktovia.checklist_step WHERE template_revision_id = ?", { rs, _ -> rs.getString(1) to rs.getObject(2, UUID::class.java) }, run.templateRevisionId).toMap()
        val actor = actor(ctx)
        for (a in cmd.answers) {
            j.update(
                "INSERT INTO taktovia.inspection_answer (tenant_id, id, inspection_id, step_code, step_id, answer, number_value, note, answered_at, created_by) VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                    "ON CONFLICT (tenant_id, inspection_id, step_code) DO UPDATE SET step_id = EXCLUDED.step_id, answer = EXCLUDED.answer, number_value = EXCLUDED.number_value, note = EXCLUDED.note, " +
                    "answered_at = EXCLUDED.answered_at, revision = taktovia.inspection_answer.revision + 1",
                UUID.randomUUID(), id, a.stepCode, stepIds[a.stepCode], a.answer, a.numberValue, a.note?.trim()?.takeIf { it.isNotEmpty() }, Timestamp.from(a.answeredAt), actor.value,
            )
        }
        if (j.update("UPDATE taktovia.inspection_run SET revision = revision + 1 WHERE id = ? AND revision = ? AND status = 'IN_PROGRESS'", id, expected) != 1)
            throw ProblemException(ErrorCode.REVISION_MISMATCH, "Der Prüflauf wurde zwischenzeitlich geändert; aktuelle Revision lesen.")
        return load(j, ctx, id)!!
    }

    override fun amend(ctx: RequestContext, id: UUID, cmd: CreateInspectionAmendment): InspectionAmendmentView {
        if (InspectionRoles.AMENDERS.none { it in ctx.roles }) throw ProblemException(ErrorCode.FORBIDDEN, "Einen Nachtrag zu einer Prüfung erfassen Werkstatt und Betriebsleitung.")
        return ctxTx.run(ctx) { j -> doAmend(j, ctx, id, cmd) }
    }

    @AuditRequired
    private fun doAmend(j: JdbcOperations, ctx: RequestContext, id: UUID, cmd: CreateInspectionAmendment): InspectionAmendmentView {
        val run = j.query("SELECT shift_id, vehicle_id, template_revision_id, kind, status, revision, started_at FROM taktovia.inspection_run WHERE id = ?",
            { rs, _ -> Locked(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getObject(3, UUID::class.java), rs.getString(4), rs.getString(5), rs.getLong(6), rs.getTimestamp(7).toInstant()) }, id).firstOrNull()
            ?: throw ProblemException.notFound()
        if (run.status != "COMPLETED") throw ProblemException(ErrorCode.INVALID_STATE_TRANSITION, "Ein Nachtrag setzt einen abgeschlossenen Prüflauf voraus; vorher wird der Entwurf bearbeitet.")
        val steps = checklists.steps(j, run.templateRevisionId)
        if (steps.none { it.code == cmd.stepCode }) throw ProblemException.invalid(Violation("/step_code", ViolationCode.NOT_IN_ENUM))
        val violations = ChecklistAnswerValidator.validate(steps.filter { it.code == cmd.stepCode }.map { it.rule() }, listOf(ChecklistAnswer(cmd.stepCode, cmd.newAnswer, cmd.newNumberValue, cmd.newNote)), complete = false).toMutableList()
        if (cmd.newAnswer == ChecklistAnswerStatus.DEFECT && cmd.newNote.isNullOrBlank() && violations.none { it.pointer.endsWith("/note") }) violations += Violation("/answers/${cmd.stepCode}/note", ViolationCode.REQUIRED)
        if (violations.isNotEmpty()) throw ProblemException.invalid(*violations.map { Violation(amendPointer(it.pointer, cmd.stepCode), it.code) }.toTypedArray())
        // Originalantwort sperren: gleichzeitige Nachträge zur selben Antwort werden nacheinander versioniert (1, 2, …), nie doppelt
        val answerId = j.query("SELECT id FROM taktovia.inspection_answer WHERE inspection_id = ? AND step_code = ? FOR UPDATE", { rs, _ -> rs.getObject(1, UUID::class.java) }, id, cmd.stepCode).firstOrNull()
            ?: throw ProblemException.invalid(Violation("/step_code", ViolationCode.INVALID_VALUE))
        val version = j.queryForObject("SELECT coalesce(max(version), 0) + 1 FROM taktovia.inspection_amendment WHERE answer_id = ?", Int::class.java, answerId)!!
        val amendmentId = UUID.randomUUID(); val actor = actor(ctx)
        j.update(
            "INSERT INTO taktovia.inspection_amendment (tenant_id, id, inspection_id, step_code, answer_id, version, kind, new_answer, new_number_value, new_note, amended_by, reason) VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            amendmentId, id, cmd.stepCode, answerId, version, cmd.kind.name, cmd.newAnswer, cmd.newNumberValue, cmd.newNote, actor.value, cmd.reason,
        )
        audit.record(j, AuditEntry(actor, AuditActions.INSPECTION_AMENDED, ObjectRef("inspection", id), ctx.correlationId, linkedMapOf(
            "amendment_id" to amendmentId.toString(), "step_code" to cmd.stepCode, "version" to version, "kind" to cmd.kind.name, "new_answer" to cmd.newAnswer)))
        return amendments(j, id).single { it.id == amendmentId }
    }

    /** Verweis des Antwortvalidators (`/answers/<code>/…`) auf die Felder des Nachtrags. */
    private fun amendPointer(p: String, code: String) = when (p.removePrefix("/answers/$code")) {
        "/answer" -> "/new_answer"; "/note" -> "/new_note"; "/number_value" -> "/new_number_value"; else -> "/step_code"
    }

    private fun amendments(j: JdbcOperations, id: UUID): List<InspectionAmendmentView> = j.query(
        "SELECT id, inspection_id, step_code, answer_id, version, kind, new_answer, new_number_value, new_note, reason, amended_by, created_at FROM taktovia.inspection_amendment WHERE inspection_id = ? ORDER BY step_code, version",
        { rs, _ -> InspectionAmendmentView(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getString(3), rs.getObject(4, UUID::class.java), rs.getInt(5), AmendmentKind.valueOf(rs.getString(6)),
            rs.getString(7), rs.getBigDecimal(8), rs.getString(9), rs.getString(10), rs.getString(11), rs.getTimestamp(12).toInstant()) }, id)

    private fun lockRun(j: JdbcOperations, ctx: RequestContext, id: UUID): Locked? {
        val mapper = RowMapper { rs, _ -> Locked(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getObject(3, UUID::class.java), rs.getString(4), rs.getString(5), rs.getLong(6), rs.getTimestamp(7).toInstant()) }
        return (if (InspectionRoles.driverOnly(ctx))
            j.query("SELECT i.shift_id, i.vehicle_id, i.template_revision_id, i.kind, i.status, i.revision, i.started_at FROM taktovia.inspection_run i WHERE i.id = ? AND EXISTS (SELECT 1 FROM taktovia.staff_shift s WHERE s.id = i.shift_id AND $OWN_SHIFT) FOR UPDATE OF i",
                mapper, id, *ShiftScope.args(ctx.actor.issuer, ctx.actor.subject))
        else j.query("SELECT i.shift_id, i.vehicle_id, i.template_revision_id, i.kind, i.status, i.revision, i.started_at FROM taktovia.inspection_run i WHERE i.id = ? FOR UPDATE OF i", mapper, id)).firstOrNull()
    }

    override fun get(ctx: RequestContext, id: UUID): InspectionView = ctxTx.run(ctx, readOnly = true) { j -> load(j, ctx, id) ?: throw ProblemException.notFound() }

    private fun load(j: JdbcOperations, ctx: RequestContext, id: UUID): InspectionView? {
        val driverOnly = InspectionRoles.driverOnly(ctx)
        val mapper = RowMapper { rs, _ ->
            Row(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getObject(3, UUID::class.java), rs.getObject(4, UUID::class.java), rs.getObject(5, UUID::class.java),
                rs.getString(6), rs.getString(7), rs.getString(8), rs.getTimestamp(9).toInstant(), rs.getTimestamp(10)?.toInstant(), rs.getTimestamp(11)?.toInstant(), rs.getLong(12), rs.getString(13))
        }
        // Fahrer sehen nur Läufe eigener Schichten (sonst 404); Disposition und Werkstatt alle Läufe des Mandanten
        val row = (if (driverOnly) j.query("SELECT $RUN_COLUMNS FROM taktovia.inspection_run i WHERE i.id = ? AND EXISTS (SELECT 1 FROM taktovia.staff_shift s WHERE s.id = i.shift_id AND $OWN_SHIFT)",
            mapper, id, *ShiftScope.args(ctx.actor.issuer, ctx.actor.subject))
        else j.query("SELECT $RUN_COLUMNS FROM taktovia.inspection_run i WHERE i.id = ?", mapper, id)).firstOrNull() ?: return null
        val tpl = j.query("SELECT template_id, version, vehicle_class, service_mode FROM taktovia.checklist_template_revision WHERE id = ?",
            { rs, _ -> InspectionTemplateView(row.revisionId, rs.getObject(1, UUID::class.java), rs.getInt(2), rs.getString(3), rs.getString(4), emptyList()) }, row.revisionId).single()
            .let { it.copy(steps = checklists.steps(j, row.revisionId)) }
        val answers = j.query("SELECT step_code, answer, number_value, note, answered_at FROM taktovia.inspection_answer WHERE inspection_id = ? ORDER BY answered_at, step_code",
            { rs, _ -> InspectionAnswerView(rs.getString(1), rs.getString(2), rs.getBigDecimal(3), rs.getString(4), rs.getTimestamp(5).toInstant()) }, id)
        val release = j.query("SELECT id, valid_until, safety_revision FROM taktovia.resource_release WHERE inspection_ref = ? AND revoked_at IS NULL ORDER BY valid_until DESC LIMIT 1",
            { rs, _ -> InspectionReleaseView(rs.getObject(1, UUID::class.java), rs.getTimestamp(2).toInstant(), rs.getLong(3)) }, id).firstOrNull()
        val missing: List<ReleaseBlocker> = when {
            release != null -> emptyList()
            row.status == "IN_PROGRESS" -> listOf(ReleaseBlocker.INSPECTION_OPEN)
            else -> readiness.missing(j, row.vehicleId, row.shiftId, row.id).ifEmpty { listOf(ReleaseBlocker.NO_INSPECTION) }
        }
        return InspectionView(row.id, row.shiftId, row.vehicleId, row.driverRef, row.revisionId, row.kind, row.status, row.outcome, row.startedAt, row.completedAt, row.receivedAt, row.revision, tpl, answers, release, row.completedBy, amendments(j, row.id), missing)
    }

    private class Shift(val id: UUID, val vehicleId: UUID, val driverRef: UUID, val state: String, val endsAt: Instant)
    private class Row(val id: UUID, val shiftId: UUID, val vehicleId: UUID, val driverRef: UUID, val revisionId: UUID, val kind: String, val status: String, val outcome: String?,
                      val startedAt: Instant, val completedAt: Instant?, val receivedAt: Instant?, val revision: Long, val completedBy: String?)

    private fun actor(ctx: RequestContext) = ActorRef((if (ctx.actor.type == ActorType.SERVICE) "service:" else "user:") + ctx.actor.subject)

    private companion object {
        const val OWN_SHIFT = ShiftScope.OWN
        const val RUN_COLUMNS = "i.id, i.shift_id, i.vehicle_id, i.driver_ref, i.template_revision_id, i.kind, i.status, i.outcome, i.started_at, i.completed_at, i.received_at, i.revision, i.completed_by"
        const val SELECT_ATTEMPTS = 3
        const val REASON_NONE = "NO_TEMPLATE"
        const val REASON_AMBIGUOUS = "AMBIGUOUS_TEMPLATE"
    }
}

/** Rollen des Prüflaufs (Rechtematrix `createInspection`: Fahrer, Werkstatt; `getInspection` zusätzlich Disposition). */
internal object InspectionRoles {
    val STARTERS = setOf("driver", "workshop")
    /** Nachträge zu abgeschlossenen Prüfungen (Planungsannahme zu CHK-014: die berechtigte Rolle ist nicht festgelegt): Werkstatt und Betriebsleitung, nie der Fahrer. */
    val AMENDERS = setOf("workshop", "tenant_admin")
    /** Ein Konto, das nur als Fahrer auftritt, sieht nur Läufe eigener Schichten. */
    fun driverOnly(ctx: RequestContext) = "driver" in ctx.roles && ctx.roles.none { it == "dispatcher" || it == "workshop" || it == "tenant_admin" }
}

/** „Eigene Schicht“ des Fahrers: `driver_ref` ist die Fahrerkennung (Fahrerstamm, Mitgliedschaft des Kontos) oder, für ältere Zuweisungen, direkt die IdP-Kennung (wie in `staff-time`, nur lesend). */
internal object ShiftScope {
    const val OWN = "(s.driver_ref::text = lower(?) OR EXISTS (SELECT 1 FROM taktovia.driver dr JOIN taktovia.membership ms ON ms.tenant_id = dr.tenant_id AND ms.id = dr.membership_id " +
        "WHERE dr.tenant_id = s.tenant_id AND dr.id = s.driver_ref AND ms.issuer = ? AND ms.subject = ? AND ms.revoked_at IS NULL))"
    fun args(issuer: String, subject: String): Array<Any> = arrayOf(subject, issuer, subject)
}

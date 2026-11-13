package de.taktovia.fleetsafety.internal

import de.taktovia.fleetsafety.api.ClassificationEntry
import de.taktovia.fleetsafety.api.DefectCatalog
import de.taktovia.fleetsafety.api.DefectClassification
import de.taktovia.fleetsafety.api.DefectSettings
import de.taktovia.fleetsafety.api.DefectSeverities
import de.taktovia.fleetsafety.api.TimelineEvent
import de.taktovia.fleetsafety.api.TimelineKind
import de.taktovia.fleetsafety.api.TriageDefect
import de.taktovia.fleetsafety.api.TriageResult
import de.taktovia.fleetsafety.api.DefectAmendment
import de.taktovia.fleetsafety.api.DefectFilter
import de.taktovia.fleetsafety.api.DefectRevision
import de.taktovia.fleetsafety.api.DefectStateAt
import de.taktovia.fleetsafety.api.DefectState
import de.taktovia.fleetsafety.api.DefectStateMachine
import de.taktovia.fleetsafety.api.DefectTransition
import de.taktovia.fleetsafety.api.StatusHistoryEntry
import de.taktovia.fleetsafety.api.DefectView
import de.taktovia.fleetsafety.api.Defects
import de.taktovia.fleetsafety.api.DriverVehicleRelation
import de.taktovia.fleetsafety.api.EvidenceView
import de.taktovia.fleetsafety.api.ReportDefect
import de.taktovia.fleetsafety.api.ReportedDefect
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
import tools.jackson.databind.json.JsonMapper
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Rollen, die Schäden melden dürfen (Rechtematrix `createDefect`: Fahrer, Disposition, Werkstatt); die Rechtematrix im Anfragefilter ist die erste, diese Prüfung die zweite Schicht. */
internal object DefectRoles {
    val REPORTERS = setOf("driver", "dispatcher", "workshop")
    /** Prüfer („berechtigte Prüfer“, OP-34): Werkstatt und Betriebsleitung lesen die Historie, auch ersetzte Anhänge. */
    val REVIEWERS = setOf("workshop", "tenant_admin")
    /** Ein Konto, das nur als Fahrer auftritt, sieht und meldet im Rahmen seines Bezugs und seiner eigenen Meldungen (DMG-011). */
    fun driverOnly(ctx: RequestContext) = "driver" in ctx.roles && ctx.roles.none { it == "dispatcher" || it == "workshop" || it == "tenant_admin" }
    fun reporterRole(ctx: RequestContext): String? = listOf("dispatcher", "workshop", "driver").firstOrNull { it in ctx.roles }
}

/** Standardumsetzung des Fahrerbezugs: Schicht des Fahrers für das Fahrzeug (siehe [DriverVehicleRelation]); nur lesend. */
internal class ShiftDriverRelation : DriverVehicleRelation {
    override fun hasRelation(jdbc: JdbcOperations, driverSubject: String, vehicleId: UUID, now: Instant): Boolean =
        jdbc.queryForObject(
            "SELECT count(*) FROM taktovia.staff_shift WHERE vehicle_id = ? AND driver_ref::text = lower(?) AND upper(state) NOT IN ('CANCELLED', 'CANCELED') " +
                "AND starts_at <= ? AND ends_at >= ?", Int::class.java, vehicleId, driverSubject,
            Timestamp.from(now.plus(Duration.ofHours(DefectCatalog.DRIVER_PRE_SHIFT_HOURS))), Timestamp.from(now.minus(Duration.ofHours(DefectCatalog.DRIVER_POST_SHIFT_HOURS))),
        )!! > 0
}

/** Schadenakte (WP-M3-43). Siehe [Defects]. */
internal class JdbcDefects(
    private val ctxTx: ContextTransactions, private val audit: AuditPort, private val outbox: Outbox, private val clock: Clock, private val relation: DriverVehicleRelation,
    private val evidence: JdbcDefectEvidence, private val blocker: CriticalDefectBlocker, private val settings: DefectSettings = DefectSettings(),
) : Defects {

    override fun report(ctx: RequestContext, cmd: ReportDefect): ReportedDefect {
        val actor = actor(ctx)
        val role = DefectRoles.reporterRole(ctx) ?: throw ProblemException(ErrorCode.FORBIDDEN, "Schäden melden Fahrer, Disposition und Werkstatt.")
        val subject = reporter(ctx)
        try {
            return ctxTx.run(ctx) { j -> doReport(j, ctx, actor, subject, role, cmd) }
        } catch (e: DataIntegrityViolationException) {
            // Gleichzeitige Wiederholung derselben Geräteereignis-ID: die zweite Transaktion scheitert an UNIQUE und liefert die erste Akte
            if (sqlState(e) == "23505" && cmd.deviceEventId != null) {
                val existing = ctxTx.run(ctx, readOnly = true) { j -> findByDeviceEvent(j, ctx, cmd.deviceEventId) } ?: throw e
                return ReportedDefect(existing, created = false)
            }
            if (sqlState(e) == "23514") throw ProblemException.invalid(Violation("/occurred_at", ViolationCode.INVALID_VALUE))   // Ereigniszeit in der Zukunft (Trigger)
            throw e
        }
    }

    @AuditRequired
    private fun doReport(j: JdbcOperations, ctx: RequestContext, actor: ActorRef, subject: UUID, role: String, cmd: ReportDefect): ReportedDefect {
        val now = clock.instant()
        if (cmd.occurredAt.isAfter(now.plusSeconds(DefectCatalog.FUTURE_TOLERANCE_SECONDS))) throw ProblemException.invalid(Violation("/occurred_at", ViolationCode.INVALID_VALUE))
        // Wiederholung (Gerät, Offline-Synchronisation): dieselbe Akte, nur für denselben Meldenden
        if (cmd.deviceEventId != null) findByDeviceEvent(j, ctx, cmd.deviceEventId)?.let {
            if (it.reportedBy != subject || it.vehicleId != cmd.vehicleId) throw ProblemException(ErrorCode.IDEMPOTENCY_CONFLICT, "Die Geräteereignis-ID gehört zu einer anderen Meldung.")
            return ReportedDefect(it, created = false)
        }
        // Fahrzeug im eigenen Mandanten (RLS): fremd oder unbekannt = 404; ein Fahrer braucht zusätzlich den Bezug, ohne Hinweis auf die Existenz
        if (j.queryForObject("SELECT count(*) FROM taktovia.vehicle WHERE id = ?", Int::class.java, cmd.vehicleId) != 1) throw ProblemException.notFound()
        if (DefectRoles.driverOnly(ctx) && !relation.hasRelation(j, ctx.actor.subject, cmd.vehicleId, now)) throw ProblemException.notFound()
        // Bekannter Schaden: Fahrer sehen offene Fälle des Fahrzeugs vor der Neuanlage (CHK-012); ohne ausdrückliche Bestätigung wird nicht still neu angelegt
        if (DefectRoles.driverOnly(ctx) && !cmd.confirmNewDespiteKnown) {
            val open = j.query("SELECT case_number FROM taktovia.defect_case WHERE vehicle_id = ? AND status NOT IN ('CLOSED', 'DISMISSED') ORDER BY case_number LIMIT 5", { rs, _ -> rs.getString(1) }, cmd.vehicleId)
            if (open.isNotEmpty()) throw ProblemException(ErrorCode.CONFLICT, "${de.taktovia.fleetsafety.api.KnownDefectCatalog.OPEN_DEFECTS_PENDING}: Für dieses Fahrzeug sind offene Fälle bekannt (${open.joinToString(", ")}); " +
                "sie sind vor der Neuanlage anzuzeigen, die Neuanlage ist mit confirm_new_despite_known=true möglich.")
        }
        cmd.sourceInspectionId?.let {
            if (j.queryForObject("SELECT count(*) FROM taktovia.inspection_run WHERE id = ? AND vehicle_id = ?", Int::class.java, it, cmd.vehicleId) != 1)
                throw ProblemException.invalid(Violation("/source_inspection_id", ViolationCode.INVALID_VALUE))
        }
        val id = UUID.randomUUID()
        val rule = DefectClassification.classify(cmd.categoryCode, cmd.kind, cmd.vehicleArea, cmd.observedEffect)
        // ein Hinweis des Meldenden kann die Klasse nur anheben (im Zweifel sicher), nie senken; die Regelmatrix bleibt maßgeblich
        val hinted = cmd.severityHint?.takeIf { DefectSeverities.rank(it) > DefectSeverities.rank(rule.severity) }
        val severity = hinted ?: rule.severity; val source = if (hinted != null) "REPORTER_HINT" else "RULE"
        val view = insertCase(j, id, actor, subject, cmd, severity, source)
        afterInsert(j, id, view, actor, cmd, ctx.correlationId, severity, rule.ruleId, source)
        audit.record(j, AuditEntry(actor, AuditActions.DEFECT_REPORTED, ObjectRef("defect", id), ctx.correlationId, buildMap {
            put("case_number", view.caseNumber); put("vehicle_id", cmd.vehicleId.toString()); put("category_code", cmd.categoryCode); put("reporter_role", role)
            put("capture_mode", cmd.captureMode.name); put("evidence_count", cmd.evidenceIds.size); cmd.severityHint?.let { put("severity_hint", it) }
        }))
        outbox.append(j, OutboxEvent(id, view.revision, "defect.reported",
            mapOf("defect_id" to id.toString(), "case_number" to view.caseNumber, "vehicle_id" to cmd.vehicleId.toString(), "category_code" to cmd.categoryCode, "reporter_role" to role,
                "evidence_ids" to cmd.evidenceIds.map { it.toString() }),
            clock.instant(), correlationId = ctx.correlationId))
        return ReportedDefect(loadOrNull(j, ctx, id)!!, created = true)
    }

    private fun insertCase(j: JdbcOperations, id: UUID, actor: ActorRef, subject: UUID, cmd: ReportDefect, severity: String, severitySource: String): DefectView =
        j.query(
            "INSERT INTO taktovia.defect_case AS d (tenant_id, id, case_number, vehicle_id, reported_by, occurred_at, location_kind, location_value, category_code, kind, vehicle_area, observed_effect, " +
                "description, source_inspection_id, source_step_code, device_event_id, created_by, severity, severity_rule_version, severity_source, classified_at, classified_by) " +
                "VALUES (taktovia.current_tenant(), ?, taktovia.next_defect_case_number(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, clock_timestamp(), ?) RETURNING $COLUMNS",
            MAPPER, id, cmd.vehicleId, subject, Timestamp.from(cmd.occurredAt), cmd.locationKind, cmd.locationValue, cmd.categoryCode, cmd.kind, cmd.vehicleArea, cmd.observedEffect,
            cmd.description, cmd.sourceInspectionId, cmd.sourceStepCode, cmd.deviceEventId, actor.value, severity, DefectClassification.VERSION, severitySource, actor.value,
        ).single()

    /** Erweiterungspunkt der folgenden Arbeitspakete (Statushistorie, Revision 1, Evidenzanbindung) innerhalb derselben Transaktion. */
    private fun afterInsert(j: JdbcOperations, id: UUID, view: DefectView, actor: ActorRef, cmd: ReportDefect, correlationId: UUID, severity: String, ruleId: String, source: String) {
        j.update("INSERT INTO taktovia.defect_status_history (tenant_id, id, defect_id, from_status, to_status, actor, reason, revision) VALUES (taktovia.current_tenant(), ?, ?, NULL, 'NEW', ?, NULL, ?)",
            UUID.randomUUID(), id, actor.value, view.revision)
        insertRevision(j, id, 1, actor, "Erstmeldung", view)
        evidence.attach(j, id, cmd.evidenceIds, actor, 1)
        // Erstklassifikation (Regelmatrix mit Regelversion) und, bei sperrender Klasse, Sofortsperre mit Alarm und Störungsablauf im selben Commit (WP-M3-45/-46)
        val eventId = UUID.randomUUID()
        insertClassification(j, eventId, id, null, severity, ruleId, source, null, null, null, actor, view.revision)
        if (settings.blocks(severity)) blocker.apply(j, actor, correlationId, view, severity, eventId)
    }

    private fun insertClassification(j: JdbcOperations, eventId: UUID, defectId: UUID, from: String?, to: String, ruleId: String?, source: String, reason: String?, assigneeBefore: UUID?, assigneeAfter: UUID?, actor: ActorRef, revision: Long) {
        j.update("INSERT INTO taktovia.defect_classification_history (tenant_id, id, defect_id, from_severity, to_severity, rule_version, rule_id, source, reason, assignee_before, assignee_after, actor, defect_revision) " +
            "VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", eventId, defectId, from, to, DefectClassification.VERSION, ruleId, source, reason, assigneeBefore, assigneeAfter, actor.value, revision)
    }

    override fun get(ctx: RequestContext, id: UUID): DefectView = ctxTx.run(ctx, readOnly = true) { j ->
        val v = loadOrNull(j, ctx, id) ?: throw ProblemException.notFound()
        if (DefectRoles.driverOnly(ctx) && v.reportedBy.toString() != ctx.actor.subject.lowercase()) throw ProblemException.notFound()
        if (!inServiceScope(j, ctx, v.vehicleId)) throw ProblemException.notFound()
        v
    }

    /** Dienstscope (WP-M3-49): ein Fahrzeug ohne Dienstzuordnung gilt nur mit der Mandantengrenze, sonst muss ein Dienst im Scope des Aufrufers liegen (fail-closed, wie die Dienstscope-Prüfung der Plattform). */
    private fun inServiceScope(j: JdbcOperations, ctx: RequestContext, vehicleId: UUID): Boolean {
        val scope = de.taktovia.platform.authz.ServiceScopeAuthorizer.effectiveServices(ctx, null, tenantWide = false).orEmpty().joinToString(",", "{", "}")
        return j.queryForObject("SELECT (NOT EXISTS (SELECT 1 FROM taktovia.vehicle_pool_assignment pa WHERE pa.vehicle_id = ?) " +
            "OR EXISTS (SELECT 1 FROM taktovia.vehicle_pool_assignment pa WHERE pa.vehicle_id = ? AND pa.service_id = ANY (CAST(? AS uuid[]))))", Boolean::class.java, vehicleId, vehicleId, scope)!!
    }

    override fun list(ctx: RequestContext, filter: DefectFilter, limit: Int, after: Pair<Instant, UUID>?): List<DefectView> = ctxTx.run(ctx, readOnly = true) { j ->
        val own = if (DefectRoles.driverOnly(ctx)) reporter(ctx) else null
        val afterTs = after?.first?.let(Timestamp::from)
        enrich(j, ctx, j.query(
            "SELECT $COLUMNS FROM taktovia.defect_case d WHERE (CAST(? AS uuid) IS NULL OR d.vehicle_id = CAST(? AS uuid)) AND (CAST(? AS text) IS NULL OR d.status = CAST(? AS text)) " +
                "AND (CAST(? AS text) IS NULL OR d.severity = CAST(? AS text)) AND (CAST(? AS timestamptz) IS NULL OR d.reported_at >= CAST(? AS timestamptz)) " +
                "AND (CAST(? AS timestamptz) IS NULL OR d.reported_at < CAST(? AS timestamptz)) AND (CAST(? AS uuid) IS NULL OR d.reported_by = CAST(? AS uuid)) " +
                "AND (CAST(? AS timestamptz) IS NULL OR (d.created_at, d.id) > (CAST(? AS timestamptz), CAST(? AS uuid))) ORDER BY d.created_at, d.id LIMIT ?",
            MAPPER, filter.vehicleId, filter.vehicleId, filter.status, filter.status, filter.severity, filter.severity,
            filter.reportedFrom?.let(Timestamp::from), filter.reportedFrom?.let(Timestamp::from), filter.reportedTo?.let(Timestamp::from), filter.reportedTo?.let(Timestamp::from),
            own, own, afterTs, afterTs, after?.second, limit + 1,
        ))
    }

    override fun listPage(ctx: RequestContext, query: de.taktovia.fleetsafety.api.DefectListQuery): de.taktovia.fleetsafety.api.DefectListPage = ctxTx.run(ctx, readOnly = true) { j ->
        JdbcDefectList.run(j, ctx, query, clock.instant(), if (DefectRoles.driverOnly(ctx)) reporter(ctx) else null)
    }

    // ---------------- Statusautomat (WP-M3-47) ----------------

    override fun transition(ctx: RequestContext, id: UUID, expectedRevision: Long, cmd: DefectTransition): DefectView {
        val actor = actor(ctx)
        try {
            return ctxTx.run(ctx) { j -> doTransition(j, ctx, actor, id, expectedRevision, cmd) }
        } catch (e: DataIntegrityViolationException) {
            // zweite Schicht: der Datenbanktrigger weist einen Übergang außerhalb der Tabelle ab, auch wenn die Anwendung ihn je zuließe
            if (sqlState(e) == "23514") throw ProblemException(ErrorCode.INVALID_STATE_TRANSITION, "Der Übergang ist für diese Akte nicht zulässig.", cause = e)
            throw e
        }
    }

    @AuditRequired
    private fun doTransition(j: JdbcOperations, ctx: RequestContext, actor: ActorRef, id: UUID, expected: Long, cmd: DefectTransition): DefectView {
        if (ctx.roles.none { it in DefectStateMachine.TRANSITIONERS }) throw ProblemException(ErrorCode.FORBIDDEN, "Statusübergänge löst nur Disposition, Werkstatt oder Betriebsleitung aus.")
        val before = j.query("SELECT $COLUMNS FROM taktovia.defect_case d WHERE d.id = ? FOR UPDATE", MAPPER, id).firstOrNull() ?: throw ProblemException.notFound()
        if (before.revision != expected) throw ProblemException(ErrorCode.REVISION_MISMATCH, "Die Akte wurde zwischenzeitlich geändert; aktuelle Revision lesen.")
        val from = DefectState.parse(before.status) ?: throw IllegalStateException("unbekannter Status ${before.status}")
        val to = cmd.to
        val reason = cmd.reason?.trim()?.takeIf { it.isNotEmpty() }   // eine leere Begründung ist keine
        if (cmd.reopen) {
            if (!from.isFinal || to != DefectState.REVIEWED) throw invalid(from, to, "Eine Wiedereröffnung gilt nur aus einem Endzustand nach REVIEWED.")
            if (ctx.roles.none { it in DefectStateMachine.REOPEN_ROLES }) throw ProblemException(ErrorCode.FORBIDDEN, "Wiedereröffnen darf nur die Werkstatt oder die Betriebsleitung.")
            if (reason == null) throw ProblemException.invalid(Violation("/reason", ViolationCode.REQUIRED))
        } else {
            if (from.isFinal) throw invalid(from, to, "Aus einem Endzustand führt nur die dokumentierte Wiedereröffnung heraus.")
            if (!DefectStateMachine.MACHINE.allowed(from, to)) throw invalid(from, to, "Der Übergang steht nicht in der Übergangstabelle.")
            if (ctx.roles.none { it in DefectStateMachine.EDGE_ROLES.getValue(from to to) }) throw ProblemException(ErrorCode.FORBIDDEN, "Für diesen Übergang fehlt die Rolle.")
            if (to == DefectState.DISMISSED && reason == null) throw ProblemException.invalid(Violation("/reason", ViolationCode.REQUIRED))
            preconditions(j, id, from, to, before.severity)
        }
        applyStatus(j, actor, ctx.correlationId, before, to, reason, cmd.reopen)
        return loadOrNull(j, ctx, id)!!
    }

    /** Führt den (bereits geprüften) Statuswechsel aus: Zustand, Revision, `dismissed_reason`, Statushistorie, Audit und Ereignis in der laufenden Transaktion unter der Zeilensperre der Akte. */
    @AuditRequired
    internal fun applyStatus(j: JdbcOperations, actor: ActorRef, correlationId: UUID, before: DefectView, to: DefectState, reason: String?, reopen: Boolean): DefectView {
        val id = before.id; val from = DefectState.parse(before.status)!!
        val after = j.query(
            "UPDATE taktovia.defect_case AS d SET status = ?, revision = d.revision + 1, updated_at = clock_timestamp(), reopen_count = d.reopen_count + ?, " +
                "dismissed_reason = CASE WHEN ? = 'DISMISSED' THEN ? ELSE d.dismissed_reason END WHERE d.id = ? AND d.revision = ? RETURNING $COLUMNS",
            MAPPER, to.name, if (reopen) 1 else 0, to.name, reason, id, before.revision,
        ).singleOrNull() ?: throw ProblemException(ErrorCode.REVISION_MISMATCH, "Die Akte wurde zwischenzeitlich geändert; aktuelle Revision lesen.")
        j.update("INSERT INTO taktovia.defect_status_history (tenant_id, id, defect_id, from_status, to_status, actor, reason, revision, is_reopening) VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?, ?, ?)",
            UUID.randomUUID(), id, from.name, to.name, actor.value, reason, after.revision, reopen)
        audit.record(j, AuditEntry(actor, AuditActions.DEFECT_STATE_CHANGED, ObjectRef("defect", id), correlationId,
            mapOf("case_number" to after.caseNumber, "from" to from.name, "to" to to.name, "revision" to after.revision, "reopened" to reopen)))
        outbox.append(j, OutboxEvent(id, after.revision, "defect.state_changed",
            mapOf("defect_id" to id.toString(), "case_number" to after.caseNumber, "vehicle_id" to after.vehicleId.toString(), "from_status" to from.name, "to_status" to to.name,
                "revision" to after.revision, "reopened" to reopen), clock.instant(), correlationId = correlationId))
        return after
    }

    /**
     * Vorbedingungen je Übergang (Detailvertrag): Abschluss ohne Reparatur nur nicht sicherheitskritisch und klassifiziert; Reparatur nur mit Reparaturauftrag; Abschluss nach Reparatur nur mit
     * Reparaturnachweis und, bei `SAFETY_CRITICAL`, mit Freigabeentscheidung; Rückkehr aus der Reparatur nur nach Stornierung aller Aufträge. Alle Tabellen gehören `fleet-safety`.
     */
    private fun preconditions(j: JdbcOperations, id: UUID, from: DefectState, to: DefectState, severity: String?) {
        when {
            to == DefectState.CLOSED && from == DefectState.REVIEWED && severity !in setOf("COSMETIC", "OTHER") ->
                throw invalid(from, to, "Ohne Reparatur wird nur ein als COSMETIC oder OTHER klassifizierter Fall abgeschlossen.")
            to == DefectState.CLOSED && from == DefectState.IN_PROGRESS && (severity == null || severity == "SAFETY_CRITICAL") ->
                throw invalid(from, to, "Ohne Reparatur wird nur ein klassifizierter, nicht sicherheitskritischer Fall abgeschlossen.")
            to == DefectState.REPAIR && j.queryForObject(ACTIVE_ORDERS, Int::class.java, id) == 0 ->
                throw invalid(from, to, "Für die Reparatur fehlt ein Reparaturauftrag.")
            to == DefectState.IN_PROGRESS && from == DefectState.REPAIR && j.queryForObject(ACTIVE_ORDERS, Int::class.java, id)!! > 0 ->
                throw invalid(from, to, "Es besteht noch ein nicht stornierter Reparaturauftrag.")
            to == DefectState.CLOSED && from == DefectState.REPAIR && j.queryForObject(PROVEN_ORDERS, Int::class.java, id) == 0 ->
                throw invalid(from, to, "Der Reparaturnachweis fehlt.")
            to == DefectState.CLOSED && from == DefectState.REPAIR && severity == "SAFETY_CRITICAL" && j.queryForObject(RELEASE_DECISIONS, Int::class.java, id) == 0 ->
                throw invalid(from, to, "Ein sicherheitskritischer Fall wird nur mit Freigabeentscheidung abgeschlossen.")
        }
    }

    private fun invalid(from: DefectState, to: DefectState, why: String) = ProblemException(ErrorCode.INVALID_STATE_TRANSITION, "Der Übergang von ${from.name} nach ${to.name} ist nicht zulässig: $why")

    override fun statusHistory(ctx: RequestContext, id: UUID): List<StatusHistoryEntry> {
        if (ctx.roles.none { it in DefectStateMachine.TRANSITIONERS }) throw ProblemException(ErrorCode.FORBIDDEN, "Die Statushistorie sehen Disposition, Werkstatt und Betriebsleitung.")
        return ctxTx.run(ctx, readOnly = true) { j ->
            if (j.queryForObject("SELECT count(*) FROM taktovia.defect_case WHERE id = ?", Int::class.java, id) != 1) throw ProblemException.notFound()
            j.query("SELECT h.id, h.defect_id, h.from_status, h.to_status, h.actor, h.reason, h.revision, h.is_reopening, h.created_at FROM taktovia.defect_status_history h WHERE h.defect_id = ? ORDER BY h.created_at, h.id", { rs, _ ->
                StatusHistoryEntry(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getLong(7), rs.getBoolean(8), rs.getTimestamp(9).toInstant())
            }, id)
        }
    }


    // ---------------- Klassifikation und Triage (WP-M3-45) ----------------

    override fun triage(ctx: RequestContext, id: UUID, expectedRevision: Long, cmd: TriageDefect): TriageResult {
        if (ctx.roles.none { it in TRIAGERS }) throw ProblemException(ErrorCode.FORBIDDEN, "Die Triage führen Disposition und Werkstatt durch.")
        val actor = actor(ctx)
        try {
            return ctxTx.run(ctx) { j -> doTriage(j, ctx, actor, id, expectedRevision, cmd) }
        } catch (e: DataIntegrityViolationException) {
            if (sqlState(e) == "23514") throw ProblemException(ErrorCode.INVALID_STATE_TRANSITION, "Die Triage ist für diese Akte nicht zulässig.", cause = e)
            throw e
        }
    }

    @AuditRequired
    private fun doTriage(j: JdbcOperations, ctx: RequestContext, actor: ActorRef, id: UUID, expected: Long, cmd: TriageDefect): TriageResult {
        // Sperrreihenfolge: erst die Akte, danach (im Sperrkern) das Fahrzeug; der Disposition-Commit sperrt nie eine Akte
        val before = j.query("SELECT $COLUMNS FROM taktovia.defect_case d WHERE d.id = ? FOR UPDATE", MAPPER, id).firstOrNull() ?: throw ProblemException.notFound()
        if (before.revision != expected) throw ProblemException(ErrorCode.REVISION_MISMATCH, "Die Akte wurde zwischenzeitlich geändert; aktuelle Revision lesen.")
        val from = DefectState.parse(before.status) ?: throw IllegalStateException("unbekannter Status ${before.status}")
        if (from !in TRIAGEABLE) throw ProblemException(ErrorCode.INVALID_STATE_TRANSITION, "Eine Akte im Status ${from.name} wird nicht mehr triagiert.")
        val reason = cmd.reason?.trim()?.takeIf { it.isNotEmpty() }
        val previous = before.severity
        val changed = previous != cmd.severity
        // Korrektur nur mit Grund; Herabstufung von sicherheitskritisch nur durch die Werkstatt (technische Prüfung); Verwerfen nur durch die Werkstatt mit Grund
        if (previous != null && changed && reason == null) throw ProblemException.invalid(Violation("/reason", ViolationCode.REQUIRED))
        if (previous != null && DefectSeverities.rank(cmd.severity) < DefectSeverities.rank(previous) && previous == DefectSeverities.SAFETY_CRITICAL && "workshop" !in ctx.roles)
            throw ProblemException(ErrorCode.FORBIDDEN, "Eine sicherheitskritische Einstufung stuft nur die Werkstatt herab.")
        if (cmd.decision == "DISMISSED") {
            if ("workshop" !in ctx.roles) throw ProblemException(ErrorCode.FORBIDDEN, "Verwerfen darf nur die Werkstatt.")
            if (reason == null) throw ProblemException.invalid(Violation("/reason", ViolationCode.REQUIRED))
        }
        val assignee = if (cmd.assigneeGiven) cmd.assignee else before.assigneeRef
        val rule = DefectClassification.classify(before.categoryCode, before.kind, before.vehicleArea, before.observedEffect)
        val ruleVersion = before.severityRuleVersion ?: DefectClassification.VERSION
        var current = j.query(
            "UPDATE taktovia.defect_case AS d SET severity = ?, severity_rule_version = ?, severity_source = ?, classified_at = clock_timestamp(), classified_by = ?, assignee_ref = ?, " +
                "revision = d.revision + 1, updated_at = clock_timestamp() WHERE d.id = ? AND d.revision = ? RETURNING $COLUMNS",
            MAPPER, cmd.severity, ruleVersion, if (previous == null || changed) "TRIAGE" else (before.severitySource ?: "TRIAGE"), actor.value, assignee, id, expected,
        ).singleOrNull() ?: throw ProblemException(ErrorCode.REVISION_MISMATCH, "Die Akte wurde zwischenzeitlich geändert; aktuelle Revision lesen.")
        val eventId = UUID.randomUUID()
        insertClassification(j, eventId, id, previous, cmd.severity, rule.ruleId, "TRIAGE", reason, before.assigneeRef, assignee, actor, current.revision)
        // Folgestatus: NEW -> REVIEWED (-> IN_PROGRESS), REVIEWED -> IN_PROGRESS, DISMISSED; ohne Statuswechsel bleibt der Status (Bestätigung oder Korrektur)
        val target = DefectState.parse(cmd.decision)!!
        val path = when {
            target == from -> emptyList()
            target == DefectState.REVIEWED && from == DefectState.NEW -> listOf(DefectState.REVIEWED)
            target == DefectState.IN_PROGRESS && from == DefectState.NEW -> listOf(DefectState.REVIEWED, DefectState.IN_PROGRESS)
            target == DefectState.IN_PROGRESS && from == DefectState.REVIEWED -> listOf(DefectState.IN_PROGRESS)
            target == DefectState.DISMISSED -> listOf(DefectState.DISMISSED)
            else -> throw ProblemException(ErrorCode.INVALID_STATE_TRANSITION, "Der Übergang von ${from.name} nach ${target.name} ist durch die Triage nicht zulässig.")
        }
        var step = from
        for (next in path) {
            current = applyStatus(j, actor, ctx.correlationId, current, next, if (next == DefectState.DISMISSED) reason else null, reopen = false)
            step = next
        }
        // sperrende Einstufung (Hochstufung auf eine sperrende Klasse; eine Bestätigung löst nichts erneut aus): Sperre, Alarm, Störungsablauf im selben Commit
        var block: de.taktovia.fleetsafety.api.BlockOutcome? = null; var alert: de.taktovia.platform.alert.BusinessAlertResult? = null
        if (settings.blocks(cmd.severity) && (previous == null || !settings.blocks(previous))) {
            val out = blocker.apply(j, actor, ctx.correlationId, current, cmd.severity, eventId)
            block = out.block; alert = out.alert
        }
        audit.record(j, AuditEntry(actor, AuditActions.DEFECT_TRIAGED, ObjectRef("defect", id), ctx.correlationId, mapOf(
            "case_number" to current.caseNumber, "from_severity" to previous, "to_severity" to cmd.severity, "decision" to cmd.decision, "status" to step.name,
            "assignee_changed" to (assignee != before.assigneeRef), "reason_given" to (reason != null), "blocked" to (block != null), "revision" to current.revision)))
        outbox.append(j, OutboxEvent(id, current.revision, "defect.triaged",
            mapOf("defect_id" to id.toString(), "vehicle_id" to current.vehicleId.toString(), "severity" to cmd.severity, "blocks_vehicle" to settings.blocks(cmd.severity)),
            clock.instant(), correlationId = ctx.correlationId))
        return TriageResult(loadOrNull(j, ctx, id)!!, changed || previous == null, block, alert)
    }

    override fun classificationHistory(ctx: RequestContext, id: UUID): List<ClassificationEntry> {
        requireHistoryReader(ctx)
        return ctxTx.run(ctx, readOnly = true) { j ->
            if (j.queryForObject("SELECT count(*) FROM taktovia.defect_case WHERE id = ?", Int::class.java, id) != 1) throw ProblemException.notFound()
            j.query("SELECT id, defect_id, from_severity, to_severity, rule_version, rule_id, source, reason, assignee_before, assignee_after, actor, defect_revision, created_at " +
                "FROM taktovia.defect_classification_history WHERE defect_id = ? ORDER BY created_at, id", { rs, _ ->
                ClassificationEntry(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                    rs.getObject(9, UUID::class.java), rs.getObject(10, UUID::class.java), rs.getString(11), rs.getLong(12), rs.getTimestamp(13).toInstant())
            }, id)
        }
    }

    override fun timeline(ctx: RequestContext, id: UUID): List<TimelineEvent> {
        requireHistoryReader(ctx)
        return ctxTx.run(ctx, readOnly = true) { j ->
            val caseNumber = j.query("SELECT case_number FROM taktovia.defect_case WHERE id = ?", { rs, _ -> rs.getString(1) }, id).firstOrNull() ?: throw ProblemException.notFound()
            val out = mutableListOf<TimelineEvent>()
            j.query("SELECT from_status, to_status, actor, reason, created_at FROM taktovia.defect_status_history WHERE defect_id = ?", { rs, _ ->
                out += TimelineEvent(TimelineKind.STATUS, rs.getTimestamp(5).toInstant(), rs.getString(3), (rs.getString(1) ?: "-") + ">" + rs.getString(2), rs.getString(4))
            }, id)
            j.query("SELECT from_severity, to_severity, assignee_before, assignee_after, actor, reason, created_at FROM taktovia.defect_classification_history WHERE defect_id = ?", { rs, _ ->
                val at = rs.getTimestamp(7).toInstant(); val actor = rs.getString(5)
                out += TimelineEvent(TimelineKind.CLASSIFICATION, at, actor, (rs.getString(1) ?: "-") + ">" + rs.getString(2), rs.getString(6))
                val b = rs.getObject(3, UUID::class.java); val a = rs.getObject(4, UUID::class.java)
                if (b != a) out += TimelineEvent(TimelineKind.ASSIGNMENT, at, actor, "${b ?: "-"}>${a ?: "-"}", null)
            }, id)
            j.query("SELECT source, reason_code, set_by, set_at FROM taktovia.vehicle_block WHERE reason_ref = ? AND source = 'SAFETY' AND reason_code = 'DEFECT_CRITICAL'", { rs, _ ->
                out += TimelineEvent(TimelineKind.VEHICLE_BLOCK, rs.getTimestamp(4).toInstant(), rs.getString(3), rs.getString(1) + "|" + rs.getString(2), null)
            }, caseNumber)
            out.sortedWith(compareBy<TimelineEvent> { it.occurredAt }.thenBy { it.kind.ordinal })
        }
    }

    private fun requireHistoryReader(ctx: RequestContext) {
        if (ctx.roles.none { it in DefectStateMachine.TRANSITIONERS }) throw ProblemException(ErrorCode.FORBIDDEN, "Die Aktenhistorie sehen Disposition, Werkstatt und Betriebsleitung.")
    }

    // ---------------- Unveränderliche Historie (WP-M3-48) ----------------

    private val json = JsonMapper.builder().build()

    /** Inhalt einer Fassung der Meldung als Schlüssel-Wert-Abbild (nur Codes, Zeiten und begrenzter Freitext). */
    private fun contentOf(v: DefectView): Map<String, Any?> = linkedMapOf(
        "occurred_at" to v.occurredAt.toString(), "location_kind" to v.locationKind, "location_value" to v.locationValue, "category_code" to v.categoryCode, "kind" to v.kind,
        "vehicle_area" to v.vehicleArea, "observed_effect" to v.observedEffect, "description" to v.description, "source_inspection_id" to v.sourceInspectionId?.toString(), "source_step_code" to v.sourceStepCode,
    )

    private fun insertRevision(j: JdbcOperations, defectId: UUID, version: Int, actor: ActorRef, reason: String, content: DefectView) {
        j.update("INSERT INTO taktovia.defect_case_revision (tenant_id, id, defect_id, version, snapshot, author, change_reason) VALUES (taktovia.current_tenant(), ?, ?, ?, CAST(? AS jsonb), ?, ?)",
            UUID.randomUUID(), defectId, version, json.writeValueAsString(contentOf(content)), actor.value, reason)
    }

    override fun amend(ctx: RequestContext, id: UUID, cmd: DefectAmendment): DefectView {
        val actor = actor(ctx)
        DefectRoles.reporterRole(ctx) ?: throw ProblemException(ErrorCode.FORBIDDEN, "Nachträge erfassen Fahrer, Disposition und Werkstatt.")
        reporter(ctx)
        try {
            return ctxTx.run(ctx) { j -> doAmend(j, ctx, actor, id, cmd) }
        } catch (e: DataIntegrityViolationException) {
            if (sqlState(e) == "23514") throw ProblemException.invalid(Violation("/occurred_at", ViolationCode.INVALID_VALUE))
            throw e
        }
    }

    @AuditRequired
    private fun doAmend(j: JdbcOperations, ctx: RequestContext, actor: ActorRef, id: UUID, cmd: DefectAmendment): DefectView {
        // Zeilensperre: gleichzeitige Nachträge werden nacheinander versioniert, jeder auf dem Stand des vorigen (kein verlorenes Update)
        val before = j.query("SELECT $COLUMNS FROM taktovia.defect_case d WHERE d.id = ? FOR UPDATE", MAPPER, id).firstOrNull() ?: throw ProblemException.notFound()
        if (DefectRoles.driverOnly(ctx) && before.reportedBy.toString() != ctx.actor.subject.lowercase()) throw ProblemException.notFound()
        val now = clock.instant()
        if (cmd.occurredAt != null && cmd.occurredAt.isAfter(now.plusSeconds(DefectCatalog.FUTURE_TOLERANCE_SECONDS))) throw ProblemException.invalid(Violation("/occurred_at", ViolationCode.INVALID_VALUE))
        val next = before.copy(
            occurredAt = cmd.occurredAt ?: before.occurredAt, locationKind = cmd.locationKind ?: before.locationKind, locationValue = cmd.locationValue ?: before.locationValue,
            categoryCode = cmd.categoryCode ?: before.categoryCode, kind = cmd.kind ?: before.kind, vehicleArea = cmd.vehicleArea ?: before.vehicleArea,
            observedEffect = cmd.observedEffect ?: before.observedEffect, description = cmd.description ?: before.description,
        )
        val changed = contentOf(next).filter { (k, v) -> v != contentOf(before)[k] }.keys.toList()
        if (changed.isEmpty() && cmd.addEvidenceIds.isEmpty() && cmd.replaceEvidence.isEmpty()) throw ProblemException.invalid(Violation("/", ViolationCode.INVALID_VALUE))
        cmd.replaceEvidence.forEachIndexed { i, r -> evidence.requireReplaceable(j, id, r.evidenceId, "/replace_evidence/$i/evidence_id") }
        val version = before.contentVersion + 1
        insertRevision(j, id, version, actor, cmd.changeReason.trim(), next)
        j.update(
            "UPDATE taktovia.defect_case SET occurred_at = ?, location_kind = ?, location_value = ?, category_code = ?, kind = ?, vehicle_area = ?, observed_effect = ?, description = ?, " +
                "content_version = ?, revision = revision + 1, updated_at = clock_timestamp() WHERE id = ?",
            Timestamp.from(next.occurredAt), next.locationKind, next.locationValue, next.categoryCode, next.kind, next.vehicleArea, next.observedEffect, next.description, version, id,
        )
        evidence.attach(j, id, cmd.addEvidenceIds, actor, version, "/add_evidence_ids")
        evidence.attach(j, id, cmd.replaceEvidence.map { it.ticketId }, actor, version, "/replace_evidence", cmd.replaceEvidence.associate { it.ticketId to it.evidenceId })
        val after = loadOrNull(j, ctx, id)!!
        audit.record(j, AuditEntry(actor, AuditActions.DEFECT_AMENDED, ObjectRef("defect", id), ctx.correlationId,
            mapOf("case_number" to after.caseNumber, "version" to version, "changed_fields" to changed, "evidence_added" to cmd.addEvidenceIds.size, "evidence_replaced" to cmd.replaceEvidence.size)))
        outbox.append(j, OutboxEvent(id, after.revision, "defect.amended",
            mapOf("defect_id" to id.toString(), "case_number" to after.caseNumber, "vehicle_id" to after.vehicleId.toString(), "version" to version, "changed_fields" to changed,
                "evidence_added" to cmd.addEvidenceIds.size, "evidence_replaced" to cmd.replaceEvidence.size), clock.instant(), correlationId = ctx.correlationId))
        return after
    }

    private fun requireReviewer(ctx: RequestContext) {
        if (ctx.roles.none { it in DefectRoles.REVIEWERS }) throw ProblemException(ErrorCode.FORBIDDEN, "Die Historie sehen nur berechtigte Prüfer (Werkstatt, Betriebsleitung).")
    }

    @Suppress("UNCHECKED_CAST")
    private fun readContent(text: String): Map<String, Any?> = json.readValue(text, Map::class.java) as Map<String, Any?>

    private fun evidenceView(e: de.taktovia.fleetsafety.api.EvidenceDetail) = EvidenceView(e.id, e.mime ?: "", e.sizeBytes ?: 0, e.sha256Hex, e.capturedAt, e.receivedAt, e.addedInVersion, e.replacesId)

    override fun history(ctx: RequestContext, id: UUID): List<DefectRevision> {
        requireReviewer(ctx)
        return ctxTx.run(ctx) { j -> doReadHistory(j, ctx, id) }
    }

    @AuditRequired
    private fun doReadHistory(j: JdbcOperations, ctx: RequestContext, id: UUID): List<DefectRevision> {
        val caseNumber = j.query("SELECT case_number FROM taktovia.defect_case WHERE id = ?", { rs, _ -> rs.getString(1) }, id).firstOrNull() ?: throw ProblemException.notFound()
        val evidence = j.query("SELECT $EVIDENCE_COLUMNS FROM taktovia.defect_evidence e WHERE e.defect_id = ? ORDER BY e.created_at, e.id", JdbcDefectEvidence.EV_MAPPER, id).groupBy { it.addedInVersion }
        val out = j.query("SELECT r.version, r.author, r.change_reason, r.created_at, r.snapshot::text FROM taktovia.defect_case_revision r WHERE r.defect_id = ? ORDER BY r.version", { rs, _ ->
            DefectRevision(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant(), readContent(rs.getString(5)), evidence[rs.getInt(1)].orEmpty().map { evidenceView(it) })
        }, id)
        audit.record(j, AuditEntry(actor(ctx), AuditActions.DEFECT_HISTORY_READ, ObjectRef("defect", id), ctx.correlationId, mapOf("case_number" to caseNumber, "versions" to out.size)))
        return out
    }

    override fun stateAt(ctx: RequestContext, id: UUID, at: Instant): DefectStateAt {
        requireReviewer(ctx)
        return ctxTx.run(ctx) { j -> doReadStateAt(j, ctx, id, at) }
    }

    @AuditRequired
    private fun doReadStateAt(j: JdbcOperations, ctx: RequestContext, id: UUID, at: Instant): DefectStateAt {
        val caseNumber = j.query("SELECT case_number FROM taktovia.defect_case WHERE id = ?", { rs, _ -> rs.getString(1) }, id).firstOrNull() ?: throw ProblemException.notFound()
        val ts = Timestamp.from(at)
        val rev = j.query("SELECT r.version, r.created_at, r.snapshot::text FROM taktovia.defect_case_revision r WHERE r.defect_id = ? AND r.created_at <= ? ORDER BY r.version DESC LIMIT 1",
            { rs, _ -> Triple(rs.getInt(1), rs.getTimestamp(2).toInstant(), readContent(rs.getString(3))) }, id, ts).firstOrNull() ?: throw ProblemException.notFound()
        val evidence = j.query(
            "SELECT $EVIDENCE_COLUMNS FROM taktovia.defect_evidence e WHERE e.defect_id = ? AND e.created_at <= ? AND NOT EXISTS " +
                "(SELECT 1 FROM taktovia.defect_evidence r WHERE r.replaces_evidence_id = e.id AND r.created_at <= ?) ORDER BY e.created_at, e.id", JdbcDefectEvidence.EV_MAPPER, id, ts, ts)
        val status = j.query("SELECT h.to_status FROM taktovia.defect_status_history h WHERE h.defect_id = ? AND h.created_at <= ? ORDER BY h.created_at DESC, h.revision DESC LIMIT 1", { rs, _ -> rs.getString(1) }, id, ts).firstOrNull()
        audit.record(j, AuditEntry(actor(ctx), AuditActions.DEFECT_HISTORY_READ, ObjectRef("defect", id), ctx.correlationId, mapOf("case_number" to caseNumber, "as_of" to at.toString(), "version" to rev.first)))
        return DefectStateAt(at, rev.first, rev.second, status, rev.third, evidence.map { evidenceView(it) })
    }

    // ---------------- Hilfen ----------------

    private fun findByDeviceEvent(j: JdbcOperations, ctx: RequestContext, deviceEventId: UUID): DefectView? =
        enrich(j, ctx, j.query("SELECT $COLUMNS FROM taktovia.defect_case d WHERE d.device_event_id = ?", MAPPER, deviceEventId)).firstOrNull()

    private fun loadOrNull(j: JdbcOperations, ctx: RequestContext, id: UUID): DefectView? = enrich(j, ctx, j.query("SELECT $COLUMNS FROM taktovia.defect_case d WHERE d.id = ?", MAPPER, id)).firstOrNull()

    /**
     * Ergänzt freigegebene Fotos und die Zahl der Fotos in Prüfung (nicht sichtbar, nicht abrufbar) in der **Projektion der aufrufenden Rolle** (WP-M3-50): vertrauliche Dokumente fehlen für
     * Unberechtigte vollständig, auch als Zähler. Eine Abfrage je Seite.
     */
    private fun enrich(j: JdbcOperations, ctx: RequestContext, views: List<DefectView>): List<DefectView> {
        if (views.isEmpty()) return views
        val ids = views.joinToString(",") { it.id.toString() }
        val privileged = DefectConfidentiality.privileged(ctx.roles)
        val released = j.query("SELECT $EVIDENCE_COLUMNS FROM taktovia.defect_evidence e WHERE e.defect_id = ANY (string_to_array(?, ',')::uuid[]) AND NOT EXISTS (SELECT 1 FROM taktovia.defect_evidence r WHERE r.replaces_evidence_id = e.id) ORDER BY e.created_at, e.id",
            JdbcDefectEvidence.EV_MAPPER, ids).filter { DefectConfidentiality.visibleTo(ctx, it) }.groupBy { it.defectId }
        val pending = j.query("SELECT defect_id, count(*) FROM taktovia.defect_evidence_upload WHERE state = 'IN_REVIEW' AND defect_id = ANY (string_to_array(?, ',')::uuid[]) " +
            "AND (confidentiality = 'STANDARD' OR ? OR uploaded_by = ?) GROUP BY defect_id",
            { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getInt(2) }, ids, privileged, DefectConfidentiality.actorRef(ctx)).toMap()
        val blocking = j.query("SELECT DISTINCT reason_ref FROM taktovia.vehicle_block WHERE status = 'ACTIVE' AND source = 'SAFETY' AND reason_code = 'DEFECT_CRITICAL' AND reason_ref = ANY (string_to_array(?, ','))",
            { rs, _ -> rs.getString(1) }, views.joinToString(",") { it.caseNumber }).toSet()
        return views.map { v ->
            v.copy(blocksVehicle = v.caseNumber in blocking, evidence = released[v.id].orEmpty().map { EvidenceView(it.id, it.mime ?: "", it.sizeBytes ?: 0, it.sha256Hex, it.capturedAt, it.receivedAt, it.addedInVersion, it.replacesId) }, evidencePending = pending[v.id] ?: 0)
        }
    }

    private fun reporter(ctx: RequestContext): UUID =
        runCatching { UUID.fromString(ctx.actor.subject) }.getOrNull() ?: throw ProblemException(ErrorCode.FORBIDDEN, "Schäden melden nur Personenkonten.")

    private fun actor(ctx: RequestContext) = ActorRef((if (ctx.actor.type == ActorType.SERVICE) "service:" else "user:") + ctx.actor.subject)

    private fun sqlState(e: Throwable): String? = generateSequence(e) { it.cause }.filterIsInstance<SQLException>().firstOrNull()?.sqlState

    internal companion object {
        /** Rollen der Triage (Rechtematrix `triageDefect`: Disposition, Werkstatt) und Status, in denen noch triagiert wird. */
        val TRIAGERS = setOf("dispatcher", "workshop")
        val TRIAGEABLE = setOf(DefectState.NEW, DefectState.REVIEWED, DefectState.IN_PROGRESS)
        /** Wie [JdbcDefectEvidence.EV_COLUMNS] (hier als eigene Konstante, damit die Abfrage-Prüfung den Text sieht). */
        const val EVIDENCE_COLUMNS = "e.id, e.defect_id, e.mime, e.size_bytes, e.sha256, e.captured_at, e.received_at, e.created_by, e.defect_version, e.replaces_evidence_id, e.confidentiality"
        const val ACTIVE_ORDERS = "SELECT count(*) FROM taktovia.repair_order WHERE defect_id = ? AND status <> 'CANCELED'"
        const val PROVEN_ORDERS = "SELECT count(*) FROM taktovia.repair_order WHERE defect_id = ? AND status = 'PROOF_RECORDED'"
        const val RELEASE_DECISIONS = "SELECT count(*) FROM taktovia.vehicle_release_decision d JOIN taktovia.repair_order r ON r.tenant_id = d.tenant_id AND r.id = d.repair_order_id " +
            "WHERE r.defect_id = ? AND d.decision = 'RELEASED'"
        const val COLUMNS = "d.id, d.case_number, d.vehicle_id, d.reported_by, d.reported_at, d.occurred_at, d.location_kind, d.location_value, d.category_code, d.kind, d.vehicle_area, " +
            "d.observed_effect, d.description, d.severity, d.status, d.assignee_ref, d.source_inspection_id, d.source_step_code, d.content_version, d.revision, d.updated_at, d.created_at, " +
            "d.severity_rule_version, d.severity_source, d.classified_at"
        val MAPPER = RowMapper { rs, _ ->
            DefectView(
                rs.getObject("id", UUID::class.java), rs.getString("case_number"), rs.getObject("vehicle_id", UUID::class.java), rs.getObject("reported_by", UUID::class.java),
                rs.getTimestamp("reported_at").toInstant(), rs.getTimestamp("occurred_at").toInstant(), rs.getString("location_kind"), rs.getString("location_value"),
                rs.getString("category_code"), rs.getString("kind"), rs.getString("vehicle_area"), rs.getString("observed_effect"), rs.getString("description"), rs.getString("severity"),
                rs.getString("status"), rs.getObject("assignee_ref", UUID::class.java), rs.getObject("source_inspection_id", UUID::class.java), rs.getString("source_step_code"),
                rs.getInt("content_version"), rs.getLong("revision"), rs.getTimestamp("updated_at").toInstant(), rs.getTimestamp("created_at").toInstant(),
                severityRuleVersion = rs.getString("severity_rule_version"), severitySource = rs.getString("severity_source"), classifiedAt = rs.getTimestamp("classified_at")?.toInstant(),
            )
        }
    }
}

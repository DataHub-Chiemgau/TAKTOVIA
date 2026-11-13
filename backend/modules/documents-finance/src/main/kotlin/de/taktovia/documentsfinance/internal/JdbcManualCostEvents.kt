package de.taktovia.documentsfinance.internal

import de.taktovia.documentsfinance.api.ApprovalDecision
import de.taktovia.documentsfinance.api.ApprovalStep
import de.taktovia.documentsfinance.api.CreateManualCostEvent
import de.taktovia.documentsfinance.api.ExpenseStates
import de.taktovia.documentsfinance.api.ManualCostCreated
import de.taktovia.documentsfinance.api.ManualCostEventView
import de.taktovia.documentsfinance.api.ManualCostEvents
import de.taktovia.documentsfinance.api.ManualCostOutcome
import de.taktovia.documentsfinance.api.ManualCostStepView
import de.taktovia.documentsfinance.api.TaxTreatment
import de.taktovia.documentsfinance.internal.DocumentRows.isFinance
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
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import java.sql.Date
import java.sql.Timestamp
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * Manuelles Fahrzeugkostenereignis (WP-M4-40). Jeder Befehl: Recht prüfen (403 vor jeder Wirkung und ohne Schritteintrag), Zeile sperren (`FOR NO KEY UPDATE`), exakte Revision prüfen (412), Zustand prüfen (409),
 * Schritt, Zustand, Audit und Ereignis in **einer** Transaktion; die endgültige Freigabe bucht in derselben Transaktion (Ereignis, Journal, Outbox `cost_event.posted`). Parallele Freigaben serialisiert die
 * Zeilensperre: genau eine gewinnt, die andere sieht den neuen Zustand (409); zusätzlich ist das Ereignis über `(tenant_id, legal_entity_id, source_system, source_ref, event_type, scenario)` einmalig.
 */
internal class JdbcManualCostEvents(private val ctxTx: ContextTransactions, private val audit: AuditPort, private val outbox: Outbox, private val clock: Clock) : ManualCostEvents {

    // ---- Anlegen ----

    override fun create(ctx: RequestContext, cmd: CreateManualCostEvent): ManualCostCreated {
        requireRight(ctx, SCOPE_WRITE)
        val originKey = cmd.originKey.trim()
        val description = cmd.description?.trim()
        val violations = buildList {
            if (!CATEGORY.matches(cmd.category)) add(Violation("/category", ViolationCode.PATTERN_MISMATCH))
            if (!ValuationRules.CURRENCY.matches(cmd.currency)) add(Violation("/currency", ViolationCode.PATTERN_MISMATCH))
            if (cmd.grossMinor < 1) add(Violation("/gross_minor", ViolationCode.BELOW_MINIMUM)) else if (cmd.grossMinor > MAX_SAFE) add(Violation("/gross_minor", ViolationCode.MONEY_OUT_OF_RANGE))
            if (cmd.taxMinor < 0) add(Violation("/tax_minor", ViolationCode.BELOW_MINIMUM)) else if (cmd.taxMinor > cmd.grossMinor) add(Violation("/tax_minor", ViolationCode.ABOVE_MAXIMUM, "höchstens der Bruttobetrag"))
            if (originKey.isEmpty()) add(Violation("/origin_key", ViolationCode.REQUIRED)) else if (originKey.length > 200) add(Violation("/origin_key", ViolationCode.TOO_LONG))
            if (description != null && (description.isEmpty() || description.length > 500)) add(Violation("/description", if (description.isEmpty()) ViolationCode.TOO_SHORT else ViolationCode.TOO_LONG))
            if (cmd.serviceTo != null && cmd.serviceFrom == null) add(Violation("/service_from", ViolationCode.REQUIRED))
            if (cmd.serviceFrom != null && cmd.serviceTo != null && cmd.serviceTo.isBefore(cmd.serviceFrom)) add(Violation("/service_to", ViolationCode.INVALID_VALUE, "das Leistungsende liegt nicht vor dem Beginn"))
            if (cmd.accountingDate.year !in 2000..2100) add(Violation("/accounting_date", ViolationCode.INVALID_VALUE))
        }
        if (violations.isNotEmpty()) throw ProblemException.invalid(*violations.toTypedArray())
        return ctxTx.run(ctx) { j -> doCreate(j, ctx, cmd, originKey, description) }
    }

    @AuditRequired
    private fun doCreate(j: JdbcOperations, ctx: RequestContext, cmd: CreateManualCostEvent, originKey: String, description: String?): ManualCostCreated {
        if (j.queryForObject("SELECT count(*) FROM taktovia.legal_entity WHERE id = ?", Int::class.java, cmd.legalEntityId) != 1) throw ProblemException.notFound()
        if (j.queryForObject("SELECT count(*) FROM taktovia.vehicle WHERE id = ?", Int::class.java, cmd.vehicleId) != 1) throw ProblemException.notFound()
        val exponent = j.query("SELECT exponent FROM taktovia.currency WHERE code = ?", { rs, _ -> rs.getInt(1) }, cmd.currency).firstOrNull()
            ?: throw ProblemException.invalid(Violation("/currency", ViolationCode.INVALID_VALUE, "unbekannte Währung"))
        if (cmd.exponent != null && cmd.exponent != exponent) throw ProblemException.invalid(Violation("/gross/exponent", ViolationCode.INVALID_VALUE, "der Exponent der Währung ist $exponent"))
        CostCatalog.find(j, cmd.category) ?: throw ProblemException.invalid(Violation("/category", ViolationCode.INVALID_VALUE, "unbekannte Kostenart"))
        if (j.queryForObject("SELECT count(*) FROM taktovia.accounting_mapping WHERE legal_entity_id = ? AND category = ?", Int::class.java, cmd.legalEntityId, cmd.category) != 1)
            throw ProblemException(ErrorCode.CONFLICT, "Für die Kostenart fehlt die Kontierungsregel des Rechtsträgers.")
        val costObject = vehicleCostObject(j, ctx, cmd.vehicleId, cmd.legalEntityId)
        val id = UUID.randomUUID(); val net = cmd.grossMinor - cmd.taxMinor
        val inserted = j.update("INSERT INTO taktovia.manual_cost_event (tenant_id, id, legal_entity_id, vehicle_id, cost_object_id, category, currency, net_minor, tax_minor, gross_minor, tax_treatment, accounting_date, " +
            "service_from, service_to, description, origin_key, state, revision, created_by) VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'IN_REVIEW', 1, ?) ON CONFLICT DO NOTHING",
            id, cmd.legalEntityId, cmd.vehicleId, costObject, cmd.category, cmd.currency, net, cmd.taxMinor, cmd.grossMinor, cmd.taxTreatment.name, Date.valueOf(cmd.accountingDate),
            cmd.serviceFrom?.let { Date.valueOf(it) }, cmd.serviceTo?.let { Date.valueOf(it) }, description, originKey, ctx.actor.subject.take(200))
        if (inserted == 0) {   // derselbe fachliche Ursprung: gleicher Inhalt liefert den vorhandenen Vorgang, anderer Inhalt ist ein Konflikt
            val existing = j.query("SELECT $COLUMNS FROM taktovia.manual_cost_event m WHERE m.legal_entity_id = ? AND m.origin_key = ?", MAPPER, cmd.legalEntityId, originKey).single()
            val same = existing.vehicleId == cmd.vehicleId && existing.category == cmd.category && existing.currency == cmd.currency && existing.grossMinor == cmd.grossMinor && existing.taxMinor == cmd.taxMinor &&
                existing.taxTreatment == cmd.taxTreatment && existing.accountingDate == cmd.accountingDate && existing.serviceFrom == cmd.serviceFrom && existing.serviceTo == cmd.serviceTo && existing.description == description
            if (!same) throw ProblemException(ErrorCode.CONFLICT, "Der Ursprungsschlüssel ist bereits mit anderem Inhalt erfasst.")
            return ManualCostCreated(existing.copy(steps = steps(j, existing.id)), reused = true)
        }
        insertStep(j, ctx, id, 1, ApprovalStep.CAPTURE, ApprovalDecision.APPROVED, "Erfassung abgeschlossen")
        audit.record(j, AuditEntry(DocumentRows.actor(ctx), AuditActions.FINANCE_MANUAL_COST_EVENT_CREATED, ObjectRef("manual_cost_event", id), ctx.correlationId, mapOf("category" to cmd.category, "currency" to cmd.currency, "reused" to false)))
        return ManualCostCreated(load(j, id), reused = false)
    }

    private fun vehicleCostObject(j: JdbcOperations, ctx: RequestContext, vehicleId: UUID, entity: UUID): UUID {
        j.update("INSERT INTO taktovia.cost_object (tenant_id, id, legal_entity_id, kind, business_ref, vehicle_id, created_by) VALUES (taktovia.current_tenant(), ?, ?, 'VEHICLE', ?, ?, ?) ON CONFLICT DO NOTHING",
            UUID.randomUUID(), entity, "$vehicleId/$entity", vehicleId, DocumentRows.actor(ctx).value)
        return j.queryForObject("SELECT id FROM taktovia.cost_object WHERE kind = 'VEHICLE' AND vehicle_id = ? AND legal_entity_id = ?", UUID::class.java, vehicleId, entity)!!
    }

    // ---- Lesen ----

    override fun get(ctx: RequestContext, id: UUID): ManualCostEventView {
        if (!ctx.hasScope(SCOPE_READ) || !isFinance(ctx)) throw ProblemException(ErrorCode.FORBIDDEN)
        return ctxTx.run(ctx, readOnly = true) { j -> j.query("SELECT $COLUMNS FROM taktovia.manual_cost_event m WHERE m.id = ?", MAPPER, id).firstOrNull()?.copy(steps = steps(j, id)) ?: throw ProblemException.notFound() }
    }

    // ---- fachliche Prüfung ----

    override fun review(ctx: RequestContext, id: UUID, expectedRevision: Long, decision: ApprovalDecision, reason: String): ManualCostEventView {
        requireRight(ctx, SCOPE_REVIEW)
        val text = reasonOf(reason)
        return ctxTx.run(ctx) { j ->
            val m = locked(j, id, expectedRevision)
            if (m.state != ExpenseStates.IN_REVIEW) throw ProblemException(ErrorCode.INVALID_STATE_TRANSITION, "Nur ein eingereichtes Kostenereignis kann fachlich geprüft werden.")
            transition(j, ctx, m, ApprovalStep.REVIEW, decision, text, ExpenseStates.IN_REVIEW, if (decision == ApprovalDecision.APPROVED) ExpenseStates.REVIEWED else REJECTED)
            load(j, id)
        }
    }

    // ---- Budgetfreigabe und zweite Freigabe ----

    override fun approve(ctx: RequestContext, id: UUID, validatedRevision: Long, reason: String): ManualCostOutcome {
        requireRight(ctx, SCOPE_APPROVE)
        val text = reasonOf(reason)
        return ctxTx.run(ctx) { j -> doApprove(j, ctx, id, validatedRevision, text) }
    }

    @AuditRequired
    private fun doApprove(j: JdbcOperations, ctx: RequestContext, id: UUID, revision: Long, reason: String): ManualCostOutcome {
        val m = locked(j, id, revision)
        val second = when (m.state) {
            ExpenseStates.REVIEWED -> false
            ExpenseStates.WAITING_SECOND_APPROVAL -> true
            ExpenseStates.POSTED -> throw ProblemException(ErrorCode.INVALID_STATE_TRANSITION, "Das Kostenereignis ist bereits freigegeben bzw. gebucht.")
            REJECTED -> throw ProblemException(ErrorCode.INVALID_STATE_TRANSITION, "Das Kostenereignis wurde abgelehnt.")
            else -> throw ProblemException(ErrorCode.INVALID_STATE_TRANSITION, "Das Kostenereignis hat die fachliche Prüfung noch nicht bestanden.")
        }
        val actor = ctx.actor.subject.take(200)
        // Rollentrennung: der Erfasser gibt nie selbst frei, die zweite Freigabe erteilt nie der Erstfreigeber (die Datenbank sichert beides zusätzlich)
        if (actor == m.createdBy) throw ProblemException(ErrorCode.FORBIDDEN, "Der Erfasser kann sein Kostenereignis nicht selbst freigeben.")
        if (second) {
            val first = j.query("SELECT decided_by FROM taktovia.manual_cost_event_step WHERE manual_cost_event_id = ? AND revision = ? AND step = 'BUDGET' AND decision = 'APPROVED' ORDER BY decided_at DESC, id DESC LIMIT 1",
                { rs, _ -> rs.getString(1) }, id, revision).firstOrNull()
            if (first == actor) throw ProblemException(ErrorCode.FORBIDDEN, "Die zweite Freigabe erteilt eine andere Person als die Erstfreigabe.")
        }
        val step = if (second) ApprovalStep.SECOND_APPROVAL else ApprovalStep.BUDGET
        if (!second && requiresSecond(j, m)) {   // Vier-Augen-Schwelle: Betrag größer als die Schwelle (exakt auf der Schwelle genügt eine Freigabe)
            transition(j, ctx, m, step, ApprovalDecision.APPROVED, reason, ExpenseStates.REVIEWED, ExpenseStates.WAITING_SECOND_APPROVAL)
            return ManualCostOutcome(load(j, id), null)
        }
        val eventId = post(j, ctx, m)
        insertStep(j, ctx, id, revision, step, ApprovalDecision.APPROVED, reason)
        check(j.update("UPDATE taktovia.manual_cost_event SET state = 'POSTED', approved_by = ?, event_id = ?, updated_at = clock_timestamp() WHERE id = ? AND state = ?", DocumentRows.actor(ctx).value, eventId, id, m.state) == 1)
        audit.record(j, AuditEntry(DocumentRows.actor(ctx), AuditActions.FINANCE_MANUAL_COST_EVENT_STEP, ObjectRef("manual_cost_event", id), ctx.correlationId,
            mapOf("step" to step.name, "decision" to "APPROVED", "revision" to revision, "state" to ExpenseStates.POSTED)))
        return ManualCostOutcome(load(j, id), eventId)
    }

    /** Vier-Augen-Schwelle: Betrag **größer** als die Schwelle; andere Währung als die der Schwelle: stets zweite Stufe; ohne Schwelle keine zweite Stufe. */
    private fun requiresSecond(j: JdbcOperations, m: ManualCostEventView): Boolean {
        val policy = JdbcFinancePolicies.load(j)
        val threshold = policy.approvalSecondThresholdMinor ?: return false
        return m.currency != policy.approvalThresholdCurrency || m.grossMinor > threshold
    }

    /** Posting wie beim Beleg ([JdbcCostEvents]): Ereignis, Journal (erst `DRAFT` mit Zeilen, dann `POSTED`) und Outbox in der laufenden Transaktion; Rückgabe: Kennung des Kostenereignisses. */
    private fun post(j: JdbcOperations, ctx: RequestContext, m: ManualCostEventView): UUID {
        val inactive = j.query("SELECT active FROM taktovia.cost_object WHERE id = ? FOR SHARE", { rs, _ -> rs.getBoolean(1) }, m.costObjectId).firstOrNull()
        if (inactive != true) throw ProblemException(ErrorCode.CONFLICT, "Das Fahrzeug-Kostenobjekt ist inaktiv.")
        val mapping = j.query("SELECT expense_account, input_tax_account, payable_account FROM taktovia.accounting_mapping WHERE legal_entity_id = ? AND category = ?",
            { rs, _ -> Triple(rs.getString(1), rs.getString(2), rs.getString(3)) }, m.legalEntityId, m.category).firstOrNull()
            ?: throw ProblemException(ErrorCode.CONFLICT, "Für die Kostenart fehlt die Kontierungsregel des Rechtsträgers.")
        val date = m.accountingDate
        val periods = j.query("SELECT id, closed_at FROM taktovia.fiscal_period WHERE legal_entity_id = ? AND starts_on <= ? AND ends_on > ? ORDER BY starts_on FOR UPDATE",
            { rs, _ -> rs.getObject("id", UUID::class.java) to (rs.getTimestamp("closed_at") != null) }, m.legalEntityId, Date.valueOf(date), Date.valueOf(date))
        if (periods.isEmpty()) throw ProblemException(ErrorCode.CONFLICT, "Für das Buchungsdatum gibt es keine Buchungsperiode des Rechtsträgers.")
        val period = periods.firstOrNull { !it.second } ?: throw ProblemException(ErrorCode.PERIOD_CLOSED, "Die Buchungsperiode ist geschlossen; Korrekturen nur als Gegenereignis in einer offenen Periode.")
        val recoverable = if (m.taxTreatment == TaxTreatment.FULLY_DEDUCTIBLE) m.taxMinor else 0L
        val cls = CostCatalog.classify(j, m.category, split = false)
        val now = clock.instant(); val eventId = UUID.randomUUID(); val journalId = UUID.randomUUID()
        val inserted = j.update("INSERT INTO taktovia.economic_event (tenant_id, id, legal_entity_id, source_system, source_ref, event_type, scenario, category, currency, net_minor, tax_minor, gross_minor, recoverable_tax_minor, " +
            "tax_rule_revision, occurred_at, booked_at, service_from, service_to, cost_behavior, attribution, cost_object_id) VALUES (taktovia.current_tenant(), ?, ?, 'MANUAL', ?, 'MANUAL_COST', 'ACTUAL', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
            eventId, m.legalEntityId, m.originKey, m.category, m.currency, m.netMinor, m.taxMinor, m.grossMinor, recoverable, JdbcCostEvents.TAX_RULE_REVISION, utc(date), Timestamp.from(now),
            m.serviceFrom?.let { utc(it) }, (m.serviceTo ?: m.serviceFrom)?.let { utc(it) }, cls?.first, cls?.second, m.costObjectId)
        if (inserted == 0) throw ProblemException(ErrorCode.CONFLICT, "Der fachliche Ursprung ist bereits gebucht.")   // nie ein zweites Ereignis zum selben Ursprung
        j.update("INSERT INTO taktovia.journal (tenant_id, id, event_id, legal_entity_id, currency, period_id, accounting_date, state) VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?, 'DRAFT')",
            journalId, eventId, m.legalEntityId, m.currency, period.first, Date.valueOf(date))
        fun line(account: String, side: String, amount: Long, role: String) {
            if (amount > 0) j.update("INSERT INTO taktovia.journal_line (tenant_id, id, journal_id, account_code, side, amount_minor, account_role) VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?)", UUID.randomUUID(), journalId, account, side, amount, role)
        }
        line(mapping.first, "D", m.grossMinor - recoverable, "EXPENSE"); line(mapping.second, "D", recoverable, "INPUT_TAX"); line(mapping.third, "C", m.grossMinor, "PAYABLE")
        j.update("UPDATE taktovia.journal SET state = 'POSTED', posted_by = ? WHERE id = ? AND state = 'DRAFT'", DocumentRows.actor(ctx).value, journalId)   // Trigger: ausgeglichen, offene Periode
        audit.record(j, AuditEntry(DocumentRows.actor(ctx), AuditActions.FINANCE_COST_EVENT_POSTED, ObjectRef("cost_event", eventId), ctx.correlationId, mapOf("event_type" to "MANUAL_COST", "currency" to m.currency, "reused" to false)))
        outbox.append(j, OutboxEvent(eventId, 1, "cost_event.posted", linkedMapOf("event_id" to eventId.toString(), "manual_cost_event_id" to m.id.toString(), "legal_entity_id" to m.legalEntityId.toString(), "event_type" to "MANUAL_COST",
            "category" to m.category, "currency" to m.currency, "gross_minor" to m.grossMinor, "cost_basis_minor" to m.grossMinor - recoverable, "booked_at" to now.toString()), now, correlationId = ctx.correlationId))
        return eventId
    }

    // ---- Ablehnung ----

    override fun reject(ctx: RequestContext, id: UUID, expectedRevision: Long, reason: String): ManualCostEventView {
        requireRight(ctx, SCOPE_APPROVE)
        val text = reasonOf(reason)
        return ctxTx.run(ctx) { j ->
            val m = locked(j, id, expectedRevision)
            val step = when (m.state) {
                ExpenseStates.REVIEWED -> ApprovalStep.BUDGET
                ExpenseStates.WAITING_SECOND_APPROVAL -> ApprovalStep.SECOND_APPROVAL
                else -> throw ProblemException(ErrorCode.INVALID_STATE_TRANSITION, "Ablehnen lässt sich nur ein Kostenereignis in der Budgetfreigabe oder in der zweiten Freigabe.")
            }
            transition(j, ctx, m, step, ApprovalDecision.REJECTED, text, m.state, REJECTED)
            load(j, id)
        }
    }

    // ---- gemeinsame Bausteine ----

    /** Menschliche Finanzkraft mit dem Funktionsscope (403 sonst, vor jeder Wirkung). */
    private fun requireRight(ctx: RequestContext, scope: String) {
        if (!ctx.hasScope(scope) || ctx.actor.type != ActorType.HUMAN || !isFinance(ctx)) throw ProblemException(ErrorCode.FORBIDDEN)
    }

    private fun reasonOf(reason: String): String {
        val t = reason.trim()
        if (t.length < 3) throw ProblemException.invalid(Violation("/reason", if (t.isEmpty()) ViolationCode.REQUIRED else ViolationCode.TOO_SHORT))
        if (t.length > 1000) throw ProblemException.invalid(Violation("/reason", ViolationCode.TOO_LONG))
        return t
    }

    /** Zeile gesperrt (404 unsichtbar/fremd) und auf exakter Revision (412 sonst). */
    private fun locked(j: JdbcOperations, id: UUID, revision: Long): ManualCostEventView {
        val m = j.query("SELECT $COLUMNS FROM taktovia.manual_cost_event m WHERE m.id = ? FOR NO KEY UPDATE OF m", MAPPER, id).firstOrNull() ?: throw ProblemException.notFound()
        if (m.revision != revision) throw ProblemException(ErrorCode.REVISION_MISMATCH, "Das Kostenereignis wurde zwischenzeitlich geändert; aktuelle Revision lesen.")
        return m
    }

    private fun insertStep(j: JdbcOperations, ctx: RequestContext, id: UUID, revision: Long, step: ApprovalStep, decision: ApprovalDecision, reason: String) {
        j.update("INSERT INTO taktovia.manual_cost_event_step (tenant_id, id, manual_cost_event_id, revision, step, decision, decided_by, reason) VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?, ?)",
            UUID.randomUUID(), id, revision, step.name, decision.name, ctx.actor.subject.take(200), reason)
    }

    /** Schritt eintragen und Zustand wechseln (bedingtes UPDATE auf den erwarteten Zustand; die Zeile ist gesperrt); Audit je Schritt. */
    private fun transition(j: JdbcOperations, ctx: RequestContext, m: ManualCostEventView, step: ApprovalStep, decision: ApprovalDecision, reason: String, from: String, to: String) {
        insertStep(j, ctx, m.id, m.revision, step, decision, reason)
        check(j.update("UPDATE taktovia.manual_cost_event SET state = ?, updated_at = clock_timestamp() WHERE id = ? AND state = ?", to, m.id, from) == 1) { "bedingter Zustandswechsel traf keine Zeile" }
        audit.record(j, AuditEntry(DocumentRows.actor(ctx), AuditActions.FINANCE_MANUAL_COST_EVENT_STEP, ObjectRef("manual_cost_event", m.id), ctx.correlationId,
            mapOf("step" to step.name, "decision" to decision.name, "revision" to m.revision, "state" to to)))
    }

    private fun load(j: JdbcOperations, id: UUID): ManualCostEventView = j.query("SELECT $COLUMNS FROM taktovia.manual_cost_event m WHERE m.id = ?", MAPPER, id).single().copy(steps = steps(j, id))

    private fun steps(j: JdbcOperations, id: UUID): List<ManualCostStepView> =
        j.query("SELECT id, step, revision, decision, decided_by, reason, decided_at FROM taktovia.manual_cost_event_step WHERE manual_cost_event_id = ? ORDER BY decided_at, id", { rs, _ ->
            ManualCostStepView(rs.getObject("id", UUID::class.java), ApprovalStep.valueOf(rs.getString("step")), rs.getLong("revision"), ApprovalDecision.valueOf(rs.getString("decision")), rs.getString("decided_by"), rs.getString("reason"),
                rs.getTimestamp("decided_at").toInstant()) }, id)

    private fun utc(d: LocalDate) = Timestamp.from(d.atStartOfDay(ZoneOffset.UTC).toInstant())

    private companion object {
        const val SCOPE_WRITE = "costs:write"
        const val SCOPE_READ = "costs:read"
        const val SCOPE_REVIEW = "expenses:review"
        const val SCOPE_APPROVE = "expenses:approve"
        const val REJECTED = "REJECTED"
        const val MAX_SAFE = 9_007_199_254_740_991L
        val CATEGORY = Regex("[A-Z][A-Z0-9_]{0,39}")
        const val COLUMNS = "m.id, m.legal_entity_id, m.vehicle_id, m.cost_object_id, m.category, m.currency, m.net_minor, m.tax_minor, m.gross_minor, m.tax_treatment, m.accounting_date, m.service_from, m.service_to, m.description, " +
            "m.origin_key, m.state, m.revision, m.created_by, m.approved_by, m.event_id, m.created_at"
        val MAPPER = RowMapper { rs, _ ->
            ManualCostEventView(rs.getObject("id", UUID::class.java), rs.getObject("legal_entity_id", UUID::class.java), rs.getObject("vehicle_id", UUID::class.java), rs.getObject("cost_object_id", UUID::class.java),
                rs.getString("category"), rs.getString("currency").trim(), rs.getLong("net_minor"), rs.getLong("tax_minor"), rs.getLong("gross_minor"), TaxTreatment.valueOf(rs.getString("tax_treatment")),
                rs.getDate("accounting_date").toLocalDate(), rs.getDate("service_from")?.toLocalDate(), rs.getDate("service_to")?.toLocalDate(), rs.getString("description"), rs.getString("origin_key"), rs.getString("state"),
                rs.getLong("revision"), rs.getString("created_by"), rs.getString("approved_by"), rs.getObject("event_id", UUID::class.java), rs.getTimestamp("created_at").toInstant(), emptyList())
        }
    }
}

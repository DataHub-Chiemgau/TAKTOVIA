package de.taktovia.documentsfinance.internal

import de.taktovia.documentsfinance.api.CostEventView
import de.taktovia.documentsfinance.api.CostEvents
import de.taktovia.documentsfinance.api.ExpenseFields
import de.taktovia.documentsfinance.api.IntegrityChecks
import de.taktovia.documentsfinance.api.JournalLineView
import de.taktovia.documentsfinance.api.JournalView
import de.taktovia.documentsfinance.api.PostExpense
import de.taktovia.documentsfinance.api.PostingResult
import de.taktovia.documentsfinance.api.ReleaseBlocker
import de.taktovia.documentsfinance.api.TaxTreatment
import de.taktovia.documentsfinance.internal.DocumentRows.isFinance
import de.taktovia.documentsfinance.internal.DocumentRows.require
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
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * Kostenereignis, Journal und einmaliges Posting (WP-M4-33). Reihenfolge der Sperren (ohne Zyklus): Beleg `FOR UPDATE` -> Kostenobjekte `FOR SHARE` -> Buchungsperiode `FOR UPDATE` (serialisiert
 * Postings und Periodenabschluss) -> Zeilen. Die Eindeutigkeit des Ursprungs sichert zusätzlich der Unique-Constraint der Datenbank (`ON CONFLICT DO NOTHING`, dann Lesen des vorhandenen Ereignisses).
 */
internal class JdbcCostEvents(
    private val ctxTx: ContextTransactions, private val audit: AuditPort, private val outbox: Outbox, private val integrity: IntegrityChecks, private val clock: Clock,
) : CostEvents {

    override fun postExpense(ctx: RequestContext, documentId: UUID, cmd: PostExpense): PostingResult {
        authorize(ctx)
        return ctxTx.run(ctx) { j -> post(j, ctx, documentId, cmd) }
    }

    override fun postExpense(jdbc: JdbcOperations, ctx: RequestContext, documentId: UUID, cmd: PostExpense): PostingResult {
        authorize(ctx)
        return post(jdbc, ctx, documentId, cmd)
    }

    /** Posting darf nur der Freigabeprozess auslösen: Scope `expenses:approve`, Finanzrolle (Mensch) oder Dienstkonto. */
    private fun authorize(ctx: RequestContext) {
        if (!ctx.hasScope(SCOPE_APPROVE) || !(isFinance(ctx) || ctx.actor.type == ActorType.SERVICE)) throw ProblemException(ErrorCode.FORBIDDEN)
    }

    @AuditRequired
    private fun post(j: JdbcOperations, ctx: RequestContext, documentId: UUID, cmd: PostExpense): PostingResult {
        if (!CATEGORY.matches(cmd.category)) throw ProblemException.invalid(Violation("/category", ViolationCode.PATTERN_MISMATCH))
        val doc = j.query(DOC_SQL, { rs, i -> DocumentRows.MAPPER.mapRow(rs, i)!! to rs.getString("approved_by") }, documentId)
            .firstOrNull() ?: throw ProblemException.notFound()
        val row = doc.first
        if (row.stateRaw == "POSTED") return existing(j, documentId)                           // einmaliges Posting: derselbe Ursprung liefert dasselbe Ereignis
        if (row.stateRaw != "APPROVED" || doc.second.isNullOrBlank()) throw ProblemException(ErrorCode.INVALID_STATE_TRANSITION, "Nur ein freigegebener Beleg kann gebucht werden.")
        integrity.requirePostable(j, documentId)                                                 // verletztes Original: 409, kein Posting
        DuplicateRows.requireNoBlock(j, documentId, includeOpen = false)                         // Evidenz eines anderen Vorgangs wird nie eigener Kostenfall (WP-M4-32)
        val entity = row.view.legalEntityId ?: throw ProblemException(ErrorCode.CONFLICT, "Der Beleg hat keinen Rechtsträger.")
        val fields = ExpenseFieldCodec.parseStored(row.fieldsJson)
        val amounts = amounts(fields, cmd.tax)

        // Kostenzuordnung erneut unter Sperre (ein Deaktivieren nach der Freigabe wartet bzw. wird hier gesehen)
        val objects = j.query(ASSIGNED_SQL,
            CostAssignmentRows.MAPPER, documentId)
        val blockers = CostAssignmentRows.blockers(objects)
        if (ReleaseBlocker.COST_ASSIGNMENT_MISSING in blockers) throw ProblemException(ErrorCode.CONFLICT, "Der Beleg hat keine Kostenzuordnung.")
        if (ReleaseBlocker.COST_OBJECT_INACTIVE in blockers) throw ProblemException(ErrorCode.CONFLICT, "Ein zugeordnetes Kostenobjekt ist inaktiv.")

        val mapping = j.query("SELECT expense_account, input_tax_account, payable_account FROM taktovia.accounting_mapping WHERE legal_entity_id = ? AND category = ?",
            { rs, _ -> Triple(rs.getString(1), rs.getString(2), rs.getString(3)) }, entity, cmd.category).firstOrNull()
            ?: throw ProblemException(ErrorCode.CONFLICT, "Für die Kostenart fehlt die Kontierungsregel des Rechtsträgers.")

        val date = amounts.date
        val periods = j.query("SELECT id, closed_at FROM taktovia.fiscal_period WHERE legal_entity_id = ? AND starts_on <= ? AND ends_on > ? ORDER BY starts_on FOR UPDATE",
            { rs, _ -> rs.getObject("id", UUID::class.java) to (rs.getTimestamp("closed_at") != null) }, entity, java.sql.Date.valueOf(date), java.sql.Date.valueOf(date))
        if (periods.isEmpty()) throw ProblemException(ErrorCode.CONFLICT, "Für das Belegdatum gibt es keine Buchungsperiode des Rechtsträgers.")
        val period = periods.firstOrNull { !it.second } ?: throw ProblemException(ErrorCode.CONFLICT, "Die Buchungsperiode ist geschlossen; Korrekturen nur als Gegenereignis in einer offenen Periode.")

        val now = clock.instant()
        val eventId = UUID.randomUUID(); val journalId = UUID.randomUUID()
        val inserted = j.update(
            "INSERT INTO taktovia.economic_event (tenant_id, id, legal_entity_id, source_system, source_ref, event_type, scenario, category, currency, net_minor, tax_minor, gross_minor, recoverable_tax_minor, " +
                "tax_rule_revision, occurred_at, booked_at, document_id) VALUES (taktovia.current_tenant(), ?, ?, 'EXPENSE', ?, 'EXPENSE_COST', 'ACTUAL', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
            eventId, entity, documentId.toString(), cmd.category, amounts.currency, amounts.net, amounts.tax, amounts.gross, amounts.recoverable, TAX_RULE_REVISION,
            Timestamp.from(date.atStartOfDay().toInstant(ZoneOffset.UTC)), Timestamp.from(now), documentId)
        if (inserted == 0) return existing(j, documentId)   // paralleler Auslöser desselben Ursprungs hat gebucht
        j.update("INSERT INTO taktovia.journal (tenant_id, id, event_id, legal_entity_id, currency, period_id, accounting_date, state) VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?, 'DRAFT')",
            journalId, eventId, entity, amounts.currency, period.first, java.sql.Date.valueOf(date))
        fun line(account: String, side: String, amount: Long) {
            if (amount > 0) j.update("INSERT INTO taktovia.journal_line (tenant_id, id, journal_id, account_code, side, amount_minor) VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?)", UUID.randomUUID(), journalId, account, side, amount)
        }
        line(mapping.first, "D", amounts.gross - amounts.recoverable)
        line(mapping.second, "D", amounts.recoverable)
        line(mapping.third, "C", amounts.gross)
        j.update("UPDATE taktovia.journal SET state = 'POSTED', posted_by = ? WHERE id = ? AND state = 'DRAFT'", DocumentRows.actor(ctx).value, journalId)   // Trigger: ausgeglichen, >= 2 Zeilen, offene Periode, Summe = Ereignis
        check(j.update("UPDATE taktovia.expense_document SET state = 'POSTED' WHERE id = ? AND state = 'APPROVED'", documentId) == 1)
        DuplicateRows.onPosted(j, ctx, documentId, eventId)                                      // PRIMARY-Evidenz und bisher verknüpfte Dubletten (WP-M4-32)
        audit.record(j, AuditEntry(DocumentRows.actor(ctx), AuditActions.FINANCE_COST_EVENT_POSTED, ObjectRef("cost_event", eventId), ctx.correlationId, mapOf("event_type" to "EXPENSE_COST", "currency" to amounts.currency, "reused" to false)))
        outbox.append(j, OutboxEvent(eventId, 1, "cost_event.posted", linkedMapOf("event_id" to eventId.toString(), "document_id" to documentId.toString(), "legal_entity_id" to entity.toString(), "event_type" to "EXPENSE_COST",
            "category" to cmd.category, "currency" to amounts.currency, "gross_minor" to amounts.gross, "cost_basis_minor" to amounts.gross - amounts.recoverable, "booked_at" to now.toString()), now, correlationId = ctx.correlationId))
        return PostingResult(event(j, eventId), journal(j, journalId), reused = false)
    }

    private fun existing(j: JdbcOperations, documentId: UUID): PostingResult {
        val e = j.query("SELECT $EVENT_COLUMNS FROM taktovia.economic_event e WHERE e.document_id = ? AND e.reverses_id IS NULL AND e.scenario = 'ACTUAL'", EVENT_MAPPER, documentId).firstOrNull()
            ?: throw ProblemException(ErrorCode.CONFLICT, "Der Beleg ist gebucht, das Kostenereignis ist nicht auffindbar.")
        val jid = j.queryForObject("SELECT id FROM taktovia.journal WHERE event_id = ?", UUID::class.java, e.id)!!
        return PostingResult(e, journal(j, jid), reused = true)
    }

    private class Amounts(val gross: Long, val tax: Long, val net: Long, val recoverable: Long, val currency: String, val date: LocalDate)

    /** Beträge aus dem Feldstand des Belegs: brutto, Mehrwertsteuer je Satz summiert, netto = brutto - Steuer; alles in kleinster Einheit und im sicheren Integerbereich. */
    private fun amounts(fields: Map<String, Any?>, tax: TaxTreatment): Amounts {
        val violations = listOfNotNull(
            if (fields[ExpenseFields.AMOUNT_MINOR] == null) Violation("/fields/amount_minor", ViolationCode.REQUIRED) else null,
            if (fields[ExpenseFields.CURRENCY] == null) Violation("/fields/currency", ViolationCode.REQUIRED) else null,
            if (fields[ExpenseFields.DOCUMENT_DATE] == null) Violation("/fields/document_date", ViolationCode.REQUIRED) else null,
        )
        if (violations.isNotEmpty()) throw ProblemException.invalid(*violations.toTypedArray())
        val gross = (fields[ExpenseFields.AMOUNT_MINOR] as Number).toLong()
        if (gross > MAX_SAFE) throw ProblemException.invalid(Violation("/fields/amount_minor", ViolationCode.MONEY_OUT_OF_RANGE))
        if (gross <= 0) throw ProblemException.invalid(Violation("/fields/amount_minor", ViolationCode.BELOW_MINIMUM))
        @Suppress("UNCHECKED_CAST") val vat = (fields[ExpenseFields.VAT_AMOUNTS] as List<Map<String, Any>>?).orEmpty().sumOf { (it["amount_minor"] as Number).toLong() }
        if (vat > gross) throw ProblemException.invalid(Violation("/fields/vat_amounts", ViolationCode.ABOVE_MAXIMUM))
        return Amounts(gross, vat, gross - vat, if (tax == TaxTreatment.FULLY_DEDUCTIBLE) vat else 0, fields[ExpenseFields.CURRENCY] as String, LocalDate.parse(fields[ExpenseFields.DOCUMENT_DATE] as String))
    }

    // ---- Lesen (Ist-Bericht, Projektion) ----

    override fun list(ctx: RequestContext, limit: Int, after: Pair<Instant, UUID>?, asOf: Instant?, legalEntityId: UUID?): List<CostEventView> {
        require(ctx, SCOPE_READ)
        if (limit !in 1..200) throw ProblemException.invalid(Violation("/limit", ViolationCode.INVALID_VALUE))
        return ctxTx.run(ctx, readOnly = true) { j ->
            j.query(LIST_SQL, EVENT_MAPPER, asOf?.let { Timestamp.from(it) }, asOf?.let { Timestamp.from(it) }, legalEntityId, legalEntityId,
                after?.let { Timestamp.from(it.first) }, after?.let { Timestamp.from(it.first) }, after?.second, limit)
        }
    }

    override fun journal(ctx: RequestContext, eventId: UUID): JournalView {
        require(ctx, SCOPE_READ)
        return ctxTx.run(ctx, readOnly = true) { j ->
            val id = j.query("SELECT id FROM taktovia.journal WHERE event_id = ?", { rs, _ -> rs.getObject(1, UUID::class.java) }, eventId).firstOrNull() ?: throw ProblemException.notFound()
            journal(j, id)
        }
    }

    override fun actualCostBasis(ctx: RequestContext, legalEntityId: UUID, currency: String): Long {
        require(ctx, SCOPE_READ)
        return ctxTx.run(ctx, readOnly = true) { j ->
            j.queryForObject("SELECT coalesce(sum(e.cost_basis_minor), 0) FROM taktovia.economic_event e JOIN taktovia.journal jn ON jn.tenant_id = e.tenant_id AND jn.event_id = e.id AND jn.state = 'POSTED' " +
                "WHERE e.scenario = 'ACTUAL' AND e.legal_entity_id = ? AND e.currency = ?", Long::class.java, legalEntityId, currency)!!
        }
    }

    override fun accountBalances(ctx: RequestContext, legalEntityId: UUID, currency: String): Map<String, Long> {
        require(ctx, SCOPE_READ)
        return ctxTx.run(ctx, readOnly = true) { j ->
            j.query("SELECT l.account_code, sum(CASE l.side WHEN 'D' THEN l.amount_minor ELSE -l.amount_minor END) AS balance FROM taktovia.journal_line l JOIN taktovia.journal jn ON jn.tenant_id = l.tenant_id AND jn.id = l.journal_id " +
                "WHERE jn.state = 'POSTED' AND jn.legal_entity_id = ? AND jn.currency = ? GROUP BY l.account_code ORDER BY l.account_code", { rs, _ -> rs.getString(1) to rs.getBigDecimal(2).longValueExact() }, legalEntityId, currency).toMap()
        }
    }

    private fun event(j: JdbcOperations, id: UUID): CostEventView = j.query("SELECT $EVENT_COLUMNS FROM taktovia.economic_event e WHERE e.id = ?", EVENT_MAPPER, id).single()

    private fun journal(j: JdbcOperations, id: UUID): JournalView {
        val lines = j.query("SELECT account_code, side, amount_minor FROM taktovia.journal_line WHERE journal_id = ? ORDER BY account_code, side", { rs, _ -> JournalLineView(rs.getString(1), rs.getString(2), rs.getLong(3)) }, id)
        return j.query("SELECT id, event_id, state, accounting_date, period_id, posted_by FROM taktovia.journal WHERE id = ?",
            { rs, _ -> JournalView(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getString(3), rs.getDate(4).toLocalDate(), rs.getObject(5, UUID::class.java), rs.getString(6), lines) }, id).single()
    }

    private companion object {
        const val SCOPE_APPROVE = "expenses:approve"
        const val DOC_COLUMNS = DocumentRows.COLUMNS
        const val OBJ_COLUMNS = CostAssignmentRows.COLUMNS
        const val DOC_SQL = "SELECT $DOC_COLUMNS, d.approved_by FROM taktovia.expense_document d WHERE d.id = ? AND d.upload_ticket_id IS NOT NULL FOR NO KEY UPDATE"
        const val ASSIGNED_SQL = "SELECT $OBJ_COLUMNS FROM taktovia.expense_cost_assignment a JOIN taktovia.cost_object o ON o.tenant_id = a.tenant_id AND o.id = a.cost_object_id WHERE a.document_id = ? ORDER BY o.id FOR SHARE OF o"
        const val SCOPE_READ = "costs:read"
        const val MAX_SAFE = 9_007_199_254_740_991L
        const val TAX_RULE_REVISION = "TEST-2026-1"   // Testregel (D-06): Vorsteuer voll abziehbar; produktive Steuerregeln sind nicht beschlossen
        val CATEGORY = Regex("[A-Z][A-Z0-9_]{0,39}")

        const val EVENT_COLUMNS = "e.id, e.legal_entity_id, e.source_system, e.source_ref, e.event_type, e.scenario, e.category, e.currency, e.net_minor, e.tax_minor, e.gross_minor, e.recoverable_tax_minor, " +
            "e.cost_basis_minor, e.tax_rule_revision, e.occurred_at, e.booked_at, e.document_id, e.reverses_id"

        /** Ist-Sicht: nur Ereignisse mit gebuchtem Journal, Szenario ACTUAL. */
        const val LIST_SQL = "SELECT $EVENT_COLUMNS FROM taktovia.economic_event e JOIN taktovia.journal jn ON jn.tenant_id = e.tenant_id AND jn.event_id = e.id AND jn.state = 'POSTED' " +
            "WHERE e.scenario = 'ACTUAL' AND (CAST(? AS timestamptz) IS NULL OR e.booked_at <= ?) AND (CAST(? AS uuid) IS NULL OR e.legal_entity_id = ?) " +
            "AND (CAST(? AS timestamptz) IS NULL OR (e.booked_at, e.id) > (?, CAST(? AS uuid))) ORDER BY e.booked_at, e.id LIMIT ?"

        val EVENT_MAPPER = RowMapper { rs, _ ->
            CostEventView(rs.getObject("id", UUID::class.java), rs.getObject("legal_entity_id", UUID::class.java), rs.getString("source_system"), rs.getString("source_ref"), rs.getString("event_type"),
                rs.getString("scenario"), rs.getString("category"), rs.getString("currency").trim(), rs.getLong("net_minor"), rs.getLong("tax_minor"), rs.getLong("gross_minor"), rs.getLong("recoverable_tax_minor"),
                rs.getLong("cost_basis_minor"), rs.getString("tax_rule_revision"), rs.getTimestamp("occurred_at").toInstant(), rs.getTimestamp("booked_at").toInstant(),
                rs.getObject("document_id", UUID::class.java), rs.getObject("reverses_id", UUID::class.java))
        }
    }
}

package de.taktovia.documentsfinance.internal

import de.taktovia.documentsfinance.api.AllocationCalculator
import de.taktovia.documentsfinance.api.AllocationLineView
import de.taktovia.documentsfinance.api.AllocationMode
import de.taktovia.documentsfinance.api.AllocationView
import de.taktovia.documentsfinance.api.CostEventCorrections
import de.taktovia.documentsfinance.api.CreateFiscalPeriod
import de.taktovia.documentsfinance.api.FiscalPeriodView
import de.taktovia.documentsfinance.api.FiscalPeriods
import de.taktovia.documentsfinance.api.ReversalResult
import de.taktovia.documentsfinance.api.ReverseCostEvent
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
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import java.math.BigDecimal
import java.sql.Date
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

private const val MAX_SAFE = 9_007_199_254_740_991L

private fun rootCause(e: Throwable): Throwable { var t = e; while (t.cause != null && t.cause !== t) t = t.cause!!; return t }

/** Fiskalperioden (WP-M4-36). Alle Schreibzugriffe sperren die Periodenzeile `FOR UPDATE`; derselbe Weg nutzen Posting, Aufteilung und Gegenereignis. */
internal class JdbcFiscalPeriods(private val ctxTx: ContextTransactions, private val audit: AuditPort, private val outbox: Outbox, private val clock: Clock) : FiscalPeriods {

    override fun create(ctx: RequestContext, cmd: CreateFiscalPeriod): FiscalPeriodView {
        authorize(ctx, SCOPE_MANAGE)
        if (!cmd.endsOn.isAfter(cmd.startsOn)) throw ProblemException.invalid(Violation("/ends_on", ViolationCode.INVALID_VALUE, "Ende muss nach dem Beginn liegen"))
        if (cmd.startsOn.plusDays(MAX_DAYS).isBefore(cmd.endsOn)) throw ProblemException.invalid(Violation("/ends_on", ViolationCode.ABOVE_MAXIMUM, "höchstens $MAX_DAYS Tage"))
        return try {
            ctxTx.run(ctx) { j -> doCreate(j, ctx, cmd) }
        } catch (e: DataIntegrityViolationException) {
            if ((rootCause(e) as? SQLException)?.sqlState == EXCLUSION_VIOLATION) throw overlap() else throw e
        }
    }

    private fun overlap() = ProblemException(ErrorCode.CONFLICT, "Die Periode überschneidet sich mit einer vorhandenen Periode des Rechtsträgers.")

    private fun doCreate(j: JdbcOperations, ctx: RequestContext, cmd: CreateFiscalPeriod): FiscalPeriodView {
        if (j.queryForObject("SELECT count(*) FROM taktovia.legal_entity WHERE id = ?", Int::class.java, cmd.legalEntityId) != 1) throw ProblemException.notFound()
        // Anlegen je Rechtsträger nacheinander: gleichzeitige Exclusion-Prüfungen könnten sich sonst gegenseitig verklemmen; der Constraint bleibt die Absicherung
        j.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended('fiscal_period:' || taktovia.current_tenant()::text || ':' || ?, 0))::text", String::class.java, cmd.legalEntityId.toString())
        if (j.queryForObject("SELECT count(*) FROM taktovia.fiscal_period WHERE legal_entity_id = ? AND daterange(starts_on, ends_on, '[)') && daterange(?, ?, '[)')", Int::class.java,
                cmd.legalEntityId, Date.valueOf(cmd.startsOn), Date.valueOf(cmd.endsOn))!! > 0) throw overlap()
        val id = UUID.randomUUID()
        j.update("INSERT INTO taktovia.fiscal_period (tenant_id, id, legal_entity_id, starts_on, ends_on, created_by) VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?)",
            id, cmd.legalEntityId, Date.valueOf(cmd.startsOn), Date.valueOf(cmd.endsOn), ctx.actor.subject.take(200))
        return read(j, id)
    }

    override fun close(ctx: RequestContext, periodId: UUID): FiscalPeriodView {
        authorize(ctx, SCOPE_CLOSE)
        return ctxTx.run(ctx) { j -> doClose(j, ctx, periodId) }
    }

    @AuditRequired
    private fun doClose(j: JdbcOperations, ctx: RequestContext, periodId: UUID): FiscalPeriodView {
        val p = j.query("$SELECT WHERE id = ? FOR UPDATE", MAPPER, periodId).firstOrNull() ?: throw ProblemException.notFound()
        if (p.closed) throw ProblemException(ErrorCode.PERIOD_CLOSED, "Die Periode ist bereits abgeschlossen; eine geschlossene Periode lässt sich nicht wieder öffnen.")
        val now = clock.instant()
        check(j.update("UPDATE taktovia.fiscal_period SET closed_at = ?, closed_by = ? WHERE id = ? AND closed_at IS NULL", Timestamp.from(now), ctx.actor.subject.take(200), periodId) == 1)
        audit.record(j, AuditEntry(DocumentRows.actor(ctx), AuditActions.FINANCE_PERIOD_CLOSED, ObjectRef("fiscal_period", periodId), ctx.correlationId,
            mapOf("legal_entity_id" to p.legalEntityId.toString(), "starts_on" to p.startsOn.toString(), "ends_on" to p.endsOn.toString())))
        outbox.append(j, OutboxEvent(periodId, 1, "fiscal_period.closed", linkedMapOf("period_id" to periodId.toString(), "legal_entity_id" to p.legalEntityId.toString(),
            "starts_on" to p.startsOn.toString(), "ends_on" to p.endsOn.toString(), "closed_at" to now.toString()), now, correlationId = ctx.correlationId))
        return read(j, periodId)
    }

    override fun list(ctx: RequestContext, legalEntityId: UUID?, limit: Int, after: Pair<LocalDate, UUID>?): List<FiscalPeriodView> {
        authorize(ctx, SCOPE_READ, write = false)
        if (limit !in 1..200) throw ProblemException.invalid(Violation("/limit", ViolationCode.INVALID_VALUE))
        return ctxTx.run(ctx, readOnly = true) { j ->
            j.query("$SELECT WHERE (CAST(? AS uuid) IS NULL OR legal_entity_id = ?) AND (CAST(? AS date) IS NULL OR (starts_on, id) > (?, CAST(? AS uuid))) ORDER BY starts_on, id LIMIT ?", MAPPER,
                legalEntityId, legalEntityId, after?.first?.let { Date.valueOf(it) }, after?.first?.let { Date.valueOf(it) }, after?.second, limit)
        }
    }

    override fun get(ctx: RequestContext, periodId: UUID): FiscalPeriodView {
        authorize(ctx, SCOPE_READ, write = false)
        return ctxTx.run(ctx, readOnly = true) { j -> j.query("$SELECT WHERE id = ?", MAPPER, periodId).firstOrNull() ?: throw ProblemException.notFound() }
    }

    private fun read(j: JdbcOperations, id: UUID): FiscalPeriodView = j.query("$SELECT WHERE id = ?", MAPPER, id).single()

    /** Verwalten und Abschließen: Scope und Finanzrolle (Mensch, kein Dienstkonto); Lesen: Scope und Finanzrolle. */
    private fun authorize(ctx: RequestContext, scope: String, write: Boolean = true) {
        if (!ctx.hasScope(scope) || !isFinance(ctx) || (write && ctx.actor.type != ActorType.HUMAN)) throw ProblemException(ErrorCode.FORBIDDEN)
    }

    private companion object {
        const val SCOPE_MANAGE = "periods:manage"
        const val SCOPE_CLOSE = "periods:close"
        const val SCOPE_READ = "periods:read"
        const val EXCLUSION_VIOLATION = "23P01"
        const val MAX_DAYS = 400L
        const val SELECT = "SELECT id, legal_entity_id, starts_on, ends_on, closed_at, closed_by, created_by FROM taktovia.fiscal_period"
        val MAPPER = RowMapper { rs, _ ->
            FiscalPeriodView(rs.getObject("id", UUID::class.java), rs.getObject("legal_entity_id", UUID::class.java), rs.getDate("starts_on").toLocalDate(), rs.getDate("ends_on").toLocalDate(),
                rs.getTimestamp("closed_at")?.toInstant(), rs.getString("closed_by"), rs.getString("created_by"))
        }
    }
}

/**
 * Gegenereignis (WP-M4-36). Sperren: Advisory-Lock je Ursprung (serialisiert Gegenereignisse desselben Ursprungs, auch gegen den Datenbank-Trigger), dann die Zielperiode `FOR UPDATE`. Die Zielperiode ist die erste
 * offene Periode ab dem Buchungsdatum; wird sie zwischen Auswahl und Sperre geschlossen, wird neu gewählt.
 */
internal class JdbcCostEventCorrections(private val ctxTx: ContextTransactions, private val audit: AuditPort, private val outbox: Outbox, private val clock: Clock) : CostEventCorrections {

    override fun reverse(ctx: RequestContext, eventId: UUID, cmd: ReverseCostEvent): ReversalResult {
        if (!ctx.hasScope(SCOPE_REVERSE) || !isFinance(ctx) || ctx.actor.type != ActorType.HUMAN) throw ProblemException(ErrorCode.FORBIDDEN)
        val reason = cmd.reason.trim()
        if (reason.length < 3) throw ProblemException.invalid(Violation("/reason", if (reason.isEmpty()) ViolationCode.REQUIRED else ViolationCode.TOO_SHORT))
        if (reason.length > 1000) throw ProblemException.invalid(Violation("/reason", ViolationCode.TOO_LONG))
        cmd.creditGrossMinor?.let { if (it <= 0) throw ProblemException.invalid(Violation("/credit_gross_minor", ViolationCode.BELOW_MINIMUM)); if (it > MAX_SAFE) throw ProblemException.invalid(Violation("/credit_gross_minor", ViolationCode.MONEY_OUT_OF_RANGE)) }
        return ctxTx.run(ctx) { j -> doReverse(j, ctx, eventId, cmd, reason) }
    }

    private class Origin(val id: UUID, val entity: UUID, val currency: String, val category: String, val net: Long, val tax: Long, val gross: Long, val recoverable: Long, val taxRule: String,
        val occurredAt: java.time.Instant, val serviceFrom: java.time.Instant?, val serviceTo: java.time.Instant?, val documentId: UUID?, val reverses: UUID?, val scenario: String, val journalId: UUID?, val journalState: String?,
        val behavior: String?, val attribution: String?, val costObjectId: UUID?)

    /** Gegenereignis in der laufenden Mandantentransaktion des Aufrufers (Umklassifikation, WP-M4-39). */
    internal fun reverseIn(j: JdbcOperations, ctx: RequestContext, eventId: UUID, cmd: ReverseCostEvent, reason: String): ReversalResult = doReverse(j, ctx, eventId, cmd, reason)

    @AuditRequired
    private fun doReverse(j: JdbcOperations, ctx: RequestContext, eventId: UUID, cmd: ReverseCostEvent, reason: String): ReversalResult {
        j.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended('economic_event_reversal:' || taktovia.current_tenant()::text || ':' || ?, 0))::text", String::class.java, eventId.toString())
        val o = j.query("SELECT e.id, e.legal_entity_id, e.currency, e.category, e.net_minor, e.tax_minor, e.gross_minor, e.recoverable_tax_minor, e.tax_rule_revision, e.occurred_at, e.service_from, e.service_to, e.document_id, e.reverses_id, e.scenario, jn.id, jn.state, e.cost_behavior, e.attribution, e.cost_object_id " +
            "FROM taktovia.economic_event e LEFT JOIN taktovia.journal jn ON jn.tenant_id = e.tenant_id AND jn.event_id = e.id WHERE e.id = ?",
            { rs, _ -> Origin(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getString(3).trim(), rs.getString(4), rs.getLong(5), rs.getLong(6), rs.getLong(7), rs.getLong(8), rs.getString(9), rs.getTimestamp(10).toInstant(),
                rs.getTimestamp(11)?.toInstant(), rs.getTimestamp(12)?.toInstant(), rs.getObject(13, UUID::class.java), rs.getObject(14, UUID::class.java), rs.getString(15), rs.getObject(16, UUID::class.java), rs.getString(17), rs.getString(18), rs.getString(19), rs.getObject(20, UUID::class.java)) }, eventId).firstOrNull() ?: throw ProblemException.notFound()
        if (o.reverses != null) throw ProblemException(ErrorCode.CONFLICT, "Ein Gegenereignis wird nicht selbst aufgehoben.")
        if (o.scenario != "ACTUAL" || o.journalState != "POSTED" || o.journalId == null) throw ProblemException(ErrorCode.CONFLICT, "Korrigiert wird nur ein gebuchtes Ist-Kostenereignis.")

        // bereits korrigierter Anteil: Summe der Gegenereignisse je Betragsart
        val rev = j.query("SELECT coalesce(sum(net_minor), 0), coalesce(sum(tax_minor), 0), coalesce(sum(gross_minor), 0), coalesce(sum(recoverable_tax_minor), 0) FROM taktovia.economic_event WHERE reverses_id = ?",
            { rs, _ -> longArrayOf(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)) }, eventId).single()
        val remNet = o.net + rev[0]; val remTax = o.tax + rev[1]; val remGross = o.gross + rev[2]; val remRec = o.recoverable + rev[3]
        if (remGross <= 0L) throw ProblemException(ErrorCode.ALREADY_REVERSED, "Das Kostenereignis ist bereits vollständig durch Gegenereignisse aufgehoben.")
        val credit = cmd.creditGrossMinor ?: remGross
        if (credit > remGross) throw ProblemException.invalid(Violation("/credit_gross_minor", ViolationCode.ABOVE_MAXIMUM, "höchstens der noch nicht korrigierte Rest ($remGross)"))
        if (j.queryForObject("SELECT count(*) FROM taktovia.economic_event WHERE reverses_id = ? AND gross_minor = ?", Int::class.java, eventId, -credit)!! > 0)
            throw ProblemException(ErrorCode.ALREADY_REVERSED, "Zu diesem Kostenereignis besteht bereits ein Gegenereignis in dieser Höhe (einmalige Wirkung).")

        // Betragsarten des Gegenereignisses: der Rest exakt, ein Teilbetrag anteilig nach Largest Remainder (nie Gleitkomma)
        val net: Long; val tax: Long; val rec: Long
        if (credit == remGross) { net = remNet; tax = remTax; rec = remRec }
        else {
            val (n, t) = AllocationCalculator.distribute(credit, listOf(UUID(0, 1) to BigDecimal(remNet), UUID(0, 2) to BigDecimal(remTax)))
            val r = AllocationCalculator.distribute(credit, listOf(UUID(0, 1) to BigDecimal(remRec), UUID(0, 2) to BigDecimal(remGross - remRec))).first()
            net = n; tax = t; rec = minOf(r, t)
        }

        // Zielperiode: erste offene Periode ab dem Buchungsdatum (die Periode des Ursprungs kann längst geschlossen sein)
        val requested = cmd.accountingDate ?: LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
        var period: Triple<UUID, LocalDate, LocalDate>? = null
        repeat(MAX_TRIES) {
            if (period != null) return@repeat
            val candidate = j.query("SELECT id, starts_on, ends_on FROM taktovia.fiscal_period WHERE legal_entity_id = ? AND closed_at IS NULL AND ends_on > ? ORDER BY starts_on LIMIT 1",
                { rs, _ -> Triple(rs.getObject(1, UUID::class.java), rs.getDate(2).toLocalDate(), rs.getDate(3).toLocalDate()) }, o.entity, Date.valueOf(requested)).firstOrNull()
                ?: throw ProblemException(ErrorCode.CONFLICT, "Für die Gegenbuchung gibt es keine offene Periode des Rechtsträgers ab dem ${requested}.")
            val stillOpen = j.query("SELECT closed_at IS NULL FROM taktovia.fiscal_period WHERE id = ? FOR UPDATE", { rs, _ -> rs.getBoolean(1) }, candidate.first).single()
            if (stillOpen) period = candidate
        }
        val target = period ?: throw ProblemException(ErrorCode.CONFLICT, "Keine offene Periode verfügbar (gleichzeitiger Abschluss).")
        val accountingDate = if (requested.isBefore(target.second)) target.second else requested

        val now = clock.instant(); val newId = UUID.randomUUID(); val journalId = UUID.randomUUID()
        val inserted = j.update("INSERT INTO taktovia.economic_event (tenant_id, id, legal_entity_id, source_system, source_ref, event_type, scenario, category, currency, net_minor, tax_minor, gross_minor, recoverable_tax_minor, tax_rule_revision, " +
            "occurred_at, booked_at, service_from, service_to, document_id, reverses_id, cost_behavior, attribution, cost_object_id) VALUES (taktovia.current_tenant(), ?, ?, 'EXPENSE', ?, 'EXPENSE_REVERSAL', 'ACTUAL', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
            newId, o.entity, "$eventId:REV:$credit", o.category, o.currency, -net, -tax, -credit, -rec, o.taxRule, Timestamp.from(accountingDate.atStartOfDay(ZoneOffset.UTC).toInstant()), Timestamp.from(now),
            Timestamp.from(o.serviceFrom ?: o.occurredAt), Timestamp.from(o.serviceTo ?: o.occurredAt), o.documentId, eventId, o.behavior, o.attribution, o.costObjectId)   // das Gegenereignis trägt Klassifikation und Kostenobjekt des Ursprungs (Summe je Klasse bleibt konsistent)
        if (inserted == 0) throw ProblemException(ErrorCode.ALREADY_REVERSED, "Zu diesem Kostenereignis besteht bereits ein Gegenereignis in dieser Höhe (einmalige Wirkung).")

        // Gegenbuchung: dieselben Konten wie der Ursprung, Seiten getauscht, nur positive Zeilen (Kern-DDL: amount_minor > 0)
        j.update("INSERT INTO taktovia.journal (tenant_id, id, event_id, legal_entity_id, currency, period_id, accounting_date, state) VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?, 'DRAFT')",
            journalId, newId, o.entity, o.currency, target.first, Date.valueOf(accountingDate))
        val accounts = accountsOf(j, o)
        fun line(role: String, side: String, amount: Long) {
            if (amount > 0) j.update("INSERT INTO taktovia.journal_line (tenant_id, id, journal_id, account_code, side, amount_minor, account_role) VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), journalId, accounts.getValue(role), side, amount, role)
        }
        line("EXPENSE", "C", credit - rec); line("INPUT_TAX", "C", rec); line("PAYABLE", "D", credit)
        check(j.update("UPDATE taktovia.journal SET state = 'POSTED', posted_by = ? WHERE id = ? AND state = 'DRAFT'", DocumentRows.actor(ctx).value, journalId) == 1)   // Trigger: ausgeglichen, offene Periode

        // Aufteilung: die gespeicherten Teilbeträge der aktiven Umlage des Ursprungs, negativ und im Verhältnis des korrigierten Anteils (für den vollen Rest exakt die Negation)
        val allocation = AllocationRows.activeOf(j, eventId)?.let { orig ->
            val basis = -(credit - rec)
            val amounts = AllocationCalculator.distribute(basis, orig.lines.map { it.costObjectId to BigDecimal(it.amountMinor) })
            val resolved = orig.lines.mapIndexed { i, l -> Resolved(l.costObjectId, l.costObjectKind, amounts[i], AllocationCalculator.percentOf(amounts[i], basis), null, null, null, amounts[i]) }
            AllocationRows.persist(j, audit, outbox, clock, ctx, EventRef(newId, o.entity, o.currency, basis, AllocationRows.exponentOf(j, o.currency)), AllocationMode.AMOUNT, "REVERSAL:${orig.policyRevision}".take(256), reason, resolved)
        }
        audit.record(j, AuditEntry(DocumentRows.actor(ctx), AuditActions.FINANCE_COST_EVENT_REVERSED, ObjectRef("cost_event", newId), ctx.correlationId,
            mapOf("reverses_id" to eventId.toString(), "period_id" to target.first.toString(), "gross_minor" to -credit, "reason" to reason)))
        outbox.append(j, OutboxEvent(newId, 1, "cost_event.reversed", linkedMapOf("event_id" to newId.toString(), "reverses_id" to eventId.toString(), "legal_entity_id" to o.entity.toString(), "currency" to o.currency,
            "gross_minor" to -credit, "cost_basis_minor" to -(credit - rec), "period_id" to target.first.toString(), "booked_at" to now.toString()), now, correlationId = ctx.correlationId))
        return ReversalResult(JdbcCostEvents.eventOf(j, newId), JdbcCostEvents.journalOf(j, journalId), allocation)
    }

    /** Konten je Rolle: aus den Zeilen des Ursprungsjournals, sonst (ältere Zeilen ohne Rolle) aus der Kontierungsregel der Kostenart. */
    private fun accountsOf(j: JdbcOperations, o: Origin): Map<String, String> {
        val byRole = j.query("SELECT account_role, account_code FROM taktovia.journal_line WHERE journal_id = ? AND account_role IS NOT NULL", { rs, _ -> rs.getString(1) to rs.getString(2) }, o.journalId).toMap()
        val needed = listOf("EXPENSE", "INPUT_TAX", "PAYABLE")
        if (byRole.keys.containsAll(needed)) return byRole
        val mapping = j.query("SELECT expense_account, input_tax_account, payable_account FROM taktovia.accounting_mapping WHERE legal_entity_id = ? AND category = ?",
            { rs, _ -> mapOf("EXPENSE" to rs.getString(1), "INPUT_TAX" to rs.getString(2), "PAYABLE" to rs.getString(3)) }, o.entity, o.category).firstOrNull()
            ?: throw ProblemException(ErrorCode.CONFLICT, "Für die Kostenart fehlt die Kontierungsregel des Rechtsträgers.")
        return mapping + byRole
    }

    private companion object {
        const val SCOPE_REVERSE = "costs:reverse"
        const val MAX_TRIES = 5
    }
}

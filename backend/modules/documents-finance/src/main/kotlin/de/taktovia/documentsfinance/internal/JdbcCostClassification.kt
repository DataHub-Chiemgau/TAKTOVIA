package de.taktovia.documentsfinance.internal

import de.taktovia.documentsfinance.api.AllocationMode
import de.taktovia.documentsfinance.api.CostAttribution
import de.taktovia.documentsfinance.api.CostBehavior
import de.taktovia.documentsfinance.api.CostClassCell
import de.taktovia.documentsfinance.api.CostClassTotals
import de.taktovia.documentsfinance.api.CostClassifications
import de.taktovia.documentsfinance.api.CostSummaryQuery
import de.taktovia.documentsfinance.api.CostSummaryRow
import de.taktovia.documentsfinance.api.CostSummaryView
import de.taktovia.documentsfinance.api.ReclassificationResult
import de.taktovia.documentsfinance.api.ReclassifyCostEvent
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
import org.springframework.jdbc.core.JdbcOperations
import java.sql.Date
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Wirksame Kostenzeilen je Ereignis, gemeinsam für die Klassifikationsauswertung und die Fahrzeugkosten (WP-M4-39/-40). Je gebuchtem Ist-Ereignis (Journal `POSTED`) zählt **entweder** die aktive Umlage
 * (Zeilen je Kostenobjekt, Summe = Kostenbasis) **oder**, ohne Umlage, sein Direktbezug (`cost_object_id`) bzw. sein einziges zugeordnetes Kostenobjekt, sonst „nicht zugeordnet“ (Kostenobjekt `NULL`). Original und
 * Umlage werden dadurch nie zusammengezählt. Spalten: Ereignis `id`, `legal_entity_id`, `category`, `cost_behavior`, `attribution`, `service_from`, `accounting_date`, Kostenobjekt `obj`, Betrag `amount`.
 * Alles in **einer** Anweisung: eine konsistente Momentaufnahme, auch gegen gleichzeitige Umklassifikation (Gegenereignis und Neubuchung committen gemeinsam).
 */
internal object EffectiveLines {
    /** `eventFilter`: zusätzliche Bedingungen auf `e` und `jn` (beginnend mit ` AND `), z. B. Währung und Periode. */
    fun cte(eventFilter: String): String =
        "WITH ev AS (SELECT e.tenant_id, e.id, e.legal_entity_id, e.category, e.cost_behavior, e.attribution, e.cost_basis_minor, e.document_id, e.cost_object_id, e.service_from, e.booked_at, jn.accounting_date " +
            "FROM taktovia.economic_event e JOIN taktovia.journal jn ON jn.tenant_id = e.tenant_id AND jn.event_id = e.id AND jn.state = 'POSTED' WHERE e.scenario = 'ACTUAL'$eventFilter), " +
            "lines AS (" +
            "SELECT ev.id, ev.legal_entity_id, ev.category, ev.cost_behavior, ev.attribution, ev.service_from, ev.accounting_date, ev.booked_at, l.cost_object_id AS obj, l.amount_minor AS amount " +
            "FROM ev JOIN taktovia.allocation_activation a ON a.tenant_id = ev.tenant_id AND a.event_id = ev.id JOIN taktovia.allocation_line l ON l.tenant_id = a.tenant_id AND l.allocation_id = a.allocation_id " +
            "UNION ALL " +
            "SELECT ev.id, ev.legal_entity_id, ev.category, ev.cost_behavior, ev.attribution, ev.service_from, ev.accounting_date, ev.booked_at, " +
            "COALESCE(ev.cost_object_id, (SELECT (array_agg(c.cost_object_id))[1] FROM taktovia.expense_cost_assignment c WHERE c.tenant_id = ev.tenant_id AND c.document_id = ev.document_id HAVING count(*) = 1)) AS obj, " +
            "ev.cost_basis_minor AS amount FROM ev WHERE NOT EXISTS (SELECT 1 FROM taktovia.allocation_activation a WHERE a.tenant_id = ev.tenant_id AND a.event_id = ev.id)) "
}

/** Gemeinsame Prüfung der Pflichtangaben einer Auswertung (Kap. 03 §1.1): Bewertungsart, Währung (und Periode) explizit; für P1 nur `ACTUAL`. */
internal object ValuationRules {
    val CURRENCY = Regex("[A-Z]{3}")

    fun checkValuationAndCurrency(valuation: String?, currency: String?): List<Violation> = buildList {
        if (valuation == null) add(Violation("/valuation", ViolationCode.REQUIRED))
        else if (valuation != "ACTUAL") add(Violation("/valuation", ViolationCode.INVALID_VALUE, "für P1 nur ACTUAL (Plan und Forecast folgen mit FCO-017)"))
        if (currency == null) add(Violation("/currency", ViolationCode.REQUIRED))
        else if (!CURRENCY.matches(currency)) add(Violation("/currency", ViolationCode.PATTERN_MISMATCH))
    }
}

internal class JdbcCostClassification(private val ctxTx: ContextTransactions, private val audit: AuditPort, private val outbox: Outbox, private val clock: Clock) : CostClassifications {
    private val corrections = JdbcCostEventCorrections(ctxTx, audit, outbox, clock)

    // ---- Auswertung ----

    override fun summary(ctx: RequestContext, query: CostSummaryQuery): CostSummaryView {
        require(ctx, SCOPE_READ)
        val violations = buildList {
            addAll(ValuationRules.checkValuationAndCurrency(query.valuation, query.currency))
            if (query.periodFrom == null) add(Violation("/period_from", ViolationCode.REQUIRED))
            if (query.periodTo == null) add(Violation("/period_to", ViolationCode.REQUIRED))
            if (query.periodFrom != null && query.periodTo != null) {
                if (!query.periodTo.isAfter(query.periodFrom)) add(Violation("/period_to", ViolationCode.INVALID_VALUE, "das Ende der Periode liegt nach ihrem Beginn"))
                else if (ChronoUnit.DAYS.between(query.periodFrom, query.periodTo) > MAX_DAYS) add(Violation("/period_to", ViolationCode.ABOVE_MAXIMUM, "höchstens $MAX_DAYS Tage"))
            }
        }
        if (violations.isNotEmpty()) throw ProblemException.invalid(*violations.toTypedArray())
        val from = query.periodFrom!!; val to = query.periodTo!!; val currency = query.currency!!
        return ctxTx.run(ctx, readOnly = true) { j ->
            query.legalEntityId?.let { if (j.queryForObject("SELECT count(*) FROM taktovia.legal_entity WHERE id = ?", Int::class.java, it) != 1) throw ProblemException.notFound() }
            query.vehicleId?.let { if (j.queryForObject("SELECT count(*) FROM taktovia.vehicle WHERE id = ?", Int::class.java, it) != 1) throw ProblemException.notFound() }
            val filter = " AND e.currency = ? AND jn.accounting_date >= ? AND jn.accounting_date < ?" + (if (query.legalEntityId != null) " AND e.legal_entity_id = ?" else "")
            val args = mutableListOf<Any?>(currency, Date.valueOf(from), Date.valueOf(to)); query.legalEntityId?.let { args += it }
            val vehicleFilter = if (query.vehicleId != null) " WHERE co.vehicle_id = ?" else ""; query.vehicleId?.let { args += it }
            val sql = EffectiveLines.cte(filter) +
                "SELECT l.obj, co.kind, co.business_ref, co.vehicle_id, l.cost_behavior, l.attribution, sum(l.amount) AS amount, count(DISTINCT l.id) AS events, now() AS as_of " +
                "FROM lines l LEFT JOIN taktovia.cost_object co ON co.tenant_id = taktovia.current_tenant() AND co.id = l.obj$vehicleFilter " +
                "GROUP BY l.obj, co.kind, co.business_ref, co.vehicle_id, l.cost_behavior, l.attribution ORDER BY co.kind NULLS LAST, co.business_ref NULLS LAST, l.obj NULLS LAST, l.cost_behavior NULLS LAST, l.attribution NULLS LAST"
            class Raw(val obj: UUID?, val kind: String?, val ref: String?, val vehicle: UUID?, val cell: CostClassCell)
            var asOf: Instant? = null
            val raws = j.query(sql, { rs, _ ->   // sql-lint: ok nur konstante Fragmente (Filterbedingungen), alle Werte als Parameter gebunden
                asOf = rs.getTimestamp("as_of").toInstant()
                Raw(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3), rs.getObject(4, UUID::class.java),
                    CostClassCell(rs.getString(5)?.let { CostBehavior.valueOf(it) }, rs.getString(6)?.let { CostAttribution.valueOf(it) }, rs.getBigDecimal("amount").longValueExact(), rs.getLong("events")))
            }, *args.toTypedArray())
            val rows = raws.groupBy { it.obj }.map { (obj, rs) ->
                val first = rs.first()
                CostSummaryRow(obj, first.kind ?: UNALLOCATED, first.ref, first.vehicle, rs.map { it.cell }, totalsOf(rs.map { it.cell }))
            }
            // alle Zellen einer Zeile stammen aus derselben Anweisung; die Gesamtsumme ist die Summe der Zeilen (und, ohne Fahrzeugfilter, der wirksamen Ist-Kosten)
            val allCells = raws.map { it.cell }
            val events = j.query(EffectiveLines.cte(filter) + "SELECT count(DISTINCT l.id) FROM lines l" + (if (query.vehicleId != null) " LEFT JOIN taktovia.cost_object co ON co.tenant_id = taktovia.current_tenant() AND co.id = l.obj WHERE co.vehicle_id = ?" else ""),   // sql-lint: ok nur konstante Fragmente (Filterbedingungen), alle Werte als Parameter gebunden
                { rs, _ -> rs.getLong(1) }, *args.toTypedArray()).single()
            CostSummaryView("ACTUAL", currency, from, to, PERIOD_BASIS, asOf ?: clock.instant(), rows, totalsOf(allCells), events)
        }
    }

    // ---- Umklassifikation ----

    override fun reclassify(ctx: RequestContext, eventId: UUID, cmd: ReclassifyCostEvent): ReclassificationResult {
        if (!ctx.hasScope(SCOPE_REVERSE) || !isFinance(ctx) || ctx.actor.type != ActorType.HUMAN) throw ProblemException(ErrorCode.FORBIDDEN)
        val reason = cmd.reason.trim()
        if (reason.length < 3) throw ProblemException.invalid(Violation("/reason", if (reason.isEmpty()) ViolationCode.REQUIRED else ViolationCode.TOO_SHORT))
        if (reason.length > 1000) throw ProblemException.invalid(Violation("/reason", ViolationCode.TOO_LONG))
        return ctxTx.run(ctx) { j -> doReclassify(j, ctx, eventId, cmd, reason) }
    }

    private class Origin(
        val id: UUID, val entity: UUID, val currency: String, val category: String, val net: Long, val tax: Long, val gross: Long, val recoverable: Long, val taxRule: String, val occurredAt: Instant,
        val serviceFrom: Instant?, val serviceTo: Instant?, val documentId: UUID?, val reverses: UUID?, val behavior: String?, val attribution: String?, val costObjectId: UUID?, val sourceSystem: String,
    )

    /**
     * Umklassifikation in einer Transaktion: Gegenereignis des Ursprungs (volle Höhe, Klassifikation des Ursprungs) und Neubuchung mit der neuen Klassifikation, beide in derselben Zielperiode.
     * Die Sperre je Ursprung (Advisory-Lock, wie beim Gegenereignis) serialisiert gleichzeitige Umklassifikationen; die Datenbank lässt zusätzlich höchstens eine Neubuchung je Ursprung zu.
     */
    @AuditRequired
    private fun doReclassify(j: JdbcOperations, ctx: RequestContext, eventId: UUID, cmd: ReclassifyCostEvent, reason: String): ReclassificationResult {
        j.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended('economic_event_reversal:' || taktovia.current_tenant()::text || ':' || ?, 0))::text", String::class.java, eventId.toString())
        val o = j.query("SELECT e.id, e.legal_entity_id, e.currency, e.category, e.net_minor, e.tax_minor, e.gross_minor, e.recoverable_tax_minor, e.tax_rule_revision, e.occurred_at, e.service_from, e.service_to, e.document_id, e.reverses_id, " +
            "e.cost_behavior, e.attribution, e.cost_object_id, e.source_system FROM taktovia.economic_event e WHERE e.id = ?",
            { rs, _ -> Origin(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getString(3).trim(), rs.getString(4), rs.getLong(5), rs.getLong(6), rs.getLong(7), rs.getLong(8), rs.getString(9),
                rs.getTimestamp(10).toInstant(), rs.getTimestamp(11)?.toInstant(), rs.getTimestamp(12)?.toInstant(), rs.getObject(13, UUID::class.java), rs.getObject(14, UUID::class.java), rs.getString(15), rs.getString(16),
                rs.getObject(17, UUID::class.java), rs.getString(18)) }, eventId).firstOrNull() ?: throw ProblemException.notFound()
        if (o.reverses != null) throw ProblemException(ErrorCode.CONFLICT, "Ein Gegenereignis wird nicht umklassifiziert.")
        if (o.behavior == cmd.costBehavior.name && o.attribution == cmd.attribution.name) throw ProblemException(ErrorCode.CONFLICT, "Das Ereignis hat bereits diese Klassifikation.")
        if (j.queryForObject("SELECT count(*) FROM taktovia.economic_event WHERE reverses_id = ?", Int::class.java, eventId)!! > 0) throw ProblemException(ErrorCode.ALREADY_REVERSED, "Das Kostenereignis ist bereits (teilweise) durch Gegenereignisse korrigiert.")
        val single = o.costObjectId ?: o.documentId?.let { doc ->
            j.query("SELECT cost_object_id FROM taktovia.expense_cost_assignment WHERE document_id = ?", { rs, _ -> rs.getObject(1, UUID::class.java) }, doc).takeIf { it.size == 1 }?.single()
        }

        val rev = corrections.reverseIn(j, ctx, eventId, ReverseCostEvent("Umklassifikation: $reason".take(1000), null, cmd.accountingDate), "Umklassifikation: $reason".take(1000))
        val now = clock.instant(); val newId = UUID.randomUUID(); val journalId = UUID.randomUUID()
        val inserted = j.update("INSERT INTO taktovia.economic_event (tenant_id, id, legal_entity_id, source_system, source_ref, event_type, scenario, cost_behavior, attribution, category, currency, net_minor, tax_minor, gross_minor, " +
            "recoverable_tax_minor, tax_rule_revision, occurred_at, booked_at, service_from, service_to, reclassified_from_id, cost_object_id) VALUES (taktovia.current_tenant(), ?, ?, 'RECLASSIFICATION', ?, 'RECLASSIFICATION', 'ACTUAL', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
            newId, o.entity, eventId.toString(), cmd.costBehavior.name, cmd.attribution.name, o.category, o.currency, o.net, o.tax, o.gross, o.recoverable, o.taxRule, Timestamp.from(rev.event.occurredAt), Timestamp.from(now),
            Timestamp.from(o.serviceFrom ?: o.occurredAt), Timestamp.from(o.serviceTo ?: o.occurredAt), eventId, single)
        if (inserted == 0) throw ProblemException(ErrorCode.ALREADY_REVERSED, "Zu diesem Kostenereignis besteht bereits eine Neubuchung (einmalige Wirkung).")

        // Neubuchung: dieselben Konten wie das Gegenereignis mit getauschten Seiten (also wie die Ursprungsbuchung), in derselben Periode und mit demselben Buchungsdatum
        j.update("INSERT INTO taktovia.journal (tenant_id, id, event_id, legal_entity_id, currency, period_id, accounting_date, state) VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?, 'DRAFT')",
            journalId, newId, o.entity, o.currency, rev.journal.periodId, Date.valueOf(rev.journal.accountingDate))
        j.query("SELECT account_code, side, amount_minor, account_role FROM taktovia.journal_line WHERE journal_id = ?", { rs, _ -> arrayOf(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getString(4)) }, rev.journal.id).forEach { l ->
            j.update("INSERT INTO taktovia.journal_line (tenant_id, id, journal_id, account_code, side, amount_minor, account_role) VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), journalId, l[0], if (l[1] == "D") "C" else "D", l[2], l[3])
        }
        check(j.update("UPDATE taktovia.journal SET state = 'POSTED', posted_by = ? WHERE id = ? AND state = 'DRAFT'", DocumentRows.actor(ctx).value, journalId) == 1)

        // Umlage des Ursprungs folgt der Neubuchung (gleiche Teilbeträge); Original und Umlage zählen weiter einmal
        AllocationRows.activeOf(j, eventId)?.let { orig ->
            val resolved = orig.lines.map { l -> Resolved(l.costObjectId, l.costObjectKind, l.amountMinor, l.percent, null, null, null, l.amountMinor) }
            AllocationRows.persist(j, audit, outbox, clock, ctx, EventRef(newId, o.entity, o.currency, o.gross - o.recoverable, AllocationRows.exponentOf(j, o.currency)), AllocationMode.AMOUNT, "RECLASSIFICATION:${orig.policyRevision}".take(256), reason, resolved)
        }
        audit.record(j, AuditEntry(DocumentRows.actor(ctx), AuditActions.FINANCE_COST_EVENT_RECLASSIFIED, ObjectRef("cost_event", newId), ctx.correlationId,
            mapOf("reclassified_from_id" to eventId.toString(), "reversal_id" to rev.event.id.toString(), "cost_behavior" to cmd.costBehavior.name, "attribution" to cmd.attribution.name,
                "previous_cost_behavior" to o.behavior, "previous_attribution" to o.attribution, "reason" to reason)))
        outbox.append(j, OutboxEvent(newId, 1, "cost_event.reclassified", linkedMapOf("event_id" to newId.toString(), "reclassified_from_id" to eventId.toString(), "reversal_id" to rev.event.id.toString(), "legal_entity_id" to o.entity.toString(),
            "currency" to o.currency, "cost_behavior" to cmd.costBehavior.name, "attribution" to cmd.attribution.name, "cost_basis_minor" to o.gross - o.recoverable, "booked_at" to now.toString()), now, correlationId = ctx.correlationId))
        val journal = JdbcCostEvents.journalOf(j, journalId)
        return ReclassificationResult(rev.event, JdbcCostEvents.eventOf(j, newId), rev.journal, journal)
    }

    internal companion object {
        const val SCOPE_READ = "costs:read"
        const val SCOPE_REVERSE = "costs:reverse"
        const val PERIOD_BASIS = "ACCOUNTING_DATE"
        const val UNALLOCATED = "UNALLOCATED"
        const val MAX_DAYS = 400L

        fun totalsOf(cells: List<CostClassCell>): CostClassTotals {
            fun sum(f: (CostClassCell) -> Boolean) = cells.filter(f).sumOf { it.amountMinor }
            return CostClassTotals(
                fixedMinor = sum { it.costBehavior == CostBehavior.FIXED }, variableMinor = sum { it.costBehavior == CostBehavior.VARIABLE }, mixedMinor = sum { it.costBehavior == CostBehavior.MIXED },
                unclassifiedMinor = sum { it.costBehavior == null }, directMinor = sum { it.attribution == CostAttribution.DIRECT }, allocatedMinor = sum { it.attribution == CostAttribution.ALLOCATED },
                totalMinor = cells.sumOf { it.amountMinor },
            )
        }
    }
}

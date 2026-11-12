package de.taktovia.documentsfinance.internal

import de.taktovia.documentsfinance.api.ALLOCATION_TARGET_KINDS
import de.taktovia.documentsfinance.api.AllocateCost
import de.taktovia.documentsfinance.api.AllocationCalculator
import de.taktovia.documentsfinance.api.AllocationLineInput
import de.taktovia.documentsfinance.api.AllocationLineView
import de.taktovia.documentsfinance.api.AllocationMode
import de.taktovia.documentsfinance.api.AllocationView
import de.taktovia.documentsfinance.api.CostAllocations
import de.taktovia.documentsfinance.api.EffectiveCost
import de.taktovia.documentsfinance.api.ExpenseFields
import de.taktovia.documentsfinance.api.ExpenseSplits
import de.taktovia.documentsfinance.api.ExpenseView
import de.taktovia.documentsfinance.api.IntegrityState
import de.taktovia.documentsfinance.api.SetSplit
import de.taktovia.documentsfinance.api.SplitLineView
import de.taktovia.documentsfinance.api.SplitView
import de.taktovia.documentsfinance.api.TaxTreatment
import de.taktovia.documentsfinance.internal.DocumentRows.SCOPE_WRITE
import de.taktovia.documentsfinance.internal.DocumentRows.isFinance
import de.taktovia.documentsfinance.internal.DocumentRows.require
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
import org.springframework.jdbc.core.JdbcOperations
import java.math.BigDecimal
import java.time.Clock
import java.util.UUID

private const val MAX_SAFE = 9_007_199_254_740_991L
private const val SCOPE_ALLOCATE = "costs:allocate"
private const val SCOPE_READ_COSTS = "costs:read"

/** Eine aufgelöste Zeile: Betrag, Anteil in Prozent (Spalte `weight`), die Vorgabe des Anwenders (Schlüsselmenge) und das Ziel. */
internal class Resolved(val costObjectId: UUID, val kind: String, val amountMinor: Long, val weightPercent: BigDecimal, val quantity: BigDecimal?, val unit: String?, val inputPercent: BigDecimal?, val inputAmount: Long?)

/** Das Ereignis, dessen Kostenbasis verteilt wird. */
internal class EventRef(val id: UUID, val legalEntityId: UUID, val currency: String, val basisMinor: Long, val exponent: Int)

/**
 * Prüfung und Auflösung einer Aufteilung (WP-M4-35). Die Vorgabeart ergibt sich aus den Zeilen: Beträge, Prozent oder Schlüsselmengen; eine Mischung wird bis zur Klärung (Offener Punkt 18) mit 422
 * abgewiesen. Beträge und Prozent werden nie als Gleitkomma gerechnet.
 */
internal object AllocationPlans {
    private fun invalid(pointer: String, code: ViolationCode, detail: String? = null): Nothing = throw ProblemException.invalid(if (detail == null) Violation(pointer, code) else Violation(pointer, code, detail))

    fun modeOf(lines: List<AllocationLineInput>): AllocationMode {
        if (lines.isEmpty()) invalid("/lines", ViolationCode.TOO_FEW_ITEMS)
        if (lines.size > 1000) invalid("/lines", ViolationCode.TOO_MANY_ITEMS)
        if (lines.map { it.costObjectId }.toSet().size != lines.size) invalid("/lines", ViolationCode.NOT_UNIQUE, "ein Kostenobjekt höchstens einmal je Revision")
        val amounts = lines.count { it.amountMinor != null }; val percents = lines.count { it.percent != null }
        lines.forEachIndexed { i, l ->
            if (l.amountMinor != null && l.percent != null) invalid("/lines/$i", ViolationCode.INVALID_VALUE, "Betrag und Prozent in einer Zeile")
            l.quantity?.let { q -> if (q.signum() <= 0 || q.scale() > 8 || q.precision() - q.scale() > 12) invalid("/lines/$i/quantity", ViolationCode.INVALID_VALUE) }
            l.unit?.let { u -> if (u !in UNITS) invalid("/lines/$i/unit", ViolationCode.NOT_IN_ENUM) }
            l.percent?.let { p -> if (p.signum() < 0 || p > BigDecimal(100) || p.scale() > 8) invalid("/lines/$i/percent", ViolationCode.INVALID_VALUE) }
        }
        return when {
            amounts > 0 && percents > 0 -> invalid("/lines", ViolationCode.INVALID_VALUE, "Betrags- und Prozentzeilen lassen sich nicht mischen")
            amounts > 0 -> if (amounts == lines.size) AllocationMode.AMOUNT else invalid("/lines", ViolationCode.REQUIRED, "jede Zeile braucht einen Betrag")
            percents > 0 -> if (percents == lines.size) AllocationMode.PERCENT else invalid("/lines", ViolationCode.REQUIRED, "jede Zeile braucht einen Prozentwert")
            else -> if (lines.all { it.quantity != null }) AllocationMode.WEIGHT else invalid("/lines", ViolationCode.REQUIRED, "Betrag, Prozent oder Schlüsselmenge je Zeile")
        }
    }

    /** Löst die Zeilen gegen die Basis auf; `kinds` = Art der Kostenobjekte (Ergebnis der Sperrabfrage). Summe ungleich Basis: 422 `ALLOCATION_SUM_MISMATCH`. */
    fun resolve(ev: EventRef, mode: AllocationMode, lines: List<AllocationLineInput>, kinds: Map<UUID, String>): List<Resolved> {
        if (ev.basisMinor == 0L) invalid("/lines", ViolationCode.INVALID_VALUE, "Kostenbasis 0 lässt sich nicht aufteilen")
        val amounts: List<Long> = when (mode) {
            AllocationMode.AMOUNT -> {
                lines.forEachIndexed { i, l ->
                    val p = "/lines/$i/amount"
                    if (l.amountCurrency != null && l.amountCurrency != ev.currency) invalid("$p/currency", ViolationCode.INVALID_VALUE, "Währung wie das Ereignis (${ev.currency})")
                    if (l.amountExponent != null && l.amountExponent != ev.exponent) invalid("$p/exponent", ViolationCode.INVALID_VALUE, "Exponent wie das Ereignis (${ev.exponent})")
                    val a = l.amountMinor!!
                    if (Math.abs(a) > MAX_SAFE) invalid("$p/amount_minor", ViolationCode.MONEY_OUT_OF_RANGE)
                    if ((ev.basisMinor > 0 && a < 0) || (ev.basisMinor < 0 && a > 0)) invalid("$p/amount_minor", ViolationCode.INVALID_VALUE, "Vorzeichen wie die Kostenbasis")
                }
                val sum = lines.fold(0L) { acc, l -> Math.addExact(acc, l.amountMinor!!) }
                if (sum != ev.basisMinor) throw sumMismatch("Summe der Teilbeträge $sum entspricht nicht der Kostenbasis ${ev.basisMinor}")
                lines.map { it.amountMinor!! }
            }
            AllocationMode.PERCENT -> {
                val total = lines.fold(BigDecimal.ZERO) { acc, l -> acc + l.percent!! }
                if (total.compareTo(BigDecimal(100)) != 0) throw sumMismatch("Summe der Prozentwerte ${total.stripTrailingZeros().toPlainString()} ist nicht 100")
                AllocationCalculator.distribute(ev.basisMinor, lines.map { it.costObjectId to it.percent!! })
            }
            AllocationMode.WEIGHT -> AllocationCalculator.distribute(ev.basisMinor, lines.map { it.costObjectId to it.quantity!! })
        }
        return lines.mapIndexed { i, l ->
            Resolved(l.costObjectId, kinds.getValue(l.costObjectId), amounts[i], AllocationCalculator.percentOf(amounts[i], ev.basisMinor), l.quantity, l.unit, l.percent, l.amountMinor)
        }
    }

    private fun sumMismatch(detail: String) = ProblemException(ErrorCode.ALLOCATION_SUM_MISMATCH, detail, listOf(Violation("/lines", ViolationCode.INVALID_VALUE, detail)))

    val UNITS = setOf("KM", "MIN", "KWH", "L", "ITEM")
}

/** Schreiben und Lesen von Umlagerevisionen; geteilt von der Aufteilung (API), dem Posting (Beleg-Split) und dem Gegenereignis (WP-M4-36). */
internal object AllocationRows {
    private val MAPPER = tools.jackson.databind.json.JsonMapper.builder().build()

    fun exponentOf(j: JdbcOperations, currency: String): Int = j.queryForObject("SELECT exponent FROM taktovia.currency WHERE code = ?", Int::class.java, currency) ?: 2

    fun activeRevision(j: JdbcOperations, eventId: UUID): Long =
        j.query("SELECT revision FROM taktovia.allocation_activation WHERE event_id = ?", { rs, _ -> rs.getLong(1) }, eventId).firstOrNull() ?: 0L

    /**
     * Legt die nächste Revision an (DRAFT mit Zeilen, dann POSTED) und macht sie aktiv; der Aufrufer hält die Sperren (Kostenobjekte, Periode) und hat die Revision geprüft. Audit und Outbox in derselben Transaktion.
     */
    fun persist(
        j: JdbcOperations, audit: AuditPort, outbox: Outbox, clock: Clock, ctx: RequestContext, ev: EventRef, mode: AllocationMode, policyRevision: String, reason: String, lines: List<Resolved>,
    ): AllocationView {
        val current = activeRevision(j, ev.id)
        val revision = (j.queryForObject("SELECT coalesce(max(revision), 0) FROM taktovia.allocation_set WHERE event_id = ?", Long::class.java, ev.id) ?: 0L) + 1
        val by = DocumentRows.actor(ctx).value
        val id = UUID.randomUUID()
        val snapshot = linkedMapOf<String, Any?>("mode" to mode.name, "policy_revision" to policyRevision, "reason" to reason, "created_by" to by, "lines" to lines.map {
            linkedMapOf("cost_object_id" to it.costObjectId.toString(), "percent" to it.inputPercent?.toPlainString(), "amount_minor" to it.inputAmount, "quantity" to it.quantity?.toPlainString(), "unit" to it.unit)
        })
        j.update("INSERT INTO taktovia.allocation_set (tenant_id, id, event_id, revision, legal_entity_id, currency, basis_minor, algorithm_version, driver_snapshot, state) " +
            "VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?, ?, ?::jsonb, 'DRAFT')", id, ev.id, revision, ev.legalEntityId, ev.currency, ev.basisMinor, AllocationCalculator.ALGORITHM_VERSION, ExpenseFieldCodec.toJson(snapshot))
        lines.sortedBy { it.costObjectId }.forEach {
            j.update("INSERT INTO taktovia.allocation_line (tenant_id, id, allocation_id, cost_object_id, amount_minor, weight) VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?)", UUID.randomUUID(), id, it.costObjectId, it.amountMinor, it.weightPercent)
        }
        check(j.update("UPDATE taktovia.allocation_set SET state = 'POSTED' WHERE id = ? AND state = 'DRAFT'", id) == 1)   // Trigger: Summe der Zeilen = Basis
        if (current == 0L) j.update("INSERT INTO taktovia.allocation_activation (tenant_id, event_id, allocation_id, revision, activated_by) VALUES (taktovia.current_tenant(), ?, ?, ?, ?)", ev.id, id, revision, by)
        else check(j.update("UPDATE taktovia.allocation_activation SET allocation_id = ?, revision = ?, activated_by = ?, activated_at = clock_timestamp() WHERE event_id = ? AND revision = ?", id, revision, by, ev.id, current) == 1)
        audit.record(j, AuditEntry(DocumentRows.actor(ctx), AuditActions.FINANCE_ALLOCATION_POSTED, ObjectRef("cost_event", ev.id), ctx.correlationId,
            mapOf("allocation_id" to id.toString(), "revision" to revision, "mode" to mode.name, "policy_revision" to policyRevision, "reason" to reason, "lines" to lines.size)))
        val now = clock.instant()
        outbox.append(j, OutboxEvent(ev.id, revision, "cost_allocation.posted", linkedMapOf("allocation_id" to id.toString(), "event_id" to ev.id.toString(), "revision" to revision,
            "legal_entity_id" to ev.legalEntityId.toString(), "currency" to ev.currency, "basis_minor" to ev.basisMinor, "line_count" to lines.size, "posted_at" to now.toString()), now, correlationId = ctx.correlationId))
        return view(j, id)
    }

    /** Die aktive Umlagerevision des Ereignisses (mit Zeilen) oder `null`. */
    fun activeOf(j: JdbcOperations, eventId: UUID): AllocationView? =
        j.query("SELECT allocation_id FROM taktovia.allocation_activation WHERE event_id = ?", { rs, _ -> rs.getObject(1, UUID::class.java) }, eventId).firstOrNull()?.let { view(j, it) }

    fun view(j: JdbcOperations, setId: UUID): AllocationView {
        val set = j.query("SELECT s.id, s.event_id, s.revision, s.state, s.legal_entity_id, s.currency, s.basis_minor, s.algorithm_version, s.driver_snapshot::text, " +
            "EXISTS (SELECT 1 FROM taktovia.allocation_activation a WHERE a.allocation_id = s.id) AS active, c.exponent FROM taktovia.allocation_set s JOIN taktovia.currency c ON c.code = s.currency WHERE s.id = ?",
            { rs, _ -> SetRow(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getLong(3), rs.getString(4), rs.getObject(5, UUID::class.java), rs.getString(6).trim(), rs.getLong(7), rs.getString(8), rs.getString(9), rs.getBoolean(10), rs.getInt(11)) }, setId).single()
        val snap = ExpenseFieldCodec.parseTree(set.snapshot)
        @Suppress("UNCHECKED_CAST") val driverLines = (snap["lines"] as List<Map<String, Any?>>).associateBy { it["cost_object_id"] as String }
        val lines = j.query("SELECT l.cost_object_id, o.kind, l.amount_minor, l.weight FROM taktovia.allocation_line l JOIN taktovia.cost_object o ON o.tenant_id = l.tenant_id AND o.id = l.cost_object_id WHERE l.allocation_id = ? ORDER BY l.cost_object_id",
            { rs, _ ->
                val id = rs.getObject(1, UUID::class.java); val d = driverLines[id.toString()]
                AllocationLineView(id, rs.getString(2), rs.getLong(3), rs.getBigDecimal(4), (d?.get("quantity") as String?)?.let(::BigDecimal), d?.get("unit") as String?)
            }, setId)
        return AllocationView(set.id, set.eventId, set.revision, set.state, set.entity, set.currency, set.exponent, set.basis, set.algorithm, AllocationMode.valueOf(snap["mode"] as String), snap["policy_revision"] as String, snap["reason"] as String, lines, set.active, snap["created_by"] as String?)
    }

    private class SetRow(val id: UUID, val eventId: UUID, val revision: Long, val state: String, val entity: UUID, val currency: String, val basis: Long, val algorithm: String, val snapshot: String, val active: Boolean, val exponent: Int)
}

/**
 * Aufteilung eines gebuchten Kostenereignisses (WP-M4-35). Sperrreihenfolge wie das Posting (WP-M4-33): Kostenobjekte `FOR SHARE`, dann Fiskalperiode `FOR UPDATE` (serialisiert parallele Aufteilungen und den
 * Periodenabschluss); zwei parallele Revisionen ergeben genau eine aktive Fassung (die zweite sieht den neuen Stand: 412, bzw. 409 `ALREADY_ALLOCATED`, wenn sie von „noch nicht aufgeteilt“ ausging).
 */
internal class JdbcCostAllocations(private val ctxTx: ContextTransactions, private val audit: AuditPort, private val outbox: Outbox, private val clock: Clock) : CostAllocations {

    override fun allocate(ctx: RequestContext, eventId: UUID, expectedRevision: Long, cmd: AllocateCost): AllocationView {
        if (!ctx.hasScope(SCOPE_ALLOCATE) || !isFinance(ctx)) throw ProblemException(ErrorCode.FORBIDDEN)
        val reason = cmd.reason.trim()
        if (reason.length < 3) throw ProblemException.invalid(Violation("/reason", if (reason.isEmpty()) ViolationCode.REQUIRED else ViolationCode.TOO_SHORT))
        if (reason.length > 1000) throw ProblemException.invalid(Violation("/reason", ViolationCode.TOO_LONG))
        val policy = cmd.policyRevision.trim()
        if (policy.isEmpty()) throw ProblemException.invalid(Violation("/allocation_policy_revision", ViolationCode.REQUIRED))
        if (policy.length > 256) throw ProblemException.invalid(Violation("/allocation_policy_revision", ViolationCode.TOO_LONG))
        val mode = AllocationPlans.modeOf(cmd.lines)
        return ctxTx.run(ctx) { j -> doAllocate(j, ctx, eventId, expectedRevision, cmd, mode, policy, reason) }
    }

    /** Audit und Outbox schreibt [AllocationRows.persist] in derselben Transaktion (gemeinsamer Schreibweg von API, Beleg-Split und Gegenereignis). */
    private fun doAllocate(j: JdbcOperations, ctx: RequestContext, eventId: UUID, expected: Long, cmd: AllocateCost, mode: AllocationMode, policy: String, reason: String): AllocationView {
        val ev = j.query("SELECT e.id, e.legal_entity_id, e.currency, e.cost_basis_minor, e.scenario, jn.id, jn.period_id, jn.state FROM taktovia.economic_event e LEFT JOIN taktovia.journal jn ON jn.tenant_id = e.tenant_id AND jn.event_id = e.id WHERE e.id = ?",
            { rs, _ -> Header(EventRef(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getString(3).trim(), rs.getLong(4), 0), rs.getString(5), rs.getObject(7, UUID::class.java), rs.getString(8)) }, eventId).firstOrNull() ?: throw ProblemException.notFound()
        if (ev.scenario != "ACTUAL" || ev.journalState != "POSTED" || ev.periodId == null) throw ProblemException(ErrorCode.CONFLICT, "Aufgeteilt wird nur ein gebuchtes Ist-Kostenereignis.")
        val ref = EventRef(ev.ref.id, ev.ref.legalEntityId, ev.ref.currency, ev.ref.basisMinor, AllocationRows.exponentOf(j, ev.ref.currency))
        // Kostenobjekte sperren (FOR SHARE, in Kennungsreihenfolge); fremde und unbekannte Kennungen sind 404, nicht erlaubte Art, fremder Rechtsträger und inaktive 422
        val ids = cmd.lines.map { it.costObjectId }.sorted()
        val objects = j.query("SELECT o.id, o.kind, o.legal_entity_id, o.active FROM taktovia.cost_object o WHERE o.id = ANY(CAST(? AS uuid[])) ORDER BY o.id FOR SHARE OF o",
            { rs, _ -> ObjectRow(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getObject(3, UUID::class.java), rs.getBoolean(4)) }, ids.joinToString(",", "{", "}"))
        if (objects.size != ids.size) throw ProblemException.notFound()
        cmd.lines.forEachIndexed { i, l ->
            val o = objects.first { it.id == l.costObjectId }
            val pointer = "/lines/$i/cost_object_id"
            if (o.kind !in ALLOCATION_TARGET_KINDS) throw ProblemException.invalid(Violation(pointer, ViolationCode.INVALID_VALUE, "Kostenobjektart ${o.kind} ist kein Aufteilungsziel"))
            if (l.costObjectType != null && l.costObjectType != o.kind) throw ProblemException.invalid(Violation("/lines/$i/cost_object_type", ViolationCode.INVALID_VALUE, "Art stimmt nicht mit dem Kostenobjekt überein"))
            if (o.entity != ref.legalEntityId) throw ProblemException.invalid(Violation(pointer, ViolationCode.INVALID_VALUE, "Kostenobjekt gehört zu einem anderen Rechtsträger als das Ereignis"))
            if (!o.active) throw ProblemException.invalid(Violation(pointer, ViolationCode.INVALID_VALUE, "Kostenobjekt ist inaktiv"))
        }
        val closed = j.query("SELECT closed_at IS NOT NULL FROM taktovia.fiscal_period WHERE id = ? FOR UPDATE", { rs, _ -> rs.getBoolean(1) }, ev.periodId).single()
        if (closed) throw ProblemException(ErrorCode.PERIOD_CLOSED, "Die Buchungsperiode des Ereignisses ist geschlossen; Korrekturen nur als Gegenereignis in einer offenen Periode.")
        val current = AllocationRows.activeRevision(j, eventId)
        if (expected == 0L && current > 0) throw ProblemException(ErrorCode.ALREADY_ALLOCATED, "Das Ereignis ist bereits aufgeteilt (aktive Revision $current); eine Korrektur braucht die aktive Revision im If-Match.")
        if (expected != current) throw ProblemException(ErrorCode.REVISION_MISMATCH, "Die Aufteilung wurde zwischenzeitlich geändert (aktive Revision $current).")
        val resolved = AllocationPlans.resolve(ref, mode, cmd.lines, objects.associate { it.id to it.kind })
        return AllocationRows.persist(j, audit, outbox, clock, ctx, ref, mode, policy, reason, resolved)
    }

    private class Header(val ref: EventRef, val scenario: String, val periodId: UUID?, val journalState: String?)
    private class ObjectRow(val id: UUID, val kind: String, val entity: UUID, val active: Boolean)

    override fun active(ctx: RequestContext, eventId: UUID): AllocationView? {
        require(ctx, SCOPE_READ_COSTS)
        return ctxTx.run(ctx, readOnly = true) { j ->
            requireEvent(j, eventId)
            j.query("SELECT allocation_id FROM taktovia.allocation_activation WHERE event_id = ?", { rs, _ -> rs.getObject(1, UUID::class.java) }, eventId).firstOrNull()?.let { AllocationRows.view(j, it) }
        }
    }

    override fun revisions(ctx: RequestContext, eventId: UUID): List<AllocationView> {
        require(ctx, SCOPE_READ_COSTS)
        return ctxTx.run(ctx, readOnly = true) { j ->
            requireEvent(j, eventId)
            j.query("SELECT id FROM taktovia.allocation_set WHERE event_id = ? ORDER BY revision", { rs, _ -> rs.getObject(1, UUID::class.java) }, eventId).map { AllocationRows.view(j, it) }
        }
    }

    private fun requireEvent(j: JdbcOperations, id: UUID) {
        if (j.queryForObject("SELECT count(*) FROM taktovia.economic_event WHERE id = ?", Int::class.java, id) != 1) throw ProblemException.notFound()
    }

    override fun effectiveCost(ctx: RequestContext, legalEntityId: UUID, currency: String): EffectiveCost {
        require(ctx, SCOPE_READ_COSTS)
        return ctxTx.run(ctx, readOnly = true) { j -> EffectiveCosts.of(j, legalEntityId, currency) }
    }
}

/**
 * Gesamtansicht der Ist-Kosten (WP-M4-35/-36): Summe der Kostenbasis aller gebuchten Ist-Ereignisse (Gegenereignisse negativ). Je Kostenobjekt zählt die **aktive Umlage**, ein nicht aufgeteiltes Ereignis
 * zählt bei seinem einzigen zugeordneten Kostenobjekt (sonst als „nicht zugeordnet“). Ereignis und seine Aufteilung werden nie zusammengezählt: die Summe je Kostenobjekt und der nicht zugeordnete Rest
 * ergeben stets die Gesamtsumme.
 */
internal object EffectiveCosts {
    fun of(j: JdbcOperations, legalEntityId: UUID, currency: String): EffectiveCost {
        val total = j.queryForObject("SELECT coalesce(sum(e.cost_basis_minor), 0) FROM taktovia.economic_event e JOIN taktovia.journal jn ON jn.tenant_id = e.tenant_id AND jn.event_id = e.id AND jn.state = 'POSTED' " +
            "WHERE e.scenario = 'ACTUAL' AND e.legal_entity_id = ? AND e.currency = ?", Long::class.java, legalEntityId, currency)!!
        val byObject = linkedMapOf<UUID, Long>()
        j.query("SELECT l.cost_object_id, sum(l.amount_minor) FROM taktovia.allocation_activation a JOIN taktovia.allocation_line l ON l.tenant_id = a.tenant_id AND l.allocation_id = a.allocation_id " +
            "JOIN taktovia.economic_event e ON e.tenant_id = a.tenant_id AND e.id = a.event_id JOIN taktovia.journal jn ON jn.tenant_id = e.tenant_id AND jn.event_id = e.id AND jn.state = 'POSTED' " +
            "WHERE e.scenario = 'ACTUAL' AND e.legal_entity_id = ? AND e.currency = ? GROUP BY l.cost_object_id ORDER BY l.cost_object_id", { rs, _ -> byObject.merge(rs.getObject(1, UUID::class.java), rs.getBigDecimal(2).longValueExact(), Long::plus) }, legalEntityId, currency)
        var unassigned = 0L
        j.query("SELECT e.cost_basis_minor, (SELECT array_agg(c.cost_object_id) FROM taktovia.expense_cost_assignment c WHERE c.tenant_id = e.tenant_id AND c.document_id = e.document_id) " +
            "FROM taktovia.economic_event e JOIN taktovia.journal jn ON jn.tenant_id = e.tenant_id AND jn.event_id = e.id AND jn.state = 'POSTED' " +
            "WHERE e.scenario = 'ACTUAL' AND e.legal_entity_id = ? AND e.currency = ? AND NOT EXISTS (SELECT 1 FROM taktovia.allocation_activation a WHERE a.tenant_id = e.tenant_id AND a.event_id = e.id)", { rs, _ ->
            val basis = rs.getLong(1); val arr = rs.getArray(2)?.array as Array<*>?
            if (arr != null && arr.size == 1) byObject.merge(arr[0] as UUID, basis, Long::plus) else unassigned += basis
        }, legalEntityId, currency)
        return EffectiveCost(currency, total, byObject, unassigned)
    }
}

/** Beträge eines Belegs für Posting und Aufteilungsprüfung: eine Quelle der Wahrheit für die Kostenbasis (brutto minus abziehbare Steuer). */
internal object ExpenseAmounts {
    class Amounts(val gross: Long, val tax: Long, val net: Long, val recoverable: Long, val currency: String, val date: java.time.LocalDate) { val costBasis: Long get() = gross - recoverable }

    fun of(fields: Map<String, Any?>, tax: TaxTreatment): Amounts {
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
        return Amounts(gross, vat, gross - vat, if (tax == TaxTreatment.FULLY_DEDUCTIBLE) vat else 0, fields[ExpenseFields.CURRENCY] as String, java.time.LocalDate.parse(fields[ExpenseFields.DOCUMENT_DATE] as String))
    }
}

/** Lesen des Beleg-Splits und die Freigabeprüfung (`SPLIT_INCOMPLETE`). */
internal object SplitRows {
    fun load(j: JdbcOperations, documentId: UUID): SplitView? {
        val head = j.query("SELECT mode, policy_revision, reason, set_by, set_at FROM taktovia.expense_split WHERE document_id = ?",
            { rs, _ -> arrayOf<Any>(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getTimestamp(5).toInstant()) }, documentId).firstOrNull() ?: return null
        val lines = j.query("SELECT cost_object_id, percent, amount_minor, quantity, unit FROM taktovia.expense_split_line WHERE document_id = ? ORDER BY cost_object_id",
            { rs, _ -> SplitLineView(rs.getObject(1, UUID::class.java), rs.getBigDecimal(2), rs.getObject(3) as Long?, rs.getBigDecimal(4), rs.getString(5)) }, documentId)
        return SplitView(AllocationMode.valueOf(head[0] as String), head[1] as String, head[2] as String, lines, head[3] as String, head[4] as java.time.Instant)
    }

    fun inputs(s: SplitView): List<AllocationLineInput> = s.lines.map { AllocationLineInput(it.costObjectId, null, it.amountMinor, null, null, it.percent, it.quantity, it.unit) }

    /** Löst den Plan gegen die Kostenbasis des Belegs auf; Fehler (Lücke, Differenz) als Problem mit dem Grund. */
    fun resolve(j: JdbcOperations, documentId: UUID, fields: Map<String, Any?>, objects: List<de.taktovia.documentsfinance.api.CostObjectView>, split: SplitView, currency: String? = null): List<Resolved> {
        val a = ExpenseAmounts.of(fields, TaxTreatment.FULLY_DEDUCTIBLE)
        val ref = EventRef(documentId, objects.first().legalEntityId, currency ?: a.currency, a.costBasis, AllocationRows.exponentOf(j, currency ?: a.currency))
        return AllocationPlans.resolve(ref, split.mode, inputs(split), objects.associate { it.id to it.kind.name })
    }

    /** Fehlt dem Beleg mit mehreren Kostenobjekten ein vollständiger Plan, kommt der Grund zurück (sonst `null`). */
    fun incompleteReason(j: JdbcOperations, documentId: UUID, fields: Map<String, Any?>, objects: List<de.taktovia.documentsfinance.api.CostObjectView>): String? {
        if (objects.size < 2) return null
        val split = load(j, documentId) ?: return "Der Beleg ist mehreren Kostenobjekten zugeordnet; die Aufteilung fehlt."
        if (split.lines.map { it.costObjectId }.toSet() != objects.map { it.id }.toSet()) return "Die Aufteilung deckt nicht alle zugeordneten Kostenobjekte ab."
        return try { resolve(j, documentId, fields, objects, split); null } catch (e: ProblemException) { e.detail ?: "Die Aufteilung ist ungültig." }
    }
}

/** Freigabeprüfung der Aufteilung (WP-M4-35, DOC-011): ein Beleg mit mehreren Kostenobjekten ist nur mit vollständigem, centgenauem Plan freigabefähig. */
internal object SplitGate {
    fun require(j: JdbcOperations, documentId: UUID, fields: Map<String, Any?>) {
        val reason = SplitRows.incompleteReason(j, documentId, fields, CostAssignmentRows.assigned(j, documentId)) ?: return
        throw ProblemException(ErrorCode.SPLIT_INCOMPLETE, reason, listOf(Violation("/split", ViolationCode.INVALID_VALUE, reason)))
    }
}

/** Beleg-Split setzen (WP-M4-35). Nur im Belegzustand `STORED`; die Zeilen müssen zu den zugeordneten Kostenobjekten gehören; jede Änderung ist eine neue Belegrevision (Eintrag `split`). */
internal class JdbcExpenseSplits(private val ctxTx: ContextTransactions, private val audit: AuditPort, private val clock: Clock) : ExpenseSplits {

    override fun set(ctx: RequestContext, documentId: UUID, expectedRevision: Long, cmd: SetSplit): ExpenseView {
        require(ctx, SCOPE_WRITE)
        val reason = cmd.reason.trim(); val policy = cmd.policyRevision.trim()
        if (reason.length < 3) throw ProblemException.invalid(Violation("/split/reason", if (reason.isEmpty()) ViolationCode.REQUIRED else ViolationCode.TOO_SHORT))
        if (reason.length > 1000) throw ProblemException.invalid(Violation("/split/reason", ViolationCode.TOO_LONG))
        if (policy.isEmpty() || policy.length > 256) throw ProblemException.invalid(Violation("/split/allocation_policy_revision", if (policy.isEmpty()) ViolationCode.REQUIRED else ViolationCode.TOO_LONG))
        val mode = AllocationPlans.modeOf(cmd.lines)
        cmd.lines.forEachIndexed { i, l -> if (l.amountMinor != null && (l.amountMinor < 0 || l.amountMinor > MAX_SAFE)) throw ProblemException.invalid(Violation("/split/lines/$i/amount", ViolationCode.INVALID_VALUE)) }
        return ctxTx.run(ctx) { j -> doSet(j, ctx, documentId, expectedRevision, cmd, mode, policy, reason) }
    }

    @AuditRequired
    private fun doSet(j: JdbcOperations, ctx: RequestContext, documentId: UUID, expected: Long, cmd: SetSplit, mode: AllocationMode, policy: String, reason: String): ExpenseView {
        val row = DocumentRows.visible(j, ctx, documentId, lock = true)
        if (row.view.revision != expected) throw ProblemException(ErrorCode.REVISION_MISMATCH, "Der Beleg wurde zwischenzeitlich geändert; aktuelle Revision lesen.")
        when {
            row.stateRaw == "QUARANTINED" || row.stateRaw == "QUARANTINE_REJECTED" -> throw ProblemException(ErrorCode.CONFLICT, "Ein Beleg in Quarantäne erlaubt keine Aufteilung.")
            row.stateRaw != "STORED" -> throw ProblemException(ErrorCode.IMMUTABLE_RECORD, "Der Beleg ist eingereicht, freigegeben oder gebucht; die Aufteilung ist unveränderlich.")
            row.view.integrity == IntegrityState.VIOLATED -> throw IntegrityChecksSupport.violation()
        }
        val assigned = CostAssignmentRows.assigned(j, documentId).map { it.id }.toSet()
        cmd.lines.forEachIndexed { i, l -> if (l.costObjectId !in assigned) throw ProblemException.invalid(Violation("/split/lines/$i/cost_object_id", ViolationCode.INVALID_VALUE, "Kostenobjekt ist dem Beleg nicht zugeordnet")) }
        val before = SplitRows.load(j, documentId)
        j.update("DELETE FROM taktovia.expense_split WHERE document_id = ?", documentId)
        val by = ctx.actor.subject.take(200)
        j.update("INSERT INTO taktovia.expense_split (tenant_id, document_id, mode, policy_revision, reason, set_by) VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?)", documentId, mode.name, policy, reason, by)
        cmd.lines.sortedBy { it.costObjectId }.forEach {
            j.update("INSERT INTO taktovia.expense_split_line (tenant_id, document_id, cost_object_id, percent, amount_minor, quantity, unit) VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?)",
                documentId, it.costObjectId, if (mode == AllocationMode.PERCENT) it.percent else null, if (mode == AllocationMode.AMOUNT) it.amountMinor else null, if (mode == AllocationMode.WEIGHT) it.quantity else null, if (mode == AllocationMode.WEIGHT) it.unit else null)
        }
        val revision = expected + 1
        check(j.update("UPDATE taktovia.expense_document SET revision = ? WHERE id = ? AND revision = ? AND state = 'STORED'", revision, documentId, expected) == 1)
        val change = linkedMapOf<String, Any?>("old" to before?.let { b -> b.lines.map { it.costObjectId.toString() } }, "new" to cmd.lines.map { it.costObjectId.toString() }, "mode" to mode.name)
        j.update("INSERT INTO taktovia.document_revision (tenant_id, id, document_id, revision, field_values, edited_by, reason) VALUES (taktovia.current_tenant(),?,?,?,?::jsonb,?,?)",
            UUID.randomUUID(), documentId, revision, ExpenseFieldCodec.toJson(mapOf("split" to change)), by, "SPLIT")
        audit.record(j, AuditEntry(DocumentRows.actor(ctx), AuditActions.FINANCE_EXPENSE_SPLIT_SET, ObjectRef("expense", documentId), ctx.correlationId,
            mapOf("revision" to revision, "mode" to mode.name, "policy_revision" to policy, "lines" to cmd.lines.size)))
        PlausibilityHook.afterRevision(j, audit, ctx, documentId, revision)
        return ExpenseViews.build(j, DocumentRows.visible(j, ctx, documentId), ctx)
    }

    override fun get(ctx: RequestContext, documentId: UUID): SplitView? {
        require(ctx, DocumentRows.SCOPE_READ)
        return ctxTx.run(ctx, readOnly = true) { j -> DocumentRows.visible(j, ctx, documentId); SplitRows.load(j, documentId) }
    }

    @Suppress("unused") private val unusedClock = clock
}

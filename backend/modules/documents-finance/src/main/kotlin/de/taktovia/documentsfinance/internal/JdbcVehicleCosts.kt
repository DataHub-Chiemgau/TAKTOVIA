package de.taktovia.documentsfinance.internal

import de.taktovia.documentsfinance.api.VehicleCostCategoryTotal
import de.taktovia.documentsfinance.api.VehicleCostItem
import de.taktovia.documentsfinance.api.VehicleCostMonthTotal
import de.taktovia.documentsfinance.api.VehicleCostQuery
import de.taktovia.documentsfinance.api.VehicleCosts
import de.taktovia.documentsfinance.api.VehicleTotalCost
import de.taktovia.documentsfinance.internal.DocumentRows.require
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import org.springframework.jdbc.core.RowMapper
import java.sql.Date
import java.time.Clock
import java.time.Instant
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Fahrzeugkosten je Monat und Kostenart (WP-M4-40, FCO-002; P1-Ausschnitt von `/v1/vehicles/{id}/total-cost`). Grundlage sind die wirksamen Kostenzeilen ([EffectiveLines]) der Kostenobjekte `VEHICLE` des Fahrzeugs
 * (über alle Rechtsträger): Original und Umlage zählen nie doppelt, Gegenereignisse mindern die Kosten. Anschaffung/Leasing erscheint mit der erfassten Position, ohne Abschreibung und ohne Verteilung (Kap. 03 §1.1,
 * OP 20). Alles in einer Anweisung (konsistente Momentaufnahme).
 */
internal class JdbcVehicleCosts(private val ctxTx: ContextTransactions, private val clock: Clock) : VehicleCosts {

    override fun totalCost(ctx: RequestContext, vehicleId: UUID, query: VehicleCostQuery): VehicleTotalCost {
        require(ctx, SCOPE_READ)
        val violations = buildList {
            addAll(ValuationRules.checkValuationAndCurrency(query.valuation, query.currency))
            if (query.monthFrom == null) add(Violation("/month_from", ViolationCode.REQUIRED))
            if (query.monthTo == null) add(Violation("/month_to", ViolationCode.REQUIRED))
            if (query.monthFrom != null && query.monthTo != null) {
                if (query.monthTo.isBefore(query.monthFrom)) add(Violation("/month_to", ViolationCode.INVALID_VALUE, "der letzte Monat liegt nicht vor dem ersten"))
                else if (ChronoUnit.MONTHS.between(query.monthFrom, query.monthTo) + 1 > MAX_MONTHS) add(Violation("/month_to", ViolationCode.ABOVE_MAXIMUM, "höchstens $MAX_MONTHS Monate"))
            }
        }
        if (violations.isNotEmpty()) throw ProblemException.invalid(*violations.toTypedArray())
        val from = query.monthFrom!!; val to = query.monthTo!!; val currency = query.currency!!
        return ctxTx.run(ctx, readOnly = true) { j ->
            if (j.queryForObject("SELECT count(*) FROM taktovia.vehicle WHERE id = ?", Int::class.java, vehicleId) != 1) throw ProblemException.notFound()
            val monthExpr = "date_trunc('month', COALESCE(timezone('UTC', l.service_from)::date, l.accounting_date))::date"
            val sql = EffectiveLines.cte(" AND e.currency = ?") +
                "SELECT $monthExpr AS month, l.category, cc.cost_group, cc.label, sum(l.amount) AS amount, count(DISTINCT l.id) AS events, " +
                "count(DISTINCT l.id) FILTER (WHERE l.service_from IS NOT NULL) AS by_service, count(DISTINCT l.id) FILTER (WHERE l.service_from IS NULL) AS by_booking, now() AS as_of " +
                "FROM lines l JOIN taktovia.cost_object co ON co.tenant_id = taktovia.current_tenant() AND co.id = l.obj AND co.kind = 'VEHICLE' AND co.vehicle_id = ? " +
                "LEFT JOIN taktovia.cost_category cc ON cc.tenant_id = taktovia.current_tenant() AND cc.category = l.category " +
                "WHERE $monthExpr >= ? AND $monthExpr < ? GROUP BY 1, l.category, cc.cost_group, cc.label HAVING sum(l.amount) <> 0 OR count(DISTINCT l.id) > 0 ORDER BY 1, l.category"
            var asOf: Instant? = null
            val items = j.query(sql, RowMapper { rs, _ ->   // sql-lint: ok nur konstante Fragmente (Monatsausdruck), alle Werte als Parameter gebunden
                asOf = rs.getTimestamp("as_of").toInstant()
                VehicleCostItem(YearMonth.from(rs.getDate("month").toLocalDate()), rs.getString("category"), rs.getString("cost_group"), rs.getString("label"), rs.getBigDecimal("amount").longValueExact(), rs.getLong("events"),
                    rs.getLong("by_service"), rs.getLong("by_booking"))
            }, currency, vehicleId, Date.valueOf(from.atDay(1)), Date.valueOf(to.plusMonths(1).atDay(1)))
            VehicleTotalCost(vehicleId, "ACTUAL", currency, from, to, ASSIGNMENT_RULE, asOf ?: clock.instant(), items,
                items.groupBy { it.month }.map { (m, xs) -> VehicleCostMonthTotal(m, xs.sumOf { it.amountMinor }) },
                items.groupBy { it.category }.map { (c, xs) -> VehicleCostCategoryTotal(c, xs.first().costGroup, xs.sumOf { it.amountMinor }) }.sortedBy { it.category }, items.sumOf { it.amountMinor })
        }
    }

    internal companion object {
        const val SCOPE_READ = "costs:read"
        const val MAX_MONTHS = 60L
        /** Annahme (Detailvertrag FCO-002): Leistungsbeginn (`service_from`, UTC), wenn vorhanden, sonst Buchungsdatum (`accounting_date`); keine anteilige Verteilung auf Monate. */
        const val ASSIGNMENT_RULE = "service_from (Leistungsbeginn, UTC), sonst accounting_date (Buchungsdatum); keine anteilige Verteilung auf Monate"
    }
}

package de.taktovia.documentsfinance.internal

import de.taktovia.documentsfinance.api.FinancePolicies
import de.taktovia.documentsfinance.api.FinancePolicyView
import de.taktovia.documentsfinance.api.FuelCardMapping
import de.taktovia.documentsfinance.api.FuelParameters
import de.taktovia.documentsfinance.api.FuelType
import de.taktovia.documentsfinance.api.FuelVehiclePolicy
import de.taktovia.documentsfinance.api.UpdateFinancePolicy
import de.taktovia.documentsfinance.internal.DocumentRows.SCOPE_READ
import de.taktovia.documentsfinance.internal.DocumentRows.SCOPE_WRITE
import de.taktovia.documentsfinance.internal.DocumentRows.require
import de.taktovia.platform.audit.AuditActions
import de.taktovia.platform.audit.AuditEntry
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.audit.ObjectRef
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** Fachparameter des Moduls je Mandant (`finance_policy`, WP-M4-30, -31) und Schwellen je Fahrzeug (`fuel_vehicle_policy`). Ohne Zeile gelten die Vorgaben von [FinancePolicyView]. */
internal class JdbcFinancePolicies(private val ctxTx: ContextTransactions, private val audit: AuditPort) : FinancePolicies {

    override fun get(ctx: RequestContext): FinancePolicyView {
        require(ctx, SCOPE_READ)
        return ctxTx.run(ctx, readOnly = true) { j -> load(j) }
    }

    override fun update(ctx: RequestContext, cmd: UpdateFinancePolicy): FinancePolicyView {
        require(ctx, SCOPE_WRITE)
        if (!DocumentRows.isFinance(ctx)) throw ProblemException(ErrorCode.FORBIDDEN)
        cmd.fuel?.let(::validate)
        return ctxTx.run(ctx) { j ->
            val current = load(j)
            val required = cmd.fuelReceiptRequired ?: current.fuelReceiptRequired
            val fp = cmd.fuel ?: current.fuel
            cmd.fuel?.cardMapping?.map { it.vehicleId }?.distinct()?.forEach { v -> if (j.queryForObject("SELECT count(*) FROM taktovia.vehicle WHERE id = ?", Int::class.java, v) != 1) throw ProblemException.notFound() }
            val v = j.query(
                "INSERT INTO taktovia.finance_policy AS p (tenant_id, fuel_receipt_required, fuel_max_liters, fuel_price_alert_pct, fuel_use_alert_pct, fuel_price_reference, fuel_card_mapping, updated_by) " +
                    "VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?) ON CONFLICT (tenant_id) DO UPDATE SET fuel_receipt_required = EXCLUDED.fuel_receipt_required, " +
                    "fuel_max_liters = EXCLUDED.fuel_max_liters, fuel_price_alert_pct = EXCLUDED.fuel_price_alert_pct, fuel_use_alert_pct = EXCLUDED.fuel_use_alert_pct, " +
                    "fuel_price_reference = EXCLUDED.fuel_price_reference, fuel_card_mapping = EXCLUDED.fuel_card_mapping, updated_by = EXCLUDED.updated_by, updated_at = clock_timestamp(), revision = p.revision + 1 " +
                    "RETURNING $COLUMNS", MAPPER, required, fp.maxLiters, fp.priceAlertPct, fp.useAlertPct, ExpenseFieldCodec.toJson(fp.priceReference.entries.associate { it.key.name to it.value.stripTrailingZeros().toPlainString() }),
                ExpenseFieldCodec.toJson(fp.cardMapping.map { linkedMapOf("card_id" to it.cardId, "vehicle_id" to it.vehicleId.toString(), "valid_from" to it.validFrom.toString(), "valid_to" to it.validTo?.toString()) }),
                ctx.actor.subject.take(200)).single()
            audit.record(j, AuditEntry(DocumentRows.actor(ctx), AuditActions.FINANCE_POLICY_CHANGED, ObjectRef("finance_policy", ctx.tenant.value.toString()), ctx.correlationId, mapOf("revision" to v.revision)))
            v
        }
    }

    override fun putVehicle(ctx: RequestContext, vehicleId: UUID, maxLiters: BigDecimal?, useAlertPct: BigDecimal?): FuelVehiclePolicy {
        require(ctx, SCOPE_WRITE)
        if (!DocumentRows.isFinance(ctx)) throw ProblemException(ErrorCode.FORBIDDEN)
        maxLiters?.let { if (it.signum() <= 0 || it > MAX_LITERS) throw ProblemException.invalid(Violation("/max_liters", ViolationCode.INVALID_VALUE)) }
        useAlertPct?.let { if (it.signum() < 0 || it > MAX_PCT) throw ProblemException.invalid(Violation("/use_alert_pct", ViolationCode.INVALID_VALUE)) }
        return ctxTx.run(ctx) { j ->
            if (j.queryForObject("SELECT count(*) FROM taktovia.vehicle WHERE id = ?", Int::class.java, vehicleId) != 1) throw ProblemException.notFound()
            val v = j.query("INSERT INTO taktovia.fuel_vehicle_policy AS p (tenant_id, vehicle_id, fuel_max_liters, fuel_use_alert_pct, updated_by) VALUES (taktovia.current_tenant(), ?, ?, ?, ?) " +
                "ON CONFLICT (tenant_id, vehicle_id) DO UPDATE SET fuel_max_liters = EXCLUDED.fuel_max_liters, fuel_use_alert_pct = EXCLUDED.fuel_use_alert_pct, updated_by = EXCLUDED.updated_by, updated_at = clock_timestamp(), revision = p.revision + 1 " +
                "RETURNING vehicle_id, fuel_max_liters, fuel_use_alert_pct, revision", { rs, _ -> FuelVehiclePolicy(rs.getObject(1, UUID::class.java), rs.getBigDecimal(2)?.let(FuelRows::plain), rs.getBigDecimal(3)?.let(FuelRows::plain), rs.getLong(4)) },
                vehicleId, maxLiters, useAlertPct, ctx.actor.subject.take(200)).single()
            audit.record(j, AuditEntry(DocumentRows.actor(ctx), AuditActions.FINANCE_POLICY_CHANGED, ObjectRef("finance_policy", ctx.tenant.value.toString()), ctx.correlationId, mapOf("revision" to v.revision, "scope" to "vehicle")))
            v
        }
    }

    private fun validate(f: FuelParameters) {
        f.maxLiters?.let { if (it.signum() <= 0 || it > MAX_LITERS) throw ProblemException.invalid(Violation("/fuel/max_liters", ViolationCode.INVALID_VALUE)) }
        f.priceAlertPct?.let { if (it.signum() < 0 || it > MAX_PCT) throw ProblemException.invalid(Violation("/fuel/price_alert_pct", ViolationCode.INVALID_VALUE)) }
        f.useAlertPct?.let { if (it.signum() < 0 || it > MAX_PCT) throw ProblemException.invalid(Violation("/fuel/use_alert_pct", ViolationCode.INVALID_VALUE)) }
        f.priceReference.values.forEach { if (it.signum() <= 0 || it.stripTrailingZeros().scale() > 8) throw ProblemException.invalid(Violation("/fuel/price_reference", ViolationCode.INVALID_VALUE)) }
        if (f.cardMapping.size > 500) throw ProblemException.invalid(Violation("/fuel/card_mapping", ViolationCode.TOO_MANY_ITEMS))
        f.cardMapping.forEach { m ->
            if (!CARD.matches(m.cardId)) throw ProblemException.invalid(Violation("/fuel/card_mapping", ViolationCode.PATTERN_MISMATCH))
            if (m.validTo != null && m.validTo.isBefore(m.validFrom)) throw ProblemException.invalid(Violation("/fuel/card_mapping", ViolationCode.INTERVAL_INVALID))
        }
        // eindeutig pro Gültigkeitsfenster: je Karte überlappen sich die Fenster nie
        f.cardMapping.groupBy { it.cardId }.values.forEach { list ->
            val sorted = list.sortedBy { it.validFrom }
            sorted.zipWithNext().forEach { (a, b) -> if (a.validTo == null || !a.validTo.isBefore(b.validFrom)) throw ProblemException.invalid(Violation("/fuel/card_mapping", ViolationCode.NOT_UNIQUE, "Gültigkeitsfenster einer Karte überlappen")) }
        }
    }

    companion object {
        private val MAX_LITERS = BigDecimal(100000); private val MAX_PCT = BigDecimal(1000)
        private val CARD = Regex("[A-Za-z0-9._-]{1,64}")
        private const val COLUMNS = "fuel_receipt_required, revision, fuel_max_liters, fuel_price_alert_pct, fuel_use_alert_pct, fuel_price_reference::text AS price_ref, fuel_card_mapping::text AS card_map"

        private val MAPPER = RowMapper { rs, _ ->
            val refs = ExpenseFieldCodec.parseTree(rs.getString("price_ref")).entries.associate { (k, v) -> FuelType.valueOf(k) to BigDecimal(v.toString()) }
            @Suppress("UNCHECKED_CAST") val cards = (ExpenseFieldCodec.parseTreeList(rs.getString("card_map")) as List<Map<String, Any?>>).map {
                FuelCardMapping(it["card_id"] as String, UUID.fromString(it["vehicle_id"] as String), LocalDate.parse(it["valid_from"] as String), (it["valid_to"] as String?)?.let(LocalDate::parse))
            }
            FinancePolicyView(rs.getBoolean("fuel_receipt_required"), rs.getLong("revision"),
                FuelParameters(rs.getBigDecimal("fuel_max_liters")?.let(FuelRows::plain), rs.getBigDecimal("fuel_price_alert_pct")?.let(FuelRows::plain), rs.getBigDecimal("fuel_use_alert_pct")?.let(FuelRows::plain), refs, cards))
        }

        /** Parameter des Mandanten der laufenden Transaktion (RLS); Vorgaben ohne Zeile. */
        fun load(j: JdbcOperations): FinancePolicyView = j.query("SELECT $COLUMNS FROM taktovia.finance_policy", MAPPER).firstOrNull() ?: FinancePolicyView()
    }
}

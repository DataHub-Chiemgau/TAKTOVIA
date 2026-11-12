package de.taktovia.documentsfinance.internal

import de.taktovia.documentsfinance.api.DocumentKind
import de.taktovia.documentsfinance.api.ExpenseView
import de.taktovia.documentsfinance.api.FuelCardMapping
import de.taktovia.documentsfinance.api.FuelPlausibility
import de.taktovia.documentsfinance.api.FuelUnit
import de.taktovia.documentsfinance.api.FuelView
import de.taktovia.documentsfinance.api.HintAcknowledgement
import de.taktovia.documentsfinance.api.HintView
import de.taktovia.documentsfinance.api.PlausibilityNote
import de.taktovia.documentsfinance.api.PlausibilityReport
import de.taktovia.documentsfinance.api.PlausibilityRule
import de.taktovia.documentsfinance.api.PlausibilityRule.FUEL_CARD_MISMATCH
import de.taktovia.documentsfinance.api.PlausibilityRule.FUEL_ODOMETER_GAP
import de.taktovia.documentsfinance.api.PlausibilityRule.FUEL_PRICE
import de.taktovia.documentsfinance.api.PlausibilityRule.FUEL_VOLUME
import de.taktovia.documentsfinance.internal.DocumentRows.SCOPE_READ
import de.taktovia.documentsfinance.internal.DocumentRows.SCOPE_WRITE
import de.taktovia.documentsfinance.internal.DocumentRows.require
import de.taktovia.platform.audit.AuditActions
import de.taktovia.platform.audit.AuditEntry
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.audit.ObjectRef
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import org.springframework.jdbc.core.JdbcOperations
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.sql.Timestamp
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * Regelformeln der Tankplausibilität (WP-M4-31). Reine Funktionen über Eingabewerte: alle Vergleiche in [BigDecimal] ohne Gleitkomma, ein Wert exakt auf der Schwelle ist kein Hinweis.
 *
 *  - `FUEL_VOLUME`: Menge (Liter) > `fuel_max_liters` (Fahrzeug, sonst Mandant).
 *  - `FUEL_PRICE`: |Preis − Vergleichspreis| · 100 > `fuel_price_alert_pct` · Vergleichspreis. Vergleichsbasis ist der konfigurierte Vergleichspreis je Kraftstoffart (`fuel_price_reference`);
 *    historische Preisvergleichsdaten gibt es nicht (Offener Punkt 16), die Basis steht im Hinweis.
 *  - `FUEL_ODOMETER_GAP`: Kilometerstand kleiner als der des letzten Tankvorgangs desselben Fahrzeugs: stets ein Hinweis (rückläufig); Stand unverändert: Hinweis (kein Fortschritt); sonst Verbrauch
 *    = Menge / Abstand · 100 gegen den Mittelwert der Verbräuche der bis zu fünf vorherigen Tankabstände des Fahrzeugs: |Verbrauch − Mittel| · 100 > `fuel_use_alert_pct` · Mittel. Ohne Vorgänger (oder ohne
 *    zwei Vorgänger für den Mittelwert) kein Hinweis, sondern der Vermerk „keine Vergleichsbasis“.
 *  - `FUEL_CARD_MISMATCH`: Karten-ID ohne gültige Zuordnung zum Fahrzeug zur Tankzeit (`fuel_card_mapping`); ohne Karten-ID entfällt die Regel ohne Hinweis.
 * Ist ein Parameter nicht konfiguriert, ist die Regel nicht prüfbar und wird als Vermerk „Parameter fehlt“ ausgewiesen, nie als bestanden.
 */
internal object FuelPlausibilityRules {
    class Previous(val odometerKm: Long, val quantity: BigDecimal)

    class Input(
        val fuel: FuelView, val effectiveAt: Instant, val previous: List<Previous>, val maxLiters: BigDecimal?, val maxLitersSource: String, val priceAlertPct: BigDecimal?, val priceReference: BigDecimal?,
        val useAlertPct: BigDecimal?, val cardMapping: List<FuelCardMapping>,
    )

    class Finding(val rule: PlausibilityRule, val threshold: String, val actual: String, val basis: Map<String, Any?>)
    class Evaluation(val findings: List<Finding>, val notes: List<PlausibilityNote>)

    private val MC = MathContext(30, RoundingMode.HALF_EVEN)
    private fun s(v: BigDecimal): String = FuelRows.plain(v).toPlainString()
    private fun round4(v: BigDecimal): BigDecimal = v.setScale(4, RoundingMode.HALF_UP)

    fun evaluate(i: Input): Evaluation {
        val findings = mutableListOf<Finding>(); val notes = mutableListOf<PlausibilityNote>()
        val f = i.fuel
        // Tankvolumen (nur Liter; kWh werden getrennt geführt)
        if (f.quantity != null && f.unit == FuelUnit.LITER) {
            val max = i.maxLiters
            if (max == null) notes += PlausibilityNote(FUEL_VOLUME, "PARAMETER_MISSING", "nicht prüfbar, Parameter fehlt: fuel_max_liters")
            else if (f.quantity > max) findings += Finding(FUEL_VOLUME, s(max), s(f.quantity), linkedMapOf("comparison_value" to s(max), "threshold" to s(max), "unit" to "LITER", "source" to i.maxLitersSource))
        }
        // Literpreis gegen die konfigurierte Vergleichsbasis
        if (f.unitPrice != null && f.fuelType != null) {
            val ref = i.priceReference; val pct = i.priceAlertPct
            if (ref == null || pct == null) notes += PlausibilityNote(FUEL_PRICE, "PARAMETER_MISSING", "nicht prüfbar, Parameter fehlt: " + listOfNotNull(if (pct == null) "fuel_price_alert_pct" else null, if (ref == null) "fuel_price_reference[${f.fuelType}]" else null).joinToString(", "))
            else {
                val diff = (f.unitPrice - ref).abs().multiply(BigDecimal(100))
                if (diff > pct.multiply(ref)) findings += Finding(FUEL_PRICE, s(pct), s(f.unitPrice), linkedMapOf("comparison_value" to s(ref), "threshold" to s(pct), "deviation_pct" to round4(diff.divide(ref, MC)).toPlainString(),
                    "source" to "fuel_price_reference[${f.fuelType}] (konfigurierte Vergleichsbasis, Offener Punkt 16)"))
            }
        }
        // Kilometerabstand
        if (f.vehicleId != null && f.odometerKm != null) {
            val prev = i.previous.firstOrNull()
            if (prev == null) notes += PlausibilityNote(FUEL_ODOMETER_GAP, "NO_COMPARISON_BASIS", "keine Vergleichsbasis: kein Vorgänger-Kilometerstand des Fahrzeugs")
            else if (f.odometerKm < prev.odometerKm) findings += Finding(FUEL_ODOMETER_GAP, ">= ${prev.odometerKm}", f.odometerKm.toString(), linkedMapOf("comparison_value" to prev.odometerKm, "reason" to "ODOMETER_DECREASING", "source" to SOURCE_PREVIOUS))
            else if (f.odometerKm == prev.odometerKm) findings += Finding(FUEL_ODOMETER_GAP, "> ${prev.odometerKm}", f.odometerKm.toString(), linkedMapOf("comparison_value" to prev.odometerKm, "reason" to "ODOMETER_NO_PROGRESS", "source" to SOURCE_PREVIOUS))
            else if (f.quantity != null) {
                val gap = f.odometerKm - prev.odometerKm
                val use = f.quantity.multiply(BigDecimal(100)).divide(BigDecimal(gap), MC)
                val history = i.previous.zipWithNext().mapNotNull { (a, b) -> (a.odometerKm - b.odometerKm).takeIf { it > 0 }?.let { a.quantity.multiply(BigDecimal(100)).divide(BigDecimal(it), MC) } }.take(5)
                val pct = i.useAlertPct
                if (pct == null) notes += PlausibilityNote(FUEL_ODOMETER_GAP, "PARAMETER_MISSING", "nicht prüfbar, Parameter fehlt: fuel_use_alert_pct")
                else if (history.isEmpty()) notes += PlausibilityNote(FUEL_ODOMETER_GAP, "NO_COMPARISON_BASIS", "keine Vergleichsbasis: für den Verbrauch fehlen vorherige Tankabstände (mindestens zwei Vorgänger)")
                else {
                    val avg = history.fold(BigDecimal.ZERO) { a, b -> a + b }.divide(BigDecimal(history.size), MC)
                    if ((use - avg).abs().multiply(BigDecimal(100)) > pct.multiply(avg)) findings += Finding(FUEL_ODOMETER_GAP, s(pct), round4(use).toPlainString(), linkedMapOf("comparison_value" to round4(avg).toPlainString(),
                        "threshold" to s(pct), "deviation_pct" to round4((use - avg).abs().multiply(BigDecimal(100)).divide(avg, MC)).toPlainString(), "odometer_gap_km" to gap, "previous_odometer_km" to prev.odometerKm,
                        "sample_size" to history.size, "unit" to "${f.unit}/100 km", "source" to "Mittel der bis zu fünf vorherigen Tankabstände des Fahrzeugs"))
                }
            }
        }
        // Tankkarten-ID
        if (f.cardId != null) {
            if (i.cardMapping.isEmpty()) notes += PlausibilityNote(FUEL_CARD_MISMATCH, "PARAMETER_MISSING", "nicht prüfbar, Parameter fehlt: fuel_card_mapping")
            else if (f.vehicleId == null) notes += PlausibilityNote(FUEL_CARD_MISMATCH, "NO_COMPARISON_BASIS", "keine Vergleichsbasis: Fahrzeug fehlt")
            else {
                val day = i.effectiveAt.atOffset(ZoneOffset.UTC).toLocalDate()
                val valid = i.cardMapping.filter { it.cardId == f.cardId && !day.isBefore(it.validFrom) && (it.validTo == null || !day.isAfter(it.validTo)) }
                if (valid.none { it.vehicleId == f.vehicleId }) findings += Finding(FUEL_CARD_MISMATCH, "Zuordnung zum Fahrzeug ${f.vehicleId}", f.cardId, linkedMapOf("comparison_value" to (valid.map { it.vehicleId.toString() }.ifEmpty { listOf("keine gültige Zuordnung") }),
                    "threshold" to "Karte gehört zum Fahrzeug", "date" to day.toString(), "source" to "fuel_card_mapping"))
            }
        }
        return Evaluation(findings, notes)
    }

    private const val SOURCE_PREVIOUS = "letzter Tankvorgang desselben Fahrzeugs"
}

/** Persistenz und Lesen der Hinweise (`expense_hint`, `expense_hint_ack`) und der Prüflauf; geteilt von Beleglesen, Prüflauf, Quittierung und Freigabeschritt. */
internal object PlausibilityRows {
    /** Effektive Tankzeit: Tankzeit, sonst Belegdatum (00:00 UTC), sonst Eingangszeit. */
    private const val EFFECTIVE = "coalesce(ft.fueled_at, (d.current_fields ->> 'document_date')::date::timestamp AT TIME ZONE 'UTC', d.original_received_at)"

    fun effectiveAt(j: JdbcOperations, documentId: UUID): Instant =
        j.queryForObject("SELECT coalesce(ft.fueled_at, (d.current_fields ->> 'document_date')::date::timestamp AT TIME ZONE 'UTC', d.original_received_at) FROM taktovia.expense_document d " +
            "LEFT JOIN taktovia.fuel_transaction ft ON ft.tenant_id = d.tenant_id AND ft.document_id = d.id WHERE d.id = ?", Timestamp::class.java, documentId)!!.toInstant()

    private fun evaluation(j: JdbcOperations, documentId: UUID, fuel: FuelView): FuelPlausibilityRules.Evaluation {
        val policy = JdbcFinancePolicies.load(j)
        val vehicle = fuel.vehicleId?.let { v -> j.query("SELECT fuel_max_liters, fuel_use_alert_pct FROM taktovia.fuel_vehicle_policy WHERE vehicle_id = ?", { rs, _ -> rs.getBigDecimal(1) to rs.getBigDecimal(2) }, v).firstOrNull() }
        val at = effectiveAt(j, documentId)
        val previous = if (fuel.vehicleId == null || fuel.unit == null) emptyList() else j.query(
            "SELECT ft.odometer_km, ft.quantity FROM taktovia.fuel_transaction ft JOIN taktovia.expense_document d ON d.tenant_id = ft.tenant_id AND d.id = ft.document_id " +
                "WHERE ft.vehicle_id = ? AND ft.document_id <> ? AND ft.unit = ? AND ft.odometer_km IS NOT NULL AND ft.quantity IS NOT NULL AND d.state NOT IN ('QUARANTINED', 'QUARANTINE_REJECTED') " +
                "AND $EFFECTIVE < ? ORDER BY $EFFECTIVE DESC, ft.id DESC LIMIT 7",
            { rs, _ -> FuelPlausibilityRules.Previous(rs.getLong(1), rs.getBigDecimal(2)) }, fuel.vehicleId, documentId, fuel.unit!!.name, Timestamp.from(at))
        val fp = policy.fuel
        return FuelPlausibilityRules.evaluate(FuelPlausibilityRules.Input(fuel, at, previous, vehicle?.first ?: fp.maxLiters, if (vehicle?.first != null) "fuel_max_liters (Fahrzeug)" else "fuel_max_liters (Mandant)",
            fp.priceAlertPct, fuel.fuelType?.let { fp.priceReference[it] }, vehicle?.second ?: fp.useAlertPct, fp.cardMapping))
    }

    /** Hinweise der Revision mit Quittierung. */
    fun hints(j: JdbcOperations, documentId: UUID, revision: Long): List<HintView> =
        j.query("SELECT h.id, h.rule, h.document_revision, h.threshold, h.actual_value, h.basis::text AS basis, h.created_at, a.acked_by, a.reason, a.acked_at FROM taktovia.expense_hint h " +
            "LEFT JOIN taktovia.expense_hint_ack a ON a.tenant_id = h.tenant_id AND a.hint_id = h.id WHERE h.document_id = ? AND h.document_revision = ? ORDER BY h.rule", { rs, _ ->
            HintView(rs.getObject("id", UUID::class.java), PlausibilityRule.valueOf(rs.getString("rule")), rs.getLong("document_revision"), rs.getString("threshold"), rs.getString("actual_value"),
                ExpenseFieldCodec.parseTree(rs.getString("basis")), rs.getTimestamp("created_at").toInstant(),
                rs.getString("acked_by")?.let { HintAcknowledgement(it, rs.getString("reason"), rs.getTimestamp("acked_at").toInstant()) })
        }, documentId, revision)

    /** Lesesicht für den Beleg: gespeicherte Hinweise der aktuellen Revision, Vermerke live berechnet (schreibt nie). */
    fun report(j: JdbcOperations, kind: DocumentKind, documentId: UUID, revision: Long, fuel: FuelView?): PlausibilityReport {
        if (kind != DocumentKind.FUEL_RECEIPT) return PlausibilityReport.NONE
        val notes = if (fuel == null) emptyList() else evaluation(j, documentId, fuel).notes
        return PlausibilityReport(hints(j, documentId, revision), notes)
    }

    /**
     * Prüflauf in der laufenden Transaktion (Beleg gesperrt): schreibt je Befund höchstens einen Hinweis je Regel und Revision (`ON CONFLICT DO NOTHING`); Entstehung steht im Audit. Ändert nie erfasste Werte.
     */
    fun check(j: JdbcOperations, audit: AuditPort, ctx: RequestContext, kind: DocumentKind, documentId: UUID, revision: Long): PlausibilityReport {
        if (kind != DocumentKind.FUEL_RECEIPT) return PlausibilityReport.NONE
        val fuel = FuelRows.load(j, documentId) ?: return PlausibilityReport.NONE
        val ev = evaluation(j, documentId, fuel)
        for (f in ev.findings) {
            val inserted = j.update("INSERT INTO taktovia.expense_hint (tenant_id, id, document_id, document_revision, rule, threshold, actual_value, basis) VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?, ?::jsonb) " +
                "ON CONFLICT (tenant_id, document_id, document_revision, rule) DO NOTHING", UUID.randomUUID(), documentId, revision, f.rule.name, f.threshold, f.actual, ExpenseFieldCodec.toJson(f.basis))
            if (inserted == 1) audit.record(j, AuditEntry(DocumentRows.actor(ctx), AuditActions.FINANCE_HINT_RAISED, ObjectRef("expense", documentId), ctx.correlationId, mapOf("rule" to f.rule.name, "revision" to revision)))
        }
        return PlausibilityReport(hints(j, documentId, revision), ev.notes)
    }

    /** Freigabesperre: offene Hinweise der aktuellen Revision (nach einem Prüflauf unter Belegsperre). 422 `HINT_NOT_ACKNOWLEDGED`, je offener Regel ein Eintrag. */
    fun requireAcknowledged(j: JdbcOperations, audit: AuditPort, ctx: RequestContext, kind: DocumentKind, documentId: UUID, revision: Long) {
        val open = check(j, audit, ctx, kind, documentId, revision).openHints
        if (open.isNotEmpty()) throw ProblemException(ErrorCode.HINT_NOT_ACKNOWLEDGED, "Plausibilitätshinweise sind nicht quittiert; ohne Quittierung mit Begründung wird nicht freigegeben.",
            open.map { Violation("/hints/${it.rule}", ViolationCode.INVALID_VALUE, "Hinweis ${it.rule} ist offen") })
    }
}

internal class JdbcFuelPlausibility(private val ctxTx: ContextTransactions, private val audit: AuditPort) : FuelPlausibility {

    override fun check(ctx: RequestContext, documentId: UUID): PlausibilityReport {
        require(ctx, SCOPE_WRITE)
        return ctxTx.run(ctx) { j ->
            val row = DocumentRows.visible(j, ctx, documentId, lock = true)   // Belegsperre: Prüfläufe, Quittierung und Freigabe sind serialisiert
            if (row.stateRaw != "STORED" && row.stateRaw !in CHECKABLE_STATES) PlausibilityReport(PlausibilityRows.hints(j, documentId, row.view.revision), emptyList())
            else PlausibilityRows.check(j, audit, ctx, row.view.kind, documentId, row.view.revision)
        }
    }

    override fun report(ctx: RequestContext, documentId: UUID): PlausibilityReport {
        require(ctx, SCOPE_READ)
        return ctxTx.run(ctx, readOnly = true) { j ->
            val row = DocumentRows.visible(j, ctx, documentId)
            PlausibilityRows.report(j, row.view.kind, documentId, row.view.revision, FuelRows.load(j, documentId))
        }
    }

    override fun acknowledge(ctx: RequestContext, documentId: UUID, hintId: UUID, expectedRevision: Long, reason: String): ExpenseView {
        if (!ctx.hasScope(SCOPE_REVIEW) || ctx.actor.type != ActorType.HUMAN || !DocumentRows.isFinance(ctx)) throw ProblemException(ErrorCode.FORBIDDEN)
        val text = reason.trim()
        if (text.length < 3) throw ProblemException.invalid(Violation("/reason", if (text.isEmpty()) ViolationCode.REQUIRED else ViolationCode.TOO_SHORT))
        if (text.length > 1000) throw ProblemException.invalid(Violation("/reason", ViolationCode.TOO_LONG))
        return ctxTx.run(ctx) { j ->
            val row = DocumentRows.visible(j, ctx, documentId, lock = true)
            if (row.view.revision != expectedRevision) throw ProblemException(ErrorCode.REVISION_MISMATCH, "Der Beleg wurde zwischenzeitlich geändert; aktuelle Revision lesen.")
            if (row.stateRaw !in CHECKABLE_STATES && row.stateRaw != "STORED") throw ProblemException(ErrorCode.IMMUTABLE_RECORD, "Der Beleg ist freigegeben oder gebucht; Hinweise lassen sich nicht mehr quittieren.")
            val hint = j.query("SELECT document_revision FROM taktovia.expense_hint WHERE id = ? AND document_id = ?", { rs, _ -> rs.getLong(1) }, hintId, documentId).firstOrNull() ?: throw ProblemException.notFound()
            if (hint != row.view.revision) throw ProblemException(ErrorCode.CONFLICT, "Der Hinweis gehört zu einer früheren Revision des Belegs; er wird in der aktuellen Revision neu geprüft.")
            val inserted = j.update("INSERT INTO taktovia.expense_hint_ack (tenant_id, hint_id, acked_by, reason) VALUES (taktovia.current_tenant(), ?, ?, ?) ON CONFLICT (tenant_id, hint_id) DO NOTHING",
                hintId, ctx.actor.subject.take(200), text)
            if (inserted == 1) audit.record(j, AuditEntry(DocumentRows.actor(ctx), AuditActions.FINANCE_HINT_ACKNOWLEDGED, ObjectRef("expense", documentId), ctx.correlationId, mapOf("revision" to hint)))
            ExpenseViews.build(j, DocumentRows.visible(j, ctx, documentId))
        }
    }

    companion object {
        const val SCOPE_REVIEW = "expenses:review"
        /** Zustände nach der Erfassung, in denen ein Beleg noch nicht gebucht ist (Freigabe-Workflow, WP-M4-34). */
        val CHECKABLE_STATES = setOf("IN_REVIEW", "REVIEWED", "WAITING_SECOND_APPROVAL")
    }
}

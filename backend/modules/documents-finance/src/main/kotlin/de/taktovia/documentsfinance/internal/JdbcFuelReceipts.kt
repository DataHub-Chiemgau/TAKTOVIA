package de.taktovia.documentsfinance.internal

import de.taktovia.documentsfinance.api.CaptureFuel
import de.taktovia.documentsfinance.api.DocumentKind
import de.taktovia.documentsfinance.api.ExpenseView
import de.taktovia.documentsfinance.api.FuelFields
import de.taktovia.documentsfinance.api.FuelReceipts
import de.taktovia.documentsfinance.api.FuelType
import de.taktovia.documentsfinance.api.FuelUnit
import de.taktovia.documentsfinance.api.FuelView
import de.taktovia.documentsfinance.api.IntegrityState
import de.taktovia.documentsfinance.internal.DocumentRows.SCOPE_READ
import de.taktovia.documentsfinance.internal.DocumentRows.SCOPE_WRITE
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
import org.springframework.jdbc.core.RowMapper
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Lesen der Tankwerte eines Belegs, geteilt von Beleglesen, Erfassung und Plausibilität. */
internal object FuelRows {
    private const val COLUMNS = "f.vehicle_id, f.fuel_type, f.quantity, f.unit, f.unit_price, f.gross_minor, f.currency, f.odometer_km, f.fueled_at, f.card_id"

    private fun mapper() = RowMapper { rs, _ ->
        val type = rs.getString("fuel_type")?.let { FuelType.valueOf(it) }
        val vehicle = rs.getObject("vehicle_id", UUID::class.java); val qty: BigDecimal? = rs.getBigDecimal("quantity"); val price: BigDecimal? = rs.getBigDecimal("unit_price")
        val odo = rs.getObject("odometer_km") as Number?
        val missing = buildList {
            if (vehicle == null) add(FuelFields.VEHICLE_ID); if (type == null) add(FuelFields.FUEL_TYPE); if (qty == null) add(FuelFields.QUANTITY)
            if (price == null) add(FuelFields.UNIT_PRICE); if (odo == null) add(FuelFields.ODOMETER_KM)
        }
        FuelView(vehicle, type, qty?.let(::plain), rs.getString("unit")?.let { FuelUnit.valueOf(it) }, price?.let(::plain), (rs.getObject("gross_minor") as Number?)?.toLong(), rs.getString("currency")?.trim(),
            odo?.toLong(), rs.getTimestamp("fueled_at")?.toInstant(), rs.getString("card_id"), missing)
    }

    /** Normalform ohne überflüssige Nullen (`40.50000000` -> `40.5`), nie in wissenschaftlicher Schreibweise. */
    fun plain(v: BigDecimal): BigDecimal = v.stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }

    /** Tankwerte eines Belegs; `null`, solange nichts erfasst ist. */
    fun load(j: JdbcOperations, documentId: UUID): FuelView? =
        j.query("SELECT $COLUMNS FROM taktovia.fuel_transaction f WHERE f.document_id = ?", mapper(), documentId).firstOrNull()

    /** Ansicht für einen Beleg: nur Belege der Art FUEL_RECEIPT haben Tankwerte; ohne erfasste Werte sind alle Pflichtwerte einzeln fehlend. */
    fun forDocument(j: JdbcOperations, kind: DocumentKind, documentId: UUID): FuelView? {
        if (kind != DocumentKind.FUEL_RECEIPT) return null
        return load(j, documentId) ?: FuelView(null, null, null, null, null, null, null, null, null, null, FuelFields.REQUIRED)
    }

    fun blockers(kind: DocumentKind, fuel: FuelView?, required: Boolean): List<de.taktovia.documentsfinance.api.ReleaseBlocker> =
        if (kind == DocumentKind.FUEL_RECEIPT && required && fuel != null && !fuel.complete) listOf(de.taktovia.documentsfinance.api.ReleaseBlocker.FUEL_FIELDS_MISSING) else emptyList()

    /** Pflichtwerte fehlen einzeln: 422 mit je einem Eintrag je Feld (Zeiger `/fuel/<feld>`). */
    fun missingProblem(fuel: FuelView) = ProblemException(ErrorCode.FUEL_FIELDS_MISSING, "Pflichtwerte des Tankbelegs fehlen.",
        fuel.missing.map { Violation("/fuel/$it", ViolationCode.REQUIRED, "Pflichtwert des Tankbelegs fehlt") })
}

/** Einheitliche Zusammenstellung der Belegansicht (Dokument, Felder, Prüfstand, Kostenzuordnung, Tankwerte); ein Ort für alle Bausteine, die einen [ExpenseView] liefern. */
internal object ExpenseViews {
    fun build(j: JdbcOperations, r: DocRow, ctx: RequestContext): ExpenseView {
        val fields = ExpenseFieldCodec.parseStored(r.fieldsJson); val review = ReviewRules.state(j, r.view.documentId, fields); val objects = CostAssignmentRows.assigned(j, r.view.documentId)
        val fuel = FuelRows.forDocument(j, r.view.kind, r.view.documentId)
        val fuelBlockers = FuelRows.blockers(r.view.kind, fuel, JdbcFinancePolicies.load(j).fuelReceiptRequired)
        val plausibility = PlausibilityRows.report(j, r.view.kind, r.view.documentId, r.view.revision, fuel)
        val hintBlockers = if (plausibility.openHints.isNotEmpty()) listOf(de.taktovia.documentsfinance.api.ReleaseBlocker.HINT_UNACKNOWLEDGED) else emptyList()
        val duplicates = DuplicateRows.candidates(j, r.view.documentId, DocumentRows.isFinance(ctx))
        val duplicateBlockers = buildList {
            if (duplicates.any { it.role == de.taktovia.documentsfinance.api.DuplicateRole.NEWER && it.state == de.taktovia.documentsfinance.api.DuplicateState.GLEICHE_EVIDENZ }) add(de.taktovia.documentsfinance.api.ReleaseBlocker.DUPLICATE_EVIDENCE)
            if (duplicates.any { it.role == de.taktovia.documentsfinance.api.DuplicateRole.NEWER && it.state == de.taktovia.documentsfinance.api.DuplicateState.OFFEN }) add(de.taktovia.documentsfinance.api.ReleaseBlocker.DUPLICATE_REVIEW_OPEN)
        }
        return ExpenseView(r.view, fields, r.view.blockers + ReviewRules.blockers(review) + CostAssignmentRows.blockers(objects) + fuelBlockers + hintBlockers + duplicateBlockers, review, objects, fuel, plausibility, duplicates)
    }
}

/**
 * Tankbeleg-Fachdaten (WP-M4-30). Die Erfassung sperrt die Belegzeile (`FOR UPDATE`, serialisiert sie mit Korrektur, Zuordnung und Freigabe), prüft `If-Match`, Zustand `STORED` und Integrität und schreibt
 * in einer Transaktion Tankvorgang, neue Belegrevision (Eintrag `fuel`), Audit und Ereignis. Mandantenbindung des Fahrzeugs sichern RLS und der zusammengesetzte Fremdschlüssel der Datenbank.
 */
internal class JdbcFuelReceipts(private val ctxTx: ContextTransactions, private val audit: AuditPort, private val outbox: Outbox, private val clock: Clock) : FuelReceipts {

    override fun capture(ctx: RequestContext, documentId: UUID, expectedRevision: Long, cmd: CaptureFuel): ExpenseView {
        require(ctx, SCOPE_WRITE)
        validate(cmd)
        return ctxTx.run(ctx) { j -> doCapture(j, ctx, documentId, expectedRevision, cmd) }
    }

    private fun validate(c: CaptureFuel) {
        if (c.vehicleId == null && c.fuelType == null && c.quantity == null && c.unitPrice == null && c.odometerKm == null && c.fueledAt == null && c.cardId == null)
            throw ProblemException.invalid(Violation("/fuel", ViolationCode.REQUIRED))
        decimal(c.quantity, "/fuel/quantity"); decimal(c.unitPrice, "/fuel/unit_price")
        c.odometerKm?.let { if (it < 0) throw ProblemException.invalid(Violation("/fuel/odometer_km", ViolationCode.BELOW_MINIMUM)); if (it > MAX_ODOMETER) throw ProblemException.invalid(Violation("/fuel/odometer_km", ViolationCode.ABOVE_MAXIMUM)) }
        c.cardId?.let { if (!CARD.matches(it)) throw ProblemException.invalid(Violation("/fuel/card_id", ViolationCode.PATTERN_MISMATCH)) }
        c.fueledAt?.let { if (it.isAfter(clock.instant().plus(Duration.ofDays(1)))) throw ProblemException.invalid(Violation("/fuel/fueled_at", ViolationCode.INVALID_VALUE, "Tankzeit liegt in der Zukunft")) }
    }

    /** Positiv, höchstens 8 Nachkommastellen und 12 Vorkommastellen (numeric(20,8)); nie Gleitkomma. */
    private fun decimal(v: BigDecimal?, pointer: String) {
        if (v == null) return
        if (v.signum() <= 0) throw ProblemException.invalid(Violation(pointer, ViolationCode.BELOW_MINIMUM, "Wert muss größer 0 sein"))
        val s = v.stripTrailingZeros()
        if (s.scale() > 8) throw ProblemException.invalid(Violation(pointer, ViolationCode.INVALID_VALUE, "Höchstens 8 Nachkommastellen"))
        if (s.precision() - s.scale() > 12) throw ProblemException.invalid(Violation(pointer, ViolationCode.ABOVE_MAXIMUM))
    }

    @AuditRequired
    private fun doCapture(j: JdbcOperations, ctx: RequestContext, id: UUID, expected: Long, cmd: CaptureFuel): ExpenseView {
        val row = DocumentRows.visible(j, ctx, id, lock = true)
        if (row.view.revision != expected) throw ProblemException(ErrorCode.REVISION_MISMATCH, "Der Beleg wurde zwischenzeitlich geändert; aktuelle Revision lesen.")
        when {
            row.stateRaw == "QUARANTINED" || row.stateRaw == "QUARANTINE_REJECTED" -> throw ProblemException(ErrorCode.CONFLICT, "Ein Beleg in Quarantäne erlaubt keine Tankwerte.")
            row.stateRaw != "STORED" -> throw ProblemException(ErrorCode.IMMUTABLE_RECORD, "Der Beleg ist freigegeben oder gebucht; die Tankwerte sind unveränderlich.")
            row.view.integrity == IntegrityState.VIOLATED -> throw IntegrityChecksSupport.violation()
        }
        if (row.view.kind != DocumentKind.FUEL_RECEIPT) throw ProblemException.invalid(Violation("/fuel", ViolationCode.INVALID_VALUE, "Tankwerte gibt es nur für Belege der Art FUEL_RECEIPT"))
        // Fahrzeug im Mandanten (RLS); fremde und unbekannte Kennungen verhalten sich gleich (404)
        cmd.vehicleId?.let { v -> if (j.queryForObject("SELECT count(*) FROM taktovia.vehicle WHERE id = ?", Int::class.java, v) != 1) throw ProblemException.notFound() }
        val before = FuelRows.load(j, id)
        val next = merge(before, cmd)
        if (before != null && same(before, next)) return ExpenseViews.build(j, DocumentRows.visible(j, ctx, id), ctx)   // keine Änderung: keine neue Revision
        val revision = expected + 1
        check(j.update("UPDATE taktovia.expense_document SET revision = ? WHERE id = ? AND revision = ? AND state = 'STORED'", revision, id, expected) == 1)
        val by = ctx.actor.subject.take(200)
        j.update("INSERT INTO taktovia.fuel_transaction AS f (tenant_id, id, document_id, vehicle_id, fuel_type, quantity, unit_price, odometer_km, fueled_at, card_id, document_revision, recorded_by) " +
            "VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (tenant_id, document_id) DO UPDATE SET vehicle_id = EXCLUDED.vehicle_id, fuel_type = EXCLUDED.fuel_type, " +
            "quantity = EXCLUDED.quantity, unit_price = EXCLUDED.unit_price, odometer_km = EXCLUDED.odometer_km, fueled_at = EXCLUDED.fueled_at, card_id = EXCLUDED.card_id, " +
            "document_revision = EXCLUDED.document_revision, recorded_by = EXCLUDED.recorded_by, recorded_at = clock_timestamp()",
            UUID.randomUUID(), id, next.vehicleId, next.fuelType?.name, next.quantity, next.unitPrice, next.odometerKm, next.fueledAt?.let { Timestamp.from(it) }, next.cardId, revision, by)
        val change = linkedMapOf<String, Any?>("old" to before?.let(::json), "new" to json(next))
        j.update("INSERT INTO taktovia.document_revision (tenant_id, id, document_id, revision, field_values, edited_by, reason) VALUES (taktovia.current_tenant(),?,?,?,?::jsonb,?,?)",
            UUID.randomUUID(), id, revision, ExpenseFieldCodec.toJson(mapOf("fuel" to change)), by, "FUEL_CAPTURE")
        val recorded = FuelRows.load(j, id)!!
        audit.record(j, AuditEntry(DocumentRows.actor(ctx), AuditActions.FINANCE_FUEL_RECORDED, ObjectRef("expense", id), ctx.correlationId, mapOf("revision" to revision, "complete" to recorded.complete)))
        outbox.append(j, OutboxEvent(id, revision, "fuel.transaction_recorded", linkedMapOf("document_id" to id.toString(), "revision" to revision, "vehicle_id" to recorded.vehicleId?.toString(),
            "fuel_type" to recorded.fuelType?.name, "unit" to recorded.unit?.name, "quantity" to recorded.quantity?.toPlainString(), "unit_price" to recorded.unitPrice?.toPlainString(), "odometer_km" to recorded.odometerKm,
            "fueled_at" to recorded.fueledAt?.toString(), "complete" to recorded.complete, "recorded_at" to clock.instant().toString()), clock.instant(), correlationId = ctx.correlationId))
        PlausibilityHook.afterRevision(j, audit, ctx, id, revision)
        DuplicateHook.afterChange(j, audit, outbox, clock, ctx, id)
        return ExpenseViews.build(j, DocumentRows.visible(j, ctx, id), ctx)
    }

    private fun merge(b: FuelView?, c: CaptureFuel) = FuelView(
        c.vehicleId ?: b?.vehicleId, c.fuelType ?: b?.fuelType, (c.quantity ?: b?.quantity)?.let(FuelRows::plain), (c.fuelType ?: b?.fuelType)?.unit, (c.unitPrice ?: b?.unitPrice)?.let(FuelRows::plain),
        b?.grossMinor, b?.currency, c.odometerKm ?: b?.odometerKm, c.fueledAt ?: b?.fueledAt, c.cardId ?: b?.cardId, emptyList())

    private fun same(a: FuelView, b: FuelView) = a.vehicleId == b.vehicleId && a.fuelType == b.fuelType && a.quantity == b.quantity && a.unitPrice == b.unitPrice &&
        a.odometerKm == b.odometerKm && a.fueledAt == b.fueledAt && a.cardId == b.cardId

    private fun json(f: FuelView): Map<String, Any?> = linkedMapOf("vehicle_id" to f.vehicleId?.toString(), "fuel_type" to f.fuelType?.name, "quantity" to f.quantity?.toPlainString(), "unit" to f.unit?.name,
        "unit_price" to f.unitPrice?.toPlainString(), "odometer_km" to f.odometerKm, "fueled_at" to f.fueledAt?.toString(), "card_id" to f.cardId)

    override fun get(ctx: RequestContext, documentId: UUID): FuelView? {
        require(ctx, SCOPE_READ)
        return ctxTx.run(ctx, readOnly = true) { j -> val r = DocumentRows.visible(j, ctx, documentId); FuelRows.forDocument(j, r.view.kind, documentId) }
    }

    private companion object {
        const val MAX_ODOMETER = 10_000_000L
        val CARD = Regex("[A-Za-z0-9._-]{1,64}")
    }
}

/** Prüflauf der Tankplausibilität (WP-M4-31) nach jeder neuen Revision eines Tankbelegs, in derselben Transaktion (Beleg gesperrt); Belege anderer Art bleiben unberührt. */
internal object PlausibilityHook {
    fun afterRevision(j: JdbcOperations, audit: AuditPort, ctx: RequestContext, documentId: UUID, revision: Long) {
        val kind = j.query("SELECT document_kind FROM taktovia.expense_document WHERE id = ?", { rs, _ -> DocumentKind.valueOf(rs.getString(1)) }, documentId).firstOrNull() ?: return
        PlausibilityRows.check(j, audit, ctx, kind, documentId, revision)
    }
}

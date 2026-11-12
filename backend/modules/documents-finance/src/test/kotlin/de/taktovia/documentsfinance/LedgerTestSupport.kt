package de.taktovia.documentsfinance

import de.taktovia.documentsfinance.api.ApprovalDecision
import de.taktovia.documentsfinance.api.AssignCosts
import de.taktovia.documentsfinance.api.CorrectExpense
import de.taktovia.documentsfinance.api.CreateFiscalPeriod
import de.taktovia.documentsfinance.api.DocumentKind
import de.taktovia.documentsfinance.api.ExpenseFields
import de.taktovia.documentsfinance.api.FiscalPeriodView
import de.taktovia.documentsfinance.api.LedgerBatch
import de.taktovia.documentsfinance.api.LedgerExportFormat
import de.taktovia.documentsfinance.api.RenderedLedgerBatch
import de.taktovia.documentsfinance.api.PostExpense
import de.taktovia.documentsfinance.api.PostingResult
import de.taktovia.documentsfinance.api.PutAccountingMapping
import de.taktovia.platform.context.Actor
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.tenant.TenantId
import java.time.LocalDate
import java.util.UUID

/**
 * Testhilfe des Clusters wave11-c5 (WP-M4-36, -37): ein Mandant mit Rechtsträger, Finanzrollen, Fiskalperioden über den echten Befehl, Kontierung, Kostenstellen und gebuchten Kostenereignissen
 * (Belegerfassung über die Bausteine, Freigabe per Zustandsupdate wie in CostEventPostingTest, Posting über den echten Befehl).
 */
class LedgerWorld(val bf: BelegFixture) {
    val w = bf.fx.world()
    val fin = person(setOf("finance"), setOf("receipts:read", "receipts:write", "expenses:review", "expenses:approve", "costs:read", "costs:allocate", "costs:reverse", "periods:read", "periods:manage", "periods:close", "ledger:read", "ledger:export"))
    val capturer = bf.driverCtx(w)
    val vehicle = UUID.randomUUID()

    fun person(roles: Set<String>, scopes: Set<String>, type: ActorType = ActorType.HUMAN, tenant: TenantId = w.tenant) =
        RequestContext(Actor(type, "https://idp.test/realms/m4", UUID.randomUUID().toString(), "sid", true), tenant, roles, scopes, emptySet(), UUID.randomUUID())

    init {
        bf.fx.exec("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (?,?,'org',CAST('{}' AS jsonb))", w.tenant.value, vehicle)
        bf.svc.accounting.put(fin, PutAccountingMapping(w.legalEntity, "FUEL", "6600", "1576", "1600"))
        bf.svc.accounting.put(fin, PutAccountingMapping(w.legalEntity, "RECEIPT", "6800", "1576", "1600"))
    }

    fun period(starts: LocalDate, ends: LocalDate): FiscalPeriodView = bf.svc.periods.create(fin, CreateFiscalPeriod(w.legalEntity, starts, ends))

    /** Perioden September, Oktober und November 2026 (alle offen). */
    fun autumn(): Triple<FiscalPeriodView, FiscalPeriodView, FiscalPeriodView> =
        Triple(period(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1)), period(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1)), period(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 12, 1)))

    fun centers(n: Int): List<UUID> = (1..n).map { UUID.randomUUID() }.sortedBy { it.toString() }.onEach {
        bf.fx.exec("INSERT INTO taktovia.cost_object(tenant_id,id,legal_entity_id,kind,business_ref) VALUES (?,?,?,'COST_CENTER',?)", w.tenant.value, it, w.legalEntity, it.toString())
    }

    /** Belegerfassung bis zur Freigabe (Zustand APPROVED) mit einem Fahrzeug-Kostenobjekt; liefert die Beleg-ID. */
    fun approvedDocument(gross: Long = 10000, vat: Long = 0, date: LocalDate = LocalDate.of(2026, 9, 15)): UUID {
        val d = bf.upload(w, fin, bytes = bf.uniqueJpeg(), kind = DocumentKind.RECEIPT)
        val fields = mutableMapOf<String, Any?>(ExpenseFields.AMOUNT_MINOR to gross, ExpenseFields.CURRENCY to "EUR", ExpenseFields.DOCUMENT_DATE to date.toString(), ExpenseFields.MERCHANT to "Händler ${UUID.randomUUID()}")
        if (vat > 0) fields[ExpenseFields.VAT_AMOUNTS] = listOf(mapOf("vat_rate_pct" to "19", "amount_minor" to vat))
        var rev = bf.svc.expenses.correct(fin, d.documentId, d.revision, CorrectExpense("erfasst", fields)).document.revision
        rev = bf.svc.costs.assign(fin, d.documentId, rev, AssignCosts(vehicleIds = setOf(vehicle))).document.revision
        bf.fx.exec("UPDATE taktovia.expense_document SET state='APPROVED', approved_by='user:freigeber' WHERE id=?", d.documentId)
        return d.documentId
    }

    fun post(documentId: UUID, ctx: RequestContext = fin): PostingResult = bf.svc.costEvents.postExpense(ctx, documentId, PostExpense("RECEIPT"))

    fun postedEvent(gross: Long = 10000, vat: Long = 0, date: LocalDate = LocalDate.of(2026, 9, 15)): PostingResult = post(approvedDocument(gross, vat, date))

    /** Beleg über den echten Freigabeweg (Erfassung, Sachprüfung, Budgetfreigabe; über der Schwelle wartet er auf die zweite Person); liefert die Beleg-ID und das Ergebnis der letzten Freigabe. */
    fun approvedByWorkflow(gross: Long = 10000, date: LocalDate = LocalDate.of(2026, 9, 15)): Pair<UUID, de.taktovia.documentsfinance.api.ApprovalOutcome> {
        val d = bf.upload(w, capturer, bytes = bf.uniqueJpeg(), kind = DocumentKind.RECEIPT)
        val fields = mapOf(ExpenseFields.AMOUNT_MINOR to gross, ExpenseFields.CURRENCY to "EUR", ExpenseFields.DOCUMENT_DATE to date.toString(), ExpenseFields.MERCHANT to "Händler ${UUID.randomUUID()}")
        var rev = bf.svc.expenses.correct(fin, d.documentId, d.revision, CorrectExpense("erfasst", fields)).document.revision
        rev = bf.svc.costs.assign(fin, d.documentId, rev, AssignCosts(vehicleIds = setOf(vehicle))).document.revision
        bf.svc.approvals.submit(capturer, d.documentId, rev)
        bf.svc.approvals.review(reviewer, d.documentId, rev, ApprovalDecision.APPROVED, "Sachlich geprüft")
        return d.documentId to bf.svc.approvals.approve(approver, d.documentId, rev, "Freigegeben")
    }

    val reviewer = person(setOf("finance"), setOf("receipts:read", "receipts:write", "expenses:review"))
    val approver = person(setOf("finance"), setOf("receipts:read", "receipts:write", "expenses:approve", "costs:read"))
    val secondApprover = person(setOf("finance"), setOf("receipts:read", "receipts:write", "expenses:approve", "costs:read"))

    fun events() = bf.fx.count("SELECT count(*) FROM taktovia.economic_event WHERE tenant_id=?", w.tenant.value)
    fun journals() = bf.fx.count("SELECT count(*) FROM taktovia.journal WHERE tenant_id=?", w.tenant.value)
    fun audit(action: String) = bf.fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action=?", w.tenant.value, action)
}


/** Neutrales Prüfformat des Moduls (eigene Testklasse, nicht der Adapter): eine Zeile je Journalzeile, deterministisch sortiert. */
class LedgerTestFormat : LedgerExportFormat {
    override val name = "test-lines"
    override val version = "1"
    override fun render(batch: LedgerBatch): RenderedLedgerBatch {
        val text = buildString {
            append("batch=").append(batch.batchRef).append(';').append("entity=").append(batch.legalEntityId).append('\n')
            batch.journals.sortedBy { it.journalId.toString() }.forEach { j -> j.lines.sortedWith(compareBy({ it.account }, { it.side }, { it.amountMinor })).forEach { l -> append(j.journalId).append('|').append(j.accountingDate).append('|').append(l.account).append('|').append(l.side).append('|').append(l.amountMinor).append('\n') } }
        }
        return RenderedLedgerBatch("text/plain", "txt", text.toByteArray(Charsets.UTF_8))
    }
}

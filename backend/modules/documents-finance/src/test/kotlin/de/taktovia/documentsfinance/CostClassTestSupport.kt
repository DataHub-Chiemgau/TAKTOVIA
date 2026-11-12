package de.taktovia.documentsfinance

import de.taktovia.documentsfinance.api.ApprovalDecision
import de.taktovia.documentsfinance.api.CostAttribution
import de.taktovia.documentsfinance.api.CostBehavior
import de.taktovia.documentsfinance.api.CostSummaryQuery
import de.taktovia.documentsfinance.api.CostSummaryView
import de.taktovia.documentsfinance.api.CreateManualCostEvent
import de.taktovia.documentsfinance.api.ManualCostEventView
import de.taktovia.documentsfinance.api.PutAccountingMapping
import de.taktovia.documentsfinance.api.UpdateFinancePolicy
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.RequestContext
import java.sql.Timestamp
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * Testhilfe des Clusters wave12-c8 (WP-M4-39, -40): ein Mandant mit Rechtsträger, Fahrzeug, Fiskalperioden September bis November 2026 (offen), Kontierung für alle Katalogkostenarten, Personen mit den
 * Rechten der Kostenklassifikation und des manuellen Kostenereignisses sowie direkt eingespielten gebuchten Ereignissen (Ereignis, ausgeglichenes gebuchtes Journal) mit vorgegebener Klassifikation.
 */
class CostClassWorld(val bf: BelegFixture, threshold: Long? = null) {
    val lw = LedgerWorld(bf)
    val tenant get() = lw.w.tenant
    val entity get() = lw.w.legalEntity
    val vehicle get() = lw.vehicle
    val sep = LocalDate.of(2026, 9, 1)

    val reader = lw.person(setOf("finance"), setOf("costs:read"))
    val reclassifier = lw.person(setOf("finance"), setOf("costs:read", "costs:reverse"))
    /** Erfasser eines manuellen Kostenereignisses; hat zusätzlich Freigaberechte, damit die Selbstfreigabe gegen die Regel (nicht gegen fehlendes Recht) geprüft wird. */
    val creator = lw.person(setOf("finance"), setOf("costs:write", "costs:read", "expenses:review", "expenses:approve"))
    val reviewer = lw.person(setOf("finance"), setOf("costs:read", "expenses:review"))
    val approver1 = lw.person(setOf("finance"), setOf("costs:read", "expenses:approve"))
    val approver2 = lw.person(setOf("finance"), setOf("costs:read", "expenses:approve"))
    val approver3 = lw.person(setOf("finance"), setOf("costs:read", "expenses:approve"))

    init {
        lw.autumn()
        for (c in listOf("ACQUISITION_LEASE", "INSURANCE", "VEHICLE_TAX", "ENERGY", "MAINTENANCE", "REPAIR", "FUEL", "GESTELLUNG", "DRIVER", "DISPATCH", "OVERHEAD", "MIXEDCOST"))
            bf.svc.accounting.put(lw.fin, PutAccountingMapping(entity, c, "6000", "1576", "1600"))
        if (threshold != null) bf.svc.policies.update(lw.fin, UpdateFinancePolicy(approvalSecondThresholdMinor = threshold))
    }

    fun costObject(kind: String, ref: String = UUID.randomUUID().toString()): UUID {
        val id = UUID.randomUUID()
        if (kind == "VEHICLE") error("Fahrzeug-Kostenobjekt über vehicleObject()")
        bf.fx.exec("INSERT INTO taktovia.cost_object(tenant_id,id,legal_entity_id,kind,business_ref) VALUES (?,?,?,?,?)", tenant.value, id, entity, kind, ref)
        return id
    }

    fun vehicleObject(v: UUID = vehicle): UUID {
        val id = UUID.randomUUID()
        bf.fx.exec("INSERT INTO taktovia.cost_object(tenant_id,id,legal_entity_id,kind,business_ref,vehicle_id) VALUES (?,?,?,'VEHICLE',?,?) ON CONFLICT DO NOTHING", tenant.value, id, entity, "$v/$entity", v)
        return UUID.fromString(bf.fx.strings("SELECT id::text FROM taktovia.cost_object WHERE tenant_id=? AND kind='VEHICLE' AND vehicle_id=? AND legal_entity_id=?", tenant.value, v, entity).single())
    }

    /** Gebuchtes Ist-Ereignis mit gebuchtem, ausgeglichenem Journal; `posted = false` lässt das Journal im Entwurf (zählt in keiner Auswertung). */
    fun posted(category: String, behavior: CostBehavior?, attribution: CostAttribution?, gross: Long, obj: UUID?, date: LocalDate = LocalDate.of(2026, 9, 15), currency: String = "EUR", posted: Boolean = true,
               serviceFrom: LocalDate? = null, serviceTo: LocalDate? = null): UUID {
        val e = UUID.randomUUID(); val j = UUID.randomUUID()
        val at = Timestamp.from(date.atStartOfDay().toInstant(ZoneOffset.UTC))
        bf.fx.exec("INSERT INTO taktovia.economic_event(tenant_id,id,legal_entity_id,source_system,source_ref,event_type,scenario,cost_behavior,attribution,category,currency,net_minor,tax_minor,gross_minor,tax_rule_revision," +
            "occurred_at,booked_at,service_from,service_to,cost_object_id) VALUES (?,?,?,'TEST',?,'TEST_COST','ACTUAL',?,?,?,?,?,0,?,'T',?,clock_timestamp(),?,?,?)",
            tenant.value, e, entity, e.toString(), behavior?.name, attribution?.name, category, currency, gross, gross, at,
            serviceFrom?.let { Timestamp.from(it.atStartOfDay().toInstant(ZoneOffset.UTC)) }, serviceTo?.let { Timestamp.from(it.atStartOfDay().toInstant(ZoneOffset.UTC)) }, obj)
        val period = UUID.fromString(bf.fx.strings("SELECT id::text FROM taktovia.fiscal_period WHERE tenant_id=? AND legal_entity_id=? AND starts_on <= ? AND ends_on > ?", tenant.value, entity, java.sql.Date.valueOf(date), java.sql.Date.valueOf(date)).single())
        bf.fx.exec("INSERT INTO taktovia.journal(tenant_id,id,event_id,legal_entity_id,currency,period_id,accounting_date,state) VALUES (?,?,?,?,?,?,?,'DRAFT')", tenant.value, j, e, entity, currency, period, java.sql.Date.valueOf(date))
        bf.fx.exec("INSERT INTO taktovia.journal_line(tenant_id,id,journal_id,account_code,side,amount_minor,account_role) VALUES (?,?,?,'6000','D',?,'EXPENSE')", tenant.value, UUID.randomUUID(), j, gross)
        bf.fx.exec("INSERT INTO taktovia.journal_line(tenant_id,id,journal_id,account_code,side,amount_minor,account_role) VALUES (?,?,?,'1600','C',?,'PAYABLE')", tenant.value, UUID.randomUUID(), j, gross)
        if (posted) bf.fx.exec("UPDATE taktovia.journal SET state='POSTED', posted_by='user:test' WHERE id=?", j)
        return e
    }

    fun summary(from: LocalDate = sep, to: LocalDate = LocalDate.of(2026, 12, 1), currency: String = "EUR", vehicleId: UUID? = null, ctx: RequestContext = reader): CostSummaryView =
        bf.svc.classifications.summary(ctx, CostSummaryQuery("ACTUAL", currency, from, to, null, vehicleId))

    fun manual(category: String, gross: Long, date: LocalDate, serviceFrom: LocalDate? = null, serviceTo: LocalDate? = null, key: String = UUID.randomUUID().toString(), taxMinor: Long = 0, v: UUID = vehicle, ctx: RequestContext = creator): ManualCostEventView =
        bf.svc.manualCosts.create(ctx, CreateManualCostEvent(entity, v, category, "EUR", gross, taxMinor, accountingDate = date, serviceFrom = serviceFrom, serviceTo = serviceTo, description = "Test $category", originKey = key)).view

    /** Prüfung und Budgetfreigabe durch verschiedene Personen (ohne Schwelle: gebucht); liefert die Sicht nach der Freigabe. */
    fun release(m: ManualCostEventView): ManualCostEventView {
        val r = bf.svc.manualCosts.review(reviewer, m.id, m.revision, ApprovalDecision.APPROVED, "Sachlich geprüft")
        return bf.svc.manualCosts.approve(approver1, r.id, r.revision, "Freigegeben").view
    }

    fun events(sql: String, vararg args: Any?) = bf.fx.count(sql, *args)
    fun person(roles: Set<String>, scopes: Set<String>, type: ActorType = ActorType.HUMAN) = lw.person(roles, scopes, type)
}

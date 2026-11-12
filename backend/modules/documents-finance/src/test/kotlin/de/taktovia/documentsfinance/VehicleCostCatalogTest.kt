package de.taktovia.documentsfinance

import de.taktovia.documentsfinance.api.ApprovalDecision
import de.taktovia.documentsfinance.api.CostAttribution
import de.taktovia.documentsfinance.api.CostBehavior
import de.taktovia.documentsfinance.api.CreateManualCostEvent
import de.taktovia.documentsfinance.api.ExpenseStates
import de.taktovia.documentsfinance.api.VehicleCostQuery
import de.taktovia.documentsfinance.api.VehicleCostGroups
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.storage.InMemoryObjectStore
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M4-40 (FCO-002): strukturierte Fahrzeugkosten. Echte Datenbank unter der Runtime-Rolle: alle sechs Kostengruppen je Fahrzeug über drei Monate nach Kostenart ausgewertet (centgenau), manuelles
 * Kostenereignis über denselben Freigabeautomaten wie ein Beleg (Rechte, Selbstfreigabe, Vier-Augen-Schwelle), Monatszuordnung nach Leistungszeitraum bzw. Buchungsdatum, einmaliges Buchen auch parallel.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VehicleCostCatalogTest {
    private lateinit var bf: BelegFixture

    @BeforeAll fun start() { bf = BelegFixture(InMemoryObjectStore()) }
    @AfterAll fun stop() { bf.close() }

    private fun q(from: String = "2026-09", to: String = "2026-11", currency: String = "EUR") = VehicleCostQuery("ACTUAL", currency, YearMonth.parse(from), YearMonth.parse(to))
    private fun d(m: Int, day: Int = 10) = LocalDate.of(2026, m, day)

    @Test
    fun sechs_kostengruppen_je_fahrzeug_ueber_drei_monate_nach_kostenart_ausgewertet() {
        val w = CostClassWorld(bf)
        // sechs Gruppen x drei Monate, je Monat und Kostenart ein eindeutiger Betrag (centgenau nachrechenbar)
        val amounts = HashMap<Pair<Int, String>, Long>()
        for ((mi, month) in listOf(9, 10, 11).withIndex()) for ((gi, group) in VehicleCostGroups.ALL.withIndex()) {
            val gross = 100_00L * (gi + 1) + 7 * (mi + 1) + 3L * gi      // z. B. 100,10 / 200,15 ...
            amounts[month to group] = gross
            val m = w.manual(group, gross, d(month))
            assertEquals(ExpenseStates.POSTED, w.release(m).state)
        }
        val r = bf.svc.vehicleCosts.totalCost(w.reader, w.vehicle, q())
        assertEquals("ACTUAL", r.valuation); assertEquals("EUR", r.currency); assertEquals(YearMonth.of(2026, 9), r.monthFrom); assertEquals(YearMonth.of(2026, 11), r.monthTo)
        assertNotNull(r.asOf); assertTrue(r.assignmentRule.isNotBlank(), "die Zuordnungsregel steht im Ergebnis")
        assertEquals(18, r.items.size, "jede der sechs Gruppen in jedem der drei Monate, ohne manuelle Tabellen")
        for (it in r.items) {
            assertEquals(amounts.getValue(it.month.monthValue to it.category), it.amountMinor, "${it.month} ${it.category}")
            assertEquals(it.category, it.costGroup, "jede Gruppe ist dem Fahrzeug strukturiert zugeordnet"); assertEquals(1, it.eventCount)
        }
        assertEquals(VehicleCostGroups.ALL.toSet(), r.items.map { it.category }.toSet())
        for (mt in r.monthTotals) assertEquals(amounts.filterKeys { it.first == mt.month.monthValue }.values.sum(), mt.amountMinor, "Summe je Monat ${mt.month}")
        for (ct in r.categoryTotals) assertEquals(amounts.filterKeys { it.second == ct.category }.values.sum(), ct.amountMinor, "Summe je Kostenart ${ct.category}")
        assertEquals(amounts.values.sum(), r.totalMinor)
        // das manuelle Ereignis ist klassifiziert (Katalog): Anschaffung, Versicherung, Kfz-Steuer fix, der Rest variabel, alle direkt
        val s = w.summary(vehicleId = w.vehicle).totals
        assertEquals(amounts.filterKeys { it.second in setOf("ACQUISITION_LEASE", "INSURANCE", "VEHICLE_TAX") }.values.sum(), s.fixedMinor)
        assertEquals(amounts.filterKeys { it.second in setOf("ENERGY", "MAINTENANCE", "REPAIR") }.values.sum(), s.variableMinor)
        assertEquals(s.totalMinor, s.directMinor); assertEquals(0, s.unclassifiedMinor)
        assertEquals(18, w.events("SELECT count(*) FROM taktovia.economic_event WHERE tenant_id=? AND event_type='MANUAL_COST'", w.tenant.value))
    }

    @Test
    fun manuelles_kostenereignis_zweimal_parallel_genau_ein_economic_event() {
        val w = CostClassWorld(bf)
        // (a) dasselbe Ereignis parallel angelegt: ein Vorgang, die übrigen erhalten ihn zurück
        val key = "kfz-steuer-2026-${UUID.randomUUID()}"
        val pool = Executors.newFixedThreadPool(6); var go = CountDownLatch(1)
        val creates = (0 until 4).map { pool.submit<Result<de.taktovia.documentsfinance.api.ManualCostCreated>> { go.await(); runCatching { bf.svc.manualCosts.create(w.creator, CreateManualCostEvent(w.entity, w.vehicle, "VEHICLE_TAX", "EUR", 28000, accountingDate = d(9), originKey = key)) } } }
        go.countDown(); val c = creates.map { it.get(60, TimeUnit.SECONDS).getOrThrow() }
        assertEquals(1, c.map { it.view.id }.toSet().size, "ein einziger Vorgang"); assertEquals(1, c.count { !it.reused }, "genau einmal angelegt")
        assertEquals(1, w.events("SELECT count(*) FROM taktovia.manual_cost_event WHERE tenant_id=? AND origin_key=?", w.tenant.value, key))
        // gleicher Ursprung, anderer Inhalt: 409
        assertEquals(ErrorCode.CONFLICT, bf.problem { bf.svc.manualCosts.create(w.creator, CreateManualCostEvent(w.entity, w.vehicle, "VEHICLE_TAX", "EUR", 29000, accountingDate = d(9), originKey = key)) }.code)
        // (b) dasselbe Ereignis parallel freigegeben: genau ein economic_event, die zweite Freigabe scheitert am Zustand
        val m = bf.svc.manualCosts.review(w.reviewer, c.first().view.id, 1, ApprovalDecision.APPROVED, "Sachlich geprüft")
        go = CountDownLatch(1)
        val approvals = listOf(w.approver1, w.approver2, w.approver3).map { a -> pool.submit<Result<Any>> { go.await(); runCatching { bf.svc.manualCosts.approve(a, m.id, m.revision, "Freigegeben") } } }
        go.countDown(); val out = approvals.map { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(1, out.count { it.isSuccess }, out.toString())
        assertTrue(out.filter { it.isFailure }.all { (it.exceptionOrNull() as ProblemException).code in setOf(ErrorCode.INVALID_STATE_TRANSITION, ErrorCode.REVISION_MISMATCH) }, out.toString())
        assertEquals(1, w.events("SELECT count(*) FROM taktovia.economic_event WHERE tenant_id=? AND source_ref=?", w.tenant.value, key), "genau ein economic_event je fachlichem Ursprung")
        assertEquals(1, w.events("SELECT count(*) FROM taktovia.journal j JOIN taktovia.economic_event e ON e.tenant_id=j.tenant_id AND e.id=j.event_id WHERE e.tenant_id=? AND e.source_ref=?", w.tenant.value, key))
        assertEquals(1, w.events("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND event_type='cost_event.posted' AND payload->>'event_type'='MANUAL_COST'", w.tenant.value))
        // auch nach dem Buchen liefert ein erneutes Anlegen denselben Vorgang
        val again = bf.svc.manualCosts.create(w.creator, CreateManualCostEvent(w.entity, w.vehicle, "VEHICLE_TAX", "EUR", 28000, accountingDate = d(9), originKey = key))
        assertTrue(again.reused); assertEquals(ExpenseStates.POSTED, again.view.state); assertNotNull(again.view.eventId)
        assertEquals(1, w.events("SELECT count(*) FROM taktovia.economic_event WHERE tenant_id=? AND source_ref=?", w.tenant.value, key))
    }

    @Test
    fun manuelles_kostenereignis_ohne_freigaberecht_abgewiesen() {
        val w = CostClassWorld(bf)
        val nobody = listOf(
            "ohne Scope" to w.person(setOf("finance"), setOf("costs:read")), "Fahrer" to w.person(setOf("driver"), setOf("costs:write")),
            "Dienstkonto" to w.person(setOf("finance"), setOf("costs:write"), ActorType.SERVICE), "ohne Rolle" to w.person(emptySet(), setOf("costs:write")))
        for ((name, c) in nobody) assertEquals(ErrorCode.FORBIDDEN, bf.problem { w.manual("INSURANCE", 5000, d(9), ctx = c) }.code, "Anlegen $name")
        assertEquals(0, w.events("SELECT count(*) FROM taktovia.manual_cost_event WHERE tenant_id=?", w.tenant.value))
        val m = w.manual("INSURANCE", 5000, d(9))
        // Prüfung und Freigabe brauchen jeweils eigenes Recht: Scope, Finanzrolle, Mensch
        val noReview = listOf(w.approver1, w.person(setOf("driver"), setOf("expenses:review")), w.person(setOf("finance"), setOf("expenses:review"), ActorType.SERVICE))
        for ((i, c) in noReview.withIndex()) assertEquals(ErrorCode.FORBIDDEN, bf.problem { bf.svc.manualCosts.review(c, m.id, m.revision, ApprovalDecision.APPROVED, "Sachlich geprüft") }.code, "Prüfung $i")
        val r = bf.svc.manualCosts.review(w.reviewer, m.id, m.revision, ApprovalDecision.APPROVED, "Sachlich geprüft")
        val noApprove = listOf(w.reviewer, w.person(setOf("driver"), setOf("expenses:approve")), w.person(setOf("finance"), setOf("expenses:approve"), ActorType.SERVICE), w.person(setOf("finance"), setOf("costs:write")))
        for ((i, c) in noApprove.withIndex()) {
            assertEquals(ErrorCode.FORBIDDEN, bf.problem { bf.svc.manualCosts.approve(c, m.id, r.revision, "Freigegeben") }.code, "Freigabe $i")
            assertEquals(ErrorCode.FORBIDDEN, bf.problem { bf.svc.manualCosts.reject(c, m.id, r.revision, "Abgelehnt") }.code, "Ablehnung $i")
        }
        assertEquals(0, w.events("SELECT count(*) FROM taktovia.economic_event WHERE tenant_id=? AND event_type='MANUAL_COST'", w.tenant.value), "ohne Freigaberecht entsteht nie ein Ereignis")
        assertEquals(0, w.events("SELECT count(*) FROM taktovia.manual_cost_event_step WHERE tenant_id=? AND step IN ('BUDGET','SECOND_APPROVAL')", w.tenant.value), "keine Schritte ohne Recht")
        assertEquals(ExpenseStates.REVIEWED, bf.svc.manualCosts.get(w.reader, m.id).state)
        // veraltete Revision: 412; Ablehnung ist endgültig
        assertEquals(ErrorCode.REVISION_MISMATCH, bf.problem { bf.svc.manualCosts.approve(w.approver1, m.id, r.revision + 1, "Freigegeben") }.code)
        assertEquals("REJECTED", bf.svc.manualCosts.reject(w.approver1, m.id, r.revision, "Nicht budgetiert").state)
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, bf.problem { bf.svc.manualCosts.approve(w.approver2, m.id, bf.svc.manualCosts.get(w.reader, m.id).revision, "Doch freigeben") }.code)
        assertEquals(0, w.events("SELECT count(*) FROM taktovia.economic_event WHERE tenant_id=? AND event_type='MANUAL_COST'", w.tenant.value))
    }

    @Test
    fun monat_ohne_kosten_leer_200_fremdes_fahrzeug_404_unbekannte_kostenart_422() {
        val w = CostClassWorld(bf); val other = CostClassWorld(bf)
        val empty = bf.svc.vehicleCosts.totalCost(w.reader, w.vehicle, q("2026-09", "2026-10"))
        assertTrue(empty.items.isEmpty() && empty.monthTotals.isEmpty() && empty.categoryTotals.isEmpty()); assertEquals(0, empty.totalMinor, "leere Auswertung statt Fehler")
        assertEquals(ErrorCode.NOT_FOUND, bf.problem { bf.svc.vehicleCosts.totalCost(w.reader, other.vehicle, q()) }.code, "Fahrzeug eines anderen Mandanten")
        assertEquals(ErrorCode.NOT_FOUND, bf.problem { bf.svc.vehicleCosts.totalCost(w.reader, UUID.randomUUID(), q()) }.code)
        val ok = VehicleCostQuery("ACTUAL", "EUR", YearMonth.of(2026, 9), YearMonth.of(2026, 10))
        for ((name, qq) in mapOf("ohne Bewertungsart" to ok.copy(valuation = null), "Plan" to ok.copy(valuation = "PLAN"), "ohne Währung" to ok.copy(currency = null), "ohne Beginn" to ok.copy(monthFrom = null), "ohne Ende" to ok.copy(monthTo = null),
            "Ende vor Beginn" to ok.copy(monthTo = YearMonth.of(2026, 8)), "zu lang" to ok.copy(monthTo = YearMonth.of(2040, 1)))) assertEquals(ErrorCode.VALIDATION_FAILED, bf.problem { bf.svc.vehicleCosts.totalCost(w.reader, w.vehicle, qq) }.code, name)
        assertEquals(ErrorCode.FORBIDDEN, bf.problem { bf.svc.vehicleCosts.totalCost(w.person(setOf("finance"), setOf("receipts:read")), w.vehicle, q()) }.code)
        // Anlegen: unbekannte Kostenart 422, Fahrzeug oder Rechtsträger eines anderen Mandanten 404, fehlende Kontierung 409, ungültige Beträge/Daten 422
        val base = CreateManualCostEvent(w.entity, w.vehicle, "INSURANCE", "EUR", 5000, accountingDate = d(9), originKey = "k-${UUID.randomUUID()}")
        fun create(c: CreateManualCostEvent) = bf.problem { bf.svc.manualCosts.create(w.creator, c) }
        assertEquals(ErrorCode.VALIDATION_FAILED, create(base.copy(category = "NO_SUCH_KIND")).code, "unbekannte Kostenart")
        assertEquals(ErrorCode.VALIDATION_FAILED, create(base.copy(category = "insurance")).code)
        assertEquals(ErrorCode.NOT_FOUND, create(base.copy(vehicleId = other.vehicle)).code, "Fahrzeug eines anderen Mandanten")
        assertEquals(ErrorCode.NOT_FOUND, create(base.copy(legalEntityId = other.entity)).code, "Rechtsträger eines anderen Mandanten")
        w.bf.fx.exec("INSERT INTO taktovia.cost_category(tenant_id,category,label) VALUES (?,'NOMAP','Ohne Kontierung')", w.tenant.value)
        assertEquals(ErrorCode.CONFLICT, create(base.copy(category = "NOMAP")).code, "Kontierungsregel für die Kostenart fehlt")
        for ((name, c) in mapOf("Betrag 0" to base.copy(grossMinor = 0), "negativer Betrag" to base.copy(grossMinor = -5), "Steuer größer Brutto" to base.copy(taxMinor = 6000), "Währung" to base.copy(currency = "eur"),
            "Leistungsende vor Beginn" to base.copy(serviceFrom = d(9, 20), serviceTo = d(9, 10)), "Leistungsende ohne Beginn" to base.copy(serviceTo = d(9, 10)), "ohne Ursprungsschlüssel" to base.copy(originKey = " "), "zu großer Betrag" to base.copy(grossMinor = Long.MAX_VALUE)))
            assertEquals(ErrorCode.VALIDATION_FAILED, create(c).code, name)
        assertEquals(0, w.events("SELECT count(*) FROM taktovia.manual_cost_event WHERE tenant_id=?", w.tenant.value))
        // Lesen eines Vorgangs im fremden Mandanten
        val m = w.manual("INSURANCE", 5000, d(9))
        assertEquals(ErrorCode.NOT_FOUND, bf.problem { bf.svc.manualCosts.get(other.reader, m.id) }.code)
        assertEquals(ErrorCode.NOT_FOUND, bf.problem { bf.svc.manualCosts.review(other.reviewer, m.id, 1, ApprovalDecision.APPROVED, "Sachlich geprüft") }.code)
    }

    @Test
    fun selbstfreigabe_ueber_schwelle_abgewiesen_und_anschaffung_nicht_abgeschrieben() {
        val w = CostClassWorld(bf, threshold = 10000)
        // Anschaffung/Leasing: die erfasste Position erscheint im Monat der Erfassung, ohne Abschreibung und ohne Verteilung auf andere Monate
        val acquisition = w.manual("ACQUISITION_LEASE", 4_500_000, d(10, 5))
        val c = bf.svc.manualCosts
        c.review(w.reviewer, acquisition.id, 1, ApprovalDecision.APPROVED, "Sachlich geprüft")
        // Selbstfreigabe: der Erfasser hat Prüf- und Freigaberecht, gibt aber nie selbst frei
        assertEquals(ErrorCode.FORBIDDEN, bf.problem { c.approve(w.creator, acquisition.id, acquisition.revision, "Selbst freigegeben") }.code, "Selbstfreigabe")
        val small = w.manual("INSURANCE", 100, d(9)); val smallReviewed = c.review(w.reviewer, small.id, 1, ApprovalDecision.APPROVED, "Sachlich geprüft")
        assertEquals(ErrorCode.FORBIDDEN, bf.problem { c.approve(w.creator, small.id, smallReviewed.revision, "Selbst") }.code, "Selbstfreigabe auch unter der Schwelle")
        // über der Schwelle: erste Freigabe wartet auf die zweite Person; der Erstfreigeber und der Erfasser dürfen nicht zweit freigeben
        val waiting = c.approve(w.approver1, acquisition.id, acquisition.revision, "Budget frei")
        assertEquals(ExpenseStates.WAITING_SECOND_APPROVAL, waiting.view.state); assertEquals(null, waiting.postingId)
        assertEquals(0, w.events("SELECT count(*) FROM taktovia.economic_event WHERE tenant_id=? AND event_type='MANUAL_COST'", w.tenant.value), "vor der zweiten Freigabe keine Buchung")
        assertEquals(ErrorCode.FORBIDDEN, bf.problem { c.approve(w.approver1, acquisition.id, acquisition.revision, "nochmal") }.code, "Doppelfreigabe derselben Person")
        assertEquals(ErrorCode.FORBIDDEN, bf.problem { c.approve(w.creator, acquisition.id, acquisition.revision, "Selbst zweit") }.code, "Erfasser als zweite Freigabe")
        val posted = c.approve(w.approver2, acquisition.id, acquisition.revision, "Zweite Freigabe")
        assertEquals(ExpenseStates.POSTED, posted.view.state); assertNotNull(posted.postingId)
        assertEquals(listOf("CAPTURE", "REVIEW", "BUDGET", "SECOND_APPROVAL"), posted.view.steps.map { it.step.name })
        // genau die erfasste Position im Oktober, nichts in den Folgemonaten
        val r = bf.svc.vehicleCosts.totalCost(w.reader, w.vehicle, q())
        assertEquals(1, r.items.size); assertEquals(YearMonth.of(2026, 10), r.items.single().month); assertEquals(4_500_000, r.items.single().amountMinor); assertEquals(4_500_000, r.totalMinor)
        assertEquals(0, r.monthTotals.count { it.month != YearMonth.of(2026, 10) })
        assertFalse(w.events("SELECT count(*) FROM taktovia.economic_event WHERE tenant_id=? AND category='ACQUISITION_LEASE'", w.tenant.value) > 1, "keine Abschreibungsbuchungen")
        // exakt auf der Schwelle genügt eine Freigabe
        val onLimit = w.release(w.manual("INSURANCE", 10000, d(10, 6)))
        assertEquals(ExpenseStates.POSTED, onLimit.state)
    }

    @Test
    fun monatszuordnung_leistungszeitraum_vor_buchungsperiode_im_ergebnis_ausgewiesen() {
        val w = CostClassWorld(bf)
        // Leistungszeitraum im September, Buchungsdatum im Oktober: Monat September (Leistungszeitraum vor Buchungsperiode); ohne Leistungszeitraum folgt das Buchungsdatum
        w.release(w.manual("INSURANCE", 12000, d(10, 5), serviceFrom = LocalDate.of(2026, 9, 1), serviceTo = LocalDate.of(2026, 9, 30)))
        w.release(w.manual("ENERGY", 3000, d(10, 5)))
        w.release(w.manual("REPAIR", 4000, d(11, 5), serviceFrom = LocalDate.of(2026, 10, 28), serviceTo = LocalDate.of(2026, 11, 3)))   // über zwei Monate: Beginn zählt, keine anteilige Verteilung
        val r = bf.svc.vehicleCosts.totalCost(w.reader, w.vehicle, q())
        assertTrue(r.assignmentRule.contains("service_from") && r.assignmentRule.contains("accounting_date"), r.assignmentRule)
        val byKey = r.items.associateBy { it.month.monthValue to it.category }
        assertEquals(12000, byKey.getValue(9 to "INSURANCE").amountMinor); assertEquals(1, byKey.getValue(9 to "INSURANCE").byServicePeriodEvents); assertEquals(0, byKey.getValue(9 to "INSURANCE").byAccountingDateEvents)
        assertEquals(3000, byKey.getValue(10 to "ENERGY").amountMinor); assertEquals(0, byKey.getValue(10 to "ENERGY").byServicePeriodEvents); assertEquals(1, byKey.getValue(10 to "ENERGY").byAccountingDateEvents)
        assertEquals(4000, byKey.getValue(10 to "REPAIR").amountMinor, "Leistungsbeginn Oktober, Buchung November"); assertFalse(byKey.containsKey(11 to "REPAIR"))
        assertEquals(3, r.items.size)
        // Teilzeitraum: der Monatsbereich begrenzt die Auswertung
        assertEquals(listOf(YearMonth.of(2026, 9)), bf.svc.vehicleCosts.totalCost(w.reader, w.vehicle, q("2026-09", "2026-09")).monthTotals.map { it.month })
    }
}

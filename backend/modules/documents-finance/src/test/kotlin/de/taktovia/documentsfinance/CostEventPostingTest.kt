package de.taktovia.documentsfinance

import de.taktovia.documentsfinance.api.AssignCosts
import de.taktovia.documentsfinance.api.CorrectExpense
import de.taktovia.documentsfinance.api.ExpenseFields
import de.taktovia.documentsfinance.api.PostExpense
import de.taktovia.documentsfinance.api.PutAccountingMapping
import de.taktovia.documentsfinance.api.StoredDocumentView
import de.taktovia.documentsfinance.api.TaxTreatment
import de.taktovia.platform.context.Actor
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.storage.InMemoryObjectStore
import de.taktovia.platform.tenant.TenantId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M4-33: Kostenereignis, Journal und einmaliges Posting (PH-AT-012, PH-AT-013). Echte Datenbank unter der Runtime-Rolle: Posting einer Tankung (119,00 brutto, 100,00 netto, 19,00 Vorsteuer),
 * Wiederholung und parallele Auslöser ohne Summenänderung, Abweisungen (Kontierung, Periode, Zuordnung, Integrität, Zustand, Berechtigung), Atomarität, Ist-Bericht nur mit `POSTED`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CostEventPostingTest {
    private lateinit var bf: BelegFixture

    @BeforeAll fun start() { bf = BelegFixture(InMemoryObjectStore()) }
    @AfterAll fun stop() { bf.close() }

    /** Mandant mit Rechtsträger, offener Periode 2026, Kontierung FUEL, Fahrzeug und einem freigegebenen Tankbeleg (119,00 EUR brutto, 19,00 Vorsteuer, Kostenzuordnung vorhanden). */
    private inner class Scenario(val amount: Long = 11900, val vat: Long = 1900, val mapping: Boolean = true, val assignment: Boolean = true, val periodClosed: Boolean = false, val approve: Boolean = true) {
        val w = bf.fx.world()
        val fin = bf.financeCtx(w)
        val poster = ctxWith(w.tenant, setOf("finance"), setOf("expenses:approve", "receipts:write", "receipts:read", "costs:read"))
        val reader = ctxWith(w.tenant, setOf("finance"), setOf("costs:read"))
        val vehicle = vehicle(w.tenant)
        val period = UUID.randomUUID()
        val doc: StoredDocumentView
        init {
            bf.fx.exec("INSERT INTO taktovia.fiscal_period(tenant_id,id,legal_entity_id,starts_on,ends_on,closed_at) VALUES (?,?,?,DATE '2026-01-01',DATE '2027-01-01',?)", w.tenant.value, period, w.legalEntity,
                if (periodClosed) java.sql.Timestamp.from(Instant.now()) else null)
            if (mapping) bf.svc.accounting.put(fin.withScopes("expenses:approve"), PutAccountingMapping(w.legalEntity, "FUEL", "6600", "1576", "1600"))
            doc = bf.upload(w, fin)
            val fields = mutableMapOf<String, Any?>(ExpenseFields.AMOUNT_MINOR to amount, ExpenseFields.CURRENCY to "EUR", ExpenseFields.DOCUMENT_DATE to "2026-06-15", ExpenseFields.MERCHANT to "Tankstelle Nord")
            if (vat > 0) fields[ExpenseFields.VAT_AMOUNTS] = listOf(mapOf("vat_rate_pct" to "19", "amount_minor" to vat))
            bf.svc.expenses.correct(fin, doc.documentId, 1, CorrectExpense("Felder erfasst", fields))
            if (assignment) bf.svc.costs.assign(fin, doc.documentId, 2, AssignCosts(vehicleIds = setOf(vehicle)))
            if (approve) approve()
        }
        fun approve() = bf.fx.exec("UPDATE taktovia.expense_document SET state='APPROVED', approved_by='user:freigeber' WHERE id=?", doc.documentId)
        fun post(tax: TaxTreatment = TaxTreatment.FULLY_DEDUCTIBLE, ctx: RequestContext = poster) = bf.svc.costEvents.postExpense(ctx, doc.documentId, PostExpense("FUEL", tax))
        fun events() = bf.fx.count("SELECT count(*) FROM taktovia.economic_event WHERE tenant_id=?", w.tenant.value)
        fun journals() = bf.fx.count("SELECT count(*) FROM taktovia.journal WHERE tenant_id=?", w.tenant.value)
        fun outbox() = bf.fx.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND event_type='cost_event.posted'", w.tenant.value)
    }

    private fun ctxWith(t: TenantId, roles: Set<String>, scopes: Set<String>) =
        RequestContext(Actor(ActorType.HUMAN, "https://idp.test/realms/m4", UUID.randomUUID().toString(), "sid", true), t, roles, scopes, emptySet(), UUID.randomUUID())

    private fun RequestContext.withScopes(vararg s: String) = RequestContext(actor, tenant, roles, scopes + s, services, correlationId)

    private fun vehicle(t: TenantId): UUID {
        val id = UUID.randomUUID()
        bf.fx.exec("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (?,?,'org',CAST('{}' AS jsonb))", t.value, id)
        return id
    }

    @Test
    fun tankung_119_ergibt_genau_ein_kostenereignis_100_kostenbasis_19_vorsteuer_119_verbindlichkeit() {
        val s = Scenario()
        val r = s.post()
        assertFalse(r.reused)
        val e = r.event
        assertEquals(listOf(10000L, 1900L, 11900L, 1900L, 10000L), listOf(e.netMinor, e.taxMinor, e.grossMinor, e.recoverableTaxMinor, e.costBasisMinor), "Kostenbasis 100,00, Vorsteuer 19,00, brutto 119,00")
        assertEquals("EUR", e.currency); assertEquals("ACTUAL", e.scenario); assertEquals(s.doc.documentId, e.documentId); assertEquals(s.w.legalEntity, e.legalEntityId)
        // Journal: Aufwand 100,00 Soll, Vorsteuer 19,00 Soll, Verbindlichkeit 119,00 Haben; gebucht und ausgeglichen
        assertEquals("POSTED", r.journal.state); assertEquals(s.period, r.journal.periodId)
        assertEquals(listOf(Triple("1576", "D", 1900L), Triple("1600", "C", 11900L), Triple("6600", "D", 10000L)), r.journal.lines.map { Triple(it.account, it.side, it.amountMinor) })
        assertEquals(0L, r.journal.lines.sumOf { if (it.side == "D") it.amountMinor else -it.amountMinor })
        assertEquals(1, s.events()); assertEquals(1, s.journals()); assertEquals(1, s.outbox())
        assertEquals("POSTED", bf.fx.strings("SELECT state FROM taktovia.expense_document WHERE id=?", s.doc.documentId).single())
        assertEquals(1, bf.fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='finance.cost_event.posted'", s.w.tenant.value))
        // Ist-Bericht und Salden als Projektion der Journalzeilen
        assertEquals(10000L, bf.svc.costEvents.actualCostBasis(s.reader, s.w.legalEntity, "EUR"))
        assertEquals(mapOf("1576" to 1900L, "1600" to -11900L, "6600" to 10000L), bf.svc.costEvents.accountBalances(s.reader, s.w.legalEntity, "EUR"))
        assertEquals(listOf(e.id), bf.svc.costEvents.list(s.reader, 10, null).map { it.id })
        assertEquals(r.journal.id, bf.svc.costEvents.journal(s.reader, e.id).id)
    }

    @Test
    fun nicht_abziehbare_vorsteuer_geht_in_die_kostenbasis() {
        val s = Scenario(); val r = s.post(TaxTreatment.NON_DEDUCTIBLE)
        assertEquals(listOf(11900L, 0L), listOf(r.event.costBasisMinor, r.event.recoverableTaxMinor))
        assertEquals(listOf("1600" to "C", "6600" to "D"), r.journal.lines.map { it.account to it.side }); assertEquals(setOf(11900L), r.journal.lines.map { it.amountMinor }.toSet())
    }

    @Test
    fun beleg_ohne_steuerangabe_hat_netto_gleich_brutto() {
        val s = Scenario(amount = 5000, vat = 0); val r = s.post()
        assertEquals(listOf(5000L, 0L, 5000L), listOf(r.event.netMinor, r.event.taxMinor, r.event.costBasisMinor)); assertEquals(2, r.journal.lines.size)
    }

    @Test
    fun erneuter_posting_ausloeser_liefert_dasselbe_ereignis_ohne_summenaenderung() {
        val s = Scenario(); val first = s.post()
        val again = s.post()
        assertTrue(again.reused); assertEquals(first.event, again.event); assertEquals(first.journal.id, again.journal.id)
        assertEquals(1, s.events()); assertEquals(1, s.journals()); assertEquals(1, s.outbox())
        assertEquals(10000L, bf.svc.costEvents.actualCostBasis(s.reader, s.w.legalEntity, "EUR"), "keine Summenänderung")
        assertEquals(1, bf.fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='finance.cost_event.posted'", s.w.tenant.value))
    }

    @Test
    fun paralleles_posting_desselben_ursprungs_genau_ein_ereignis_und_ein_journal() {
        repeat(5) {
            val s = Scenario(); val pool = Executors.newFixedThreadPool(8); val start = CountDownLatch(1)
            val futures = (1..8).map { pool.submit<Result<de.taktovia.documentsfinance.api.PostingResult>> { start.await(); runCatching { s.post() } } }
            start.countDown(); val out = futures.map { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
            assertTrue(out.all { it.isSuccess }, out.toString())
            assertEquals(1, out.count { !it.getOrThrow().reused }, "genau einer bucht, die übrigen erhalten dasselbe Ereignis")
            assertEquals(1, out.map { it.getOrThrow().event.id }.toSet().size)
            assertEquals(1, s.events()); assertEquals(1, s.journals()); assertEquals(1, s.outbox())
            assertEquals(10000L, bf.svc.costEvents.actualCostBasis(s.reader, s.w.legalEntity, "EUR"))
        }
    }

    @Test
    fun posting_parallel_zum_periodenabschluss_ist_konsistent() {
        repeat(8) {
            val s = Scenario(); val pool = Executors.newFixedThreadPool(2); val start = CountDownLatch(1)
            val post = pool.submit<Result<Any>> { start.await(); runCatching { s.post() } }
            val close = pool.submit { start.await(); bf.fx.exec("UPDATE taktovia.fiscal_period SET closed_at = clock_timestamp() WHERE id=?", s.period) }
            start.countDown(); close.get(60, TimeUnit.SECONDS); val p = post.get(60, TimeUnit.SECONDS); pool.shutdown()
            if (p.isSuccess) {   // Posting kam vor dem Abschluss: Ereignis, Journal und Belegzustand sind vollständig
                assertEquals(1, s.events()); assertEquals(1, s.journals()); assertEquals("POSTED", bf.fx.strings("SELECT state FROM taktovia.expense_document WHERE id=?", s.doc.documentId).single())
            } else {             // Abschluss kam zuerst: abgewiesen, nichts gebucht, Beleg bleibt freigegeben
                assertEquals(ErrorCode.CONFLICT, (p.exceptionOrNull() as de.taktovia.platform.problem.ProblemException).code, p.toString())
                assertEquals(0, s.events()); assertEquals(0, s.journals()); assertEquals("APPROVED", bf.fx.strings("SELECT state FROM taktovia.expense_document WHERE id=?", s.doc.documentId).single())
            }
        }
    }

    @Test
    fun geschlossene_periode_fehlende_periode_und_fehlende_kontierung_werden_abgewiesen_ohne_spuren() {
        val closed = Scenario(periodClosed = true)
        assertEquals(ErrorCode.CONFLICT, bf.problem { closed.post() }.code); assertTrue(bf.problem { closed.post() }.detail!!.contains("geschlossen"))
        val noMap = Scenario(mapping = false)
        assertEquals(ErrorCode.CONFLICT, bf.problem { noMap.post() }.code); assertTrue(bf.problem { noMap.post() }.detail!!.contains("Kontierungsregel"))
        val noPeriod = Scenario(); bf.fx.exec("UPDATE taktovia.expense_document SET state='STORED' WHERE id=?", noPeriod.doc.documentId)
        bf.fx.exec("DELETE FROM taktovia.fiscal_period WHERE id=?", noPeriod.period); noPeriod.approve()
        assertTrue(bf.problem { noPeriod.post() }.detail!!.contains("keine Buchungsperiode"))
        listOf(closed, noMap, noPeriod).forEach { assertEquals(0, it.events()); assertEquals(0, it.journals()); assertEquals(0, it.outbox()); assertEquals("APPROVED", bf.fx.strings("SELECT state FROM taktovia.expense_document WHERE id=?", it.doc.documentId).single()) }
    }

    @Test
    fun beleg_ohne_freigabe_ohne_kostenzuordnung_oder_mit_inaktivem_kostenobjekt_wird_nicht_gebucht() {
        val unapproved = Scenario(approve = false)
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, bf.problem { unapproved.post() }.code)
        val noAssign = Scenario(assignment = false)
        assertEquals(ErrorCode.CONFLICT, bf.problem { noAssign.post() }.code); assertTrue(bf.problem { noAssign.post() }.detail!!.contains("Kostenzuordnung"))
        val inactive = Scenario()
        bf.svc.costs.deactivate(inactive.fin, bf.svc.costs.assigned(inactive.fin, inactive.doc.documentId).single().id)
        assertEquals(ErrorCode.CONFLICT, bf.problem { inactive.post() }.code); assertTrue(bf.problem { inactive.post() }.detail!!.contains("inaktiv"))
        listOf(unapproved, noAssign, inactive).forEach { assertEquals(0, it.events()); assertEquals(0, it.journals()) }
    }

    @Test
    fun integritaetsverletzung_sperrt_das_posting() {
        val s = Scenario()
        bf.fx.exec("UPDATE taktovia.expense_document SET integrity_state='VIOLATED', integrity_checked_at=now() WHERE id=?", s.doc.documentId)
        assertEquals(ErrorCode.DOCUMENT_INTEGRITY_VIOLATION, bf.problem { s.post() }.code); assertEquals(0, s.events())
    }

    @Test
    fun betrag_ausserhalb_des_sicheren_integerbereichs_und_fehlende_pflichtfelder() {
        val big = Scenario(amount = 1000, vat = 0, approve = false)
        bf.fx.exec("UPDATE taktovia.expense_document SET current_fields = jsonb_set(current_fields, '{amount_minor}', '9007199254740992'::jsonb), revision = revision + 1 WHERE id=?", big.doc.documentId); big.approve()
        // gespeicherte Felder werden beim Lesen normalisiert und lassen den Wert nicht zu; das Posting scheitert mit 422 statt ein Ereignis zu erzeugen
        val p = bf.problem { big.post() }
        assertEquals(ErrorCode.VALIDATION_FAILED, p.code); assertEquals(0, big.events())
        val missing = Scenario(approve = false); bf.fx.exec("UPDATE taktovia.expense_document SET current_fields = current_fields - 'document_date', revision = revision + 1 WHERE id=?", missing.doc.documentId); missing.approve()
        assertEquals(ErrorCode.VALIDATION_FAILED, bf.problem { missing.post() }.code); assertEquals(0, missing.events())
        val taxOver = Scenario(amount = 1000, vat = 1500)
        assertEquals(ErrorCode.VALIDATION_FAILED, bf.problem { taxOver.post() }.code); assertEquals(0, taxOver.events())
    }

    @Test
    fun berechtigung_und_mandantentrennung() {
        val s = Scenario(); val other = Scenario()
        assertEquals(ErrorCode.FORBIDDEN, bf.problem { s.post(ctx = s.reader) }.code, "ohne Scope expenses:approve")
        assertEquals(ErrorCode.FORBIDDEN, bf.problem { s.post(ctx = ctxWith(s.w.tenant, setOf("driver"), setOf("expenses:approve"))) }.code, "ohne Finanzrolle")
        assertEquals(ErrorCode.NOT_FOUND, bf.problem { bf.svc.costEvents.postExpense(other.poster, s.doc.documentId, PostExpense("FUEL")) }.code, "Beleg eines anderen Mandanten")
        s.post()
        assertEquals(emptyList<Any>(), bf.svc.costEvents.list(other.reader, 10, null), "Ist-Bericht je Mandant"); assertEquals(0L, bf.svc.costEvents.actualCostBasis(other.reader, s.w.legalEntity, "EUR"))
        assertEquals(ErrorCode.NOT_FOUND, bf.problem { bf.svc.costEvents.journal(other.reader, UUID.randomUUID()) }.code)
        assertEquals(ErrorCode.FORBIDDEN, bf.problem { bf.svc.costEvents.list(ctxWith(s.w.tenant, setOf("finance"), setOf("receipts:read")), 10, null) }.code)
    }

    @Test
    fun ereignis_und_gebuchtes_journal_sind_unveraenderlich_auch_fuer_die_runtime_rolle() {
        val s = Scenario(); val r = s.post()
        val fails = listOf("UPDATE taktovia.economic_event SET category='X' WHERE id=?", "DELETE FROM taktovia.economic_event WHERE id=?", "UPDATE taktovia.journal SET state='DRAFT' WHERE event_id=?",
            "DELETE FROM taktovia.journal WHERE event_id=?", "UPDATE taktovia.journal_line SET amount_minor=1 WHERE journal_id=(SELECT id FROM taktovia.journal WHERE event_id=?)", "DELETE FROM taktovia.journal_line WHERE journal_id=(SELECT id FROM taktovia.journal WHERE event_id=?)")
        for (sql in fails) {
            val ex = runCatching { bf.fx.tx.run(s.w.tenant) { j -> j.update(sql, r.event.id) } }.exceptionOrNull()
            assertTrue(ex != null, "muss scheitern: $sql")
        }
        assertEquals(r.event, bf.svc.costEvents.list(s.reader, 10, null).single())
    }

    @Test
    fun nur_gebuchte_journale_zaehlen_im_ist_bericht() {
        val s = Scenario(); val le = s.w.legalEntity
        // Ereignis mit Entwurfsjournal (z. B. abgebrochener Buchungsversuch) und Plan-Ereignis zählen nicht
        val draftEvent = UUID.randomUUID(); val planEvent = UUID.randomUUID(); val j = UUID.randomUUID()
        fun insertEvent(id: UUID, scenario: String, ref: String) = bf.fx.exec("INSERT INTO taktovia.economic_event(tenant_id,id,legal_entity_id,source_system,source_ref,event_type,scenario,category,currency,net_minor,tax_minor,gross_minor,tax_rule_revision,occurred_at,booked_at) VALUES (?,?,?,'EXPENSE',?,'EXPENSE_COST',?,'FUEL','EUR',700,0,700,'T',now(),now())", s.w.tenant.value, id, le, ref, scenario)
        insertEvent(draftEvent, "ACTUAL", "draft-1"); insertEvent(planEvent, "PLAN", "plan-1")
        bf.fx.exec("INSERT INTO taktovia.journal(tenant_id,id,event_id,legal_entity_id,currency,period_id,accounting_date,state) VALUES (?,?,?,?,'EUR',?,DATE '2026-06-15','DRAFT')", s.w.tenant.value, j, draftEvent, le, s.period)
        bf.fx.exec("INSERT INTO taktovia.journal_line(tenant_id,id,journal_id,account_code,side,amount_minor) VALUES (?,?,?,'6600','D',700), (?,?,?,'1600','C',700)", s.w.tenant.value, UUID.randomUUID(), j, s.w.tenant.value, UUID.randomUUID(), j)
        assertEquals(0L, bf.svc.costEvents.actualCostBasis(s.reader, le, "EUR")); assertEquals(emptyMap<String, Long>(), bf.svc.costEvents.accountBalances(s.reader, le, "EUR")); assertEquals(0, bf.svc.costEvents.list(s.reader, 10, null).size)
        s.post()
        assertEquals(10000L, bf.svc.costEvents.actualCostBasis(s.reader, le, "EUR")); assertFalse(bf.svc.costEvents.list(s.reader, 10, null).any { it.id == draftEvent || it.id == planEvent })
    }

    @Test
    fun liste_mit_cursor_und_as_of() {
        val s = Scenario()
        val docs = (1..3).map { i ->
            val d = bf.upload(s.w, s.fin, Samples.jpeg(seed = 40 + i))
            bf.svc.expenses.correct(s.fin, d.documentId, 1, CorrectExpense("x", mapOf(ExpenseFields.AMOUNT_MINOR to 1000L * i, ExpenseFields.CURRENCY to "EUR", ExpenseFields.DOCUMENT_DATE to "2026-06-15")))
            bf.svc.costs.assign(s.fin, d.documentId, 2, AssignCosts(vehicleIds = setOf(s.vehicle)))
            bf.fx.exec("UPDATE taktovia.expense_document SET state='APPROVED', approved_by='user:f' WHERE id=?", d.documentId)
            d.documentId
        }
        val ids = docs.map { bf.svc.costEvents.postExpense(s.poster, it, PostExpense("FUEL")).event }
        val page1 = bf.svc.costEvents.list(s.reader, 2, null)
        assertEquals(ids.take(2).map { it.id }, page1.map { it.id })
        val page2 = bf.svc.costEvents.list(s.reader, 2, page1.last().let { it.bookedAt to it.id })
        assertEquals(listOf(ids[2].id), page2.map { it.id })
        assertEquals(listOf(ids[0].id), bf.svc.costEvents.list(s.reader, 10, null, asOf = ids[0].bookedAt).map { it.id }, "as_of: nur Stand bis zum Zeitpunkt")
        assertNotEquals(ids[0].bookedAt, ids[2].bookedAt)
    }

    @Test
    fun kontierungsregel_pflege() {
        val s = Scenario(mapping = false); val other = bf.fx.world()
        val fin = s.fin.withScopes("expenses:approve")
        val m = bf.svc.accounting.put(fin, PutAccountingMapping(s.w.legalEntity, "FUEL", "6600", "1576", "1600")); assertEquals(1L, m.revision)
        assertEquals(1L, bf.svc.accounting.put(fin, PutAccountingMapping(s.w.legalEntity, "FUEL", "6600", "1576", "1600")).revision, "gleiche Werte ändern nichts")
        assertEquals(2L, bf.svc.accounting.put(fin, PutAccountingMapping(s.w.legalEntity, "FUEL", "6610", "1576", "1600")).revision)
        assertEquals("6610", bf.svc.accounting.get(fin, s.w.legalEntity, "FUEL")!!.expenseAccount)
        assertEquals(ErrorCode.VALIDATION_FAILED, bf.problem { bf.svc.accounting.put(fin, PutAccountingMapping(s.w.legalEntity, "fuel", "6600", "1576", "1600")) }.code)
        assertEquals(ErrorCode.VALIDATION_FAILED, bf.problem { bf.svc.accounting.put(fin, PutAccountingMapping(s.w.legalEntity, "FUEL", "1600", "1576", "1600")) }.code)
        assertEquals(ErrorCode.NOT_FOUND, bf.problem { bf.svc.accounting.put(fin, PutAccountingMapping(other.legalEntity, "FUEL", "6600", "1576", "1600")) }.code, "Rechtsträger eines anderen Mandanten")
        assertEquals(ErrorCode.FORBIDDEN, bf.problem { bf.svc.accounting.put(bf.driverCtx(s.w).withScopes("expenses:approve"), PutAccountingMapping(s.w.legalEntity, "FUEL", "6600", "1576", "1600")) }.code)
        assertEquals(2, bf.fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='finance.accounting_mapping.changed'", s.w.tenant.value))
    }
}

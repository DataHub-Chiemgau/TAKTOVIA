package de.taktovia.documentsfinance

import de.taktovia.documentsfinance.api.AllocateCost
import de.taktovia.documentsfinance.api.AllocationCalculator
import de.taktovia.documentsfinance.api.AllocationLineInput
import de.taktovia.documentsfinance.api.AllocationMode
import de.taktovia.documentsfinance.api.AssignCosts
import de.taktovia.documentsfinance.api.CorrectExpense
import de.taktovia.documentsfinance.api.DocumentKind
import de.taktovia.documentsfinance.api.ExpenseFields
import de.taktovia.documentsfinance.api.ExpenseStates
import de.taktovia.documentsfinance.api.PostExpense
import de.taktovia.documentsfinance.api.PutAccountingMapping
import de.taktovia.documentsfinance.api.ReleaseBlocker
import de.taktovia.documentsfinance.api.SetSplit
import de.taktovia.platform.context.Actor
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.storage.InMemoryObjectStore
import de.taktovia.platform.tenant.TenantId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M4-35 (DOC-011, E2E-072, PH-AT-013, COST-02): Belegaufteilung auf mehrere Kostenobjekte, centgenau. Echte Datenbank unter der Runtime-Rolle: Largest Remainder (Drittel, Prozent, Betrag), Korrektur über den
 * Absolutbetrag, Summe ungleich Basis, Rechtsträger- und Mandantengrenzen, geschlossene Periode, genau eine aktive Revision bei Parallelität, keine zusätzliche Quellkostenbuchung, Beleg-Split vor dem Posting.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AllocationServiceTest {
    private lateinit var bf: BelegFixture

    @BeforeAll fun start() { bf = BelegFixture(InMemoryObjectStore()) }
    @AfterAll fun stop() { bf.close() }

    private fun person(t: TenantId, roles: Set<String>, scopes: Set<String>, type: ActorType = ActorType.HUMAN) =
        RequestContext(Actor(type, "https://idp.test/realms/m4", UUID.randomUUID().toString(), "sid", true), t, roles, scopes, emptySet(), UUID.randomUUID())

    private fun problem(f: () -> Unit): ProblemException = bf.problem(f)

    private inner class World {
        val w = bf.fx.world()
        val fin = person(w.tenant, setOf("finance"), setOf("receipts:read", "receipts:write", "expenses:review", "expenses:approve", "costs:read", "costs:allocate"))
        val approver = person(w.tenant, setOf("finance"), setOf("receipts:read", "receipts:write", "expenses:approve", "costs:read"))
        val reviewer = person(w.tenant, setOf("finance"), setOf("receipts:read", "receipts:write", "expenses:review"))
        val capturer = bf.driverCtx(w)
        val period = UUID.randomUUID()
        val vehicle = UUID.randomUUID()
        init {
            bf.fx.exec("INSERT INTO taktovia.fiscal_period(tenant_id,id,legal_entity_id,starts_on,ends_on) VALUES (?,?,?,DATE '2026-01-01',DATE '2027-01-01')", w.tenant.value, period, w.legalEntity)
            bf.fx.exec("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (?,?,'org',CAST('{}' AS jsonb))", w.tenant.value, vehicle)
            bf.svc.accounting.put(fin, PutAccountingMapping(w.legalEntity, "FUEL", "6600", "1576", "1600"))
            bf.svc.accounting.put(fin, PutAccountingMapping(w.legalEntity, "RECEIPT", "6800", "1576", "1600"))
        }

        /** Kostenstellen, absteigend sortiert nach Kennung wie erzeugt: a < b < c (stabile Reihenfolge bei Gleichstand). */
        fun centers(n: Int, entity: UUID = w.legalEntity, kind: String = "COST_CENTER"): List<UUID> = (1..n).map { i ->
            val id = UUID.fromString("00000000-0000-4000-8000-%012d".format(i + (UUID.randomUUID().leastSignificantBits and 0xFFFF)))
            bf.fx.exec("INSERT INTO taktovia.cost_object(tenant_id,id,legal_entity_id,kind,business_ref) VALUES (?,?,?,?,?)", w.tenant.value, id, entity, kind, id.toString()); id
        }.sorted()

        /** Gebuchtes Kostenereignis eines Belegs mit einem Fahrzeug-Kostenobjekt: brutto = Kostenbasis (keine Steuer); die Aufteilung folgt über die API. */
        fun postedEvent(gross: Long = 10001): UUID {
            val d = bf.upload(w, fin, bytes = bf.uniqueJpeg(), kind = DocumentKind.RECEIPT)
            var rev = d.revision
            rev = bf.svc.expenses.correct(fin, d.documentId, rev, CorrectExpense("erfasst", mapOf(ExpenseFields.AMOUNT_MINOR to gross, ExpenseFields.CURRENCY to "EUR", ExpenseFields.DOCUMENT_DATE to "2026-06-15", ExpenseFields.MERCHANT to "Händler ${UUID.randomUUID()}"))).document.revision
            bf.svc.costs.assign(fin, d.documentId, rev, AssignCosts(vehicleIds = setOf(vehicle)))
            bf.fx.exec("UPDATE taktovia.expense_document SET state='APPROVED', approved_by='user:freigeber' WHERE id=?", d.documentId)
            return bf.svc.costEvents.postExpense(fin, d.documentId, PostExpense("RECEIPT")).event.id
        }

        fun lines(targets: List<UUID>, amounts: List<Long>? = null, percents: List<String>? = null, quantities: List<String>? = null) = targets.mapIndexed { i, t ->
            AllocationLineInput(t, null, amounts?.get(i), if (amounts != null) "EUR" else null, if (amounts != null) 2 else null, percents?.get(i)?.let(::BigDecimal), quantities?.get(i)?.let(::BigDecimal), null)
        }
        fun allocate(event: UUID, expected: Long, lines: List<AllocationLineInput>, by: RequestContext = fin) = bf.svc.allocations.allocate(by, event, expected, AllocateCost("test-key-v1", "Aufteilung nach Testschlüssel", lines))
        fun events() = bf.fx.count("SELECT count(*) FROM taktovia.economic_event WHERE tenant_id=?", w.tenant.value)
        fun journals() = bf.fx.count("SELECT count(*) FROM taktovia.journal WHERE tenant_id=?", w.tenant.value)
        fun journalLines() = bf.fx.count("SELECT count(*) FROM taktovia.journal_line WHERE tenant_id=?", w.tenant.value)
        fun activations(e: UUID) = bf.fx.count("SELECT count(*) FROM taktovia.allocation_activation WHERE event_id=?", e)
        fun sets(e: UUID) = bf.fx.count("SELECT count(*) FROM taktovia.allocation_set WHERE event_id=?", e)
    }

    @Test
    fun rechenkern_largest_remainder_ist_exakt_und_deterministisch() {
        val ids = listOf(UUID(0, 1), UUID(0, 2), UUID(0, 3))
        val thirds = ids.map { it to BigDecimal.ONE }
        assertEquals(listOf(3334L, 3333L, 3333L), AllocationCalculator.distribute(10000, thirds))
        assertEquals(listOf(3334L, 3334L, 3333L), AllocationCalculator.distribute(10001, thirds))
        // Gleichstand: die kleinere Kennung zuerst, unabhängig von der Eingabereihenfolge
        val shuffled = AllocationCalculator.distribute(10000, listOf(ids[2], ids[0], ids[1]).map { it to BigDecimal.ONE })
        assertEquals(listOf(3333L, 3334L, 3333L), shuffled, "Eingabe c,a,b: a erhält den Restcent")
        // größter Dezimalrest zuerst: 70/20/10 von 99 -> 69,3 / 19,8 / 9,9 -> 69 + 20 + 10
        assertEquals(listOf(69L, 20L, 10L), AllocationCalculator.distribute(99, listOf(ids[0] to BigDecimal("70"), ids[1] to BigDecimal("20"), ids[2] to BigDecimal("10"))))
        // negativ über den Absolutbetrag, Summe bleibt exakt
        assertEquals(listOf(-3334L, -3333L, -3333L), AllocationCalculator.distribute(-10000, thirds))
        for (basis in listOf(1L, 2L, 7L, 100L, 10001L, 9_007_199_254_740_991L)) assertEquals(basis, AllocationCalculator.distribute(basis, thirds).sum())
        assertEquals(listOf(1L, 0L, 0L), AllocationCalculator.distribute(1, thirds))
    }

    @Test
    fun drittelsplit_100_01_eur_ergibt_33_34_33_34_33_33() {
        val w = World(); val (a, b, c) = w.centers(3); val e = w.postedEvent(10001)
        val r = w.allocate(e, 0, w.lines(listOf(a, b, c), quantities = listOf("1", "1", "1")))
        assertEquals(listOf(3334L, 3334L, 3333L), r.lines.map { it.amountMinor }, "33,34 / 33,34 / 33,33 EUR")
        assertEquals(10001L, r.lines.sumOf { it.amountMinor }); assertEquals(10001L, r.basisMinor); assertEquals(1L, r.revision); assertEquals("POSTED", r.state); assertTrue(r.active)
        assertEquals(AllocationMode.WEIGHT, r.mode); assertEquals("LRM-1", r.algorithmVersion)
        // ein erneuter Lauf (neue Revision mit demselben Schlüssel) ist identisch
        val again = w.allocate(e, 1, w.lines(listOf(a, b, c), quantities = listOf("1", "1", "1")))
        assertEquals(r.lines.map { it.costObjectId to it.amountMinor }, again.lines.map { it.costObjectId to it.amountMinor }); assertEquals(2L, again.revision)
        // 10.000 Minor Units im Drittelsplit: 3.334 / 3.333 / 3.333
        val e2 = w.postedEvent(10000)
        assertEquals(listOf(3334L, 3333L, 3333L), w.allocate(e2, 0, w.lines(listOf(a, b, c), quantities = listOf("1", "1", "1"))).lines.map { it.amountMinor })
        assertEquals(1, w.activations(e)); assertEquals(2, w.sets(e))
        assertEquals(3, bf.fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='finance.allocation.posted'", w.w.tenant.value), "je Revision ein Auditeintrag (zwei Revisionen, zweites Ereignis)")
    }

    @Test
    fun prozent_und_betrag_ergeben_gleiche_zeilen_summe_100_pflicht() {
        val w = World(); val (a, b, c) = w.centers(3); val e = w.postedEvent(10001)
        val byPercent = w.allocate(e, 0, w.lines(listOf(a, b, c), percents = listOf("50", "30", "20")))
        assertEquals(listOf(5001L, 3000L, 2000L), byPercent.lines.map { it.amountMinor }.let { l -> l }, "10001 * 50 % = 5000,5 (Rest an den größten Rest), 3000,3, 2000,2")
        val e2 = w.postedEvent(10001)
        val byAmount = w.allocate(e2, 0, w.lines(listOf(a, b, c), amounts = byPercent.lines.map { it.amountMinor }))
        assertEquals(byPercent.lines.map { it.costObjectId to it.amountMinor }, byAmount.lines.map { it.costObjectId to it.amountMinor }, "prozentual und betragsmäßig führen zu denselben Zeilen")
        assertEquals(AllocationMode.AMOUNT, byAmount.mode); assertEquals(AllocationMode.PERCENT, byPercent.mode)
        // Prozente ungleich 100: 422 mit eigenem Code, nichts angelegt
        val e3 = w.postedEvent(10001)
        for (p in listOf(listOf("50", "30", "19.99999999"), listOf("50", "30", "20.00000001"), listOf("40", "30", "20"))) {
            assertEquals(ErrorCode.ALLOCATION_SUM_MISMATCH, problem { w.allocate(e3, 0, w.lines(listOf(a, b, c), percents = p)) }.code, p.toString())
        }
        assertEquals(0, w.sets(e3)); assertEquals(0, w.activations(e3))
        // Mischung von Betrags- und Prozentzeilen wird bis zur Klärung (Offener Punkt 18) abgewiesen; ebenso doppelte Kostenobjekte
        val mixed = listOf(AllocationLineInput(a, amountMinor = 5000, amountCurrency = "EUR", amountExponent = 2), AllocationLineInput(b, percent = BigDecimal("50")))
        assertEquals(ErrorCode.VALIDATION_FAILED, problem { w.allocate(e3, 0, mixed) }.code)
        assertEquals(ErrorCode.VALIDATION_FAILED, problem { w.allocate(e3, 0, w.lines(listOf(a, a), percents = listOf("50", "50"))) }.code)
        assertEquals(0, w.sets(e3))
    }

    @Test
    fun negative_korrektur_hebt_ursprungsbetraege_exakt_auf_10000_minor() {
        val w = World(); val (a, b, c) = w.centers(3); val orig = w.postedEvent(10000)
        val first = w.allocate(orig, 0, w.lines(listOf(a, b, c), quantities = listOf("1", "1", "1")))
        // Gegenereignis (Korrektur, -100,00 EUR) als gebuchtes Ereignis: die Aufteilung über den Absolutbetrag ergibt -3334 / -3333 / -3333
        val reversal = reversalEvent(w, orig, -10000)
        val corr = w.allocate(reversal, 0, w.lines(listOf(a, b, c), quantities = listOf("1", "1", "1")))
        assertEquals(listOf(-3334L, -3333L, -3333L), corr.lines.map { it.amountMinor }); assertEquals(-10000L, corr.basisMinor)
        assertEquals(listOf(0L, 0L, 0L), first.lines.indices.map { first.lines[it].amountMinor + corr.lines[it].amountMinor }, "die Korrektur hebt die ursprünglichen Beträge exakt auf")
        // Gesamtansicht: nichts bleibt übrig
        val eff = bf.svc.allocations.effectiveCost(w.fin, w.w.legalEntity, "EUR")
        assertEquals(0L, eff.totalMinor); assertEquals(setOf(0L), eff.byCostObject.filterKeys { it in listOf(a, b, c) }.values.toSet())
        // Vorzeichen der Beträge muss zur Basis passen
        assertEquals(ErrorCode.VALIDATION_FAILED, problem { w.allocate(w.postedEvent(10000), 0, w.lines(listOf(a, b), amounts = listOf(11000, -1000))) }.code)
    }

    /** Gebuchtes Gegenereignis (negative Beträge) mit ausgeglichenem Journal in der offenen Periode, direkt in der Datenbank angelegt (der Befehl folgt mit WP-M4-36). */
    private fun reversalEvent(w: World, original: UUID, gross: Long): UUID {
        val ev = UUID.randomUUID(); val jn = UUID.randomUUID(); val t = w.w.tenant.value; val le = w.w.legalEntity
        bf.fx.exec("INSERT INTO taktovia.economic_event(tenant_id,id,legal_entity_id,source_system,source_ref,event_type,scenario,category,currency,net_minor,tax_minor,gross_minor,recoverable_tax_minor,tax_rule_revision,occurred_at,booked_at,reverses_id) " +
            "VALUES (?,?,?,'EXPENSE_REVERSAL',?, 'EXPENSE_COST','ACTUAL','RECEIPT','EUR',?,0,?,0,'T',now(),now(),?)", t, ev, le, ev.toString(), gross, gross, original)
        bf.fx.exec("INSERT INTO taktovia.journal(tenant_id,id,event_id,legal_entity_id,currency,period_id,accounting_date,state) VALUES (?,?,?,?,'EUR',?,DATE '2026-07-01','DRAFT')", t, jn, ev, le, w.period)
        bf.fx.exec("INSERT INTO taktovia.journal_line(tenant_id,id,journal_id,account_code,side,amount_minor) VALUES (?,?,?,'6800','C',?)", t, UUID.randomUUID(), jn, -gross)
        bf.fx.exec("INSERT INTO taktovia.journal_line(tenant_id,id,journal_id,account_code,side,amount_minor) VALUES (?,?,?,'1600','D',?)", t, UUID.randomUUID(), jn, -gross)
        bf.fx.exec("UPDATE taktovia.journal SET state='POSTED', posted_by='user:test' WHERE id=?", jn)
        return ev
    }

    @Test
    fun summe_ungleich_basis_blockiert_freigabe() {
        val w = World(); val (a, b) = w.centers(2); val e = w.postedEvent(10001)
        // um einen Cent daneben (zu wenig und zu viel): 422 mit eigenem Code, keine Revision
        for (amounts in listOf(listOf(5000L, 5000L), listOf(5001L, 5001L), listOf(5001L, 4999L))) {
            val p = problem { w.allocate(e, 0, w.lines(listOf(a, b), amounts = amounts)) }
            assertEquals(ErrorCode.ALLOCATION_SUM_MISMATCH, p.code, amounts.toString())
        }
        assertEquals(0, w.sets(e)); assertEquals(0, w.activations(e))
        assertEquals(10001L, w.allocate(e, 0, w.lines(listOf(a, b), amounts = listOf(5000L, 5001L))).lines.sumOf { it.amountMinor })
    }

    private class Doc(val documentId: UUID, var revision: Long)
    private fun rev(w: World, d: Doc): Long = bf.svc.expenses.get(w.fin, d.documentId).document.revision

    private fun docWithTwoCenters(w: World, a: UUID, b: UUID, amount: Long): Doc {
        val v = bf.upload(w.w, w.capturer, bytes = bf.uniqueJpeg(), kind = DocumentKind.RECEIPT)
        var rev = v.revision
        rev = bf.svc.expenses.correct(w.fin, v.documentId, rev, CorrectExpense("erfasst", mapOf(ExpenseFields.AMOUNT_MINOR to amount, ExpenseFields.CURRENCY to "EUR", ExpenseFields.DOCUMENT_DATE to "2026-06-15", ExpenseFields.MERCHANT to "Händler ${UUID.randomUUID()}"))).document.revision
        rev = bf.svc.costs.assign(w.fin, v.documentId, rev, AssignCosts(costObjectIds = setOf(a, b))).document.revision
        return Doc(v.documentId, rev)
    }

    @Test
    fun beleg_split_blockiert_bei_luecke_oder_differenz_und_wird_beim_posting_zur_ersten_umlagerevision() {
        val w = World(); val (a, b) = w.centers(2); val d = docWithTwoCenters(w, a, b, 10001)
        // 1) kein Plan
        assertEquals(ErrorCode.SPLIT_INCOMPLETE, problem { bf.svc.approvals.submit(w.capturer, d.documentId, rev(w, d)); bf.svc.approvals.review(w.reviewer, d.documentId, rev(w, d), de.taktovia.documentsfinance.api.ApprovalDecision.APPROVED, "Sachlich geprüft") }.code)
        // zurück in die Erfassung (Ablehnung in der Sachprüfung), danach Plan mit Differenz von einem Cent
        bf.svc.approvals.review(w.reviewer, d.documentId, rev(w, d), de.taktovia.documentsfinance.api.ApprovalDecision.REJECTED, "Aufteilung fehlt")
        val bad = bf.svc.splits.set(w.fin, d.documentId, rev(w, d), SetSplit("test-key-v1", "Aufteilung", w.lines(listOf(a, b), amounts = listOf(5000L, 5000L))))
        assertTrue(ReleaseBlocker.SPLIT_INCOMPLETE in bad.blockers); assertTrue(bad.splitProblem!!.contains("10000"), bad.splitProblem)
        bf.svc.approvals.submit(w.capturer, d.documentId, rev(w, d))
        assertEquals(ErrorCode.SPLIT_INCOMPLETE, problem { bf.svc.approvals.review(w.reviewer, d.documentId, rev(w, d), de.taktovia.documentsfinance.api.ApprovalDecision.APPROVED, "Sachlich geprüft") }.code, "Differenz")
        assertEquals(0, w.events()); assertEquals(0, w.journals())
        bf.svc.approvals.review(w.reviewer, d.documentId, rev(w, d), de.taktovia.documentsfinance.api.ApprovalDecision.REJECTED, "Differenz im Split")
        // 2) centgenauer Plan, erst jetzt freigabefähig; Posting erzeugt Ereignis, Journal und die erste Umlagerevision atomar
        val ok = bf.svc.splits.set(w.fin, d.documentId, rev(w, d), SetSplit("test-key-v1", "Aufteilung", w.lines(listOf(a, b), amounts = listOf(5000L, 5001L))))
        assertTrue(ReleaseBlocker.SPLIT_INCOMPLETE !in ok.blockers); assertEquals(null, ok.splitProblem)
        bf.svc.approvals.submit(w.capturer, d.documentId, rev(w, d)); bf.svc.approvals.review(w.reviewer, d.documentId, rev(w, d), de.taktovia.documentsfinance.api.ApprovalDecision.APPROVED, "Sachlich geprüft")
        val out = bf.svc.approvals.approve(w.approver, d.documentId, rev(w, d), "Freigegeben")
        assertEquals(ExpenseStates.POSTED, out.state); assertEquals(1, w.events()); assertEquals(1, w.journals())
        val active = bf.svc.allocations.active(w.fin, out.postingId!!)!!
        assertEquals(1L, active.revision); assertEquals(mapOf(a to 5000L, b to 5001L), active.lines.associate { it.costObjectId to it.amountMinor })
        assertEquals(10001L, active.lines.sumOf { it.amountMinor }); assertEquals(w.w.legalEntity, active.legalEntityId)
        // Allokation erzeugt kein zusätzliches Journal und keine zweite Quellkostenbuchung; Gesamtansicht: 100,01 EUR, nicht das Doppelte
        val eff = bf.svc.allocations.effectiveCost(w.fin, w.w.legalEntity, "EUR")
        assertEquals(10001L, eff.totalMinor); assertEquals(10001L, eff.byCostObject.values.sum() + eff.unassignedMinor)
    }

    @Test
    fun split_nur_fuer_zugeordnete_kostenobjekte_und_prozentplan() {
        val w = World(); val (a, b, c) = w.centers(3); val d = docWithTwoCenters(w, a, b, 10001)
        assertEquals(ErrorCode.VALIDATION_FAILED, problem { bf.svc.splits.set(w.fin, d.documentId, rev(w, d), SetSplit("k", "Aufteilung", w.lines(listOf(a, c), percents = listOf("50", "50")))) }.code, "c ist nicht zugeordnet")
        val v = bf.svc.splits.set(w.fin, d.documentId, rev(w, d), SetSplit("k", "Aufteilung", w.lines(listOf(a, b), percents = listOf("50", "50"))))
        assertEquals(AllocationMode.PERCENT, v.split!!.mode); assertTrue(ReleaseBlocker.SPLIT_INCOMPLETE !in v.blockers)
        // eine Änderung der Kostenzuordnung verwirft den Plan (Kaskade): der Beleg ist wieder nicht freigabefähig
        val re = bf.svc.costs.assign(w.fin, d.documentId, rev(w, d), AssignCosts(costObjectIds = setOf(a, b, c)))
        assertEquals(null, re.split); assertTrue(ReleaseBlocker.SPLIT_INCOMPLETE in re.blockers)
        assertEquals(ErrorCode.REVISION_MISMATCH, problem { bf.svc.splits.set(w.fin, d.documentId, 1, SetSplit("k", "Aufteilung", w.lines(listOf(a, b, c), percents = listOf("50", "25", "25")))) }.code)
    }

    @Test
    fun parallele_allokationsrevisionen_genau_eine_aktiv() {
        val w = World(); val (a, b) = w.centers(2)
        // ohne bekannte Revision (0): genau eine gewinnt, die übrigen sehen die aktive Aufteilung (409 ALREADY_ALLOCATED)
        repeat(3) {
            val e = w.postedEvent(10001); val pool = Executors.newFixedThreadPool(6); val go = CountDownLatch(1)
            val fs = (1..6).map { i -> pool.submit<Result<Long>> { go.await(); runCatching { w.allocate(e, 0, w.lines(listOf(a, b), amounts = listOf(i * 100L, 10001L - i * 100)))?.revision ?: 0 } } }
            go.countDown(); val out = fs.map { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
            assertEquals(1, out.count { it.isSuccess }, out.toString())
            assertTrue(out.filter { it.isFailure }.all { (it.exceptionOrNull() as ProblemException).code == ErrorCode.ALREADY_ALLOCATED }, out.toString())
            assertEquals(1, w.activations(e)); assertEquals(1, w.sets(e))
        }
        // mit derselben aktiven Revision (1): genau eine neue Fassung (Revision 2), die übrigen 412; es gibt immer genau eine aktive
        repeat(3) {
            val e = w.postedEvent(10001); w.allocate(e, 0, w.lines(listOf(a, b), amounts = listOf(5000, 5001)))
            val pool = Executors.newFixedThreadPool(6); val go = CountDownLatch(1)
            val fs = (1..6).map { i -> pool.submit<Result<Long>> { go.await(); runCatching { w.allocate(e, 1, w.lines(listOf(a, b), amounts = listOf(i * 100L, 10001L - i * 100))).revision } } }
            go.countDown(); val out = fs.map { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
            assertEquals(1, out.count { it.isSuccess }, out.toString())
            assertTrue(out.filter { it.isFailure }.all { (it.exceptionOrNull() as ProblemException).code == ErrorCode.REVISION_MISMATCH }, out.toString())
            assertEquals(1, w.activations(e)); assertEquals(2, w.sets(e)); assertEquals(2L, bf.svc.allocations.active(w.fin, e)!!.revision)
            assertEquals(listOf(1L, 2L), bf.svc.allocations.revisions(w.fin, e).map { it.revision }); assertEquals(listOf(false, true), bf.svc.allocations.revisions(w.fin, e).map { it.active })
        }
    }

    @Test
    fun geschlossene_periode_409_und_gebuchte_allokation_409() {
        val w = World(); val (a, b) = w.centers(2); val e = w.postedEvent(10001)
        w.allocate(e, 0, w.lines(listOf(a, b), amounts = listOf(5000, 5001)))
        // bereits aufgeteilt, aber ohne Kenntnis der Revision: 409 ALREADY_ALLOCATED (nicht überschrieben); falsche Revision 412
        assertEquals(ErrorCode.ALREADY_ALLOCATED, problem { w.allocate(e, 0, w.lines(listOf(a, b), amounts = listOf(4000, 6001))) }.code)
        assertEquals(ErrorCode.REVISION_MISMATCH, problem { w.allocate(e, 7, w.lines(listOf(a, b), amounts = listOf(4000, 6001))) }.code)
        assertEquals(listOf(5000L, 5001L), bf.svc.allocations.active(w.fin, e)!!.lines.sortedBy { it.costObjectId }.map { it.amountMinor }.let { if (a < b) it else it.reversed() })
        // geschlossene Periode: 409 PERIOD_CLOSED, keine neue Revision
        bf.fx.exec("UPDATE taktovia.fiscal_period SET closed_at = clock_timestamp() WHERE id=?", w.period)
        assertEquals(ErrorCode.PERIOD_CLOSED, problem { w.allocate(e, 1, w.lines(listOf(a, b), amounts = listOf(4000, 6001))) }.code)
        assertEquals(1, w.sets(e))
        // die gebuchte Umlage ist per SQL auch für die Runtime-Rolle final (siehe db/tests/test_allocation.py); hier: die Zeilen sind unverändert
        assertEquals(10001L, bf.svc.allocations.active(w.fin, e)!!.lines.sumOf { it.amountMinor })
    }

    @Test
    fun allokation_parallel_zu_periodenabschluss_genau_eine_reihenfolge() {
        var allocatedFirst = 0; var closedFirst = 0
        repeat(10) {
            val w = World(); val (a, b) = w.centers(2); val e = w.postedEvent(10001); val e2 = w.postedEvent(10001)
            val pool = Executors.newFixedThreadPool(2); val go = CountDownLatch(1)
            val alloc = pool.submit<Result<Any>> { go.await(); runCatching { w.allocate(e, 0, w.lines(listOf(a, b), amounts = listOf(5000, 5001))) } }
            val close = pool.submit { go.await(); bf.fx.exec("UPDATE taktovia.fiscal_period SET closed_at = clock_timestamp() WHERE id=?", w.period) }
            go.countDown(); close.get(60, TimeUnit.SECONDS); val r = alloc.get(60, TimeUnit.SECONDS); pool.shutdown()
            if (r.isSuccess) { allocatedFirst++; assertEquals(1, w.activations(e)); assertEquals(1, w.sets(e)) }
            else { closedFirst++; assertEquals(ErrorCode.PERIOD_CLOSED, (r.exceptionOrNull() as ProblemException).code, r.toString()); assertEquals(0, w.activations(e)); assertEquals(0, w.sets(e)) }
            // nach dem Abschluss ist die Periode für jede weitere Aufteilung gesperrt
            assertEquals(ErrorCode.PERIOD_CLOSED, problem { w.allocate(e2, 0, w.lines(listOf(a, b), amounts = listOf(5000, 5001))) }.code)
            assertEquals(0, w.activations(e2))
        }
        assertEquals(10, allocatedFirst + closedFirst)
    }

    @Test
    fun allokation_erzeugt_kein_zusatzjournal_und_keine_verbindlichkeit() {
        val w = World(); val (a, b, c) = w.centers(3); val e = w.postedEvent(10001)
        val before = Triple(w.events(), w.journals(), w.journalLines())
        val balances = bf.svc.costEvents.accountBalances(w.fin, w.w.legalEntity, "EUR"); val cost = bf.svc.costEvents.actualCostBasis(w.fin, w.w.legalEntity, "EUR")
        w.allocate(e, 0, w.lines(listOf(a, b, c), quantities = listOf("1", "1", "1")))
        assertEquals(before, Triple(w.events(), w.journals(), w.journalLines()), "keine Quellkostenbuchung, kein Journal, keine Verbindlichkeit")
        assertEquals(balances, bf.svc.costEvents.accountBalances(w.fin, w.w.legalEntity, "EUR")); assertEquals(cost, bf.svc.costEvents.actualCostBasis(w.fin, w.w.legalEntity, "EUR"))
        val eff = bf.svc.allocations.effectiveCost(w.fin, w.w.legalEntity, "EUR")
        assertEquals(10001L, eff.totalMinor, "Ereignis plus Aufteilung ergeben 100,01 EUR, nicht 200,02 EUR"); assertEquals(listOf(a, b, c).sumOf { eff.byCostObject.getValue(it) }, 10001L); assertEquals(0L, eff.unassignedMinor)
        assertEquals(1, bf.fx.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND event_type='cost_allocation.posted'", w.w.tenant.value))
        // Ereignis gegen das Schema
        val schema = com.networknt.schema.SchemaRegistry.withDefaultDialect(com.networknt.schema.dialect.Dialects.getDraft202012())
            .getSchema(Files.readString(Path.of(System.getProperty("taktovia.repoRoot")).resolve("api/events/cost_allocation.posted.v1.schema.json")))
        bf.fx.strings("SELECT payload::text FROM taktovia.outbox WHERE tenant_id=? AND event_type='cost_allocation.posted'", w.w.tenant.value).also { assertEquals(1, it.size) }
            .forEach { assertEquals(emptyList<Any>(), schema.validate(it, com.networknt.schema.InputFormat.JSON).toList().map { m -> m.message }, it) }
        // Audit mit Akteur, Regelrevision und Begründung (Kap. 05 §5.5)
        assertEquals(1, bf.fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='finance.allocation.posted' AND redacted_changes->>'policy_revision'='test-key-v1' AND redacted_changes->>'reason'='Aufteilung nach Testschlüssel' AND actor_ref=?", w.w.tenant.value, "user:" + w.fin.actor.subject))
    }

    @Test
    fun fremder_rechtstraeger_mandant_inaktiv_und_berechtigung() {
        val w = World(); val (a, b) = w.centers(2); val e = w.postedEvent(10001)
        val otherEntity = UUID.randomUUID(); bf.fx.exec("INSERT INTO taktovia.legal_entity(tenant_id,id,name,country) VALUES (?,?,'Betreiber Y','DE')", w.w.tenant.value, otherEntity)
        val foreignEntity = w.centers(1, otherEntity).single()
        assertEquals(ErrorCode.VALIDATION_FAILED, problem { w.allocate(e, 0, w.lines(listOf(a, foreignEntity), amounts = listOf(5000, 5001))) }.code, "Kostenobjekt eines anderen Rechtsträgers (E2E-072, Referenzfall X/Y nur als Negativfall)")
        val other = World(); val foreignTenant = other.centers(1).single()
        assertEquals(ErrorCode.NOT_FOUND, problem { w.allocate(e, 0, w.lines(listOf(a, foreignTenant), amounts = listOf(5000, 5001))) }.code, "Kostenobjekt eines anderen Mandanten: nicht auffindbar")
        assertEquals(ErrorCode.NOT_FOUND, problem { other.allocate(e, 0, other.lines(other.centers(2), amounts = listOf(5000, 5001))) }.code, "Ereignis eines anderen Mandanten")
        val unknown = problem { w.allocate(UUID.randomUUID(), 0, w.lines(listOf(a, b), amounts = listOf(5000, 5001))) }; assertEquals(ErrorCode.NOT_FOUND, unknown.code)
        bf.fx.exec("UPDATE taktovia.cost_object SET active=false WHERE id=?", b)
        assertEquals(ErrorCode.VALIDATION_FAILED, problem { w.allocate(e, 0, w.lines(listOf(a, b), amounts = listOf(5000, 5001))) }.code, "inaktiv")
        val unit = w.centers(1, kind = "SERVICE").single()
        assertEquals(ErrorCode.VALIDATION_FAILED, problem { w.allocate(e, 0, w.lines(listOf(a, unit), amounts = listOf(5000, 5001))) }.code, "nur Kostenstelle, Fahrzeug, Projekt")
        assertEquals(0, w.sets(e)); assertEquals(0, w.activations(e))
        // Währung/Exponent der Zeile wie das Ereignis
        val (c, d) = w.centers(2)
        assertEquals(ErrorCode.VALIDATION_FAILED, problem { w.allocate(e, 0, listOf(AllocationLineInput(c, amountMinor = 5000, amountCurrency = "USD", amountExponent = 2), AllocationLineInput(d, amountMinor = 5001, amountCurrency = "USD", amountExponent = 2))) }.code)
        // ohne Scope, ohne Finanzrolle, Fahrer: 403
        val noScope = person(w.w.tenant, setOf("finance"), setOf("costs:read")); val driver = person(w.w.tenant, setOf("driver"), setOf("costs:allocate"))
        for (c2 in listOf(noScope, driver)) assertEquals(ErrorCode.FORBIDDEN, problem { w.allocate(e, 0, w.lines(listOf(c, d), amounts = listOf(5000, 5001)), by = c2) }.code)
        // Projekt als Ziel ist zulässig
        val project = w.centers(1, kind = "PROJECT").single()
        assertEquals(2, w.allocate(e, 0, w.lines(listOf(c, project), amounts = listOf(5000, 5001))).lines.size)
    }

    @Test
    fun kein_gleitkommatyp_fuer_geld() {
        val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first { Files.exists(it.resolve("api/finance.yaml")) }
        val money = listOf("api/CostAllocations.kt", "internal/JdbcCostAllocations.kt", "api/CostEvents.kt", "internal/JdbcCostEvents.kt", "api/ExpenseApprovals.kt", "internal/JdbcExpenseApprovals.kt", "internal/JdbcAccountingMappings.kt")
            .map { root.resolve("backend/modules/documents-finance/src/main/kotlin/de/taktovia/documentsfinance/$it") }
        val forbidden = Regex("""\b(Double|Float)\b|\.toDouble\(|\.toFloat\(|\bdoubleValue\(|\bfloatValue\(|\bMath\.(floor|ceil|round|pow)\(|[0-9]\.[0-9]+[fFdD]\b""")
        money.forEach { f ->
            val code = Files.readAllLines(f).filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") || it.trimStart().startsWith("/**") }.joinToString("\n")
            assertEquals(null, forbidden.find(code)?.value, "Gleitkomma in ${f.fileName}")
        }
        // API: die Allokationsschemas führen kein `type: number` (Geld ist SignedMoney, Prozent und Mengen sind Dezimalstrings)
        val yaml = Files.readString(root.resolve("api/finance.yaml"))
        val section = yaml.substring(yaml.indexOf("    CostAllocationLine:"), yaml.indexOf("    ApprovalRequest:"))
        assertTrue(!Regex("type: (number|float|double)").containsMatchIn(section), "Allokationsschemas enthalten einen Gleitkommatyp")
        // Datenbank: Geld bigint, Gewicht und Prozent numeric
        assertEquals(listOf("bigint"), bf.fx.strings("SELECT data_type FROM information_schema.columns WHERE table_schema='taktovia' AND table_name='allocation_line' AND column_name='amount_minor'"))
        assertEquals(listOf("numeric"), bf.fx.strings("SELECT data_type FROM information_schema.columns WHERE table_schema='taktovia' AND table_name='allocation_line' AND column_name='weight'"))
    }

}

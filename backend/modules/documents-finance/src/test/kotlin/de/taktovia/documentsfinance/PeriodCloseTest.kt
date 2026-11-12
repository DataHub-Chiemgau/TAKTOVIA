package de.taktovia.documentsfinance

import de.taktovia.documentsfinance.api.AllocateCost
import de.taktovia.documentsfinance.api.AllocationLineInput
import de.taktovia.documentsfinance.api.CreateFiscalPeriod
import de.taktovia.documentsfinance.api.FiscalPeriods
import de.taktovia.documentsfinance.api.ReverseCostEvent
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
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M4-36 (PH-AT-015, E2E-073, COST-05): Fiskalperiode, Periodenabschluss und Gegenereignis. Echte Datenbank unter der Runtime-Rolle: Überlappungsverbot, Abschluss mit eigenem Recht, Audit und ohne Wiederöffnung,
 * Posting und Aufteilung in eine geschlossene Periode (409 `PERIOD_CLOSED`), Gegenereignis in der nächsten offenen Periode mit Ursprungsbezug und negierter Aufteilung, Dublette, Überschreitung, Parallelität
 * (Abschluss gegen Posting, Aufteilung und Abschluss; Gegenereignisse desselben Ursprungs).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PeriodCloseTest {
    private lateinit var bf: BelegFixture

    @BeforeAll fun start() { bf = BelegFixture(InMemoryObjectStore()) }
    @AfterAll fun stop() { bf.close() }

    private fun problem(f: () -> Unit): ProblemException = bf.problem(f)
    private val sep = LocalDate.of(2026, 9, 1)

    @Test
    fun perioden_ueberlappen_sich_nie_auch_parallel() {
        val w = LedgerWorld(bf)
        w.period(sep, LocalDate.of(2026, 10, 1)); w.period(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1))   // benachbart: erlaubt
        for ((a, b) in listOf(LocalDate.of(2026, 9, 15) to LocalDate.of(2026, 10, 15), sep to LocalDate.of(2026, 10, 1), LocalDate.of(2026, 8, 1) to LocalDate.of(2026, 12, 1))) {
            val p = problem { w.period(a, b) }; assertEquals(ErrorCode.CONFLICT, p.code, "$a-$b"); assertTrue(p.detail!!.contains("überschneidet"))
        }
        assertEquals(ErrorCode.VALIDATION_FAILED, problem { w.period(LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 1)) }.code, "Ende nach Beginn")
        // paralleles Anlegen überlappender Perioden: genau eine entsteht
        val pool = Executors.newFixedThreadPool(4); val go = CountDownLatch(1)
        val fs = (0 until 4).map { i -> pool.submit<Result<Any>> { go.await(); runCatching { w.period(LocalDate.of(2027, 1, 1).plusDays(i * 5L), LocalDate.of(2027, 2, 1).plusDays(i * 5L)) } } }
        go.countDown(); val out = fs.map { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(1, out.count { it.isSuccess }, out.toString()); assertTrue(out.filter { it.isFailure }.all { (it.exceptionOrNull() as ProblemException).code == ErrorCode.CONFLICT })
        // anderer Rechtsträger oder Mandant: nicht auffindbar
        assertEquals(ErrorCode.NOT_FOUND, problem { bf.svc.periods.create(w.fin, CreateFiscalPeriod(UUID.randomUUID(), sep, LocalDate.of(2026, 10, 1))) }.code)
        val other = LedgerWorld(bf)
        assertEquals(ErrorCode.NOT_FOUND, problem { bf.svc.periods.create(w.fin, CreateFiscalPeriod(other.w.legalEntity, sep, LocalDate.of(2026, 10, 1))) }.code)
        assertEquals(listOf(sep), bf.svc.periods.list(w.fin, w.w.legalEntity, 10, null).take(1).map { it.startsOn })
        assertEquals(0, bf.svc.periods.list(other.fin, w.w.legalEntity, 10, null).size, "Perioden eines fremden Mandanten sind unsichtbar")
    }

    @Test
    fun abschluss_braucht_eigenes_recht_steht_im_audit_und_ist_endgueltig() {
        val w = LedgerWorld(bf); val (september, _, _) = w.autumn()
        // eigenes Finanzrecht: Scope und Finanzrolle, Mensch; Verwalten und Lesen genügen nicht
        val denied = listOf(w.person(setOf("finance"), setOf("periods:manage", "periods:read", "costs:read")), w.person(setOf("driver"), setOf("periods:close")), w.person(setOf("finance"), setOf("periods:close"), ActorType.SERVICE), w.person(emptySet(), setOf("periods:close")))
        denied.forEachIndexed { i, c -> assertEquals(ErrorCode.FORBIDDEN, problem { bf.svc.periods.close(c, september.id) }.code, "Fall $i") }
        assertEquals(0, w.audit("finance.period.closed"))
        val closer = w.person(setOf("finance"), setOf("periods:close", "periods:read"))
        val closed = bf.svc.periods.close(closer, september.id)
        assertTrue(closed.closed); assertNotNull(closed.closedAt); assertEquals(closer.actor.subject, closed.closedBy)
        assertEquals(1, bf.fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='finance.period.closed' AND object_ref=? AND actor_ref=?", w.w.tenant.value, "fiscal_period:${september.id}", "user:" + closer.actor.subject))
        // ein zweiter Abschluss ist abgewiesen (kein zweiter Auditeintrag); es gibt keinen Befehl zum Wiederöffnen, und die Datenbank hält die Periode fest
        assertEquals(ErrorCode.PERIOD_CLOSED, problem { bf.svc.periods.close(closer, september.id) }.code); assertEquals(1, w.audit("finance.period.closed"))
        assertEquals(emptyList<String>(), FiscalPeriods::class.java.methods.map { it.name }.filter { it.contains("open", ignoreCase = true) || it.contains("reopen", ignoreCase = true) })
        assertEquals(ErrorCode.NOT_FOUND, problem { bf.svc.periods.close(closer, UUID.randomUUID()) }.code)
        assertTrue(runCatching { bf.fx.exec("UPDATE taktovia.fiscal_period SET closed_at = NULL WHERE id=?", september.id) }.isFailure)
        assertTrue(bf.svc.periods.get(closer, september.id).closed)
    }

    @Test
    fun posting_in_geschlossene_periode_wird_abgewiesen_ohne_spuren() {
        val w = LedgerWorld(bf); val (september, _, _) = w.autumn()
        val doc = w.approvedDocument(date = LocalDate.of(2026, 9, 15))
        bf.svc.periods.close(w.fin, september.id)
        val p = problem { w.post(doc) }
        assertEquals(ErrorCode.PERIOD_CLOSED, p.code); assertEquals(0, w.events()); assertEquals(0, w.journals())
        assertEquals("APPROVED", bf.fx.strings("SELECT state FROM taktovia.expense_document WHERE id=?", doc).single(), "Beleg bleibt freigegeben")
        // eine Aufteilung in die geschlossene Periode: 409 PERIOD_CLOSED (siehe auch AllocationServiceTest)
        val w2 = LedgerWorld(bf); val (s2, _, _) = w2.autumn(); val e = w2.postedEvent(10001); val (a, b) = w2.centers(2)
        bf.svc.periods.close(w2.fin, s2.id)
        val lines = listOf(AllocationLineInput(a, amountMinor = 5000, amountCurrency = "EUR", amountExponent = 2), AllocationLineInput(b, amountMinor = 5001, amountCurrency = "EUR", amountExponent = 2))
        assertEquals(ErrorCode.PERIOD_CLOSED, problem { bf.svc.allocations.allocate(w2.fin, e.event.id, 0, AllocateCost("k", "Aufteilung", lines)) }.code)
    }

    @Test
    fun september_abgeschlossen_gutschrift_im_oktober_journal_unveraendert_korrektur_mit_ursprungsbezug() {
        val w = LedgerWorld(bf); val (september, october, _) = w.autumn()
        val orig = w.postedEvent(10000, date = LocalDate.of(2026, 9, 15)); val journalBefore = bf.svc.costEvents.journal(w.fin, orig.event.id)
        assertEquals(september.id, journalBefore.periodId)
        bf.svc.periods.close(w.fin, september.id)
        val r = bf.svc.corrections.reverse(w.fin, orig.event.id, ReverseCostEvent("Gutschrift Lieferant", creditGrossMinor = 2000, accountingDate = LocalDate.of(2026, 10, 5)))
        // Oktoberkorrektur -20,00 EUR mit Ursprungsbezug; selbst ein unveränderliches Ereignis
        assertEquals(-2000L, r.event.grossMinor); assertEquals(-2000L, r.event.costBasisMinor); assertEquals(orig.event.id, r.event.reversesId); assertEquals(w.w.legalEntity, r.event.legalEntityId)
        assertEquals(october.id, r.journal.periodId); assertEquals(LocalDate.of(2026, 10, 5), r.journal.accountingDate); assertEquals("POSTED", r.journal.state)
        // Gegenbuchung mit getauschter Seite, nur positive Zeilen: Aufwand Haben, Verbindlichkeit Soll
        assertEquals(listOf(Triple("1600", "D", 2000L), Triple("6800", "C", 2000L)), r.journal.lines.map { Triple(it.account, it.side, it.amountMinor) })
        // Septemberjournal und -ereignis unverändert
        assertEquals(journalBefore, bf.svc.costEvents.journal(w.fin, orig.event.id)); assertEquals(orig.event, bf.svc.costEvents.list(w.fin, 10, null).first { it.id == orig.event.id })
        assertEquals(8000L, bf.svc.costEvents.actualCostBasis(w.fin, w.w.legalEntity, "EUR"), "100,00 - 20,00")
        assertEquals(mapOf("1600" to -8000L, "6800" to 8000L), bf.svc.costEvents.accountBalances(w.fin, w.w.legalEntity, "EUR"))
        assertEquals(1, w.audit("finance.cost_event.reversed")); assertEquals(1, bf.fx.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND event_type='cost_event.reversed'", w.w.tenant.value))
        assertEquals(setOf(r.event.id), bf.svc.costEvents.list(w.fin, 10, null).filter { it.reversesId != null }.map { it.id }.toSet())
        // das Gegenereignis ist unveränderlich (Runtime hat weder UPDATE noch DELETE, die Datenbank sperrt ohnehin)
        assertTrue(runCatching { bf.fx.exec("UPDATE taktovia.economic_event SET category='X' WHERE id=?", r.event.id) }.isFailure)
        assertEquals("2026-09-15", bf.fx.strings("SELECT occurred_at::date::text FROM taktovia.economic_event WHERE id=?", orig.event.id).single())
        assertEquals(bf.fx.strings("SELECT service_from::date::text FROM taktovia.economic_event WHERE id=?", r.event.id).single(), "2026-09-15", "Ursprungsleistungszeitraum")
    }

    @Test
    fun naechste_offene_periode_dublette_ueberschreitung_und_regeln() {
        val w = LedgerWorld(bf); val (september, october, november) = w.autumn()
        val orig = w.postedEvent(11900, vat = 1900, date = LocalDate.of(2026, 9, 15))
        val other = w.postedEvent(1000, date = LocalDate.of(2026, 9, 16)).event.id
        bf.svc.periods.close(w.fin, september.id); bf.svc.periods.close(w.fin, october.id)
        // Wunschdatum in einer geschlossenen Periode: die nächste offene Periode (November), Buchungsdatum = deren Beginn
        val r = bf.svc.corrections.reverse(w.fin, orig.event.id, ReverseCostEvent("Gutschrift", creditGrossMinor = 2380, accountingDate = LocalDate.of(2026, 9, 20)))
        assertEquals(november.id, r.journal.periodId); assertEquals(LocalDate.of(2026, 11, 1), r.journal.accountingDate)
        // anteilig nach Largest Remainder: 23,80 brutto = 20,00 netto + 3,80 Steuer (voll abziehbar), Kostenbasis 20,00
        assertEquals(listOf(-2000L, -380L, -2380L, -380L, -2000L), listOf(r.event.netMinor, r.event.taxMinor, r.event.grossMinor, r.event.recoverableTaxMinor, r.event.costBasisMinor))
        assertEquals(listOf(Triple("1576", "C", 380L), Triple("1600", "D", 2380L), Triple("6800", "C", 2000L)), r.journal.lines.map { Triple(it.account, it.side, it.amountMinor) })
        // dieselbe Korrektur noch einmal (gleicher Ursprung und Betrag): Dublette, einmalige Wirkung
        assertEquals(ErrorCode.ALREADY_REVERSED, problem { bf.svc.corrections.reverse(w.fin, orig.event.id, ReverseCostEvent("Gutschrift", creditGrossMinor = 2380, accountingDate = LocalDate.of(2026, 11, 3))) }.code)
        assertEquals(3, w.events()); assertEquals(3, w.journals())   // Ursprung, Gutschrift und der zweite Beleg
        // mehr als der Rest: 422; ein Gegenereignis eines Gegenereignisses: 409
        assertEquals(ErrorCode.VALIDATION_FAILED, problem { bf.svc.corrections.reverse(w.fin, orig.event.id, ReverseCostEvent("Zu viel", creditGrossMinor = 9521, accountingDate = LocalDate.of(2026, 11, 3))) }.code)
        assertEquals(ErrorCode.CONFLICT, problem { bf.svc.corrections.reverse(w.fin, r.event.id, ReverseCostEvent("Rücknahme der Korrektur")) }.code)
        // der Rest (ohne Betrag): exakt die verbleibenden Beträge, danach ist das Ereignis vollständig aufgehoben
        val rest = bf.svc.corrections.reverse(w.fin, orig.event.id, ReverseCostEvent("Restbetrag", accountingDate = LocalDate.of(2026, 11, 3)))
        assertEquals(listOf(-8000L, -1520L, -9520L, -1520L, -8000L), listOf(rest.event.netMinor, rest.event.taxMinor, rest.event.grossMinor, rest.event.recoverableTaxMinor, rest.event.costBasisMinor))
        assertEquals(1000L, bf.svc.costEvents.actualCostBasis(w.fin, w.w.legalEntity, "EUR"), "nur der zweite Beleg (10,00 EUR) bleibt: der erste ist vollständig aufgehoben")
        assertEquals(mapOf("1576" to 0L, "1600" to -1000L, "6800" to 1000L), bf.svc.costEvents.accountBalances(w.fin, w.w.legalEntity, "EUR"))
        assertEquals(ErrorCode.ALREADY_REVERSED, problem { bf.svc.corrections.reverse(w.fin, orig.event.id, ReverseCostEvent("Noch einmal")) }.code)
        // ohne offene Periode: 409; Recht und Mandant
        bf.svc.periods.close(w.fin, november.id)
        assertEquals(ErrorCode.CONFLICT, problem { bf.svc.corrections.reverse(w.fin, other, ReverseCostEvent("Keine offene Periode", accountingDate = LocalDate.of(2026, 11, 3))) }.code)
        val noScope = w.person(setOf("finance"), setOf("costs:read")); val driver = w.person(setOf("driver"), setOf("costs:reverse"))
        for (c in listOf(noScope, driver)) assertEquals(ErrorCode.FORBIDDEN, problem { bf.svc.corrections.reverse(c, other, ReverseCostEvent("Ohne Recht")) }.code)
        assertEquals(ErrorCode.NOT_FOUND, problem { bf.svc.corrections.reverse(LedgerWorld(bf).fin, other, ReverseCostEvent("Fremder Mandant")) }.code)
        assertEquals(ErrorCode.VALIDATION_FAILED, problem { bf.svc.corrections.reverse(w.fin, other, ReverseCostEvent("ab")) }.code)
    }

    @Test
    fun gegenereignis_kehrt_die_gespeicherten_teilbetraege_der_aufteilung_negativ_um() {
        val w = LedgerWorld(bf); val (september, october, _) = w.autumn(); val (a, b, c) = w.centers(3)
        val orig = w.postedEvent(10000, date = LocalDate.of(2026, 9, 15))
        fun lines(vararg o: UUID) = o.map { AllocationLineInput(it, quantity = java.math.BigDecimal.ONE) }
        val alloc = bf.svc.allocations.allocate(w.fin, orig.event.id, 0, AllocateCost("test-key-v1", "Drittelsplit", lines(a, b, c)))
        assertEquals(listOf(3334L, 3333L, 3333L), alloc.lines.map { it.amountMinor })
        bf.svc.periods.close(w.fin, september.id)
        // volle Korrektur: exakt die Negation der gespeicherten Beträge (keine Neuberechnung des Restcents)
        val full = bf.svc.corrections.reverse(w.fin, orig.event.id, ReverseCostEvent("Storno des Belegs", accountingDate = LocalDate.of(2026, 10, 2)))
        assertEquals(october.id, full.journal.periodId); val corr = full.allocation!!
        assertEquals(alloc.lines.map { it.costObjectId to -it.amountMinor }, corr.lines.map { it.costObjectId to it.amountMinor }); assertEquals(-10000L, corr.basisMinor); assertEquals(full.event.id, corr.eventId)
        // Gesamtansicht: Kostenereignis plus seine Aufteilung ergeben 100,00 EUR (nicht 200,00), nach der Korrektur 0
        val eff = bf.svc.allocations.effectiveCost(w.fin, w.w.legalEntity, "EUR")
        assertEquals(0L, eff.totalMinor); assertEquals(0L, eff.byCostObject.values.sum() + eff.unassignedMinor)
        // Teilkorrektur an einem zweiten Ereignis: anteilig im Verhältnis der gespeicherten Beträge, Summe exakt
        val second = w.postedEvent(10000, date = LocalDate.of(2026, 10, 10)); bf.svc.allocations.allocate(w.fin, second.event.id, 0, AllocateCost("k", "Drittelsplit", lines(a, b, c)))
        assertEquals(10000L, bf.svc.allocations.effectiveCost(w.fin, w.w.legalEntity, "EUR").totalMinor, "100,00 EUR, nicht 200,00 EUR")
        val part = bf.svc.corrections.reverse(w.fin, second.event.id, ReverseCostEvent("Teilgutschrift", creditGrossMinor = 3000, accountingDate = LocalDate.of(2026, 10, 12)))
        assertEquals(-3000L, part.allocation!!.lines.sumOf { it.amountMinor }); assertEquals(listOf(-1000L, -1000L, -1000L), part.allocation!!.lines.map { it.amountMinor })
        val eff2 = bf.svc.allocations.effectiveCost(w.fin, w.w.legalEntity, "EUR")
        assertEquals(7000L, eff2.totalMinor); assertEquals(7000L, eff2.byCostObject.values.sum() + eff2.unassignedMinor)
    }

    @Test
    fun abschluss_parallel_zu_posting_allokation_und_abschluss_genau_eine_reihenfolge() {
        var postedFirst = 0; var closedFirst = 0
        repeat(8) {
            val w = LedgerWorld(bf); val (september, _, _) = w.autumn(); val doc = w.approvedDocument(date = LocalDate.of(2026, 9, 15)); val (a, b) = w.centers(2)
            val pre = w.postedEvent(10001, date = LocalDate.of(2026, 9, 16))   // zur Aufteilung parallel zum Abschluss
            val pool = Executors.newFixedThreadPool(4); val go = CountDownLatch(1)
            val post = pool.submit<Result<Any>> { go.await(); runCatching { w.post(doc) } }
            val alloc = pool.submit<Result<Any>> { go.await(); runCatching { bf.svc.allocations.allocate(w.fin, pre.event.id, 0, AllocateCost("k", "Aufteilung", listOf(AllocationLineInput(a, amountMinor = 5000, amountCurrency = "EUR", amountExponent = 2), AllocationLineInput(b, amountMinor = 5001, amountCurrency = "EUR", amountExponent = 2)))) } }
            val closers = (1..2).map { pool.submit<Result<Any>> { go.await(); runCatching { bf.svc.periods.close(w.fin, september.id) } } }
            go.countDown(); val p = post.get(60, TimeUnit.SECONDS); val al = alloc.get(60, TimeUnit.SECONDS); val cl = closers.map { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
            // zwei parallele Abschlüsse: genau einer gewinnt, der andere sieht PERIOD_CLOSED; ein Auditeintrag
            assertEquals(1, cl.count { it.isSuccess }, cl.toString()); assertTrue(cl.filter { it.isFailure }.all { (it.exceptionOrNull() as ProblemException).code == ErrorCode.PERIOD_CLOSED }); assertEquals(1, w.audit("finance.period.closed"))
            // Posting: entweder vor dem Abschluss vollständig gebucht oder abgewiesen (PERIOD_CLOSED), nie halb
            val posted = bf.fx.count("SELECT count(*) FROM taktovia.economic_event WHERE document_id=?", doc)
            if (p.isSuccess) { postedFirst++; assertEquals(1, posted); assertEquals("POSTED", bf.fx.strings("SELECT state FROM taktovia.expense_document WHERE id=?", doc).single()) }
            else { closedFirst++; assertEquals(ErrorCode.PERIOD_CLOSED, (p.exceptionOrNull() as ProblemException).code, p.toString()); assertEquals(0, posted); assertEquals("APPROVED", bf.fx.strings("SELECT state FROM taktovia.expense_document WHERE id=?", doc).single()) }
            // Aufteilung: entweder vollständig oder PERIOD_CLOSED, nie ohne Aktivierung
            if (al.isSuccess) assertEquals(1, bf.fx.count("SELECT count(*) FROM taktovia.allocation_activation WHERE event_id=?", pre.event.id))
            else { assertEquals(ErrorCode.PERIOD_CLOSED, (al.exceptionOrNull() as ProblemException).code, al.toString()); assertEquals(0, bf.fx.count("SELECT count(*) FROM taktovia.allocation_set WHERE event_id=?", pre.event.id)) }
            // danach ist die Periode nicht mehr beschreibbar
            assertEquals(ErrorCode.PERIOD_CLOSED, problem { w.post(w.approvedDocument(date = LocalDate.of(2026, 9, 20))) }.code)
        }
        assertEquals(8, postedFirst + closedFirst)
    }

    @Test
    fun parallele_gegenereignisse_desselben_ursprungs_ueberschreiten_den_ursprung_nie() {
        val w = LedgerWorld(bf); w.autumn(); val orig = w.postedEvent(10000, date = LocalDate.of(2026, 9, 15))
        val pool = Executors.newFixedThreadPool(6); val go = CountDownLatch(1)
        val fs = (0 until 6).map { i -> pool.submit<Result<Any>> { go.await(); runCatching { bf.svc.corrections.reverse(w.fin, orig.event.id, ReverseCostEvent("Gutschrift $i", creditGrossMinor = 4000L + i, accountingDate = LocalDate.of(2026, 10, 5))) } } }
        go.countDown(); val out = fs.map { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(2, out.count { it.isSuccess }, out.toString())   // 4000 + 4001 = 8001 passen, jede weitere Gutschrift über 4000 überschritte 10000
        assertTrue(out.filter { it.isFailure }.all { (it.exceptionOrNull() as ProblemException).code == ErrorCode.VALIDATION_FAILED }, out.toString())
        assertEquals(1 + 2, w.events()); assertEquals(1 + 2, w.journals())
        assertTrue(bf.svc.costEvents.actualCostBasis(w.fin, w.w.legalEntity, "EUR") in 1L..2000L)
        // zwei parallele volle Korrekturen desselben Ursprungs: genau eine wirkt
        val w2 = LedgerWorld(bf); w2.autumn(); val o2 = w2.postedEvent(10000, date = LocalDate.of(2026, 9, 15))
        val pool2 = Executors.newFixedThreadPool(4); val go2 = CountDownLatch(1)
        val full = (0 until 4).map { pool2.submit<Result<Any>> { go2.await(); runCatching { bf.svc.corrections.reverse(w2.fin, o2.event.id, ReverseCostEvent("Storno", accountingDate = LocalDate.of(2026, 10, 5))) } } }
        go2.countDown(); val r2 = full.map { it.get(60, TimeUnit.SECONDS) }; pool2.shutdown()
        assertEquals(1, r2.count { it.isSuccess }, r2.toString()); assertTrue(r2.filter { it.isFailure }.all { (it.exceptionOrNull() as ProblemException).code == ErrorCode.ALREADY_REVERSED })
        assertEquals(0L, bf.svc.costEvents.actualCostBasis(w2.fin, w2.w.legalEntity, "EUR")); assertFalse(w2.events() > 2)
    }
}

package de.taktovia.documentsfinance

import de.taktovia.documentsfinance.api.AllocateCost
import de.taktovia.documentsfinance.api.AllocationLineInput
import de.taktovia.documentsfinance.api.CostAttribution
import de.taktovia.documentsfinance.api.CostBehavior
import de.taktovia.documentsfinance.api.CostSummaryQuery
import de.taktovia.documentsfinance.api.ReclassifyCostEvent
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.storage.InMemoryObjectStore
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WP-M4-39 (FCO-001, COST-08): Kostenklassifikation fix/variabel und direkt/umgelegt als Kreuztabelle je Kostenobjekt und Periode. Echte Datenbank unter der Runtime-Rolle: Rechenbeispiel B (Kap. 03 §6),
 * Original und Umlage nie doppelt, `MIXED` und „nicht klassifiziert“ getrennt, Pflichtangaben der Abfrage (422/404/403), Umklassifikation nur als Gegenereignis plus Neubuchung, konkurrierend zur Auswertung
 * und gegen sich selbst.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CostClassificationTest {
    private lateinit var bf: BelegFixture

    @BeforeAll fun start() { bf = BelegFixture(InMemoryObjectStore()) }
    @AfterAll fun stop() { bf.close() }

    private val oct15 = LocalDate.of(2026, 10, 15)

    @Test
    fun kostenaufstellung_testfahrzeug_zeigt_vier_klassen_getrennt_und_in_summe() {
        val w = CostClassWorld(bf)
        val vehicleObj = w.vehicleObject(); val service = w.costObject("SERVICE")
        // Rechenbeispiel B (Betreiber Y, 1.680,00 EUR): Gestellung 800,00 (Klassifikation nennt das Beispiel nicht: nicht klassifiziert), Energie 180,00 variabel direkt am Fahrzeug,
        // Fahrer 600,00 variabel direkt am Dienst, Disposition 100,00 fix umgelegt
        w.posted("GESTELLUNG", null, null, 80000, null)
        w.posted("ENERGY", CostBehavior.VARIABLE, CostAttribution.DIRECT, 18000, vehicleObj)
        w.posted("DRIVER", CostBehavior.VARIABLE, CostAttribution.DIRECT, 60000, service)
        w.posted("DISPATCH", CostBehavior.FIXED, CostAttribution.ALLOCATED, 10000, vehicleObj)
        val all = w.summary()
        assertEquals("ACTUAL", all.valuation); assertEquals("EUR", all.currency); assertEquals(LocalDate.of(2026, 9, 1), all.periodFrom); assertEquals(LocalDate.of(2026, 12, 1), all.periodTo)
        assertEquals("ACCOUNTING_DATE", all.periodBasis); assertNotNull(all.asOf)
        val t = all.totals
        assertEquals(168000, t.totalMinor, "Gesamtsumme Betreiber Y 1.680,00 EUR")
        assertEquals(10000, t.fixedMinor); assertEquals(78000, t.variableMinor); assertEquals(0, t.mixedMinor); assertEquals(80000, t.unclassifiedMinor)
        assertEquals(78000, t.directMinor); assertEquals(10000, t.allocatedMinor)
        assertEquals(t.totalMinor, t.fixedMinor + t.variableMinor + t.mixedMinor + t.unclassifiedMinor, "Achse fix/variabel plus nicht klassifiziert = Gesamt")
        assertEquals(t.totalMinor, t.directMinor + t.allocatedMinor + t.unclassifiedMinor, "Achse direkt/zugerechnet plus nicht klassifiziert = Gesamt")
        assertEquals(168000, all.rows.sumOf { it.totals.totalMinor }, "die Zeilen je Kostenobjekt ergeben die Gesamtsumme")
        assertEquals(4, all.eventCount)

        // Testfahrzeug: alle vier Klassen getrennt und in Summe
        val v = w.summary(vehicleId = w.vehicle)
        val row = v.rows.single(); assertEquals(vehicleObj, row.costObjectId); assertEquals("VEHICLE", row.costObjectKind); assertEquals(w.vehicle, row.vehicleId)
        assertEquals(10000, row.totals.fixedMinor); assertEquals(18000, row.totals.variableMinor); assertEquals(18000, row.totals.directMinor); assertEquals(10000, row.totals.allocatedMinor)
        assertEquals(28000, row.totals.totalMinor, "Summe der wirksamen Ist-Kosten, nicht die Addition der vier Klassen (das wären 56.000)")
        assertEquals(setOf(CostBehavior.VARIABLE to CostAttribution.DIRECT, CostBehavior.FIXED to CostAttribution.ALLOCATED), row.cells.map { it.costBehavior to it.attribution }.toSet())
        // Dienst als Kostenobjekt der Verkehrsleistung
        assertEquals(60000, all.rows.single { it.costObjectKind == "SERVICE" }.totals.variableMinor)
        // die Periode begrenzt: Oktober enthält nichts
        assertEquals(0, w.summary(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1)).totals.totalMinor)
    }

    @Test
    fun original_und_umlage_werden_nicht_doppelt_gezaehlt() {
        val w = CostClassWorld(bf)
        val (k1, k2) = w.lw.centers(2)
        val e = w.lw.postedEvent(gross = 168000).event   // 1.680,00 EUR (Kostenbasis = Brutto ohne Vorsteuer)
        assertEquals(168000, w.summary().totals.totalMinor)
        // Umlage auf K1 1.008,00 und K2 672,00: die Verteilung wird nicht auf die 1.680,00 addiert
        bf.svc.allocations.allocate(w.lw.fin, e.id, 0, AllocateCost("test-key-v1", "Aufteilung nach Testschlüssel", listOf(AllocationLineInput(k1, amountMinor = 100800), AllocationLineInput(k2, amountMinor = 67200))))
        val s = w.summary()
        assertEquals(168000, s.totals.totalMinor, "Original und Umlage derselben Kosten zählen einmal")
        assertEquals(168000, s.rows.sumOf { it.totals.totalMinor })
        assertEquals(100800, s.rows.single { it.costObjectId == k1 }.totals.totalMinor); assertEquals(67200, s.rows.single { it.costObjectId == k2 }.totals.totalMinor)
        assertTrue(s.rows.none { it.costObjectKind == "VEHICLE" }, "das Fahrzeug trägt nach der Umlage nichts mehr")
        assertEquals(bf.svc.costEvents.actualCostBasis(w.reader, w.entity, "EUR"), s.totals.totalMinor, "Gesamtsumme = Summe der wirksamen Ist-Kosten")
        // ein Gegenereignis hebt die Kosten samt Umlage auf (nicht doppelt negiert)
        bf.svc.corrections.reverse(w.lw.fin, e.id, de.taktovia.documentsfinance.api.ReverseCostEvent("Storno", accountingDate = oct15))
        assertEquals(0, w.summary().totals.totalMinor)
        assertEquals(0, w.summary().rows.sumOf { it.totals.totalMinor })
    }

    @Test
    fun mixed_eigene_spalte_und_nicht_klassifiziert_getrennt_ausgewiesen() {
        val w = CostClassWorld(bf); val obj = w.vehicleObject()
        w.posted("MIXEDCOST", CostBehavior.MIXED, CostAttribution.DIRECT, 3000, obj)
        w.posted("INSURANCE", CostBehavior.FIXED, CostAttribution.DIRECT, 5000, obj)
        w.posted("ENERGY", CostBehavior.VARIABLE, CostAttribution.DIRECT, 7000, obj)
        w.posted("GESTELLUNG", null, null, 800, obj)                       // ohne Klassifikation: nicht verworfen, separat ausgewiesen
        w.posted("ENERGY", CostBehavior.VARIABLE, CostAttribution.DIRECT, 9999, obj, posted = false)   // Journal nur im Entwurf: zählt nie
        val t = w.summary().totals
        assertEquals(3000, t.mixedMinor, "MIXED in eigener Spalte, weder fix noch variabel")
        assertEquals(5000, t.fixedMinor); assertEquals(7000, t.variableMinor)
        assertEquals(800, t.unclassifiedMinor, "nicht klassifiziert getrennt")
        assertEquals(15800, t.totalMinor)
        val cells = w.summary().rows.single().cells
        assertEquals(800, cells.single { it.costBehavior == null && it.attribution == null }.amountMinor); assertEquals(1, cells.single { it.costBehavior == CostBehavior.MIXED }.eventCount)
        assertEquals(4, w.summary().eventCount, "nur gebuchte Ereignisse")
    }

    @Test
    fun abfrage_ohne_valuation_periode_waehrung_422_fremdes_fahrzeug_404() {
        val w = CostClassWorld(bf); val other = CostClassWorld(bf)
        val ok = CostSummaryQuery("ACTUAL", "EUR", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1))
        fun code(q: CostSummaryQuery, ctx: de.taktovia.platform.context.RequestContext = w.reader) = bf.problem { bf.svc.classifications.summary(ctx, q) }
        for ((name, q) in mapOf("ohne Bewertungsart" to ok.copy(valuation = null), "ohne Periode (Beginn)" to ok.copy(periodFrom = null), "ohne Periode (Ende)" to ok.copy(periodTo = null), "ohne Währung" to ok.copy(currency = null),
            "Plan" to ok.copy(valuation = "PLAN"), "Forecast" to ok.copy(valuation = "FORECAST"), "unbekannte Bewertungsart" to ok.copy(valuation = "actual"), "Währung kein ISO-Code" to ok.copy(currency = "eur"),
            "Ende vor Beginn" to ok.copy(periodTo = LocalDate.of(2026, 8, 1)), "Periode zu lang" to ok.copy(periodTo = LocalDate.of(2030, 1, 1)))) {
            assertEquals(ErrorCode.VALIDATION_FAILED, code(q).code, name)
        }
        assertEquals(ErrorCode.NOT_FOUND, code(ok.copy(vehicleId = other.vehicle)).code, "Fahrzeug eines anderen Mandanten")
        assertEquals(ErrorCode.NOT_FOUND, code(ok.copy(vehicleId = UUID.randomUUID())).code, "unbekanntes Fahrzeug")
        assertEquals(ErrorCode.NOT_FOUND, code(ok.copy(legalEntityId = other.entity)).code, "Rechtsträger eines anderen Mandanten")
        assertEquals(ErrorCode.FORBIDDEN, code(ok, w.person(setOf("finance"), setOf("receipts:read"))).code, "ohne Leserecht")
        // fremde Kosten sind unsichtbar
        other.posted("ENERGY", CostBehavior.VARIABLE, CostAttribution.DIRECT, 123, other.vehicleObject())
        assertEquals(0, w.summary().totals.totalMinor)
    }

    @Test
    fun umklassifikation_ist_gegenereignis_und_neubuchung_nachvollziehbar() {
        val w = CostClassWorld(bf)
        val e = w.lw.postedEvent(gross = 10000).event      // Beleg ohne Klassifikation (Kostenart RECEIPT)
        assertNull(e.costBehavior)
        val before = bf.fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='finance.cost_event.reversed'", w.tenant.value)
        val r = bf.svc.classifications.reclassify(w.reclassifier, e.id, ReclassifyCostEvent(CostBehavior.VARIABLE, CostAttribution.DIRECT, "Tankkosten sind variabel", oct15))
        assertEquals(e.id, r.reversal.reversesId); assertEquals(-10000, r.reversal.costBasisMinor); assertNull(r.reversal.costBehavior, "das Gegenereignis trägt die Klassifikation des Ursprungs")
        assertEquals(e.id, r.rebooking.reclassifiedFromId); assertEquals(10000, r.rebooking.costBasisMinor); assertEquals("VARIABLE", r.rebooking.costBehavior); assertEquals("DIRECT", r.rebooking.attribution)
        assertEquals("POSTED", r.reversalJournal.state); assertEquals("POSTED", r.rebookingJournal.state); assertEquals(r.reversalJournal.periodId, r.rebookingJournal.periodId)
        assertEquals(before + 1, bf.fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='finance.cost_event.reversed'", w.tenant.value), "nachvollziehbar über das Gegenereignis im Audit")
        assertEquals(1, bf.fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='finance.cost_event.reclassified'", w.tenant.value))
        // der Ursprung bleibt unverändert; wirksam ist die Neubuchung (kein Beleg gebucht? der Beleg hat weiter genau ein ursprüngliches Ereignis)
        assertEquals(1, bf.fx.count("SELECT count(*) FROM taktovia.economic_event WHERE id=? AND cost_behavior IS NULL AND attribution IS NULL", e.id))
        val s = w.summary()
        assertEquals(10000, s.totals.totalMinor); assertEquals(10000, s.totals.variableMinor); assertEquals(0, s.totals.unclassifiedMinor)
        assertEquals(w.vehicle, s.rows.single().vehicleId, "die Neubuchung behält das Kostenobjekt des Belegs")
        // dieselbe Klassifikation, Gegenereignis, fremde Rechte
        assertEquals(ErrorCode.CONFLICT, bf.problem { bf.svc.classifications.reclassify(w.reclassifier, r.rebooking.id, ReclassifyCostEvent(CostBehavior.VARIABLE, CostAttribution.DIRECT, "gleich", oct15)) }.code)
        assertEquals(ErrorCode.CONFLICT, bf.problem { bf.svc.classifications.reclassify(w.reclassifier, r.reversal.id, ReclassifyCostEvent(CostBehavior.FIXED, CostAttribution.DIRECT, "Gegenereignis", oct15)) }.code)
        assertEquals(ErrorCode.ALREADY_REVERSED, bf.problem { bf.svc.classifications.reclassify(w.reclassifier, e.id, ReclassifyCostEvent(CostBehavior.FIXED, CostAttribution.DIRECT, "zweites Mal", oct15)) }.code)
        // die Neubuchung selbst ist wieder umklassifizierbar (Kette), die Summe bleibt
        val again = bf.svc.classifications.reclassify(w.reclassifier, r.rebooking.id, ReclassifyCostEvent(CostBehavior.FIXED, CostAttribution.ALLOCATED, "doch fix", oct15))
        assertEquals(10000, w.summary().totals.fixedMinor); assertEquals(10000, w.summary().totals.totalMinor); assertEquals(r.rebooking.id, again.rebooking.reclassifiedFromId)
        for (c in listOf(w.person(setOf("finance"), setOf("costs:read")), w.person(setOf("driver"), setOf("costs:reverse")), w.person(setOf("finance"), setOf("costs:reverse"), ActorType.SERVICE))) {
            assertEquals(ErrorCode.FORBIDDEN, bf.problem { bf.svc.classifications.reclassify(c, again.rebooking.id, ReclassifyCostEvent(CostBehavior.MIXED, CostAttribution.DIRECT, "ohne Recht", oct15)) }.code)
        }
        assertEquals(ErrorCode.NOT_FOUND, bf.problem { bf.svc.classifications.reclassify(CostClassWorld(bf).reclassifier, again.rebooking.id, ReclassifyCostEvent(CostBehavior.MIXED, CostAttribution.DIRECT, "fremd", oct15)) }.code)
        assertEquals(ErrorCode.VALIDATION_FAILED, bf.problem { bf.svc.classifications.reclassify(w.reclassifier, again.rebooking.id, ReclassifyCostEvent(CostBehavior.MIXED, CostAttribution.DIRECT, " ", oct15)) }.code)
    }

    @Test
    fun umklassifikation_parallel_zur_auswertung_keine_doppelzaehlung() {
        val w = CostClassWorld(bf)
        val ids = (1..4).map { w.lw.postedEvent(gross = 10000L * it).event.id }   // 100 + 200 + 300 + 400 EUR, unklassifiziert
        val expected = 100000L
        assertEquals(expected, w.summary().totals.totalMinor)
        val pool = Executors.newFixedThreadPool(6); val go = CountDownLatch(1); val done = AtomicBoolean(false)
        val reader = pool.submit<List<Long>> {
            go.await(); val seen = mutableListOf<Long>()
            while (!done.get()) seen += w.summary().totals.totalMinor
            seen += w.summary().totals.totalMinor; seen
        }
        val writers = ids.mapIndexed { i, id ->
            pool.submit<Unit> { go.await(); bf.svc.classifications.reclassify(w.reclassifier, id, ReclassifyCostEvent(if (i % 2 == 0) CostBehavior.VARIABLE else CostBehavior.FIXED, CostAttribution.DIRECT, "Umklassifikation $i", oct15)) }
        }
        go.countDown(); writers.forEach { it.get(120, TimeUnit.SECONDS) }; done.set(true)
        val seen = reader.get(60, TimeUnit.SECONDS); pool.shutdown()
        assertTrue(seen.size >= 2, "die Auswertung lief parallel")
        assertTrue(seen.all { it == expected }, "in keinem Zwischenstand eine Doppelzählung oder Lücke: ${seen.distinct()}")
        val t = w.summary().totals
        assertEquals(expected, t.totalMinor); assertEquals(0, t.unclassifiedMinor); assertEquals(40000, t.variableMinor); assertEquals(60000, t.fixedMinor)
    }

    @Test
    fun zwei_parallele_umklassifikationen_genau_eine_neubuchung() {
        val w = CostClassWorld(bf)
        val e = w.lw.postedEvent(gross = 25000).event
        val pool = Executors.newFixedThreadPool(4); val go = CountDownLatch(1)
        val results = (0 until 4).map { i ->
            pool.submit<Result<Any>> { go.await(); runCatching { bf.svc.classifications.reclassify(w.reclassifier, e.id, ReclassifyCostEvent(CostBehavior.entries[i % 3], CostAttribution.DIRECT, "parallel $i", oct15)) } }
        }
        go.countDown(); val out = results.map { it.get(120, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(1, out.count { it.isSuccess }, out.toString())
        assertTrue(out.filter { it.isFailure }.all { (it.exceptionOrNull() as ProblemException).code in setOf(ErrorCode.ALREADY_REVERSED, ErrorCode.CONFLICT) }, out.toString())
        assertEquals(1, bf.fx.count("SELECT count(*) FROM taktovia.economic_event WHERE tenant_id=? AND reclassified_from_id=?", w.tenant.value, e.id), "genau eine wirksame Neubuchung")
        assertEquals(1, bf.fx.count("SELECT count(*) FROM taktovia.economic_event WHERE tenant_id=? AND reverses_id=?", w.tenant.value, e.id), "genau ein Gegenereignis")
        assertEquals(25000, w.summary().totals.totalMinor)
    }
}

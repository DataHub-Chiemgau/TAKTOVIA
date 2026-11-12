package de.taktovia.documentsfinance

import de.taktovia.documentsfinance.api.AssignCosts
import de.taktovia.documentsfinance.api.CaptureFuel
import de.taktovia.documentsfinance.api.CorrectExpense
import de.taktovia.documentsfinance.api.DocumentKind
import de.taktovia.documentsfinance.api.ExpenseFields
import de.taktovia.documentsfinance.api.ExpenseView
import de.taktovia.documentsfinance.api.FuelFields
import de.taktovia.documentsfinance.api.FuelType
import de.taktovia.documentsfinance.api.FuelUnit
import de.taktovia.documentsfinance.api.PostExpense
import de.taktovia.documentsfinance.api.PutAccountingMapping
import de.taktovia.documentsfinance.api.ReleaseBlocker
import de.taktovia.documentsfinance.api.StoredDocumentView
import de.taktovia.documentsfinance.api.UpdateFinancePolicy
import de.taktovia.platform.context.Actor
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.platform.storage.InMemoryObjectStore
import de.taktovia.platform.tenant.TenantId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M4-30 (DOC-005): Tankbeleg-Fachdaten. Echte Datenbank unter der Runtime-Rolle: Pflichtwerte werden einzeln angezeigt und blockieren die Freigabe, ein vollständiger Tankvorgang ist nach der
 * Freigabe genau einmal als Kostenereignis auswertbar (E2E-064), Mengen und Preise bleiben Dezimalwerte ohne Gleitkomma, fremde Fahrzeuge sind nicht erreichbar.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FuelReceiptTest {
    private lateinit var bf: BelegFixture

    @BeforeAll fun start() { bf = BelegFixture(InMemoryObjectStore()) }
    @AfterAll fun stop() { bf.close() }

    private inner class Scenario(val kind: DocumentKind = DocumentKind.FUEL_RECEIPT, val amount: Boolean = true) {
        val w = bf.fx.world()
        val fin = bf.financeCtx(w)
        val driverId = UUID.randomUUID()
        val driver = bf.driverCtx(w, driverId)
        val poster = ctx(w.tenant, setOf("finance"), setOf("expenses:approve", "receipts:write", "receipts:read", "costs:read"))
        val vehicle = vehicle(w.tenant)
        val doc: StoredDocumentView = bf.upload(w, driver, kind = kind)
        var revision = doc.revision
        init {
            bf.fx.exec("INSERT INTO taktovia.fiscal_period(tenant_id,id,legal_entity_id,starts_on,ends_on) VALUES (?,?,?,DATE '2026-01-01',DATE '2027-01-01')", w.tenant.value, UUID.randomUUID(), w.legalEntity)
            bf.svc.accounting.put(fin.withScopes("expenses:approve"), PutAccountingMapping(w.legalEntity, "FUEL", "6600", "1576", "1600"))
            if (amount) fields(ExpenseFields.AMOUNT_MINOR to 11900L, ExpenseFields.CURRENCY to "EUR", ExpenseFields.DOCUMENT_DATE to "2026-06-15", ExpenseFields.MERCHANT to "Tankstelle Nord",
                ExpenseFields.VAT_AMOUNTS to listOf(mapOf("vat_rate_pct" to "19", "amount_minor" to 1900L)))
        }
        fun fields(vararg f: Pair<String, Any?>): ExpenseView = bf.svc.expenses.correct(fin, doc.documentId, revision, CorrectExpense("erfasst", f.toMap())).also { revision = it.document.revision }
        fun fuel(c: CaptureFuel, by: RequestContext = driver): ExpenseView = bf.svc.fuel.capture(by, doc.documentId, revision, c).also { revision = it.document.revision }
        fun complete() = fuel(CaptureFuel(vehicle, FuelType.DIESEL, BigDecimal("42.5"), BigDecimal("1.679"), 120_500, Instant.parse("2026-06-15T08:00:00Z"), "KARTE-77"))
        fun view() = bf.svc.expenses.get(fin, doc.documentId)
        fun approveBySql() = bf.fx.exec("UPDATE taktovia.expense_document SET state='APPROVED', approved_by='user:freigeber' WHERE id=?", doc.documentId)
        fun events() = bf.fx.count("SELECT count(*) FROM taktovia.economic_event WHERE tenant_id=?", w.tenant.value)
    }

    private fun ctx(t: TenantId, roles: Set<String>, scopes: Set<String>) =
        RequestContext(Actor(ActorType.HUMAN, "https://idp.test/realms/m4", UUID.randomUUID().toString(), "sid", true), t, roles, scopes, emptySet(), UUID.randomUUID())

    private fun RequestContext.withScopes(vararg s: String) = RequestContext(actor, tenant, roles, scopes + s, services, correlationId)

    private fun vehicle(t: TenantId): UUID {
        val id = UUID.randomUUID()
        bf.fx.exec("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (?,?,'org',CAST('{}' AS jsonb))", t.value, id)
        return id
    }

    @Test
    fun fehlende_pflichtwerte_werden_einzeln_angezeigt() {
        val s = Scenario()
        // noch nichts erfasst: alle fünf Pflichtwerte fehlen einzeln, der Beleg ist nicht freigabefähig
        val v0 = s.view()
        assertEquals(FuelFields.REQUIRED, v0.fuel!!.missing); assertTrue(ReleaseBlocker.FUEL_FIELDS_MISSING in v0.blockers); assertFalse(v0.approvable)
        // nur Menge und Preis: Fahrzeug, Kraftstoffart und Kilometerstand fehlen weiter, je Wert ein Eintrag
        val v1 = s.fuel(CaptureFuel(quantity = BigDecimal("42.5"), unitPrice = BigDecimal("1.679")))
        assertEquals(listOf(FuelFields.VEHICLE_ID, FuelFields.FUEL_TYPE, FuelFields.ODOMETER_KM), v1.fuel!!.missing)
        assertTrue(ReleaseBlocker.FUEL_FIELDS_MISSING in v1.blockers)
        // Liter fehlen, alles andere vorhanden
        val s2 = Scenario(); s2.fuel(CaptureFuel(s2.vehicle, FuelType.DIESEL, null, BigDecimal("1.679"), 1000))
        assertEquals(listOf(FuelFields.QUANTITY), s2.view().fuel!!.missing)
        // vollständig: kein Hindernis aus den Tankwerten
        val s3 = Scenario(); s3.complete()
        assertTrue(s3.view().fuel!!.complete); assertFalse(ReleaseBlocker.FUEL_FIELDS_MISSING in s3.view().blockers)
        // Beleg ohne Betrag: der Brutto fehlt als Belegpflichtfeld (UNCONFIRMED_FIELDS), nicht als Tankwert
        val s4 = Scenario(amount = false); s4.complete()
        assertTrue(s4.view().fuel!!.complete); assertTrue(ExpenseFields.AMOUNT_MINOR in s4.view().review.unconfirmedFields)
    }

    @Test
    fun vollstaendiger_tankbeleg_ist_nach_der_freigabe_als_kostenereignis_auswertbar() {
        val s = Scenario(); s.complete()
        bf.svc.costs.assign(s.fin, s.doc.documentId, s.revision, AssignCosts(vehicleIds = setOf(s.vehicle)))
        val v = s.view()
        assertTrue(v.approvable, v.blockers.toString()); assertEquals(BigDecimal("42.5"), v.fuel!!.quantity); assertEquals(FuelUnit.LITER, v.fuel!!.unit)
        assertEquals(11900L, v.fuel!!.grossMinor); assertEquals("EUR", v.fuel!!.currency)   // Brutto folgt dem Belegstand
        s.approveBySql()
        val r = bf.svc.costEvents.postExpense(s.poster, s.doc.documentId, PostExpense("FUEL"))
        assertEquals(10000L, r.event.costBasisMinor); assertEquals(1, s.events())
        assertEquals(10000L, bf.svc.costEvents.actualCostBasis(s.poster, s.w.legalEntity, "EUR"))
    }

    @Test
    fun parallele_posting_ausloeser_desselben_tankbelegs_ergeben_ein_kostenereignis() {
        val s = Scenario(); s.complete(); bf.svc.costs.assign(s.fin, s.doc.documentId, s.revision, AssignCosts(vehicleIds = setOf(s.vehicle))); s.approveBySql()
        val pool = Executors.newFixedThreadPool(6); val go = CountDownLatch(1)
        val out = (1..6).map { pool.submit<Result<Boolean>> { go.await(); runCatching { bf.svc.costEvents.postExpense(s.poster, s.doc.documentId, PostExpense("FUEL")).reused } } }
        go.countDown(); val res = out.map { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
        assertTrue(res.all { it.isSuccess }, res.toString()); assertEquals(1, res.count { it.getOrThrow() == false }); assertEquals(1, s.events())
    }

    @Test
    fun negative_oder_ungueltige_werte_werden_abgewiesen() {
        val s = Scenario(); val r0 = s.revision
        fun bad(c: CaptureFuel, pointer: String, code: ViolationCode) {
            val p = bf.problem { s.fuel(c) }
            assertEquals(ErrorCode.VALIDATION_FAILED, p.code, p.toString()); assertEquals(pointer, p.violations.single().pointer); assertEquals(code, p.violations.single().code)
        }
        bad(CaptureFuel(quantity = BigDecimal("-1.5")), "/fuel/quantity", ViolationCode.BELOW_MINIMUM)
        bad(CaptureFuel(quantity = BigDecimal.ZERO), "/fuel/quantity", ViolationCode.BELOW_MINIMUM)
        bad(CaptureFuel(unitPrice = BigDecimal("0.00")), "/fuel/unit_price", ViolationCode.BELOW_MINIMUM)
        bad(CaptureFuel(quantity = BigDecimal("1.123456789")), "/fuel/quantity", ViolationCode.INVALID_VALUE)   // mehr als 8 Nachkommastellen
        bad(CaptureFuel(quantity = BigDecimal("1000000000000")), "/fuel/quantity", ViolationCode.ABOVE_MAXIMUM)
        bad(CaptureFuel(odometerKm = -1), "/fuel/odometer_km", ViolationCode.BELOW_MINIMUM)
        bad(CaptureFuel(odometerKm = 10_000_001), "/fuel/odometer_km", ViolationCode.ABOVE_MAXIMUM)
        bad(CaptureFuel(cardId = "ungültig mit Leerzeichen"), "/fuel/card_id", ViolationCode.PATTERN_MISMATCH)
        bad(CaptureFuel(fueledAt = Instant.now().plusSeconds(3 * 86400)), "/fuel/fueled_at", ViolationCode.INVALID_VALUE)
        bad(CaptureFuel(), "/fuel", ViolationCode.REQUIRED)
        assertEquals(0, bf.fx.count("SELECT count(*) FROM taktovia.fuel_transaction WHERE tenant_id=?", s.w.tenant.value), "abgewiesene Eingaben hinterlassen nichts")
        assertEquals(r0, s.view().document.revision, "keine neue Revision")
    }

    @Test
    fun fremdes_fahrzeug_wird_ohne_existenzhinweis_abgewiesen() {
        val s = Scenario(); val other = Scenario()
        val foreign = bf.problem { s.fuel(CaptureFuel(vehicleId = other.vehicle)) }
        val unknown = bf.problem { s.fuel(CaptureFuel(vehicleId = UUID.randomUUID())) }
        assertEquals(ErrorCode.NOT_FOUND, foreign.code); assertEquals(foreign.code, unknown.code); assertEquals(foreign.detail, unknown.detail, "fremd und unbekannt sind nicht unterscheidbar")
        assertNull(s.view().fuel!!.vehicleId)
    }

    @Test
    fun mengen_und_preise_werden_ohne_gleitkomma_verarbeitet() {
        val s = Scenario()
        val v = s.fuel(CaptureFuel(quantity = BigDecimal("33.33333333"), unitPrice = BigDecimal("1.79900000")))
        assertEquals(BigDecimal("33.33333333"), v.fuel!!.quantity); assertEquals(BigDecimal("1.799"), v.fuel!!.unitPrice)
        // 0,1 + 0,2 bleibt in der Datenbank exakt 0,3 (in Gleitkomma 0,30000000000000004)
        val s2 = Scenario(); s2.fuel(CaptureFuel(quantity = BigDecimal("0.1")))
        assertEquals(BigDecimal("0.1"), s2.view().fuel!!.quantity)
        assertEquals(BigDecimal("0.3"), bf.fx.strings("SELECT (quantity + 0.2)::text FROM taktovia.fuel_transaction WHERE document_id=?", s2.doc.documentId).single().toBigDecimal().stripTrailingZeros())
        val unit = Scenario(); unit.fuel(CaptureFuel(fuelType = FuelType.ELECTRIC, quantity = BigDecimal("55.5")))
        assertEquals(FuelUnit.KWH, unit.view().fuel!!.unit, "kWh getrennt von Litern")
    }

    @Test
    fun erfassung_erzeugt_eine_revision_wiederholung_nicht_und_veraltete_revision_ist_412() {
        val s = Scenario(); val r0 = s.revision
        val v = s.complete(); assertEquals(r0 + 1, v.document.revision)
        val again = bf.svc.fuel.capture(s.driver, s.doc.documentId, s.revision, CaptureFuel(vehicleId = s.vehicle)); assertEquals(r0 + 1, again.document.revision, "gleicher Wert: keine neue Revision")
        assertEquals(ErrorCode.REVISION_MISMATCH, bf.problem { bf.svc.fuel.capture(s.driver, s.doc.documentId, r0, CaptureFuel(odometerKm = 5)) }.code)
        val revs = bf.svc.expenses.revisions(s.fin, s.doc.documentId).last()
        assertEquals("FUEL_CAPTURE", revs.reason); assertTrue(revs.fields.containsKey("fuel")); assertNull((revs.fields["fuel"]!!.old))
        assertEquals(1, bf.fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='finance.fuel.recorded'", s.w.tenant.value))
        assertEquals(1, bf.fx.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND event_type='fuel.transaction_recorded'", s.w.tenant.value))
    }

    @Test
    fun parallele_erfassungen_auf_derselben_revision_genau_eine_gewinnt() {
        val s = Scenario(); val pool = Executors.newFixedThreadPool(4); val go = CountDownLatch(1)
        val out = (1..4).map { i -> pool.submit<Result<ExpenseView>> { go.await(); runCatching { bf.svc.fuel.capture(s.driver, s.doc.documentId, s.revision, CaptureFuel(odometerKm = 1000L + i)) } } }
        go.countDown(); val res = out.map { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(1, res.count { it.isSuccess }, res.toString())
        assertTrue(res.filter { it.isFailure }.all { (it.exceptionOrNull() as ProblemException).code == ErrorCode.REVISION_MISMATCH })
    }

    @Test
    fun nach_der_freigabe_sind_tankwerte_unveraenderlich_und_belege_anderer_art_haben_keine() {
        val s = Scenario(); s.complete(); bf.svc.costs.assign(s.fin, s.doc.documentId, s.revision, AssignCosts(vehicleIds = setOf(s.vehicle))); s.approveBySql()
        val rev = bf.svc.expenses.get(s.fin, s.doc.documentId).document.revision
        assertEquals(ErrorCode.IMMUTABLE_RECORD, bf.problem { bf.svc.fuel.capture(s.fin, s.doc.documentId, rev, CaptureFuel(odometerKm = 7)) }.code)
        val plain = Scenario(kind = DocumentKind.RECEIPT)
        assertEquals(ErrorCode.VALIDATION_FAILED, bf.problem { plain.fuel(CaptureFuel(odometerKm = 7)) }.code)
        assertNull(plain.view().fuel); assertNull(bf.svc.fuel.get(plain.fin, plain.doc.documentId))
    }

    @Test
    fun fuel_receipt_required_false_blockiert_unvollstaendige_tankwerte_nicht() {
        val s = Scenario()
        assertTrue(ReleaseBlocker.FUEL_FIELDS_MISSING in s.view().blockers)
        assertEquals(ErrorCode.FORBIDDEN, bf.problem { bf.svc.policies.update(s.driver.withScopes("receipts:write"), UpdateFinancePolicy(false)) }.code, "nur die Finanzrolle")
        assertTrue(bf.svc.policies.get(s.fin).fuelReceiptRequired)
        bf.svc.policies.update(s.fin, UpdateFinancePolicy(false))
        assertFalse(bf.svc.policies.get(s.fin).fuelReceiptRequired)
        assertFalse(ReleaseBlocker.FUEL_FIELDS_MISSING in s.view().blockers)
        val other = Scenario(); assertTrue(bf.svc.policies.get(other.fin).fuelReceiptRequired, "Parameter eines anderen Mandanten wirkt nicht")
    }

    @Test
    fun erfassung_ersetzt_nie_die_finanzpruefung_und_fremde_mandanten_sehen_nichts() {
        val s = Scenario(); s.complete()
        assertEquals("STORED", s.view().document.workflowState); assertEquals(0, bf.fx.count("SELECT count(*) FROM taktovia.expense_document WHERE id=? AND approved_by IS NOT NULL", s.doc.documentId))
        val other = Scenario()
        assertEquals(ErrorCode.NOT_FOUND, bf.problem { bf.svc.fuel.get(other.fin, s.doc.documentId) }.code)
        assertEquals(ErrorCode.NOT_FOUND, bf.problem { bf.svc.fuel.capture(other.fin, s.doc.documentId, s.revision, CaptureFuel(odometerKm = 3)) }.code)
        val noScope = ctx(s.w.tenant, setOf("driver"), emptySet())
        assertEquals(ErrorCode.FORBIDDEN, bf.problem { bf.svc.fuel.capture(noScope, s.doc.documentId, s.revision, CaptureFuel(odometerKm = 3)) }.code)
    }
}

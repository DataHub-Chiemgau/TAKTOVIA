package de.taktovia.documentsfinance

import de.taktovia.documentsfinance.api.AssignCosts
import de.taktovia.documentsfinance.api.CaptureFuel
import de.taktovia.documentsfinance.api.CorrectExpense
import de.taktovia.documentsfinance.api.DocumentKind
import de.taktovia.documentsfinance.api.ExpenseFields
import de.taktovia.documentsfinance.api.ExpenseView
import de.taktovia.documentsfinance.api.FuelCardMapping
import de.taktovia.documentsfinance.api.FuelParameters
import de.taktovia.documentsfinance.api.FuelType
import de.taktovia.documentsfinance.api.PlausibilityRule
import de.taktovia.documentsfinance.api.PlausibilityRule.FUEL_CARD_MISMATCH
import de.taktovia.documentsfinance.api.PlausibilityRule.FUEL_ODOMETER_GAP
import de.taktovia.documentsfinance.api.PlausibilityRule.FUEL_PRICE
import de.taktovia.documentsfinance.api.PlausibilityRule.FUEL_VOLUME
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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M4-31 (DOC-012, E2E-070): Tankplausibilität mit kontrollierbaren Hinweisen. Echte Datenbank unter der Runtime-Rolle: je Regel ein eigener Hinweis über der Schwelle und keiner exakt auf der
 * Schwelle, Berechnungsbasis im Hinweis, keine stillen Änderungen, Quittierung mit Begründung und Audit, Freigabesperre bei offenem Hinweis, parallele Prüfläufe und Quittierungen.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FuelPlausibilityTest {
    private lateinit var bf: BelegFixture

    @BeforeAll fun start() { bf = BelegFixture(InMemoryObjectStore()) }
    @AfterAll fun stop() { bf.close() }

    private fun ctx(t: TenantId, roles: Set<String>, scopes: Set<String>) =
        RequestContext(Actor(ActorType.HUMAN, "https://idp.test/realms/m4", UUID.randomUUID().toString(), "sid", true), t, roles, scopes, emptySet(), UUID.randomUUID())

    private fun vehicle(t: TenantId): UUID { val id = UUID.randomUUID(); bf.fx.exec("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (?,?,'org',CAST('{}' AS jsonb))", t.value, id); return id }

    private fun dec(s: String) = BigDecimal(s)

    /** Mandant mit Fahrzeug und konfigurierten Schwellen: max. 80 l, Dieselvergleichspreis 1,60 (±10 %), Verbrauchsabweichung 20 %, Karte KARTE-1 gehört zum Fahrzeug. */
    private inner class World(configure: Boolean = true) {
        val w = bf.fx.world()
        val fin = bf.financeCtx(w).let { RequestContext(it.actor, it.tenant, it.roles, it.scopes + setOf("expenses:review"), it.services, it.correlationId) }
        val driver = bf.driverCtx(w)
        val vehicle = vehicle(w.tenant); val otherVehicle = vehicle(w.tenant)
        init {
            if (configure) bf.svc.policies.update(fin, UpdateFinancePolicy(fuel = FuelParameters(dec("80"), dec("10"), dec("20"), mapOf(FuelType.DIESEL to dec("1.60")),
                listOf(FuelCardMapping("KARTE-1", vehicle, LocalDate.parse("2026-01-01"))))))
        }

        inner class Fill(val doc: StoredDocumentView) {
            var revision = doc.revision
            fun fuel(c: CaptureFuel, by: RequestContext = driver): ExpenseView = bf.svc.fuel.capture(by, doc.documentId, revision, c).also { revision = it.document.revision }
            fun view() = bf.svc.expenses.get(fin, doc.documentId)
            fun rules() = view().plausibility.hints.map { it.rule }.toSet()
            fun fields(vararg f: Pair<String, Any?>) = bf.svc.expenses.correct(fin, doc.documentId, revision, CorrectExpense("erfasst", f.toMap())).also { revision = it.document.revision }
        }

        /** Tankvorgang mit Tankzeit `day` Juni 2026. */
        fun fill(day: Int, odo: Long?, qty: String, price: String = "1.60", card: String? = null, type: FuelType = FuelType.DIESEL, vehicleId: UUID? = vehicle): Fill {
            val f = Fill(bf.upload(w, driver, bytes = Samples.jpeg(seed = (Math.random() * 1_000_000).toInt()), kind = DocumentKind.FUEL_RECEIPT))
            f.fuel(CaptureFuel(vehicleId, type, dec(qty), dec(price), odo, Instant.parse("2026-06-%02dT08:00:00Z".format(day)), card))
            return f
        }

        /** Beleg freigabefähig machen (außer Hinweisen): Pflichtfelder, Kostenzuordnung. */
        fun readyForApproval(f: Fill) {
            f.fields(ExpenseFields.AMOUNT_MINOR to 11900L, ExpenseFields.CURRENCY to "EUR", ExpenseFields.DOCUMENT_DATE to "2026-06-15", ExpenseFields.MERCHANT to "Tankstelle")
            f.revision = bf.svc.costs.assign(fin, f.doc.documentId, f.revision, AssignCosts(vehicleIds = setOf(vehicle))).document.revision
        }
        fun count(rule: PlausibilityRule? = null, doc: UUID? = null) = bf.fx.count("SELECT count(*) FROM taktovia.expense_hint WHERE tenant_id=?" + (rule?.let { " AND rule='${it.name}'" } ?: "") + (doc?.let { " AND document_id='$it'" } ?: ""), w.tenant.value)
    }

    @Test
    fun je_regel_eigener_hinweis_ueber_schwelle_und_keiner_exakt_auf_schwelle() {
        val a = World()
        // Menge: exakt 80 kein Hinweis, knapp darüber ein Hinweis
        assertTrue(FUEL_VOLUME !in a.fill(1, 1000, "80").rules()); assertTrue(FUEL_VOLUME in a.fill(2, 1500, "80.001").rules())
        // Literpreis: +10 % exakt (1,76) und -10 % exakt (1,44) kein Hinweis, knapp darüber/darunter ein Hinweis
        assertTrue(FUEL_PRICE !in a.fill(3, 2000, "40", "1.76").rules()); assertTrue(FUEL_PRICE !in a.fill(4, 2500, "40", "1.44").rules())
        assertTrue(FUEL_PRICE in a.fill(5, 3000, "40", "1.761").rules()); assertTrue(FUEL_PRICE in a.fill(6, 3500, "40", "1.439").rules())
        // Kilometerabstand/Verbrauch: Historie 10,0 l/100 km; 12,0 (genau +20 %) kein Hinweis, darüber ein Hinweis
        val b = World()
        b.fill(1, 10000, "50"); b.fill(2, 10500, "50")                 // Verbrauch des zweiten Abstands: 50 l auf 500 km = 10,0
        assertTrue(FUEL_ODOMETER_GAP !in b.fill(3, 11000, "60").rules(), "exakt +20 %: kein Hinweis")
        val over = b.fill(4, 11000, "60.01"); assertTrue(FUEL_ODOMETER_GAP in over.rules(), "knapp darüber: Hinweis")
        // Karte: gültige Zuordnung kein Hinweis, anderes Fahrzeug ein Hinweis; ohne Karte kein Hinweis
        assertTrue(FUEL_CARD_MISMATCH !in a.fill(7, 4000, "40", card = "KARTE-1").rules())
        assertTrue(FUEL_CARD_MISMATCH in a.fill(8, 100, "40", card = "KARTE-1", vehicleId = a.otherVehicle).rules())
        assertTrue(FUEL_CARD_MISMATCH in a.fill(9, 4500, "40", card = "UNBEKANNT").rules())
        assertTrue(a.fill(10, 5000, "40").view().plausibility.hints.none { it.rule == FUEL_CARD_MISMATCH })
        // alle vier Regeln zugleich: je Regel ein eigener Hinweis
        val c = World(); c.fill(1, 10000, "50"); c.fill(2, 10500, "50")
        val all = c.fill(3, 10600, "90", "2.50", card = "FALSCH")
        assertEquals(setOf(FUEL_VOLUME, FUEL_PRICE, FUEL_ODOMETER_GAP, FUEL_CARD_MISMATCH), all.rules()); assertEquals(4, all.view().plausibility.hints.size)
        // ein plausibler Tankvorgang erzeugt keinen Hinweis und blockiert nichts durch Hinweise
        val ok = World().let { w -> w.fill(1, 1000, "40", "1.65", card = "KARTE-1").view() }
        assertTrue(ok.plausibility.hints.isEmpty()); assertFalse(ReleaseBlocker.HINT_UNACKNOWLEDGED in ok.blockers)
    }

    @Test
    fun hinweis_aendert_keine_erfassten_werte_und_nennt_berechnungsbasis() {
        val w = World(); val f = w.fill(1, 10000, "50"); w.fill(2, 10500, "50")
        val g = w.fill(3, 11000, "95", "1.90", card = "X")
        val before = g.view(); val fuelBefore = before.fuel; val fieldsBefore = before.fields; val revBefore = before.document.revision
        val report = bf.svc.plausibility.check(w.fin, g.doc.documentId)   // erneuter Prüflauf
        val after = g.view()
        assertEquals(fuelBefore, after.fuel, "erfasste Tankwerte unverändert"); assertEquals(fieldsBefore, after.fields); assertEquals(revBefore, after.document.revision, "keine neue Revision durch den Hinweis")
        val vol = report.hints.single { it.rule == FUEL_VOLUME }
        assertEquals("80", vol.threshold); assertEquals("95", vol.actualValue); assertEquals("80", vol.basis["comparison_value"]); assertEquals("80", vol.basis["threshold"]); assertTrue((vol.basis["source"] as String).contains("fuel_max_liters"))
        val price = report.hints.single { it.rule == FUEL_PRICE }
        assertEquals("10", price.threshold); assertEquals("1.9", price.actualValue); assertEquals("1.6", price.basis["comparison_value"]); assertEquals("18.7500", price.basis["deviation_pct"]); assertNotNull(price.basis["source"])
        val gap = report.hints.single { it.rule == FUEL_ODOMETER_GAP }
        assertEquals("20", gap.threshold); assertEquals("19", gap.actualValue.substringBefore(".")); assertEquals("10", (gap.basis["comparison_value"] as String).substringBefore(".")); assertEquals(500, gap.basis["odometer_gap_km"]); assertEquals(10500, gap.basis["previous_odometer_km"])
        assertTrue(report.hints.all { it.open && it.documentRevision == revBefore })
        assertTrue(f.view().plausibility.hints.isEmpty(), "der Vorgänger-Beleg ist nicht betroffen")
        // Entstehung im Audit (je Regel und Revision einmal), Hinweis ist einsehbar
        assertEquals(report.hints.size, bf.fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='finance.hint.raised'", w.w.tenant.value))
        assertEquals(report.hints.map { it.id }.toSet(), bf.svc.plausibility.report(w.fin, g.doc.documentId).hints.map { it.id }.toSet())
    }

    @Test
    fun unquittierter_hinweis_blockiert_freigabe() {
        val w = World(); val f = w.fill(1, 1000, "85"); w.readyForApproval(f)
        assertTrue(ReleaseBlocker.HINT_UNACKNOWLEDGED in f.view().blockers); assertFalse(f.view().approvable)
        val p = bf.problem { bf.svc.review.requireApprovable(w.fin, f.doc.documentId, f.revision) }
        assertEquals(ErrorCode.HINT_NOT_ACKNOWLEDGED, p.code); assertEquals(listOf("/hints/FUEL_VOLUME"), p.violations.map { it.pointer })
        // Quittierung mit Begründung hebt die Sperre auf; der Hinweis selbst bleibt unverändert sichtbar
        val hint = f.view().plausibility.hints.single()
        val acked = bf.svc.plausibility.acknowledge(w.fin, f.doc.documentId, hint.id, f.revision, "Großtank, Fuhrpark-Sondertour")
        assertFalse(ReleaseBlocker.HINT_UNACKNOWLEDGED in acked.blockers); assertEquals("Großtank, Fuhrpark-Sondertour", acked.plausibility.hints.single().acknowledgement!!.reason)
        assertEquals(hint.copy(acknowledgement = acked.plausibility.hints.single().acknowledgement), acked.plausibility.hints.single(), "Hinweis unverändert, nur die Quittierung kommt dazu")
        assertNotNull(bf.svc.review.requireApprovable(w.fin, f.doc.documentId, f.revision))
        // exakt dieselben Werte in einem Beleg ohne Hinweis blockieren nicht
        val clean = w.fill(2, 2000, "50"); w.readyForApproval(clean); assertNotNull(bf.svc.review.requireApprovable(w.fin, clean.doc.documentId, clean.revision))
    }

    @Test
    fun quittierung_gilt_nur_fuer_die_revision_und_neue_werte_werden_neu_geprueft() {
        val w = World(); val f = w.fill(1, 1000, "85")
        val h1 = f.view().plausibility.hints.single(); val rev1 = f.revision
        bf.svc.plausibility.acknowledge(w.fin, f.doc.documentId, h1.id, rev1, "erste Begründung")
        // neue Revision (anderer Wert) -> neu geprüft: neuer Hinweis, die alte Quittierung gilt nicht
        f.fuel(CaptureFuel(quantity = dec("90")))
        val h2 = f.view().plausibility.hints.single()
        assertEquals(f.revision, h2.documentRevision); assertTrue(h2.open); assertEquals("90", h2.actualValue); assertEquals(2, w.count(doc = f.doc.documentId), "alter Hinweis bleibt als Spur erhalten")
        assertEquals(ErrorCode.CONFLICT, bf.problem { bf.svc.plausibility.acknowledge(w.fin, f.doc.documentId, h1.id, f.revision, "alter Hinweis") }.code, "Hinweis einer früheren Revision")
        // Wert wieder unter der Schwelle: die neue Revision hat keinen Hinweis
        f.fuel(CaptureFuel(quantity = dec("70"))); assertTrue(f.view().plausibility.hints.isEmpty())
    }

    @Test
    fun zwei_pruef_laeufe_ein_hinweis_je_regel() {
        repeat(4) {
            val w = World(); w.fill(1, 10000, "50"); w.fill(2, 10500, "50")
            val g = w.fill(3, 10600, "90", "2.50", card = "FALSCH")
            val before = w.count(doc = g.doc.documentId)
            val pool = Executors.newFixedThreadPool(8); val go = CountDownLatch(1)
            val runs = (1..8).map { pool.submit<Result<Int>> { go.await(); runCatching { bf.svc.plausibility.check(w.fin, g.doc.documentId).hints.size } } }
            go.countDown(); val out = runs.map { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
            assertTrue(out.all { it.isSuccess }, out.toString()); assertEquals(setOf(4), out.map { it.getOrThrow() }.toSet())
            assertEquals(before, w.count(doc = g.doc.documentId)); assertEquals(4, before)
            PlausibilityRule.entries.forEach { r -> assertEquals(1, w.count(r, g.doc.documentId), r.name) }
            assertEquals(4, bf.fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='finance.hint.raised'", w.w.tenant.value), "Entstehung je Hinweis genau einmal im Audit")
        }
    }

    @Test
    fun fehlender_vorgaenger_und_ruecklaeufiger_stand_karte_und_parameter_faelle() {
        val w = World()
        // kein Vorgänger: kein Hinweis, Vermerk "keine Vergleichsbasis"
        val first = w.fill(1, 1000, "40", card = "KARTE-1").view().plausibility
        assertTrue(first.hints.isEmpty()); assertEquals(listOf(FUEL_ODOMETER_GAP to "NO_COMPARISON_BASIS"), first.notes.map { it.rule to it.code }); assertTrue(first.notes.single().text.contains("keine Vergleichsbasis"))
        // ein Vorgänger, aber keine Verbrauchshistorie: weiter nur Vermerk
        assertEquals("NO_COMPARISON_BASIS", w.fill(2, 1500, "40").view().plausibility.notes.single().code)
        // rückläufiger Kilometerstand: stets ein Hinweis, mit Berechnungsbasis
        val back = w.fill(3, 1400, "40").view().plausibility.hints.single { it.rule == FUEL_ODOMETER_GAP }
        assertEquals("ODOMETER_DECREASING", back.basis["reason"]); assertEquals(1500, back.basis["comparison_value"]); assertEquals("1400", back.actualValue)
        // Vorgänger gleichen Stands ist kein Fortschritt
        assertEquals("ODOMETER_NO_PROGRESS", w.fill(4, 1400, "40").let { it.view().plausibility.hints.single { h -> h.rule == FUEL_ODOMETER_GAP }.basis["reason"] })
        // Parameter nicht konfiguriert: nicht stillschweigend bestanden, sondern Vermerk; rückläufiger Stand bleibt auch ohne Parameter ein Hinweis
        val bare = World(configure = false); bare.fill(1, 5000, "50", card = "K")
        val n = bare.fill(2, 4000, "200", "9.99", card = "K").view().plausibility
        assertEquals(setOf(FUEL_VOLUME, FUEL_PRICE, FUEL_CARD_MISMATCH), n.notes.filter { it.code == "PARAMETER_MISSING" }.map { it.rule }.toSet()); assertTrue(n.notes.all { it.text.contains("Parameter fehlt") || it.text.contains("Vergleichsbasis") })
        assertEquals(setOf(FUEL_ODOMETER_GAP), n.hints.map { it.rule }.toSet(), "nur der rückläufige Stand")
        // Konfiguration eines anderen Mandanten wirkt nicht
        val other = World(); other.fill(1, 1000, "40")
        assertTrue(bare.fill(3, 6000, "81").view().plausibility.hints.none { it.rule == FUEL_VOLUME }, "Schwelle des anderen Mandanten gilt hier nicht")
        // Karten-ID außerhalb des Gültigkeitsfensters: Hinweis
        bf.svc.policies.update(w.fin, UpdateFinancePolicy(fuel = FuelParameters(dec("80"), dec("10"), dec("20"), mapOf(FuelType.DIESEL to dec("1.60")), listOf(FuelCardMapping("KARTE-2", w.vehicle, LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-31"))))))
        assertTrue(FUEL_CARD_MISMATCH in w.fill(10, 9000, "40", card = "KARTE-2").rules())
    }

    @Test
    fun quittierung_ohne_begruendung_422_ohne_recht_403_audit_eintrag() {
        val w = World(); val f = w.fill(1, 1000, "85"); val hint = f.view().plausibility.hints.single()
        fun ack(c: RequestContext, reason: String, rev: Long = f.revision, h: UUID = hint.id) = bf.problem { bf.svc.plausibility.acknowledge(c, f.doc.documentId, h, rev, reason) }
        assertEquals(ErrorCode.VALIDATION_FAILED, ack(w.fin, "").code); assertEquals(ViolationCode.REQUIRED, ack(w.fin, "  ").violations.single().code)
        assertEquals(ViolationCode.TOO_SHORT, ack(w.fin, "ab").violations.single().code); assertEquals(ViolationCode.TOO_LONG, ack(w.fin, "x".repeat(1001)).violations.single().code)
        // ohne Recht: Fahrer (Erfasser) ohne Scope und Rolle, Finanzrolle ohne Scope, Dienstkonto
        assertEquals(ErrorCode.FORBIDDEN, ack(w.driver, "Begründung").code)
        assertEquals(ErrorCode.FORBIDDEN, ack(bf.financeCtx(w.w), "Begründung").code, "Finanzrolle ohne Scope expenses:review")
        assertEquals(ErrorCode.FORBIDDEN, ack(bf.serviceCtx(w.w.tenant), "Begründung").code)
        assertEquals(0, bf.fx.count("SELECT count(*) FROM taktovia.expense_hint_ack WHERE tenant_id=?", w.w.tenant.value), "abgewiesene Versuche hinterlassen nichts")
        // veraltete Revision 412, unbekannter Hinweis 404, fremder Mandant 404
        assertEquals(ErrorCode.REVISION_MISMATCH, ack(w.fin, "Begründung", rev = f.revision - 1).code); assertEquals(ErrorCode.NOT_FOUND, ack(w.fin, "Begründung", h = UUID.randomUUID()).code)
        val other = World(); assertEquals(ErrorCode.NOT_FOUND, ack(other.fin, "Begründung").code)
        // erfolgreich: Akteur, Begründung, Zeit; genau ein Audit-Eintrag; wiederholte Quittierung ist ein No-op
        bf.svc.plausibility.acknowledge(w.fin, f.doc.documentId, hint.id, f.revision, "Begründung mit Sinn")
        bf.svc.plausibility.acknowledge(w.fin, f.doc.documentId, hint.id, f.revision, "zweiter Versuch")
        assertEquals(1, bf.fx.count("SELECT count(*) FROM taktovia.expense_hint_ack WHERE tenant_id=?", w.w.tenant.value))
        assertEquals(listOf("Begründung mit Sinn"), bf.fx.strings("SELECT reason FROM taktovia.expense_hint_ack WHERE tenant_id=?", w.w.tenant.value))
        assertEquals(1, bf.fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='finance.hint.acknowledged'", w.w.tenant.value))
        assertEquals(1, bf.fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='finance.hint.raised'", w.w.tenant.value))
        // nach der Freigabe nicht mehr quittierbar
        bf.fx.exec("UPDATE taktovia.expense_document SET state='APPROVED', approved_by='user:x' WHERE id=?", f.doc.documentId)
        assertEquals(ErrorCode.IMMUTABLE_RECORD, ack(w.fin, "Begründung mit Sinn").code)
    }

    @Test
    fun parallele_quittierungen_desselben_hinweises_ergeben_genau_einen_eintrag() {
        val w = World(); val f = w.fill(1, 1000, "85"); val hint = f.view().plausibility.hints.single()
        val pool = Executors.newFixedThreadPool(6); val go = CountDownLatch(1)
        val out = (1..6).map { i -> pool.submit<Result<ExpenseView>> { go.await(); runCatching { bf.svc.plausibility.acknowledge(w.fin, f.doc.documentId, hint.id, f.revision, "Begründung $i") } } }
        go.countDown(); val res = out.map { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
        assertTrue(res.all { it.isSuccess }, res.toString()); assertEquals(1, bf.fx.count("SELECT count(*) FROM taktovia.expense_hint_ack WHERE tenant_id=?", w.w.tenant.value))
        assertEquals(1, bf.fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='finance.hint.acknowledged'", w.w.tenant.value))
    }

    @Test
    fun quittierung_parallel_zu_freigabe_nie_freigabe_mit_offenem_hinweis() {
        repeat(8) {
            val w = World(); val f = w.fill(1, 1000, "85"); w.readyForApproval(f); val hint = f.view().plausibility.hints.single()
            val pool = Executors.newFixedThreadPool(2); val go = CountDownLatch(1)
            val approve = pool.submit<Result<Unit>> { go.await(); runCatching {
                bf.fx.ctxTx.run(w.fin) { j -> bf.svc.review.requireApprovable(j, w.fin, f.doc.documentId, f.revision); check(j.update("UPDATE taktovia.expense_document SET state='IN_REVIEW' WHERE id=? AND state='STORED'", f.doc.documentId) == 1) }
            } }
            val ackRun = pool.submit<Result<Any>> { go.await(); runCatching { bf.svc.plausibility.acknowledge(w.fin, f.doc.documentId, hint.id, f.revision, "Begründung ok") } }
            go.countDown(); val a = approve.get(60, TimeUnit.SECONDS); val k = ackRun.get(60, TimeUnit.SECONDS); pool.shutdown()
            val state = bf.fx.strings("SELECT state FROM taktovia.expense_document WHERE id=?", f.doc.documentId).single()
            val acked = bf.fx.count("SELECT count(*) FROM taktovia.expense_hint_ack WHERE tenant_id=?", w.w.tenant.value)
            if (a.isSuccess) { assertEquals("IN_REVIEW", state); assertEquals(1, acked, "Freigabe nur mit quittiertem Hinweis") }
            else { assertEquals("STORED", state); assertEquals(ErrorCode.HINT_NOT_ACKNOWLEDGED, (a.exceptionOrNull() as ProblemException).code) }
            if (k.isFailure) assertEquals(ErrorCode.IMMUTABLE_RECORD, (k.exceptionOrNull() as ProblemException).code)   // Freigabe war zuerst
            assertTrue(a.isSuccess || k.isSuccess)
        }
    }
}

package de.taktovia.documentsfinance

import de.taktovia.documentsfinance.api.AssignCosts
import de.taktovia.documentsfinance.api.CaptureFuel
import de.taktovia.documentsfinance.api.CorrectExpense
import de.taktovia.documentsfinance.api.DocumentKind
import de.taktovia.documentsfinance.api.DuplicateCandidateView
import de.taktovia.documentsfinance.api.DuplicateCertainty
import de.taktovia.documentsfinance.api.DuplicateDecision
import de.taktovia.documentsfinance.api.DuplicateMatch
import de.taktovia.documentsfinance.api.DuplicateRole
import de.taktovia.documentsfinance.api.DuplicateState
import de.taktovia.documentsfinance.api.ExpenseFields
import de.taktovia.documentsfinance.api.FuelType
import de.taktovia.documentsfinance.api.ImportFile
import de.taktovia.documentsfinance.api.IntakeOutcome
import de.taktovia.documentsfinance.api.PostExpense
import de.taktovia.documentsfinance.api.PutAccountingMapping
import de.taktovia.documentsfinance.api.ReleaseBlocker
import de.taktovia.documentsfinance.api.SourceChannel
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
import org.junit.jupiter.api.Assertions.assertNull
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
 * WP-M4-32 (DOC-009, E2E-065, PH-AT-012): Dublettenerkennung und Evidenzverknüpfung. Echte Datenbank unter der Runtime-Rolle: zweimal importierter Beleg ergibt einen Hinweis und kein zweites Kostenereignis,
 * Foto und Tankkartentransaktion sind Evidenzen desselben Ereignisses, ähnliche Belege bleiben zur Prüfung offen (nie gelöscht oder zusammengeführt), kein Treffer über Mandantengrenzen, parallele Freigabe
 * identischer Belege bucht genau einmal.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DuplicateDetectionTest {
    private lateinit var bf: BelegFixture

    @BeforeAll fun start() { bf = BelegFixture(InMemoryObjectStore()) }
    @AfterAll fun stop() { bf.close() }

    private fun vehicle(t: TenantId): UUID { val id = UUID.randomUUID(); bf.fx.exec("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (?,?,'org',CAST('{}' AS jsonb))", t.value, id); return id }

    private inner class World {
        val w = bf.fx.world()
        val driver = bf.driverCtx(w)
        val fin = bf.financeCtx(w).let { RequestContext(it.actor, it.tenant, it.roles, it.scopes + setOf("expenses:review", "expenses:approve", "costs:read"), it.services, it.correlationId) }
        val vehicle = vehicle(w.tenant)
        val service = bf.serviceCtx(w.tenant)
        init {
            bf.fx.exec("INSERT INTO taktovia.fiscal_period(tenant_id,id,legal_entity_id,starts_on,ends_on) VALUES (?,?,?,DATE '2026-01-01',DATE '2027-01-01')", w.tenant.value, UUID.randomUUID(), w.legalEntity)
            bf.svc.accounting.put(fin, PutAccountingMapping(w.legalEntity, "FUEL", "6600", "1576", "1600"))
        }

        inner class Doc(val view: StoredDocumentView) {
            val id: UUID get() = view.documentId
            var revision = view.revision
            fun fields(merchant: String = "Tankstelle Nord", date: String = "2026-06-15", amount: Long = 11900, number: String? = "R-1001", currency: String = "EUR"): Doc {
                val f = mutableMapOf<String, Any?>(ExpenseFields.AMOUNT_MINOR to amount, ExpenseFields.CURRENCY to currency, ExpenseFields.DOCUMENT_DATE to date, ExpenseFields.MERCHANT to merchant,
                    ExpenseFields.VAT_AMOUNTS to listOf(mapOf("vat_rate_pct" to "19", "amount_minor" to amount * 19 / 119)))
                if (number != null) f[ExpenseFields.DOCUMENT_NUMBER] = number
                revision = bf.svc.expenses.correct(fin, id, revision, CorrectExpense("erfasst", f)).document.revision; return this
            }
            fun assign(): Doc { revision = bf.svc.costs.assign(fin, id, revision, AssignCosts(vehicleIds = setOf(vehicle))).document.revision; return this }
            fun fuel(card: String? = null, at: String = "2026-06-15T08:00:00Z", odo: Long = 1000): Doc {
                revision = bf.svc.fuel.capture(fin, id, revision, CaptureFuel(vehicle, FuelType.DIESEL, java.math.BigDecimal("40"), java.math.BigDecimal("1.65"), odo, Instant.parse(at), card)).document.revision; return this
            }
            fun view() = bf.svc.expenses.get(fin, id)
            fun candidates(): List<DuplicateCandidateView> = view().duplicates
            /** Freigabe wie im Workflow: Freigabesperre (alle Prüfungen) und Posting in einer Transaktion. */
            fun approveAndPost(by: RequestContext = fin): UUID? = bf.fx.ctxTx.run(by) { j ->
                bf.svc.review.requireApprovable(j, by, id, revision)
                check(j.update("UPDATE taktovia.expense_document SET state='APPROVED', approved_by=? WHERE id=? AND state='STORED'", "user:" + by.actor.subject, id) == 1)
                bf.svc.costEvents.postExpense(j, by, id, PostExpense("FUEL")).event.id
            }
        }

        fun plain(bytes: ByteArray = bf.uniqueJpeg(), kind: DocumentKind = DocumentKind.RECEIPT, channel: SourceChannel = SourceChannel.DISPATCH_WEB) =
            Doc(bf.upload(w, driver, bytes, channel, kind))

        fun import(bytes: ByteArray, ref: String, kind: DocumentKind = DocumentKind.FUEL_RECEIPT): Doc {
            val out = bf.svc.intake.importFile(service, ImportFile(w.legalEntity, "beleg.pdf", "application/pdf", bytes, kind, ref))
            return Doc((out as IntakeOutcome.Created).document)
        }

        fun events() = bf.fx.count("SELECT count(*) FROM taktovia.economic_event WHERE tenant_id=?", w.tenant.value)
        fun links() = bf.fx.count("SELECT count(*) FROM taktovia.evidence_link WHERE tenant_id=?", w.tenant.value)
    }

    private fun problem(f: () -> Unit): ProblemException = bf.problem(f)

    @Test
    fun doppelter_import_ergibt_hinweis_und_ein_kostenereignis() {
        val w = World(); val bytes = Samples.pdf("einmal importiert")
        val first = w.import(bytes, "lauf-1/beleg.pdf").fields().assign().fuel()
        val second = w.import(bytes, "lauf-2/beleg.pdf")                    // zweiter Import derselben Datei
        // Duplikathinweis: sicherer Treffer über den Dateihash, am neueren Beleg, sofort als Evidenz verknüpft
        val c = second.candidates().single()
        assertEquals(DuplicateRole.NEWER, c.role); assertEquals(DuplicateMatch.HASH, c.match); assertEquals(DuplicateCertainty.SAFE, c.certainty); assertEquals(DuplicateState.GLEICHE_EVIDENZ, c.state)
        assertEquals(first.id, c.otherDocumentId); assertEquals(100, c.score); assertEquals(true, c.basis!!["hash_equal"])
        assertTrue(ReleaseBlocker.DUPLICATE_EVIDENCE in second.view().blockers); assertFalse(second.view().approvable)
        assertEquals(DuplicateRole.OLDER, first.candidates().single().role, "der ältere Beleg sieht den Treffer als Gegenüber")
        // keine doppelte Kostenbuchung: der erste Beleg bucht, der zweite ist Evidenz desselben Ereignisses
        val event = first.approveAndPost(); assertNotNull(event)
        second.fields().assign().fuel()
        assertEquals(ErrorCode.DUPLICATE_EVIDENCE_LINKED, problem { second.approveAndPost() }.code)
        assertEquals(ErrorCode.DUPLICATE_EVIDENCE_LINKED, problem { bf.fx.ctxTx.run(w.fin) { j -> bf.fx.exec("UPDATE taktovia.expense_document SET state='APPROVED', approved_by='user:x' WHERE id=?", second.id); bf.svc.costEvents.postExpense(j, w.fin, second.id, PostExpense("FUEL")) } }.code, "auch das Posting weist die Evidenz ab")
        assertEquals(1, w.events()); assertEquals(10000L, bf.svc.costEvents.actualCostBasis(w.fin, w.w.legalEntity, "EUR"))
        // Evidenzverknüpfung zum selben Ereignis: PRIMARY (erster Beleg) und ADDITIONAL (Dublette)
        assertEquals(2, w.links())
        assertEquals(listOf("PRIMARY", "ADDITIONAL"), bf.fx.strings("SELECT role FROM taktovia.evidence_link WHERE tenant_id=? ORDER BY role DESC", w.w.tenant.value))
        assertEquals(1, bf.fx.count("SELECT count(DISTINCT event_id) FROM taktovia.evidence_link WHERE tenant_id=?", w.w.tenant.value))
        // Entstehung im Audit und als Ereignis; beide Belege bleiben unverändert erhalten (nie gelöscht oder zusammengeführt)
        assertEquals(1, bf.fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='finance.duplicate.detected'", w.w.tenant.value))
        assertEquals(1, bf.fx.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND event_type='expense.duplicate_detected'", w.w.tenant.value))
        assertEquals(2, bf.fx.count("SELECT count(*) FROM taktovia.expense_document WHERE tenant_id=?", w.w.tenant.value))
    }

    @Test
    fun foto_und_tankkartentransaktion_sind_evidenzen_desselben_tankereignisses() {
        // Reihenfolge 1: Foto zuerst gebucht, die Tankkartentransaktion kommt später über den Importkanal
        val w = World()
        val photo = w.plain(kind = DocumentKind.FUEL_RECEIPT, channel = SourceChannel.DRIVER_APP).fields(number = null).assign().fuel(card = "KARTE-7")
        assertTrue(photo.candidates().isEmpty())
        val event = photo.approveAndPost()
        val tx = w.import(Samples.pdf("kartenabrechnung"), "karte/2026-06/77").fields(number = null).fuel(card = "KARTE-7", odo = 1000)   // gleiche Karte, Tankzeit, Händler, Datum, Betrag
        val c = tx.candidates().single()
        assertEquals(DuplicateMatch.FACTS, c.match); assertEquals(DuplicateCertainty.SAFE, c.certainty); assertEquals(100, c.score); assertTrue((c.basis!!["matched"] as List<*>).containsAll(listOf("merchant", "document_date", "amount_minor", "currency", "transaction")))
        assertEquals(ErrorCode.DUPLICATE_EVIDENCE_LINKED, problem { tx.assign().approveAndPost() }.code)
        assertEquals(1, w.events()); assertEquals(2, w.links())
        assertEquals(setOf(event), bf.fx.strings("SELECT event_id::text FROM taktovia.evidence_link WHERE tenant_id=?", w.w.tenant.value).map { UUID.fromString(it) }.toSet(), "beide Evidenzen hängen am selben Kostenereignis")
        assertEquals(setOf("PRIMARY", "ADDITIONAL"), bf.fx.strings("SELECT role FROM taktovia.evidence_link WHERE tenant_id=?", w.w.tenant.value).toSet())
        // Reihenfolge 2: die Kartentransaktion ist die ältere und bucht; das später aufgenommene Foto wird Evidenz
        val v = World()
        val card = v.import(Samples.pdf("kartenabrechnung 2"), "karte/2026-06/78").fields(number = null).assign().fuel(card = "KARTE-8")
        val ev2 = card.approveAndPost()
        val foto = v.plain(kind = DocumentKind.FUEL_RECEIPT, channel = SourceChannel.DRIVER_APP).fields(number = null).fuel(card = "KARTE-8")
        assertEquals(DuplicateCertainty.SAFE, foto.candidates().single().certainty); assertEquals(DuplicateRole.NEWER, foto.candidates().single().role)
        assertEquals(1, v.events()); assertEquals(2, v.links()); assertEquals(setOf(ev2), bf.fx.strings("SELECT event_id::text FROM taktovia.evidence_link WHERE tenant_id=?", v.w.tenant.value).map { UUID.fromString(it) }.toSet())
    }

    @Test
    fun gleiche_fachdaten_bei_anderer_datei_ergeben_einen_treffer() {
        val w = World()
        val a = w.plain().fields(); val b = w.plain().fields()          // verschiedene Dateien, gleiche Fachdaten inklusive Belegnummer
        val c = b.candidates().single()
        assertEquals(DuplicateMatch.FACTS, c.match); assertEquals(DuplicateCertainty.SAFE, c.certainty); assertEquals(a.id, c.otherDocumentId); assertTrue(c.other!!.merchant == "tankstelle nord" && c.other!!.amountMinor == 11900L)
    }

    @Test
    fun aehnlicher_beleg_anderer_betrag_ist_moeglicher_treffer_nicht_geloescht_nicht_zusammengefuehrt() {
        val w = World()
        val a = w.plain().fields(amount = 11900).assign()
        val b = w.plain().fields(amount = 11800).assign()               // gleicher Händler, gleiches Datum, gleiche Belegnummer, anderer Betrag
        val c = b.candidates().single()
        assertEquals(DuplicateCertainty.POSSIBLE, c.certainty); assertEquals(DuplicateState.OFFEN, c.state); assertNull(c.decidedBy)
        assertTrue((c.basis!!["differing"] as List<*>).any { (it as Map<*, *>)["field"] == "amount_minor" })
        assertTrue(ReleaseBlocker.DUPLICATE_REVIEW_OPEN in b.view().blockers)
        // offener Treffer: Freigabe 409 DUPLICATE_REVIEW_REQUIRED, der ältere Beleg bucht unbeeinflusst
        assertEquals(ErrorCode.DUPLICATE_REVIEW_REQUIRED, problem { b.approveAndPost() }.code); assertEquals(0, w.events())
        assertNotNull(a.approveAndPost()); assertEquals(1, w.events())
        // Entscheidung „eigener Vorgang“: der Beleg wird eigener Kostenfall, beide Belege bleiben, zwei Ereignisse
        bf.svc.duplicates.decide(w.fin, b.id, c.id, b.revision, DuplicateDecision.EIGENER_VORGANG, "Zweite Tankung am selben Tag")
        assertEquals(DuplicateState.EIGENER_VORGANG, b.candidates().single().state)
        assertNotNull(b.approveAndPost()); assertEquals(2, w.events()); assertEquals(2, bf.fx.count("SELECT count(*) FROM taktovia.expense_document WHERE tenant_id=?", w.w.tenant.value))
        assertEquals(2, w.links(), "jeder Beleg ist PRIMARY seines eigenen Ereignisses")
        // Entscheidung „gleiche Evidenz“: der Beleg wird weitere Evidenz des Vorgangs des älteren, kein zweites Ereignis
        val x = w.plain().fields(amount = 5000, number = "R-2000").assign(); val y = w.plain().fields(amount = 5100, number = "R-2000").assign()
        val cy = y.candidates().single(); assertEquals(DuplicateState.OFFEN, cy.state)
        assertNotNull(x.approveAndPost())
        val view = bf.svc.duplicates.decide(w.fin, y.id, cy.id, y.revision, DuplicateDecision.GLEICHE_EVIDENZ, "Korrigierter Beleg derselben Tankung")
        assertEquals(DuplicateState.GLEICHE_EVIDENZ, view.duplicates.single().state); assertTrue(ReleaseBlocker.DUPLICATE_EVIDENCE in view.blockers)
        assertEquals(ErrorCode.DUPLICATE_EVIDENCE_LINKED, problem { y.approveAndPost() }.code)
        assertEquals(3, w.events()); assertEquals(4, w.links(), "x PRIMARY, y ADDITIONAL (MANUAL_SAME) am selben Ereignis")
        assertEquals(listOf("MANUAL_SAME"), bf.fx.strings("SELECT decision FROM taktovia.evidence_link WHERE tenant_id=? AND document_id=?", w.w.tenant.value, y.id))
    }

    @Test
    fun gleicher_hash_in_zweitem_mandanten_ohne_treffer_und_ohne_auskunft() {
        val a = World(); val b = World(); val bytes = Samples.pdf("gemeinsame Datei")
        val da = a.import(bytes, "x-1").fields(); val db = b.import(bytes, "x-1").fields()
        assertTrue(da.candidates().isEmpty()); assertTrue(db.candidates().isEmpty())
        assertEquals(0, bf.fx.count("SELECT count(*) FROM taktovia.duplicate_candidate WHERE tenant_id IN (?,?)", a.w.tenant.value, b.w.tenant.value))
        // auch die Prüfung liefert nichts, und ein Beleg des anderen Mandanten ist nicht erreichbar
        assertTrue(bf.svc.duplicates.check(a.fin, da.id).isEmpty()); assertEquals(ErrorCode.NOT_FOUND, problem { bf.svc.duplicates.check(b.fin, da.id) }.code)
    }

    @Test
    fun parallele_freigabe_identischer_belege_genau_ein_kostenereignis() {
        repeat(6) { round ->
            val w = World(); val bytes = Samples.pdf("parallel $round")
            // Variante je Runde: identische Datei bzw. verschiedene Datei mit gleichen Fachdaten; Felder per SQL gesetzt, damit die Erkennung erst im Freigabeschritt läuft
            val docs = (1..2).map { i -> w.plain(if (round % 2 == 0) bytes.copyOf().also { } else bf.uniqueJpeg()).assign() }
            if (round % 2 == 0) {   // identische Datei: zweites Ticket derselben Datei ist ein eigener Beleg (anderer Objektschlüssel)
                assertEquals(2, bf.fx.count("SELECT count(DISTINCT id) FROM taktovia.expense_document WHERE tenant_id=?", w.w.tenant.value))
            }
            docs.forEach { d -> bf.fx.exec("UPDATE taktovia.expense_document SET current_fields=?::jsonb, revision=revision+1 WHERE id=?",
                """{"amount_minor":11900,"currency":"EUR","document_date":"2026-06-15","merchant":"Tankstelle Nord","document_number":"R-77","vat_amounts":[{"vat_rate_pct":"19","amount_minor":1900}]}""", d.id); d.revision += 1 }
            val pool = Executors.newFixedThreadPool(2); val go = CountDownLatch(1)
            val out = docs.map { d -> pool.submit<Result<UUID?>> { go.await(); runCatching { d.approveAndPost() } } }
            go.countDown(); val res = out.map { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
            assertEquals(1, res.count { it.isSuccess }, "genau ein Beleg bucht: $res")
            assertEquals(ErrorCode.DUPLICATE_EVIDENCE_LINKED, (res.single { it.isFailure }.exceptionOrNull() as ProblemException).code)
            assertEquals(1, w.events()); assertEquals(2, w.links()); assertEquals(1, bf.fx.count("SELECT count(*) FROM taktovia.duplicate_candidate WHERE tenant_id=?", w.w.tenant.value), "ein Treffer je Paar")
            assertEquals(1, bf.fx.strings("SELECT state FROM taktovia.expense_document WHERE tenant_id=? AND state='POSTED'", w.w.tenant.value).size)
        }
    }

    @Test
    fun parallele_pruefungen_erzeugen_je_paar_genau_einen_treffer() {
        repeat(4) {
            val w = World(); val a = w.plain().fields(); val b = w.plain()
            bf.fx.exec("UPDATE taktovia.expense_document SET current_fields=?::jsonb, revision=revision+1 WHERE id=?", """{"amount_minor":11900,"currency":"EUR","document_date":"2026-06-15","merchant":"Tankstelle Nord","document_number":"R-1001"}""", b.id)
            val pool = Executors.newFixedThreadPool(8); val go = CountDownLatch(1)
            val runs = (1..8).map { i -> pool.submit<Result<Int>> { go.await(); runCatching { bf.svc.duplicates.check(w.fin, if (i % 2 == 0) a.id else b.id).size } } }
            go.countDown(); val out = runs.map { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
            assertTrue(out.all { it.isSuccess }, out.toString()); assertEquals(1, bf.fx.count("SELECT count(*) FROM taktovia.duplicate_candidate WHERE tenant_id=?", w.w.tenant.value))
            assertEquals(1, bf.fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='finance.duplicate.detected'", w.w.tenant.value))
        }
    }

    @Test
    fun entscheidung_recht_begruendung_revision_wiederholung_und_audit() {
        val w = World(); w.plain().fields(amount = 11900); val b = w.plain().fields(amount = 11800); val c = b.candidates().single()
        fun decide(ctx: RequestContext, reason: String = "Begründung ok", rev: Long = b.revision, cid: UUID = c.id, d: DuplicateDecision = DuplicateDecision.EIGENER_VORGANG) = problem { bf.svc.duplicates.decide(ctx, b.id, cid, rev, d, reason) }
        assertEquals(ErrorCode.FORBIDDEN, decide(w.driver).code); assertEquals(ErrorCode.FORBIDDEN, decide(bf.financeCtx(w.w)).code, "Finanzrolle ohne expenses:review"); assertEquals(ErrorCode.FORBIDDEN, decide(bf.serviceCtx(w.w.tenant)).code)
        assertEquals(ViolationCode.TOO_SHORT, decide(w.fin, "ab").violations.single().code); assertEquals(ViolationCode.REQUIRED, decide(w.fin, " ").violations.single().code)
        assertEquals(ErrorCode.REVISION_MISMATCH, decide(w.fin, rev = b.revision - 1).code); assertEquals(ErrorCode.NOT_FOUND, decide(w.fin, cid = UUID.randomUUID()).code)
        assertEquals(ErrorCode.NOT_FOUND, decide(World().fin).code, "fremder Mandant")
        assertEquals(DuplicateState.OFFEN, b.candidates().single().state, "abgewiesene Versuche entscheiden nichts")
        bf.svc.duplicates.decide(w.fin, b.id, c.id, b.revision, DuplicateDecision.EIGENER_VORGANG, "Begründung ok")
        bf.svc.duplicates.decide(w.fin, b.id, c.id, b.revision, DuplicateDecision.EIGENER_VORGANG, "wiederholt")   // No-op
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, decide(w.fin, d = DuplicateDecision.GLEICHE_EVIDENZ).code, "entschieden bleibt entschieden")
        val done = b.candidates().single(); assertEquals("Begründung ok", done.reason); assertNotNull(done.decidedAt)
        assertEquals(1, bf.fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='finance.duplicate.decided'", w.w.tenant.value))
        // der Fahrer (Erfasser) sieht den Treffer, aber nicht das Gegenüber
        val seen = bf.svc.expenses.get(w.driver, b.id).duplicates.single()
        assertNull(seen.otherDocumentId); assertNull(seen.basis); assertNull(seen.other); assertEquals(DuplicateState.EIGENER_VORGANG, seen.state)
    }

    @Test
    fun schwelle_waehrung_quarantaene_und_unabhaengige_belege() {
        val w = World()
        val a = w.plain().fields(amount = 11900, number = "A-1")
        // andere Währung: nie ein Treffer; anderer Händler und Betrag: kein Treffer
        assertTrue(w.plain().fields(amount = 11900, number = "A-1", currency = "CHF").candidates().isEmpty())
        assertTrue(w.plain().fields(merchant = "Bäckerei", date = "2026-06-16", amount = 450, number = "B-7").candidates().isEmpty())
        // Schwelle: Händler, Datum, Betrag, Währung gleich ohne Belegnummer = 70 Punkte; bei Schwelle 80 kein Treffer, bei 60 möglicher Treffer
        bf.svc.policies.update(w.fin, UpdateFinancePolicy(duplicateThreshold = 80))
        assertTrue(w.plain().fields(number = null).candidates().isEmpty(), "unter der Schwelle")
        bf.svc.policies.update(w.fin, UpdateFinancePolicy(duplicateThreshold = 60))
        val c = w.plain().fields(number = null, amount = 11900).candidates()
        assertTrue(c.isNotEmpty()); assertTrue(c.all { it.certainty == DuplicateCertainty.POSSIBLE || it.score >= 70 })
        // ein Beleg in Quarantäne wird nie verglichen
        bf.fx.exec("UPDATE taktovia.expense_document SET state='QUARANTINED' WHERE id=?", a.id)
        assertTrue(w.plain().fields(number = "A-1", amount = 11900, merchant = "Anderer Händler").candidates().none { it.otherDocumentId == a.id })
    }
}

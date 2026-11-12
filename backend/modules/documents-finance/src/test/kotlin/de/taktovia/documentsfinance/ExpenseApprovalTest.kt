package de.taktovia.documentsfinance

import de.taktovia.documentsfinance.api.ApprovalDecision
import de.taktovia.documentsfinance.api.ApprovalOutcome
import de.taktovia.documentsfinance.api.ApprovalStep
import de.taktovia.documentsfinance.api.AssignCosts
import de.taktovia.documentsfinance.api.CaptureFuel
import de.taktovia.documentsfinance.api.CorrectExpense
import de.taktovia.documentsfinance.api.DocumentKind
import de.taktovia.documentsfinance.api.ExpenseFields
import de.taktovia.documentsfinance.api.ExpenseStates
import de.taktovia.documentsfinance.api.ExpenseView
import de.taktovia.documentsfinance.api.ExportState
import de.taktovia.documentsfinance.api.FuelParameters
import de.taktovia.documentsfinance.api.FuelType
import de.taktovia.documentsfinance.api.PutAccountingMapping
import de.taktovia.documentsfinance.api.ReleaseBlocker
import de.taktovia.documentsfinance.api.StoredDocumentView
import de.taktovia.documentsfinance.api.UpdateFinancePolicy
import de.taktovia.platform.context.Actor
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
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
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M4-34 (DOC-010, E2E-068, E2E-064, PH-AT-019): Freigabe-Workflow mit Rollentrennung und Vier-Augen-Schwelle. Echte Datenbank unter der Runtime-Rolle: Erfassung, fachliche Prüfung, Budgetfreigabe und zweite
 * Freigabe als getrennte Schritte mit eigenem Recht und eigenem Eintrag, keine Selbstfreigabe, Blockaden mit eigenem Code, atomares Posting, Ablehnung mit Rückgabe in die Erfassung, parallele Freigaben.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExpenseApprovalTest {
    private lateinit var bf: BelegFixture

    @BeforeAll fun start() { bf = BelegFixture(InMemoryObjectStore()) }
    @AfterAll fun stop() { bf.close() }

    private fun person(t: TenantId, roles: Set<String>, scopes: Set<String>, type: ActorType = ActorType.HUMAN) =
        RequestContext(Actor(type, "https://idp.test/realms/m4", UUID.randomUUID().toString(), "sid", true), t, roles, scopes, emptySet(), UUID.randomUUID())

    private fun vehicle(t: TenantId): UUID { val id = UUID.randomUUID(); bf.fx.exec("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (?,?,'org',CAST('{}' AS jsonb))", t.value, id); return id }

    private inner class World(threshold: Long? = null) {
        val w = bf.fx.world()
        private val financeScopes = setOf("receipts:read", "receipts:write", "expenses:review", "expenses:approve", "costs:read")
        fun finance(vararg extra: String) = person(w.tenant, setOf("finance"), financeScopes + extra)
        val admin = finance()                                                   // pflegt Kontierung und Parameter
        val capturer = bf.driverCtx(w)                                          // Erfasser
        val reviewer = person(w.tenant, setOf("finance"), setOf("receipts:read", "receipts:write", "expenses:review"))   // nur fachliche Prüfung
        val approver1 = finance(); val approver2 = finance(); val approver3 = finance()
        val exporter = person(w.tenant, emptySet(), setOf("expenses:export"), ActorType.SERVICE)
        val vehicle = vehicle(w.tenant)
        init {
            bf.fx.exec("INSERT INTO taktovia.fiscal_period(tenant_id,id,legal_entity_id,starts_on,ends_on) VALUES (?,?,?,DATE '2026-01-01',DATE '2027-01-01')", w.tenant.value, UUID.randomUUID(), w.legalEntity)
            bf.svc.accounting.put(admin, PutAccountingMapping(w.legalEntity, "FUEL", "6600", "1576", "1600"))
            if (threshold != null) bf.svc.policies.update(admin, UpdateFinancePolicy(approvalSecondThresholdMinor = threshold))
        }

        inner class Doc(val creator: RequestContext, val view: StoredDocumentView) {
            val id: UUID get() = view.documentId
            var revision = view.revision
            fun fields(amount: Long = 11900, number: String = UUID.randomUUID().toString().take(8)): Doc {
                val f = mapOf(ExpenseFields.AMOUNT_MINOR to amount, ExpenseFields.CURRENCY to "EUR", ExpenseFields.DOCUMENT_DATE to "2026-06-15", ExpenseFields.MERCHANT to "Tankstelle $number", ExpenseFields.DOCUMENT_NUMBER to number,
                    ExpenseFields.VAT_AMOUNTS to listOf(mapOf("vat_rate_pct" to "19", "amount_minor" to amount * 19 / 119)))
                val current = view().fields
                revision = bf.svc.expenses.correct(admin, id, revision, CorrectExpense("erfasst", f.filter { (k, v) -> current[k] != v })).document.revision; return this   // nur geänderte Werte (ein gleicher Wert ist 422)
            }
            fun assign(): Doc { revision = bf.svc.costs.assign(admin, id, revision, AssignCosts(vehicleIds = setOf(vehicle))).document.revision; return this }
            fun fuel(): Doc { revision = bf.svc.fuel.capture(admin, id, revision, CaptureFuel(vehicle, FuelType.DIESEL, BigDecimal("40"), BigDecimal("1.65"), 1000, Instant.parse("2026-06-15T08:00:00Z"))).document.revision; return this }
            fun view(): ExpenseView = bf.svc.expenses.get(admin, id)
            fun state() = bf.fx.strings("SELECT state FROM taktovia.expense_document WHERE id=?", id).single()
            fun submit(by: RequestContext = creator): ExpenseView = bf.svc.approvals.submit(by, id, revision)
            fun review(by: RequestContext = reviewer, decision: ApprovalDecision = ApprovalDecision.APPROVED, reason: String = "Sachlich geprüft") = bf.svc.approvals.review(by, id, revision, decision, reason)
            fun approve(by: RequestContext, reason: String = "Freigegeben") = bf.svc.approvals.approve(by, id, revision, reason)
            fun steps() = bf.svc.approvals.steps(admin, id)
            fun toReview(): Doc { submit(); return this }
            fun toReviewed(): Doc { submit(); review(); return this }
        }

        /** Vollständiger, plausibler Tankbeleg in Erfassung. */
        fun doc(amount: Long = 11900, creator: RequestContext = capturer): Doc {
            val d = Doc(creator, bf.upload(w, creator, bytes = bf.uniqueJpeg(), kind = DocumentKind.FUEL_RECEIPT))
            return d.fields(amount).assign().fuel()
        }
        fun events() = bf.fx.count("SELECT count(*) FROM taktovia.economic_event WHERE tenant_id=?", w.tenant.value)
        fun journals() = bf.fx.count("SELECT count(*) FROM taktovia.journal WHERE tenant_id=?", w.tenant.value)
        fun stepCount(id: UUID? = null) = bf.fx.count("SELECT count(*) FROM taktovia.approval_step WHERE tenant_id=?" + (id?.let { " AND document_id='$it'" } ?: ""), w.tenant.value)
    }

    private fun problem(f: () -> Unit): ProblemException = bf.problem(f)

    @Test
    fun freigabe_unter_der_schwelle_bucht_genau_einmal_mit_allen_schritten() {
        val w = World(threshold = 20000)
        val d = w.doc(11900)
        d.submit(); assertEquals(ExpenseStates.IN_REVIEW, d.state())
        d.review(); assertEquals(ExpenseStates.REVIEWED, d.state())
        val out = d.approve(w.approver1)
        assertEquals(ExpenseStates.POSTED, out.state); assertNotNull(out.postingId); assertEquals(ExpenseStates.POSTED, d.state())
        assertEquals(1, w.events()); assertEquals(1, w.journals()); assertEquals(out.postingId, bf.fx.strings("SELECT id::text FROM taktovia.economic_event WHERE tenant_id=?", w.w.tenant.value).single().let { UUID.fromString(it) })
        assertEquals(10000L, bf.svc.costEvents.actualCostBasis(w.admin, w.w.legalEntity, "EUR"), "E2E-064: genau einmal gebucht")
        assertEquals("user:" + w.approver1.actor.subject, bf.fx.strings("SELECT approved_by FROM taktovia.expense_document WHERE id=?", d.id).single())
        // Erfassung, Sachprüfung, Budgetfreigabe: je ein Eintrag mit Akteur, Dokumentrevision, Entscheidung, Begründung und Zeit; dasselbe im Audit
        val steps = d.steps()
        assertEquals(listOf(ApprovalStep.CAPTURE, ApprovalStep.REVIEW, ApprovalStep.BUDGET), steps.map { it.step })
        assertEquals(listOf(w.capturer, w.reviewer, w.approver1).map { it.actor.subject }, steps.map { it.decidedBy }); assertTrue(steps.all { it.decision == ApprovalDecision.APPROVED && it.documentRevision == d.revision })
        assertEquals(listOf("Erfassung abgeschlossen", "Sachlich geprüft", "Freigegeben"), steps.map { it.reason }); assertTrue(steps.zipWithNext().all { (a, b) -> !b.decidedAt.isBefore(a.decidedAt) })
        assertEquals(3, bf.fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='finance.expense.approval_step'", w.w.tenant.value))
        assertEquals(1, bf.fx.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND event_type='expense.reviewed'", w.w.tenant.value)); assertEquals(1, bf.fx.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND event_type='expense.approved'", w.w.tenant.value))
        assertEquals(1, bf.fx.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND event_type='cost_event.posted'", w.w.tenant.value))
        // das Original bleibt unverändert, der Exportstatus ist ein eigenes Feld und bleibt offen
        assertEquals(ExportState.NOT_EXPORTED, d.view().exportState); assertEquals(d.view.sha256, d.view().document.sha256)
        // die Freigabe ist einmalig: ein zweiter Versuch bucht nicht erneut
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, problem { d.approve(w.approver2) }.code); assertEquals(1, w.events())
    }

    @Test
    fun vier_augen_ueber_der_schwelle_zweite_person_erforderlich_und_kein_export_ohne_beide_freigaben() {
        val w = World(threshold = 10000)
        val creatorAsFinance = w.finance(); val d = w.doc(11900, creator = creatorAsFinance).also { it.toReviewed() }   // 119,00 > 100,00, Erfasser mit Finanzrolle
        val first = d.approve(w.approver1)
        assertEquals(ExpenseStates.WAITING_SECOND_APPROVAL, first.state); assertNull(first.postingId); assertEquals(0, w.events()); assertEquals(0, w.journals()); assertEquals(ExpenseStates.WAITING_SECOND_APPROVAL, d.state())
        assertEquals(1, bf.fx.count("SELECT count(*) FROM taktovia.expense_document WHERE id=? AND approved_by IS NULL", d.id), "noch nicht endgültig freigegeben")
        assertEquals(1, bf.fx.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND event_type='expense.second_approval_pending'", w.w.tenant.value))
        // kein Export ohne beide Freigaben: auch mit Exportrecht nicht
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, problem { bf.svc.approvals.markExported(w.exporter, d.id) }.code)
        // Erfasser und Erstfreigeber können die zweite Freigabe nicht selbst erteilen
        assertEquals(ErrorCode.FORBIDDEN, problem { d.approve(w.approver1) }.code, "Erstfreigeber"); assertEquals(0, w.events())
        assertEquals(ErrorCode.FORBIDDEN, problem { d.approve(creatorAsFinance) }.code, "Erfasser")
        // eine andere Person erteilt die zweite Freigabe: POSTED, genau ein Ereignis
        val second = d.approve(w.approver2)
        assertEquals(ExpenseStates.POSTED, second.state); assertNotNull(second.postingId); assertEquals(1, w.events())
        assertEquals(listOf(ApprovalStep.CAPTURE, ApprovalStep.REVIEW, ApprovalStep.BUDGET, ApprovalStep.SECOND_APPROVAL), d.steps().map { it.step })
        assertEquals(listOf(w.approver1.actor.subject, w.approver2.actor.subject), d.steps().takeLast(2).map { it.decidedBy })
        // jetzt (und nur jetzt) exportierbar, mit eigenem Recht; Freigaberechte allein genügen nie
        assertEquals(ErrorCode.FORBIDDEN, problem { bf.svc.approvals.markExported(w.approver3, d.id) }.code)
        assertEquals(ExportState.EXPORTED, bf.svc.approvals.markExported(w.exporter, d.id).exportState); assertEquals(ExportState.EXPORTED, bf.svc.approvals.markExported(w.exporter, d.id).exportState, "wiederholt: No-op")
        assertEquals(1, bf.fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='finance.expense.exported'", w.w.tenant.value))
    }

    @Test
    fun betrag_exakt_auf_schwelle_ohne_zweite_stufe() {
        val w = World(threshold = 11900)
        val exact = w.doc(11900).also { it.toReviewed() }
        assertEquals(ExpenseStates.POSTED, exact.approve(w.approver1).state, "exakt auf der Schwelle: eine Freigabe genügt")
        val over = w.doc(11901).also { it.toReviewed() }
        assertEquals(ExpenseStates.WAITING_SECOND_APPROVAL, over.approve(w.approver1).state, "einen Cent darüber: zweite Stufe")
        // ohne Schwelle gibt es keine zweite Stufe
        val none = World(); val d = none.doc(99_999_999).also { it.toReviewed() }
        assertEquals(ExpenseStates.POSTED, d.approve(none.approver1).state)
    }

    @Test
    fun ohne_freigaberecht_403_und_kein_posted() {
        val w = World(); val d = w.doc().also { it.toReviewed() }
        val before = w.stepCount(d.id)
        // Erfassungs- und Prüfrecht genügen nicht; Fahrer, Finanzrolle ohne Scope, Dienstkonto, fehlende Rolle
        val denied = listOf(w.capturer, w.reviewer, person(w.w.tenant, setOf("finance"), setOf("receipts:read", "receipts:write")), person(w.w.tenant, setOf("driver"), setOf("expenses:approve", "receipts:write")),
            person(w.w.tenant, setOf("finance"), setOf("expenses:approve", "receipts:write"), ActorType.SERVICE), person(w.w.tenant, emptySet(), setOf("expenses:approve")))
        denied.forEachIndexed { i, c -> assertEquals(ErrorCode.FORBIDDEN, problem { d.approve(c) }.code, "Fall $i") }
        assertEquals(ExpenseStates.REVIEWED, d.state()); assertEquals(1, bf.fx.count("SELECT count(*) FROM taktovia.expense_document WHERE id=? AND approved_by IS NULL", d.id)); assertEquals(0, w.events()); assertEquals(0, w.journals())
        assertEquals(before, w.stepCount(d.id), "ein verweigerter Versuch schreibt keinen Schritt")
        // Ablehnung und Prüfung verlangen ihr eigenes Recht
        assertEquals(ErrorCode.FORBIDDEN, problem { bf.svc.approvals.reject(w.capturer, d.id, d.revision, "Nicht erlaubt") }.code); assertEquals(ErrorCode.FORBIDDEN, problem { bf.svc.approvals.review(w.approver1.let { person(w.w.tenant, setOf("finance"), setOf("expenses:approve")) }, d.id, d.revision, ApprovalDecision.REJECTED, "Nicht erlaubt") }.code)
        // fremder Mandant: 404 ohne Datenpreisgabe
        val other = World(); assertEquals(ErrorCode.NOT_FOUND, problem { bf.svc.approvals.approve(other.approver1, d.id, d.revision, "Fremd") }.code)
    }

    @Test
    fun selbstfreigabe_durch_den_erfasser_wird_abgewiesen() {
        val w = World(); val capturerFinance = w.finance()
        val d = w.doc(creator = capturerFinance).also { it.toReviewed() }
        assertEquals(ErrorCode.FORBIDDEN, problem { d.approve(capturerFinance) }.code); assertEquals(0, w.events())
        assertEquals(ExpenseStates.REVIEWED, d.state()); assertEquals(3 - 1, w.stepCount(d.id), "nur Erfassung und Sachprüfung")
        assertEquals(ExpenseStates.POSTED, d.approve(w.approver1).state, "eine andere Person gibt frei")
        // auch die Datenbank hält die Regel an der Anwendung vorbei
        val e = w.doc().also { it.toReviewed() }
        val ex = runCatching { bf.fx.exec("INSERT INTO taktovia.approval_step(tenant_id,id,document_id,document_revision,step,decision,decided_by,reason) VALUES (?,?,?,?,'BUDGET','APPROVED',?,'Selbst')", w.w.tenant.value, UUID.randomUUID(), e.id, e.revision, w.capturer.actor.subject) }
        assertTrue(ex.isFailure)
    }

    @Test
    fun dieselbe_person_in_beiden_schritten_auch_parallel_abgewiesen() {
        repeat(6) {
            val w = World(threshold = 10000); val d = w.doc(11900).also { it.toReviewed() }
            val pool = Executors.newFixedThreadPool(4); val go = CountDownLatch(1)
            val runs = (1..4).map { pool.submit<Result<ApprovalOutcome>> { go.await(); runCatching { d.approve(w.approver1) } } }   // dieselbe Person viermal gleichzeitig
            go.countDown(); val out = runs.map { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
            assertEquals(1, out.count { it.isSuccess }, out.toString()); assertEquals(ExpenseStates.WAITING_SECOND_APPROVAL, out.single { it.isSuccess }.getOrThrow().state)
            assertTrue(out.filter { it.isFailure }.all { (it.exceptionOrNull() as ProblemException).code == ErrorCode.FORBIDDEN }, out.toString())
            assertEquals(0, w.events()); assertEquals(ExpenseStates.WAITING_SECOND_APPROVAL, d.state())
            assertEquals(1, bf.fx.count("SELECT count(*) FROM taktovia.approval_step WHERE tenant_id=? AND step IN ('BUDGET','SECOND_APPROVAL')", w.w.tenant.value))
        }
    }

    @Test
    fun zwei_freigeber_parallel_genau_ein_posting() {
        repeat(8) {
            val w = World(); val d = w.doc().also { it.toReviewed() }
            val pool = Executors.newFixedThreadPool(2); val go = CountDownLatch(1)
            val a = pool.submit<Result<ApprovalOutcome>> { go.await(); runCatching { d.approve(w.approver1) } }
            val b = pool.submit<Result<ApprovalOutcome>> { go.await(); runCatching { d.approve(w.approver2) } }
            go.countDown(); val res = listOf(a, b).map { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
            assertEquals(1, res.count { it.isSuccess }, res.toString()); assertEquals(ErrorCode.INVALID_STATE_TRANSITION, (res.single { it.isFailure }.exceptionOrNull() as ProblemException).code)
            assertEquals(1, w.events()); assertEquals(1, w.journals()); assertEquals(1, bf.fx.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND event_type='cost_event.posted'", w.w.tenant.value))
            assertEquals(1, bf.fx.count("SELECT count(*) FROM taktovia.approval_step WHERE tenant_id=? AND step='BUDGET'", w.w.tenant.value))
        }
    }

    @Test
    fun ablehnung_fuehrt_in_erfassung_ohne_kostenereignis_und_journal() {
        val w = World()
        // Ablehnung in der fachlichen Prüfung
        val a = w.doc().also { it.toReview() }
        assertEquals(ErrorCode.VALIDATION_FAILED, problem { a.review(decision = ApprovalDecision.REJECTED, reason = "ab") }.code, "Begründung ist Pflicht")
        val back = a.review(decision = ApprovalDecision.REJECTED, reason = "Betrag unleserlich")
        assertEquals(ExpenseStates.STORED, back.document.workflowState); assertEquals(0, w.events()); assertEquals(0, w.journals())
        assertEquals(listOf(ApprovalStep.CAPTURE to ApprovalDecision.APPROVED, ApprovalStep.REVIEW to ApprovalDecision.REJECTED), a.steps().map { it.step to it.decision }); assertEquals("Betrag unleserlich", a.steps().last().reason)
        // der Erfasser kann wieder bearbeiten (neue Revision) und erneut einreichen
        a.fields(12000, "NEU-1"); a.submit(); a.review(); assertEquals(ExpenseStates.POSTED, a.approve(w.approver1).state)
        // Ablehnung in der Budgetfreigabe
        val b = w.doc().also { it.toReviewed() }
        assertEquals(ErrorCode.FORBIDDEN, problem { bf.svc.approvals.reject(w.reviewer, b.id, b.revision, "Budget überschritten") }.code)
        val r = bf.svc.approvals.reject(w.approver1, b.id, b.revision, "Budget überschritten")
        assertEquals(ExpenseStates.STORED, r.document.workflowState); assertEquals(ApprovalStep.BUDGET to ApprovalDecision.REJECTED, b.steps().last().let { it.step to it.decision })
        assertEquals(1, w.events(), "nur der Beleg a ist gebucht, b hat weder Ereignis noch Journal"); assertEquals(1, w.journals())
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, problem { bf.svc.approvals.reject(w.approver1, b.id, b.revision, "Nochmal") }.code, "in Erfassung gibt es nichts abzulehnen")
        // Ablehnung in der zweiten Freigabe
        val x = World(threshold = 1000); val c = x.doc(11900).also { it.toReviewed(); it.approve(x.approver1) }
        bf.svc.approvals.reject(x.approver2, c.id, c.revision, "Zweite Prüfung negativ"); assertEquals(ExpenseStates.STORED, c.state()); assertEquals(ApprovalStep.SECOND_APPROVAL, c.steps().last().step); assertEquals(0, x.events())
    }

    @Test
    fun ablehnung_parallel_zu_freigabe_ergibt_genau_einen_ausgang() {
        repeat(8) {
            val w = World(); val d = w.doc().also { it.toReviewed() }
            val pool = Executors.newFixedThreadPool(2); val go = CountDownLatch(1)
            val approve = pool.submit<Result<Any>> { go.await(); runCatching { d.approve(w.approver1) } }
            val reject = pool.submit<Result<Any>> { go.await(); runCatching { bf.svc.approvals.reject(w.approver2, d.id, d.revision, "Abgelehnt") } }
            go.countDown(); val a = approve.get(60, TimeUnit.SECONDS); val r = reject.get(60, TimeUnit.SECONDS); pool.shutdown()
            assertEquals(1, listOf(a, r).count { it.isSuccess }, "genau ein Ausgang: $a / $r")
            if (a.isSuccess) { assertEquals(ExpenseStates.POSTED, d.state()); assertEquals(1, w.events()) } else { assertEquals(ExpenseStates.STORED, d.state()); assertEquals(0, w.events()); assertEquals(0, w.journals()) }
        }
    }

    @Test
    fun freigabe_parallel_zu_revisionskorrektur_ergibt_412() {
        repeat(6) {
            val w = World(); val d = w.doc().also { it.toReviewed() }
            val stale = d.revision
            // Rückgabe in die Erfassung, Korrektur (neue Revision) und erneute Prüfung: eine Freigabe auf der alten Revision scheitert mit 412 und bucht nichts
            bf.svc.approvals.reject(w.approver1, d.id, d.revision, "Betrag korrigieren")
            d.fields(12000, "KORR-$it"); d.toReviewed(); assertTrue(d.revision > stale)
            assertEquals(ErrorCode.REVISION_MISMATCH, problem { bf.svc.approvals.approve(w.approver2, d.id, stale, "Alte Revision") }.code); assertEquals(0, w.events())
            // parallel: Korrektur (nur in Erfassung möglich) und Freigabe schließen sich aus; nie ein Posting auf einem Stand, den niemand geprüft hat
            val pool = Executors.newFixedThreadPool(2); val go = CountDownLatch(1)
            val approve = pool.submit<Result<Any>> { go.await(); runCatching { bf.svc.approvals.approve(w.approver2, d.id, d.revision, "Frei") } }
            val correct = pool.submit<Result<Any>> { go.await(); runCatching { bf.svc.expenses.correct(w.admin, d.id, d.revision, CorrectExpense("Betrag geändert", mapOf(ExpenseFields.AMOUNT_MINOR to 3900))) } }
            go.countDown(); val a = approve.get(60, TimeUnit.SECONDS); val c = correct.get(60, TimeUnit.SECONDS); pool.shutdown()
            assertTrue(a.isSuccess); assertTrue(c.isFailure); assertEquals(ErrorCode.IMMUTABLE_RECORD, (c.exceptionOrNull() as ProblemException).code)
            assertEquals(1, w.events()); assertEquals(12000L, bf.fx.strings("SELECT gross_minor::text FROM taktovia.economic_event WHERE tenant_id=?", w.w.tenant.value).single().toLong(), "gebucht ist der geprüfte Stand")
        }
    }

    @Test
    fun blockaden_mit_je_eigenem_stabilen_code() {
        val w = World()
        // Beleg ohne Pflichtfelder: UNCONFIRMED_FIELDS
        val raw = w.Doc(w.capturer, bf.upload(w.w, w.capturer, bytes = bf.uniqueJpeg(), kind = DocumentKind.FUEL_RECEIPT)).also { it.assign(); it.fuel(); it.submit() }
        assertEquals(ErrorCode.UNCONFIRMED_FIELDS, problem { raw.review() }.code)
        // ohne Kostenzuordnung
        val noCost = w.Doc(w.capturer, bf.upload(w.w, w.capturer, bytes = bf.uniqueJpeg(), kind = DocumentKind.FUEL_RECEIPT)).also { it.fields(); it.fuel(); it.submit() }
        assertEquals(ErrorCode.COST_ASSIGNMENT_REQUIRED, problem { noCost.review() }.code)
        // inaktives Kostenobjekt
        val inactive = w.doc().also { it.submit() }; bf.svc.costs.deactivate(w.admin, bf.svc.costs.assigned(w.admin, inactive.id).single().id)
        assertEquals(ErrorCode.COST_ASSIGNMENT_REQUIRED, problem { inactive.review() }.code)
        val w2 = World()   // frische Kostenobjekte (das Fahrzeug-Kostenobjekt von w ist deaktiviert)
        // fehlende Tankwerte
        val noFuel = w2.Doc(w2.capturer, bf.upload(w2.w, w2.capturer, bytes = bf.uniqueJpeg(), kind = DocumentKind.FUEL_RECEIPT)).also { it.fields(); it.assign(); it.submit() }
        val p = problem { noFuel.review() }; assertEquals(ErrorCode.FUEL_FIELDS_MISSING, p.code); assertEquals(5, p.violations.size)
        // offener Plausibilitätshinweis, bis er quittiert ist
        bf.svc.policies.update(w2.admin, UpdateFinancePolicy(fuel = FuelParameters(maxLiters = BigDecimal("30"))))
        val hint = w2.doc().also { it.submit() }
        assertEquals(ErrorCode.HINT_NOT_ACKNOWLEDGED, problem { hint.review() }.code)
        val h = hint.view().plausibility.hints.single(); bf.svc.plausibility.acknowledge(w2.reviewer, hint.id, h.id, hint.revision, "Großtank, Sondertour")
        hint.review(); assertEquals(ExpenseStates.REVIEWED, hint.state())
        // offene Dublette: 409; Integritätsverletzung: 409
        val orig = w2.doc(11900).also { it.toReview() }
        val twin = w2.Doc(w2.capturer, bf.upload(w2.w, w2.capturer, bytes = bf.uniqueJpeg(), kind = DocumentKind.FUEL_RECEIPT)).also { it.fields(11800, orig.view().fields[ExpenseFields.DOCUMENT_NUMBER] as String).assign().fuel() }
        bf.fx.exec("UPDATE taktovia.expense_document SET current_fields = jsonb_set(current_fields, '{merchant}', to_jsonb(?::text)), revision = revision + 1 WHERE id=?", orig.view().fields[ExpenseFields.MERCHANT] as String, twin.id); twin.revision += 1
        val dup = problem { twin.submit(); twin.review() }; assertEquals(ErrorCode.DUPLICATE_REVIEW_REQUIRED, dup.code)
        val corrupt = w2.doc().also { it.submit() }; bf.fx.exec("UPDATE taktovia.expense_document SET integrity_state='VIOLATED', integrity_checked_at=now() WHERE id=?", corrupt.id)
        assertEquals(ErrorCode.DOCUMENT_INTEGRITY_VIOLATION, problem { corrupt.review() }.code)
        // ein Beleg, der die fachliche Prüfung nicht bestanden hat, ist nie freigabefähig
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, problem { w2.doc().approve(w2.approver1) }.code, "Erfassung -> keine Freigabe ohne Sachprüfung")
        assertEquals(0, w2.events())
    }

    @Test
    fun posting_scheitert_dann_bleibt_der_beleg_unveraendert_und_ohne_schritt() {
        val w = World(); val d = w.doc().also { it.toReviewed() }
        bf.fx.exec("DELETE FROM taktovia.accounting_mapping WHERE tenant_id=?", w.w.tenant.value)   // Kontierung fehlt: das Posting wird abgewiesen
        val steps = w.stepCount(d.id)
        assertEquals(ErrorCode.CONFLICT, problem { d.approve(w.approver1) }.code)
        assertEquals(ExpenseStates.REVIEWED, d.state()); assertEquals(steps, w.stepCount(d.id), "atomar: kein Schritt, kein approved_by"); assertEquals(0, w.events()); assertEquals(0, w.journals())
        assertEquals(1, bf.fx.count("SELECT count(*) FROM taktovia.expense_document WHERE id=? AND approved_by IS NULL", d.id))
        bf.svc.accounting.put(w.admin, PutAccountingMapping(w.w.legalEntity, "FUEL", "6600", "1576", "1600"))
        assertEquals(ExpenseStates.POSTED, d.approve(w.approver1).state)
    }

    @Test
    fun einreichen_nur_in_erfassung_und_nie_fuer_dublettenbelege_und_belege_ohne_recht() {
        val w = World(); val d = w.doc()
        assertEquals(ErrorCode.REVISION_MISMATCH, problem { bf.svc.approvals.submit(w.capturer, d.id, d.revision - 1) }.code)
        assertEquals(ErrorCode.NOT_FOUND, problem { bf.svc.approvals.submit(bf.driverCtx(w.w), d.id, d.revision) }.code, "anderer Fahrer")
        assertEquals(ErrorCode.FORBIDDEN, problem { bf.svc.approvals.submit(person(w.w.tenant, setOf("driver"), emptySet()), d.id, d.revision) }.code)
        d.submit(); assertEquals(ErrorCode.INVALID_STATE_TRANSITION, problem { d.submit() }.code, "nur einmal")
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, problem { d.review(decision = ApprovalDecision.APPROVED).let { d.review() } }.code, "Prüfung nur im Zustand IN_REVIEW")
        // nach dem Einreichen ist die Erfassung gesperrt
        assertEquals(ErrorCode.IMMUTABLE_RECORD, problem { bf.svc.expenses.correct(w.admin, d.id, d.revision, CorrectExpense("zu spät", mapOf(ExpenseFields.AMOUNT_MINOR to 1))) }.code)
        assertEquals(ErrorCode.IMMUTABLE_RECORD, problem { bf.svc.costs.assign(w.admin, d.id, d.revision, AssignCosts(vehicleIds = setOf(w.vehicle))) }.code)
    }
}

package de.taktovia.documentsfinance

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.dialect.Dialects
import de.taktovia.documentsfinance.api.CorrectExpense
import de.taktovia.documentsfinance.api.DocumentState
import de.taktovia.documentsfinance.api.EditedView
import de.taktovia.documentsfinance.api.ExpenseFields
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.storage.FileObjectStore
import de.taktovia.platform.storage.ObjectKey
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M4-23 (DOC-007): das Original bleibt bytegleich, jede Korrektur ist eine eigene, unveränderliche Revision mit Bearbeiter, Zeitpunkt, Alt- und Neuwert; keine freie Bearbeitung nach Freigabe
 * oder Buchung; parallele Korrekturen derselben Revision: genau eine gewinnt. Echte Datenbank unter der Runtime-Rolle.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DocumentRevisionTest {
    private lateinit var bf: BelegFixture
    private val repo = Path.of(System.getProperty("taktovia.repoRoot"))
    private val registry = SchemaRegistry.withDefaultDialect(Dialects.getDraft202012())

    @BeforeAll fun start(@TempDir dir: Path) { bf = BelegFixture(FileObjectStore(dir)) }
    @AfterAll fun stop() { bf.close() }

    private fun objectKeyOf(id: UUID) = bf.fx.strings("SELECT object_key FROM taktovia.expense_document WHERE id=?", id).single()

    @Test
    fun korrektur_ueberschreibt_original_nie() {
        val w = bf.fx.world(); val fin = bf.financeCtx(w); val original = Samples.pdf("Tankbeleg", 600)
        val d = bf.upload(w, fin, original)
        val e1 = bf.svc.expenses.correct(fin, d.documentId, 1, CorrectExpense("Betrag erfasst", mapOf(ExpenseFields.AMOUNT_MINOR to 8900, ExpenseFields.CURRENCY to "EUR")))
        assertEquals(2, e1.document.revision); assertEquals(8900L, e1.fields[ExpenseFields.AMOUNT_MINOR])
        val e2 = bf.svc.expenses.correct(fin, d.documentId, 2, CorrectExpense("Lesefehler 89,00 statt 39,00", mapOf(ExpenseFields.AMOUNT_MINOR to 3900),
            editedView = EditedView("image/jpeg", Samples.jpeg(300, 5))))
        assertEquals(3, e2.document.revision); assertEquals(3900L, e2.fields[ExpenseFields.AMOUNT_MINOR]); assertEquals("EUR", e2.fields[ExpenseFields.CURRENCY])
        // Original: bytegleich abrufbar, Hash und Objekt unverändert, Zustand weiter STORED
        assertArrayEquals(original, bf.svc.expenses.original(fin, d.documentId))
        assertEquals(Samples.sha256(original), e2.document.sha256); assertEquals(DocumentState.STORED, e2.document.state)
        assertEquals(Samples.sha256(original), bf.fx.strings("SELECT original_sha256 FROM taktovia.expense_document WHERE id=?", d.documentId).single())
        assertArrayEquals(original, bf.store.get(ObjectKey(w.tenant, objectKeyOf(d.documentId))), "Objekt im Originalbestand unverändert")
        // alle Versionen mit Bearbeiter, Zeitpunkt, Alt- und Neuwert
        val revs = bf.svc.expenses.revisions(fin, d.documentId)
        assertEquals(listOf(1L, 2L, 3L), revs.map { it.revision })
        assertEquals(emptyMap<String, Any>(), revs[0].fields); assertEquals("CREATED", revs[0].reason)
        assertEquals(null to 8900L, revs[1].fields[ExpenseFields.AMOUNT_MINOR]!!.let { it.old to it.new })
        assertEquals(8900L to 3900L, revs[2].fields[ExpenseFields.AMOUNT_MINOR]!!.let { it.old to it.new })
        assertEquals("Lesefehler 89,00 statt 39,00", revs[2].reason)
        assertTrue(revs.all { it.editedBy == fin.actor.subject && it.editedAt.isAfter(java.time.Instant.EPOCH) }); assertTrue(revs[2].editedAt >= revs[1].editedAt)
        // bearbeitete Ansicht als eigenes Objekt, nie unter originals/
        val view = revs[2].editedViewKey!!
        assertTrue(view.startsWith("receipts/edited/") && !view.contains("originals")); assertNotNull(bf.store.get(ObjectKey(w.tenant, view)))
        assertNull(revs[1].editedViewKey)
        // Audit und Ereignis je Revision; Ereignis gegen das Schema
        assertEquals(2, bf.fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='finance.expense.revised' AND object_ref=?", w.tenant.value, "expense:${d.documentId}"))
        val payloads = bf.fx.strings("SELECT payload::text FROM taktovia.outbox WHERE tenant_id=? AND event_type='expense.revision_created' AND aggregate_id=? ORDER BY aggregate_revision", w.tenant.value, d.documentId)
        assertEquals(2, payloads.size)
        val schema = registry.getSchema(Files.readString(repo.resolve("api/events/expense.revision_created.v1.schema.json")))
        payloads.forEach { assertEquals(emptyList<Any>(), schema.validate(it, InputFormat.JSON).toList().map { m -> m.message }, it) }
        assertFalse(bf.fx.strings("SELECT redacted_changes::text FROM taktovia.audit_event WHERE action='finance.expense.revised' AND tenant_id=?", w.tenant.value).any { it.contains("8900") || it.contains("3900") }, "Audit ohne Werte")
    }

    @Test
    fun schreib_und_loeschversuche_auf_originale_und_revisionen_scheitern() {
        val w = bf.fx.world(); val fin = bf.financeCtx(w); val d = bf.upload(w, fin, Samples.pdf("fest"))
        bf.svc.expenses.correct(fin, d.documentId, 1, CorrectExpense("Betrag", mapOf(ExpenseFields.AMOUNT_MINOR to 100)))
        // Objektspeicher der Bausteine: Originale nur einmal schreibbar, nie überschreib- oder löschbar
        val store = de.taktovia.documentsfinance.api.ProtectedObjectStore(bf.store); val key = ObjectKey(w.tenant, objectKeyOf(d.documentId))
        assertEquals(ErrorCode.IMMUTABLE_RECORD, bf.problem { store.put(key, ByteArray(10), "application/pdf") }.code)
        assertEquals(ErrorCode.IMMUTABLE_RECORD, bf.problem { store.delete(key) }.code)
        assertFalse(store.putIfAbsent(key, ByteArray(10), "application/pdf"), "putIfAbsent überschreibt nicht")
        assertNotNull(store.get(key)); assertTrue(store.get(key)!!.size > 100)
        // Datenbank unter der Runtime-Rolle: Revisionen sind weder änderbar noch löschbar
        for (sql in listOf("UPDATE taktovia.document_revision SET reason='x' WHERE document_id=?", "DELETE FROM taktovia.document_revision WHERE document_id=?"))
            assertThrows<org.springframework.dao.DataAccessException>(sql) { bf.fx.tx.run(w.tenant) { j -> j.update(sql, d.documentId) } }
        assertThrows<org.springframework.dao.DataAccessException> { bf.fx.tx.run(w.tenant) { j -> j.update("UPDATE taktovia.expense_document SET original_sha256=? WHERE id=?", "b".repeat(64), d.documentId) } }
        assertThrows<org.springframework.dao.DataAccessException> { bf.fx.tx.run(w.tenant) { j -> j.update("DELETE FROM taktovia.expense_document WHERE id=?", d.documentId) } }
        assertEquals(2, bf.svc.expenses.revisions(fin, d.documentId).size)
    }

    @Test
    fun parallele_korrekturen_genau_eine_gewinnt() {
        val w = bf.fx.world(); val fin = bf.financeCtx(w); val d = bf.upload(w, fin, Samples.jpeg(seed = 31))
        val pool = Executors.newFixedThreadPool(12); val go = CountDownLatch(1)
        val results = (1..12).map { n -> pool.submit<Any> { go.await(); try { bf.svc.expenses.correct(fin, d.documentId, 1, CorrectExpense("Korrektur $n", mapOf(ExpenseFields.AMOUNT_MINOR to 1000 + n))) } catch (e: ProblemException) { e } } }
        go.countDown(); val outs = results.map { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(1, outs.count { it !is ProblemException }, "genau eine Korrektur gewinnt")
        assertTrue(outs.filterIsInstance<ProblemException>().all { it.code == ErrorCode.REVISION_MISMATCH }, "alle anderen erhalten 412")
        assertEquals(2, bf.svc.expenses.get(fin, d.documentId).document.revision)
        assertEquals(2, bf.svc.expenses.revisions(fin, d.documentId).size, "keine verlorene oder doppelte Revision")
    }

    @Test
    fun veralteter_stand_unveraenderter_wert_unbekanntes_feld_und_fehlende_angaben_werden_abgewiesen() {
        val w = bf.fx.world(); val fin = bf.financeCtx(w); val d = bf.upload(w, fin, Samples.jpeg(seed = 32))
        fun c(rev: Long, reason: String = "Grund", vararg f: Pair<String, Any?>) = bf.svc.expenses.correct(fin, d.documentId, rev, CorrectExpense(reason, f.toMap()))
        bf.svc.expenses.correct(fin, d.documentId, 1, CorrectExpense("erst", mapOf(ExpenseFields.AMOUNT_MINOR to 500)))
        assertEquals(ErrorCode.REVISION_MISMATCH, bf.problem { c(1, "alt", ExpenseFields.AMOUNT_MINOR to 600) }.code, "veralteter ETag: 412")
        assertEquals("/fields/amount_minor", bf.problem { c(2, "gleich", ExpenseFields.AMOUNT_MINOR to 500) }.violations.single().pointer, "unveränderter Wert")
        assertEquals("/fields/nonsense", bf.problem { c(2, "x", "nonsense" to 1) }.violations.single().pointer)
        assertEquals("/fields/amount_minor", bf.problem { c(2, "x", ExpenseFields.AMOUNT_MINOR to -5) }.violations.single().pointer)
        assertEquals("/fields/amount_minor", bf.problem { c(2, "x", ExpenseFields.AMOUNT_MINOR to 12.5) }.violations.single().pointer, "kein Gleitkomma")
        assertEquals("/fields/currency", bf.problem { c(2, "x", ExpenseFields.CURRENCY to "eur") }.violations.single().pointer)
        assertEquals("/fields/document_date", bf.problem { c(2, "x", ExpenseFields.DOCUMENT_DATE to "31.02.2026") }.violations.single().pointer)
        assertEquals("/reason", bf.problem { c(2, "  ", ExpenseFields.AMOUNT_MINOR to 1) }.violations.single().pointer)
        assertEquals("/fields", bf.problem { c(2) }.violations.single().pointer, "weder Feld noch Bestätigung")
        assertEquals("/confirm", bf.problem { bf.svc.expenses.correct(fin, d.documentId, 2, CorrectExpense("x", confirm = setOf(ExpenseFields.AMOUNT_MINOR))) }.violations.single().pointer, "kein OCR-Vorschlag zu bestätigen")
        assertEquals(2, bf.svc.expenses.get(fin, d.documentId).document.revision, "nach allen Fehlversuchen unverändert")
    }

    @Test
    fun freigegebener_gebuchter_und_gesperrter_beleg_ist_nicht_frei_bearbeitbar() {
        val w = bf.fx.world(); val fin = bf.financeCtx(w)
        val posted = bf.upload(w, fin, Samples.jpeg(seed = 41))
        bf.fx.exec("UPDATE taktovia.expense_document SET state='POSTED' WHERE id=?", posted.documentId)
        assertEquals(ErrorCode.IMMUTABLE_RECORD, bf.problem { bf.svc.expenses.correct(fin, posted.documentId, 1, CorrectExpense("frei", mapOf(ExpenseFields.AMOUNT_MINOR to 1))) }.code)
        val approved = bf.upload(w, fin, Samples.jpeg(seed = 42))
        bf.fx.exec("UPDATE taktovia.expense_document SET state='APPROVED' WHERE id=?", approved.documentId)
        assertEquals(ErrorCode.IMMUTABLE_RECORD, bf.problem { bf.svc.expenses.correct(fin, approved.documentId, 1, CorrectExpense("frei", mapOf(ExpenseFields.AMOUNT_MINOR to 1))) }.code)
        assertEquals("POSTED", bf.svc.expenses.get(fin, posted.documentId).document.workflowState)
        // Quarantäne (Schadsoftware): nicht bearbeitbar
        val infected = bf.upload(w, fin, Samples.pdf(de.taktovia.documentsfinance.api.EicarSignatureScanner.EICAR))
        assertEquals(DocumentState.QUARANTINE_REJECTED, infected.state)
        assertEquals(ErrorCode.CONFLICT, bf.problem { bf.svc.expenses.correct(fin, infected.documentId, 1, CorrectExpense("x", mapOf(ExpenseFields.AMOUNT_MINOR to 1))) }.code)
        assertEquals(0, bf.fx.count("SELECT count(*) FROM taktovia.document_revision WHERE document_id IN (?,?,?) AND revision > 1", posted.documentId, approved.documentId, infected.documentId))
    }

    @Test
    fun beleg_und_original_eines_fremden_mandanten_oder_akteurs_sind_nicht_erreichbar() {
        val a = bf.fx.world(); val b = bf.fx.world(); val owner = UUID.randomUUID()
        val d = bf.upload(a, bf.driverCtx(a, owner), Samples.pdf("privat"))
        val cmd = CorrectExpense("x", mapOf(ExpenseFields.AMOUNT_MINOR to 1))
        for (ctx in listOf(bf.financeCtx(b), bf.driverCtx(a), bf.driverCtx(b))) {
            assertEquals(ErrorCode.NOT_FOUND, bf.problem { bf.svc.expenses.get(ctx, d.documentId) }.code)
            assertEquals(ErrorCode.NOT_FOUND, bf.problem { bf.svc.expenses.revisions(ctx, d.documentId) }.code)
            assertEquals(ErrorCode.NOT_FOUND, bf.problem { bf.svc.expenses.original(ctx, d.documentId) }.code)
            assertEquals(ErrorCode.NOT_FOUND, bf.problem { bf.svc.expenses.correct(ctx, d.documentId, 1, cmd) }.code)
        }
        bf.svc.expenses.correct(bf.driverCtx(a, owner), d.documentId, 1, cmd)   // der Erfasser darf
        assertEquals(2, bf.svc.expenses.get(bf.financeCtx(a), d.documentId).document.revision, "Finanzrolle sieht den Stand")
    }
}

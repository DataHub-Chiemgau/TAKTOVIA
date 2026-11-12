package de.taktovia.documentsfinance

import de.taktovia.documentsfinance.api.CorrectExpense
import de.taktovia.documentsfinance.api.ExpenseFields
import de.taktovia.documentsfinance.api.ExtractedFieldKind
import de.taktovia.documentsfinance.api.FieldStatus
import de.taktovia.documentsfinance.api.FlagReason
import de.taktovia.documentsfinance.api.RecognizedLine
import de.taktovia.documentsfinance.api.ReleaseBlocker
import de.taktovia.documentsfinance.api.StoredDocumentView
import de.taktovia.documentsfinance.api.UpdateDocumentSettings
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.storage.FileObjectStore
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M4-28 (DOC-003, E2E-064, E2E-066): OCR-Ergebnisse sind unbestätigte Vorschläge. Markierung bei niedriger Konfidenz und Summenabweichung, Freigabevoraussetzung "alle Pflichtfelder bestätigt",
 * Rennen zwischen Feldkorrektur und Freigabe. Echte Datenbank unter der Runtime-Rolle; die Freigabeschritte selbst (WP-M4-34) sind hier der Aufruf von `ReceiptReview.requireApprovable`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OcrConfirmationTest {
    private val rec = ScriptedRecognizer()
    private lateinit var bf: BelegFixture

    @BeforeAll fun start(@TempDir d: Path) { bf = BelegFixture(FileObjectStore(d), rec) }
    @AfterAll fun stop() { bf.close() }

    /** Beleg mit Zeilen (Text, Konfidenz) und abgeschlossenem OCR-Lauf. */
    private fun extracted(w: UploadFixture.World, vararg text: Pair<String, Double>): StoredDocumentView {
        val fin = bf.financeCtx(w); val bytes = Samples.pdf(UUID.randomUUID().toString(), 500)
        rec.script[Samples.sha256(bytes)] = text.mapIndexed { i, (t, c) -> RecognizedLine(t, c, 1, 40.0, 780.0 - 14 * i, 300.0, 12.0) }
        val d = bf.upload(w, fin, bytes); bf.svc.extraction.process(fin, bf.svc.extraction.request(fin, d.documentId).id); return d
    }

    private fun sure(vararg t: String) = t.map { it to 0.98 }.toTypedArray()
    private val receipt = sure("Tankstelle Nord", "Datum 14.03.2026", "Beleg-Nr: T-77", "Gesamtbetrag EUR 89,00", "MwSt 19% 14,21")
    private val mandatory = setOf(ExpenseFields.AMOUNT_MINOR, ExpenseFields.CURRENCY, ExpenseFields.DOCUMENT_DATE, ExpenseFields.MERCHANT)

    private fun violationPointers(e: ProblemException) = e.violations.map { it.pointer }

    @Test
    fun niedrige_konfidenz_wird_markiert_sichere_felder_nicht() {
        val w = bf.fx.world(); val fin = bf.financeCtx(w)
        val d = extracted(w, "Tankstelle Nord" to 0.99, "Datum 14.03.2026" to 0.97, "Gesamtbetrag EUR 89,00" to 0.42)
        val p = bf.svc.extraction.latest(fin, d.documentId)!!.proposals
        assertEquals(FlagReason.LOW_CONFIDENCE, p.single { it.kind == ExtractedFieldKind.AMOUNT }.flagReason, "Betrag: 0,42 liegt unter der Schwelle")
        assertNull(p.single { it.kind == ExtractedFieldKind.DOCUMENT_DATE }.flagReason)
        assertNull(p.single { it.kind == ExtractedFieldKind.MERCHANT }.flagReason, "0,99 * 0,9 liegt über der Schwelle")
        assertNull(p.single { it.kind == ExtractedFieldKind.DOCUMENT_NUMBER }.flagReason, "nicht erkanntes Feld wird nicht markiert, es fehlt ohnehin")
        assertEquals(FlagReason.LOW_CONFIDENCE, p.single { it.kind == ExtractedFieldKind.CURRENCY }.flagReason, "die Währung stammt aus derselben unsicheren Zeile")
        assertEquals(2, bf.fx.count("SELECT count(*) FROM taktovia.extracted_field WHERE document_id=? AND flag_reason='LOW_CONFIDENCE'", d.documentId))
    }

    @Test
    fun summenabweichung_wird_markiert_und_konsistente_summe_nicht() {
        val w = bf.fx.world(); val fin = bf.financeCtx(w)
        val bad = extracted(w, *sure("Werkstatt Süd", "Datum 01.02.2026", "Netto 70,00", "MwSt 19% 13,30", "Gesamtbetrag EUR 89,00"))
        val flags = bf.svc.extraction.latest(fin, bad.documentId)!!.proposals.filter { it.flagReason != null }
        assertEquals(setOf(ExtractedFieldKind.AMOUNT, ExtractedFieldKind.NET_AMOUNT, ExtractedFieldKind.VAT_AMOUNT), flags.map { it.kind }.toSet())
        assertTrue(flags.all { it.flagReason == FlagReason.SUM_MISMATCH })
        val good = extracted(w, *sure("Werkstatt Süd", "Datum 01.02.2026", "Netto 74,79", "MwSt 19% 14,21", "Gesamtbetrag EUR 89,00"))
        assertTrue(bf.svc.extraction.latest(fin, good.documentId)!!.proposals.none { it.flagReason != null })
    }

    @Test
    fun freigabe_mit_unbestaetigtem_betrag_wird_abgewiesen() {
        val w = bf.fx.world(); val fin = bf.financeCtx(w); val d = extracted(w, *receipt)
        // Händler, Währung, Datum bestätigt; der Betrag (OCR: 89,00) bleibt ein Vorschlag
        bf.svc.expenses.correct(fin, d.documentId, 1, CorrectExpense("geprüft", confirm = setOf(ExpenseFields.MERCHANT, ExpenseFields.CURRENCY, ExpenseFields.DOCUMENT_DATE)))
        val e = bf.problem { bf.svc.review.requireApprovable(fin, d.documentId, 2) }
        assertEquals(ErrorCode.UNCONFIRMED_FIELDS, e.code); assertEquals(422, e.code.status)
        assertEquals(listOf("/fields/amount_minor"), violationPointers(e))
        val view = bf.svc.expenses.get(fin, d.documentId)
        assertEquals(listOf(ExpenseFields.AMOUNT_MINOR), view.review.unconfirmedFields); assertTrue(ReleaseBlocker.UNCONFIRMED_FIELDS in view.blockers)
        assertFalse(view.document.approvable && view.blockers.isEmpty())
        // kein Aufwand: weder Kostenereignis noch Journal, Beleg unverändert im Entwurf
        assertEquals(0, bf.fx.count("SELECT count(*) FROM taktovia.journal WHERE tenant_id=?", w.tenant.value))
        assertEquals(0, bf.fx.count("SELECT count(*) FROM taktovia.economic_event WHERE tenant_id=?", w.tenant.value))
        assertEquals("STORED", bf.svc.expenses.get(fin, d.documentId).document.workflowState)
        assertTrue(ExpenseFields.AMOUNT_MINOR !in view.fields, "der Vorschlag 89,00 ist kein Belegstand")
        // Bestätigung des Betrags (nach Korrektur auf 39,00): jetzt zulässig
        bf.svc.expenses.correct(fin, d.documentId, 2, CorrectExpense("laut Beleg 39,00", mapOf(ExpenseFields.AMOUNT_MINOR to 3900)))
        val ok = bf.svc.review.requireApprovable(fin, d.documentId, 3)
        assertEquals(3L, ok.revision)
        assertTrue(bf.svc.expenses.get(fin, d.documentId).review.ready)
        assertEquals("8900", bf.svc.extraction.latest(fin, d.documentId)!!.proposals.single { it.kind == ExtractedFieldKind.AMOUNT }.normalizedValue, "OCR-Vorschlag bleibt abrufbar")
    }

    @Test
    fun alle_pflichtfelder_bestaetigt_ist_freigabefaehig() {
        val w = bf.fx.world(); val fin = bf.financeCtx(w); val d = extracted(w, *receipt)
        val e1 = bf.problem { bf.svc.review.requireApprovable(fin, d.documentId, 1) }
        assertEquals(mandatory.map { "/fields/$it" }.toSet(), violationPointers(e1).toSet(), "ohne jede Bestätigung fehlen alle Pflichtfelder")
        bf.svc.expenses.correct(fin, d.documentId, 1, CorrectExpense("alles gelesen", confirm = mandatory))
        assertEquals(2L, bf.svc.review.requireApprovable(fin, d.documentId, 2).revision)
        assertEquals(FieldStatus.CONFIRMED, bf.svc.extraction.latest(fin, d.documentId)!!.current.single { it.kind == ExtractedFieldKind.AMOUNT }.status)
    }

    @Test
    fun manuell_erfasster_beleg_ohne_ocr_ist_nach_eingabe_der_pflichtfelder_freigabefaehig() {
        val w = bf.fx.world(); val fin = bf.financeCtx(w); val d = bf.upload(w, fin, Samples.pdf("manuell ${UUID.randomUUID()}"))
        assertEquals(ErrorCode.UNCONFIRMED_FIELDS, bf.problem { bf.svc.review.requireApprovable(fin, d.documentId, 1) }.code)
        bf.svc.expenses.correct(fin, d.documentId, 1, CorrectExpense("manuell", mapOf(ExpenseFields.AMOUNT_MINOR to 500, ExpenseFields.CURRENCY to "EUR", ExpenseFields.DOCUMENT_DATE to "2026-03-01", ExpenseFields.MERCHANT to "Handwerker")))
        bf.svc.review.requireApprovable(fin, d.documentId, 2)
    }

    @Test
    fun summenabweichung_blockiert_die_freigabe_bis_zur_korrektur() {
        val w = bf.fx.world(); val fin = bf.financeCtx(w)
        val d = extracted(w, *sure("Werkstatt Süd", "Datum 01.02.2026", "Netto 70,00", "MwSt 19% 13,30", "Gesamtbetrag EUR 89,00"))
        // alle Pflichtfelder bestätigt, aber Netto und Steuer bleiben markierte Vorschläge ohne Entscheidung
        bf.svc.expenses.correct(fin, d.documentId, 1, CorrectExpense("Pflicht", confirm = mandatory))
        val open = bf.problem { bf.svc.review.requireApprovable(fin, d.documentId, 2) }
        assertEquals(ErrorCode.UNCONFIRMED_FIELDS, open.code)
        assertEquals(setOf("/fields/net_amount_minor", "/fields/vat_amounts"), violationPointers(open).toSet())
        // Netto und Steuer werden bestätigt, die Summe stimmt weiter nicht: 70,00 + 13,30 <> 89,00
        bf.svc.expenses.correct(fin, d.documentId, 2, CorrectExpense("bestätigt", confirm = setOf(ExpenseFields.NET_AMOUNT_MINOR, ExpenseFields.VAT_AMOUNTS)))
        val mismatch = bf.problem { bf.svc.review.requireApprovable(fin, d.documentId, 3) }
        assertEquals(ErrorCode.UNCONFIRMED_FIELDS, mismatch.code); assertEquals(listOf("/fields/amount_minor"), violationPointers(mismatch))
        assertTrue(bf.svc.expenses.get(fin, d.documentId).review.sumMismatch); assertTrue(ReleaseBlocker.SUM_MISMATCH in bf.svc.expenses.get(fin, d.documentId).blockers)
        // Korrektur des Bruttos auf 83,30 löst die Abweichung
        bf.svc.expenses.correct(fin, d.documentId, 3, CorrectExpense("Brutto war 83,30", mapOf(ExpenseFields.AMOUNT_MINOR to 8330)))
        bf.svc.review.requireApprovable(fin, d.documentId, 4)
    }

    @Test
    fun markierter_vorschlag_ohne_entscheidung_blockiert_auch_ausserhalb_der_pflichtfelder() {
        val w = bf.fx.world(); val fin = bf.financeCtx(w)
        val d = extracted(w, "Kiosk" to 0.99, "Datum 01.02.2026" to 0.99, "Summe 5,00 EUR" to 0.99, "MwSt 7% 0,33" to 0.3)
        bf.svc.expenses.correct(fin, d.documentId, 1, CorrectExpense("Pflicht", confirm = mandatory))
        assertEquals(listOf("/fields/vat_amounts"), violationPointers(bf.problem { bf.svc.review.requireApprovable(fin, d.documentId, 2) }))
        bf.svc.expenses.correct(fin, d.documentId, 2, CorrectExpense("Steuer geprüft", confirm = setOf(ExpenseFields.VAT_AMOUNTS)))
        bf.svc.review.requireApprovable(fin, d.documentId, 3)
    }

    @Test
    fun pflichtfelder_je_mandant_konfigurierbar_geschuetzte_felder_nicht_entfernbar() {
        val w = bf.fx.world(); val fin = bf.financeCtx(w); val d = extracted(w, *receipt)
        val base = UpdateDocumentSettings(true, null, null, emptySet())
        assertEquals(mandatory, bf.svc.settings.get(fin).receiptMandatoryFields, "Vorgabe: die steuerlich notwendigen Felder")
        assertEquals("/receipt_mandatory_fields", bf.problem { bf.svc.settings.update(fin, base.copy(receiptMandatoryFields = setOf(ExpenseFields.AMOUNT_MINOR, ExpenseFields.CURRENCY))) }.violations.single().pointer)
        assertEquals("/receipt_mandatory_fields", bf.problem { bf.svc.settings.update(fin, base.copy(receiptMandatoryFields = mandatory + "gewicht")) }.violations.single().pointer)
        bf.svc.settings.update(fin, base.copy(receiptMandatoryFields = mandatory + ExpenseFields.DOCUMENT_NUMBER))
        bf.svc.expenses.correct(fin, d.documentId, 1, CorrectExpense("Pflicht", confirm = mandatory))
        assertEquals(listOf("/fields/document_number"), violationPointers(bf.problem { bf.svc.review.requireApprovable(fin, d.documentId, 2) }))
        bf.svc.settings.update(fin, base.copy(receiptOcrEnabled = false))   // null = Pflichtfelder unverändert
        assertEquals(mandatory + ExpenseFields.DOCUMENT_NUMBER, bf.svc.settings.get(fin).receiptMandatoryFields)
        bf.svc.expenses.correct(fin, d.documentId, 2, CorrectExpense("Nr.", confirm = setOf(ExpenseFields.DOCUMENT_NUMBER)))
        bf.svc.review.requireApprovable(fin, d.documentId, 3)
    }

    @Test
    fun rechte_mandant_revision_und_zustand_der_freigabepruefung() {
        val a = bf.fx.world(); val b = bf.fx.world(); val fin = bf.financeCtx(a); val d = extracted(a, *receipt)
        bf.svc.expenses.correct(fin, d.documentId, 1, CorrectExpense("Pflicht", confirm = mandatory))
        assertEquals(ErrorCode.NOT_FOUND, bf.problem { bf.svc.review.requireApprovable(bf.financeCtx(b), d.documentId, 2) }.code, "fremder Mandant: 404")
        assertEquals(ErrorCode.NOT_FOUND, bf.problem { bf.svc.review.state(bf.financeCtx(b), d.documentId) }.code)
        assertEquals(ErrorCode.FORBIDDEN, bf.problem { bf.svc.review.requireApprovable(bf.fx.ctx(a.tenant, roles = setOf("finance"), scopes = setOf("receipts:read")), d.documentId, 2) }.code, "ohne Schreibrecht: 403")
        assertEquals(ErrorCode.FORBIDDEN, bf.problem { bf.svc.expenses.correct(bf.fx.ctx(a.tenant, roles = setOf("finance"), scopes = setOf("receipts:read")), d.documentId, 2, CorrectExpense("x", confirm = mandatory)) }.code)
        assertEquals(ErrorCode.REVISION_MISMATCH, bf.problem { bf.svc.review.requireApprovable(fin, d.documentId, 1) }.code, "veralteter Stand: 412")
        bf.fx.exec("UPDATE taktovia.expense_document SET state='APPROVED' WHERE id=?", d.documentId)
        assertEquals(ErrorCode.CONFLICT, bf.problem { bf.svc.review.requireApprovable(fin, d.documentId, 2) }.code, "nur der Entwurf ist freigabefähig")
    }

    /** Freigabe in einer Transaktion (Prüfung unter Zeilensperre, dann Zustandswechsel) gegen eine parallele Feldkorrektur. */
    @Test
    fun korrektur_parallel_zur_freigabe_ergibt_412() {
        repeat(12) { round ->
            val w = bf.fx.world(); val fin = bf.financeCtx(w)   // je Runde ein eigener Mandant: gleiche Belegdaten in einem Mandanten wären Dubletten (WP-M4-32)
            val d = extracted(w, *receipt)
            bf.svc.expenses.correct(fin, d.documentId, 1, CorrectExpense("Pflicht $round", confirm = mandatory))   // Revision 2, alles bestätigt, Betrag 89,00
            val pool = Executors.newFixedThreadPool(2); val go = CountDownLatch(1)
            val approve = pool.submit<Any> {
                go.await()
                try {
                    bf.fx.ctxTx.run(fin) { j ->
                        bf.svc.review.requireApprovable(j, fin, d.documentId, 2)
                        Thread.sleep(40)   // Fenster, in dem die Korrektur gleichzeitig läuft
                        j.update("UPDATE taktovia.expense_document SET state='APPROVED', approved_by='checker' WHERE id=?", d.documentId)
                    }
                } catch (e: ProblemException) { e }
            }
            val correct = pool.submit<Any> {
                go.await()
                try { bf.svc.expenses.correct(fin, d.documentId, 2, CorrectExpense("Betrag korrigiert", mapOf(ExpenseFields.AMOUNT_MINOR to 3900))) } catch (e: ProblemException) { e }
            }
            go.countDown(); val a = approve.get(60, TimeUnit.SECONDS); val c = correct.get(60, TimeUnit.SECONDS); pool.shutdown()
            val approved = a !is ProblemException; val corrected = c !is ProblemException
            assertTrue(approved != corrected || (!approved && !corrected), "Freigabe und Korrektur auf derselben Revision schließen sich aus (Runde $round)")
            val state = bf.svc.expenses.get(fin, d.documentId)
            if (approved) {   // Freigabe zuerst: die Korrektur trifft den freigegebenen Beleg
                assertEquals(ErrorCode.IMMUTABLE_RECORD, (c as ProblemException).code); assertEquals(8900L, state.fields[ExpenseFields.AMOUNT_MINOR], "freigegeben wurde der bestätigte Wert")
                assertEquals(2L, state.document.revision)
            } else {          // Korrektur zuerst: die Freigabe auf der alten Revision scheitert mit 412, nichts wird freigegeben
                assertEquals(ErrorCode.REVISION_MISMATCH, (a as ProblemException).code); assertEquals(3L, state.document.revision)
                assertEquals("STORED", state.document.workflowState); assertEquals(3900L, state.fields[ExpenseFields.AMOUNT_MINOR])
            }
        }
    }
}
